package io.github.patschgoo.castapp.ui

import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceEntry
import io.github.patschgoo.castapp.mediaservers.ServerKind
import io.github.patschgoo.castapp.multi.Peers
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastPhase
import io.github.patschgoo.castapp.service.CastState
import io.github.patschgoo.castapp.tv.TvReceiverService
import io.github.patschgoo.castapp.ui.theme.LocalCastColors
import kotlinx.coroutines.delay

/**
 * The main screen. On top the TV everything goes to ("Cast to") and the three things this
 * phone itself can send (its screen, one app, its sound); below, tiles for where else to
 * play from. A running cast sits in a bar at the bottom that opens the player and the
 * remote. On tablets the player and the TV list stand beside the tiles instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TileHomeContent(
    devices: List<DeviceEntry>,
    scanning: Boolean,
    active: CastState.Active?,
    snackbar: SnackbarHostState,
    actions: DevicesActions,
    audioMode: Boolean,
    lastUsedId: String?,
    /** Settings: show "Recent on this phone". */
    showRecent: Boolean = true,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var moreFor by remember { mutableStateOf<DeviceEntry?>(null) }
    var confirmForget by remember { mutableStateOf<DeviceEntry?>(null) }

    val listed = HomeLogic.listed(devices, audioMode)
    val target = HomeLogic.target(devices, lastUsedId, audioMode)
    val castingToTarget = active != null && target != null && HomeLogic.sameDevice(active.device, target.device)

    LaunchedEffect(active == null) { if (active == null) showPlayer = false }

    // Something that needs a TV: the one in "Cast to", or ask which one first.
    fun withTarget(block: (CastDevice) -> Unit) {
        val device = target?.device
        if (device == null) showPicker = true else block(device)
    }
    // Videos and photos can't play on a speaker: leave audio mode and use a TV.
    fun withScreen(block: (CastDevice) -> Unit) {
        if (audioMode) actions.onAudioMode(false)
        val device = HomeLogic.target(devices, lastUsedId, audioMode = false)?.device
        if (device == null) showPicker = true else block(device)
    }

    val tiles = listOf(
        SourceTile(Icons.Rounded.Movie, R.string.home_tile_videos, R.string.home_tile_videos_hint) { withScreen { actions.onCastFiles(it, FilePick.VIDEOS) } },
        SourceTile(Icons.Rounded.Photo, R.string.home_tile_photos, R.string.home_tile_photos_hint) { withScreen { actions.onCastFiles(it, FilePick.PHOTOS) } },
        SourceTile(Icons.Rounded.MusicNote, R.string.home_tile_music, R.string.home_tile_music_hint) { withTarget { actions.onCastFiles(it, FilePick.MUSIC) } },
        SourceTile(Icons.Rounded.VideoLibrary, R.string.home_tile_plex, R.string.home_tile_plex_hint) { actions.onOpenServers(ServerKind.PLEX) },
        SourceTile(Icons.Rounded.Dns, R.string.home_tile_jellyfin, R.string.home_tile_jellyfin_hint) { actions.onOpenServers(ServerKind.JELLYFIN) },
        SourceTile(Icons.Rounded.Lan, R.string.network_cloud, R.string.home_tile_network_hint, actions.onOpenNetwork),
        SourceTile(Icons.Rounded.LiveTv, R.string.tv_channels, R.string.home_tile_channels_hint, actions.onOpenTvChannels),
        SourceTile(Icons.Rounded.Public, R.string.main_web_videos, R.string.home_tile_web_hint, actions.onOpenBrowser),
    )

    // Cards that are only there sometimes get no gap in the list while they're empty.
    val receiving by TvReceiverService.running.collectAsStateWithLifecycle()
    val peers by Peers.peers.collectAsStateWithLifecycle()
    val peerActivity by Peers.activity.collectAsStateWithLifecycle()
    val peerRemote by Peers.remote.collectAsStateWithLifecycle()
    val showPeers = peers.any { it.casting } || peerActivity != null || peerRemote != null

    val home: LazyListScope.() -> Unit = {
        item(key = "header") { HomeHeader(actions) }
        item(key = "target") { CastToRow(target, castingToTarget, scanning, onClick = { showPicker = true }) }
        item(key = "quick") {
            QuickActions(
                soundOnly = audioMode || target?.device?.video == false,
                running = active?.takeIf { it.kind != CastKind.FILE },
                onMirror = { withTarget(actions.onMirror) },
                onApp = { withTarget(actions.onCastApp) },
                onSound = { withTarget(actions.onCastSound) },
                onMusic = { withTarget { actions.onCastFiles(it, FilePick.MUSIC) } },
                onStop = actions.onStop,
            )
        }
        if (showPeers) item(key = "peers") { PeerCards(localCasting = active != null) }
        if (receiving) item(key = "receiving") { ReceivingCard(actions) }
        item(key = "tiles") { SourceTiles(tiles) }
        if (showRecent) {
            item(key = "recent") {
                RecentVideosRow(
                    onPlay = { video -> withScreen { actions.onCastUris(it, listOf(video.uri)) } },
                    onSeeAll = { withScreen { actions.onCastFiles(it, FilePick.VIDEOS) } },
                    onHide = actions.onHideRecent,
                )
            }
        }
        item(key = "continue") { ContinueWatchingRow() }
    }

    val picker: @Composable () -> Unit = {
        TvPickerBody(
            listed = listed,
            targetId = target?.device?.id,
            active = active,
            scanning = scanning,
            audioMode = audioMode,
            actions = actions,
            onPick = {
                actions.onSelect(it.device)
                showPicker = false
            },
            onMore = {
                showPicker = false
                moreFor = it
            },
            onAdd = { showAdd = true },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= TwoPaneMinWidth
        val paneMax = if (maxWidth >= WideMinWidth) 560.dp else Dp.Unspecified
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { if (active != null && !twoPane) CastBar(active, actions, onOpen = { showPlayer = true }) },
        ) { padding ->
            val contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            )
            if (twoPane) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally)) {
                    // fill = false: a filled weight would stretch each pane to half the screen, past paneMax.
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = paneMax).fillMaxWidth(),
                        contentPadding = contentPadding,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = home,
                    )
                    Column(
                        Modifier.weight(1f, fill = false).widthIn(max = paneMax).fillMaxWidth().verticalScroll(rememberScrollState()).padding(contentPadding),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (active != null) PaneCard { PlayerBody(active, actions) }
                        PaneCard { picker() }
                    }
                }
            } else {
                LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp), content = home)
            }
        }
    }

    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) { picker() }
        }
    }

    if (showPlayer && active != null) {
        ModalBottomSheet(
            onDismissRequest = { showPlayer = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                PlayerBody(active, actions)
            }
        }
    }

    moreFor?.let { entry ->
        DeviceSheet(
            entry = entry,
            audioMode = audioMode,
            onCastSound = { moreFor = null; actions.onCastSound(entry.device) },
            onOpenBrowser = { moreFor = null; actions.onOpenBrowser() },
            onDismiss = { moreFor = null },
            onMirror = { moreFor = null; actions.onMirror(entry.device) },
            onCastApp = { moreFor = null; actions.onCastApp(entry.device) },
            onCastFile = { moreFor = null; actions.onCastFiles(entry.device, FilePick.ANY) },
            onForget = { moreFor = null; confirmForget = entry },
            onRemote = { moreFor = null; actions.onOpenRemote(entry.device) },
        )
    }

    confirmForget?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmForget = null },
            title = { Text(stringResource(R.string.polish_forget_title, entry.device.name)) },
            text = { Text(stringResource(R.string.polish_forget_text)) },
            confirmButton = {
                TextButton(onClick = { actions.onForget(entry.device); confirmForget = null }) { Text(stringResource(R.string.polish_forget)) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showAdd) AddDeviceDialog(onDismiss = { showAdd = false }, onAdd = actions.onAddManual)
}

