package com.nuvio.tv.livetv.extras

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PortableWifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon

/** A small "no signal" mark on a channel the last dead channel scan couldn't reach. */
@Composable
internal fun DeadChannelMark(channelKey: String, tint: Color, size: Dp = 14.dp) {
    val dead by DeadChannels.dead.collectAsState()
    if (channelKey !in dead) return
    Icon(
        imageVector = Icons.Default.PortableWifiOff,
        contentDescription = "Didn't answer in the last dead channel scan",
        tint = tint.copy(alpha = 0.8f),
        modifier = Modifier.size(size)
    )
    Spacer(Modifier.width(3.dp))
}
