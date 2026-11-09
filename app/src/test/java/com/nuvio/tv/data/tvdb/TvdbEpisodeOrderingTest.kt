package com.nuvio.tv.data.tvdb

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.shownEpisode
import com.nuvio.tv.domain.model.shownSeason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvdbEpisodeOrderingTest {
    private fun video(season: Int, episode: Int, title: String, released: String? = null) =
        Video(id = "tt0303461:$season:$episode", title = title, released = released, thumbnail = null,
            season = season, episode = episode, overview = null)

    private fun meta(videos: List<Video>) = Meta(
        id = "tt0303461", type = ContentType.SERIES, name = "Firefly", poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList(),
        runtime = null, director = emptyList(), cast = emptyList(), videos = videos, country = null, awards = null,
        language = null, links = emptyList()
    )

    // Firefly: the network aired episodes out of order; the DVD order puts the pilot first.
    private val titles = listOf("The Train Job", "Bushwhacked", "Our Mrs. Reynolds", "Jaynestown", "Serenity")
    private val aired = titles.mapIndexed { i, t -> TvdbEpisode(100L + i, 1, i + 1, t, "2002-09-${20 + i}") }
    private val dvdNumbers = mapOf("Serenity" to 1, "The Train Job" to 2, "Bushwhacked" to 3, "Our Mrs. Reynolds" to 6, "Jaynestown" to 7)
    private val dvd = aired.map { it.copy(number = dvdNumbers.getValue(it.title)) }

    @Test
    fun dvdOrderRenumbersForDisplayOnly() {
        val provider = titles.mapIndexed { i, t -> video(1, i + 1, t) }
        val result = applyTvdbOrder(meta(provider), 7L, "dvd", "DVD order", aired, dvd) as TvdbOrderingResult.Applied
        val serenity = result.meta.videos.single { it.title == "Serenity" }
        assertEquals(1, serenity.shownEpisode)
        assertEquals(5, serenity.episode) // provider number kept for streams and progress
        assertEquals("tt0303461:1:5", serenity.id)
        assertEquals(6, result.meta.videos.single { it.title == "Our Mrs. Reynolds" }.shownEpisode)
        assertEquals("dvd", result.meta.episodeOrder?.type)
        assertEquals(0, result.meta.episodeOrder?.unmatched)
    }

    @Test
    fun matchesByTitleWhenProviderNumbersDiffer() {
        // Provider already numbers in a different order than TVDB aired.
        val provider = listOf(video(1, 1, "Serenity"), video(1, 2, "The Train Job"), video(1, 3, "Bushwhacked"),
            video(1, 4, "Our Mrs. Reynolds"), video(1, 5, "Jaynestown"))
        val matches = matchProviderEpisodes(provider, aired)
        assertEquals(5, matches.size)
        assertEquals(104L, matches.getValue(provider[0]).id)
    }

    @Test
    fun matchesByAirDateWithoutTitles() {
        val provider = listOf(video(2, 9, "Episode 9", "2002-09-24"))
        assertEquals(104L, matchProviderEpisodes(provider, aired).values.single().id)
    }

    @Test
    fun rejectsWhenTooFewEpisodesMatch() {
        val provider = (1..5).map { video(3, it, "Unrelated $it", "2010-01-0$it") }
        val result = applyTvdbOrder(meta(provider), 7L, "dvd", "DVD order", aired, dvd)
        assertTrue(result is TvdbOrderingResult.Rejected)
    }

    @Test
    fun unmatchedEpisodesGoToSpecials() {
        val provider = titles.mapIndexed { i, t -> video(1, i + 1, t) } + video(1, 6, "Bonus Feature", "2003-12-01")
        val result = applyTvdbOrder(meta(provider), 7L, "dvd", "DVD order", aired, dvd) as TvdbOrderingResult.Applied
        val bonus = result.meta.videos.single { it.title == "Bonus Feature" }
        assertEquals(0, bonus.shownSeason)
        assertEquals(1, bonus.shownEpisode)
        assertEquals(1, result.meta.episodeOrder?.unmatched)
    }

    @Test
    fun providerOrderWhenNoDisplayNumbers() {
        val plain = video(2, 3, "x")
        assertNull(plain.displaySeason)
        assertEquals(2, plain.shownSeason)
        assertEquals(3, plain.shownEpisode)
    }
}
