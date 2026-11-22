package io.github.patschgoo.castapp.iptv

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.patschgoo.castapp.R

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(12)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Asks for the PIN. [onSubmit] says whether it was right (then the caller closes the dialog). */
@Composable
internal fun PinDialog(rules: ParentalRules, title: String, onUnlocked: () -> Unit, onDismiss: () -> Unit) {
    var pin by rememberSaveable { mutableStateOf("") }
    var wrong by rememberSaveable { mutableStateOf(false) }
    var wait by rememberSaveable { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField(pin, { pin = it; wrong = false }, stringResource(R.string.iptvx_parental_pin))
                if (wait > 0) {
                    Text(stringResource(R.string.iptvx_parental_wait, wait), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else if (wrong) {
                    Text(stringResource(R.string.iptvx_parental_wrong), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pin.length >= ParentalRules.MIN_PIN,
                onClick = {
                    if (rules.unlock(pin)) {
                        onUnlocked()
                    } else {
                        wrong = true
                        wait = rules.waitSeconds()
                        pin = ""
                    }
                },
            ) { Text(stringResource(R.string.iptvx_parental_unlock)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.iptvg_cancel)) } },
    )
}

/**
 * Settings of the parental lock: the PIN, and for each group of the open list whether it is
 * open, locked (asks for the PIN before it plays) or hidden. Single channels are set with a long press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ParentalSheet(rules: ParentalRules, snapshot: ParentalSnapshot, groups: List<String>, now: Long, onDismiss: () -> Unit) {
    var pin by rememberSaveable { mutableStateOf("") }
    var pin2 by rememberSaveable { mutableStateOf("") }
    var oldPin by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<Int?>(null) }
    var changing by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf(false) }
    val unlocked = snapshot.unlocked(now)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.iptvx_parental_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.iptvx_parental_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                !snapshot.hasPin -> {
                    PinField(pin, { pin = it; problem = null }, stringResource(R.string.iptvx_parental_new_pin))
                    PinField(pin2, { pin2 = it; problem = null }, stringResource(R.string.iptvx_parental_repeat_pin))
                    problem?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(
                        onClick = {
                            problem = when {
                                !ParentalRules.validPin(pin) -> R.string.iptvx_parental_pin_rule
                                pin != pin2 -> R.string.iptvx_parental_pin_mismatch
                                else -> {
                                    rules.setPin(pin)
                                    pin = ""
                                    pin2 = ""
                                    null
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.iptvx_parental_set_pin)) }
                }
                !unlocked -> {
                    PinField(pin, { pin = it; problem = null }, stringResource(R.string.iptvx_parental_pin))
                    problem?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(
                        enabled = pin.length >= ParentalRules.MIN_PIN,
                        onClick = {
                            problem = if (rules.unlock(pin)) null else R.string.iptvx_parental_wrong
                            pin = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.iptvx_parental_unlock)) }
                }
                else -> {
                    if (groups.isEmpty()) {
                        Text(stringResource(R.string.iptvx_parental_no_groups), style = MaterialTheme.typography.bodyMedium)
                    }
                    for (g in groups) {
                        val p = snapshot.groupProtection(g)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(onClickLabel = stringResource(R.string.polish_change), role = Role.Button) { rules.setGroup(g, p.next()) }
                                .semantics(mergeDescendants = true) {}
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(g, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            when (p) {
                                Protection.NONE -> Text(stringResource(R.string.iptvx_parental_off), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Protection.LOCKED -> {
                                    Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.iptvx_parental_locked), color = MaterialTheme.colorScheme.primary)
                                }
                                Protection.HIDDEN -> {
                                    Icon(Icons.Rounded.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.iptvx_parental_hidden), color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Text(stringResource(R.string.iptvx_parental_tap_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { rules.lock() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.iptvx_parental_lock_now)) }
                    OutlinedButton(onClick = { changing = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.iptvx_parental_change_pin)) }
                    OutlinedButton(onClick = { removing = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.iptvx_parental_remove_pin)) }
                }
            }
        }
    }
    if (changing) {
        AlertDialog(
            onDismissRequest = { changing = false },
            title = { Text(stringResource(R.string.iptvx_parental_change_pin)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PinField(oldPin, { oldPin = it; problem = null }, stringResource(R.string.iptvx_parental_pin))
                    PinField(pin, { pin = it; problem = null }, stringResource(R.string.iptvx_parental_new_pin))
                    problem?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!ParentalRules.validPin(pin)) {
                        problem = R.string.iptvx_parental_pin_rule
                    } else if (rules.changePin(oldPin, pin)) {
                        changing = false
                        problem = null
                    } else {
                        problem = R.string.iptvx_parental_wrong
                    }
                    oldPin = ""
                    pin = ""
                }) { Text(stringResource(R.string.iptvg_save)) }
            },
            dismissButton = { TextButton(onClick = { changing = false }) { Text(stringResource(R.string.iptvg_cancel)) } },
        )
    }
    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text(stringResource(R.string.iptvx_parental_remove_pin)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.iptvx_parental_remove_text), style = MaterialTheme.typography.bodySmall)
                    PinField(oldPin, { oldPin = it; problem = null }, stringResource(R.string.iptvx_parental_pin))
                    problem?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (rules.removePin(oldPin)) {
                        removing = false
                        problem = null
                    } else {
                        problem = R.string.iptvx_parental_wrong
                    }
                    oldPin = ""
                }) { Text(stringResource(R.string.iptvx_parental_remove_pin)) }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text(stringResource(R.string.iptvg_cancel)) } },
        )
    }
}
