package com.nuvio.tv.livetv.coverage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.parental.ParentalControls
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

sealed interface GuideCoverageState {
    data object Loading : GuideCoverageState
    /** No enabled playlist with live channels. */
    data object NoPlaylists : GuideCoverageState
    /** Playlists, but no channels loaded or no guide stored yet. */
    data class NoGuide(val channels: Int) : GuideCoverageState
    data class Ready(val report: GuideCoverage.Report, val showPlaylists: Boolean) : GuideCoverageState
    data class Failed(val message: String) : GuideCoverageState
}

/** Works out the Guide Coverage report off the main thread, from the stored channels and guide. */
@HiltViewModel
class GuideCoverageViewModel @Inject constructor(
    private val repository: LiveTvRepository,
    private val prefs: LiveTvPreferences,
    private val guideDb: EpgDatabase
) : ViewModel() {

    private val _state = MutableStateFlow<GuideCoverageState>(GuideCoverageState.Loading)
    val state: StateFlow<GuideCoverageState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        repository.ensureLoaded()
        refresh()
    }

    fun refresh() {
        job?.cancel()
        _state.value = GuideCoverageState.Loading
        job = viewModelScope.launch {
            _state.value = runCatching { withContext(Dispatchers.IO) { build() } }
                .getOrElse { GuideCoverageState.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    private suspend fun build(): GuideCoverageState {
        val playlists = prefs.currentPlaylists().filter { it.liveEnabled }
        if (playlists.isEmpty()) return GuideCoverageState.NoPlaylists
        // Channels are read in the background at start-up; give them a moment.
        val all = repository.channels.value.ifEmpty {
            withTimeoutOrNull(15_000) { repository.channels.first { it.isNotEmpty() } }.orEmpty()
        }
        val enabledIds = playlists.mapTo(HashSet()) { it.id }
        val hidden = prefs.currentHiddenEverywhere()
        val settings = prefs.currentSettings()
        val user = prefs.userState.first()
        // Same rules as the guide: turned-off playlists and channels hidden everywhere don't count.
        val channels = all.asSequence()
            .filter { it.sourceId in enabledIds && !hidden.hides(it.key, it.groupId) }
            .map {
                GuideCoverage.Channel(
                    key = it.key,
                    name = it.name,
                    groupId = it.groupId,
                    group = it.group.ifBlank { "Uncategorized" },
                    playlistId = it.sourceId,
                    playlistName = it.sourceName,
                    locked = ParentalControls.isLocked(it.groupId, it.group, settings, user.lockedGroups)
                )
            }.toList()
        if (channels.isEmpty()) return GuideCoverageState.NoGuide(0)
        val keys = channels.mapTo(HashSet()) { it.key }
        val now = System.currentTimeMillis()
        val covered = guideDb.channelsWithListings(now, now + GuideCoverage.WINDOW_MS, keys)
        if (covered.isEmpty() && guideDb.channelsWithListings(channels = keys).isEmpty()) {
            return GuideCoverageState.NoGuide(channels.size)
        }
        val bindings = guideDb.guideBindings(keys).mapValues { (_, b) -> b.sourceId to b.programs }
        val used = bindings.values.mapTo(HashSet()) { it.first }
        // Your enabled guides always show (even at 0); a playlist's own guide shows if it feeds channels.
        val guideNames = LinkedHashMap<String, String>()
        playlists.filter { it.id in used }.forEach { guideNames[it.id] = "${it.name} (built-in)" }
        if (EpgDatabase.OWN_SOURCE in used) guideNames[EpgDatabase.OWN_SOURCE] = "Custom listings"
        prefs.currentEpgSources().filter { it.enabled }.forEach { guideNames[it.id] = it.name }
        val report = GuideCoverage.compute(channels, covered, bindings, guideNames)
        return GuideCoverageState.Ready(report, showPlaylists = report.playlists.size > 1)
    }
}
