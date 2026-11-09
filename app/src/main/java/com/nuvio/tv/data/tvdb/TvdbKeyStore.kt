package com.nuvio.tv.data.tvdb

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TVDB API key. A key typed in the app wins; otherwise the one built in (TVDB_API_KEY in
 * local.properties or the CI secret). The typed key is stored encrypted on the device.
 */
@Singleton
class TvdbKeyStore @Inject constructor(@ApplicationContext context: Context) {
    private val stored = ProtectedProfilePreferences(context, "tvdb_key", "com.tvxo.tv.tvdb.key.v1")

    fun userKey(): String? = runCatching { stored.read(DEVICE) }.getOrNull()?.takeIf(String::isNotBlank)
    fun key(): String? = userKey() ?: BuildConfig.TVDB_API_KEY.takeIf(String::isNotBlank)
    fun hasBuiltInKey(): Boolean = BuildConfig.TVDB_API_KEY.isNotBlank()

    fun saveUserKey(value: String?) {
        stored.write(DEVICE, value?.trim()?.takeIf(String::isNotBlank))
    }

    private companion object { const val DEVICE = 0 }
}
