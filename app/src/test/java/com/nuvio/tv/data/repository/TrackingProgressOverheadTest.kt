package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.data.mdblist.MdbListIds
import com.nuvio.tv.data.mdblist.MdbListItemType
import com.nuvio.tv.data.mdblist.MdbListMedia
import com.nuvio.tv.data.mdblist.MdbListMutationTarget
import com.nuvio.tv.data.mdblist.MdbListPlayback
import com.nuvio.tv.data.mdblist.MdbListProgressProjection
import com.nuvio.tv.data.mdblist.MdbListScrobbleReceipt
import com.nuvio.tv.data.mdblist.MdbListSyncSnapshot
import com.nuvio.tv.data.mdblist.MdbListWatchedRecord
import com.nuvio.tv.data.mdblist.applyScrobble
import com.nuvio.tv.data.simkl.SimklLibraryEntry
import com.nuvio.tv.data.simkl.SimklListStatus
import com.nuvio.tv.data.simkl.SimklMedia
import com.nuvio.tv.data.simkl.SimklMediaType
import com.nuvio.tv.data.simkl.SimklPlaybackEpisode
import com.nuvio.tv.data.simkl.SimklScrobbleOutcome
import com.nuvio.tv.data.simkl.SimklScrobbleResult
import com.nuvio.tv.data.simkl.SimklSnapshotProjection
import com.nuvio.tv.data.simkl.SimklSyncSnapshot
import com.nuvio.tv.data.simkl.applyScrobbleResult
import com.nuvio.tv.data.simkl.enrichMediaReference
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingProgressOverheadTest {

    @Test
    fun `verify - simkl withUpdatedPlayback reuses projection collections and entryIndex when entries are unchanged`() {
        val testMedia = SimklMedia(title = "Movie 1", ids = mapOf("imdb" to JsonPrimitive("tt001")))
        val historicalEntries = (1..100).map { i ->
            SimklLibraryEntry(
                mediaType = if (i % 2 == 0) SimklMediaType.MOVIES else SimklMediaType.SHOWS,
                movie = if (i % 2 == 0) SimklMedia(title = "Movie $i", ids = mapOf("imdb" to JsonPrimitive("tt$i"))) else null,
                show = if (i % 2 != 0) SimklMedia(title = "Show $i", ids = mapOf("imdb" to JsonPrimitive("tt$i"))) else null
            )
        }
        val snapshot = SimklSyncSnapshot(
            isInitialized = true,
            watermark = "watermark-1",
            entries = historicalEntries,
            playback = emptyList()
        )
        val initialProjection = SimklSnapshotProjection.create(snapshot)

        val pauseResult = SimklScrobbleResult(
            outcome = SimklScrobbleOutcome.PAUSE,
            playbackId = 999L,
            progress = 35.5,
            mediaType = SimklMediaType.MOVIES,
            media = testMedia,
            episode = null
        )

        val updatedSnapshot = snapshot.applyScrobbleResult(
            result = pauseResult,
            committedAtEpochMs = System.currentTimeMillis(),
            entryIndex = initialProjection.entryIndex
        )

        val updatedProjection = initialProjection.withUpdatedPlayback(updatedSnapshot)

        // Verify that entries-derived collections and entryIndex are REUSED by reference
        assertTrue(initialProjection.library === updatedProjection.library)
        assertTrue(initialProjection.watched === updatedProjection.watched)
        assertTrue(initialProjection.watchedShowEpisodes === updatedProjection.watchedShowEpisodes)
        assertTrue(initialProjection.showIdSiblings === updatedProjection.showIdSiblings)
        assertTrue(initialProjection.entryIndex === updatedProjection.entryIndex)
        assertEquals(1, updatedProjection.progress.size)
    }

    @Test
    fun `verify - simkl entryIndex enriches media reference and reconciles completed scrobble in O(1)`() {
        val entries = (1..500).map { i ->
            SimklLibraryEntry(
                mediaType = SimklMediaType.SHOWS,
                status = SimklListStatus.WATCHING,
                show = SimklMedia(
                    title = "Show $i",
                    year = 2020,
                    ids = mapOf(
                        "imdb" to JsonPrimitive("tt$i"),
                        "simkl" to JsonPrimitive(i * 10)
                    )
                )
            )
        }
        val snapshot = SimklSyncSnapshot(
            isInitialized = true,
            entries = entries,
            playback = emptyList()
        )
        val projection = SimklSnapshotProjection.create(snapshot)

        val partialReference = TrackingMediaReference(
            kind = TrackingMediaKind.SHOW,
            title = null,
            year = null,
            ids = TrackingExternalIds(imdb = "tt250")
        )
        val enriched = snapshot.enrichMediaReference(
            reference = partialReference,
            entryIndex = projection.entryIndex
        )
        assertEquals("Show 250", enriched.title)
        assertEquals(2020, enriched.year)
        assertEquals(2500L, enriched.ids.simkl)

        val stopResult = SimklScrobbleResult(
            outcome = SimklScrobbleOutcome.SCROBBLE,
            playbackId = 77L,
            progress = 92.0,
            mediaType = SimklMediaType.SHOWS,
            media = SimklMedia(title = "Show 250", ids = mapOf("imdb" to JsonPrimitive("tt250"))),
            episode = SimklPlaybackEpisode(season = 1, number = 3)
        )
        val updatedSnapshot = snapshot.applyScrobbleResult(
            result = stopResult,
            committedAtEpochMs = 1_700_000_000_000L,
            entryIndex = projection.entryIndex
        )
        // Unrelated entries are preserved by reference while entry 250 (index 249) gets the episode
        assertTrue(snapshot.entries[0] === updatedSnapshot.entries[0])
        assertEquals(1, updatedSnapshot.entries[249].seasons.single().episodes.size)
    }

    @Test
    fun `verify - mdblist withUpdatedPlayback reuses watched collections and showIdSiblings without copying`() {
        val snapshot = MdbListSyncSnapshot(
            accountId = 123,
            watched = listOf(
                MdbListWatchedRecord(
                    type = MdbListItemType.MOVIE,
                    media = MdbListMedia(ids = MdbListIds(imdb = "tt100")),
                    watchedAt = "2026-01-01T00:00:00Z"
                ),
                MdbListWatchedRecord(
                    type = MdbListItemType.SHOW,
                    media = MdbListMedia(ids = MdbListIds(imdb = "tt300", tmdb = 300L)),
                    season = 1,
                    episode = 1,
                    watchedAt = "2026-01-01T00:00:00Z"
                )
            ),
            playback = emptyList()
        )
        val initialProjection = MdbListProgressProjection(snapshot)

        // Case 1: Known show playback reuses watchedShowIdSiblings by reference
        val knownShowPlayback = listOf(
            MdbListPlayback(
                id = 1L,
                type = MdbListItemType.SHOW,
                media = MdbListMedia(ids = MdbListIds(imdb = "tt300", tmdb = 300L)),
                season = 1,
                episode = 2,
                progress = 45f,
                updatedAt = "2026-01-02T00:00:00Z"
            )
        )
        val knownShowProjection = initialProjection.withUpdatedPlayback(knownShowPlayback)
        assertTrue(initialProjection.watchedItems === knownShowProjection.watchedItems)
        assertTrue(initialProjection.watchedMovieIds === knownShowProjection.watchedMovieIds)
        assertTrue(initialProjection.watchedShowEpisodes === knownShowProjection.watchedShowEpisodes)
        assertTrue(initialProjection.showIdSiblings === knownShowProjection.showIdSiblings)
        assertTrue(initialProjection.mediaIndex === knownShowProjection.mediaIndex)

        // Case 2: Brand-new show playback uses overlay map and resolves both watched and new show siblings
        val newShowPlayback = listOf(
            MdbListPlayback(
                id = 2L,
                type = MdbListItemType.SHOW,
                media = MdbListMedia(ids = MdbListIds(imdb = "tt999", tmdb = 999L)),
                season = 1,
                episode = 1,
                progress = 30f,
                updatedAt = "2026-01-02T00:00:00Z"
            )
        )
        val newShowProjection = initialProjection.withUpdatedPlayback(newShowPlayback)
        assertEquals(
            setOf("tt300", "imdb:tt300", "tmdb:300"),
            newShowProjection.showIdSiblings["tt300"]
        )
        assertEquals(
            setOf("tt999", "imdb:tt999", "tmdb:999"),
            newShowProjection.showIdSiblings["tt999"]
        )
    }

    @Test
    fun `verify - simkl applyScrobbleResult on pause with large library is fast and preserves entries reference`() {
        val entries = (1..1000).map { i ->
            SimklLibraryEntry(
                mediaType = if (i % 2 == 0) SimklMediaType.MOVIES else SimklMediaType.SHOWS,
                movie = if (i % 2 == 0) SimklMedia(title = "Movie $i", ids = mapOf("imdb" to JsonPrimitive("tt$i"))) else null,
                show = if (i % 2 != 0) SimklMedia(title = "Show $i", ids = mapOf("imdb" to JsonPrimitive("tt$i"))) else null
            )
        }
        val snapshot = SimklSyncSnapshot(
            isInitialized = true,
            entries = entries,
            playback = emptyList()
        )
        val projection = SimklSnapshotProjection.create(snapshot)
        val pauseResult = SimklScrobbleResult(
            outcome = SimklScrobbleOutcome.PAUSE,
            playbackId = 42L,
            progress = 25.0,
            mediaType = SimklMediaType.MOVIES,
            media = SimklMedia(title = "Movie 500", ids = mapOf("imdb" to JsonPrimitive("tt500"))),
            episode = null
        )

        val startTime = System.nanoTime()
        val updated = snapshot.applyScrobbleResult(pauseResult, System.currentTimeMillis(), projection.entryIndex)
        val durationMs = (System.nanoTime() - startTime) / 1_000_000

        assertTrue(snapshot.entries === updated.entries)
        assertEquals(1, updated.playback.size)
        assertTrue("Expected < 50ms but was ${durationMs}ms", durationMs < 50)
    }

    @Test
    fun `verify - mdblist applyScrobble on pause and completed stop uses mediaIndex without scanning unrelated watched items`() {
        val watched = (1..2500).map { i ->
            MdbListWatchedRecord(
                type = MdbListItemType.MOVIE,
                media = MdbListMedia(ids = MdbListIds(imdb = "tt$i", tmdb = i.toLong())),
                watchedAt = "2026-01-01T00:00:00Z"
            )
        }
        val snapshot = MdbListSyncSnapshot(
            accountId = 123,
            watched = watched,
            playback = emptyList()
        )
        val projection = MdbListProgressProjection(snapshot)
        val target = MdbListMutationTarget(
            type = MdbListItemType.MOVIE,
            media = MdbListMedia(ids = MdbListIds(imdb = "tt500"))
        )
        val pauseReceipt = MdbListScrobbleReceipt(
            target = target,
            action = TrackingScrobbleAction.PAUSE,
            progress = 42f,
            timestamp = "2026-01-02T00:00:00Z",
            playbackId = 999L
        )

        val paused = snapshot.applyScrobble(pauseReceipt, projection.mediaIndex)
        assertTrue(snapshot.watched === paused.watched)
        assertEquals(1, paused.playback.size)
        assertEquals(500L, paused.playback.single().media.ids.tmdb)

        val stopReceipt = MdbListScrobbleReceipt(
            target = MdbListMutationTarget(
                type = MdbListItemType.MOVIE,
                media = MdbListMedia(title = "Enriched Movie 500", ids = MdbListIds(imdb = "tt500", trakt = 5000L))
            ),
            action = TrackingScrobbleAction.STOP,
            progress = 90f,
            timestamp = "2026-01-03T00:00:00Z",
            playbackId = 999L
        )
        val completed = paused.applyScrobble(stopReceipt, projection.mediaIndex)
        assertTrue(completed.playback.isEmpty())
        assertEquals(2500, completed.watched.size)
        // Unrelated watched record is preserved by reference at its original position
        assertTrue(snapshot.watched.first() === completed.watched.first())
        // Matched watched record was enriched and placed at the end with latest watchedAt
        assertEquals("tt500", completed.watched.last().media.ids.imdb)
        assertEquals(500L, completed.watched.last().media.ids.tmdb)
        assertEquals(5000L, completed.watched.last().media.ids.trakt)
        assertEquals("2026-01-03T00:00:00Z", completed.watched.last().watchedAt)
    }
}
