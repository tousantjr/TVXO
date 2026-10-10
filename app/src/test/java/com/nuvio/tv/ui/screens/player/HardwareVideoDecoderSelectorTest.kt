package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareVideoDecoderSelectorTest {

    private fun decoder(name: String, mimeType: String, softwareOnly: Boolean): MediaCodecInfo =
        MediaCodecInfo.newInstance(
            name,
            mimeType,
            mimeType,
            null,
            !softwareOnly,
            softwareOnly,
            !softwareOnly,
            false,
            false,
        )

    private fun selector(vararg decoders: MediaCodecInfo): MediaCodecSelector =
        hardwareFirstVideoCodecSelector { mimeType, _, _ -> decoders.filter { it.mimeType == mimeType } }

    private fun names(selector: MediaCodecSelector, mimeType: String): List<String> =
        selector.getDecoderInfos(mimeType, false, false).map { it.name }

    @Test
    fun `software HEVC and H264 are dropped when the box has hardware`() {
        val box = selector(
            decoder("c2.amlogic.hevc.decoder", MimeTypes.VIDEO_H265, softwareOnly = false),
            decoder("c2.android.hevc.decoder", MimeTypes.VIDEO_H265, softwareOnly = true),
            decoder("OMX.amlogic.avc.decoder.awesome2", MimeTypes.VIDEO_H264, softwareOnly = false),
            decoder("OMX.google.h264.decoder", MimeTypes.VIDEO_H264, softwareOnly = true),
            decoder("c2.android.avc.decoder", MimeTypes.VIDEO_H264, softwareOnly = true),
        )
        assertEquals(listOf("c2.amlogic.hevc.decoder"), names(box, MimeTypes.VIDEO_H265))
        assertEquals(listOf("OMX.amlogic.avc.decoder.awesome2"), names(box, MimeTypes.VIDEO_H264))
    }

    @Test
    fun `software stays when it is the only decoder for the type`() {
        val box = selector(
            decoder("c2.android.vp9.decoder", MimeTypes.VIDEO_VP9, softwareOnly = true),
            decoder("c2.android.av1.decoder", MimeTypes.VIDEO_AV1, softwareOnly = true),
        )
        assertEquals(listOf("c2.android.vp9.decoder"), names(box, MimeTypes.VIDEO_VP9))
        assertEquals(listOf("c2.android.av1.decoder"), names(box, MimeTypes.VIDEO_AV1))
    }

    @Test
    fun `name prefix catches a software decoder the flag misses`() {
        val box = selector(
            decoder("c2.mtk.hevc.decoder", MimeTypes.VIDEO_H265, softwareOnly = false),
            decoder("c2.android.hevc.decoder", MimeTypes.VIDEO_H265, softwareOnly = false),
        )
        assertEquals(listOf("c2.mtk.hevc.decoder"), names(box, MimeTypes.VIDEO_H265))
    }

    @Test
    fun `hardware order is kept`() {
        val box = selector(
            decoder("c2.amlogic.dolby-vision.dvhe.decoder", MimeTypes.VIDEO_DOLBY_VISION, softwareOnly = false),
            decoder("c2.android.dv.decoder", MimeTypes.VIDEO_DOLBY_VISION, softwareOnly = true),
            decoder("c2.amlogic.dolby-vision.dvav.decoder", MimeTypes.VIDEO_DOLBY_VISION, softwareOnly = false),
        )
        assertEquals(
            listOf("c2.amlogic.dolby-vision.dvhe.decoder", "c2.amlogic.dolby-vision.dvav.decoder"),
            names(box, MimeTypes.VIDEO_DOLBY_VISION),
        )
    }

    @Test
    fun `audio and other video types are untouched`() {
        val box = selector(
            decoder("OMX.amlogic.ac3.decoder", MimeTypes.AUDIO_AC3, softwareOnly = false),
            decoder("c2.android.ac3.decoder", MimeTypes.AUDIO_AC3, softwareOnly = true),
            decoder("c2.android.aac.decoder", MimeTypes.AUDIO_AAC, softwareOnly = true),
            decoder("c2.amlogic.vc1.decoder", "video/vc1", softwareOnly = false),
            decoder("c2.amlogic.vc1.decoder.sw", "video/vc1", softwareOnly = true),
        )
        assertEquals(listOf("OMX.amlogic.ac3.decoder", "c2.android.ac3.decoder"), names(box, MimeTypes.AUDIO_AC3))
        assertEquals(listOf("c2.android.aac.decoder"), names(box, MimeTypes.AUDIO_AAC))
        assertEquals(listOf("c2.amlogic.vc1.decoder", "c2.amlogic.vc1.decoder.sw"), names(box, "video/vc1"))
    }

    @Test
    fun `empty list stays empty`() {
        assertTrue(hardwareFirstVideoDecoders(MimeTypes.VIDEO_H265, emptyList<String>()) { true }.isEmpty())
    }

    @Test
    fun `decoder names in the log line`() {
        val softwareOnly = setOf("c2.amlogic.vc1.decoder.sw")
        assertTrue(isSoftwareVideoDecoderName("c2.android.hevc.decoder", emptySet()))
        assertTrue(isSoftwareVideoDecoderName("OMX.google.h264.decoder", emptySet()))
        assertTrue(isSoftwareVideoDecoderName("libdav1d", emptySet()))
        assertTrue(isSoftwareVideoDecoderName("libgav1", emptySet()))
        assertTrue(isSoftwareVideoDecoderName("C2.AMLOGIC.VC1.DECODER.SW", softwareOnly))
        assertFalse(isSoftwareVideoDecoderName("c2.amlogic.hevc.decoder", softwareOnly))
        assertFalse(isSoftwareVideoDecoderName("c2.amlogic.dolby-vision.dvhe.decoder", softwareOnly))
        assertFalse(isSoftwareVideoDecoderName("amstream_dves_hevc.sideband", softwareOnly))
        assertFalse(isSoftwareVideoDecoderName("OMX.MTK.VIDEO.DECODER.HEVC", softwareOnly))
    }
}
