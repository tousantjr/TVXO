package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PortableWifiOff
import androidx.compose.material.icons.filled.Scoreboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.extras.extrasEntry
import com.nuvio.tv.livetv.ui.LiveDialog
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.MenuItem
import com.nuvio.tv.livetv.ui.TextInputDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/*
 * TVXO additions to Settings → Live TV: Guide alerts, Dead channels and Live score bar.
 * Each takes [section], which wraps the rows in Live TV settings' own folding section, so
 * they look and move like the others (D-pad: up/down through rows, OK to change).
 */

internal val GuideAlertsIcon: ImageVector get() = Icons.Default.NotificationsActive
internal val DeadChannelsIcon: ImageVector get() = Icons.Default.PortableWifiOff
internal val ScoreBarIcon: ImageVector get() = Icons.Default.Scoreboard

private val LEAD_CHOICES = listOf(0, 2, 5, 10, 15, 30)

private fun leadLabel(m: Int) = if (m == 0) "At the start" else "$m minutes before"

@Composable
internal fun GuideAlertsSettings(section: @Composable (title: String, summary: String, rows: @Composable ColumnScope.() -> Unit) -> Unit) {
    val context = LocalContext.current
    val entry = remember { extrasEntry(context) } ?: return
    val prefs = entry.extrasPrefs()
    val s by prefs.guideAlerts.collectAsState()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var pickLead by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    val summary = when {
        s.keywords.isEmpty() -> "No words yet"
        !s.enabled -> "Off · ${s.keywords.size} words"
        else -> "${s.keywords.size} words · ${leadLabel(s.leadMinutes).lowercase()}"
    }
    section(stringResource(R.string.tvxo_guide_alerts), summary) {
        SettingsToggleRow(
            title = "Guide Alerts",
            subtitle = "A message pops up when a show matching one of your words is about to start on your channels",
            checked = s.enabled,
            onToggle = { scope.launch { prefs.setGuideAlertsEnabled(!s.enabled) } }
        )
        SettingsActionRow("Alert Time", "How long before the show starts", leadLabel(s.leadMinutes), onClick = { pickLead = true })
        SettingsActionRow("Add a Word", "A team, a sport or a show name (\"Lakers\", \"UFC\"). Whole words, any case.", onClick = { adding = true })
        s.keywords.forEach { k ->
            SettingsActionRow(k, "Select to remove", onClick = { removing = k })
        }
    }
    if (adding) {
        TextInputDialog(
            title = "Add a word",
            initial = "",
            hint = "Lakers",
            confirmLabel = "Add",
            onDismiss = { adding = false },
            onConfirm = { text -> adding = false; scope.launch { prefs.addKeyword(text) } }
        )
    }
    if (pickLead) {
        LiveDialog(onDismiss = { pickLead = false }, width = 420.dp) {
            LiveText("Alert time", size = 20.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            LEAD_CHOICES.forEach { m ->
                MenuItem((if (m == s.leadMinutes) "✓ " else "") + leadLabel(m)) {
                    pickLead = false
                    scope.launch { prefs.setLeadMinutes(m) }
                }
            }
        }
    }
    removing?.let { k ->
        LiveDialog(onDismiss = { removing = null }, width = 420.dp) {
            LiveText("Remove \"$k\"?", size = 20.sp, weight = FontWeight.Bold, maxLines = 2)
            Spacer(Modifier.height(10.dp))
            MenuItem("Remove") { removing = null; scope.launch { prefs.removeKeyword(k) } }
            MenuItem("Cancel") { removing = null }
        }
    }
}

@Composable
internal fun DeadChannelsSettings(section: @Composable (title: String, summary: String, rows: @Composable ColumnScope.() -> Unit) -> Unit) {
    val context = LocalContext.current
    val entry = remember { extrasEntry(context) } ?: return
    val prefs = entry.extrasPrefs()
    val scanner = entry.extrasDeadScanner()
    val progress by scanner.progress.collectAsState()
    val dead by scanner.deadKeys.collectAsState()
    val results by prefs.deadResults.collectAsState()
    val lastScan by prefs.lastScanAt.collectAsState()
    val hide by prefs.hideDead.collectAsState()
    val scope = rememberCoroutineScope()
    val last = lastScan.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
    val summary = when {
        progress.running -> "Scanning… ${progress.done} of ${progress.total}"
        last == null -> "Not scanned yet"
        else -> "${dead.size} dead · checked $last"
    }
    section("Dead Channels", summary) {
        if (progress.running) {
            SettingsActionRow(
                "Cancel Scan",
                "Checked ${progress.done} of ${progress.total} · ${progress.deadFound} not answering so far",
                onClick = { scanner.cancel() }
            )
        } else {
            SettingsActionRow(
                stringResource(R.string.tvxo_scan_dead_channels),
                "Asks each channel's stream for a moment of video (4 at a time, 6 seconds each). Channels that don't answer get a small mark in the guide.",
                if (last == null) null else "${results.size} checked",
                onClick = { scanner.start() }
            )
        }
        SettingsToggleRow(
            title = stringResource(R.string.tvxo_hide_dead_channels),
            subtitle = "Leave channels the last scan found dead out of the guide",
            checked = hide,
            onToggle = { scope.launch { prefs.setHideDead(!hide) } }
        )
        if (results.isNotEmpty() && !progress.running) {
            SettingsActionRow("Clear Results", "Forget the last scan (all channels show again, unmarked)", onClick = { scanner.clear() })
        }
        Text(
            text = "Some providers allow only one or two streams at a time; channels they turn away are left as they were, not marked dead.",
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm)
        )
    }
}

@Composable
internal fun ScoreBarSettings(section: @Composable (title: String, summary: String, rows: @Composable ColumnScope.() -> Unit) -> Unit) {
    val context = LocalContext.current
    val entry = remember { extrasEntry(context) } ?: return
    val prefs = entry.extrasPrefs()
    val sports = remember { runCatching { entry.extrasSports() }.getOrNull() } ?: return
    val follows by sports.follows.collectAsState()
    val sportsSettings by sports.settings.collectAsState()
    val barPref by prefs.scoreBar.collectAsState()
    val alertPref by prefs.scoreAlerts.collectAsState()
    val scope = rememberCoroutineScope()
    val hasTeams = follows.teams.isNotEmpty()
    val bar = barPref ?: hasTeams
    val alerts = alertPref ?: hasTeams
    val summary = when {
        sportsSettings.hideScores -> "Off while Sports hides scores"
        !hasTeams -> "Pick My Teams in Sports first"
        else -> listOf(if (bar) "Bar on" else "Bar off", if (alerts) "alerts on" else "alerts off").joinToString(" · ")
    }
    section(stringResource(R.string.tvxo_live_score_bar), summary) {
        SettingsToggleRow(
            title = "Live Score Bar",
            subtitle = "A thin strip with your My Teams games that are on now, while a channel, movie or show plays",
            checked = bar,
            onToggle = { scope.launch { prefs.setScoreBar(!bar) } }
        )
        SettingsToggleRow(
            title = "Score Alerts",
            subtitle = "A message when your team's game goes to overtime, is close near the end, or the score changes",
            checked = alerts,
            onToggle = { scope.launch { prefs.setScoreAlerts(!alerts) } }
        )
        Text(
            text = "Uses Sports' My Teams, Score Delay and Hide Scores (Hide Scores turns both off). " +
                "With Sports' own Scoring Alerts on, score changes show there only.",
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm)
        )
    }
}
