package com.nuvio.tv.data.mdblist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MdbListSyncRepositoryStorageTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `scrobble pause updates only playback storage and skips full snapshot serialization`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val harness = MdbListSyncTestHarness(TestScope(testDispatcher))
        val snapshot = harness.snapshot().copy(
            watched = (1..500).map { mdbListTestMovie(it.toLong()) },
            playback = emptyList()
        )
        harness.seed(snapshot)
        harness.repository.ensureLoaded()

        val service = MdbListScrobbleService(harness.http.api, harness.repository)
        harness.http.reply(body = """{"action":"pause","progress":42.0,"paused_at":"$MDBLIST_TEST_TIME"}""")

        val event = TrackingScrobbleEvent(
            media = TrackingMediaReference(
                kind = TrackingMediaKind.MOVIE,
                title = "Movie 1",
                year = 2026,
                ids = TrackingExternalIds(imdb = "tt1", tmdb = 1L)
            ),
            progressPercent = 42.0
        )

        val beforeSaveCalls = harness.storage.saveCalls
        val beforePlaybackCalls = harness.storage.savePlaybackCalls

        service.scrobble(harness.http.store.scope(), TrackingScrobbleAction.PAUSE, event)

        // Verify only savePlayback was called, saving thousands of watched items was bypassed
        assertEquals(beforePlaybackCalls + 1, harness.storage.savePlaybackCalls)
        assertEquals(beforeSaveCalls, harness.storage.saveCalls)

        // Verify playback state updated in repository
        val currentSnapshot = harness.repository.currentSnapshot()
        assertNotNull(currentSnapshot)
        assertEquals(1, currentSnapshot!!.playback.size)
        assertEquals(42.0f, currentSnapshot.playback.first().progress, 0.001f)
    }

    @Test
    fun `loadCurrent restores playback state from playback storage on restart`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val harness = MdbListSyncTestHarness(TestScope(testDispatcher))

        val baseSnapshot = harness.snapshot().copy(
            watched = listOf(mdbListTestMovie(1)),
            playback = emptyList()
        )
        harness.storage.profiles[1] = json.encodeToString(baseSnapshot)
        harness.storage.playbacks[1] = json.encodeToString(listOf(mdbListTestPlayback(progress = 55.0f)))

        harness.repository.ensureLoaded()

        val current = harness.repository.currentSnapshot()
        assertNotNull(current)
        assertEquals(1, current!!.playback.size)
        assertEquals(55.0f, current.playback.first().progress, 0.001f)
    }

    @Test
    fun `watched mutation saves full snapshot and clears playback storage`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val harness = MdbListSyncTestHarness(TestScope(testDispatcher))

        val baseSnapshot = harness.snapshot().copy(
            watched = listOf(mdbListTestMovie(1)),
            playback = listOf(mdbListTestPlayback(progress = 80.0f))
        )
        harness.seed(baseSnapshot)
        harness.storage.playbacks[1] = json.encodeToString(baseSnapshot.playback)
        harness.repository.ensureLoaded()

        // Mutate watched list
        val newWatched = mdbListTestMovie(2)
        harness.repository.mutate(harness.http.store.scope()) { snap ->
            snap.copy(watched = snap.watched + newWatched) to Unit
        }

        // Full snapshot save was called
        assertTrue(harness.storage.saveCalls > 0)
        // Playback storage was cleared by save
        assertNull(harness.storage.playbacks[1])
    }

    @Test
    fun `AndroidMdbListSyncStorage migrates legacy playback once writes tombstone and clears playback before saving snapshot`() = runTest {
        val snapshotKey = stringPreferencesKey("snapshot")
        val playbackKey = stringPreferencesKey("playback")
        val opLog = mutableListOf<String>()

        val initialLegacyPrefs = emptyPreferences().toMutablePreferences().apply {
            this[snapshotKey] = """{"accountId":1}"""
            this[playbackKey] = """[{"id":7,"progress":33.0}]"""
        }.toPreferences()

        val syncStore = RecordingPreferencesDataStore("sync", initialLegacyPrefs, opLog)
        val playbackStore = RecordingPreferencesDataStore("playback", emptyPreferences(), opLog)
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(1, "mdblist_sync") } returns syncStore
            every { get(1, "mdblist_playback") } returns playbackStore
            every { isProfileDeleted(1) } returns false
        }
        val storage = AndroidMdbListSyncStorage(factory)

        // 1. Before migration, loadPlayback falls back to legacy key in mdblist_sync
        assertEquals("""[{"id":7,"progress":33.0}]""", storage.loadPlayback(1))
        assertEquals(1, syncStore.readCount)

        // 2. save() clears playback first (writing "" tombstone), then writes snapshot & removes legacy playback key
        opLog.clear()
        syncStore.resetReadCount()
        storage.save(1, """{"accountId":1,"watched":[]}""") {}

        assertEquals(listOf("playback:edit", "sync:edit"), opLog)
        assertEquals("", playbackStore.value[playbackKey])
        assertNull(syncStore.value[playbackKey])
        assertEquals("""{"accountId":1,"watched":[]}""", syncStore.value[snapshotKey])

        // 3. Subsequent loadPlayback sees "" tombstone in mdblist_playback and returns null WITHOUT reading mdblist_sync
        syncStore.resetReadCount()
        assertNull(storage.loadPlayback(1))
        assertEquals(0, syncStore.readCount)

        // 4. remove() clears playback first, then removes snapshot from mdblist_sync
        storage.savePlayback(1, """[{"id":8,"progress":50.0}]""") {}
        opLog.clear()
        storage.remove(1) {}
        assertEquals(listOf("playback:edit", "sync:edit"), opLog)
        assertEquals("", playbackStore.value[playbackKey])
        assertNull(syncStore.value[snapshotKey])
    }

    private class RecordingPreferencesDataStore(
        private val name: String,
        initial: Preferences = emptyPreferences(),
        private val opLog: MutableList<String>
    ) : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(initial)
        var readCount: Int = 0
            private set

        val value: Preferences
            get() = state.value

        override val data: Flow<Preferences> = state.onEach { readCount += 1 }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                opLog += "$name:edit"
                transform(state.value).also { updated ->
                    state.value = updated
                }
            }

        fun resetReadCount() {
            readCount = 0
        }
    }
}
