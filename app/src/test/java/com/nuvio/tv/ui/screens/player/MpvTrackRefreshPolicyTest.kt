package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvTrackRefreshPolicyTest {

    @Test
    fun `unchanged track list is not re-read within the fallback window`() {
        assertFalse(shouldRefreshMpvTracks(generation = 3, refreshedGeneration = 3, sinceLastRefreshMs = 500))
        assertFalse(
            shouldRefreshMpvTracks(
                generation = 3,
                refreshedGeneration = 3,
                sinceLastRefreshMs = MPV_TRACK_REFRESH_FALLBACK_MS - 1
            )
        )
    }

    @Test
    fun `track list change is re-read on the next tick`() {
        assertTrue(shouldRefreshMpvTracks(generation = 4, refreshedGeneration = 3, sinceLastRefreshMs = 0))
    }

    @Test
    fun `never refreshed is re-read`() {
        assertTrue(shouldRefreshMpvTracks(generation = 0, refreshedGeneration = -1, sinceLastRefreshMs = 0))
    }

    @Test
    fun `unchanged track list is still re-read once the fallback window passes`() {
        assertTrue(
            shouldRefreshMpvTracks(
                generation = 3,
                refreshedGeneration = 3,
                sinceLastRefreshMs = MPV_TRACK_REFRESH_FALLBACK_MS
            )
        )
    }
}
