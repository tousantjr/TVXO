package com.nuvio.tv.livetv.coverage

/**
 * Guide Coverage: how many of your live channels have TV guide (EPG) listings, by group, by
 * playlist and by guide. Pure logic (no Android), so it is unit tested; the screen feeds it.
 */
object GuideCoverage {

    /** How far ahead a channel needs listings to count as covered. */
    const val WINDOW_MS = 24L * 60 * 60 * 1000

    /** A channel that counts (enabled playlist, not hidden everywhere). */
    data class Channel(
        val key: String,
        val name: String,
        val groupId: String,
        val group: String,
        val playlistId: String,
        val playlistName: String,
        /** In a group that's PIN-locked right now: counted, but its channel names are not listed. */
        val locked: Boolean = false
    )

    data class Tally(val id: String, val name: String, val covered: Int, val total: Int) {
        val missing: Int get() = total - covered
        val percent: Int get() = percentOf(covered, total)
    }

    data class GuideTally(val id: String, val name: String, val channels: Int, val programs: Long)

    data class MissingGroup(
        val groupId: String,
        val group: String,
        /** Visible channels with no guide (locked groups: empty, see [lockedCount]). */
        val channels: List<Channel>,
        val lockedCount: Int = 0
    ) {
        val count: Int get() = channels.size + lockedCount
    }

    data class Report(
        val total: Int,
        val covered: Int,
        val programs: Long,
        val groups: List<Tally>,
        val playlists: List<Tally>,
        val guides: List<GuideTally>,
        val missing: List<MissingGroup>
    ) {
        val percent: Int get() = percentOf(covered, total)
        val missingCount: Int get() = total - covered
        val isEmpty: Boolean get() = total == 0
    }

    /** Whole percent, rounded down, except that anything above 0 shows at least 1 and below all at most 99. */
    fun percentOf(part: Int, whole: Int): Int {
        if (whole <= 0) return 0
        if (part <= 0) return 0
        if (part >= whole) return 100
        return ((part * 100L) / whole).toInt().coerceIn(1, 99)
    }

    /**
     * @param channels channels that count (already filtered for enabled playlists and hidden ones).
     * @param covered keys of channels with at least one listing in the window.
     * @param bindings channel key -> (guide id, stored listings) for channels bound to a guide.
     * @param guideNames guide id -> display name (your EPG sources); others are a playlist's built-in guide.
     */
    fun compute(
        channels: List<Channel>,
        covered: Set<String>,
        bindings: Map<String, Pair<String, Int>> = emptyMap(),
        guideNames: Map<String, String> = emptyMap()
    ): Report {
        val unique = channels.distinctBy { it.key }
        val isCovered: (Channel) -> Boolean = { it.key in covered }

        val groups = unique.groupBy { it.groupId }.map { (id, list) ->
            Tally(id, list.first().group, list.count(isCovered), list.size)
        }.sortedWith(compareBy<Tally> { it.percent }.thenByDescending { it.missing }.thenBy { it.name.lowercase() })

        val playlists = unique.groupBy { it.playlistId }.map { (id, list) ->
            Tally(id, list.first().playlistName, list.count(isCovered), list.size)
        }.sortedWith(compareBy<Tally> { it.percent }.thenBy { it.name.lowercase() })

        // Each covered channel is credited to the one guide its listings come from.
        val byGuide = LinkedHashMap<String, MutableList<Channel>>()
        unique.filter(isCovered).forEach { ch ->
            bindings[ch.key]?.first?.let { byGuide.getOrPut(it) { ArrayList() } += ch }
        }
        val programsByGuide = HashMap<String, Long>()
        unique.filter(isCovered).forEach { ch ->
            bindings[ch.key]?.let { (src, n) -> programsByGuide[src] = (programsByGuide[src] ?: 0L) + n }
        }
        val guides = (byGuide.keys + guideNames.keys).distinct().map { id ->
            val list = byGuide[id].orEmpty()
            val name = guideNames[id] ?: builtInName(list)
            GuideTally(id, name, list.size, programsByGuide[id] ?: 0L)
        }.sortedWith(compareByDescending<GuideTally> { it.channels }.thenBy { it.name.lowercase() })

        val missing = unique.filterNot(isCovered).groupBy { it.groupId }.map { (id, list) ->
            val (locked, open) = list.partition { it.locked }
            MissingGroup(id, list.first().group, open.sortedBy { it.name.lowercase() }, locked.size)
        }.sortedWith(compareByDescending<MissingGroup> { it.count }.thenBy { it.group.lowercase() })

        return Report(
            total = unique.size,
            covered = unique.count(isCovered),
            programs = programsByGuide.values.sum(),
            groups = groups,
            playlists = playlists,
            guides = guides,
            missing = missing
        )
    }

    /** A guide that isn't one of your EPG sources: the built-in guide of the playlist it mostly feeds. */
    private fun builtInName(list: List<Channel>): String {
        val playlist = list.groupingBy { it.playlistName }.eachCount().maxByOrNull { it.value }?.key
        return if (playlist != null) "$playlist (built-in)" else "Playlist guide (built-in)"
    }
}
