package io.github.patschgoo.castapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.multi.JoinPolicy
import io.github.patschgoo.castapp.multi.MirrorFormats
import io.github.patschgoo.castapp.multi.PeerInfo
import io.github.patschgoo.castapp.multi.Peers
import io.github.patschgoo.castapp.multi.ScreenInfo
import io.github.patschgoo.castapp.multi.ScreenState
import io.github.patschgoo.castapp.service.CastController
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The casting card's part for several screens: every screen with its own status and volume,
 * "Add a screen", "Hand over…", who is watching and who may join.
 */
@Composable
fun MultiScreenSection(state: CastState.Active) {
    val context = LocalContext.current
    val peers by Peers.peers.collectAsStateWithLifecycle()
    val policy by Peers.policyFlow.collectAsStateWithLifecycle()
    val watchersFlow = remember(Peers.isReady) { if (Peers.isReady) Peers.hub.watchers else MutableStateFlow(0) }
    val watchers by watchersFlow.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var handing by remember { mutableStateOf(false) }
    val handhelds = peers.filter { it.isHandheld }

    Column(Modifier.padding(top = 10.dp)) {
        if (state.handingOver) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.multi_handing_over), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(6.dp))
        }
        if (state.screens.isNotEmpty()) {
            Text(stringResource(R.string.multi_screens_header, state.screens.size), style = MaterialTheme.typography.labelLarge)
            state.screens.forEach { ScreenRow(it) }
            if (state.kind == CastKind.FILE && state.screens.any { !it.primary && !it.synced }) {
                Text(stringResource(R.string.multi_best_effort), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            } else if (state.kind == CastKind.FILE && state.screens.any { it.synced }) {
                Text(stringResource(R.string.multi_synced_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!state.handingOver) {
                AssistChip(
                    onClick = { adding = true },
                    label = { Text(stringResource(R.string.multi_add_screen)) },
                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (handhelds.isNotEmpty() && !state.handingOver) {
                AssistChip(
                    onClick = { handing = true },
                    label = { Text(stringResource(R.string.multi_hand_over_menu)) },
                    leadingIcon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (watchers > 0) {
                AssistChip(
                    onClick = {},
                    label = { Text(pluralStringResource(R.plurals.multi_watching, watchers, watchers)) },
                    leadingIcon = { Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (peers.isNotEmpty() || watchers > 0) JoinPolicyChip(policy) { Peers.setPolicy(context, it) }
        }
    }

    if (adding) {
        AddScreenDialog(state, onDismiss = { adding = false }) { device ->
            adding = false
            CastController.activeCast?.addScreen(device)
        }
    }
    if (handing) {
        PeerPickerDialog(
            title = stringResource(R.string.multi_hand_over_title),
            text = stringResource(R.string.multi_hand_over_text),
            peers = handhelds,
            onDismiss = { handing = false },
        ) { peer ->
            handing = false
            Peers.offerHandOver(context, peer)
        }
    }
}

@Composable
private fun ScreenRow(screen: ScreenInfo) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                buildString {
                    append(screen.name)
                    screen.kindLabel?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildList {
                    add(stringResource(screenStateText(screen.state)))
                    screen.volume?.let { add(stringResource(R.string.multi_volume, (it * 100).toInt())) }
                    if (screen.muted) add(stringResource(R.string.multi_muted))
                    if (screen.synced) add(stringResource(R.string.multi_in_sync))
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { CastController.activeCast?.changeScreenVolume(screen.id, -VOLUME_STEP) }) {
            Icon(Icons.AutoMirrored.Rounded.VolumeDown, contentDescription = stringResource(R.string.multi_volume_down, screen.name))
        }
        IconButton(onClick = { CastController.activeCast?.changeScreenVolume(screen.id, VOLUME_STEP) }) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = stringResource(R.string.multi_volume_up, screen.name))
        }
        IconButton(onClick = { CastController.activeCast?.toggleScreenMute(screen.id) }) {
            Icon(
                Icons.AutoMirrored.Rounded.VolumeOff,
                contentDescription = stringResource(R.string.multi_mute, screen.name),
                tint = if (screen.muted) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
            )
        }
        IconButton(onClick = { CastController.activeCast?.removeScreen(screen.id) }) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.multi_remove_screen, screen.name))
        }
    }
}

private fun screenStateText(state: ScreenState): Int = when (state) {
    ScreenState.CONNECTING -> R.string.multi_state_connecting
    ScreenState.BUFFERING -> R.string.multi_state_buffering
    ScreenState.PLAYING -> R.string.multi_state_playing
    ScreenState.PAUSED -> R.string.multi_state_paused
    ScreenState.ERROR -> R.string.multi_state_error
}

@Composable
private fun JoinPolicyChip(policy: JoinPolicy, onChange: (JoinPolicy) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(stringResource(R.string.multi_policy_chip, stringResource(policyText(policy)))) },
            leadingIcon = { Icon(Icons.Rounded.Group, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (p in JoinPolicy.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(policyText(p))) },
                    leadingIcon = if (p == policy) {
                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        open = false
                        onChange(p)
                    },
                )
            }
        }
    }
}

