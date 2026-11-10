package com.nuvio.tv.livetv.extras

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guide alerts: shows matching one of your words (Settings → Live TV → Guide alerts) pop up
 * shortly before they start, anywhere in the app, with Watch now / Dismiss. Checked every 30
 * seconds and again whenever the guide is refreshed. Each show alerts once (saved).
 */
@Composable
fun GuideAlertHost(navController: NavController) {
    val context = LocalContext.current
    val entry = remember { extrasEntry(context) } ?: return
    val scope = rememberCoroutineScope()
    var due by remember { mutableStateOf<GuideAlert?>(null) }
    val queue = remember { ArrayDeque<GuideAlert>() }

    LaunchedEffect(Unit) {
        val prefs = entry.extrasPrefs()
        val repo = entry.extrasLiveRepository()
        var loaded = false
        var lastGen = -1
        while (true) {
            val s = prefs.guideAlerts.value
            if (s.enabled && s.keywords.isNotEmpty()) {
                if (!loaded) { repo.ensureLoaded(updateCheckDelayMs = 20_000); loaded = true }
                val now = System.currentTimeMillis()
                val found = runCatching { withContext(Dispatchers.IO) { findDue(entry, s, now) } }.getOrDefault(emptyList())
                found.forEach { a ->
                    prefs.markFired(a.key, a.startMs, now)
                    if (queue.none { it.key == a.key } && due?.key != a.key) queue.addLast(a)
                }
                if (due == null) due = queue.removeFirstOrNull()
            }
            // Wake up early when the guide is refreshed (new listings may match).
            withTimeoutOrNull(30_000) { repo.guideGeneration.first { it != lastGen && lastGen != -1 } }
            lastGen = repo.guideGeneration.value
        }
    }

    val a = due ?: return
    fun next() { due = queue.removeFirstOrNull() }
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(a.startMs))
    ExtrasAlertPopup(
        heading = if (System.currentTimeMillis() < a.startMs) "Guide alert · starts soon" else "Guide alert · starting now",
        title = a.title,
        detail = "$time · ${a.channelName} · matches \"${a.keyword}\"",
        onWatch = {
            val channel = entry.extrasChannelStore().displayChannels.value.firstOrNull { it.key == a.channelKey }
                ?: entry.extrasLiveRepository().channels.value.firstOrNull { it.key == a.channelKey }
            next()
            if (channel != null) scope.launch { watchChannelNow(entry, navController, channel) }
        },
        onDismiss = { if (due?.key == a.key) next() },
        alertKey = a.key
    )
}

/** Upcoming shows on your channels (not hidden ones) that are due to alert now. */
private suspend fun findDue(entry: TvxoExtrasEntryPoint, s: GuideAlertSettings, now: Long): List<GuideAlert> {
    val user = entry.extrasLivePrefs().userState.first()
    val channels = entry.extrasChannelStore().displayChannels.value
        .filter { it.key !in user.hiddenChannels && it.groupId !in user.hiddenGroups }
    if (channels.isEmpty()) return emptyList()
    val names = channels.associate { it.key to it.name }
    val lead = s.leadMinutes.coerceAtLeast(0) * 60_000L
    val listings = entry.extrasGuideDb().range(now - GuideAlertMatcher.LATE_MS, now + lead + 60_000L, light = true)
    val candidates = listings.flatMap { (key, list) ->
        val name = names[key] ?: return@flatMap emptyList()
        list.filter { it.startMs >= now - GuideAlertMatcher.LATE_MS }.map { GuideAlertCandidate(key, name, it.title, it.startMs) }
    }
    val fired = entry.extrasPrefs().firedAlerts.value.keys
    val reminders = user.reminders.mapTo(HashSet()) { GuideAlert.keyOf(it.channelKey, it.startMs) }
    return GuideAlertMatcher.dueAlerts(candidates, s.keywords, s.leadMinutes, now, fired, reminders)
}
