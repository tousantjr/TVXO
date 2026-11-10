package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.R
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "My Teams This Week": a calendar of the followed teams' games, a column per day. The games
 * come from what Sports already knows (followed teams' seasons are loaded for My Teams).
 */
@Composable
internal fun SportsWeekPage(
    known: List<SportsEvent>,
    follows: SportsFollows,
    settings: SportsSettings,
    now: Long,
    use24h: Boolean,
    playingKey: String?,
    matches: Map<String, List<ChannelMatch>>,
    header: @Composable () -> Unit,
    onOpen: (SportsEvent) -> Unit,
    onQuickWatch: (SportsEvent) -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    val today = remember(now / 3_600_000L) { java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate() }
    val start = SportsWeek.weekStart(today).plusWeeks(weekOffset.toLong())
    val days = remember(known, follows, start) { SportsWeek.group(known, follows, start, zone) }
    val rangeFmt = remember { DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()) }
    val dayFmt = remember { DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 36.dp, top = 20.dp, bottom = 26.dp)) {
        header()
        Row(Modifier.padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SportsAction("‹ " + stringResource(R.string.sports_week_previous)) { weekOffset = (weekOffset - 1).coerceAtLeast(-4) }
            Spacer(Modifier.width(8.dp))
            SportsAction(stringResource(R.string.sports_week_this), selected = weekOffset == 0) { weekOffset = 0 }
            Spacer(Modifier.width(8.dp))
            SportsAction(stringResource(R.string.sports_week_next) + " ›") { weekOffset = (weekOffset + 1).coerceAtMost(8) }
            Spacer(Modifier.width(16.dp))
            LiveText(
                stringResource(R.string.sports_week_range, start.format(rangeFmt), start.plusDays(6).format(rangeFmt)),
                color = SportsLook.Dim, size = 12.sp, weight = FontWeight.Bold
            )
        }
        if (follows.teams.isEmpty()) {
            LiveText(stringResource(R.string.sports_week_no_teams), color = SportsLook.Dim, size = 14.sp, modifier = Modifier.padding(top = 30.dp))
            return@Column
        }
        Row(Modifier.weight(1f).fillMaxWidth().sportsEnter(start), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { d ->
                WeekColumn(
                    day = d, isToday = d.date == today, label = d.date.format(dayFmt).uppercase(Locale.getDefault()),
                    follows = follows, hideScores = settings.hideScores, use24h = use24h, now = now,
                    playingKey = playingKey, matches = matches, onOpen = onOpen, onQuickWatch = onQuickWatch,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }
        LiveText(
            stringResource(R.string.sports_week_hint),
            color = NuvioTheme.colors.TextTertiary, size = 10.sp, modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun WeekColumn(
    day: WeekDay,
    isToday: Boolean,
    label: String,
    follows: SportsFollows,
    hideScores: Boolean,
    use24h: Boolean,
    now: Long,
    playingKey: String?,
    matches: Map<String, List<ChannelMatch>>,
    onOpen: (SportsEvent) -> Unit,
    onQuickWatch: (SportsEvent) -> Unit,
    modifier: Modifier
) {
    Column(
        modifier.clip(SportsLook.SmallShape)
            .background(if (isToday) SportsColors.Pink.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.025f))
            .padding(5.dp)
    ) {
        LiveText(
            label, color = if (isToday) SportsColors.Pink else Color(0xFFC9CCD6), size = 11.sp, weight = FontWeight.Black,
            modifier = Modifier.padding(start = 3.dp, bottom = 5.dp)
        )
        if (day.games.isEmpty()) {
            LiveText("—", color = SportsLook.Faint, size = 12.sp, modifier = Modifier.padding(start = 3.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 3.dp, horizontal = 3.dp)) {
                items(day.games, key = { it.id }) { ev ->
                    WeekTile(ev, follows, hideScores, use24h, playing = matches[ev.id]?.any { it.channel.key == playingKey } == true && playingKey != null, onOpen = { onOpen(ev) }, onHold = { onQuickWatch(ev) })
                }
            }
        }
    }
}

/** A compact game for the week: league logo, both teams on their colours, time or score. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WeekTile(ev: SportsEvent, follows: SportsFollows, hideScores: Boolean, use24h: Boolean, playing: Boolean, onOpen: () -> Unit, onHold: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val league = SportsCatalog.league(ev.leagueId)
    val sides = ev.ordered(league?.sport ?: Sport.OTHER)
    Column(
        Modifier.fillMaxWidth()
            .sportsFrame(focused, playing, NuvioTheme.colors.Secondary, radius = 10.dp)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen, onLongClick = onHold)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LeagueMark(league, 16.dp)
            Spacer(Modifier.width(5.dp))
            when (ev.state) {
                GameState.LIVE -> LiveBadge(SportsAnimLogic.closeLate(ev, progressOf(ev, System.currentTimeMillis())))
                GameState.POST -> LiveText(stringResource(R.string.sports_final), color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black)
                else -> LiveText(startLabel(ev, use24h), color = Color.White, size = 10.sp, weight = FontWeight.Black, maxLines = 1)
            }
        }
        if (sides.size == 2) {
            val showScore = ev.hasScore && !hideScores
            sides.forEach { s ->
                val c = bandColor(teamColor(s.team))
                Row(
                    Modifier.fillMaxWidth().height(24.dp)
                        .background(Brush.horizontalGradient(0f to c.copy(alpha = 0.85f), 1f to c.copy(alpha = 0.1f)))
                        .padding(horizontal = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TeamLogo(s.team, 18.dp)
                    Spacer(Modifier.width(4.dp))
                    val mine = follows.followsTeam(ev.leagueId, s.team.id)
                    LiveText(
                        s.team.abbr.ifBlank { s.team.name }, color = Color.White, size = 10.sp,
                        weight = if (mine) FontWeight.Black else FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f)
                    )
                    if (showScore) LiveText(s.score.orEmpty(), color = Color.White, size = 11.sp, weight = FontWeight.Black)
                }
            }
        } else {
            LiveText(ev.title, color = Color.White, size = 10.sp, weight = FontWeight.Bold, maxLines = 2, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
        }
        Box(Modifier.height(3.dp))
    }
}
