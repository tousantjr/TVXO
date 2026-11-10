package com.nuvio.tv.livetv.sports

/**
 * "Game started": spots a followed team's game going live, for the small pop-up in SportsHost.
 * Pure, so it is unit tested.
 */
internal object SportsGameStarted {
    /** A game seen live for the first time this long after its start still counts as "just started". */
    const val FRESH_MS = 10 * 60_000L

    /** True when one of the game's teams is followed. */
    fun followed(ev: SportsEvent, follows: SportsFollows): Boolean =
        ev.sides.any { follows.followsTeam(it.team.leagueId, it.team.id) }

    /**
     * Games that have just started: live now, followed, not already announced ([fired]), and
     * either seen before the start ([previous] state PRE) or first seen live within [FRESH_MS]
     * of the start time.
     */
    fun detect(
        previous: Map<String, GameState>,
        events: List<SportsEvent>,
        follows: SportsFollows,
        fired: Set<String>,
        now: Long
    ): List<SportsEvent> = events.filter { ev ->
        ev.state == GameState.LIVE && ev.id !in fired && followed(ev, follows) && when (previous[ev.id]) {
            GameState.PRE -> true
            null -> ev.hasTime && now - ev.startMs in 0..FRESH_MS
            else -> false
        }
    }

    /**
     * Followed teams that have just won: games seen LIVE before ([previous]) that are now final,
     * with a followed side marked the winner. Returns the game and the winning team.
     */
    fun wins(previous: Map<String, GameState>, events: List<SportsEvent>, follows: SportsFollows): List<Pair<SportsEvent, TeamInfo>> =
        events.mapNotNull { ev ->
            if (ev.state != GameState.POST || previous[ev.id] != GameState.LIVE) return@mapNotNull null
            val side = ev.sides.firstOrNull { it.winner && follows.followsTeam(it.team.leagueId, it.team.id) } ?: return@mapNotNull null
            ev to side.team
        }
}
