package com.nuvio.tv.data.tvdb

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.model.EpisodeOrderInfo
import com.nuvio.tv.domain.model.Meta
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** TVDB order option shown in the episode-order dialog. */
data class TvdbOrderOption(val type: String, val label: String)

/**
 * Finds the TVDB series for a show and applies the profile's chosen episode order to it
 * (display numbers only, see TvdbEpisodeOrdering.kt).
 */
@Singleton
class TvdbEpisodeOrderService @Inject constructor(
    private val client: TvdbClient,
    private val keys: TvdbKeyStore,
    private val choices: TvdbOrderStore,
    private val tmdb: TmdbService
) {
    private class Cached<T>(val value: T, val at: Long = System.currentTimeMillis())
    private val seriesIds = ConcurrentHashMap<String, Cached<Long?>>()
    private val episodeCache = ConcurrentHashMap<String, Cached<List<TvdbEpisode>>>()
    private val orderCache = ConcurrentHashMap<Long, Cached<List<TvdbOrder>>>()

    fun hasKey(): Boolean = keys.key() != null

    /** Ids a choice is saved under (the provider's own id and the IMDb id, so both sources share it). */
    fun aliases(meta: Meta): List<String> = listOfNotNull(meta.id, meta.imdbId).filter(String::isNotBlank).distinct()

    /** Saved choice for this show, or null for provider order. */
    suspend fun savedChoice(profileId: Int, meta: Meta): TvdbOrderChoice? = choices.choice(profileId, aliases(meta))

    suspend fun saveChoice(profileId: Int, meta: Meta, seriesId: Long, type: String?) =
        choices.save(profileId, aliases(meta), seriesId, type)

    /** TVDB series id for this show, or null when it can't be found. Throws TvdbException on API problems. */
    suspend fun seriesId(meta: Meta): Long? {
        val cacheKey = meta.id
        seriesIds[cacheKey]?.takeIf { fresh(it) }?.let { return it.value }
        val id = resolveSeriesId(meta)
        seriesIds[cacheKey] = Cached(id)
        return id
    }

    private suspend fun resolveSeriesId(meta: Meta): Long? {
        meta.id.removePrefix("tvdb:").takeIf { meta.id.startsWith("tvdb:") }?.toLongOrNull()?.let { return it }
        val imdb = listOfNotNull(meta.imdbId, meta.id.substringBefore(':'))
            .firstOrNull { it.matches(Regex("tt\\d+")) }
            ?: meta.id.takeIf { it.startsWith("tmdb:") }?.substringAfter("tmdb:")?.substringBefore(':')?.toIntOrNull()
                ?.let { runCatching { tmdb.tmdbToImdb(it, "series") }.getOrNull() }
            ?: return null
        return client.seriesByImdb(imdb)
    }

    /** Orders offered in the dialog: TVDB's aired order first, then DVD, absolute, etc. */
    suspend fun availableOrders(seriesId: Long): List<TvdbOrderOption> {
        val orders = orderCache[seriesId]?.takeIf { fresh(it) }?.value
            ?: client.orders(seriesId).also { orderCache[seriesId] = Cached(it) }
        return orders.filter { it.type != "default" }
            .sortedBy { if (it.type == AIRED) 0 else 1 }
            .map { TvdbOrderOption(it.type, labelFor(it)) }
    }

    /**
     * Returns [meta] with display numbers from the saved TVDB order, or unchanged when the profile
     * uses provider order. When the order can't be applied, the meta comes back without display
     * numbers and [Meta.episodeOrder] explains why.
     */
    suspend fun apply(profileId: Int, meta: Meta): Meta {
        val choice = runCatching { savedChoice(profileId, meta) }.getOrNull() ?: return meta
        val fallbackLabel = choice.type
        return try {
            withTimeout(TIMEOUT_MS) {
                val label = runCatching { availableOrders(choice.seriesId) }.getOrNull()
                    ?.firstOrNull { it.type == choice.type }?.label ?: fallbackLabel
                val aired = episodes(choice.seriesId, AIRED)
                val selected = if (choice.type == AIRED) aired else episodes(choice.seriesId, choice.type)
                if (selected.isEmpty()) {
                    return@withTimeout problem(meta, choice, label, "TVDB has no episodes in its $label for this show.")
                }
                when (val result = applyTvdbOrder(meta, choice.seriesId, choice.type, label, aired, selected)) {
                    is TvdbOrderingResult.Applied -> result.meta
                    is TvdbOrderingResult.Rejected -> problem(meta, choice, label, result.reason)
                }
            }
        } catch (e: TvdbException) {
            problem(meta, choice, fallbackLabel, messageFor(e.reason))
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            problem(meta, choice, fallbackLabel, "TVDB took too long to answer, so provider order is shown.")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "TVDB order failed for ${meta.id}", e)
            problem(meta, choice, fallbackLabel, "TVDB order couldn't be loaded, so provider order is shown.")
        }
    }

    private suspend fun episodes(seriesId: Long, type: String): List<TvdbEpisode> {
        val key = "$seriesId|$type"
        episodeCache[key]?.takeIf { fresh(it) }?.let { return it.value }
        return client.episodes(seriesId, type).also { episodeCache[key] = Cached(it) }
    }

    private fun problem(meta: Meta, choice: TvdbOrderChoice, label: String, reason: String) =
        meta.copy(episodeOrder = EpisodeOrderInfo(choice.seriesId, choice.type, label, problem = reason))

    private fun fresh(cached: Cached<*>) = System.currentTimeMillis() - cached.at < CACHE_MS

    companion object {
        private const val TAG = "TvdbEpisodeOrder"
        const val AIRED = "official"
        private const val TIMEOUT_MS = 15_000L
        private const val CACHE_MS = 24 * 60 * 60 * 1000L

        fun labelFor(order: TvdbOrder): String = when (order.type) {
            AIRED -> "Aired order"
            "dvd" -> "DVD order"
            "absolute" -> "Absolute order"
            "alternate" -> "Alternate order"
            "regional" -> "Regional order"
            "altdvd" -> "Alternate DVD order"
            else -> order.name.ifBlank { order.type }
        }

        fun messageFor(reason: TvdbException.Reason): String = when (reason) {
            TvdbException.Reason.NO_KEY -> "Add a TVDB API key in Settings › Integrations › TVDB to use TVDB episode order."
            TvdbException.Reason.AUTHENTICATION -> "TVDB rejected the API key. Check it in Settings › Integrations › TVDB."
            TvdbException.Reason.RATE_LIMIT -> "TVDB is limiting requests right now. Try again in a few minutes."
            TvdbException.Reason.UNAVAILABLE -> "TVDB is unavailable right now, so provider order is shown."
            TvdbException.Reason.NETWORK -> "Couldn't reach TVDB, so provider order is shown."
            TvdbException.Reason.INVALID_RESPONSE -> "TVDB sent an unexpected answer, so provider order is shown."
        }
    }
}
