package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Test

/** TVXO: extra XMLTV guides fill empty time slots of the provider's guide, and channel names match loosely. */
class ExtraGuideMergeTest {
    private fun p(start: Long, stop: Long, title: String) = EpgProgram(startMs = start, stopMs = stop, title = title)

    @Test
    fun `extra guide fills only gaps`() {
        val provider = listOf(p(0, 60, "News"), p(120, 180, "Film"))
        val extra = listOf(p(0, 60, "X News"), p(60, 120, "Quiz"), p(150, 210, "Overlap"), p(180, 240, "Late"))
        val merged = LiveTvRepository.fillGaps(provider, extra)
        assertEquals(listOf("News", "Quiz", "Film", "Late"), merged.map { it.title })
    }

    @Test
    fun `empty provider takes the whole extra guide`() {
        val extra = listOf(p(0, 60, "A"), p(60, 120, "B"))
        assertEquals(extra, LiveTvRepository.fillGaps(emptyList(), extra))
        assertEquals(extra, LiveTvRepository.fillGaps(extra, emptyList()))
    }

    @Test
    fun `channel names are normalised for matching`() {
        val key = LiveTvRepository.normalize("BBC One")
        assertEquals(key, LiveTvRepository.normalize("UK: BBC One HD"))
        assertEquals(key, LiveTvRepository.normalize("|UK| BBC ONE FHD"))
        assertEquals(key, LiveTvRepository.normalize("bbc one (4K)"))
        assertEquals(LiveTvRepository.normalize("CNN"), LiveTvRepository.normalize("US: CNN 4K"))
    }
}
