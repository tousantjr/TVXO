package com.nuvio.tv.livetv.coverage

import com.nuvio.tv.livetv.coverage.GuideCoverage.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCoverageTest {

    private fun ch(key: String, group: String = "News", playlist: String = "p1", locked: Boolean = false) =
        Channel(key, "Channel $key", "g_$group", group, playlist, "Playlist $playlist", locked)

    @Test
    fun percentRoundsDownButNeverShowsZeroOrHundredWrongly() {
        assertEquals(0, GuideCoverage.percentOf(0, 0))
        assertEquals(0, GuideCoverage.percentOf(0, 10))
        assertEquals(1, GuideCoverage.percentOf(1, 1000))
        assertEquals(99, GuideCoverage.percentOf(999, 1000))
        assertEquals(66, GuideCoverage.percentOf(2, 3))
        assertEquals(100, GuideCoverage.percentOf(5, 5))
    }

    @Test
    fun emptyInputGivesEmptyReport() {
        val r = GuideCoverage.compute(emptyList(), emptySet())
        assertTrue(r.isEmpty)
        assertEquals(0, r.percent)
        assertTrue(r.groups.isEmpty() && r.missing.isEmpty() && r.guides.isEmpty())
    }

    @Test
    fun overallCountsAndDuplicateKeysCountOnce() {
        val chans = listOf(ch("a"), ch("b"), ch("c"), ch("d"), ch("a"))
        val r = GuideCoverage.compute(chans, setOf("a", "b", "c"))
        assertEquals(4, r.total)
        assertEquals(3, r.covered)
        assertEquals(1, r.missingCount)
        assertEquals(75, r.percent)
    }

    @Test
    fun groupsSortWorstFirst() {
        val chans = listOf(
            ch("n1", "News"), ch("n2", "News"),
            ch("s1", "Sports"), ch("s2", "Sports"), ch("s3", "Sports"),
            ch("k1", "Kids")
        )
        val r = GuideCoverage.compute(chans, setOf("n1", "n2", "s1", "k1"))
        assertEquals(listOf("Sports", "Kids", "News"), r.groups.map { it.name })
        val sports = r.groups.first()
        assertEquals(1, sports.covered)
        assertEquals(3, sports.total)
        assertEquals(2, sports.missing)
        assertEquals(33, sports.percent)
    }

    @Test
    fun perPlaylistTallies() {
        val chans = listOf(ch("a", playlist = "p1"), ch("b", playlist = "p1"), ch("c", playlist = "p2"))
        val r = GuideCoverage.compute(chans, setOf("a", "b"))
        assertEquals(listOf("Playlist p2", "Playlist p1"), r.playlists.map { it.name })
        assertEquals(0, r.playlists[0].percent)
        assertEquals(100, r.playlists[1].percent)
    }

    @Test
    fun missingChannelsGroupedBiggestFirstAndSortedByName() {
        val chans = listOf(ch("z", "Sports"), ch("b", "Sports"), ch("m", "News"), ch("ok", "News"))
        val r = GuideCoverage.compute(chans, setOf("ok"))
        assertEquals(listOf("Sports", "News"), r.missing.map { it.group })
        assertEquals(listOf("b", "z"), r.missing[0].channels.map { it.key })
        assertEquals("Playlist p1", r.missing[0].channels[0].playlistName)
    }

    @Test
    fun lockedGroupChannelsAreCountedButNotNamed() {
        val chans = listOf(ch("x1", "Adult", locked = true), ch("x2", "Adult", locked = true), ch("x3", "Adult", locked = true))
        val r = GuideCoverage.compute(chans, setOf("x3"))
        val adult = r.missing.single()
        assertTrue(adult.channels.isEmpty())
        assertEquals(2, adult.lockedCount)
        assertEquals(2, adult.count)
        assertEquals(3, r.total)
    }

    @Test
    fun guidesCountCoveredChannelsAndProgrammes() {
        val chans = listOf(ch("a"), ch("b"), ch("c"), ch("d", playlist = "p2"))
        val bindings = mapOf(
            "a" to ("epg1" to 100),
            "b" to ("epg1" to 50),
            "c" to ("epg2" to 10),   // bound but no listings in the window: not credited
            "d" to ("p2" to 7)
        )
        val names = mapOf("epg1" to "Paid Guide", "epg2" to "Free Guide", "epg3" to "Unused Guide", "p2" to "Playlist p2 (built-in)")
        val r = GuideCoverage.compute(chans, setOf("a", "b", "d"), bindings, names)
        assertEquals(listOf("Paid Guide", "Playlist p2 (built-in)", "Free Guide", "Unused Guide"), r.guides.map { it.name })
        assertEquals(2, r.guides[0].channels)
        assertEquals(150L, r.guides[0].programs)
        assertEquals(0, r.guides.first { it.id == "epg3" }.channels)
        assertEquals(157L, r.programs)
    }

    @Test
    fun unnamedGuideIsLabelledAsPlaylistBuiltIn() {
        val chans = listOf(ch("a", playlist = "p9"))
        val r = GuideCoverage.compute(chans, setOf("a"), mapOf("a" to ("xyz" to 3)))
        assertEquals("Playlist p9 (built-in)", r.guides.single().name)
    }
}
