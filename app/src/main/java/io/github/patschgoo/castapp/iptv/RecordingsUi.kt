package io.github.patschgoo.castapp.iptv

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Warning
import io.github.patschgoo.castapp.ui.readableWidth

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.ui.CastLauncherSheet
import io.github.patschgoo.castapp.ui.rememberCastLauncher

/** The recordings and the reminders: what is planned, what runs, what was saved. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val center = remember { Recordings.get(context) }
    val reminders = remember { Reminders.get(context) }
    val recordings by center.items.collectAsStateWithLifecycle()
    val reminderList by reminders.items.collectAsStateWithLifecycle()
    val settings by center.settings.values.collectAsStateWithLifecycle()
    val launcher = rememberCastLauncher()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Recording?>(null) }
    val askNotifications = rememberNotificationAsker()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.iptvx_recordings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.iptvx_recording_settings)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(Modifier.readableWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                listOf(
                    stringResource(R.string.iptvx_tab_recordings, recordings.size),
                    stringResource(R.string.iptvx_tab_reminders, reminderList.size),
                ).forEachIndexed { i, label ->
                    SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
            if (tab == 0) {
                val sorted = remember(recordings) {
                    recordings.sortedWith(compareByDescending<Recording> { it.isActive }.thenByDescending { it.plannedStartMs })
                }
                if (sorted.isEmpty()) {
                    EmptyHint(Icons.Rounded.FiberManualRecord, stringResource(R.string.iptvx_no_recordings))
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f).readableWidth(),
                    ) {
                        items(sorted, key = { it.id }) { r ->
                            RecordingCard(
                                r,
                                onPlay = { center.mediaItem(r)?.let { launcher.cast(listOf(it)) } },
                                onStop = { center.stop(r.id) },
                                onCancel = { center.cancelScheduled(r.id) },
                                onDelete = { confirmDelete = r },
                            )
                        }
                    }
                }
            } else {
                ReminderList(reminderList, onCancel = { reminders.cancel(it) }, onAskPermission = askNotifications, modifier = Modifier.weight(1f))
            }
        }
    }

    confirmDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.iptvx_delete_title)) },
            text = { Text(stringResource(R.string.iptvx_delete_text, r.title)) },
            confirmButton = {
                TextButton(onClick = {
                    center.delete(r.id)
                    confirmDelete = null
                }) { Text(stringResource(R.string.iptvx_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.iptvg_cancel)) } },
        )
    }
    if (showSettings) RecordingSettingsDialog(center.settings, settings, onDismiss = { showSettings = false })
    CastLauncherSheet(launcher)
}

@Composable
private fun RecordingCard(r: Recording, onPlay: () -> Unit, onStop: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val time = rememberTimeFormat()
    val whenText = DateUtils.formatDateTime(context, r.plannedStartMs, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL) +
        " " + time(r.plannedStartMs)
    val size = Formatter.formatShortFileSize(context, r.bytes)
    val status = when (r.state) {
        RecordingState.SCHEDULED -> stringResource(R.string.iptvx_state_scheduled)
        RecordingState.RECORDING -> stringResource(R.string.iptvx_state_recording, size, RecordingFormat.duration(r.recordedMs))
        RecordingState.DONE -> stringResource(R.string.iptvx_state_done, size, RecordingFormat.duration(r.recordedMs))
        RecordingState.PARTIAL -> stringResource(R.string.iptvx_state_partial, size, RecordingFormat.duration(r.recordedMs))
        RecordingState.FAILED -> stringResource(R.string.iptvx_state_failed)
    }
    val statusColor = when (r.state) {
        RecordingState.FAILED -> MaterialTheme.colorScheme.error
        RecordingState.PARTIAL -> MaterialTheme.colorScheme.tertiary
        RecordingState.RECORDING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${r.channelName} · $whenText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // An icon per state, so the state does not depend on the colour alone.
                    val stateIcon = when (r.state) {
                        RecordingState.SCHEDULED -> Icons.Rounded.Schedule
                        RecordingState.RECORDING -> Icons.Rounded.FiberManualRecord
                        RecordingState.DONE -> Icons.Rounded.CheckCircle
                        RecordingState.PARTIAL -> Icons.Rounded.Warning
                        RecordingState.FAILED -> Icons.Rounded.Error
                    }
                    Icon(stateIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall, color = statusColor)
                }
                if (r.state == RecordingState.PARTIAL && r.gaps > 0) {
                    Text(stringResource(R.string.iptvx_gaps, r.gaps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val problem = when (r.error) {
                    null -> null
                    RecordingBook.INTERRUPTED -> stringResource(R.string.iptvx_error_interrupted)
                    RecordingCenter.MISSED -> stringResource(R.string.iptvx_error_missed)
                    else -> r.error
                }
                if (problem != null && (r.state == RecordingState.FAILED || r.state == RecordingState.PARTIAL)) {
                    Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                r.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            when (r.state) {
                RecordingState.SCHEDULED -> IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.iptvx_cancel_recording)) }
                RecordingState.RECORDING -> IconButton(onClick = onStop) { Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.iptvx_stop_recording)) }
                RecordingState.DONE, RecordingState.PARTIAL -> {
                    IconButton(onClick = onPlay) { Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.iptvx_play_on_tv), tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.iptvx_delete)) }
                }
                RecordingState.FAILED -> IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.iptvx_delete)) }
            }
        }
    }
}

@Composable
private fun ReminderList(items: List<Reminder>, onCancel: (String) -> Unit, onAskPermission: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val time = rememberTimeFormat()
    val notificationsOn = remember(items) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    Column(modifier.fillMaxSize()) {
        if (!notificationsOn) {
            Card(Modifier.readableWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.iptvx_notifications_off), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) onAskPermission()
                        val intent = if (Build.VERSION.SDK_INT >= 26) {
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        } else {
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", context.packageName, null))
                        }
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text(stringResource(R.string.iptvx_open_notification_settings)) }
                }
            }
        }
        if (items.isEmpty()) {
            EmptyHint(Icons.Rounded.Notifications, stringResource(R.string.iptvx_no_reminders))
            return@Column
        }
        LazyColumn(Modifier.readableWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { r ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val day = DateUtils.formatDateTime(context, r.startMs, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)
                            Text(
                                stringResource(R.string.iptvx_reminder_row, r.channelName, day, time(r.startMs)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onCancel(r.id) }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.iptvx_cancel_reminder)) }
                    }
                }
            }
        }
    }
}

/** Where recordings go (the phone, the gallery, a network folder) and how much extra is recorded. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordingSettingsDialog(settings: RecordingSettings, values: RecordingSettings.Values, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sources by CastApp.get(context).network.sources.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.iptvx_recording_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.iptvx_save_to), style = MaterialTheme.typography.labelLarge)
                @Composable
                fun option(selected: Boolean, label: String, onSelect: () -> Unit) {
                    Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp, top = 6.dp, bottom = 6.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                option(values.target == RecordingTarget.PHONE, stringResource(R.string.iptvx_dest_phone)) { settings.setTarget(RecordingTarget.PHONE) }
                if (RecordingSinks.galleryAvailable) {
                    option(values.target == RecordingTarget.GALLERY, stringResource(R.string.iptvx_dest_gallery)) { settings.setTarget(RecordingTarget.GALLERY) }
                }
                for (s in sources) {
                    option(values.target == RecordingTarget.NETWORK && values.sourceId == s.id, stringResource(R.string.iptvx_dest_network, s.name)) {
                        settings.setTarget(RecordingTarget.NETWORK, s.id)
                    }
                }
                if (sources.isEmpty()) {
                    Text(stringResource(R.string.iptvx_no_network_folders), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.iptvx_pad_before), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in listOf(0, 1, 2, 5, 10)) {
                        FilterChip(selected = values.padBeforeMin == m, onClick = { settings.setPadding(m, values.padAfterMin) }, label = { Text(stringResource(R.string.iptvg_minutes, m)) })
                    }
                }
                Text(stringResource(R.string.iptvx_pad_after), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in listOf(0, 2, 5, 10, 15, 30)) {
                        FilterChip(selected = values.padAfterMin == m, onClick = { settings.setPadding(values.padBeforeMin, m) }, label = { Text(stringResource(R.string.iptvg_minutes, m)) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.iptvx_done)) } },
    )
}

/** An empty list: an icon and what to do to fill it. */
@Composable
private fun EmptyHint(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Column(Modifier.readableWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
