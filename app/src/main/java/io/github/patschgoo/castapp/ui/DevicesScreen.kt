package io.github.patschgoo.castapp.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceEntry
import io.github.patschgoo.castapp.service.CastController
import io.github.patschgoo.castapp.service.CastPhase
import io.github.patschgoo.castapp.service.CastService
import io.github.patschgoo.castapp.service.CastState
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ClosedCaptionDisabled
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.mutableLongStateOf
import io.github.patschgoo.castapp.service.CastKind
import android.os.SystemClock
import kotlinx.coroutines.delay
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator

/** Callbacks of the main screen, so the screen itself stays free of app wiring. */
class DevicesActions(
    val onRefresh: () -> Unit,
    val onScan: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onMirror: (CastDevice) -> Unit,
    val onCastApp: (CastDevice) -> Unit,
    val onCastFiles: (CastDevice, FilePick) -> Unit,
    val onCastSound: (CastDevice) -> Unit,
    val onOpenBrowser: () -> Unit,
    val onAudioMode: (Boolean) -> Unit,
    val onForget: (CastDevice) -> Unit,
    val onAddManual: suspend (String) -> CastDevice?,
    val onStop: () -> Unit,
    val onVolumeDown: () -> Unit,
    val onVolumeUp: () -> Unit,
    val onToggleMute: () -> Unit,
    val onDarkScreen: () -> Unit,
    val onPlayPause: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onRewind: () -> Unit,
    val onForward: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onToggleSubtitles: () -> Unit,
    val onOpenTvChannels: () -> Unit = {},
    val onOpenNetwork: () -> Unit = {},
    val onQuality: (String) -> Unit = {},
    val onCreateSubtitles: () -> Unit = {},
    val onLiveCaption: () -> Unit = {},
    /** Mirroring to a CastApp TV: let the TV mirror to this phone instead. */
    val onSwap: () -> Unit = {},
    val onStopReceiving: () -> Unit = {},
    /** Opens the remote control, for [CastDevice] if one is given. */
    val onOpenRemote: (CastDevice?) -> Unit = {},
    /** Opens Plex or Jellyfin / Emby. */
    val onOpenServers: (io.github.patschgoo.castapp.mediaservers.ServerKind) -> Unit = {},
    /** Makes a device the one "Cast to" uses. */
    val onSelect: (CastDevice) -> Unit = {},
    /** Plays files of this phone that were not chosen in the system picker ("Recent on this phone"). */
    val onCastUris: (CastDevice, List<android.net.Uri>) -> Unit = { _, _ -> },
    /** Turns "Recent on this phone" off (Settings turns it on again). */
    val onHideRecent: () -> Unit = {},
)

