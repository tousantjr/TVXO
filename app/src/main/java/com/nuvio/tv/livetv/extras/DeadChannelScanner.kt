package com.nuvio.tv.livetv.extras

import com.nuvio.tv.livetv.data.ForkHttp
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What one probe found. UNKNOWN: the server answered in a way that doesn't say (busy, login limit). */
enum class ProbeVerdict { ALIVE, DEAD, UNKNOWN }

/** Pure logic for the dead channel scan (unit tested). */
object DeadScanLogic {
    const val TIMEOUT_MS = 6_000L
    const val CONCURRENCY = 4

    /**
     * [code] = the HTTP status (null when there was no answer at all: timeout, refused, bad host).
     * Login/connection-limit answers (401, 403, 429, 458, 503, 509) are UNKNOWN, not dead: Xtream
     * providers send them when another stream is already open on the account.
     */
    fun verdict(code: Int?): ProbeVerdict = when {
        code == null -> ProbeVerdict.DEAD
        code in 200..399 -> ProbeVerdict.ALIVE
        code in setOf(401, 403, 429, 458, 503, 509) -> ProbeVerdict.UNKNOWN
        code == 400 || code == 404 || code == 410 || code in 500..599 -> ProbeVerdict.DEAD
        else -> ProbeVerdict.UNKNOWN
    }

    /** Channels a scan can probe (Stalker portal links need a portal login first, so they're left out). */
    fun probeable(url: String): Boolean = url.startsWith("http://", true) || url.startsWith("https://", true)

    /**
     * The saved results after a (possibly cancelled) scan: new answers replace old ones, UNKNOWN
     * answers keep what was known before, and channels no longer in the list are dropped.
     */
    fun merge(
        old: Map<String, DeadScanEntry>,
        found: Map<String, ProbeVerdict>,
        now: Long,
        stillListed: Set<String>
    ): Map<String, DeadScanEntry> {
        val out = old.filterKeys { it in stillListed }.toMutableMap()
        found.forEach { (key, v) ->
            when (v) {
                ProbeVerdict.ALIVE -> out[key] = DeadScanEntry(false, now)
                ProbeVerdict.DEAD -> out[key] = DeadScanEntry(true, now)
                ProbeVerdict.UNKNOWN -> Unit
            }
        }
        return out
    }

    fun deadKeys(results: Map<String, DeadScanEntry>): Set<String> = results.filterValues { it.dead }.keys

    /** The list with dead channels taken out when "Hide dead channels" is on. */
    fun <T> hide(channels: List<T>, dead: Set<String>, hideOn: Boolean, key: (T) -> String): List<T> =
        if (!hideOn || dead.isEmpty()) channels else channels.filter { key(it) !in dead }
}

/** Which channels the last scan found dead, for the guide's small mark (filled in by [DeadChannelScanner]). */
object DeadChannels {
    val dead = MutableStateFlow<Set<String>>(emptySet())
}

data class DeadScanProgress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val deadFound: Int = 0)

/**
 * "Scan for dead channels" (Settings → Live TV): asks every channel's stream for a few bytes,
 * with the player's user agent and headers, four at a time, 6 seconds each. Cancellable; the
 * answers are saved with the time they were found.
 */
@Singleton
class DeadChannelScanner @Inject constructor(
    private val prefs: TvxoExtrasPrefs,
    private val livePrefs: LiveTvPreferences,
    private val channelStore: LiveTvChannelStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http: OkHttpClient = ForkHttp.builder()
        .connectTimeout(DeadScanLogic.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(DeadScanLogic.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(DeadScanLogic.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false)
        .apply {
            val tls = com.nuvio.tv.livetv.data.ForgivingTls
            tls.trustManager?.let { tm -> tls.socketFactory?.let { sslSocketFactory(it, tm) } }
        }
        .build()

    private val _progress = MutableStateFlow(DeadScanProgress())
    val progress: StateFlow<DeadScanProgress> = _progress.asStateFlow()
    private var job: Job? = null

    val deadKeys: StateFlow<Set<String>> = prefs.deadResults.map { DeadScanLogic.deadKeys(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Channel keys to leave out of the lists ("Hide dead channels" on), else empty. */
    val hiddenKeys: StateFlow<Set<String>> = combine(deadKeys, prefs.hideDead) { d, h -> if (h) d else emptySet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    init {
        scope.launch { deadKeys.collect { DeadChannels.dead.value = it } }
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val user = livePrefs.userState.first()
            val all = channelStore.displayChannels.value
            val channels = all.filter { it.key !in user.hiddenChannels && it.groupId !in user.hiddenGroups && DeadScanLogic.probeable(it.url) }
            val found = java.util.concurrent.ConcurrentHashMap<String, ProbeVerdict>()
            _progress.value = DeadScanProgress(true, 0, channels.size, 0)
            val gate = Semaphore(DeadScanLogic.CONCURRENCY)
            try {
                channels.map { ch ->
                    async {
                        gate.withPermit {
                            if (!isActive) return@withPermit
                            val v = probe(ch)
                            found[ch.key] = v
                            _progress.value = _progress.value.let {
                                it.copy(done = it.done + 1, deadFound = it.deadFound + if (v == ProbeVerdict.DEAD) 1 else 0)
                            }
                        }
                    }
                }.awaitAll()
            } finally {
                // Saved also when cancelled: what was found so far counts.
                withContext(kotlinx.coroutines.NonCancellable) {
                    val now = System.currentTimeMillis()
                    val merged = DeadScanLogic.merge(prefs.deadResults.value, found, now, all.mapTo(HashSet()) { it.key })
                    prefs.saveDeadResults(merged, now)
                    _progress.value = _progress.value.copy(running = false)
                }
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun clear() {
        cancel()
        scope.launch {
            job?.join()
            prefs.clearDeadResults()
            _progress.value = DeadScanProgress()
        }
    }

    private fun probe(ch: LiveChannel): ProbeVerdict {
        val ua = ch.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: LiveTvRepository.DEFAULT_UA
        val req = Request.Builder().url(ch.url).get()
            .header("User-Agent", ua)
            .apply { ch.headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) } }
            .header("Range", "bytes=0-1023")
            .build()
        val code: Int? = runCatching {
            // Only the answer's status is read; the stream itself is closed straight away.
            http.newCall(req).execute().use { it.code }
        }.getOrNull()
        return DeadScanLogic.verdict(code)
    }
}
