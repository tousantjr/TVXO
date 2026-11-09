package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

/** Season shown on screen (TVDB order when one is chosen, otherwise the provider's). */
val Video.shownSeason: Int? get() = displaySeason ?: season

/** Episode number shown on screen (TVDB order when one is chosen, otherwise the provider's). */
val Video.shownEpisode: Int? get() = displayEpisode ?: episode

@Immutable
data class EpisodeOrderInfo(
    val seriesId: Long,
    /** TVDB season type, e.g. "dvd", "absolute", "official". */
    val type: String,
    val label: String,
    /** Provider episodes TVDB couldn't place; they are listed under Specials. */
    val unmatched: Int = 0,
    /** Set when the chosen order couldn't be applied and provider order is shown instead. */
    val problem: String? = null
)
