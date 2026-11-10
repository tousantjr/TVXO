package com.nuvio.tv.livetv.multiview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.R
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.livetv.ui.ChannelLogo
import com.nuvio.tv.livetv.ui.LiveDialog
import com.nuvio.tv.livetv.ui.LiveFocusRow
import com.nuvio.tv.livetv.ui.LivePlayerSurface
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.focusedTextColor
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A tile: [id] stays with the tile (and its player) when its channel is swapped. */
private data class MvTile(val id: Int, val channel: LiveChannel)

/** What the OK menu or the channel picker is open for. */
private sealed interface MvMenu {
    data class Options(val index: Int) : MvMenu
    data class Pick(val swapIndex: Int?) : MvMenu
}

private sealed interface TileStatus {
    data object Loading : TileStatus
    data object Playing : TileStatus
    data object NoDecoder : TileStatus
    data class Failed(val message: String) : TileStatus
}

private const val NO_DECODER_TEXT = "This device can't play another stream"

/**
 * Multiview: up to four live channels at once, each on its own player. The D-pad moves between
 * tiles; only the highlighted tile is heard. OK on a tile: full screen, swap channel or remove;
 * OK on "Add channel": the channel picker. Back leaves and lets go of every tile's player.
 *
 * Shown in its own full-screen window so the guide and player keys underneath never see its keys.
 * The main Live TV player lets go of its stream while Multiview is open and picks it up again on
 * the way out (unless a tile was opened full screen, which plays that channel instead).
 */
@Composable
internal fun LiveTvMultiviewScreen(
    initial: LiveChannel?,
    channels: List<LiveChannel>,
    playback: LiveTvPlaybackController,
    onFullScreen: (LiveChannel) -> Unit,
    onExit: () -> Unit,
    /** More channels to start with (Multiview opened from Sports with several live games). */
    initialChannels: List<LiveChannel> = emptyList()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val startTiles = remember {
        (listOfNotNull(initial) + initialChannels).distinctBy { it.key }.take(MultiviewLayout.MAX_TILES)
            .mapIndexed { i, ch -> MvTile(i, ch) }
    }
    var nextId by remember { mutableIntStateOf(startTiles.size.coerceAtLeast(1)) }
    var tiles by remember { mutableStateOf(startTiles) }
    var focus by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf<MvMenu?>(null) }
    var handedOff by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(true) }
    val maxStreams = remember {
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        MultiviewLayout.maxStreams(am?.isLowRamDevice == true, com.nuvio.tv.ui.screens.settings.MemoryBudget.heapMb)
    }
    val rootFocus = remember { FocusRequester() }
    val slots = MultiviewLayout.slotCount(tiles.size)
    if (focus >= slots) focus = slots - 1

    // The main player lets go of its stream while Multiview is open.
    DisposableEffect(Unit) {
        playback.suspendForMultiview()
        onDispose { if (!handedOff) playback.resumeAfterMultiview() }
    }
    // Home / another app: every tile lets go of its player; they come back on return. Live TV's
    // own "back from the background" would restart the main player behind us: stop it again.
    DisposableEffect(context) {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper && c !is androidx.lifecycle.LifecycleOwner) c = c.baseContext
        val lifecycle = (c as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> started = false
                androidx.lifecycle.Lifecycle.Event.ON_START -> started = true
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> scope.launch {
                    delay(300)
                    playback.suspendForMultiview()
                }
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    fun exit() = onExit()
    fun refocus() { scope.launch { delay(60); runCatching { rootFocus.requestFocus() } } }

    Dialog(
        onDismissRequest = { if (menu == null) exit() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        var sawOkDown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { delay(60); runCatching { rootFocus.requestFocus() } }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootFocus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (menu != null) return@onPreviewKeyEvent false
                    val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    val dir = when (e.key) {
                        Key.DirectionLeft -> MultiviewLayout.Direction.LEFT
                        Key.DirectionRight -> MultiviewLayout.Direction.RIGHT
                        Key.DirectionUp -> MultiviewLayout.Direction.UP
                        Key.DirectionDown -> MultiviewLayout.Direction.DOWN
                        else -> null
                    }
                    when {
                        dir != null -> {
                            if (e.type == KeyEventType.KeyDown) MultiviewLayout.move(focus, slots, dir)?.let { focus = it }
                            true
                        }
                        isOk && e.type == KeyEventType.KeyDown -> { sawOkDown = true; true }
                        // Acts on release, and only for a press that started here (the OK that
                        // opened Multiview is let go in this window).
                        isOk && e.type == KeyEventType.KeyUp -> {
                            if (sawOkDown) {
                                sawOkDown = false
                                menu = if (focus < tiles.size) MvMenu.Options(focus) else MvMenu.Pick(null)
                            }
                            true
                        }
                        else -> false
                    }
                }
        ) {
            val grid = MultiviewLayout.gridFor(slots)
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in 0 until grid.rows) {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in 0 until grid.cols) {
                            val index = r * grid.cols + c
                            Box(Modifier.weight(1f).fillMaxSize()) {
                                when {
                                    index < tiles.size -> {
                                        val tile = tiles[index]
                                        androidx.compose.runtime.key(tile.id) {
                                            ChannelTile(
                                                channel = tile.channel,
                                                playback = playback,
                                                focused = focus == index,
                                                // Over the device's limit: don't even try.
                                                allowed = index < maxStreams,
                                                started = started
                                            )
                                        }
                                    }
                                    index < slots -> AddTile(focused = focus == index)
                                }
                            }
                        }
                    }
                }
            }
            // A small title, top centre.
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 18.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.ViewQuilt, contentDescription = null, tint = NuvioTheme.colors.Secondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                LiveText(stringResource(R.string.live_multiview), size = 12.sp, weight = FontWeight.Bold)
                LiveText("  ·  OK: options  ·  Back: exit", size = 11.sp, color = NuvioTheme.colors.TextSecondary)
            }
        }

        when (val m = menu) {
            is MvMenu.Options -> {
                val tile = tiles.getOrNull(m.index)
                if (tile == null) menu = null else TileOptions(
                    channel = tile.channel,
                    onFullScreen = {
                        menu = null
                        handedOff = true
                        onFullScreen(tile.channel)
                    },
                    onSwap = { menu = MvMenu.Pick(m.index) },
                    onRemove = {
                        val before = tiles
                        tiles = MultiviewTiles.remove(before, m.index)
                        focus = MultiviewLayout.focusAfterRemove(focus, m.index, MultiviewLayout.slotCount(tiles.size))
                        menu = null
                        refocus()
                    },
                    onDismiss = { menu = null; refocus() }
                )
            }
            is MvMenu.Pick -> ChannelPicker(
                channels = channels,
                onScreen = tiles.map { it.channel.key }.toSet(),
                startKey = m.swapIndex?.let { tiles.getOrNull(it)?.channel?.key } ?: tiles.lastOrNull()?.channel?.key,
                title = if (m.swapIndex != null) "Swap channel" else "Add channel",
                onPick = { ch ->
                    val swap = m.swapIndex
                    if (swap != null && swap in tiles.indices) {
                        // Each tile keeps its own player; only the channels move.
                        val swapped = MultiviewTiles.swap(tiles.map { it.channel }, swap, ch) { a, b -> a.key == b.key }
                        tiles = tiles.mapIndexed { i, t -> t.copy(channel = swapped[i]) }
                        focus = swap
                    } else if (tiles.size < MultiviewLayout.MAX_TILES) {
                        val existing = tiles.indexOfFirst { it.channel.key == ch.key }
                        if (existing >= 0) focus = existing else {
                            tiles = tiles + MvTile(nextId++, ch)
                            focus = tiles.size - 1
                        }
                    }
                    menu = null
                    refocus()
                },
                onDismiss = { menu = null; refocus() }
            )
            null -> Unit
        }
    }
}

