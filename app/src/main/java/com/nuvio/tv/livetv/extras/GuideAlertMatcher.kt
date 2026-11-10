package com.nuvio.tv.livetv.extras

/** A show in the guide that matched one of your alert words. */
data class GuideAlert(
    val channelKey: String,
    val channelName: String,
    val title: String,
    val startMs: Long,
    val keyword: String
) {
    val key: String get() = keyOf(channelKey, startMs)

    companion object {
        fun keyOf(channelKey: String, startMs: Long) = "$channelKey|$startMs"
    }
}

/** A show to check: which channel, its title and start. */
data class GuideAlertCandidate(val channelKey: String, val channelName: String, val title: String, val startMs: Long)

/** Pure logic for Guide alerts (unit tested): word matching, when an alert is due, de-duplication. */
object GuideAlertMatcher {
    /** Fired alerts are kept until a day after their show started. */
    const val KEEP_FIRED_MS = 24 * 60 * 60_000L
    /** An alert can still show this long after the show started (the TV was busy, the app just opened). */
    const val LATE_MS = 2 * 60_000L

    /** Lowercase, letters and digits only, single spaces, padded with a space either side. */
    internal fun normalize(s: String): String {
        val sb = StringBuilder(s.length + 2).append(' ')
        var space = true
        for (ch in s.lowercase()) {
            if (ch.isLetterOrDigit()) { sb.append(ch); space = false }
            else if (!space) { sb.append(' '); space = true }
        }
        if (!space) sb.append(' ')
        return sb.toString()
    }

    /**
     * True when [keyword] appears in [title] as whole words, ignoring case and punctuation:
     * "UFC" matches "UFC 300: Prelims" but not "Bufcast"; "Lakers" matches "Lakers @ Celtics".
     */
    fun matches(title: String, keyword: String): Boolean {
        val k = normalize(keyword)
        if (k.isBlank()) return false
        return normalize(title).contains(k)
    }

    /** The first of [keywords] that [title] matches, or null. */
    fun firstMatch(title: String, keywords: List<String>): String? = keywords.firstOrNull { matches(title, it) }

    /** Due now: within [leadMinutes] before the start, up to [LATE_MS] after it. */
    fun isDue(startMs: Long, now: Long, leadMinutes: Int): Boolean =
        now >= startMs - leadMinutes.coerceAtLeast(0) * 60_000L && now < startMs + LATE_MS

    /**
     * The alerts due now, in start order: matching shows not already fired (or set as a
     * reminder, which shows its own message). The same show on several channels (same title
     * and start) alerts once.
     */
    fun dueAlerts(
        candidates: List<GuideAlertCandidate>,
        keywords: List<String>,
        leadMinutes: Int,
        now: Long,
        fired: Set<String>,
        reminderKeys: Set<String> = emptySet()
    ): List<GuideAlert> {
        if (keywords.isEmpty()) return emptyList()
        return candidates.asSequence()
            .filter { isDue(it.startMs, now, leadMinutes) }
            .filter { GuideAlert.keyOf(it.channelKey, it.startMs) !in fired && GuideAlert.keyOf(it.channelKey, it.startMs) !in reminderKeys }
            .mapNotNull { c -> firstMatch(c.title, keywords)?.let { GuideAlert(c.channelKey, c.channelName, c.title, c.startMs, it) } }
            .sortedBy { it.startMs }
            .distinctBy { normalize(it.title) to it.startMs }
            .toList()
    }

    /** Drops fired keys whose show started more than [KEEP_FIRED_MS] ago. */
    fun prune(fired: Map<String, Long>, now: Long): Map<String, Long> = fired.filterValues { now - it < KEEP_FIRED_MS }
}