// ---- top of the screen ---------------------------------------------------------

@Composable
private fun HomeHeader(actions: DevicesActions) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        RoundButton(Icons.Rounded.SettingsRemote, stringResource(R.string.remote_open)) { actions.onOpenRemote(null) }
        Spacer(Modifier.width(4.dp))
        RoundButton(Icons.Rounded.Settings, stringResource(R.string.settings), actions.onOpenSettings)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = LocalCastColors.current.card,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) { Icon(icon, contentDescription = label) }
}

/** The TV everything on this screen goes to; opens the list to pick another one. */
@Composable
private fun CastToRow(target: DeviceEntry?, casting: Boolean, scanning: Boolean, onClick: () -> Unit) {
    val colors = LocalCastColors.current
    val device = target?.device
    val status = when {
        target == null -> stringResource(if (scanning) R.string.scanning_short else R.string.home_status_searching)
        casting -> stringResource(R.string.home_status_casting)
        target.online -> stringResource(R.string.home_status_ready)
        else -> stringResource(R.string.home_status_missing)
    }
    val statusColor = if (target != null && (casting || target.online)) colors.ok else MaterialTheme.colorScheme.onSurfaceVariant
    // With a large font the status goes under the name, so the name keeps the width.
    val large = isLargeFont()
    val statusLine: @Composable () -> Unit = {
        Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(6.dp))
            Text(status, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = statusColor, maxLines = 1)
        }
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(26.dp), color = colors.card, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(deviceIcon(device), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_cast_to), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    device?.name ?: stringResource(R.string.home_pick_tv),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (large) statusLine()
            }
            if (!large) {
                Spacer(Modifier.width(8.dp))
                statusLine()
            }
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

