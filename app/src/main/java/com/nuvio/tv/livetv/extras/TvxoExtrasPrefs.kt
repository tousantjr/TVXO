package com.nuvio.tv.livetv.extras

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Guide alerts: words to look for in upcoming show titles. */
data class GuideAlertSettings(
    val enabled: Boolean = true,
    val keywords: List<String> = emptyList(),
    val leadMinutes: Int = 5
)

/** One dead-channel scan result: the channel didn't answer (or answered with an error) at [checkedAt]. */
data class DeadScanEntry(val dead: Boolean, val checkedAt: Long, val reason: String? = null)

/**
 * Settings and saved state for TVXO's own Live TV extras (guide alerts, dead channel scan,
 * live score bar). Kept in a DataStore file of its own so the main Live TV settings are untouched.
 */
@Singleton
class TvxoExtrasPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { context.preferencesDataStoreFile("tvxo_extras") }
    )

    private object Keys {
        val alertsOn = booleanPreferencesKey("guide_alerts_on")
        val alertWords = stringPreferencesKey("guide_alert_words")
        val alertLead = intPreferencesKey("guide_alert_lead")
        val alertFired = stringPreferencesKey("guide_alert_fired")
        val deadResults = stringPreferencesKey("dead_results")
        val deadScanAt = stringPreferencesKey("dead_scan_at")
        val hideDead = booleanPreferencesKey("hide_dead")
        val scoreBar = stringPreferencesKey("score_bar")
        val scoreAlerts = stringPreferencesKey("score_alerts")
    }

    val guideAlerts: StateFlow<GuideAlertSettings> = store.data.map { p ->
        GuideAlertSettings(
            enabled = p[Keys.alertsOn] ?: true,
            keywords = decodeList(p[Keys.alertWords]),
            leadMinutes = (p[Keys.alertLead] ?: 5).coerceIn(0, 120)
        )
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, GuideAlertSettings())

    /** Alert keys already shown ("channelKey|startMs") -> when the show starts (for pruning). */
    val firedAlerts: StateFlow<Map<String, Long>> = store.data.map { decodeLongMap(it[Keys.alertFired]) }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val deadResults: StateFlow<Map<String, DeadScanEntry>> = store.data.map { decodeDead(it[Keys.deadResults]) }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val lastScanAt: StateFlow<Long> = store.data.map { it[Keys.deadScanAt]?.toLongOrNull() ?: 0L }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, 0L)

    val hideDead: StateFlow<Boolean> = store.data.map { it[Keys.hideDead] ?: false }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

    /** Null = never chosen (on when you follow teams in Sports → My Teams). */
    val scoreBar: StateFlow<Boolean?> = store.data.map { it[Keys.scoreBar]?.toBooleanStrictOrNull() }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    /** Null = never chosen (same default as the score bar). */
    val scoreAlerts: StateFlow<Boolean?> = store.data.map { it[Keys.scoreAlerts]?.toBooleanStrictOrNull() }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun setGuideAlertsEnabled(on: Boolean) { store.edit { it[Keys.alertsOn] = on } }
    suspend fun setLeadMinutes(min: Int) { store.edit { it[Keys.alertLead] = min.coerceIn(0, 120) } }
    suspend fun addKeyword(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        store.edit { p ->
            val cur = decodeList(p[Keys.alertWords])
            if (cur.none { it.equals(w, ignoreCase = true) }) p[Keys.alertWords] = encodeList(cur + w)
        }
    }
    suspend fun removeKeyword(word: String) {
        store.edit { p -> p[Keys.alertWords] = encodeList(decodeList(p[Keys.alertWords]).filterNot { it.equals(word, true) }) }
    }

    /** Remembers [key] as shown, and forgets keys for shows that started over a day ago. */
    suspend fun markFired(key: String, startMs: Long, now: Long = System.currentTimeMillis()) {
        store.edit { p ->
            val m = GuideAlertMatcher.prune(decodeLongMap(p[Keys.alertFired]), now) + (key to startMs)
            p[Keys.alertFired] = encodeLongMap(m)
        }
    }

    suspend fun saveDeadResults(results: Map<String, DeadScanEntry>, scannedAt: Long) {
        store.edit { p ->
            p[Keys.deadResults] = encodeDead(results)
            p[Keys.deadScanAt] = scannedAt.toString()
        }
    }
    suspend fun clearDeadResults() {
        store.edit { p -> p.remove(Keys.deadResults); p.remove(Keys.deadScanAt) }
    }
    suspend fun setHideDead(on: Boolean) { store.edit { it[Keys.hideDead] = on } }
    suspend fun setScoreBar(on: Boolean) { store.edit { it[Keys.scoreBar] = on.toString() } }
    suspend fun setScoreAlerts(on: Boolean) { store.edit { it[Keys.scoreAlerts] = on.toString() } }

    companion object {
        internal fun decodeList(raw: String?): List<String> = runCatching {
            val a = JSONArray(raw ?: return emptyList())
            (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

        internal fun encodeList(list: List<String>): String = JSONArray(list).toString()

        internal fun decodeLongMap(raw: String?): Map<String, Long> = runCatching {
            val o = JSONObject(raw ?: return emptyMap())
            o.keys().asSequence().associateWith { o.getLong(it) }
        }.getOrDefault(emptyMap())

        internal fun encodeLongMap(m: Map<String, Long>): String =
            JSONObject().also { o -> m.forEach { (k, v) -> o.put(k, v) } }.toString()

        internal fun decodeDead(raw: String?): Map<String, DeadScanEntry> = runCatching {
            val o = JSONObject(raw ?: return emptyMap())
            o.keys().asSequence().associateWith { k ->
                val e = o.getJSONObject(k)
                DeadScanEntry(e.optBoolean("d"), e.optLong("t"), e.optString("r").ifBlank { null })
            }
        }.getOrDefault(emptyMap())

        internal fun encodeDead(m: Map<String, DeadScanEntry>): String = JSONObject().also { o ->
            m.forEach { (k, v) ->
                o.put(k, JSONObject().put("d", v.dead).put("t", v.checkedAt).also { j -> v.reason?.let { j.put("r", it) } })
            }
        }.toString()
    }
}
