package io.github.patschgoo.castapp.automation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.R

/** Settings: the switch "Let other apps control CastApp" and its secret. */
@Composable
fun AutomationSettingsItem() {
    val context = LocalContext.current
    val settings = remember { AutomationSettings.get(context) }
    val config by settings.config.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }

    Column {
        ListItem(
            onClick = { settings.setEnabled(!config.enabled) },
            supportingContent = {
                Text(
                    stringResource(
                        when {
                            !config.enabled -> R.string.automation_summary_off
                            config.secret.isEmpty() -> R.string.automation_summary_open
                            else -> R.string.automation_summary_secret
                        },
                    ),
                )
            },
            trailingContent = { Switch(checked = config.enabled, onCheckedChange = { settings.setEnabled(it) }) },
        ) { Text(stringResource(R.string.automation_title)) }

        if (config.enabled) {
            ListItem(
                onClick = { editing = true },
                supportingContent = {
                    Text(stringResource(if (config.secret.isEmpty()) R.string.automation_secret_not_set else R.string.automation_secret_is_set))
                },
            ) { Text(stringResource(R.string.automation_secret_title)) }
            Text(
                stringResource(R.string.automation_docs_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }

    if (editing) {
        SecretDialog(
            initial = config.secret,
            onGenerate = { settings.generateSecret() },
            onSave = { settings.setSecret(it) },
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun SecretDialog(initial: String, onGenerate: () -> String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.automation_secret_title)) },
        text = {
            Column {
                Text(stringResource(R.string.automation_secret_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> !c.isWhitespace() }.take(64) },
                    label = { Text(stringResource(R.string.automation_secret_field)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { text = onGenerate() }) { Text(stringResource(R.string.automation_secret_generate)) }
                    TextButton(onClick = { copy(context, text) }, enabled = text.isNotEmpty()) { Text(stringResource(R.string.automation_secret_copy)) }
                    TextButton(onClick = { text = "" }, enabled = text.isNotEmpty()) { Text(stringResource(R.string.automation_secret_remove)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text); onDismiss() }) { Text(stringResource(R.string.automation_secret_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun copy(context: Context, secret: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("CastApp secret", secret))
    Toast.makeText(context, R.string.automation_secret_copied, Toast.LENGTH_SHORT).show()
}