@Composable
fun DevicesScreen(
    app: CastApp,
    onOpenSettings: () -> Unit,
    onMirror: (CastDevice) -> Unit,
    onCastApp: (CastDevice) -> Unit,
    onCastFiles: (CastDevice, FilePick) -> Unit,
    onCastSound: (CastDevice) -> Unit,
    onOpenBrowser: () -> Unit,
    onDarkScreen: () -> Unit,
    onOpenTvChannels: () -> Unit = {},
    onOpenNetwork: () -> Unit = {},
    onOpenServers: (io.github.patschgoo.castapp.mediaservers.ServerKind) -> Unit = {},
    onCastUris: (CastDevice, List<android.net.Uri>) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val devices by app.devices.devices.collectAsStateWithLifecycle()
    val audioMode by app.devices.audioMode.collectAsStateWithLifecycle()
    val lastUsedId by app.devices.lastUsedId.collectAsStateWithLifecycle()
    val scanning by app.devices.scanning.collectAsStateWithLifecycle()
    val castState by CastController.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val recentHidden by rememberUpdatedState(stringResource(R.string.home_recent_hidden))

    LaunchedEffect(Unit) {
        CastController.messages.collect { snackbar.showSnackbar(it) }
    }

    val actions = remember(app) {
        DevicesActions(
            onRefresh = { app.devices.refresh() },
            onScan = { app.devices.scanNetwork() },
            onOpenSettings = onOpenSettings,
            onMirror = onMirror,
            onCastApp = onCastApp,
            onCastFiles = onCastFiles,
            onCastSound = onCastSound,
            onOpenBrowser = onOpenBrowser,
            onAudioMode = { app.devices.setAudioMode(it) },
            onForget = { app.devices.forget(it.id) },
            onAddManual = { app.devices.addManual(it) },
            onStop = { CastService.stop(context) },
            onVolumeDown = { CastService.send(context, CastService.ACTION_VOLUME_DOWN) },
            onVolumeUp = { CastService.send(context, CastService.ACTION_VOLUME_UP) },
            onToggleMute = { CastService.send(context, CastService.ACTION_TOGGLE_MUTE) },
            onDarkScreen = onDarkScreen,
            onPlayPause = { CastService.send(context, CastService.ACTION_PLAY_PAUSE) },
            onSeek = { CastService.seek(context, it) },
            onRewind = { CastService.send(context, CastService.ACTION_REWIND) },
            onForward = { CastService.send(context, CastService.ACTION_FORWARD) },
            onNext = { CastService.send(context, CastService.ACTION_NEXT) },
            onPrevious = { CastService.send(context, CastService.ACTION_PREVIOUS) },
            onToggleSubtitles = { CastService.send(context, CastService.ACTION_SUBTITLES) },
            onOpenTvChannels = onOpenTvChannels,
            onOpenNetwork = onOpenNetwork,
            onQuality = { CastService.setQuality(context, it) },
            onCreateSubtitles = { CastService.send(context, CastService.ACTION_CREATE_SUBTITLES) },
            onLiveCaption = {
                // Live Caption writes subtitles for any sound on the phone; they're on the screen, so on the TV too.
                try {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_CAPTIONING_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
            },
            onSwap = {
                (CastController.state.value as? CastState.Active)?.device?.let {
                    io.github.patschgoo.castapp.service.SwapDirection.toPhone(context, it)
                }
            },
            onStopReceiving = { io.github.patschgoo.castapp.tv.TvReceiverService.stop(context) },
            onOpenRemote = { device ->
                context.startActivity(
                    io.github.patschgoo.castapp.RemoteActivity.intent(context, device?.let(io.github.patschgoo.castapp.remote.RemoteTargets::idFor)),
                )
            },
            onOpenServers = onOpenServers,
            onSelect = { app.devices.markUsed(it) },
            onCastUris = onCastUris,
            onHideRecent = {
                app.settings.update { it.copy(recentVideos = false) }
                scope.launch { snackbar.showSnackbar(recentHidden) }
            },
        )
    }
    TileHomeContent(devices, scanning, castState as? CastState.Active, snackbar, actions, audioMode, lastUsedId, settings.recentVideos)
}

/** The icon for what is being cast: a file, a photo, sound only, or the screen. */
internal fun castIcon(state: CastState.Active): ImageVector = when {
    state.audioOnly -> Icons.Rounded.Speaker
    state.isPhoto -> Icons.Rounded.Photo
    state.kind == CastKind.FILE -> Icons.Rounded.Movie
    else -> Icons.Rounded.CastConnected
}

/** The file's title, or "Casting to <TV>". */
@Composable
internal fun castTitle(state: CastState.Active): String = when {
    state.kind == CastKind.FILE -> state.title ?: stringResource(R.string.main_video)
    state.audioOnly -> stringResource(R.string.casting_sound_to, state.device.name)
    else -> stringResource(R.string.casting_to, state.device.name)
}

/** The line under the title: where it plays and how (phase, queue position, resolution, bitrate…). */
@Composable
internal fun castDetails(state: CastState.Active): String =
    if (state.kind == CastKind.FILE) {
        buildList {
            add(
                when {
                    state.phase != CastPhase.LIVE -> state.phase.label
                    state.paused && state.isPhoto -> stringResource(R.string.main_slideshow_paused)
                    state.paused -> stringResource(R.string.main_paused_on, state.device.name)
                    else -> stringResource(R.string.main_on_device_screen_off, state.device.name)
                },
            )
            if (state.queueSize > 1) add(stringResource(R.string.main_queue_position, state.queueIndex + 1, state.queueSize))
            if (state.converting) add(stringResource(R.string.main_converted_on_phone))
        }.joinToString(" · ")
    } else {
        buildList {
            add(state.phase.label)
            if (state.audioOnly) add(stringResource(R.string.main_phone_sound))
            if (state.resolution.isNotEmpty()) add(state.resolution)
            if (state.bitrateKbps > 0 && !state.audioOnly) add(stringResource(R.string.main_bitrate, state.bitrateKbps / 1000f))
            if (state.videoCodec.isNotEmpty() && !state.audioOnly) add(state.videoCodec)
            if (state.microphone) add(stringResource(R.string.mic_on_short))
            if (!state.audioOnly && state.phase != CastPhase.CONNECTING) add(stringResource(if (state.audio) R.string.main_audio_on else R.string.main_audio_off))
            if (state.lowLatencyPlayer) add(stringResource(R.string.main_low_latency))
            if (state.compatibilityMode) add(stringResource(R.string.main_compatible_mode))
        }.joinToString(" · ")
    }

/**
 * Everything about the running cast: what plays where, the playback controls, quality,
 * subtitles, queue, sleep timer, the other screens, volume, Stop and Dark screen.
 * The main screen shows it in the sheet behind the cast bar (or beside the list on tablets).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerPanel(state: CastState.Active, actions: DevicesActions, modifier: Modifier = Modifier) {
    Column(modifier) {
        val file = state.kind == CastKind.FILE
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(castIcon(state), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            // TalkBack announces changes here (connecting, live, paused, errors in the phase label).
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(castTitle(state), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(castDetails(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.phase != CastPhase.LIVE) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        if (file && state.isPhoto) PhotoControls(state, actions)
        else if (file) PlaybackControls(state, actions)
        ExtrasRow(state, actions)
        PlaybackExtras(state)
        MultiScreenSection(state)
        Spacer(Modifier.height(12.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) { VolumeControls(state, actions) }
            Button(onClick = actions.onStop) {
                Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_stop))
            }
        }
        if (state.canDarkScreen) {
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = actions.onDarkScreen, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.DarkMode, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_dark_screen))
            }
            Text(
                stringResource(R.string.dark_screen_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}


@Composable
private fun PlaybackControls(state: CastState.Active, actions: DevicesActions) {
    // Tick the position locally between the TV's (infrequent) status updates.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.paused, state.phase) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    if (state.live) {
        LiveControls(state, actions)
        return
    }
    val duration = state.durationMs.coerceAtLeast(0)
    val position = state.positionNow(now)
    val sliderState = remember { SliderState() }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(position, duration, dragging) {
        if (!dragging) sliderState.value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    }
    val positionLabel = stringResource(R.string.polish_position)
    val positionValue = stringResource(
        R.string.polish_position_value,
        formatTime(if (dragging) (sliderState.value * duration).toLong() else position),
        if (duration > 0) formatTime(duration) else "--:--",
    )
    Column(Modifier.padding(top = 8.dp)) {
        Slider(
            modifier = Modifier.semantics {
                contentDescription = positionLabel
                stateDescription = positionValue
            },
            state = sliderState,
            onValueChange = {
                dragging = true
                sliderState.value = it
            },
            onValueChangeFinished = {
                if (duration > 0) actions.onSeek((sliderState.value * duration).toLong())
                dragging = false
            },
            enabled = duration > 0,
        )
        Row(Modifier.clearAndSetSemantics { }) {
            Text(formatTime(if (dragging) (sliderState.value * duration).toLong() else position), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0) formatTime(duration) else "--:--", style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (state.queueSize > 1) {
                IconButton(onClick = actions.onPrevious, enabled = state.queueIndex > 0 || position > 5000) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.action_previous))
                }
            }
            IconButton(onClick = actions.onRewind) {
                Icon(Icons.Rounded.Replay10, contentDescription = stringResource(R.string.action_rewind))
            }
            Spacer(Modifier.width(12.dp))
            FilledIconButton(onClick = actions.onPlayPause, modifier = Modifier.size(56.dp)) {
                val playing = state.phase == CastPhase.LIVE && !state.paused
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.action_pause else R.string.action_play),
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = actions.onForward) {
                Icon(Icons.Rounded.Forward30, contentDescription = stringResource(R.string.action_forward))
            }
            if (state.queueSize > 1) {
                IconButton(onClick = actions.onNext, enabled = state.queueIndex + 1 < state.queueSize) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.action_next))
                }
            }
            if (state.hasSubtitles) {
                val subtitleState = stringResource(if (state.subtitlesOn) R.string.polish_on else R.string.polish_off)
                IconButton(onClick = actions.onToggleSubtitles, modifier = Modifier.semantics { stateDescription = subtitleState }) {
                    Icon(
                        if (state.subtitlesOn) Icons.Rounded.ClosedCaption else Icons.Rounded.ClosedCaptionDisabled,
                        contentDescription = stringResource(R.string.action_subtitles),
                    )
                }
            }
        }
    }
}

/** TV channels: no seeking; Previous/Next switch channels. */
@Composable
private fun LiveControls(state: CastState.Active, actions: DevicesActions) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (state.queueSize > 1) {
            IconButton(onClick = actions.onPrevious, enabled = state.queueIndex > 0) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.main_previous_channel))
            }
            Spacer(Modifier.width(12.dp))
        }
        FilledIconButton(onClick = actions.onPlayPause, modifier = Modifier.size(56.dp)) {
            val playing = state.phase == CastPhase.LIVE && !state.paused
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (playing) R.string.action_pause else R.string.action_play),
                modifier = Modifier.size(32.dp),
            )
        }
        if (state.queueSize > 1) {
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = actions.onNext, enabled = state.queueIndex + 1 < state.queueSize) {
                Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.main_next_channel))
            }
        }
    }
}

