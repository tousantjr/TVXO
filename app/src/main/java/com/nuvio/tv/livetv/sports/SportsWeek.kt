package com.nuvio.tv.livetv.sports

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** One day of "My Teams This Week": its date and the followed teams' games, by start time. */
data class WeekDay(val date: LocalDate, val games: List<SportsEvent>)

/** "My Teams This Week": the weekly calendar. Pure, so it is unit tested. */
internal object SportsWeek {
    /** Monday of the week [date] is in. */
    fun weekStart(date: LocalDate, firstDay: DayOfWeek = DayOfWeek.MONDAY): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(firstDay))

    /**
     * Seven days from [start], each with the games of followed teams on that day (in [zone]),
     * earliest first, one entry per game id. Games outside the week are left out.
     */
    fun group(events: List<SportsEvent>, follows: SportsFollows, start: LocalDate, zone: ZoneId): List<WeekDay> {
        val days = (0L until 7L).map { start.plusDays(it) }
        val byDay = events.asSequence()
            .filter { SportsGameStarted.followed(it, follows) }
            .distinctBy { it.id }
            .groupBy { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
        return days.map { d -> WeekDay(d, byDay[d].orEmpty().sortedWith(compareBy({ it.startMs }, { it.id }))) }
    }

    /** The [start]..[start]+7 days window in epoch millis (for picking which events to look at). */
    fun window(start: LocalDate, zone: ZoneId): LongRange {
        val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = start.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return from..to
    }
}
