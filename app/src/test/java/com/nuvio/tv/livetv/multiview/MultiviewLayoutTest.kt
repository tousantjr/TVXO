package com.nuvio.tv.livetv.multiview

import androidx.media3.common.PlaybackException
import com.nuvio.tv.livetv.multiview.MultiviewLayout.Direction
import com.nuvio.tv.livetv.multiview.MultiviewLayout.Grid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewLayoutTest {

    @Test
    fun gridForTileCount() {
        assertEquals(Grid(1, 1), MultiviewLayout.gridFor(0))
        assertEquals(Grid(1, 1), MultiviewLayout.gridFor(1))
        assertEquals(Grid(1, 2), MultiviewLayout.gridFor(2))
        assertEquals(Grid(2, 2), MultiviewLayout.gridFor(3))
        assertEquals(Grid(2, 2), MultiviewLayout.gridFor(4))
    }

    @Test
    fun slotCountAddsAnAddTileUntilFull() {
        assertEquals(1, MultiviewLayout.slotCount(0))
        assertEquals(2, MultiviewLayout.slotCount(1))
        assertEquals(4, MultiviewLayout.slotCount(3))
        assertEquals(4, MultiviewLayout.slotCount(4))
        assertEquals(4, MultiviewLayout.slotCount(9))
    }

    @Test
    fun moveSideBySide() {
        assertEquals(1, MultiviewLayout.move(0, 2, Direction.RIGHT))
        assertEquals(0, MultiviewLayout.move(1, 2, Direction.LEFT))
        assertNull(MultiviewLayout.move(0, 2, Direction.LEFT))
        assertNull(MultiviewLayout.move(1, 2, Direction.RIGHT))
        assertNull(MultiviewLayout.move(0, 2, Direction.UP))
        assertNull(MultiviewLayout.move(0, 2, Direction.DOWN))
    }

    @Test
    fun moveInFullGrid() {
        assertEquals(2, MultiviewLayout.move(0, 4, Direction.DOWN))
        assertEquals(3, MultiviewLayout.move(1, 4, Direction.DOWN))
        assertEquals(1, MultiviewLayout.move(3, 4, Direction.UP))
        assertEquals(2, MultiviewLayout.move(3, 4, Direction.LEFT))
        assertNull(MultiviewLayout.move(2, 4, Direction.DOWN))
        assertNull(MultiviewLayout.move(1, 4, Direction.UP))
    }

    @Test
    fun moveWithThreeTiles() {
        // Bottom-right is empty: Down from top-right goes to the only bottom tile.
        assertEquals(2, MultiviewLayout.move(1, 3, Direction.DOWN))
        assertEquals(2, MultiviewLayout.move(0, 3, Direction.DOWN))
        assertNull(MultiviewLayout.move(2, 3, Direction.RIGHT))
        assertEquals(0, MultiviewLayout.move(2, 3, Direction.UP))
    }

    @Test
    fun moveSingleTileAndEmpty() {
        for (d in Direction.entries) assertNull(MultiviewLayout.move(0, 1, d))
        assertNull(MultiviewLayout.move(0, 0, Direction.RIGHT))
    }

    @Test
    fun onlyFocusedTileIsHeard() {
        assertEquals(listOf(0f, 1f, 0f, 0f), MultiviewLayout.volumes(4, 1))
        assertEquals(listOf(1f), MultiviewLayout.volumes(1, 0))
        assertEquals(emptyList<Float>(), MultiviewLayout.volumes(0, 0))
    }

    @Test
    fun streamLimit() {
        assertEquals(2, MultiviewLayout.maxStreams(lowRamDevice = true, heapMb = 512))
        assertEquals(2, MultiviewLayout.maxStreams(lowRamDevice = false, heapMb = 192))
        assertEquals(4, MultiviewLayout.maxStreams(lowRamDevice = false, heapMb = 512))
        // Unknown heap: not treated as low memory.
        assertEquals(4, MultiviewLayout.maxStreams(lowRamDevice = false, heapMb = 0))
    }

    @Test
    fun decoderErrorsAreRecognised() {
        assertTrue(MultiviewLayout.isDecoderLimit(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertTrue(MultiviewLayout.isDecoderLimit(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES))
        assertFalse(MultiviewLayout.isDecoderLimit(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertFalse(MultiviewLayout.isDecoderLimit(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
    }

    @Test
    fun focusAfterRemove() {
        assertEquals(0, MultiviewLayout.focusAfterRemove(focus = 1, removed = 0, slotsAfter = 3))
        assertEquals(1, MultiviewLayout.focusAfterRemove(focus = 1, removed = 2, slotsAfter = 3))
        assertEquals(1, MultiviewLayout.focusAfterRemove(focus = 3, removed = 3, slotsAfter = 2))
        assertEquals(0, MultiviewLayout.focusAfterRemove(focus = 2, removed = 0, slotsAfter = 0))
    }

    @Test
    fun tileListOperations() {
        assertEquals(listOf("a", "b"), MultiviewTiles.add(listOf("a"), "b"))
        assertEquals(listOf("a"), MultiviewTiles.add(listOf("a"), "a"))
        assertEquals(listOf("a", "b", "c", "d"), MultiviewTiles.add(listOf("a", "b", "c", "d"), "e"))
        assertEquals(listOf("a", "c"), MultiviewTiles.remove(listOf("a", "b", "c"), 1))
        assertEquals(listOf("a"), MultiviewTiles.remove(listOf("a"), 5))
        assertEquals(listOf("a", "x", "c"), MultiviewTiles.swap(listOf("a", "b", "c"), 1, "x"))
        // Already on screen: the two tiles trade channels.
        assertEquals(listOf("c", "b", "a"), MultiviewTiles.swap(listOf("a", "b", "c"), 0, "c"))
        assertEquals(listOf("a", "b"), MultiviewTiles.swap(listOf("a", "b"), 7, "z"))
    }
}
