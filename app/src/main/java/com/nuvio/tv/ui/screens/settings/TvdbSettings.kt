package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.tvdb.TvdbClient
import com.nuvio.tv.data.tvdb.TvdbEpisodeOrderService
import com.nuvio.tv.data.tvdb.TvdbException
import com.nuvio.tv.data.tvdb.TvdbKeyStore
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class TvdbSettingsState(
    val userKey: String = "",
    val builtIn: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null
)

@HiltViewModel
internal class TvdbSettingsViewModel @Inject constructor(
    private val keys: TvdbKeyStore,
    private val client: TvdbClient
) : ViewModel() {
    val state = MutableStateFlow(TvdbSettingsState(keys.userKey().orEmpty(), keys.hasBuiltInKey()))

    /** Saves the key (blank = use the built-in one) and logs in once to check it. */
    fun saveAndTest(value: String) {
        val trimmed = value.trim()
        state.value = state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            keys.saveUserKey(trimmed.ifBlank { null })
            val message = try {
                client.testConnection()
                "Connected to TVDB."
            } catch (e: CancellationException) {
                throw e
            } catch (e: TvdbException) {
                TvdbEpisodeOrderService.messageFor(e.reason)
            } catch (_: Exception) {
                "Couldn't reach TVDB."
            }
            state.value = TvdbSettingsState(keys.userKey().orEmpty(), keys.hasBuiltInKey(), message = message)
        }
    }
}

/** Integrations hub row: TVDB API key used for TVDB episode orders (DVD, absolute, ...). */
@Composable
internal fun TvdbSettingsRow(viewModel: TvdbSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    SettingsActionRow(
        title = "TVDB",
        subtitle = "API key for TVDB episode orders (aired, DVD, absolute) on show pages",
        value = when {
            state.userKey.isNotBlank() -> "Key set"
            state.builtIn -> "Built-in key"
            else -> "Not set"
        },
        leadingIcon = Icons.Default.FormatListNumbered,
        onClick = { open = true }
    )
    if (!open) return
    var value by remember { mutableStateOf(state.userKey) }
    val keyboard = LocalSoftwareKeyboardController.current
    NuvioDialog(
        onDismiss = { open = false },
        title = "TVDB API key",
        subtitle = "Get a v4 key at thetvdb.com/api-information. Leave blank to use the key built into this app" +
            if (state.builtIn) "." else " (none is built in).",
        width = 700.dp
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                cursorBrush = SolidColor(NuvioTheme.colors.Primary),
                decorationBox = { inner ->
                    if (value.isBlank()) Text("Paste TVDB API key", style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextTertiary)
                    inner()
                }
            )
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text("Metadata provided by TheTVDB. Please consider adding missing information or subscribing.",
            style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
            Button(onClick = { open = false }) { Text("Close") }
            Button(enabled = !state.busy, onClick = { viewModel.saveAndTest(value) }) {
                Text(if (state.busy) "Testing..." else "Save & test")
            }
        }
    }
}
