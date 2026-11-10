package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

private fun ch(key: String) = LiveChannel(key, "s", "S", key.uppercase(), null, null, null, "g", "G", 1, "http://x/$key")
private fun match(key: String, score: Int = 100) = ChannelMatch(ch(key), score, "test", null, null, false)
private fun team(id: String, league: String = "nfl") = TeamInfo(league, id, id, id, id.uppercase(), null, null, null, null)
private fun side(id: String, home: Boolean, score: String? = null, winner: Boolean = false) = Side(team(id), score, null, winner, home)
private fun game(id: String, state: GameState, startMs: Long, away: String = "a$id", home: String = "h$id", winner: String? = null, hasTime: Boolean = true) =
    SportsEvent(id, "nfl", startMs, hasTime, state, "$away at $home", null, null,
        side(away, false, winner = winner == away), side(home, true, winner = winner == home))
private fun follows(vararg ids: String) = SportsFollows(teams = ids.map { FollowedTeam("nfl", it, it, it, null, null) })

class SportsDeadRankingTest {
    @Test fun `no scan keeps the list as is`() {
        val l = listOf(match("a"), match("b"))
        assertEquals(l, SportsDeadRanking.matches(l, emptySet()))
    }

    @Test fun `dead channels are dropped and working ones keep their order`() {
        val l = listOf(match("a"), match("b"), match("c"))
        assertEquals(listOf("b", "c"), SportsDeadRanking.matches(l, setOf("a")).map { it.channel.key })
    }

    @Test fun `all dead keeps everything so the game still has a channel`() {
        val l = listOf(match("a"), match("b"))
        assertEquals(l, SportsDeadRanking.matches(l, setOf("a", "b")))
    }

    @Test fun `a hand picked channel is kept even when marked dead`() {
        val l = listOf(match("a", SportsDeadRanking.PICK_SCORE), match("b"), match("c"))
        assertEquals(listOf("a", "c"), SportsDeadRanking.matches(l, setOf("a", "b")).map { it.channel.key })
    }

    @Test fun `all ranks every game`() {
        val m = mapOf("g1" to listOf(match("a"), match("b")), "g2" to listOf(match("c")))
        val r = SportsDeadRanking.all(m, setOf("a", "c"))
        assertEquals(listOf("b"), r["g1"]!!.map { it.channel.key })
        assertEquals(listOf("c"), r["g2"]!!.map { it.channel.key })
    }
}

class SportsGameStartedTest {
    private val now = 1_000_000_000L

    @Test fun `followed game going from PRE to LIVE is detected`() {
        val ev = game("1", GameState.LIVE, now - 60_000, home = "det")
        val r = SportsGameStarted.detect(mapOf("1" to GameState.PRE), listOf(ev), follows("det"), emptySet(), now)
        assertEquals(listOf("1"), r.map { it.id })
    }

    @Test fun `games of teams not followed are ignored`() {
        val ev = game("1", GameState.LIVE, now - 60_000)
        assertTrue(SportsGameStarted.detect(mapOf("1" to GameState.PRE), listOf(ev), follows("det"), emptySet(), now).isEmpty())
    }

    @Test fun `already announced or already live games are ignored`() {
        val ev = game("1", GameState.LIVE, now - 60_000, home = "det")
        assertTrue(SportsGameStarted.detect(mapOf("1" to GameState.PRE), listOf(ev), follows("det"), setOf("1"), now).isEmpty())
        assertTrue(SportsGameStarted.detect(mapOf("1" to GameState.LIVE), listOf(ev), follows("det"), emptySet(), now).isEmpty())
    }

    @Test fun `first seen live counts only shortly after the start`() {
        val fresh = game("1", GameState.LIVE, now - 5 * 60_000, home = "det")
        val old = game("2", GameState.LIVE, now - 30 * 60_000, home = "det")
        val noTime = game("3", GameState.LIVE, now - 60_000, home = "det", hasTime = false)
        val r = SportsGameStarted.detect(emptyMap(), listOf(fresh, old, noTime), follows("det"), emptySet(), now)
        assertEquals(listOf("1"), r.map { it.id })
    }

