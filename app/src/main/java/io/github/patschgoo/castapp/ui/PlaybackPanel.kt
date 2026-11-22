package io.github.patschgoo.castapp.ui

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastState
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.service.MediaType
import io.github.patschgoo.castapp.service.PlaybackHub
import io.github.patschgoo.castapp.service.QueueView
import io.github.patschgoo.castapp.service.RepeatMode
import io.github.patschgoo.castapp.service.SleepPlan
import io.github.patschgoo.castapp.service.SleepTimer
import io.github.patschgoo.castapp.service.SubtitleTracks
import io.github.patschgoo.castapp.service.TracksView
import kotlinx.coroutines.delay

/**
 * The playback extras of the casting card: resume notice, subtitle timing, and
 * chips for the queue, subtitles, sound track and sleep timer. Talks to the
 * running cast directly ([PlaybackHub]); the sleep timer works for mirroring too.
 */
@Composable
fun PlaybackExtras(state: CastState.Active) {
    val file = state.kind == CastKind.FILE
    val queue by PlaybackHub.queue.collectAsStateWithLifecycle()
    val tracks by PlaybackHub.tracks.collectAsStateWithLifecycle()
    val resumed by PlaybackHub.resumed.collectAsStateWithLifecycle()
    val sleep by SleepTimer.plan.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<Sheet?>(null) }

    Column(Modifier.padding(top = 8.dp)) {
        val notice = resumed
        if (file && notice != null && !state.live) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.resumed_at, formatClock(notice.positionMs)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { PlaybackHub.session?.startOver() }) { Text(stringResource(R.string.start_over)) }
            }
        }
        if (file && tracks.subtitlesActive && !state.isPhoto) SubtitleTiming(tracks)
        if (file && tracks.extractProgress >= 0) {
            Text(stringResource(R.string.subs_reading, tracks.extractProgress), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { tracks.extractProgress / 100f },
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val q = queue
            if (file && q != null) {
                AssistChip(
                    onClick = { sheet = Sheet.QUEUE },
                    label = { Text(stringResource(R.string.queue_chip, state.queueIndex + 1, state.queueSize)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (file && tracks.canPickSubtitles && !state.isPhoto) {
                val chosen = tracks.subtitleOptions.firstOrNull { it.selected }?.label ?: stringResource(R.string.subs_off)
                AssistChip(
                    onClick = { sheet = Sheet.SUBTITLES },
                    label = { Text(stringResource(R.string.subs_chip, chosen), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Rounded.Subtitles, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (file && tracks.audioOptions.isNotEmpty()) {
                val chosen = tracks.audioOptions.firstOrNull { it.selected }?.label ?: tracks.audioOptions.first().label
                AssistChip(
                    onClick = { sheet = Sheet.AUDIO },
                    label = { Text(stringResource(R.string.audio_chip, chosen), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Rounded.Audiotrack, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            SleepChip(sleep, onClick = { sheet = Sheet.SLEEP })
        }
    }

    when (sheet) {
        Sheet.QUEUE -> queue?.let { QueueSheet(it, state.live, onDismiss = { sheet = null }) } ?: run { sheet = null }
        Sheet.SUBTITLES -> SubtitleSheet(tracks, onDismiss = { sheet = null })
        Sheet.AUDIO -> AudioSheet(tracks, onDismiss = { sheet = null })
        Sheet.SLEEP -> SleepDialog(sleep, canStopAtItemEnd = file && !state.live && !state.isPhoto, onDismiss = { sheet = null })
        null -> Unit
    }
}

private enum class Sheet { QUEUE, SUBTITLES, AUDIO, SLEEP }

@Composable
private fun SubtitleTiming(tracks: TracksView) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        Text(stringResource(R.string.subs_timing), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = { PlaybackHub.session?.shiftSubtitles(-SubtitleTracks.STEP_MS) }) {
            Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.subs_earlier))
        }
        Text(
            SubtitleTracks.formatOffset(tracks.offsetMs),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.widthIn(min = 64.dp).padding(horizontal = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        FilledTonalIconButton(onClick = { PlaybackHub.session?.shiftSubtitles(SubtitleTracks.STEP_MS) }) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.subs_later))
        }
    }
}

@Composable
private fun SleepChip(plan: SleepPlan?, onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(plan) {
        while (plan != null && !plan.atItemEnd) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val label = when {
        plan == null -> stringResource(R.string.sleep_chip)
        plan.atItemEnd -> stringResource(R.string.sleep_chip_item_end)
        else -> stringResource(R.string.sleep_chip_left, SleepPlan.format(plan.remainingMs(now)))
    }
    if (plan == null) {
        AssistChip(
            onClick = onClick,
            label = { Text(label) },
            leadingIcon = { Icon(Icons.Rounded.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
    } else {
        FilterChip(
            selected = true,
            onClick = onClick,
            label = { Text(label) },
            leadingIcon = { Icon(Icons.Rounded.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
    }
}

@Composable
private fun SleepDialog(plan: SleepPlan?, canStopAtItemEnd: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                for (minutes in SleepPlan.CHOICES) {
                    ChoiceRow(pluralStringResource(R.plurals.sleep_minutes, minutes, minutes), selected = plan?.minutes == minutes && !plan.atItemEnd) {
                        SleepTimer.start(context, minutes)
                        onDismiss()
                    }
                }
                if (canStopAtItemEnd) {
                    ChoiceRow(stringResource(R.string.sleep_item_end), selected = plan?.atItemEnd == true) {
                        SleepTimer.stopAtEndOfItem(context)
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = {
            if (plan != null) {
                TextButton(onClick = { SleepTimer.cancel(); onDismiss() }) { Text(stringResource(R.string.sleep_cancel)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
        dismissButton = if (plan != null) {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
        } else null,
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitleSheet(tracks: TracksView, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp).selectableGroup()) {
            Text(stringResource(R.string.subs_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            for (option in tracks.subtitleOptions) {
                ChoiceRow(option.label, option.selected, option.detail) {
                    PlaybackHub.session?.chooseSubtitles(option.key)
                    onDismiss()
                }
            }
            if (tracks.probing) {
                Text(stringResource(R.string.subs_probing), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            if (tracks.pictureSubtitles > 0) {
                Text(
                    pluralStringResource(R.plurals.subs_picture_note, tracks.pictureSubtitles, tracks.pictureSubtitles),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioSheet(tracks: TracksView, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp).selectableGroup()) {
            Text(stringResource(R.string.audio_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.audio_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            for (option in tracks.audioOptions) {
                ChoiceRow(option.label, option.selected) {
                    PlaybackHub.session?.chooseAudioTrack(option.ordinal)
                    onDismiss()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(queue: QueueView, live: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        for (uri in uris) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        PlaybackHub.session?.queueAdd(MediaItem.fromUris(context.contentResolver, uris))
    }
    val current = queue.entries.indexOfFirst { it.id == queue.currentId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (live) R.string.queue_title_channels else R.string.queue_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Text("${current + 1}/${queue.entries.size}", style = MaterialTheme.typography.labelLarge)
            }
            if (queue.editable) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = queue.shuffled,
                        onClick = { PlaybackHub.session?.setShuffle(!queue.shuffled) },
                        label = { Text(stringResource(R.string.queue_shuffle)) },
                        leadingIcon = { Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    FilterChip(
                        selected = queue.repeat != RepeatMode.OFF,
                        onClick = { PlaybackHub.session?.cycleRepeat() },
                        label = {
                            Text(
                                stringResource(
                                    when (queue.repeat) {
                                        RepeatMode.OFF -> R.string.queue_repeat_off
                                        RepeatMode.ALL -> R.string.queue_repeat_all
                                        RepeatMode.ONE -> R.string.queue_repeat_one
                                    },
                                ),
                            )
                        },
                        leadingIcon = {
                            Icon(if (queue.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                    )
                    AssistChip(
                        onClick = { picker.launch(arrayOf("video/*", "audio/*", "image/*", "application/x-subrip", "text/vtt", "application/octet-stream")) },
                        label = { Text(stringResource(R.string.queue_add)) },
                        leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 480.dp)) {
                itemsIndexed(queue.entries, key = { _, e -> e.id }) { i, entry ->
                    val playing = entry.id == queue.currentId
                    ListItem(
                        onClick = { if (!playing) PlaybackHub.session?.queueJump(entry.id) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (playing) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                        leadingContent = {
                            Icon(
                                when {
                                    playing -> Icons.Rounded.PlayArrow
                                    entry.live -> Icons.Rounded.LiveTv
                                    entry.type == MediaType.AUDIO -> Icons.Rounded.MusicNote
                                    entry.type == MediaType.PHOTO -> Icons.Rounded.Photo
                                    else -> Icons.Rounded.Movie
                                },
                                contentDescription = if (playing) stringResource(R.string.queue_playing) else null,
                            )
                        },
                        trailingContent = if (queue.editable) {
                            {
                                Row {
                                    IconButton(onClick = { PlaybackHub.session?.queueMove(entry.id, -1) }, enabled = i > 0) {
                                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.queue_up))
                                    }
                                    IconButton(onClick = { PlaybackHub.session?.queueMove(entry.id, 1) }, enabled = i + 1 < queue.entries.size) {
                                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.queue_down))
                                    }
                                    IconButton(onClick = { PlaybackHub.session?.queueRemove(entry.id) }) {
                                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.queue_remove))
                                    }
                                }
                            }
                        } else null,
                    ) {
                        Text(
                            entry.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (playing) FontWeight.SemiBold else null,
                        )
                    }
                }
            }
        }
    }
}

/** 12:34 or 1:02:03 */
internal fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, s) else "%d:%02d".format(java.util.Locale.ROOT, m, s)
}
