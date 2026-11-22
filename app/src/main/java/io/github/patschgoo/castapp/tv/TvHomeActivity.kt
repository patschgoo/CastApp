package io.github.patschgoo.castapp.tv

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.settings.ThemeMode
import io.github.patschgoo.castapp.ui.theme.CastAppTheme

/**
 * The TV home of CastApp (shown on Fire TV, Android TV, Google TV): starts the
 * receiver and explains how to cast to this TV from a phone.
 */
class TvHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvReceiver.init(this)
        TvReceiverService.start(this)
        setContent {
            CastAppTheme(ThemeMode.DARK) { TvHome() }
        }
    }

    override fun onResume() {
        super.onResume()
        TvReceiverService.start(this)
    }
}

@Composable
internal fun TvHome() {
    val context = LocalContext.current
    val running by TvReceiverService.running.collectAsStateWithLifecycle()
    val address by TvReceiverService.address.collectAsStateWithLifecycle()
    val tvState by TvReceiver.state.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(TvReceiver.deviceName(context)) }
    var startOnBoot by remember { mutableStateOf(TvReceiver.startOnBoot(context)) }
    var renaming by remember { mutableStateOf(false) }
    val needsOverlay = Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(context)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(Modifier.weight(1f)) {
                Icon(Icons.Rounded.CastConnected, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.tv_title), style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(if (running) Color(0xFF4CAF50) else Color(0xFFFFA000)))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            !running -> stringResource(R.string.tv_starting)
                            tvState.state == TransportState.PLAYING -> stringResource(R.string.tv_playing)
                            else -> stringResource(R.string.tv_ready)
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.tv_name_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(name, style = MaterialTheme.typography.headlineSmall)
                address?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.tv_address, it), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { renaming = true }) { Text(stringResource(R.string.tv_rename)) }
                    OutlinedButton(onClick = {
                        startOnBoot = !startOnBoot
                        TvReceiver.setStartOnBoot(context, startOnBoot)
                    }) {
                        Text(stringResource(if (startOnBoot) R.string.tv_boot_on else R.string.tv_boot_off))
                    }
                }
            }
            Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState())) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(28.dp)) {
                        Text(stringResource(R.string.tv_how_title), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(12.dp))
                        for (step in listOf(R.string.tv_step1, R.string.tv_step2, R.string.tv_step3)) {
                            Text(stringResource(step, name), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp), modifier = Modifier.padding(vertical = 6.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.tv_other_apps), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(20.dp))
                ReceiverProtectionCard()
                if (needsOverlay) {
                    Spacer(Modifier.height(20.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp)) {
                            Text(stringResource(R.string.tv_background_title), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.tv_background_text), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = {
                                try {
                                    context.startActivity(
                                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, ("package:" + context.packageName).toUri()),
                                    )
                                } catch (_: Exception) {
                                    android.widget.Toast.makeText(context, R.string.tv_background_unavailable, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }) { Text(stringResource(R.string.tv_background_allow)) }
                        }
                    }
                }
            }
        }
    }

    if (renaming) {
        var draft by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.tv_rename)) },
            text = {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    TvReceiver.setDeviceName(context, draft)
                    name = TvReceiver.deviceName(context)
                    TvReceiverService.nameChanged(context)
                    renaming = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
