package com.nuvio.tv.livetv.extras

import com.nuvio.tv.livetv.sports.Sport

/** What a score alert is about. */
enum class ScoreAlertKind { SCORE_CHANGE, CLOSE_LATE, OVERTIME }

/** One game as the score bar sees it at one moment (taken from Sports data, read only). */
data class GameSnap(
    val id: String,
    val sport: Sport,
    val live: Boolean,
    val away: Int?,
    val home: Int?,
    /** "4th · 4:12", "72'", "Top 9th", "OT · 3:21"... */
    val status: String?,
    /** Games played in two halves rather than four quarters (college basketball). */
    val halves: Boolean = false
)

data class ScoreAlertHit(val kind: ScoreAlertKind, val key: String)

/** Pure logic for score alerts (unit tested). */
object ScoreAlertDetector {
    private val CLOCK = Regex("""\b(\d{1,2}):(\d{2})\b""")
    private val ORDINAL = Regex("""\b(\d{1,2})(?:st|nd|rd|th)\b""", RegexOption.IGNORE_CASE)
    private val QUARTER = Regex("""\bQ(\d)\b""", RegexOption.IGNORE_CASE)
    private val MINUTE = Regex("""\b(\d{1,3})'""")
    private val OVERTIME = Regex("""(\b\d?OT\b|overtime|\bSO\b|shootout|extra time|\bAET\b|\bET\b|penalties)""", RegexOption.IGNORE_CASE)

    /** Seconds left on the clock in [status], or null when there's no clock. */
    fun clockSeconds(status: String?): Int? =
        CLOCK.find(status ?: return null)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }

    /** The period / quarter / inning number in [status], or null. */
    fun period(status: String?): Int? {
        val s = status ?: return null
        return ORDINAL.find(s)?.groupValues?.get(1)?.toIntOrNull() ?: QUARTER.find(s)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Soccer's match minute ("72'", "90'+3'"), or null. */
    fun minute(status: String?): Int? = MINUTE.find(status ?: return null)?.groupValues?.get(1)?.toIntOrNull()

    fun isOvertime(g: GameSnap): Boolean {
        if (!g.live) return false
        if (g.status != null && OVERTIME.containsMatchIn(g.status)) return true
        // Baseball: extra innings.
        return g.sport == Sport.BASEBALL && (period(g.status) ?: 0) >= 10
    }

    /** In the last 5 minutes (or last inning / from the 85th minute) with the teams within one score. */
    fun isCloseLate(g: GameSnap): Boolean {
        if (!g.live) return false
        val a = g.away ?: return false
        val h = g.home ?: return false
        val margin = kotlin.math.abs(a - h)
        val p = period(g.status)
        val clock = clockSeconds(g.status)
        return when (g.sport) {
            Sport.AMERICAN_FOOTBALL -> margin <= 8 && p == 4 && clock != null && clock <= 300
            Sport.BASKETBALL -> margin <= 3 && p == (if (g.halves) 2 else 4) && clock != null && clock <= 300
            Sport.HOCKEY -> margin <= 1 && p == 3 && clock != null && clock <= 300
            Sport.FOOTBALL -> margin <= 1 && (minute(g.status) ?: 0) >= 85
            Sport.BASEBALL -> margin <= 1 && p == 9
            else -> false
        }
    }

    /**
     * The alert for [cur], given the last look at the same game [prev] (null: first look, which
     * only sets the baseline) and the alert keys already shown. Overtime first, then a close
     * finish, then a score change. [scoreChanges] false leaves score changes out (Sports'
     * own Scoring Alerts already show them).
     */
    fun detect(prev: GameSnap?, cur: GameSnap, fired: Set<String>, scoreChanges: Boolean = true): ScoreAlertHit? {
        if (prev == null || !cur.live) return null
        val ot = "${cur.id}|ot"
        if (isOvertime(cur) && ot !in fired) return ScoreAlertHit(ScoreAlertKind.OVERTIME, ot)
        val close = "${cur.id}|close"
        if (isCloseLate(cur) && close !in fired) return ScoreAlertHit(ScoreAlertKind.CLOSE_LATE, close)
        if (scoreChanges && cur.away != null && cur.home != null && prev.away != null && prev.home != null &&
            (cur.away != prev.away || cur.home != prev.home)
        ) {
            val key = "${cur.id}|${cur.away}-${cur.home}"
            if (key !in fired) return ScoreAlertHit(ScoreAlertKind.SCORE_CHANGE, key)
        }
        return null
    }
}
