package io.github.patschgoo.castapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

/** Callbacks of the device list, so the screen itself stays free of app wiring. */
class DevicesActions(
    val onRefresh: () -> Unit,
    val onScan: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onMirror: (CastDevice) -> Unit,
    val onCastApp: (CastDevice) -> Unit,
    val onCastFile: (CastDevice) -> Unit,
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
)

@Composable
fun DevicesScreen(
    app: CastApp,
    onOpenSettings: () -> Unit,
    onMirror: (CastDevice) -> Unit,
    onCastApp: (CastDevice) -> Unit,
    onCastFile: (CastDevice) -> Unit,
    onDarkScreen: () -> Unit,
) {
    val context = LocalContext.current
    val devices by app.devices.devices.collectAsStateWithLifecycle()
    val scanning by app.devices.scanning.collectAsStateWithLifecycle()
    val castState by CastController.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

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
            onCastFile = onCastFile,
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
        )
    }
    DevicesContent(devices, scanning, castState as? CastState.Active, snackbar, actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesContent(
    devices: List<DeviceEntry>,
    scanning: Boolean,
    active: CastState.Active?,
    snackbar: SnackbarHostState,
    actions: DevicesActions,
) {
    var sheetFor by remember { mutableStateOf<DeviceEntry?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (scanning) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        }
                    } else {
                        IconButton(onClick = actions.onRefresh) {
                            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.refresh))
                        }
                    }
                    IconButton(onClick = actions.onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (active != null) {
                item(key = "casting") {
                    CastingCard(state = active, actions = actions)
                }
            }

            item(key = "header") {
                Text(
                    stringResource(R.string.devices_header),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }

            if (devices.isEmpty()) {
                item(key = "empty") { EmptyCard(scanning) }
            }

            items(devices, key = { it.device.id }) { entry ->
                val castingHere = active?.device?.id == entry.device.id || active?.device?.host == entry.device.host
                DeviceCard(
                    entry = entry,
                    casting = castingHere,
                    onClick = { sheetFor = entry },
                    onCast = { if (castingHere) actions.onStop() else actions.onMirror(entry.device) },
                )
            }

            item(key = "help") {
                HelpCard(
                    scanning = scanning,
                    onScan = actions.onScan,
                    onAdd = { showAddDialog = true },
                )
            }
        }
    }

    sheetFor?.let { entry ->
        DeviceSheet(
            entry = entry,
            onDismiss = { sheetFor = null },
            onMirror = { sheetFor = null; actions.onMirror(entry.device) },
            onCastApp = { sheetFor = null; actions.onCastApp(entry.device) },
            onCastFile = { sheetFor = null; actions.onCastFile(entry.device) },
            onForget = { sheetFor = null; actions.onForget(entry.device) },
        )
    }

    if (showAddDialog) {
        AddDeviceDialog(
            onDismiss = { showAddDialog = false },
            onAdd = actions.onAddManual,
        )
    }
}

@Composable
private fun CastingCard(state: CastState.Active, actions: DevicesActions) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            val file = state.kind == CastKind.FILE
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (file) Icons.Rounded.Movie else Icons.Rounded.CastConnected,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (file) state.title ?: "Video" else stringResource(R.string.casting_to, state.device.name),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val details = if (file) {
                        when {
                            state.phase != CastPhase.LIVE -> state.phase.label
                            state.paused -> "Paused on ${state.device.name}"
                            else -> "Playing on ${state.device.name} · screen can be off"
                        }
                    } else {
                        buildList {
                            add(state.phase.label)
                            if (state.resolution.isNotEmpty()) add(state.resolution)
                            if (state.phase != CastPhase.CONNECTING) add(if (state.audio) "Audio on" else "No audio")
                            if (state.compatibilityMode) add("Compatible mode")
                        }.joinToString(" · ")
                    }
                    Text(details, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                if (state.phase != CastPhase.LIVE) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            if (file) PlaybackControls(state, actions)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                VolumeControls(state, actions)
                Spacer(Modifier.weight(1f))
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
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                )
            }
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
    val duration = state.durationMs.coerceAtLeast(0)
    val position = state.positionNow(now)
    val sliderState = remember { SliderState() }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(position, duration, dragging) {
        if (!dragging) sliderState.value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    }
    Column(Modifier.padding(top = 8.dp)) {
        Slider(
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
        Row {
            Text(formatTime(if (dragging) (sliderState.value * duration).toLong() else position), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0) formatTime(duration) else "--:--", style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
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
        }
    }
}

