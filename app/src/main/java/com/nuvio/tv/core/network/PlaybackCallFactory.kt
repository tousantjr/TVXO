package com.nuvio.tv.core.network

import androidx.annotation.VisibleForTesting
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * Playback-side HTTP goes through the VPN layer ([com.nuvio.vpn.android.VpnHttpClient]), which
 * needs a running Android app and refuses requests otherwise. JVM unit tests swap [wrap] for a
 * plain client; the app never changes it.
 */
internal object PlaybackCallFactory {
    @VisibleForTesting
    @Volatile
    var wrap: (OkHttpClient) -> Call.Factory = { com.nuvio.vpn.android.VpnHttpClient.wrap(it) }
}
