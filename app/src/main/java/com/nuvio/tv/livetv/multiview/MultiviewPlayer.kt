package com.nuvio.tv.livetv.multiview

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

/**
 * One Multiview tile's own player. Built like the Live TV player (decoder fallback, extension
 * renderers, Dolby/DTS decoded to plain sound), but with a smaller download-ahead since up to four
 * play at once, no audio focus handling (the tiles would take it from each other) and video
 * capped at 1080p (a tile is never bigger than that).
 */
@OptIn(UnstableApi::class)
internal fun buildMultiviewPlayer(context: Context): ExoPlayer {
    val renderers = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): androidx.media3.exoplayer.audio.AudioSink? {
            @Suppress("DEPRECATION")
            return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }
    }
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(10_000, 25_000, 1_500, 3_000)
        .setPrioritizeTimeOverSizeThresholds(true)
        .setTargetBufferBytes(24 shl 20)
        .build()
    return ExoPlayer.Builder(context, renderers)
        .setLoadControl(loadControl)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ false
        )
        .build()
        .apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon().setMaxVideoSize(1920, 1080).build()
            playWhenReady = true
        }
}
