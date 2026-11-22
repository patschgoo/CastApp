package io.github.patschgoo.castapp.iptv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.R

/** Asks for the notification permission (Android 13+) the first time a reminder or a recording needs it. */
@Composable
internal fun rememberNotificationAsker(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * One programme of the guide: what it is, and what to do with it. Live: watch, record from now.
 * Later: remind me, record. Past (with catch-up): watch again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProgrammeSheet(
    entry: ChannelEntry,
    programme: Programme,
    now: Long,
    canReplay: Boolean,
    reminder: Boolean,
    plannedRecording: Recording?,
    onWatch: () -> Unit,
    onReplay: () -> Unit,
    onToggleReminder: () -> Unit,
    onRecord: () -> Unit,
    onCancelRecording: () -> Unit,
    onSchedule: () -> Unit,
    onDismiss: () -> Unit,
) {
    val time = rememberTimeFormat()
    val context = LocalContext.current
    val live = programme.isOn(now)
    val past = programme.stop <= now
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(entry.channel, 36)
                Spacer(Modifier.width(10.dp))
                Text(entry.channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = onSchedule) { Text(stringResource(R.string.iptvx_all_programmes)) }
            }
            Text(programme.title, style = MaterialTheme.typography.titleLarge)
            val day = DateUtils.formatDateTime(context, programme.start, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)
            Text(
                stringResource(
                    R.string.iptvx_programme_time,
                    day, time(programme.start), time(programme.stop), ((programme.stop - programme.start) / 60_000L).toInt(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (live) {
                LinearProgressIndicator(
                    progress = { programme.progress(now) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            programme.desc?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(4.dp))
            if (live) {
                Button(onClick = onWatch, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.iptvx_watch_now))
                }
            }
            if (past && canReplay) {
                Button(onClick = onReplay, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.iptvg_replay))
                }
            }
            if (!past) {
                if (!live) {
                    FilledTonalButton(onClick = onToggleReminder, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (reminder) Icons.Rounded.NotificationsOff else Icons.Rounded.Notifications, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (reminder) R.string.iptvx_cancel_reminder else R.string.iptvx_remind_me))
                    }
                }
                if (plannedRecording != null) {
                    OutlinedButton(onClick = onCancelRecording, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (plannedRecording.state == RecordingState.RECORDING) R.string.iptvx_stop_recording else R.string.iptvx_cancel_recording))
                    }
                } else {
                    FilledTonalButton(onClick = onRecord, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.FiberManualRecord, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (live) R.string.iptvx_record_from_now else R.string.iptvx_record))
                    }
                }
            }
        }
    }
}

/** A short text for where recordings go. */
@Composable
internal fun destinationLabel(values: RecordingSettings.Values): String {
    val context = LocalContext.current
    return when (values.target) {
        RecordingTarget.PHONE -> stringResource(R.string.iptvx_dest_phone)
        RecordingTarget.GALLERY -> stringResource(R.string.iptvx_dest_gallery)
        RecordingTarget.NETWORK -> CastApp.get(context).network.get(values.sourceId)?.name ?: stringResource(R.string.iptvx_dest_missing)
    }
}

/**
 * "Record": for a programme with the extra minutes before and after, or ([programme] null) a channel
 * now for a time. [warning] says when the provider's connection would be shared with what is playing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordDialog(
    title: String,
    programme: Programme?,
    now: Long,
    values: RecordingSettings.Values,
    warning: String?,
    onConfirm: (padBeforeMin: Int, padAfterMin: Int, durationMin: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val time = rememberTimeFormat()
    var before by rememberSaveable { mutableIntStateOf(values.padBeforeMin) }
    var after by rememberSaveable { mutableIntStateOf(values.padAfterMin) }
    var duration by rememberSaveable { mutableIntStateOf(60) }
    val live = programme != null && programme.isOn(now)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.iptvx_record_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (programme != null) {
                    val from = if (live) now else programme.start - before * 60_000L
                    val to = programme.stop + after * 60_000L
                    Text(stringResource(R.string.iptvx_record_range, time(from), time(to)), style = MaterialTheme.typography.bodyMedium)
                    if (!live) {
                        Text(stringResource(R.string.iptvx_pad_before), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (m in listOf(0, 1, 2, 5, 10)) {
                                FilterChip(selected = before == m, onClick = { before = m }, label = { Text(stringResource(R.string.iptvg_minutes, m)) })
                            }
                        }
                    }
                    Text(stringResource(R.string.iptvx_pad_after), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (m in listOf(0, 2, 5, 10, 15, 30)) {
                            FilterChip(selected = after == m, onClick = { after = m }, label = { Text(stringResource(R.string.iptvg_minutes, m)) })
                        }
                    }
                } else {
                    Text(stringResource(R.string.iptvx_record_for), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (m in listOf(30, 60, 120, 180, 0)) {
                            FilterChip(
                                selected = duration == m,
                                onClick = { duration = m },
                                label = { Text(if (m == 0) stringResource(R.string.iptvx_until_stopped) else stringResource(R.string.iptvg_minutes, m)) },
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.iptvx_saved_to, destinationLabel(values)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (warning != null) {
                    Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(before, after, duration) }) { Text(stringResource(R.string.iptvx_record)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.iptvg_cancel)) } },
    )
}

/** Long press on a channel: record it now, set its lock. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelActionsSheet(
    channel: Channel,
    parental: ParentalSnapshot,
    now: Long,
    onRecordNow: () -> Unit,
    onProtect: (Protection) -> Unit,
    onSetUpLock: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FilledTonalButton(onClick = onRecordNow, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.FiberManualRecord, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.iptvx_record_now))
            }
            Text(stringResource(R.string.iptvx_parental_title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            if (!parental.hasPin) {
                OutlinedButton(onClick = onSetUpLock, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.iptvx_parental_set_up)) }
            } else {
                val own = parental.channelProtection(channel)
                val viaGroup = parental.groupProtection(channel.group)
                if (viaGroup != Protection.NONE) {
                    Text(
                        stringResource(R.string.iptvx_parental_by_group, channel.group.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!parental.unlocked(now)) {
                    Text(stringResource(R.string.iptvx_parental_enter_pin_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRowOf {
                    FilterChip(selected = own == Protection.NONE, onClick = { onProtect(Protection.NONE) }, label = { Text(stringResource(R.string.iptvx_parental_off)) })
                    FilterChip(
                        selected = own == Protection.LOCKED, onClick = { onProtect(Protection.LOCKED) },
                        label = { Text(stringResource(R.string.iptvx_parental_locked)) },
                        leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                    FilterChip(
                        selected = own == Protection.HIDDEN, onClick = { onProtect(Protection.HIDDEN) },
                        label = { Text(stringResource(R.string.iptvx_parental_hidden)) },
                        leadingIcon = { Icon(Icons.Rounded.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowOf(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
