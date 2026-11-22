package io.github.patschgoo.castapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.service.ResumeRules
import io.github.patschgoo.castapp.service.WatchEntry
import io.github.patschgoo.castapp.service.WatchHistory

/**
 * "Continue watching" on the main screen: videos that were stopped part-way.
 * Tapping one asks which TV and plays it from where it was left.
 */
@Composable
fun ContinueWatchingRow() {
    val context = LocalContext.current
    val history = remember { WatchHistory.get(context) }
    val all by history.entries.collectAsStateWithLifecycle()
    val entries = all.filter { ResumeRules.resumeAt(it.positionMs, it.durationMs, it.finished) != null }.take(12)
    if (entries.isEmpty()) return
    val launcher = rememberCastLauncher()
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.continue_watching),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
            TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.continue_clear)) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.uri }) { entry ->
                ContinueCard(
                    entry,
                    onPlay = { entry.item?.let { launcher.cast(listOf(it)) } },
                    onRemove = { history.remove(entry.uri) },
                )
            }
        }
    }
    CastLauncherSheet(launcher)

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.continue_clear_title)) },
            text = { Text(stringResource(R.string.continue_clear_text)) },
            confirmButton = {
                TextButton(onClick = { history.clear(); confirmClear = false }) { Text(stringResource(R.string.continue_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ContinueCard(entry: WatchEntry, onPlay: () -> Unit, onRemove: () -> Unit) {
    OutlinedCard(onClick = onPlay, modifier = Modifier.width(210.dp)) {
        Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (entry.mimeType.startsWith("audio/")) Icons.Rounded.MusicNote else Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.continue_remove), modifier = Modifier.size(18.dp))
                }
            }
            Box(Modifier.padding(end = 12.dp).height(40.dp)) {
                Text(entry.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            val left = entry.durationMs - entry.positionMs
            Text(
                if (entry.durationMs > 0) stringResource(R.string.continue_left, formatClock(left)) else stringResource(R.string.continue_at, formatClock(entry.positionMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.progress >= 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { entry.progress }, modifier = Modifier.padding(end = 12.dp).fillMaxWidth())
            }
        }
    }
}
