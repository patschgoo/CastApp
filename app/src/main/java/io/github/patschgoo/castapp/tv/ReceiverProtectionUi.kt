package io.github.patschgoo.castapp.tv

import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.patschgoo.castapp.ui.focusRing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.R

private fun title(mode: ProtectionMode) = when (mode) {
    ProtectionMode.ANYONE -> R.string.protect_mode_anyone
    ProtectionMode.ASK -> R.string.protect_mode_ask
    ProtectionMode.PAIRED -> R.string.protect_mode_paired
}

private fun description(mode: ProtectionMode) = when (mode) {
    ProtectionMode.ANYONE -> R.string.protect_mode_anyone_hint
    ProtectionMode.ASK -> R.string.protect_mode_ask_hint
    ProtectionMode.PAIRED -> R.string.protect_mode_paired_hint
}

/** Phone settings row: who may cast to this phone while it receives. Touch. */
@Composable
fun ReceiverProtectionItem() {
    val context = LocalContext.current
    TvReceiver.init(context)
    val guard = TvReceiver.guard
    val data by guard.data.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val mode = data.mode ?: guard.mode
    ListItem(
        onClick = { open = true },
        supportingContent = { Text(stringResource(title(mode))) },
    ) { Text(stringResource(R.string.protect_title)) }
    if (open) ReceiverProtectionDialog { open = false }
}

/** TV home card: the current protection and a button (reachable with the D-pad) that opens the settings. */
@Composable
fun ReceiverProtectionCard() {
    val context = LocalContext.current
    TvReceiver.init(context)
    val guard = TvReceiver.guard
    val data by guard.data.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val mode = data.mode ?: guard.mode
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(28.dp)) {
            Text(stringResource(R.string.protect_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(title(mode)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            if (mode == ProtectionMode.PAIRED) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.protect_code_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(guard.code, style = MaterialTheme.typography.displaySmall)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { open = true }, modifier = Modifier.focusRing()) { Text(stringResource(R.string.protect_change)) }
        }
    }
    if (open) ReceiverProtectionDialog { open = false }
}

/** The choice of who may cast, the pairing code, and the list of senders to forget. Touch and D-pad. */
@Composable
fun ReceiverProtectionDialog(onDismiss: () -> Unit) {
    val guard = TvReceiver.guard
    val data by guard.data.collectAsStateWithLifecycle()
    val mode = data.mode ?: guard.mode
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.protect_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (m in ProtectionMode.entries) {
                    Row(
                        Modifier.fillMaxWidth().focusRing(RoundedCornerShape(8.dp)).selectable(selected = mode == m, role = Role.RadioButton, onClick = { guard.setMode(m) }).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = mode == m, onClick = null)
                        Column {
                            Text(stringResource(title(m)), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(description(m)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (mode == ProtectionMode.PAIRED) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.protect_code_label), style = MaterialTheme.typography.labelLarge)
                            Text(data.code ?: guard.code, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        OutlinedButton(onClick = { guard.newCode() }, modifier = Modifier.focusRing()) { Text(stringResource(R.string.protect_new_code)) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.protect_known_title), style = MaterialTheme.typography.titleSmall)
                val none = data.allowed.isEmpty() && data.blocked.isEmpty() && data.paired.isEmpty()
                if (none) Text(stringResource(R.string.protect_known_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (p in data.paired) {
                    KnownRow(p.name, stringResource(R.string.protect_kind_paired)) { guard.forgetPaired(p.hash) }
                }
                for (a in data.allowed) {
                    KnownRow(a.name, stringResource(R.string.protect_kind_allowed, a.address)) { guard.forgetAllowed(a.key) }
                }
                for (b in data.blocked) {
                    KnownRow(b.name, stringResource(R.string.protect_kind_blocked, b.address)) { guard.forgetBlocked(b.key) }
                }
                if (!none) {
                    TextButton(onClick = { guard.forgetAll() }, modifier = Modifier.focusRing()) { Text(stringResource(R.string.protect_forget_all)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRing()) { Text(stringResource(R.string.protect_done)) } },
    )
}

@Composable
private fun KnownRow(name: String, detail: String, onForget: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onForget, modifier = Modifier.focusRing()) { Text(stringResource(R.string.protect_forget)) }
    }
}