@Composable
private fun VolumeControls(state: CastState.Active, actions: DevicesActions) {
    val buttonColors = IconButtonDefaults.filledIconButtonColors(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    FilledIconButton(onClick = actions.onVolumeDown, colors = buttonColors) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, contentDescription = stringResource(R.string.action_volume_down))
    }
    Text(
        when {
            state.muted -> "Muted"
            state.volume != null -> "${(state.volume * 100).toInt()}%"
            else -> "–"
        },
        style = MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(52.dp),
    )
    FilledIconButton(onClick = actions.onVolumeUp, colors = buttonColors) {
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = stringResource(R.string.action_volume_up))
    }
    Spacer(Modifier.width(4.dp))
    FilledIconButton(
        onClick = actions.onToggleMute,
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

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
private fun DeviceCard(entry: DeviceEntry, casting: Boolean, onClick: () -> Unit, onCast: () -> Unit) {
    val d = entry.device
    ElevatedCard(Modifier.fillMaxWidth()) {
        ListItem(
            onClick = onClick,
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            leadingContent = {
                Icon(
                    Icons.Rounded.Tv,
                    contentDescription = null,
                    tint = if (entry.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            },
            supportingContent = {
                val parts = buildList {
                    if (casting) add("Casting now")
                    else if (!entry.online) add("Not found right now")
                    else d.status?.let { add(it) }
                    d.model?.let { add(it) }
                    add(d.host)
                }
                Text(parts.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = {
                if (casting) {
                    OutlinedButton(onClick = onCast) { Text(stringResource(R.string.action_stop)) }
                } else {
                    FilledTonalButton(onClick = onCast) { Text(stringResource(R.string.action_cast)) }
                }
            },
        ) { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun EmptyCard(scanning: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.width(16.dp))
            Text(
                if (scanning) stringResource(R.string.scanning_network) else stringResource(R.string.searching),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun HelpCard(scanning: Boolean, onScan: () -> Unit, onAdd: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.help_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.help_text), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onScan, enabled = !scanning) {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (scanning) stringResource(R.string.scanning_short) else stringResource(R.string.scan_network))
                }
                OutlinedButton(onClick = onAdd) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.add_by_ip))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSheet(
    entry: DeviceEntry,
    onDismiss: () -> Unit,
    onMirror: () -> Unit,
    onCastApp: () -> Unit,
    onCastFile: () -> Unit,
    onForget: () -> Unit,
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
            SheetAction(Icons.AutoMirrored.Rounded.ScreenShare, stringResource(R.string.mirror_screen), stringResource(R.string.mirror_screen_hint), onMirror)
            SheetAction(
                Icons.Rounded.Apps,
                stringResource(R.string.cast_app),
                if (android.os.Build.VERSION.SDK_INT >= 34) stringResource(R.string.cast_app_hint_system) else stringResource(R.string.cast_app_hint_legacy),
                onCastApp,
            )
            SheetAction(Icons.Rounded.VideoFile, stringResource(R.string.cast_file), stringResource(R.string.cast_file_hint), onCastFile)
            if (entry.saved) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SheetAction(Icons.Rounded.Delete, stringResource(R.string.forget_tv), null, onForget)
            }
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
private fun AddDeviceDialog(onDismiss: () -> Unit, onAdd: suspend (String) -> CastDevice?) {
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
