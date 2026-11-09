package com.nuvio.tv.data.tvdb

import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.screens.home.CwMetaSummary
import com.nuvio.tv.ui.screens.home.CwVideoSummary
import com.nuvio.tv.ui.screens.home.resolveNextUpVideoFromMeta
import com.nuvio.tv.ui.screens.player.PlayerNextEpisodeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Next episode and Up Next follow the chosen TVDB order (display numbers), keeping provider ids. */
class TvdbNextEpisodeOrderTest {
    // Provider aired order S1E1..S1E4; DVD order swaps E2 and E3 and puts E4 first in season 2.
    private fun ep(e: Int, ds: Int?, de: Int?) = Video(
        id = "tt1:1:$e", title = "Episode title $e", released = "2020-01-0${e}", thumbnail = null,
        season = 1, episode = e, overview = null, displaySeason = ds, displayEpisode = de
    )
    private val dvd = listOf(ep(1, 1, 1), ep(2, 1, 3), ep(3, 1, 2), ep(4, 2, 1))

    @Test
    fun `player next episode follows display order`() {
        assertEquals("tt1:1:3", PlayerNextEpisodeRules.resolveNextEpisode(dvd, 1, 1)?.id)
        assertEquals("tt1:1:2", PlayerNextEpisodeRules.resolveNextEpisode(dvd, 1, 3)?.id)
        assertEquals("tt1:1:4", PlayerNextEpisodeRules.resolveNextEpisode(dvd, 1, 2)?.id)
        assertNull(PlayerNextEpisodeRules.resolveNextEpisode(dvd, 1, 4))
    }

    @Test
    fun `player keeps provider order without a TVDB order`() {
        val plain = dvd.map { it.copy(displaySeason = null, displayEpisode = null) }
        assertEquals("tt1:1:2", PlayerNextEpisodeRules.resolveNextEpisode(plain, 1, 1)?.id)
    }

    @Test
    fun `numbering then next episode works end to end`() {
        fun tv(id: Long, s: Int, n: Int, title: String) = TvdbEpisode(id, s, n, title, "2020-01-0${id}")
        val aired = (1..4).map { tv(it.toLong(), 1, it, "Episode title $it") }
        val selected = listOf(tv(1, 1, 1, "a"), tv(3, 1, 2, "c"), tv(2, 1, 3, "b"), tv(4, 2, 1, "d"))
        val plain = dvd.map { it.copy(displaySeason = null, displayEpisode = null) }
        val numbered = (numberTvdbVideos(plain, "DVD order", aired, selected) as TvdbNumbering.Numbered).videos
        assertEquals("tt1:1:3", PlayerNextEpisodeRules.resolveNextEpisode(numbered, 1, 1)?.id)
        assertEquals(1, numbered.first { it.id == "tt1:1:3" }.season)
    }

    private fun cw(videos: List<Video>) = CwMetaSummary(
        "tt1", "Series", null, null, null, null, emptyList(), null, null, null, null,
        videos.map { CwVideoSummary(it.id, it.title, it.released, null, it.season, it.episode, null,
            displaySeason = it.displaySeason, displayEpisode = it.displayEpisode) }
    )
    private fun seed(e: Int) = WatchProgress(
        "tt1", "series", "Series", null, null, null, "tt1:1:$e", 1, e, null, 100, 100, 1, progressPercent = 100f
    )

    @Test
    fun `up next follows display order and keeps provider numbers`() {
        val next = resolveNextUpVideoFromMeta(seed(1), cw(dvd), showUnairedNextUp = false)
        assertEquals("tt1:1:3", next?.id)
        assertEquals(3, next?.episode)
        assertEquals("tt1:1:4", resolveNextUpVideoFromMeta(seed(2), cw(dvd), false)?.id)
    }

    @Test
    fun `up next falls back to provider order when the seed is a TVDB special`() {
        val moved = listOf(ep(1, 0, 1), ep(2, 1, 1), ep(3, 1, 2))
        assertEquals("tt1:1:2", resolveNextUpVideoFromMeta(seed(1), cw(moved), false)?.id)
    }
}
