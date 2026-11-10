package com.nuvio.tv.livetv.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideAlertMatcherTest {
    private val min = 60_000L
    private val now = 1_000_000_000L

    @Test fun `matches whole words ignoring case and punctuation`() {
        assertTrue(GuideAlertMatcher.matches("NBA: Lakers @ Celtics", "lakers"))
        assertTrue(GuideAlertMatcher.matches("UFC 300: Prelims", "UFC"))
        assertTrue(GuideAlertMatcher.matches("The Late Show with Stephen Colbert", "late show"))
        assertTrue(GuideAlertMatcher.matches("Premier-League Live", "premier league"))
    }

    @Test fun `does not match inside other words`() {
        assertFalse(GuideAlertMatcher.matches("Bufcast", "UFC"))
        assertFalse(GuideAlertMatcher.matches("Lakersville Report", "Lakers"))
        assertFalse(GuideAlertMatcher.matches("Snowfall", "fall"))
    }

    @Test fun `blank keyword never matches`() {
        assertFalse(GuideAlertMatcher.matches("Anything", ""))
        assertFalse(GuideAlertMatcher.matches("Anything", "  !! "))
        assertNull(GuideAlertMatcher.firstMatch("Anything", emptyList()))
    }

    @Test fun `firstMatch returns the matching keyword`() {
        assertEquals("UFC", GuideAlertMatcher.firstMatch("UFC Fight Night", listOf("Lakers", "UFC")))
    }

    @Test fun `isDue window is lead time before to two minutes after`() {
        val start = now + 5 * min
        assertTrue(GuideAlertMatcher.isDue(start, now, 5))
        assertFalse(GuideAlertMatcher.isDue(start, now - 1, 5))
        assertTrue(GuideAlertMatcher.isDue(start, start + GuideAlertMatcher.LATE_MS - 1, 5))
        assertFalse(GuideAlertMatcher.isDue(start, start + GuideAlertMatcher.LATE_MS, 5))
        assertFalse(GuideAlertMatcher.isDue(start, now, 0))
        assertTrue(GuideAlertMatcher.isDue(start, start, 0))
    }

    private fun cand(ch: String, title: String, start: Long) = GuideAlertCandidate(ch, "Ch $ch", title, start)

    @Test fun `dueAlerts skips fired and reminder programmes`() {
        val start = now + 3 * min
        val c = listOf(cand("a", "Lakers vs Suns", start), cand("b", "Lakers Postgame", start + 1), cand("c", "Lakers Tonight", start + 2))
        val out = GuideAlertMatcher.dueAlerts(
            c, listOf("Lakers"), 5, now,
            fired = setOf(GuideAlert.keyOf("a", start)),
            reminderKeys = setOf(GuideAlert.keyOf("b", start + 1))
        )
        assertEquals(listOf("c"), out.map { it.channelKey })
    }

    @Test fun `dueAlerts de-duplicates the same show on several channels`() {
        val start = now + 2 * min
        val c = listOf(cand("a", "UFC 300", start), cand("b", "ufc 300!", start), cand("c", "UFC 300", start + min))
        val out = GuideAlertMatcher.dueAlerts(c, listOf("ufc"), 5, now, emptySet())
        assertEquals(2, out.size)
        assertEquals("a", out[0].channelKey)
        assertEquals(start + min, out[1].startMs)
    }

    @Test fun `dueAlerts never fires twice once marked`() {
        val start = now + 2 * min
        val c = listOf(cand("a", "UFC 300", start))
        val first = GuideAlertMatcher.dueAlerts(c, listOf("ufc"), 5, now, emptySet())
        assertEquals(1, first.size)
        val fired = first.map { it.key }.toSet()
        assertTrue(GuideAlertMatcher.dueAlerts(c, listOf("ufc"), 5, now + min, fired).isEmpty())
    }

    @Test fun `dueAlerts ignores shows outside the window and empty keywords`() {
        val c = listOf(cand("a", "UFC 300", now + 30 * min))
        assertTrue(GuideAlertMatcher.dueAlerts(c, listOf("ufc"), 5, now, emptySet()).isEmpty())
        assertTrue(GuideAlertMatcher.dueAlerts(listOf(cand("a", "UFC", now)), emptyList(), 5, now, emptySet()).isEmpty())
    }

    @Test fun `prune drops keys older than a day`() {
        val m = mapOf("old" to now - GuideAlertMatcher.KEEP_FIRED_MS - 1, "new" to now - min)
        assertEquals(setOf("new"), GuideAlertMatcher.prune(m, now).keys)
    }
}
