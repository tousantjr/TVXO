package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.R
import com.nuvio.tv.livetv.home.LocalLiveTvHomeActions
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.EntryPointAccessors

/** One highlight / replay on the Home row: the game it's from and what plays. */
internal data class HomeHighlight(val ev: SportsEvent, val match: ChannelMatch)

/** Which finished games of your teams to look up highlights for (pure: unit tested). */
internal object SportsHighlightsPick {
    const val MAX_GAMES = 4
    const val MAX_ITEMS = 10
    const val WINDOW_MS = 4L * 24 * 60 * 60_000L

    /** Followed teams' finished games from the last four days, newest first, at most [MAX_GAMES]. */
    fun games(events: List<SportsEvent>, follows: SportsFollows, now: Long): List<SportsEvent> =
        events.asSequence()
            .filter { it.state == GameState.POST && it.startMs in (now - WINDOW_MS)..now }
            .filter { SportsGameStarted.followed(it, follows) }
            .distinctBy { it.id }
            .sortedByDescending { it.startMs }
            .take(MAX_GAMES)
            .toList()
}

/**
 * Home: highlights and replays of your followed teams' recent games, from your sports add-ons
 * (the same source as the game pages' replays). Not shown when there are none, when scores are
 * hidden (they'd give results away), or away from the real home screen.
 */
@Composable
fun SportsHomeHighlightsRow(startPadding: Dp = 0.dp, titleStyle: TextStyle? = null) {
    val actions = LocalLiveTvHomeActions.current
    if (!actions.onHome) return
    val context = LocalContext.current
    val entry = remember {
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, SportsEntryPoint::class.java) }.getOrNull()
    } ?: return
    val repo = remember { entry.sportsRepository() }
    val settings by repo.settings.collectAsState()
    val follows by repo.follows.collectAsState()
    val known by repo.known.collectAsState()
    if (!settings.showInSidebar || settings.hideScores || follows.teams.isEmpty()) return
    val games = remember(known, follows) { SportsHighlightsPick.games(known, follows, System.currentTimeMillis()) }
    var items by remember { mutableStateOf<List<HomeHighlight>>(emptyList()) }
    LaunchedEffect(games.map { it.id }) {
        val out = ArrayList<HomeHighlight>()
        for (ev in games) {
            runCatching { repo.highlightsFor(ev) }.getOrDefault(emptyList())
                .take(3).forEach { out += HomeHighlight(ev, it) }
            if (out.size >= SportsHighlightsPick.MAX_ITEMS) break
        }
        items = out.distinctBy { it.match.channel.key }.take(SportsHighlightsPick.MAX_ITEMS)
    }
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val title = stringResource(R.string.sports_highlights_row)
        Row(Modifier.padding(start = startPadding, end = startPadding, bottom = 8.dp)) {
            if (titleStyle != null) androidx.tv.material3.Text(text = title, style = titleStyle, color = NuvioTheme.colors.TextPrimary)
            else LiveText(title, size = 16.sp, weight = FontWeight.SemiBold)
        }
        LazyRow(
            contentPadding = PaddingValues(start = startPadding, end = maxOf(startPadding, 24.dp)),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items, key = { it.match.channel.key }) { h ->
                HighlightCard(h) {
                    entry.sportsPlayback().play(h.match.channel)
                    actions.openFullscreen()
                }
            }
        }
    }
}

@Composable
private fun HighlightCard(h: HomeHighlight, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val league = SportsCatalog.league(h.ev.leagueId)
    val sides = h.ev.ordered(league?.sport ?: Sport.OTHER)
    Column(
        Modifier.width(250.dp).height(84.dp)
            .sportsFrame(focused = focused, accent = league?.let { leagueColor(it) } ?: SportsColors.Pink, radius = 12.dp)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LeagueMark(league, 18.dp)
            Spacer(Modifier.width(6.dp))
            sides.forEach { s -> TeamLogo(s.team, 20.dp); Spacer(Modifier.width(4.dp)) }
            Spacer(Modifier.width(2.dp))
            LiveText(
                if (sides.size == 2) sides.joinToString(" v ") { it.team.abbr ?: it.team.name } else h.ev.title,
                color = Color.White, size = 12.sp, weight = FontWeight.Bold, maxLines = 1
            )
        }
        Spacer(Modifier.height(6.dp))
        LiveText(h.match.channel.name, color = if (focused) SportsColors.Lime else SportsLook.Dim, size = 11.sp, maxLines = 1, marquee = focused)
    }
}
