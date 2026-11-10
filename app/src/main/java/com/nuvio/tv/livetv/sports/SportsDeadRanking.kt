package com.nuvio.tv.livetv.sports

/**
 * Channel matching that knows about the dead channel scan (Settings → Live TV → Scan for dead
 * channels). Pure, so it is unit tested.
 */
internal object SportsDeadRanking {
    /** A match the user picked by hand ("Your pick") is never moved or dropped. */
    const val PICK_SCORE = 200

    /**
     * Working channels first (their order kept), dead ones left out. When every match is dead
     * they are all kept, in order, so a game never loses its only channel because of an old scan.
     */
    fun <T> rank(list: List<T>, dead: Set<String>, key: (T) -> String, picked: (T) -> Boolean = { false }): List<T> {
        if (dead.isEmpty() || list.isEmpty()) return list
        val (alive, gone) = list.partition { picked(it) || key(it) !in dead }
        return if (alive.isEmpty()) gone else alive
    }

    fun matches(list: List<ChannelMatch>, dead: Set<String>): List<ChannelMatch> =
        rank(list, dead, { it.channel.key }, { it.score >= PICK_SCORE })

    fun all(map: Map<String, List<ChannelMatch>>, dead: Set<String>): Map<String, List<ChannelMatch>> =
        if (dead.isEmpty()) map else map.mapValues { (_, l) -> matches(l, dead) }
}