/** A playing tile: its own player for [channel], heard only while [focused]. */
@Composable
private fun ChannelTile(
    channel: LiveChannel,
    playback: LiveTvPlaybackController,
    focused: Boolean,
    allowed: Boolean,
    started: Boolean
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var status by remember { mutableStateOf<TileStatus>(TileStatus.Loading) }
    var retry by remember { mutableIntStateOf(0) }

    // The tile's own player, made while the app is in front and let go when it leaves.
    DisposableEffect(allowed, started) {
        if (!allowed) {
            status = TileStatus.NoDecoder
            return@DisposableEffect onDispose { }
        }
        if (!started) return@DisposableEffect onDispose { }
        val p = runCatching { buildMultiviewPlayer(context) }.getOrNull()
        if (p == null) {
            status = TileStatus.NoDecoder
            return@DisposableEffect onDispose { }
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) status = TileStatus.Playing
                else if (state == Player.STATE_BUFFERING && status !is TileStatus.Playing) status = TileStatus.Loading
            }
            override fun onPlayerError(error: PlaybackException) {
                status = if (MultiviewLayout.isDecoderLimit(error.errorCode)) TileStatus.NoDecoder
                    else TileStatus.Failed(error.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase())
                // A stream that just dropped: try again once in a while (not when out of decoders).
                if (status is TileStatus.Failed) retry++
            }
        }
        p.addListener(listener)
        player = p
        onDispose {
            player = null
            p.removeListener(listener)
            runCatching { p.release() }
        }
    }
    // Load (or swap to) the channel.
    LaunchedEffect(player, channel.key, retry) {
        val p = player ?: return@LaunchedEffect
        if (retry > 0) delay((retry * 3_000L).coerceAtMost(15_000L))
        if (status !is TileStatus.Failed || retry > 0) status = TileStatus.Loading
        try {
            val source = playback.multiviewMediaSource(channel)
            p.setMediaSource(source)
            p.prepare()
            p.playWhenReady = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            status = TileStatus.Failed(e.message ?: "couldn't open this channel")
        }
    }
    // Only the highlighted tile is heard.
    LaunchedEffect(player, focused) { player?.volume = if (focused) 1f else 0f }

    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.fillMaxSize()
            .border(if (focused) 4.dp else 1.dp, if (focused) colors.Secondary else Color.White.copy(alpha = 0.15f), shape)
            .padding(if (focused) 5.dp else 2.dp)
            .background(Color.Black)
    ) {
        val p = player
        if (p != null && status !is TileStatus.NoDecoder) {
            LivePlayerSurface(player = p, modifier = Modifier.fillMaxSize(), useSurfaceView = false)
        }
        when (val s = status) {
            TileStatus.Loading -> LiveText("Loading…", modifier = Modifier.align(Alignment.Center), color = colors.TextSecondary)
            TileStatus.NoDecoder -> LiveText(
                NO_DECODER_TEXT, modifier = Modifier.align(Alignment.Center).padding(16.dp),
                color = colors.TextPrimary, align = TextAlign.Center, maxLines = 3
            )
            is TileStatus.Failed -> LiveText(
                "Couldn't play this channel (${s.message})", modifier = Modifier.align(Alignment.Center).padding(16.dp),
                color = colors.TextPrimary, align = TextAlign.Center, maxLines = 3
            )
            TileStatus.Playing -> Unit
        }
        // Channel name (and a speaker on the one you hear), bottom left.
        Row(
            Modifier.align(Alignment.BottomStart).padding(8.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChannelLogo(channel.logo, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            LiveText(channel.name, size = 12.sp, weight = FontWeight.SemiBold, maxLines = 1)
            if (focused) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = colors.Secondary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun AddTile(focused: Boolean) {
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.fillMaxSize()
            .border(if (focused) 4.dp else 1.dp, if (focused) colors.Secondary else Color.White.copy(alpha = 0.15f), shape)
            .background(Color.White.copy(alpha = if (focused) 0.08f else 0.03f), shape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Add, contentDescription = null, tint = if (focused) colors.Secondary else colors.TextSecondary, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(6.dp))
            LiveText("Add channel", size = 15.sp, weight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TileOptions(
    channel: LiveChannel,
    onFullScreen: () -> Unit,
    onSwap: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    LiveDialog(onDismiss = onDismiss, width = 420.dp) {
        LiveText(channel.name, size = 18.sp, weight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(12.dp))
        LiveFocusRow(modifier = Modifier.fillMaxWidth().focusRequester(first), onClick = onFullScreen) { f ->
            LiveText("Full screen", color = focusedTextColor(f))
        }
        LiveFocusRow(modifier = Modifier.fillMaxWidth(), onClick = onSwap) { f ->
            LiveText("Swap channel", color = focusedTextColor(f))
        }
        LiveFocusRow(modifier = Modifier.fillMaxWidth(), onClick = onRemove) { f ->
            LiveText("Remove from Multiview", color = focusedTextColor(f))
        }
    }
}

/** Your live channels, to add to Multiview or swap into a tile. */
@Composable
private fun ChannelPicker(
    channels: List<LiveChannel>,
    onScreen: Set<String>,
    startKey: String?,
    title: String,
    onPick: (LiveChannel) -> Unit,
    onDismiss: () -> Unit
) {
    val startIndex = remember(channels, startKey) { channels.indexOfFirst { it.key == startKey }.coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 2).coerceAtLeast(0))
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { startFocus.requestFocus() } }
    LiveDialog(onDismiss = onDismiss, width = 560.dp) {
        LiveText(title, size = 18.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        if (channels.isEmpty()) {
            LiveText("No channels loaded yet.", color = NuvioTheme.colors.TextSecondary)
            return@LiveDialog
        }
        LazyColumn(state = listState, modifier = Modifier.heightIn(max = 520.dp)) {
            itemsIndexed(channels, key = { i, ch -> "$i|${ch.key}" }) { i, ch ->
                LiveFocusRow(
                    modifier = Modifier.fillMaxWidth().let { if (i == startIndex) it.focusRequester(startFocus) else it },
                    selected = ch.key in onScreen,
                    onClick = { onPick(ch) }
                ) { f ->
                    LiveText(if (ch.number > 0) ch.number.toString() else "", modifier = Modifier.width(44.dp), color = focusedTextColor(f), size = 13.sp)
                    ChannelLogo(ch.logo, size = 22.dp)
                    LiveText(ch.name, modifier = Modifier.weight(1f), color = focusedTextColor(f), maxLines = 1)
                    if (ch.key in onScreen) LiveText("On screen", size = 11.sp, color = NuvioTheme.colors.Secondary)
                }
            }
        }
    }
}
