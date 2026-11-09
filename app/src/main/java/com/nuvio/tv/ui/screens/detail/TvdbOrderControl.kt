package com.nuvio.tv.ui.screens.detail

// Modelled on Nuvio ReSeq by abbaskip (GPLv3): ui/screens/detail/TvdbOrderControl.kt.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.tvdb.TvdbEpisodeOrderService
import com.nuvio.tv.data.tvdb.TvdbException
import com.nuvio.tv.data.tvdb.TvdbOrderOption
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.ui.components.NuvioDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class TvdbOrderState(
    val open: Boolean = false,
    val busy: Boolean = false,
    val seriesId: Long? = null,
    val options: List<TvdbOrderOption> = emptyList(),
    /** Saved TVDB order type, or null for provider order. */
    val selected: String? = null,
    val error: String? = null
)

@HiltViewModel
internal class TvdbOrderViewModel @Inject constructor(
    private val service: TvdbEpisodeOrderService,
    private val profiles: ProfileManager
) : ViewModel() {
    val state = MutableStateFlow(TvdbOrderState())
    private var job: Job? = null

    fun open(meta: Meta) {
        job?.cancel()
        state.value = TvdbOrderState(open = true, busy = true)
        job = viewModelScope.launch {
            val profileId = profiles.activeProfileId.value
            val saved = runCatching { service.savedChoice(profileId, meta) }.getOrNull()
            try {
                val seriesId = saved?.seriesId ?: service.seriesId(meta)
                if (seriesId == null) {
                    state.value = TvdbOrderState(open = true, selected = saved?.type,
                        error = "This show couldn't be found on TVDB (it needs an IMDb, TMDB or TVDB id).")
                    return@launch
                }
                state.value = TvdbOrderState(open = true, seriesId = seriesId,
                    options = service.availableOrders(seriesId), selected = saved?.type)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TvdbException) {
                state.value = TvdbOrderState(open = true, selected = saved?.type,
                    error = TvdbEpisodeOrderService.messageFor(e.reason))
            } catch (_: Exception) {
                state.value = TvdbOrderState(open = true, selected = saved?.type, error = "Couldn't load TVDB orders. Try again.")
            }
        }
    }

    fun choose(meta: Meta, type: String?, onChanged: () -> Unit) {
        val current = state.value
        val seriesId = current.seriesId ?: meta.episodeOrder?.seriesId ?: if (type == null) 0L else return
        state.value = current.copy(busy = true)
        job = viewModelScope.launch {
            try {
                service.saveChoice(profiles.activeProfileId.value, meta, seriesId, type)
                close()
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.value = state.value.copy(busy = false, error = "Couldn't save the episode order.")
            }
        }
    }

    fun close() {
        job?.cancel()
        job = null
        state.value = TvdbOrderState()
    }
}

@Composable
internal fun TvdbOrderButton(meta: Meta, onChanged: () -> Unit, viewModel: TvdbOrderViewModel = hiltViewModel()) {
    if (!meta.apiType.equals("series", ignoreCase = true) || meta.videos.none { it.season != null }) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    val order = meta.episodeOrder
    val label = when {
        order == null -> "Episode order"
        order.problem != null -> "Order: provider"
        else -> "Order: ${order.label.removeSuffix(" order")}"
    }
    Button(onClick = { viewModel.open(meta) }) { Text(label, maxLines = 1) }
    if (!state.open) return
    NuvioDialog(
        onDismiss = viewModel::close,
        title = "Episode order",
        subtitle = meta.name,
        width = 620.dp,
        usePlatformDefaultWidth = false,
        contentPadding = 16.dp,
        contentSpacing = 8.dp
    ) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Changes only how seasons and episode numbers are shown. Streams, watch progress and Trakt/Simkl stay on the provider's numbering.",
                style = MaterialTheme.typography.bodyMedium
            )
            order?.problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (order != null && order.problem == null && order.unmatched > 0) {
                Text("${order.unmatched} episode(s) TVDB couldn't place are listed under Specials.", style = MaterialTheme.typography.bodySmall)
            }
            if (state.busy) Text("Contacting TVDB...")
            state.error?.let { Text(it) }
            OrderChoice("Provider order (default)", state.selected == null, !state.busy) {
                viewModel.choose(meta, null, onChanged)
            }
            state.options.forEach { option ->
                OrderChoice("${option.label} (TVDB)", state.selected == option.type, !state.busy) {
                    viewModel.choose(meta, option.type, onChanged)
                }
            }
            Button(modifier = Modifier.height(40.dp), onClick = viewModel::close) { Text("Close") }
            Text("Metadata provided by TheTVDB. Please consider adding missing information or subscribing at thetvdb.com.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun OrderChoice(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(modifier = Modifier.fillMaxWidth().height(40.dp), enabled = enabled, onClick = onClick) {
        Text((if (selected) "✓ " else "") + text, maxLines = 1)
    }
}
