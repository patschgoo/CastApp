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
import androidx.compose.material.icons.Icons
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
import io.github.patschgoo.castapp.service.FileCastSession
import io.github.patschgoo.castapp.ui.theme.CastAppTheme

/**
 * "Cast to TV" from the share sheet or "Open with": shows the TVs and casts the
 * video or music file to the one you tap. The TV plays the file directly, so
 * the phone's screen can be turned off afterwards.
 */
class CastFileActivity : ComponentActivity() {
    private var uri: Uri? = null
    private var mimeType: String? = null
    private var title: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val u = sharedUri(intent)
        val mime = intent.type?.takeIf { it != "*/*" } ?: u?.let { contentResolver.getType(it) }
        if (u == null || mime == null || !(mime.startsWith("video/") || mime.startsWith("audio/"))) {
            Toast.makeText(this, R.string.share_unsupported, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        uri = u
        mimeType = mime
        title = FileCastSession.describe(contentResolver, u).first ?: u.lastPathSegment

        val app = CastApp.get(this)
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            CastAppTheme(settings.theme) {
                val devices by app.devices.devices.collectAsStateWithLifecycle()
                val scanning by app.devices.scanning.collectAsStateWithLifecycle()
                PickTvSheet(
                    fileName = title ?: "",
                    devices = devices,
                    scanning = scanning,
                    onPick = ::castTo,
                    onScan = { app.devices.scanNetwork() },
                    onDismiss = ::finish,
                )
            }
        }
    }

    private fun sharedUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        Intent.ACTION_VIEW -> intent.data
        else -> null
    }

    private fun castTo(device: CastDevice) {
        val u = uri ?: return
        CastService.startFile(this, device, u, mimeType, title)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickTvSheet(
    fileName: String,
    devices: List<DeviceEntry>,
    scanning: Boolean,
    onPick: (CastDevice) -> Unit,
    onScan: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.share_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
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
                ListItem(
                    onClick = { onPick(entry.device) },
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
                            listOfNotNull(if (entry.online) null else "Not found right now", entry.device.model, entry.device.host)
                                .joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                ) { Text(entry.device.name) }
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
