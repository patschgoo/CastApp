package io.github.patschgoo.castapp.youtube

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceEntry

/**
 * Bottom sheet "Play in YouTube on the TV": lists the TVs with a YouTube app we
 * can start (Chromecast / Google TV and CastApp TVs).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeTvSheet(
    link: YouTubeLink,
    devices: List<DeviceEntry>,
    scanning: Boolean,
    onPick: (CastDevice) -> Unit,
    onScan: () -> Unit,
    onDismiss: () -> Unit,
    /** Shown as "Open in the browser instead" when not null. */
    onOpenInBrowser: (() -> Unit)? = null,
) {
    val tvs = devices.filter { YouTubeCast.supports(it.device) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.yt_play_on_tv),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            val details = buildList {
                add(stringResource(R.string.yt_video, link.videoId))
                if (link.startSeconds > 0) add(stringResource(R.string.yt_starts_at, YouTubeLink.formatTime(link.startSeconds)))
                if (link.listId != null) add(stringResource(R.string.yt_with_playlist))
            }.joinToString(" · ")
            Text(
                details,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Spacer(Modifier.size(8.dp))
            if (tvs.isEmpty()) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.yt_no_devices))
                }
            }
            for (entry in tvs) {
                val d = entry.device
                ListItem(
                    onClick = { onPick(d) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                    leadingContent = {
                        Icon(
                            Icons.Rounded.Tv,
                            contentDescription = null,
                            tint = if (entry.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                    },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                if (d.isCastAppTv) stringResource(R.string.yt_via_tv_app) else stringResource(R.string.yt_via_cast),
                                d.model?.takeIf { !d.isCastAppTv },
                            ).joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                ) { Text(d.name) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                if (onOpenInBrowser != null) {
                    TextButton(onClick = onOpenInBrowser) {
                        Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.yt_open_in_browser))
                    }
                }
                TextButton(onClick = onScan, enabled = !scanning) {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (scanning) R.string.scanning_short else R.string.scan_network))
                }
            }
        }
    }
}