    @Test fun `a followed team winning is reported once the game goes final`() {
        val ev = game("1", GameState.POST, now - 3 * 3_600_000, home = "det", winner = "det")
        val r = SportsGameStarted.wins(mapOf("1" to GameState.LIVE), listOf(ev), follows("det"))
        assertEquals("det", r.single().second.id)
        assertTrue(SportsGameStarted.wins(mapOf("1" to GameState.POST), listOf(ev), follows("det")).isEmpty())
        val lost = game("2", GameState.POST, now, home = "det", away = "chi", winner = "chi")
        assertTrue(SportsGameStarted.wins(mapOf("2" to GameState.LIVE), listOf(lost), follows("det")).isEmpty())
    }
}

class SportsWeekTest {
    private val zone = ZoneId.of("America/Chicago")
    private fun at(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun `week starts on Monday`() {
        assertEquals(LocalDate.of(2026, 11, 9), SportsWeek.weekStart(LocalDate.of(2026, 11, 12)))
        assertEquals(LocalDate.of(2026, 11, 9), SportsWeek.weekStart(LocalDate.of(2026, 11, 9)))
        assertEquals(DayOfWeek.MONDAY, SportsWeek.weekStart(LocalDate.of(2026, 11, 15)).dayOfWeek)
    }

    @Test fun `games are grouped by local day, followed teams only, earliest first`() {
        val mon = LocalDate.of(2026, 11, 9)
        val events = listOf(
            game("late", GameState.PRE, at(mon, 21), home = "det"),
            game("early", GameState.PRE, at(mon, 12), away = "det"),
            game("other", GameState.PRE, at(mon, 13)),
            game("sun", GameState.PRE, at(mon.plusDays(6), 23), home = "det"),
            game("next", GameState.PRE, at(mon.plusDays(7), 12), home = "det"),
            game("early", GameState.PRE, at(mon, 12), away = "det")
        )
        val week = SportsWeek.group(events, follows("det"), mon, zone)
        assertEquals(7, week.size)
        assertEquals(mon, week.first().date)
        assertEquals(listOf("early", "late"), week[0].games.map { it.id })
        assertEquals(listOf("sun"), week[6].games.map { it.id })
        assertEquals(3, week.sumOf { it.games.size })
    }

    @Test fun `window covers exactly seven days`() {
        val mon = LocalDate.of(2026, 11, 9)
        val w = SportsWeek.window(mon, zone)
        assertTrue(at(mon, 0) in w)
        assertTrue(at(mon.plusDays(6), 23) in w)
        assertTrue(at(mon.plusDays(7), 0) !in w)
    }
}

class SportsOddsTest {
    private val pickcenter = """
        [{"provider":{"id":"58","name":"ESPN BET"},"details":"DET -3.5","overUnder":47.5,"spread":-3.5,
          "awayTeamOdds":{"favorite":false,"moneyLine":150},
          "homeTeamOdds":{"favorite":true,"moneyLine":-170}}]
    """.trimIndent()

    @Test fun `pickcenter line is parsed`() {
        val b = SportsOdds.parse(pickcenter)!!
        assertEquals("ESPN BET", b.provider)
        assertEquals("DET -3.5", b.details)
        assertEquals(-3.5, b.spread!!, 0.0)
        assertEquals(47.5, b.overUnder!!, 0.0)
        assertEquals(150, b.awayMoneyLine)
        assertEquals(-170, b.homeMoneyLine)
        assertEquals(true, b.homeFavorite)
    }

    @Test fun `implied chances remove the margin and add up to 100`() {
        val b = SportsOdds.parse(pickcenter)!!
        val h = b.impliedHome!!; val a = b.impliedAway!!
        assertEquals(100.0, h + a, 1e-9)
        assertTrue(h > a)
        assertEquals(61.2, h, 0.5)
    }

    @Test fun `american odds in the newer nested shape are read`() {
        val j = """[{"provider":{"name":"X"},"awayTeamOdds":{"current":{"moneyLine":{"american":"+120"}}},
            "homeTeamOdds":{"current":{"moneyLine":{"american":"-140"}}}}]"""
        val b = SportsOdds.parse(j)!!
        assertEquals(120, b.awayMoneyLine)
        assertEquals(-140, b.homeMoneyLine)
    }

