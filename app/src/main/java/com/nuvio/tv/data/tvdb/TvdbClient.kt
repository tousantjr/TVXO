package com.nuvio.tv.data.tvdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Adapted from Nuvio ReSeq (abbaskip, GPL-3.0): data/tvdb/TvdbClient.kt.

class TvdbException(val reason: Reason) : IOException(reason.name) {
    enum class Reason { NO_KEY, AUTHENTICATION, RATE_LIMIT, UNAVAILABLE, NETWORK, INVALID_RESPONSE }
}

data class TvdbOrder(val type: String, val name: String)

data class TvdbEpisode(
    val id: Long,
    val season: Int,
    val number: Int,
    val title: String,
    val aired: String?
)

/** TheTVDB v4 API. Only reads public series/episode metadata. */
@Singleton
class TvdbClient @Inject constructor(
    private val keys: TvdbKeyStore,
    private val http: OkHttpClient
) {
    private class Token(val key: String, val value: String, val expiresAt: Long)
    private val mutex = Mutex()
    @Volatile private var token: Token? = null
    private val json = Json { ignoreUnknownKeys = true }

    /** Logs in with the current key, so Settings can say whether it works. */
    suspend fun testConnection() = mutex.withLock {
        token = null
        withContext(Dispatchers.IO) { token() }
        Unit
    }

    /** Episode orders TVDB has for this series (aired, DVD, absolute, ...). */
    suspend fun orders(seriesId: Long): List<TvdbOrder> {
        require(seriesId > 0)
        return get("series/$seriesId/extended?short=true").objectData()["seasons"].array().mapNotNull { value ->
            val type = (value as? JsonObject)?.get("type") as? JsonObject ?: return@mapNotNull null
            val id = type["type"].string() ?: return@mapNotNull null
            TvdbOrder(id, type["name"].string() ?: id)
        }.distinctBy { it.type }
    }

    /** TVDB series id for an IMDb id, or null when TVDB has none (or more than one). */
    suspend fun seriesByImdb(imdbId: String): Long? {
        require(imdbId.matches(Regex("tt\\d+")))
        return get("search/remoteid/$imdbId")["data"].array()
            .mapNotNull { ((it as? JsonObject)?.get("series") as? JsonObject)?.get("id").long() }
            .distinct().singleOrNull()
    }

    /** Every episode in one order. All pages must load; a partial order is never returned. */
    suspend fun episodes(seriesId: Long, order: String): List<TvdbEpisode> {
        require(seriesId > 0 && order.matches(Regex("[a-zA-Z0-9_-]+")))
        val result = mutableListOf<TvdbEpisode>()
        var page = 0
        while (true) {
            val response = get("series/$seriesId/episodes/$order?page=$page")
            val rows = response.objectData()["episodes"] as? JsonArray
                ?: throw TvdbException(TvdbException.Reason.INVALID_RESPONSE)
            result += rows.mapNotNull { (it as? JsonObject)?.let(::parseTvdbEpisode) }
            if ((response["links"] as? JsonObject)?.get("next").string() == null) break
            if (rows.isEmpty() || ++page > 200) throw TvdbException(TvdbException.Reason.INVALID_RESPONSE)
        }
        return result.distinctBy { it.id }
    }

    private suspend fun get(path: String): JsonObject = mutex.withLock {
        withContext(Dispatchers.IO) {
            repeat(2) { attempt ->
                val current = token()
                val request = Request.Builder().url("$BASE/$path")
                    .header("Authorization", "Bearer ${current.value}").build()
                val (code, body) = execute(request)
                if (code == 401 && attempt == 0) {
                    token = null
                } else {
                    checkStatus(code)
                    return@withContext decode(body)
                }
            }
            throw TvdbException(TvdbException.Reason.AUTHENTICATION)
        }
    }

    private suspend fun token(): Token {
        val key = keys.key() ?: throw TvdbException(TvdbException.Reason.NO_KEY)
        token?.takeIf { it.key == key && it.expiresAt > System.currentTimeMillis() }?.let { return it }
        val body = buildJsonObject { put("apikey", key) }.toString().toRequestBody("application/json".toMediaType())
        val (code, responseBody) = execute(Request.Builder().url("$BASE/login").post(body).build())
        checkStatus(code)
        val value = decode(responseBody).objectData()["token"].string()
            ?: throw TvdbException(TvdbException.Reason.INVALID_RESPONSE)
        // Tokens last a month; refresh well before that.
        return Token(key, value, System.currentTimeMillis() + 20L * 24 * 60 * 60 * 1000).also { token = it }
    }

    /** Cancelling the coroutine cancels the socket too. */
    private suspend fun execute(request: Request): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(TvdbException(TvdbException.Reason.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { it.code to (it.body?.string() ?: "") }
                } catch (e: Exception) {
                    continuation.resumeWithException(TvdbException(TvdbException.Reason.NETWORK))
                    return
                }
                continuation.resume(result)
            }
        })
    }

    private fun decode(body: String): JsonObject = try { json.parseToJsonElement(body).jsonObject }
        catch (_: Exception) { throw TvdbException(TvdbException.Reason.INVALID_RESPONSE) }

    private fun checkStatus(code: Int) {
        if (code in 200..299) return
        throw TvdbException(when (code) {
            401, 403 -> TvdbException.Reason.AUTHENTICATION
            404 -> TvdbException.Reason.UNAVAILABLE
            429 -> TvdbException.Reason.RATE_LIMIT
            else -> TvdbException.Reason.NETWORK
        })
    }

    private companion object { const val BASE = "https://api4.thetvdb.com/v4" }
}

internal fun parseTvdbEpisode(row: JsonObject): TvdbEpisode? {
    val id = row["id"].long()?.takeIf { it > 0 } ?: return null
    val season = row["seasonNumber"].long()?.toInt()?.takeIf { it >= 0 } ?: return null
    val number = row["number"].long()?.toInt()?.takeIf { it > 0 } ?: return null
    return TvdbEpisode(id, season, number, row["name"].string().orEmpty(), row["aired"].string())
}

private fun JsonObject.objectData(): JsonObject = this["data"] as? JsonObject
    ?: throw TvdbException(TvdbException.Reason.INVALID_RESPONSE)
private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
private fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray).orEmpty()
