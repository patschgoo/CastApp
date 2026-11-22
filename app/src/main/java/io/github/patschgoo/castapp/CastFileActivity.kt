package io.github.patschgoo.castapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SpeakerGroup
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceEntry
import io.github.patschgoo.castapp.service.CastService
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.service.MediaType
import io.github.patschgoo.castapp.ui.theme.CastAppTheme
import io.github.patschgoo.castapp.youtube.YouTubeCast
import io.github.patschgoo.castapp.youtube.YouTubeLink
import io.github.patschgoo.castapp.youtube.YouTubeTvSheet

/**
 * "Cast to TV" from the share sheet or "Open with": videos, music and photos (one
 * or many), or a link. A web page opens in the built-in browser to find its video;
 * a direct video link is cast right away. The TV plays everything by itself, so the
 * phone's screen can be turned off afterwards.
 */
class CastFileActivity : ComponentActivity() {
    private var items: List<MediaItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        val link = sharedText?.let { Regex("https?://\\S+").find(it)?.value }
        if (link != null && sharedUris(intent).isEmpty()) {
            val mime = MediaItem.mimeForUrl(link)
            val youTube = YouTubeLink.parse(link)
            if (youTube != null) {
                // A YouTube video: the TV's own YouTube app plays it best.
                showYouTubeSheet(youTube, link)
                return
            }
            if (looksLikeMediaFile(link)) {
                items = listOf(MediaItem(title = link.substringBefore('?').substringAfterLast('/').ifEmpty { link }, uri = link, mimeType = mime))
            } else {
                // A normal page: find the video in our browser.
                startActivity(Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, link))
                finish()
                return
            }
        } else {
            items = MediaItem.fromUris(contentResolver, sharedUris(intent))
        }
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.share_unsupported, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val app = CastApp.get(this)
        val soundOnly = items.all { it.type == MediaType.AUDIO }
        val title = if (items.size == 1) items[0].title else resources.getQuantityString(R.plurals.share_count, items.size, items.size)
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            CastAppTheme(settings.theme) {
                val all by app.devices.devices.collectAsStateWithLifecycle()
                val scanning by app.devices.scanning.collectAsStateWithLifecycle()
                PickTvSheet(
                    title = title,
                    devices = if (soundOnly) all else all.filter { it.device.video },
                    scanning = scanning,
                    onPick = ::castTo,
                    onScan = { app.devices.scanNetwork() },
                    onDismiss = ::finish,
                )
            }
        }
    }

    private fun showYouTubeSheet(link: YouTubeLink, original: String) {
        val app = CastApp.get(this)
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            CastAppTheme(settings.theme) {
                val all by app.devices.devices.collectAsStateWithLifecycle()
                val scanning by app.devices.scanning.collectAsStateWithLifecycle()
                YouTubeTvSheet(
                    link = link,
                    devices = all,
                    scanning = scanning,
                    onPick = { device ->
                        YouTubeCast.play(this, device, link)
                        finish()
                    },
                    onScan = { app.devices.scanNetwork() },
                    onDismiss = ::finish,
                    onOpenInBrowser = {
                        startActivity(Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, original))
                        finish()
                    },
                )
            }
        }
    }

    private fun looksLikeMediaFile(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return listOf(".mp4", ".m4v", ".mov", ".webm", ".mkv", ".m3u8", ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".wav")
            .any { path.endsWith(it) }
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM),
        )
        Intent.ACTION_SEND_MULTIPLE ->
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        else -> emptyList()
    }

    private fun castTo(device: CastDevice) {
        CastService.startMedia(this, device, items)
        finish()
    }

    override fun onStart() {
        super.onStart()
        CastApp.get(this).devices.startDiscovery()
    }

    override fun onStop() {
        CastApp.get(this).devices.stopDiscovery()
        super.onStop()
    }
}

/** Bottom sheet listing the TVs (and speakers, for music) to cast to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickTvSheet(
    title: String,
    devices: List<DeviceEntry>,
    scanning: Boolean,
    onPick: (CastDevice) -> Unit,
    onScan: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.share_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Spacer(Modifier.size(8.dp))
            if (devices.isEmpty()) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(if (scanning) R.string.scanning_network else R.string.searching))
                }
            }
            for (entry in devices) {
                val d = entry.device
                ListItem(
                    onClick = { onPick(d) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                    leadingContent = {
                        Icon(
                            when {
                                d.group -> Icons.Rounded.SpeakerGroup
                                !d.video -> Icons.Rounded.Speaker
                                else -> Icons.Rounded.Tv
                            },
                            contentDescription = null,
                            tint = if (entry.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                    },
                    supportingContent = {
                        Text(
                            listOfNotNull(if (entry.online) null else stringResource(R.string.dev_not_found_now), d.kindLabel, d.model, d.host)
                                .joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                ) { Text(d.name) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onScan, enabled = !scanning) {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (scanning) R.string.scanning_short else R.string.scan_network))
                }
            }
        }
    }
}
