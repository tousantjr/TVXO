package com.nuvio.tv.livetv.sports

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioPrimitives
import kotlin.math.abs

/** Sports animations, from Sports settings: everything, the light ones only, or none. */
enum class SportsAnimLevel(val key: String, val label: String) {
    FULL("full", "Full"), REDUCED("reduced", "Reduced"), OFF("off", "Off");

    companion object {
        fun from(key: String?): SportsAnimLevel = entries.firstOrNull { it.key == key } ?: FULL
    }
}

/** The animation level for everything under the Sports hub and its pop-ups. */
internal val LocalSportsAnim = staticCompositionLocalOf { SportsAnimLevel.FULL }

internal object SportsColors {
    val Pink = NuvioPrimitives.tvxoPink
    val Lime = NuvioPrimitives.tvxoLime
}

/** Pure rules behind the animations (unit tested). */
internal object SportsAnimLogic {
    /** How close counts as "close" for each sport (points, goals or runs). */
    fun closeMargin(sport: Sport): Double = when (sport) {
        Sport.AMERICAN_FOOTBALL -> 8.0
        Sport.BASKETBALL -> 6.0
        Sport.BASEBALL -> 2.0
        else -> 1.0
    }

    /** A live game, late on (75% through or more), within [closeMargin]. */
    fun closeLate(ev: SportsEvent, progress: Float): Boolean {
        if (ev.state != GameState.LIVE || progress < 0.75f) return false
        val sides = ev.sides
        if (sides.size != 2) return false
        val a = sides[0].score?.toDoubleOrNull() ?: return false
        val b = sides[1].score?.toDoubleOrNull() ?: return false
        val sport = SportsCatalog.league(ev.leagueId)?.sport ?: Sport.OTHER
        return abs(a - b) <= closeMargin(sport)
    }

    /** A short key for a game's score, to spot changes ("" when there is no score). */
    fun scoreKey(ev: SportsEvent): String = if (ev.hasScore) ev.sides.joinToString("-") { it.score.orEmpty() } else ""
}

/**
 * The LIVE dot: pulses (red, or lime when the game is close late on). Reduced: a slow fade only.
 * Off: a still dot. The pulse is read in the draw layer, so it never recomposes.
 */
@Composable
internal fun PulsingDot(close: Boolean, size: Dp = 7.dp, modifier: Modifier = Modifier) {
    val level = LocalSportsAnim.current
    val color = if (close) SportsColors.Lime else SportsLook.Live
    if (level == SportsAnimLevel.OFF) {
        Box(modifier.size(size).clip(CircleShape).background(color)); return
    }
    val t = rememberInfiniteTransition(label = "liveDot")
    val period = if (level == SportsAnimLevel.FULL) (if (close) 650 else 1_000) else 1_600
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(period, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p")
    Box(
        modifier.size(size)
            .graphicsLayer {
                alpha = 0.45f + 0.55f * p
                val s = if (level == SportsAnimLevel.FULL) 0.8f + 0.35f * p else 1f
                scaleX = s; scaleY = s
            }
            .clip(CircleShape).background(color)
    )
}

/** Shimmer for placeholders while loading (a still tint when animations are off). */
internal fun Modifier.sportsShimmer(): Modifier = composed {
    val level = LocalSportsAnim.current
    if (level == SportsAnimLevel.OFF) return@composed this.background(Color.White.copy(alpha = 0.04f))
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(if (level == SportsAnimLevel.FULL) 1_300 else 2_200, easing = LinearEasing)), label = "x")
    this.drawBehind {
        val w = size.width
        drawRect(
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.02f), Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.02f)),
                start = Offset(w * x - w * 0.4f, 0f), end = Offset(w * x + w * 0.4f, size.height)
            )
        )
    }
}

/** A soft glow just outside a card's edge (drawn before the clip, so it isn't cut off). */
internal fun Modifier.sportsGlow(color: Color, alpha: () -> Float, radius: Dp): Modifier = drawBehind {
    val a = alpha()
    if (a <= 0.01f) return@drawBehind
    val r = radius.toPx()
    for (i in 1..3) {
        val grow = i * 2.dp.toPx()
        drawRoundRect(
            color.copy(alpha = a * (0.42f - i * 0.11f)),
            topLeft = Offset(-grow, -grow),
            size = androidx.compose.ui.geometry.Size(size.width + grow * 2, size.height + grow * 2),
            cornerRadius = CornerRadius(r + grow),
            style = Stroke(2.dp.toPx())
        )
    }
}

/** A score that rolls up to its new value (Full), fades (Reduced), or just changes (Off). */
@Composable
internal fun ScoreRoll(score: String, content: @Composable (String) -> Unit) {
    when (LocalSportsAnim.current) {
        SportsAnimLevel.OFF -> content(score)
        SportsAnimLevel.REDUCED -> AnimatedContent(score, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "score") { content(it) }
        SportsAnimLevel.FULL -> AnimatedContent(
            score,
            transitionSpec = {
                (slideInVertically(tween(320)) { it } + fadeIn(tween(320))) togetherWith (slideOutVertically(tween(260)) { -it } + fadeOut(tween(200)))
            },
            label = "score"
        ) { content(it) }
    }
}

/**
 * Slide/fade in when [key] changes (a new tab, league, day or week, or data arriving).
 * [direction]: -1 from the left, 1 from the right, 0 fade only. Drawn in the graphics layer.
 */
@Composable
internal fun Modifier.sportsEnter(key: Any?, direction: Int = 0): Modifier {
    val level = LocalSportsAnim.current
    if (level == SportsAnimLevel.OFF) return this
    val progress = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    val dir = androidx.compose.runtime.remember { intArrayOf(0) }
    androidx.compose.runtime.LaunchedEffect(key) {
        dir[0] = if (level == SportsAnimLevel.FULL) direction else 0
        progress.snapTo(0f)
        progress.animateTo(1f, tween(if (level == SportsAnimLevel.FULL) 260 else 160, easing = FastOutSlowInEasing))
    }
    return this.graphicsLayer {
        val p = progress.value
        alpha = 0.35f + 0.65f * p
        translationX = (1f - p) * dir[0] * 28.dp.toPx()
    }
}
