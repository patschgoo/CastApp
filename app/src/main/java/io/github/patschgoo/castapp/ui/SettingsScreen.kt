package io.github.patschgoo.castapp.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.BuildConfig
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.settings.AppSettings
import io.github.patschgoo.castapp.settings.Resolution
import io.github.patschgoo.castapp.settings.StreamMode

private enum class SettingDialog { RESOLUTION, FPS, BITRATE, MODE, PORT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    audioPermissionGranted: Boolean,
    onRequestAudioPermission: () -> Unit,
    onBack: () -> Unit,
) {
    val s by settings.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingDialog?>(null) }
    val audioSupported = Build.VERSION.SDK_INT >= 29

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item { SectionTitle("Picture") }
            item {
                SettingItem("Quality", s.resolution.label) { dialog = SettingDialog.RESOLUTION }
            }
            item {
                SettingItem("Frame rate", "${s.fps} fps" + if (s.fps > 30) " (smoother, needs good Wi-Fi)" else "") { dialog = SettingDialog.FPS }
            }
            item {
                val auto = "Automatic (${s.effectiveBitrate / 1_000_000.0} Mbit/s)".replace(".0 ", " ")
                SettingItem("Bitrate", if (s.bitrateMbps == 0) auto else "${s.bitrateMbps} Mbit/s") { dialog = SettingDialog.BITRATE }
            }

            item { SectionTitle("Sound") }
            item {
                val summary = when {
                    !audioSupported -> "Needs Android 10 or newer"
                    s.audio && !audioPermissionGranted -> "Tap to allow audio capture"
                    s.audio -> "Sound plays on the TV. Some apps (e.g. many streaming apps) block this."
                    else -> "Only the picture is cast"
                }
                ListItem(
                    onClick = {
                        if (s.audio && !audioPermissionGranted) onRequestAudioPermission()
                        else settings.update { it.copy(audio = !it.audio) }
                    },
                    enabled = audioSupported,
                    supportingContent = { Text(summary) },
                    trailingContent = {
                        Switch(
                            checked = s.audio && audioSupported,
                            enabled = audioSupported,
                            onCheckedChange = { checked ->
                                settings.update { it.copy(audio = checked) }
                                if (checked && !audioPermissionGranted) onRequestAudioPermission()
                            },
                        )
                    },
                ) { Text("Cast audio") }
            }

            item { SectionTitle("Connection") }
            item { SettingItem("Stream mode", s.streamMode.label + " — " + s.streamMode.description) { dialog = SettingDialog.MODE } }
            item { SettingItem("Stream port", "${s.port} (only change if something else uses it)") { dialog = SettingDialog.PORT } }

            item { SectionTitle("About") }
            item {
                ListItem(
                    supportingContent = {
                        Text("No ads, no tracking, no account. Talks to your TV directly over your Wi-Fi — Google Play Services are not needed.")
                    },
                ) { Text("CastApp ${BuildConfig.VERSION_NAME}") }
            }
            item {
                ListItem(
                    onClick = { settings.reset() },
                    supportingContent = { Text("Back to 720p, 30 fps, automatic bitrate") },
                ) { Text("Reset settings") }
            }
        }
    }

    when (dialog) {
        SettingDialog.RESOLUTION -> ChoiceDialog(
            title = "Quality",
            options = Resolution.entries.map { it to it.label },
            selected = s.resolution,
            onSelect = { r -> settings.update { it.copy(resolution = r) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.FPS -> ChoiceDialog(
            title = "Frame rate",
            options = listOf(30 to "30 fps (recommended)", 60 to "60 fps"),
            selected = s.fps,
            onSelect = { f -> settings.update { it.copy(fps = f) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.BITRATE -> ChoiceDialog(
            title = "Bitrate",
            options = listOf(0 to "Automatic") + listOf(2, 4, 6, 8, 12, 16).map { it to "$it Mbit/s" },
            selected = s.bitrateMbps,
            onSelect = { b -> settings.update { it.copy(bitrateMbps = b) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.MODE -> ChoiceDialog(
            title = "Stream mode",
            options = StreamMode.entries.map { it to it.label },
            descriptions = StreamMode.entries.associateWith { it.description },
            selected = s.streamMode,
            onSelect = { m -> settings.update { it.copy(streamMode = m) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.PORT -> PortDialog(
            port = s.port,
            onSave = { p -> settings.update { it.copy(port = p) } },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingItem(title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        supportingContent = { Text(summary) },
    ) { Text(title) }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    descriptions: Map<T, String> = emptyMap(),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                for ((value, label) in options) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = value == selected, role = Role.RadioButton) {
                                onSelect(value)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            descriptions[value]?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PortDialog(port: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(port.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in 1024..65535
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stream port") },
        text = {
            Column {
                Text("The TV fetches the stream from your phone on this port.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(5) },
                    singleLine = true,
                    isError = !valid,
                    supportingText = { Text("1024 – 65535") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (valid) { onSave(value); onDismiss() } }, enabled = valid) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
