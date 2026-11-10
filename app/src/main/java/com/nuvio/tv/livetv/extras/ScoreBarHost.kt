package com.nuvio.tv.livetv.extras

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.nuvio.tv.livetv.sports.GameState
import com.nuvio.tv.livetv.sports.Sport
import com.nuvio.tv.livetv.sports.SportsCatalog
import com.nuvio.tv.livetv.sports.SportsEvent
import com.nuvio.tv.livetv.sports.SportsFollows
import com.nuvio.tv.livetv.sports.SportsLive
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.theme.NuvioTheme

/** Sports event -> what the score bar and score alerts look at (pure, read only). */
internal fun SportsEvent.toSnap(): GameSnap {
    val sport = SportsCatalog.league(leagueId)?.sport ?: Sport.OTHER
    return GameSnap(
        id = id,
        sport = sport,
        live = state == GameState.LIVE,
        away = away?.score?.trim()?.toIntOrNull(),
        home = home?.score?.trim()?.toIntOrNull(),
        status = status,
        halves = leagueId.contains("college-basketball")
    )
}

/** Live games with at least one My Teams side. */
internal fun myLiveGames(events: List<SportsEvent>, follows: SportsFollows): List<SportsEvent> =
    events.filter { ev ->
        ev.state == GameState.LIVE && ev.sides.size == 2 &&
            ev.sides.any { follows.followsTeam(ev.leagueId, it.team.id) }
    }

/**
 * Live score bar (Settings → Live TV → Live score bar): a thin strip along the top with your
 * My Teams games that are on now, while the Live TV player or the movie/show player is on
 * screen. Also the score alerts (score change, close finish, overtime) with Watch now.
 *
 * Reads Sports data only (events, My Teams, Sports settings, best channel); it makes no calls
 * of its own. Sports' Score Delay is already applied to its events; Hide scores turns both off.
 */
@Composable
fun LiveScoreBarHost(navController: NavController) {
    val context = LocalContext.current
    val entry = remember { extrasEntry(context) } ?: return
    val sports = remember { runCatching { entry.extrasSports() }.getOrNull() } ?: return
    val prefs = entry.extrasPrefs()
    val events by sports.events.collectAsState()
    val follows by sports.follows.collectAsState()
    val sportsSettings by sports.settings.collectAsState()
    val barPref by prefs.scoreBar.collectAsState()
    val alertPref by prefs.scoreAlerts.collectAsState()
    val panelUp by SportsLive.pinsHidden.collectAsState()
    val back by navController.currentBackStackEntryAsState()
    val route = back?.destination?.route
    val playing = route == Screen.LiveTvPlayer.route || route?.startsWith("player/") == true

    val hasTeams = follows.teams.isNotEmpty()
    val barOn = (barPref ?: hasTeams) && !sportsSettings.hideScores
    val alertsOn = (alertPref ?: hasTeams) && !sportsSettings.hideScores
    val games = remember(events, follows) { myLiveGames(events, follows) }

    // Score alerts: compare each of your live games with the last look at it.
    val prev = remember { HashMap<String, GameSnap>() }
    val fired = remember { HashSet<String>() }
    var alert by remember { mutableStateOf<Pair<SportsEvent, ScoreAlertHit>?>(null) }
    LaunchedEffect(games, alertsOn, sportsSettings.scoringAlerts) {
        if (!alertsOn) { prev.clear(); return@LaunchedEffect }
        games.forEach { ev ->
            val cur = ev.toSnap()
            // Sports' own Scoring Alerts already pop up on score changes: don't show them twice.
            val hit = ScoreAlertDetector.detect(prev[ev.id], cur, fired, scoreChanges = !sportsSettings.scoringAlerts)
            prev[ev.id] = cur
            if (hit != null) {
                fired += hit.key
                if (alert == null) alert = ev to hit
            }
        }
        val live = games.mapTo(HashSet()) { it.id }
        prev.keys.retainAll(live)
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = barOn && playing && !panelUp && games.isNotEmpty(),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut()
        ) {
            ScoreStrip(games.take(4))
        }
    }

    val (ev, hit) = alert ?: return
    val line = scoreLine(ev)
    val heading = when (hit.kind) {
        ScoreAlertKind.SCORE_CHANGE -> "Score alert · score change"
        ScoreAlertKind.CLOSE_LATE -> "Score alert · close finish"
        ScoreAlertKind.OVERTIME -> "Score alert · overtime"
    }
    val channel = remember(ev.id) { runCatching { sports.bestChannel(ev.id)?.channel }.getOrNull() }
    ExtrasAlertPopup(
        heading = heading,
        title = line,
        detail = listOfNotNull(ev.status, channel?.name?.let { "on $it" }).joinToString(" · ").ifBlank { ev.title },
        onWatch = channel?.let { ch -> { alert = null; watchChannelNow(entry, navController, ch) } },
        onDismiss = { alert = null },
        autoCloseMs = 20_000L,
        alertKey = hit.key
    )
}

/** "BOS 2 – 1 NYR" in the order people say it. */
internal fun scoreLine(ev: SportsEvent): String {
    val sport = SportsCatalog.league(ev.leagueId)?.sport ?: Sport.OTHER
    val s = ev.ordered(sport)
    if (s.size != 2) return ev.title
    fun name(i: Int) = s[i].team.abbr.ifBlank { s[i].team.name }
    return "${name(0)} ${s[0].score ?: "-"} – ${s[1].score ?: "-"} ${name(1)}"
}

@Composable
private fun ScoreStrip(games: List<SportsEvent>) {
    Row(
        Modifier
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(NuvioTheme.colors.Secondary))
        games.forEach { ev ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveText(scoreLine(ev), size = 13.sp, weight = FontWeight.Bold, maxLines = 1)
                ev.status?.takeIf { it.isNotBlank() }?.let {
                    LiveText(it, color = NuvioTheme.colors.TextSecondary, size = 12.sp, maxLines = 1)
                }
            }
        }
    }
}
