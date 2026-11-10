package com.nuvio.tv.livetv.sports

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.R
import com.nuvio.tv.livetv.ui.LiveText

/**
 * "Game started": the key the pop-up listens for, without taking the highlight (the activity
 * hands every key here first; only Play/Pause is used, and only while the pop-up shows).
 */
object SportsStartedPrompt {
    @Volatile
    internal var onWatch: (() -> Unit)? = null

    /** Called by MainActivity for every key. True: used (switching to the game). */
    fun onKey(event: KeyEvent): Boolean {
        val action = onWatch ?: return false
        if (event.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && event.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY) return false
        if (event.action == KeyEvent.ACTION_UP) { onWatch = null; action() }
        return true
    }
}

/** The pop-up in the corner: both teams, the channel, and how to switch. Never takes focus. */
@Composable
internal fun GameStartedCard(ev: SportsEvent, match: ChannelMatch?) {
    val league = SportsCatalog.league(ev.leagueId)
    val sides = ev.ordered(league?.sport ?: Sport.OTHER)
    Column(
        Modifier.padding(bottom = 36.dp, end = 32.dp).width(330.dp)
            .sportsFrame(focused = false, playing = true, accent = SportsColors.Pink)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveBadge(close = false)
            Spacer(Modifier.width(8.dp))
            LiveText(stringResource(R.string.sports_game_started), color = Color.White, size = 12.sp, weight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            LeagueMark(league, 20.dp)
        }
        if (sides.size == 2) {
            sides.forEach { s -> TeamBand(s, showScore = false, dim = false, followed = false, modifier = Modifier.height(34.dp), logo = 24.dp, name = 12.sp) }
        } else {
            LiveText(ev.title, color = Color.White, size = 13.sp, weight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
        }
        LiveText(
            if (match != null) stringResource(R.string.sports_game_started_watch, match.channel.name) else stringResource(R.string.sports_game_started_no_channel),
            color = if (match != null) SportsColors.Lime else SportsLook.Dim, size = 11.sp, weight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/** A team's logo, big in the middle for a moment, when one of your teams scores or wins. */
@Composable
internal fun TeamLogoSplash(team: TeamInfo, won: Boolean, onDone: () -> Unit) {
    val level = LocalSportsAnim.current
    val scale = remember(team.key, won) { Animatable(0.5f) }
    val alpha = remember(team.key, won) { Animatable(0f) }
    LaunchedEffect(team.key, won) {
        val full = level == SportsAnimLevel.FULL
        alpha.animateTo(1f, tween(if (full) 180 else 120))
        if (full) {
            scale.animateTo(1.12f, tween(320, easing = FastOutSlowInEasing))
            scale.animateTo(1f, tween(180))
        } else scale.snapTo(1f)
        kotlinx.coroutines.delay(if (full) 650 else 500)
        alpha.animateTo(0f, tween(260))
        onDone()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.graphicsLayer { this.alpha = alpha.value; scaleX = scale.value; scaleY = scale.value },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.background(
                    androidx.compose.ui.graphics.Brush.radialGradient(listOf(teamColor(team).copy(alpha = 0.55f), Color.Transparent))
                ).padding(28.dp)
            ) { TeamLogo(team, 150.dp) }
            LiveText(
                if (won) stringResource(R.string.sports_splash_win, team.name.uppercase()) else stringResource(R.string.sports_splash_score, team.name.uppercase()),
                color = if (won) SportsColors.Lime else Color.White, size = 22.sp, weight = FontWeight.Black
            )
        }
    }
}