private fun deviceIcon(device: CastDevice?): ImageVector = when {
    device == null -> Icons.Rounded.Tv
    device.group -> Icons.Rounded.SpeakerGroup
    !device.video -> Icons.Rounded.Speaker
    else -> Icons.Rounded.Tv
}

/** What this phone is sending right now, for the buttons below "Cast to". */
internal enum class LiveMode { SCREEN, APP, SOUND }

internal fun liveMode(running: CastState.Active?): LiveMode? = when {
    running == null -> null
    running.audioOnly -> LiveMode.SOUND
    running.singleApp -> LiveMode.APP
    else -> LiveMode.SCREEN
}

/**
 * What this phone itself can send: its screen, a single app, or only its sound. For a
 * speaker (or in audio mode) that is the sound and music files. The buttons keep their
 * places while one runs: the running one is highlighted ("● One app") and stops it, the
 * other picture button switches over without stopping (Android asks again, the TV keeps playing).
 */
@Composable
private fun QuickActions(
    soundOnly: Boolean,
    running: CastState.Active?,
    onMirror: () -> Unit,
    onApp: () -> Unit,
    onSound: () -> Unit,
    onMusic: () -> Unit,
    onStop: () -> Unit,
) {
    // With a large font three buttons side by side break their words: one below the other then.
    val stacked = isLargeFont()
    val mode = liveMode(running)
    // Sharing a single app is Android 14's; before, "One app" mirrors the screen and opens the app.
    val appSharing = Build.VERSION.SDK_INT >= 34
    val idle = if (mode == null) QuickStyle.PRIMARY else QuickStyle.PLAIN
    val stopLabel = stringResource(R.string.mode_stop)
    val buttons: @Composable (Modifier) -> Unit = { modifier ->
        if (soundOnly) {
            when (mode) {
                null -> QuickButton(Icons.Rounded.Speaker, stringResource(R.string.home_phone_sound), QuickStyle.PRIMARY, onSound, modifier, stacked)
                else -> QuickButton(Icons.Rounded.Stop, stopLabel, QuickStyle.ACTIVE, onStop, modifier, stacked, tag = modeName(mode))
            }
            QuickButton(Icons.Rounded.LibraryMusic, stringResource(R.string.home_music_files), QuickStyle.PLAIN, onMusic, modifier, stacked)
        } else {
            when (mode) {
                LiveMode.SCREEN -> QuickButton(Icons.Rounded.Stop, stopLabel, QuickStyle.ACTIVE, onStop, modifier, stacked, tag = modeName(mode))
                LiveMode.APP -> QuickButton(Icons.Rounded.SwapHoriz, stringResource(R.string.mode_switch_to_screen), QuickStyle.PLAIN, onMirror, modifier, stacked)
                else -> QuickButton(Icons.AutoMirrored.Rounded.ScreenShare, stringResource(R.string.home_mirror), idle, onMirror, modifier, stacked)
            }
            when (mode) {
                LiveMode.APP -> QuickButton(Icons.Rounded.Stop, stopLabel, QuickStyle.ACTIVE, onStop, modifier, stacked, tag = modeName(mode))
                LiveMode.SCREEN -> if (appSharing) {
                    QuickButton(Icons.Rounded.SwapHoriz, stringResource(R.string.mode_switch_to_app), QuickStyle.PLAIN, onApp, modifier, stacked)
                } else {
                    QuickButton(Icons.Rounded.Apps, stringResource(R.string.home_one_app), QuickStyle.PLAIN, onApp, modifier, stacked)
                }
                else -> QuickButton(Icons.Rounded.Apps, stringResource(R.string.home_one_app), QuickStyle.PLAIN, onApp, modifier, stacked)
            }
            when (mode) {
                LiveMode.SOUND -> QuickButton(Icons.Rounded.Stop, stopLabel, QuickStyle.ACTIVE, onStop, modifier, stacked, tag = modeName(mode))
                else -> QuickButton(Icons.Rounded.Speaker, stringResource(R.string.home_phone_sound), QuickStyle.PLAIN, onSound, modifier, stacked)
            }
        }
    }
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) { buttons(Modifier.fillMaxWidth()) }
    } else {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            buttons(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** "Whole screen", "One app" or "Phone sound": what is live right now. */
@Composable
internal fun modeName(mode: LiveMode): String = stringResource(
    when (mode) {
        LiveMode.SCREEN -> R.string.mode_whole_screen
        LiveMode.APP -> R.string.mode_one_app
        LiveMode.SOUND -> R.string.home_phone_sound
    },
)

/** The user picked a large font (Android's font size 150 % and up): some rows become columns. */
@Composable
private fun isLargeFont(): Boolean = LocalDensity.current.fontScale >= 1.5f

/** ACTIVE: the mode that is live right now (filled, with a tag naming it). */
private enum class QuickStyle { PRIMARY, PLAIN, OUTLINED, ACTIVE }

@Composable
private fun QuickButton(
    icon: ImageVector,
    label: String,
    style: QuickStyle,
    onClick: () -> Unit,
    modifier: Modifier,
    wide: Boolean,
    /** A small line above the label: what is live ("● One app"). */
    tag: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val container = when (style) {
        QuickStyle.PRIMARY, QuickStyle.ACTIVE -> scheme.primary
        QuickStyle.PLAIN -> LocalCastColors.current.card
        QuickStyle.OUTLINED -> Color.Transparent
    }
    val content = when (style) {
        QuickStyle.PRIMARY, QuickStyle.ACTIVE -> scheme.onPrimary
        QuickStyle.PLAIN -> scheme.onSurface
        QuickStyle.OUTLINED -> scheme.primary
    }
    val live = stringResource(R.string.mode_live_now)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = container,
        contentColor = content,
        border = when (style) {
            QuickStyle.OUTLINED -> BorderStroke(2.dp, scheme.primary)
            // A ring inside the fill: "this one is on", also for people who can't tell the colours apart.
            QuickStyle.ACTIVE -> BorderStroke(3.dp, scheme.onPrimary.copy(alpha = 0.55f))
            else -> null
        },
        modifier = modifier.semantics {
            role = Role.Button
            if (tag != null) stateDescription = live + ": " + tag
        },
    ) {
        val tagLine: @Composable (TextAlign) -> Unit = { align ->
            if (tag != null) {
                Text(
                    "\u25CF " + tag,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = align,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
        if (wide) {
            Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    tagLine(TextAlign.Start)
                    Text(label, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            return@Surface
        }
        Column(
            Modifier.heightIn(min = 72.dp).padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            tagLine(TextAlign.Center)
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- where to play from ---------------------------------------------------------

private class SourceTile(val icon: ImageVector, val label: Int, val hint: Int, val onClick: () -> Unit)

/** One corner of some tiles is tighter, so the grid doesn't look like a table. */
private fun tileShape(index: Int): Shape {
    val big = 24.dp
    val small = 8.dp
    return when (index % 8) {
        1 -> RoundedCornerShape(big, big, big, small)
        2 -> RoundedCornerShape(small, big, big, big)
        5 -> RoundedCornerShape(big, small, big, big)
        6 -> RoundedCornerShape(big, big, small, big)
        else -> RoundedCornerShape(big)
    }
}

@Composable
private fun SourceTiles(tiles: List<SourceTile>) {
    val colors = LocalCastColors.current
    // Two tiles side by side leave a large font no room for the names.
    val columns = if (isLargeFont()) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(columns).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { column, tile ->
                    val index = rowIndex * columns + column
                    Surface(
                        onClick = tile.onClick,
                        shape = tileShape(index),
                        color = colors.tints[index % colors.tints.size],
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).fillMaxHeight().semantics { role = Role.Button },
                    ) {
                        Column(Modifier.heightIn(min = 84.dp).padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Box(
                                Modifier.size(32.dp).clip(RoundedCornerShape(11.dp)).background(colors.tileChip),
                                contentAlignment = Alignment.Center,
                            ) { Icon(tile.icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            Spacer(Modifier.height(8.dp))
                            // Two lines each: German names don't fit one line on a 360 dp phone.
                            Column {
                                Text(stringResource(tile.label), fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    stringResource(tile.hint),
                                    fontSize = 12.sp,
                                    lineHeight = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (row.size < columns) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ---- the running cast -----------------------------------------------------------

/** How far the file has played, 0..1; ticks on its own between the TV's status updates. */
@Composable
private fun castProgress(state: CastState.Active): Float {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.paused, state.phase) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val duration = state.durationMs
    return if (duration > 0) (state.positionNow(now).toFloat() / duration).coerceIn(0f, 1f) else 0f
}

/** The bar at the bottom while something is cast: what and where, pause, and a tap opens the player. */
@Composable
private fun CastBar(state: CastState.Active, actions: DevicesActions, onOpen: () -> Unit) {
    val colors = LocalCastColors.current
    val file = state.kind == CastKind.FILE
    val showProgress = file && !state.live && !state.isPhoto && state.durationMs > 0
    val openLabel = stringResource(R.string.home_bar_open)
    Surface(
        color = colors.bar,
        contentColor = colors.onBar,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 8.dp).fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen),
    ) {
        Column {
            Row(
                Modifier.padding(start = 10.dp, end = 8.dp, top = 10.dp, bottom = if (showProgress) 6.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(colors.barButton),
                    contentAlignment = Alignment.Center,
                ) { Icon(castIcon(state), contentDescription = null, tint = colors.barAccent, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    Text(castTitle(state), fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(castDetails(state), fontSize = 12.sp, lineHeight = 15.sp, color = colors.barMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (file && !state.isPhoto) {
                    val playing = state.phase == CastPhase.LIVE && !state.paused
                    IconButton(onClick = actions.onPlayPause) {
                        Icon(
                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(if (playing) R.string.action_pause else R.string.action_play),
                        )
                    }
                } else if (state.canDarkScreen) {
                    IconButton(onClick = actions.onDarkScreen) {
                        Icon(Icons.Rounded.DarkMode, contentDescription = stringResource(R.string.action_dark_screen))
                    }
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(colors.barButton),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null, tint = colors.barAccent) }
            }
            if (showProgress) {
                val progress = castProgress(state)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).height(3.dp),
                    color = colors.barAccent,
                    trackColor = colors.barButton,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

/** The player and, for TVs, the remote: the content of the sheet behind the cast bar. */
@Composable
private fun PlayerBody(state: CastState.Active, actions: DevicesActions) {
    PlayerPanel(state, actions)
    if (state.device.video) {
        Spacer(Modifier.height(16.dp))
        HomeRemote(state.device, onOpenFull = { actions.onOpenRemote(state.device) })
    }
}

@Composable
private fun PaneCard(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = LocalCastColors.current.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

// ---- picking a TV ----------------------------------------------------------------

/**
 * "Cast to": the TVs (in audio mode the speakers too), the one in use marked. Also where
 * a TV that doesn't show up is looked for: search again, scan the network, add by IP.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvPickerBody(
    listed: List<DeviceEntry>,
    targetId: String?,
    active: CastState.Active?,
    scanning: Boolean,
    audioMode: Boolean,
    actions: DevicesActions,
    onPick: (DeviceEntry) -> Unit,
    onMore: (DeviceEntry) -> Unit,
    onAdd: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.home_cast_to),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (scanning) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            }
        } else {
            IconButton(onClick = actions.onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.refresh))
            }
        }
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        SegmentedButton(
            selected = !audioMode,
            onClick = { actions.onAudioMode(false) },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
            icon = { Icon(Icons.Rounded.Tv, contentDescription = null, modifier = Modifier.size(18.dp)) },
        ) { Text(stringResource(R.string.mode_video)) }
        SegmentedButton(
            selected = audioMode,
            onClick = { actions.onAudioMode(true) },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
            icon = { Icon(Icons.Rounded.Speaker, contentDescription = null, modifier = Modifier.size(18.dp)) },
        ) { Text(stringResource(R.string.mode_audio)) }
    }
    if (listed.isEmpty()) {
        Row(
            Modifier.padding(vertical = 16.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.width(16.dp))
            Text(stringResource(if (scanning) R.string.scanning_network else R.string.searching), style = MaterialTheme.typography.bodyMedium)
        }
    }
    for (entry in listed) {
        PickerRow(
            entry = entry,
            selected = entry.device.id == targetId,
            casting = active != null && HomeLogic.sameDevice(active.device, entry.device),
            onClick = { onPick(entry) },
            onMore = { onMore(entry) },
        )
    }
    ButtonPair(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        FilledTonalButton(onClick = actions.onScan, enabled = !scanning) {
            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(if (scanning) R.string.scanning_short else R.string.scan_network), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(onClick = onAdd) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.add_by_ip), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    io.github.patschgoo.castapp.support.NetworkCheckButton()
    Text(stringResource(R.string.help_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    Text(
        stringResource(R.string.help_text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * Two buttons side by side at the same width when both labels fit, else one below the other
 * at full width (long German labels, narrow phones, large fonts), instead of cutting the labels.
 */
@Composable
private fun ButtonPair(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val width = constraints.maxWidth
        val half = (width - gap) / 2
        if (measurables.all { it.maxIntrinsicWidth(constraints.maxHeight) <= half }) {
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = half, maxWidth = half, minHeight = 0)) }
            val height = placeables.maxOf { it.height }
            layout(width, height) {
                placeables.forEachIndexed { i, p -> p.placeRelative(i * (half + gap), (height - p.height) / 2) }
            }
        } else {
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = width, minHeight = 0)) }
            layout(width, placeables.sumOf { it.height } + gap * (placeables.size - 1)) {
                var y = 0
                placeables.forEach {
                    it.placeRelative(0, y)
                    y += it.height + gap
                }
            }
        }
    }
}

@Composable
private fun PickerRow(entry: DeviceEntry, selected: Boolean, casting: Boolean, onClick: () -> Unit, onMore: () -> Unit) {
    val d = entry.device
    val scheme = MaterialTheme.colorScheme
    val selectedText = stringResource(R.string.home_selected)
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { if (selected) stateDescription = selectedText }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                deviceIcon(d),
                contentDescription = null,
                tint = when {
                    selected -> scheme.onPrimaryContainer
                    entry.online -> scheme.onSurface
                    else -> scheme.outline
                },
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // Long names (German TV names, large fonts) get a second line before they are cut.
            WholeWordsText(
                d.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (entry.online || selected) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 2,
            )
            val parts = buildList {
                if (casting) add(stringResource(R.string.main_casting_now))
                else if (!entry.online) add(stringResource(R.string.main_not_found))
                else d.status?.let { add(it) }
                d.kindLabel?.let { add(it) }
                if (d.group) add(stringResource(R.string.main_speaker_group))
                d.model?.let { add(it) }
                add(d.host)
            }
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (casting) scheme.primary else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.primary)
        IconButton(onClick = onMore) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.home_more_for, d.name))
        }
    }
}
