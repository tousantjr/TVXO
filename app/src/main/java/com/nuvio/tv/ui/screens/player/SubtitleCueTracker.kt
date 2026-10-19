package com.nuvio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.text.TextOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SubtitleCueState(
    val version: Long = 0L,
    val visible: Boolean = false
)

internal class SubtitleCueTracker {
    private val mutableState = MutableStateFlow(SubtitleCueState())
    val state: StateFlow<SubtitleCueState> = mutableState.asStateFlow()

    @Volatile
    private var lastKey: String? = null

    fun update(cues: List<Cue>) {
        publish(
            key = cues.joinToString("\u0000") { it.text?.toString().orEmpty() },
            visible = cues.any { !it.text.isNullOrBlank() || it.bitmap != null }
        )
    }

    fun updateText(text: String?) {
        val key = text.orEmpty()
        publish(key = key, visible = key.isNotBlank())
    }

    @Synchronized
    private fun publish(key: String, visible: Boolean) {
        if (key == lastKey) return
        lastKey = key
        mutableState.value = SubtitleCueState(mutableState.value.version + 1, visible)
    }
}

internal class CueTrackingTextOutput(
    private val delegate: TextOutput,
    private val onCuesShown: (List<Cue>) -> Unit
) : TextOutput {
    override fun onCues(cueGroup: CueGroup) {
        delegate.onCues(cueGroup)
        onCuesShown(cueGroup.cues)
    }

    @Deprecated("Uses the deprecated Media3 callback for text outputs.")
    override fun onCues(cues: List<Cue>) {
        @Suppress("DEPRECATION")
        delegate.onCues(cues)
        onCuesShown(cues)
    }
}
