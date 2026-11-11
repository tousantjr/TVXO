package com.nuvio.tv.livetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nuvio.tv.ui.screens.settings.AddXtreamSourceDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * TVXO: Live TV with no Xtream login set up yet. The login form opens straight away; cancelled,
 * this page stays with an "Add Xtream source" button. Left and Back always go to the menu, so
 * the page can never hold the highlight.
 */
@Composable
internal fun LiveTvNoSourceScreen(onOpenSettings: () -> Unit) {
    val settingsViewModel: LiveTvSettingsViewModel = hiltViewModel()
    val openSidebar = com.nuvio.tv.LocalOpenSidebar.current
    val sidebarExpanded = com.nuvio.tv.LocalSidebarExpanded.current
    // Opened once per visit to the page (not again after Cancel).
    var autoOpened by rememberSaveable { mutableStateOf(false) }
    var showForm by remember { mutableStateOf(false) }
    val addButton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!autoOpened) {
            autoOpened = true
            showForm = true
        }
    }
    LaunchedEffect(showForm, sidebarExpanded) {
        if (!showForm && !sidebarExpanded) {
            delay(80)
            runCatching { addButton.requestFocus() }
        }
    }
    BackHandler(enabled = !showForm && !sidebarExpanded) { openSidebar() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && !showForm) {
                    openSidebar(); true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 620.dp).padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            LiveText("No Live TV source yet", size = 24.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            LiveText(
                "Add your Xtream Codes login (server, username and password) to load your channels and TV guide.",
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 3,
                align = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            NoSourceButton(
                label = "Add Xtream source",
                modifier = Modifier.focusRequester(addButton),
                onClick = { showForm = true }
            )
            Spacer(Modifier.height(12.dp))
            NoSourceButton(label = "Open Live TV settings", onClick = onOpenSettings)
            Spacer(Modifier.height(22.dp))
            LiveText("Press Left or Back for the menu", color = NuvioTheme.colors.TextTertiary, size = 13.sp)
        }
    }
    if (showForm) {
        AddXtreamSourceDialog(settingsViewModel, onDone = { showForm = false })
    }
}

@Composable
private fun NoSourceButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .widthIn(min = 300.dp)
            .background(if (focused) NuvioTheme.colors.Secondary else NuvioTheme.colors.SurfaceVariant, shape)
            .border(1.dp, if (focused) NuvioTheme.colors.Secondary else NuvioTheme.colors.Border, shape)
            .onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (ok && e.type == KeyEventType.KeyUp) { onClick(); true } else ok
            }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        LiveText(
            label,
            color = if (focused) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary,
            weight = FontWeight.SemiBold
        )
    }
}
