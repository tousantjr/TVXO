package com.nuvio.tv.livetv.extras

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.livetv.ui.LiveDialog
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.MenuItem
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TvxoExtrasEntryPoint {
    fun extrasPrefs(): TvxoExtrasPrefs
    fun extrasLivePrefs(): LiveTvPreferences
    fun extrasLiveRepository(): LiveTvRepository
    fun extrasChannelStore(): LiveTvChannelStore
    fun extrasGuideDb(): EpgDatabase
    fun extrasPlayback(): LiveTvPlaybackController
    fun extrasDeadScanner(): DeadChannelScanner
    /** Read only: the Sports data the score bar shows (nothing in Sports is changed). */
    fun extrasSports(): com.nuvio.tv.livetv.sports.SportsRepository
}

internal fun extrasEntry(context: Context): TvxoExtrasEntryPoint? =
    runCatching { EntryPointAccessors.fromApplication(context.applicationContext, TvxoExtrasEntryPoint::class.java) }.getOrNull()

/** Tunes to [channel] and opens Live TV full screen, from wherever the app is. */
internal fun watchChannelNow(entry: TvxoExtrasEntryPoint, navController: NavController, channel: LiveChannel) {
    entry.extrasPlayback().play(channel)
    com.nuvio.tv.livetv.ui.LiveTvFullscreen.request()
    runCatching {
        navController.navigate(Screen.LiveTv.route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        navController.navigate(Screen.LiveTvPlayer.route) { launchSingleTop = true }
    }
}

/**
 * A small pop-up on top of whatever is on screen (also over the player): a heading, a title,
 * a detail line, then "Watch now" (when there's somewhere to watch) and "Dismiss". Closes by
 * itself after [autoCloseMs].
 */
@Composable
internal fun ExtrasAlertPopup(
    heading: String,
    title: String,
    detail: String,
    onWatch: (() -> Unit)?,
    onDismiss: () -> Unit,
    autoCloseMs: Long = 60_000L,
    alertKey: Any = title
) {
    val first = remember(alertKey) { FocusRequester() }
    LaunchedEffect(alertKey) { delay(autoCloseMs); onDismiss() }
    LiveDialog(onDismiss = onDismiss, width = 460.dp) {
        LiveText(heading, color = NuvioTheme.colors.Secondary, size = 13.sp, weight = FontWeight.SemiBold)
        LiveText(title, size = 22.sp, weight = FontWeight.Bold, maxLines = 2)
        LiveText(detail, color = NuvioTheme.colors.TextSecondary, size = 14.sp, maxLines = 2)
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onWatch != null) MenuItem("Watch now", Modifier.focusRequester(first), onClick = onWatch)
            MenuItem("Dismiss", if (onWatch == null) Modifier.focusRequester(first) else Modifier, onClick = onDismiss)
        }
    }
    LaunchedEffect(alertKey) { delay(80); runCatching { first.requestFocus() } }
}
