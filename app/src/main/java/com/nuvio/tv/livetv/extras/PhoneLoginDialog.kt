package com.nuvio.tv.livetv.extras

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionRow
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * "Use your phone" on the Xtream login: a QR code and a short address for a page on this TV
 * (home network only) where the phone types the Xtream server, username and password. The page
 * stops when this closes or once a login was sent; [onLogin] then fills and saves the form.
 */
@Composable
fun PhoneXtreamLoginDialog(onLogin: (PhoneXtreamLogin) -> Unit, onCancel: () -> Unit) {
    val received = remember { Channel<PhoneXtreamLogin>(Channel.CONFLATED) }
    val server = remember { PhoneLoginServer.start { received.trySend(it) } }
    DisposableEffect(server) { onDispose { server?.stop() } }
    LaunchedEffect(server) {
        val login = received.receive()
        server?.stop()
        onLogin(login)
    }
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { cancelFocus.requestFocus() } }
    val qr = remember(server?.url) { server?.url?.let { runCatching { QrCodeGenerator.generate(it, 420, margin = 1) }.getOrNull() } }

    NuvioDialog(onDismiss = onCancel, title = stringResource(R.string.tvxo_use_your_phone), width = 560.dp) {
        if (server == null) {
            Text(
                text = "Your TV isn't on a home network right now, so the phone page can't open. Type the login with the remote instead.",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                text = "Scan the QR code with a phone on the same Wi-Fi, or open the address below, then enter your Xtream server, username and password.",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            if (qr != null) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "QR code",
                    modifier = Modifier.align(Alignment.CenterHorizontally).size(196.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Text(
                text = server.url.removePrefix("http://"),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.Secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Waiting for your phone… this screen continues by itself. The page works once and only on your home network.",
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.focusRequester(cancelFocus)) {
                SettingsDialogActionButton(text = "Cancel", onClick = onCancel)
            }
        }
    }
}
