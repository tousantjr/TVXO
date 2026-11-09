package com.nuvio.tv.data.tvdb

import com.nuvio.tv.domain.model.EpisodeOrderInfo
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import java.text.Normalizer
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * Display-only TVDB ordering. Each provider episode is matched to a TVDB episode (via TVDB's
 * aired order, which is what most providers number by), then given that episode's number in the
 * chosen order as displaySeason/displayEpisode. The provider's season/episode/id are untouched,
 * so stream search, watch progress, Continue Watching and Trakt/Simkl/MDBList see no change.
 */

/** Below this share of matched regular episodes the order is not applied (provider order stays). */
internal const val TVDB_MIN_MATCH_SHARE = 0.7

internal sealed interface TvdbOrderingResult {
    data class Applied(val meta: Meta) : TvdbOrderingResult
    data class Rejected(val reason: String) : TvdbOrderingResult
}

internal fun applyTvdbOrder(
    meta: Meta,
    seriesId: Long,
    type: String,
    label: String,
    aired: List<TvdbEpisode>,
    selected: List<TvdbEpisode>
): TvdbOrderingResult {
    val episodes = meta.videos.filter { it.season != null && it.episode != null }
    if (episodes.isEmpty()) return TvdbOrderingResult.Rejected("This show has no numbered episodes.")
    val matches = matchProviderEpisodes(episodes, aired)
    val regular = episodes.filter { (it.season ?: 0) > 0 }
    val selectedById = selected.associateBy { it.id }
    // Every TVDB slot in the chosen order may hold one provider episode only.
    val taken = HashSet<Pair<Int, Int>>()
    val placed = HashMap<Video, TvdbEpisode>()
    for (video in episodes.sortedWith(compareBy({ it.season }, { it.episode }))) {
        val slot = matches[video]?.let { selectedById[it.id] } ?: continue
        if (taken.add(slot.season to slot.number)) placed[video] = slot
    }
    val placedRegular = regular.count { it in placed }
    if (regular.isNotEmpty() && placedRegular < regular.size * TVDB_MIN_MATCH_SHARE) {
        return TvdbOrderingResult.Rejected(
            "Only $placedRegular of ${regular.size} episodes could be matched to TVDB's $label, so provider order is shown."
        )
    }
    // Episodes TVDB couldn't place go to Specials, after any specials already there.
    var nextSpecial = placed.values.filter { it.season == 0 }.maxOfOrNull { it.number } ?: 0
    val unmatched = episodes.filter { it !in placed }.sortedWith(compareBy({ it.season }, { it.episode }))
    val unmatchedNumbers = unmatched.associateWith { ++nextSpecial }
    val videos = meta.videos.map { video ->
        val slot = placed[video]
        when {
            slot != null -> video.copy(displaySeason = slot.season, displayEpisode = slot.number)
            video in unmatchedNumbers -> video.copy(displaySeason = 0, displayEpisode = unmatchedNumbers.getValue(video))
            else -> video
        }
    }
    return TvdbOrderingResult.Applied(
        meta.copy(
            videos = videos,
            episodeOrder = EpisodeOrderInfo(seriesId, type, label, unmatched = unmatched.count { (it.season ?: 0) > 0 })
        )
    )
}

/**
 * Matches provider episodes to TVDB aired-order episodes. Same numbers count when the title or
 * air date agrees (or neither side has either); otherwise a unique title, then a unique air date.
 */
internal fun matchProviderEpisodes(videos: List<Video>, aired: List<TvdbEpisode>): Map<Video, TvdbEpisode> {
    val byCoordinates = aired.associateBy { it.season to it.number }
    val byTitle = aired.filter { normalizeTitle(it.title).isNotBlank() }.groupBy { normalizeTitle(it.title) }
    val byDate = aired.filter { it.aired != null }.groupBy { it.aired!!.take(10) }
    val used = HashSet<Long>()
    val result = LinkedHashMap<Video, TvdbEpisode>()
    fun take(video: Video, episode: TvdbEpisode?) {
        if (episode != null && used.add(episode.id)) result[video] = episode
    }
    // Pass 1: same numbers, confirmed by title or date.
    for (video in videos) {
        val candidate = byCoordinates[video.season!! to video.episode!!] ?: continue
        val title = normalizeTitle(video.title)
        val titleAgrees = title.isNotBlank() && !isGenericTitle(title) && title == normalizeTitle(candidate.title)
        val dateAgrees = datesClose(video.released, candidate.aired)
        val nothingToCompare = (title.isBlank() || isGenericTitle(title)) && video.released.isNullOrBlank()
        if (titleAgrees || dateAgrees || nothingToCompare) take(video, candidate)
    }
    // Pass 2: a unique title anywhere in the series.
    for (video in videos.filter { it !in result }) {
        val title = normalizeTitle(video.title)
        if (title.isBlank() || isGenericTitle(title)) continue
        take(video, byTitle[title]?.filter { it.id !in used }?.singleOrNull())
    }
    // Pass 3: a unique air date.
    for (video in videos.filter { it !in result }) {
        val date = video.released?.take(10) ?: continue
        take(video, byDate[date]?.filter { it.id !in used }?.singleOrNull())
    }
    return result
}

private val combiningMarks = Regex("\\p{M}")
private val punctuation = Regex("[^\\p{L}\\p{N}]+")
private val genericTitle = Regex("(?:episode|ep|e|chapter) ?\\d+")

internal fun normalizeTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .lowercase(Locale.ROOT).replace(combiningMarks, "").replace(punctuation, " ").trim()

private fun isGenericTitle(normalized: String) = genericTitle.matches(normalized)

/** Air dates within two days (time zones and premiere-vs-local dates differ by a day or so). */
private fun datesClose(a: String?, b: String?): Boolean {
    val first = a?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
    val second = b?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
    return kotlin.math.abs(ChronoUnit.DAYS.between(first, second)) <= 2
}
