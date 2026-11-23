package io.github.patschgoo.castapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.patschgoo.castapp.CompactRemote
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.RemoteCodeDialog
import io.github.patschgoo.castapp.RemoteViewModel
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.remote.RemoteDiscovery
import io.github.patschgoo.castapp.remote.RemoteTargets

/**
 * The remote inside the player sheet: D-pad, volume, channels, Back / Home / Menu / Mute
 * for the TV that is being cast to. It connects only when asked to ("Use this phone as
 * the remote"), because the first connection makes many TVs ask for permission on their
 * screen. Everything else (keyboard, numbers, voice, Bluetooth and infrared remotes) stays
 * in the full remote, one tap away.
 */
@Composable
internal fun HomeRemote(device: CastDevice, onOpenFull: () -> Unit) {
    val context = LocalContext.current
    val targetId = RemoteTargets.idFor(device)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.remote_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(onClick = onOpenFull) { Text(stringResource(R.string.home_remote_full)) }
        }
        if (targetId == null) {
            // No network remote is known for this kind of TV; the full remote lists Bluetooth and infrared.
            Text(
                stringResource(R.string.home_remote_other, device.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val vm: RemoteViewModel = viewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        val notFound by vm.showList.collectAsStateWithLifecycle()
        val code by vm.codeRequest.collectAsStateWithLifecycle()
        // Still connected from last time the sheet was open: show the keys right away.
        var wanted by rememberSaveable(targetId) { mutableStateOf(vm.state.value.target?.id == targetId) }
        val target = state.target?.takeIf { it.id == targetId }

        if (wanted) {
            // Looks for the TV only while the main screen is visible; shared with the full remote ([RemoteDiscovery]).
            LifecycleStartEffect(targetId) {
                val discovery = RemoteDiscovery.acquire(context)
                onStopOrDispose { discovery.close() }
            }
            DisposableEffect(targetId) {
                vm.setShowList(false)
                vm.start(targetId, force = true)
                onDispose {}
            }
        }

        when {
            !wanted -> FilledTonalButton(onClick = { wanted = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.SettingsRemote, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_remote_use))
            }
            target != null -> CompactRemote(state, target, press = { vm.press(it) }, onRetry = { vm.retry() })
            notFound -> Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(stringResource(R.string.remote_not_found, device.name), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    vm.setShowList(false)
                    vm.start(targetId, force = true)
                }) { Text(stringResource(R.string.home_remote_retry)) }
            }
            else -> Row(
                Modifier.padding(vertical = 12.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.remote_connecting, device.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        code?.let { request -> RemoteCodeDialog(request, onAnswer = { vm.answerCode(it) }) }
    }
}
