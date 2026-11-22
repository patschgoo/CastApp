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
import io.github.patschgoo.castapp.subtitles.VoskModels
import io.github.patschgoo.castapp.support.supportSettingsSection
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalContext

private enum class SettingDialog { RESOLUTION, FPS, BITRATE, MODE, PORT, THEME, PICTURE, CONVERT, SLIDESHOW, RECEIVER, LANGUAGE }

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
            item { SectionTitle(stringResource(R.string.set_sec_picture)) }
            item {
                SettingItem(stringResource(R.string.set_quality), s.resolution.label) { dialog = SettingDialog.RESOLUTION }
            }
            item {
                SettingItem(stringResource(R.string.set_fps), "${s.fps} fps" + if (s.fps > 30) stringResource(R.string.set_fps_smoother) else "") { dialog = SettingDialog.FPS }
            }
            item {
                val mbit = s.effectiveBitrate / 1_000_000.0
                val auto = stringResource(R.string.set_bitrate_auto_value, if (mbit % 1.0 == 0.0) mbit.toInt().toString() else String.format(androidx.compose.ui.platform.LocalConfiguration.current.locales[0], "%.1f", mbit))
                SettingItem(stringResource(R.string.set_bitrate), if (s.bitrateMbps == 0) auto else "${s.bitrateMbps} Mbit/s") { dialog = SettingDialog.BITRATE }
            }
            item { VideoCodecSettingItem(s, settings) }
            item {
                ListItem(
                    onClick = { settings.update { it.copy(adaptiveQuality = !it.adaptiveQuality) } },
                    supportingContent = { Text(stringResource(R.string.set_adaptive_summary)) },
                    trailingContent = {
                        Switch(checked = s.adaptiveQuality, onCheckedChange = { c -> settings.update { it.copy(adaptiveQuality = c) } })
                    },
                ) { Text(stringResource(R.string.set_adaptive_title)) }
            }
            item {
                SettingItem(stringResource(R.string.set_picture_shape), s.picture.label + " — " + s.picture.description) { dialog = SettingDialog.PICTURE }
            }

            item { SectionTitle(stringResource(R.string.set_sec_sound)) }
            item {
                val summary = when {
                    !audioSupported -> stringResource(R.string.set_audio_needs_10)
                    s.audio && !audioPermissionGranted -> stringResource(R.string.set_audio_tap_allow)
                    s.audio -> stringResource(R.string.set_audio_on)
                    else -> stringResource(R.string.set_audio_off)
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
                ) { Text(stringResource(R.string.set_audio_title)) }
            }
            item { MicrophoneSettingItems(s, settings) }
            item { AudioDelayItem(s.audioDelayMs) { d -> settings.update { it.copy(audioDelayMs = d) } } }

            item { SectionTitle(stringResource(R.string.set_sec_media)) }
            item { SettingItem(stringResource(R.string.set_convert), s.convert.label + " — " + s.convert.description) { dialog = SettingDialog.CONVERT } }
            item { SettingItem(stringResource(R.string.set_slideshow), stringResource(R.string.set_slideshow_value, s.slideshowSeconds)) { dialog = SettingDialog.SLIDESHOW } }

            item { SectionTitle(stringResource(R.string.set_sec_subtitles)) }
            item {
                val lang = VoskModels.byCode(s.subtitleLanguage)
                val installed by VoskModels.installed.collectAsStateWithLifecycle()
                SettingItem(
                    stringResource(R.string.set_spoken_language),
                    when {
                        lang == null -> stringResource(R.string.set_spoken_none)
                        lang.code in installed -> stringResource(R.string.set_spoken_ready, lang.label)
                        else -> stringResource(R.string.set_spoken_missing, lang.label)
                    },
                ) { dialog = SettingDialog.LANGUAGE }
            }
            item {
                ListItem(
                    onClick = { settings.update { it.copy(autoSubtitles = !it.autoSubtitles) } },
                    supportingContent = {
                        Text(stringResource(R.string.set_auto_subs_summary))
                    },
                    trailingContent = {
                        Switch(checked = s.autoSubtitles, onCheckedChange = { c -> settings.update { it.copy(autoSubtitles = c) } })
                    },
                ) { Text(stringResource(R.string.set_auto_subs_title)) }
            }
            item { SubtitleStyleItems() }
            item {
                val context = LocalContext.current
                SettingItem(
                    stringResource(R.string.set_live_caption),
                    stringResource(R.string.set_live_caption_text),
                ) {
                    try {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_CAPTIONING_SETTINGS))
                    } catch (_: Exception) {
                    }
                }
            }

            item { SubtitleSourcesSection() }

            item { SectionTitle(stringResource(R.string.set_sec_connection)) }
            item { ReceiveOnPhoneItem() }
            item { io.github.patschgoo.castapp.tv.ReceiverProtectionItem() }
            item { SettingItem(stringResource(R.string.set_stream_mode), s.streamMode.label + " — " + s.streamMode.description) { dialog = SettingDialog.MODE } }
            item { SettingItem(stringResource(R.string.set_stream_port), stringResource(R.string.set_stream_port_value, s.port)) { dialog = SettingDialog.PORT } }
            item {
                SettingItem(
                    stringResource(R.string.set_receiver_title),
                    if (s.receiverAppId.isBlank()) stringResource(R.string.set_receiver_off)
                    else stringResource(R.string.set_receiver_on, s.receiverAppId),
                ) { dialog = SettingDialog.RECEIVER }
            }

            item { SectionTitle(stringResource(R.string.set_sec_appearance)) }
            item {
                val summary = when (s.theme) {
                    ThemeMode.SYSTEM -> stringResource(R.string.set_theme_system)
                    else -> s.theme.label
                } + if (Build.VERSION.SDK_INT >= 31) stringResource(R.string.set_theme_wallpaper) else ""
                SettingItem(stringResource(R.string.set_theme), summary) { dialog = SettingDialog.THEME }
            }

            supportSettingsSection()

            item { SectionTitle(stringResource(R.string.set_sec_about)) }
            item {
                ListItem(
                    supportingContent = {
                        Text(stringResource(R.string.set_about_text))
                    },
                ) { Text(stringResource(R.string.set_about_version, BuildConfig.VERSION_NAME)) }
            }
            item {
                ListItem(
                    onClick = { settings.reset() },
                    supportingContent = { Text(stringResource(R.string.set_reset_summary)) },
                ) { Text(stringResource(R.string.set_reset)) }
            }
        }
    }

    when (dialog) {
        SettingDialog.RESOLUTION -> ChoiceDialog(
            title = stringResource(R.string.set_quality),
            options = Resolution.entries.map { it to it.label },
            selected = s.resolution,
            onSelect = { r -> settings.update { it.copy(resolution = r) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.FPS -> ChoiceDialog(
            title = stringResource(R.string.set_fps),
            options = listOf(30 to stringResource(R.string.set_fps_30), 60 to "60 fps"),
            selected = s.fps,
            onSelect = { f -> settings.update { it.copy(fps = f) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.BITRATE -> ChoiceDialog(
            title = stringResource(R.string.set_bitrate),
            options = listOf(0 to stringResource(R.string.set_bitrate_auto)) + listOf(2, 4, 6, 8, 12, 16).map { it to "$it Mbit/s" },
            selected = s.bitrateMbps,
            onSelect = { b -> settings.update { it.copy(bitrateMbps = b) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.MODE -> ChoiceDialog(
            title = stringResource(R.string.set_stream_mode),
            options = StreamMode.entries.map { it to it.label },
            descriptions = StreamMode.entries.associateWith { it.description },
            selected = s.streamMode,
            onSelect = { m -> settings.update { it.copy(streamMode = m) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.THEME -> ChoiceDialog(
            title = stringResource(R.string.set_theme),
            options = ThemeMode.entries.map { it to it.label },
            selected = s.theme,
            onSelect = { t -> settings.update { it.copy(theme = t) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.PICTURE -> ChoiceDialog(
            title = stringResource(R.string.set_picture_shape),
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
            title = stringResource(R.string.set_convert),
            options = ConvertMode.entries.map { it to it.label },
            descriptions = ConvertMode.entries.associateWith { it.description },
            selected = s.convert,
            onSelect = { m -> settings.update { it.copy(convert = m) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.SLIDESHOW -> ChoiceDialog(
            title = stringResource(R.string.set_slideshow),
            options = listOf(3, 6, 10, 20, 60).map { it to stringResource(R.string.set_slideshow_option, it) },
            selected = s.slideshowSeconds,
            onSelect = { v -> settings.update { it.copy(slideshowSeconds = v) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.RECEIVER -> ReceiverDialog(
            appId = s.receiverAppId,
            onSave = { id -> settings.update { it.copy(receiverAppId = id) } },
            onDismiss = { dialog = null },
        )
        SettingDialog.LANGUAGE -> LanguageDialog(
            selected = s.subtitleLanguage,
            onSelect = { code -> settings.update { it.copy(subtitleLanguage = code) } },
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
        title = { Text(stringResource(R.string.set_stream_port)) },
        text = {
            Column {
                Text(stringResource(R.string.set_port_text), style = MaterialTheme.typography.bodyMedium)
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
        Text(stringResource(R.string.set_sync_title), style = MaterialTheme.typography.bodyLarge)
        Text(
            when {
                delayMs == 0 -> stringResource(R.string.set_sync_ok)
                delayMs > 0 -> stringResource(R.string.set_sync_later, delayMs)
                else -> stringResource(R.string.set_sync_earlier, -delayMs)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChange((delayMs - 50).coerceAtLeast(-500)) }) { Text("− 50 ms") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onChange(0) }, enabled = delayMs != 0) { Text(stringResource(R.string.set_reset_short)) }
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
        title = { Text(stringResource(R.string.set_receiver_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.set_receiver_dialog_text),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isLetterOrDigit).take(8).uppercase() },
                    singleLine = true,
                    isError = !valid,
                    label = { Text(stringResource(R.string.set_receiver_id_label)) },
                    supportingText = { Text(stringResource(R.string.set_receiver_fallback)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()); onDismiss() }, enabled = valid) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Speech-recognition languages: download (once, ~40 MB), pick, delete. */
@Composable
private fun LanguageDialog(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val installed by VoskModels.installed.collectAsStateWithLifecycle()
    val downloads by VoskModels.downloads.collectAsStateWithLifecycle()
    val error by VoskModels.error.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_spoken_language)) },
        text = {
            LazyColumn {
                item {
                    Text(
                        stringResource(R.string.set_lang_text),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                items(VoskModels.ALL.size) { i ->
                    val lang = VoskModels.ALL[i]
                    val progress = downloads[lang.code]
                    val ready = lang.code in installed
                    ListItem(
                        onClick = {
                            onSelect(lang.code)
                            if (!ready) VoskModels.startDownload(context, lang)
                        },
                        leadingContent = { RadioButton(selected = lang.code == selected, onClick = null) },
                        supportingContent = {
                            Text(
                                when {
                                    progress != null -> stringResource(R.string.set_lang_downloading, (progress * 100).toInt())
                                    ready -> stringResource(R.string.set_lang_downloaded)
                                    else -> stringResource(R.string.set_lang_size, lang.sizeMb)
                                },
                            )
                        },
                        trailingContent = {
                            when {
                                progress != null -> CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                                ready -> IconButton(onClick = {
                                    VoskModels.delete(context, lang)
                                }) { Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.set_lang_delete, lang.label)) }
                                else -> Icon(Icons.Rounded.Download, contentDescription = null)
                            }
                        },
                    ) { Text(lang.label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.set_done)) } },
    )
}

/** "Receive on this phone": the phone becomes a CastApp TV receiver while this is on. */
@Composable
private fun ReceiveOnPhoneItem() {
    val context = LocalContext.current
    val on by io.github.patschgoo.castapp.tv.TvReceiverService.running.collectAsStateWithLifecycle()
    fun toggle(enable: Boolean) {
        if (enable) io.github.patschgoo.castapp.tv.TvReceiverService.start(context)
        else io.github.patschgoo.castapp.tv.TvReceiverService.stop(context)
    }
    ListItem(
        onClick = { toggle(!on) },
        supportingContent = {
            Text(
                if (on) stringResource(R.string.receive_summary_on, io.github.patschgoo.castapp.tv.TvReceiver.deviceName(context))
                else stringResource(R.string.receive_summary_off),
            )
        },
        trailingContent = { Switch(checked = on, onCheckedChange = { toggle(it) }) },
    ) { Text(stringResource(R.string.receive_title)) }
}
