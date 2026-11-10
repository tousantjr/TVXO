package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.LiveChannel

/** Which channels Multiview opens with when started from Sports. Pure, so it is unit tested. */
internal object SportsMultiview {
    const val MAX = 4

    /** Live games with a matched channel ([mineOnly]: followed teams only), [firstId] then followed games first. */
    fun liveGames(
        events: List<SportsEvent>,
        matches: Map<String, List<ChannelMatch>>,
        follows: SportsFollows,
        mineOnly: Boolean,
        firstId: String? = null
    ): List<SportsEvent> = events.asSequence()
        .filter { it.state == GameState.LIVE && matches[it.id].orEmpty().isNotEmpty() }
        .filter { !mineOnly || SportsGameStarted.followed(it, follows) }
        .distinctBy { it.id }
        .sortedWith(compareBy<SportsEvent>({ it.id != firstId }, { !SportsGameStarted.followed(it, follows) }, { it.startMs }))
        .toList()

    /** One channel per game (a channel already used for another game is skipped), up to [MAX]. */
    fun pick(
        events: List<SportsEvent>,
        matches: Map<String, List<ChannelMatch>>,
        follows: SportsFollows,
        mineOnly: Boolean,
        firstId: String? = null
    ): List<LiveChannel> {
        val used = LinkedHashMap<String, LiveChannel>()
        for (ev in liveGames(events, matches, follows, mineOnly, firstId)) {
            val ch = matches[ev.id].orEmpty().firstOrNull { it.channel.key !in used }?.channel ?: continue
            used[ch.key] = ch
            if (used.size >= MAX) break
        }
        return used.values.toList()
    }
}
