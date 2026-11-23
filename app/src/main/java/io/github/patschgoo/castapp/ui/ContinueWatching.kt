package io.github.patschgoo.castapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.artwork.Artwork
import io.github.patschgoo.castapp.artwork.ArtworkRules
import io.github.patschgoo.castapp.service.ResumeRules
import io.github.patschgoo.castapp.service.WatchEntry
import io.github.patschgoo.castapp.service.WatchHistory

/**
 * "Continue watching" on the main screen: videos that were stopped part-way, each with its
 * picture ([Artwork]). Tapping one asks which TV and plays it from where it was left.
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
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            ContinuePicture(entry, Modifier.fillMaxSize())
            // On a dark disc, so it shows on any picture, in light and dark mode.
            IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd)) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.continue_remove), tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            if (entry.progress >= 0) {
                LinearProgressIndicator(
                    progress = { entry.progress },
                    trackColor = Color.Black.copy(alpha = 0.35f),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                )
            }
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
            Box(Modifier.heightIn(min = 40.dp)) {
                Text(entry.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            val left = entry.durationMs - entry.positionMs
            Text(
                if (entry.durationMs > 0) stringResource(R.string.continue_left, formatClock(left)) else stringResource(R.string.continue_at, formatClock(entry.positionMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The entry's poster, logo or a frame of the video ([Artwork]), and a film or music icon while
 * it loads or when there is none. Decorative: the card is read out by its title.
 */
@Composable
private fun ContinuePicture(entry: WatchEntry, modifier: Modifier) {
    val context = LocalContext.current
    val item = remember(entry.itemJson) { entry.item }
    val first = remember(item) { item?.let(Artwork::inMemory)?.asImageBitmap() }
    val picture by produceState<ImageBitmap?>(first, item) {
        value = first
        if (first == null && item != null) value = Artwork.load(context, item, entry.durationMs)?.asImageBitmap()
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val image = picture
        if (image == null) {
            Icon(
                if (entry.mimeType.startsWith("audio/")) Icons.Rounded.MusicNote else Icons.Rounded.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        } else {
            when (remember(image) { ArtworkRules.fitFor(image.width, image.height, image.hasAlpha) }) {
                ArtworkRules.Fit.FILL -> Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                ArtworkRules.Fit.POSTER -> {
                    // Blurred from Android 12 on, a faint copy below.
                    Image(image, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.3f, modifier = Modifier.fillMaxSize().blur(12.dp))
                    Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                ArtworkRules.Fit.LOGO ->
                    Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(16.dp))
            }
        }
    }
}
