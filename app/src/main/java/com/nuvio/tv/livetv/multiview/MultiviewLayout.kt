package com.nuvio.tv.livetv.multiview

import androidx.media3.common.PlaybackException

/**
 * Multiview's pure rules: how tiles are laid out, how the D-pad moves between them, which tile
 * is heard, how many streams a device may play at once and which player errors mean "no more
 * decoders". Kept free of Android UI so it can be unit tested.
 */
internal object MultiviewLayout {
    const val MAX_TILES = 4

    /** Rows and columns of the tile grid. */
    data class Grid(val rows: Int, val cols: Int)

    enum class Direction { LEFT, RIGHT, UP, DOWN }

    /** 1 tile: full; 2: side by side; 3 or 4: a 2×2 grid. */
    fun gridFor(count: Int): Grid = when {
        count <= 1 -> Grid(1, 1)
        count == 2 -> Grid(1, 2)
        else -> Grid(2, 2)
    }

    /** Tiles on screen for [channels] channels: each channel plus an "Add channel" tile while there's room. */
    fun slotCount(channels: Int): Int {
        val c = channels.coerceIn(0, MAX_TILES)
        return if (c < MAX_TILES) c + 1 else c
    }

    /** The tile the D-pad lands on from [focus], or null at the edge (nothing there). */
    fun move(focus: Int, count: Int, direction: Direction): Int? {
        if (count <= 0) return null
        val grid = gridFor(count)
        val from = focus.coerceIn(0, count - 1)
        val row = from / grid.cols
        val col = from % grid.cols
        val (r, c) = when (direction) {
            Direction.LEFT -> row to col - 1
            Direction.RIGHT -> row to col + 1
            Direction.UP -> row - 1 to col
            Direction.DOWN -> row + 1 to col
        }
        if (r !in 0 until grid.rows || c !in 0 until grid.cols) return null
        val target = r * grid.cols + c
        if (target < count) return target
        // Three tiles: Down from the top-right tile goes to the bottom-left one (the only one there).
        return if (direction == Direction.DOWN && r * grid.cols < count) count - 1 else null
    }

    /** Volume per tile: only the focused tile is heard. */
    fun volumes(count: Int, focus: Int): List<Float> = List(count.coerceAtLeast(0)) { if (it == focus) 1f else 0f }

    /** How many tiles may play at once: 2 on low-memory devices, else 4. */
    fun maxStreams(lowRamDevice: Boolean, heapMb: Long): Int =
        if (lowRamDevice || heapMb in 1L until 256L) 2 else MAX_TILES

    /** True when a player error means the device ran out of video decoders (not a bad stream). */
    fun isDecoderLimit(errorCode: Int): Boolean = errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
        errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED

    /** Focus after removing tile [removed] when [slotsAfter] tiles remain. */
    fun focusAfterRemove(focus: Int, removed: Int, slotsAfter: Int): Int {
        if (slotsAfter <= 0) return 0
        val f = if (focus > removed) focus - 1 else focus
        return f.coerceIn(0, slotsAfter - 1)
    }
}

/** List helpers for the tiles (any item type, so they can be tested without channels). */
internal object MultiviewTiles {
    /** Adds [item] when there's room and it isn't on screen yet. */
    fun <T> add(tiles: List<T>, item: T, same: (T, T) -> Boolean = { a, b -> a == b }): List<T> =
        if (tiles.size >= MultiviewLayout.MAX_TILES || tiles.any { same(it, item) }) tiles else tiles + item

    fun <T> remove(tiles: List<T>, index: Int): List<T> =
        if (index !in tiles.indices) tiles else tiles.filterIndexed { i, _ -> i != index }

    /** Puts [item] in tile [index]; if it's already in another tile, the two trade places. */
    fun <T> swap(tiles: List<T>, index: Int, item: T, same: (T, T) -> Boolean = { a, b -> a == b }): List<T> {
        if (index !in tiles.indices) return tiles
        val other = tiles.indexOfFirst { same(it, item) }
        val out = tiles.toMutableList()
        if (other >= 0 && other != index) out[other] = tiles[index]
        out[index] = item
        return out
    }
}