/** Quality picker and subtitle helpers under the controls. */
@Composable
private fun ExtrasRow(state: CastState.Active, actions: DevicesActions) {
    val mirroring = state.kind != CastKind.FILE && !state.audioOnly
    val showQuality = state.qualityOptions.isNotEmpty()
    val writing = state.subtitleProgress >= 0
    val findSubtitles = canFindSubtitles(state)
    if (!showQuality && !state.canCreateSubtitles && !writing && !mirroring && !findSubtitles && !state.device.video) return
    Column(Modifier.padding(top = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showQuality) {
                var open by remember { mutableStateOf(false) }
                Box {
                    AssistChip(
                        onClick = { open = true },
                        label = { Text(stringResource(R.string.main_quality, state.qualityLabel.ifEmpty { "–" })) },
                        leadingIcon = { Icon(Icons.Rounded.HighQuality, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for (option in state.qualityOptions) {
                            DropdownMenuItem(
                                text = { Text(qualityText(option, mirroring)) },
                                leadingIcon = if (option == state.qualityLabel) {
                                    { Icon(Icons.Rounded.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    open = false
                                    if (option != state.qualityLabel) actions.onQuality(option)
                                },
                            )
                        }
                    }
                }
            }
            if (state.canCreateSubtitles && !writing) {
                AssistChip(
                    onClick = actions.onCreateSubtitles,
                    label = { Text(stringResource(R.string.main_write_subtitles)) },
                    leadingIcon = { Icon(Icons.Rounded.Subtitles, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (findSubtitles) FindSubtitlesChip()
            if (state.device.video) {
                AssistChip(
                    onClick = { actions.onOpenRemote(state.device) },
                    label = { Text(stringResource(R.string.remote_open_short)) },
                    leadingIcon = { Icon(Icons.Rounded.SettingsRemote, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (mirroring && state.device.isCastAppTv) {
                AssistChip(
                    onClick = actions.onSwap,
                    label = { Text(stringResource(R.string.swap_action)) },
                    leadingIcon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (mirroring) {
                AssistChip(
                    onClick = actions.onLiveCaption,
                    label = { Text(stringResource(R.string.main_live_caption)) },
                    leadingIcon = { Icon(Icons.Rounded.ClosedCaption, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
        if (writing) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.main_writing_subtitles, state.subtitleProgress), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { state.subtitleProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun qualityText(option: String, mirroring: Boolean): String = when {
    option == "Original" -> stringResource(R.string.main_quality_original)
    option == "Auto" -> stringResource(R.string.main_quality_auto)
    mirroring && option == "4K" -> stringResource(R.string.main_quality_4k)
    !mirroring && option.endsWith("p") -> stringResource(R.string.main_quality_converted, option)
    else -> option
}

/** Shown while this phone receives casts (Settings → Receive on this phone, or after a swap). */
@Composable
internal fun ReceivingCard(actions: DevicesActions) {
    val receiving by io.github.patschgoo.castapp.tv.TvReceiverService.running.collectAsStateWithLifecycle()
    if (!receiving) return
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CastConnected, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.receive_card, io.github.patschgoo.castapp.tv.TvReceiver.deviceName(context)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions.onStopReceiving) { Text(stringResource(R.string.action_stop)) }
        }
    }
}

@Composable
private fun PhotoControls(state: CastState.Active, actions: DevicesActions) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onPrevious, enabled = state.queueIndex > 0) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.action_previous))
        }
        Spacer(Modifier.width(12.dp))
        FilledIconButton(onClick = actions.onPlayPause, modifier = Modifier.size(56.dp), enabled = state.queueSize > 1) {
            Icon(
                if (state.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = stringResource(if (state.paused) R.string.action_play else R.string.action_pause),
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        IconButton(onClick = actions.onNext, enabled = state.queueIndex + 1 < state.queueSize) {
            Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.action_next))
        }
    }
}

@Composable
private fun VolumeControls(state: CastState.Active, actions: DevicesActions) {
    val buttonColors = IconButtonDefaults.filledIconButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    FilledIconButton(onClick = actions.onVolumeDown, colors = buttonColors) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, contentDescription = stringResource(R.string.action_volume_down))
    }
    Text(
        when {
            state.muted -> stringResource(R.string.main_muted)
            state.volume != null -> "${(state.volume * 100).toInt()}%"
            else -> "–"
        },
        style = MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 52.dp).padding(horizontal = 4.dp),
    )
    FilledIconButton(onClick = actions.onVolumeUp, colors = buttonColors) {
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = stringResource(R.string.action_volume_up))
    }
    Spacer(Modifier.width(4.dp))
    val muteState = stringResource(if (state.muted) R.string.main_muted else R.string.polish_sound_on)
    FilledIconButton(
        onClick = actions.onToggleMute,
        modifier = Modifier.semantics { stateDescription = muteState },
        colors = if (state.muted) {
            IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.onPrimaryContainer,
                contentColor = MaterialTheme.colorScheme.primaryContainer,
            )
        } else buttonColors,
    ) {
        Icon(Icons.AutoMirrored.Rounded.VolumeOff, contentDescription = stringResource(R.string.action_mute))
    }
}

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceSheet(
    entry: DeviceEntry,
    audioMode: Boolean,
    onCastSound: () -> Unit,
    onOpenBrowser: () -> Unit,
    onDismiss: () -> Unit,
    onMirror: () -> Unit,
    onCastApp: () -> Unit,
    onCastFile: () -> Unit,
    onForget: () -> Unit,
    onRemote: () -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                entry.device.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                listOfNotNull(entry.device.model, entry.device.host).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            val d = entry.device
            if (audioMode || !d.video) {
                SheetAction(Icons.Rounded.Speaker, stringResource(R.string.cast_sound), stringResource(R.string.cast_sound_hint), onCastSound)
                SheetAction(Icons.Rounded.LibraryMusic, stringResource(R.string.cast_music_files), stringResource(R.string.cast_file_hint), onCastFile)
            } else {
                SheetAction(
                    Icons.AutoMirrored.Rounded.ScreenShare,
                    stringResource(R.string.mirror_screen),
                    stringResource(if (d.slowMirroring) R.string.mirror_screen_hint_dlna else R.string.mirror_screen_hint),
                    onMirror,
                )
                SheetAction(
                    Icons.Rounded.Apps,
                    stringResource(R.string.cast_app),
                    if (android.os.Build.VERSION.SDK_INT >= 34) stringResource(R.string.cast_app_hint_system) else stringResource(R.string.cast_app_hint_legacy),
                    onCastApp,
                )
                SheetAction(Icons.Rounded.VideoFile, stringResource(R.string.cast_file), stringResource(R.string.cast_file_hint), onCastFile)
                SheetAction(Icons.Rounded.Public, stringResource(R.string.web_video), stringResource(R.string.web_video_hint), onOpenBrowser)
                SheetAction(Icons.Rounded.Speaker, stringResource(R.string.cast_sound), stringResource(R.string.cast_sound_hint_tv), onCastSound)
                SheetAction(Icons.Rounded.SettingsRemote, stringResource(R.string.remote_open), stringResource(R.string.remote_open_hint), onRemote)
            }
            if (entry.saved) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SheetAction(Icons.Rounded.Delete, stringResource(R.string.forget_tv), null, onForget)
            }
            if (d.video) io.github.patschgoo.castapp.support.WakeOnLanSheetItems(d)
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 8.dp),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = subtitle?.let { { Text(it) } },
    ) { Text(title) }
}

@Composable
internal fun AddDeviceDialog(onDismiss: () -> Unit, onAdd: suspend (String) -> CastDevice?) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val notFound = stringResource(R.string.add_not_found)

    fun submit() {
        if (address.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            val device = onAdd(address)
            busy = false
            if (device != null) onDismiss() else error = notFound.format(address.trim())
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.add_by_ip)) },
        text = {
            Column {
                Text(stringResource(R.string.add_hint), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; error = null },
                    label = { Text(stringResource(R.string.ip_address)) },
                    placeholder = { Text("192.168.1.42") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = !busy && address.isNotBlank()) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
        },
    )
}