private fun policyText(p: JoinPolicy) = when (p) {
    JoinPolicy.ASK -> R.string.multi_policy_ask
    JoinPolicy.ALLOW -> R.string.multi_policy_allow
    JoinPolicy.OFF -> R.string.multi_policy_off
}

/** Every TV and speaker that isn't part of the cast yet, any kind (with or without CastApp). */
@Composable
private fun AddScreenDialog(state: CastState.Active, onDismiss: () -> Unit, onPick: (CastDevice) -> Unit) {
    val context = LocalContext.current
    val all by CastApp.get(context).devices.devices.collectAsStateWithLifecycle()
    val inCast = (CastController.activeCast?.screenDevices ?: listOf(state.device))
    val soundOnly = state.audioOnly
    val candidates = all.map { it.device }
        .filter { d -> inCast.none { MirrorFormats.sameDevice(it, d) } }
        .filter { d -> d.video || soundOnly || state.kind == CastKind.FILE }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.multi_add_screen_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(if (state.kind == CastKind.FILE) R.string.multi_add_screen_file_text else R.string.multi_add_screen_text),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (candidates.isEmpty()) {
                    Text(stringResource(R.string.multi_add_screen_none), style = MaterialTheme.typography.bodyMedium)
                }
                candidates.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onPick(d) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Cast, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            d.kindLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.multi_cancel)) } },
    )
}

@Composable
private fun PeerPickerDialog(title: String, text: String, peers: List<PeerInfo>, onDismiss: () -> Unit, onPick: (PeerInfo) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                peers.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onPick(p) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.multi_cancel)) } },
    )
}

/**
 * The main screen's cards for other CastApp devices: "<name> is casting <title> · Join",
 * "Ask to cast", and the controls of a cast this device took over the controls of.
 */
@Composable
fun PeerCards(localCasting: Boolean) {
    val context = LocalContext.current
    val peers by Peers.peers.collectAsStateWithLifecycle()
    val activity by Peers.activity.collectAsStateWithLifecycle()
    val remote by Peers.remote.collectAsStateWithLifecycle()
    val casting = peers.filter { it.casting }
    if (casting.isEmpty() && remote == null && activity == null) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        remote?.let { RemoteCard(it) }
        activity?.let { a ->
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (a) {
                        is Peers.Activity.Joining -> stringResource(R.string.multi_joining, a.peer)
                        is Peers.Activity.Asking -> stringResource(R.string.multi_asking, a.peer)
                        is Peers.Activity.Offering -> stringResource(R.string.multi_offering, a.peer)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        casting.filter { p -> remote?.base != p.base }.forEach { peer ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.multi_peer_casting, peer.name, peer.title.ifBlank { stringResource(R.string.multi_title_screen) }),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    val details = buildList {
                        if (peer.screens.isNotEmpty()) add(stringResource(R.string.multi_peer_on, peer.screens.joinToString(", ")))
                        if (peer.watchers > 0) add(pluralStringResource(R.plurals.multi_watching, peer.watchers, peer.watchers))
                    }
                    if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (peer.joinable) {
                            FilledTonalButton(onClick = { Peers.join(context, peer) }, enabled = activity == null) {
                                Text(stringResource(R.string.multi_join))
                            }
                        }
                        if (!localCasting) {
                            OutlinedButton(onClick = { Peers.askToCast(context, peer) }, enabled = activity == null) {
                                Text(stringResource(R.string.multi_ask_to_cast))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Controls of another device's cast (it keeps serving files only it can read). */
@Composable
private fun RemoteCard(remote: Peers.RemoteSession) {
    val status = remote.status
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.multi_remote_title, status?.title?.ifBlank { null } ?: remote.name),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.multi_remote_text, remote.name, status?.screens?.joinToString(", ").orEmpty()),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Peers.control("previous") }) { Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.action_previous)) }
                IconButton(onClick = { Peers.control("rewind") }) { Icon(Icons.Rounded.Replay10, contentDescription = stringResource(R.string.action_rewind)) }
                IconButton(onClick = { Peers.control("toggle") }) {
                    val paused = status?.paused == true
                    Icon(
                        if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = stringResource(if (paused) R.string.action_play else R.string.action_pause),
                    )
                }
                IconButton(onClick = { Peers.control("forward") }) { Icon(Icons.Rounded.Forward30, contentDescription = stringResource(R.string.action_forward)) }
                IconButton(onClick = { Peers.control("next") }) { Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.action_next)) }
                IconButton(onClick = { Peers.control("stop") }) { Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.action_stop)) }
            }
            TextButton(onClick = { Peers.releaseRemote() }) { Text(stringResource(R.string.multi_remote_release)) }
        }
    }
}

private const val VOLUME_STEP = 0.05