    @Test fun `empty, broken or useless input gives null`() {
        assertNull(SportsOdds.parse(null))
        assertNull(SportsOdds.parse(""))
        assertNull(SportsOdds.parse("{not json"))
        assertNull(SportsOdds.parse("[]"))
        assertNull(SportsOdds.parse("""[{"provider":{"name":"X"}}]"""))
    }

    @Test fun `formatting`() {
        assertEquals("+150", SportsOdds.formatMoneyLine(150))
        assertEquals("-170", SportsOdds.formatMoneyLine(-170))
        assertNull(SportsOdds.formatMoneyLine(null))
        assertEquals("PK", SportsOdds.formatSpread(0.0))
        assertEquals("+3.5", SportsOdds.formatSpread(3.5))
        assertEquals("-7", SportsOdds.formatSpread(-7.0))
        assertNull(SportsOdds.implied(50))
        assertNull(SportsOdds.noVig(null, 120))
    }
}

class SportsLiveDetailTest {
    @Test fun `plays come newest first with scores`() {
        val j = """[{"text":"Kickoff","period":{"number":1},"clock":{"displayValue":"15:00"}},
            {"text":"TD pass","scoringPlay":true,"team":{"id":"8"},"awayScore":0,"homeScore":7,"period":{"number":1},"clock":{"displayValue":"9:12"}}]"""
        val p = SportsLiveDetail.plays(j)
        assertEquals(listOf("TD pass", "Kickoff"), p.map { it.text })
        assertTrue(p[0].scoring)
        assertEquals(7, p[0].homeScore)
        assertEquals("9:12", p[0].clock)
    }

    @Test fun `commentary is used when there are no plays`() {
        val c = """[{"text":"Goal! 1-0","time":{"displayValue":"23'"},"type":{"type":"goal"}}]"""
        val p = SportsLiveDetail.plays(null, c)
        assertEquals("23'", p.single().clock)
        assertTrue(p.single().scoring)
        assertTrue(SportsLiveDetail.plays(null, null).isEmpty())
    }

    @Test fun `box score takes the first group with players`() {
        val j = """[{"team":{"id":"8","abbreviation":"DET"},"statistics":[{"name":"passing","labels":["C/ATT","YDS"],
            "athletes":[{"athlete":{"shortName":"J. Goff"},"stats":["20/28","245"]}]}]}]"""
        val b = SportsLiveDetail.boxscore(j).single()
        assertEquals("DET", b.team)
        assertEquals(listOf("C/ATT", "YDS"), b.labels)
        assertEquals("J. Goff", b.rows.single().name)
        assertNotNull(b.group)
    }
}

class SportsMultiviewTest {
    @Test fun `one channel per live game, followed first, at most four`() {
        val events = (1..6).map { game("$it", GameState.LIVE, it.toLong(), home = "t$it") } + game("pre", GameState.PRE, 0)
        val matches = (1..6).associate { "$it" to listOf(match("c$it")) } + mapOf("pre" to listOf(match("cp")))
        val picked = SportsMultiview.pick(events, matches, follows("t5"), mineOnly = false)
        assertEquals(listOf("c5", "c1", "c2", "c3"), picked.map { it.key })
        assertEquals(listOf("c5"), SportsMultiview.pick(events, matches, follows("t5"), mineOnly = true).map { it.key })
    }

    @Test fun `a channel shared by two games is used once`() {
        val events = listOf(game("1", GameState.LIVE, 1), game("2", GameState.LIVE, 2))
        val matches = mapOf("1" to listOf(match("x")), "2" to listOf(match("x"), match("y")))
        assertEquals(listOf("x", "y"), SportsMultiview.pick(events, matches, follows(), false).map { it.key })
    }
}

class SportsHighlightsPickTest {
    @Test fun `recent finished games of followed teams, newest first`() {
        val now = 10L * 24 * 3_600_000
        val events = listOf(
            game("old", GameState.POST, now - 6L * 24 * 3_600_000, home = "det"),
            game("y", GameState.POST, now - 24L * 3_600_000, home = "det"),
            game("t", GameState.POST, now - 3_600_000, away = "det"),
            game("live", GameState.LIVE, now - 3_600_000, home = "det"),
            game("other", GameState.POST, now - 3_600_000)
        )
        assertEquals(listOf("t", "y"), SportsHighlightsPick.games(events, follows("det"), now).map { it.id })
    }
}
