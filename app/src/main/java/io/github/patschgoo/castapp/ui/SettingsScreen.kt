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
import io.github.patschgoo.castapp.settings.ConvertMode
import io.github.patschgoo.castapp.settings.PictureMode
import io.github.patschgoo.castapp.settings.StreamMode
import io.github.patschgoo.castapp.settings.ThemeMode

private enum class SettingDialog { RESOLUTION, FPS, BITRATE, MODE, PORT, THEME, PICTURE, CONVERT, SLIDESHOW, RECEIVER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    audioPermissionGranted: Boolean,
    onRequestAudioPermission: () -> Unit,
    onBack: () -> Unit,
    onRequestOverlayPermission: () -> Unit = {},
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
            item {
                ListItem(
                    onClick = { settings.update { it.copy(adaptiveQuality = !it.adaptiveQuality) } },
                    supportingContent = { Text("Lowers the bitrate when the Wi-Fi can't keep up, raises it again when it can") },
                    trailingContent = {
                        Switch(checked = s.adaptiveQuality, onCheckedChange = { c -> settings.update { it.copy(adaptiveQuality = c) } })
                    },
                ) { Text("Automatic quality") }
            }
            item {
                SettingItem("Picture shape", s.picture.label + " — " + s.picture.description) { dialog = SettingDialog.PICTURE }
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
            item { AudioDelayItem(s.audioDelayMs) { d -> settings.update { it.copy(audioDelayMs = d) } } }

            item { SectionTitle("Videos, music and photos") }
            item { SettingItem("Convert videos the TV can't play", s.convert.label + " — " + s.convert.description) { dialog = SettingDialog.CONVERT } }
            item { SettingItem("Slideshow", "Next photo after ${s.slideshowSeconds} seconds") { dialog = SettingDialog.SLIDESHOW } }

            item { SectionTitle("Connection") }
            item { SettingItem("Stream mode", s.streamMode.label + " — " + s.streamMode.description) { dialog = SettingDialog.MODE } }
            item { SettingItem("Stream port", "${s.port} (only change if something else uses it)") { dialog = SettingDialog.PORT } }
            item {
                SettingItem(
                    "Low-latency TV player (advanced)",
                    if (s.receiverAppId.isBlank()) "Off — uses Google's standard player. Needs a one-time setup, see the README."
                    else "App ID ${s.receiverAppId} — about 0.2 s delay on Chromecast / Google TV / Shield",
                ) { dialog = SettingDialog.RECEIVER }
            }

            item { SectionTitle("Appearance") }
            item {
                val summary = when (s.theme) {
                    ThemeMode.SYSTEM -> "Follows your phone's light/dark setting"
                    else -> s.theme.label
                } + if (Build.VERSION.SDK_INT >= 31) " · colors from your wallpaper" else ""
                SettingItem("Theme", summary) { dialog = SettingDialog.THEME }
            }

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
        SettingDialog.THEME -> ChoiceDialog(
            title = "Theme",
            options = ThemeMode.entries.map { it to it.label },
            selected = s.theme,
            onSelect = { t -> settings.update { it.copy(theme = t) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.PICTURE -> ChoiceDialog(
            title = "Picture shape",
            options = PictureMode.entries.map { it to it.label },
            descriptions = PictureMode.entries.associateWith { it.description },
            selected = s.picture,
            onSelect = { m ->
                settings.update { it.copy(picture = m) }
                if (m == PictureMode.LANDSCAPE) onRequestOverlayPermission()
            },
            onDismiss = { dialog = null },
        )
        SettingDialog.CONVERT -> ChoiceDialog(
            title = "Convert videos the TV can't play",
            options = ConvertMode.entries.map { it to it.label },
            descriptions = ConvertMode.entries.associateWith { it.description },
            selected = s.convert,
            onSelect = { m -> settings.update { it.copy(convert = m) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.SLIDESHOW -> ChoiceDialog(
            title = "Slideshow",
            options = listOf(3, 6, 10, 20, 60).map { it to "$it seconds per photo" },
            selected = s.slideshowSeconds,
            onSelect = { v -> settings.update { it.copy(slideshowSeconds = v) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.RECEIVER -> ReceiverDialog(
            appId = s.receiverAppId,
            onSave = { id -> settings.update { it.copy(receiverAppId = id) } },
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

@Composable
private fun AudioDelayItem(delayMs: Int, onChange: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Sound sync", style = MaterialTheme.typography.bodyLarge)
        Text(
            when {
                delayMs == 0 -> "In sync (0 ms). If lips and sound don't match on your TV, adjust here while casting."
                delayMs > 0 -> "Sound $delayMs ms later"
                else -> "Sound ${-delayMs} ms earlier"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChange((delayMs - 50).coerceAtLeast(-500)) }) { Text("− 50 ms") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onChange(0) }, enabled = delayMs != 0) { Text("Reset") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onChange((delayMs + 50).coerceAtMost(500)) }) { Text("+ 50 ms") }
        }
    }
}

@Composable
private fun ReceiverDialog(appId: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(appId) }
    val valid = text.isBlank() || Regex("[0-9A-Fa-f]{8}").matches(text.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Low-latency TV player") },
        text = {
            Column {
                Text(
                    "Google's standard player buffers about a second. CastApp's own TV player (in the receiver folder of the " +
                        "project) plays with about 0.2 s delay, but Google requires it to be registered once: host it (e.g. GitHub " +
                        "Pages), register it at the Google Cast SDK Developer Console (one-time US\$5) and enter its App ID here. " +
                        "Leave empty to use the standard player.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isLetterOrDigit).take(8).uppercase() },
                    singleLine = true,
                    isError = !valid,
                    label = { Text("App ID (8 characters)") },
                    supportingText = { Text("Falls back to the standard player if it doesn't start") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()); onDismiss() }, enabled = valid) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
