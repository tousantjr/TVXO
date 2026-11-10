package com.nuvio.tv.livetv.extras

import com.nuvio.tv.livetv.sports.Sport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreAlertDetectorTest {
    private fun g(a: Int?, h: Int?, status: String?, sport: Sport = Sport.HOCKEY, live: Boolean = true, halves: Boolean = false) =
        GameSnap("e1", sport, live, a, h, status, halves)

    @Test fun `first look only sets the baseline`() {
        assertNull(ScoreAlertDetector.detect(null, g(1, 0, "2nd · 10:00"), emptySet()))
    }

    @Test fun `score change fires once`() {
        val prev = g(0, 0, "1st · 12:00")
        val cur = g(1, 0, "1st · 11:00")
        val hit = ScoreAlertDetector.detect(prev, cur, emptySet())
        assertEquals(ScoreAlertKind.SCORE_CHANGE, hit?.kind)
        assertNull(ScoreAlertDetector.detect(prev, cur, setOf(hit!!.key)))
        assertNull(ScoreAlertDetector.detect(cur, cur, emptySet()))
    }

    @Test fun `score changes can be left to Sports`() {
        assertNull(ScoreAlertDetector.detect(g(0, 0, "1st · 12:00"), g(1, 0, "1st · 11:00"), emptySet(), scoreChanges = false))
    }

    @Test fun `no alerts for games that are not live or have no scores`() {
        assertNull(ScoreAlertDetector.detect(g(0, 0, "3rd"), g(1, 0, "Final", live = false), emptySet()))
        assertNull(ScoreAlertDetector.detect(g(null, null, "1st"), g(1, null, "1st"), emptySet()))
    }

    @Test fun `close late per sport`() {
        assertTrue(ScoreAlertDetector.isCloseLate(g(2, 3, "3rd · 4:12")))
        assertFalse(ScoreAlertDetector.isCloseLate(g(1, 3, "3rd · 4:12")))
        assertFalse(ScoreAlertDetector.isCloseLate(g(2, 3, "3rd · 5:01")))
        assertFalse(ScoreAlertDetector.isCloseLate(g(2, 3, "2nd · 1:00")))
        assertTrue(ScoreAlertDetector.isCloseLate(g(20, 24, "4th · 2:00", Sport.AMERICAN_FOOTBALL)))
        assertFalse(ScoreAlertDetector.isCloseLate(g(10, 24, "4th · 2:00", Sport.AMERICAN_FOOTBALL)))
        assertTrue(ScoreAlertDetector.isCloseLate(g(100, 102, "Q4 3:30", Sport.BASKETBALL)))
        assertTrue(ScoreAlertDetector.isCloseLate(g(60, 61, "2nd · 4:00", Sport.BASKETBALL, halves = true)))
        assertFalse(ScoreAlertDetector.isCloseLate(g(60, 61, "2nd · 4:00", Sport.BASKETBALL)))
        assertTrue(ScoreAlertDetector.isCloseLate(g(1, 1, "88'", Sport.FOOTBALL)))
        assertFalse(ScoreAlertDetector.isCloseLate(g(1, 1, "70'", Sport.FOOTBALL)))
        assertTrue(ScoreAlertDetector.isCloseLate(g(3, 4, "Top 9th", Sport.BASEBALL)))
    }

    @Test fun `close late fires once and before a score change`() {
        val prev = g(1, 3, "3rd · 4:30")
        val cur = g(2, 3, "3rd · 4:00")
        val hit = ScoreAlertDetector.detect(prev, cur, emptySet())
        assertEquals(ScoreAlertKind.CLOSE_LATE, hit?.kind)
        val next = ScoreAlertDetector.detect(prev, cur, setOf(hit!!.key))
        assertEquals(ScoreAlertKind.SCORE_CHANGE, next?.kind)
    }

    @Test fun `overtime detection`() {
        assertTrue(ScoreAlertDetector.isOvertime(g(2, 2, "OT · 3:21")))
        assertTrue(ScoreAlertDetector.isOvertime(g(2, 2, "2OT 1:00", Sport.BASKETBALL)))
        assertTrue(ScoreAlertDetector.isOvertime(g(2, 2, "Shootout")))
        assertTrue(ScoreAlertDetector.isOvertime(g(4, 4, "Top 10th", Sport.BASEBALL)))
        assertFalse(ScoreAlertDetector.isOvertime(g(4, 4, "Top 9th", Sport.BASEBALL)))
        assertFalse(ScoreAlertDetector.isOvertime(g(2, 2, "OT", live = false)))
        assertFalse(ScoreAlertDetector.isOvertime(g(2, 2, "Boston Bruins")))
    }

    @Test fun `overtime fires first and only once`() {
        val prev = g(2, 2, "3rd · 0:00")
        val cur = g(2, 2, "OT · 5:00")
        val hit = ScoreAlertDetector.detect(prev, cur, emptySet())
        assertEquals(ScoreAlertKind.OVERTIME, hit?.kind)
        assertEquals("e1|ot", hit?.key)
        assertNull(ScoreAlertDetector.detect(cur, cur, setOf("e1|ot")))
    }

    @Test fun `clock and period parsing`() {
        assertEquals(252, ScoreAlertDetector.clockSeconds("4th · 4:12"))
        assertNull(ScoreAlertDetector.clockSeconds("Halftime"))
        assertEquals(3, ScoreAlertDetector.period("3rd · 1:00"))
        assertEquals(4, ScoreAlertDetector.period("Q4 0:30"))
        assertEquals(90, ScoreAlertDetector.minute("90'+3'"))
    }
}
