package com.nuvio.tv.data.tvdb

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class TvdbOrderChoice(val seriesId: Long, val type: String)

/** The TVDB order each profile picked per show. Shows without an entry use provider order. */
@Singleton
class TvdbOrderStore @Inject constructor(private val factory: ProfileDataStoreFactory) {
    private fun store(profileId: Int) = factory.get(profileId, "tvdb_orders")
    private fun key(alias: String) = stringPreferencesKey("order:$alias")

    suspend fun choice(profileId: Int, aliases: Collection<String>): TvdbOrderChoice? {
        val prefs = store(profileId).data.first()
        return aliases.firstNotNullOfOrNull { alias -> prefs[key(alias)]?.let(::decode) }
    }

    /** [type] null goes back to provider order. */
    suspend fun save(profileId: Int, aliases: Collection<String>, seriesId: Long, type: String?) {
        store(profileId).edit { prefs ->
            for (alias in aliases.filter(String::isNotBlank).distinct()) {
                if (type == null) prefs.remove(key(alias)) else prefs[key(alias)] = "$seriesId|$type"
            }
        }
    }

    private fun decode(raw: String): TvdbOrderChoice? {
        val parts = raw.split('|')
        val id = parts.getOrNull(0)?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val type = parts.getOrNull(1)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: return null
        return TvdbOrderChoice(id, type)
    }
}
