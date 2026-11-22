package io.github.patschgoo.castapp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.capture.VideoCodecs
import io.github.patschgoo.castapp.settings.AppSettings
import io.github.patschgoo.castapp.settings.CastSettings
import io.github.patschgoo.castapp.settings.CodecPreference
import kotlin.math.roundToInt

/** Settings → Picture → "Video codec: Automatic / H.264 / HEVC". */
@Composable
fun VideoCodecSettingItem(s: CastSettings, settings: AppSettings) {
    var open by remember { mutableStateOf(false) }
    val phoneHevc = remember { VideoCodecs.canEncodeHevc() }
    val labels = mapOf(
        CodecPreference.AUTO to stringResource(R.string.codec_auto),
        CodecPreference.H264 to stringResource(R.string.codec_h264),
        CodecPreference.HEVC to stringResource(R.string.codec_hevc),
    )
    val descriptions = mapOf(
        CodecPreference.AUTO to stringResource(R.string.codec_auto_desc),
        CodecPreference.H264 to stringResource(R.string.codec_h264_desc),
        CodecPreference.HEVC to stringResource(R.string.codec_hevc_desc),
    )
    ListItem(
        onClick = { open = true },
        supportingContent = {
            Text(
                if (!phoneHevc) stringResource(R.string.codec_no_hevc_encoder)
                else labels.getValue(s.videoCodec) + " — " + descriptions.getValue(s.videoCodec),
            )
        },
    ) { Text(stringResource(R.string.codec_setting_title)) }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.codec_setting_title)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    for (option in CodecPreference.entries) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = option == s.videoCodec,
                                    enabled = phoneHevc || option != CodecPreference.HEVC,
                                    role = Role.RadioButton,
                                    onClick = {
                                        settings.update { it.copy(videoCodec = option) }
                                        open = false
                                    },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == s.videoCodec, onClick = null, enabled = phoneHevc || option != CodecPreference.HEVC)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(labels.getValue(option))
                                Text(
                                    descriptions.getValue(option),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** Settings → Sound → "Phone microphone" (with its level). Asks for the microphone permission when turned on. */
@Composable
fun MicrophoneSettingItems(s: CastSettings, settings: AppSettings) {
    val context = LocalContext.current
    fun granted() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var permission by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> permission = ok }
    val withApps = Build.VERSION.SDK_INT >= 29 && s.audio

    ListItem(
        onClick = {
            if (s.microphone && !permission) launcher.launch(Manifest.permission.RECORD_AUDIO)
            else {
                settings.update { it.copy(microphone = !it.microphone) }
                if (!s.microphone && !permission) launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        supportingContent = {
            Text(
                when {
                    !s.microphone -> stringResource(R.string.mic_setting_off)
                    !permission -> stringResource(R.string.mic_setting_permission)
                    withApps -> stringResource(R.string.mic_setting_on)
                    else -> stringResource(R.string.mic_setting_on_no_apps)
                },
            )
        },
        trailingContent = {
            Switch(
                checked = s.microphone,
                onCheckedChange = { on ->
                    settings.update { it.copy(microphone = on) }
                    if (on && !permission) launcher.launch(Manifest.permission.RECORD_AUDIO)
                },
            )
        },
    ) { Text(stringResource(R.string.mic_setting_title)) }

    if (s.microphone) {
        // Changes apply while mirroring (the level is read live).
        var level by remember(s.micLevel) { mutableFloatStateOf(s.micLevel.toFloat()) }
        ListItem(
            supportingContent = {
                Slider(
                    value = level,
                    onValueChange = { level = it },
                    onValueChangeFinished = { settings.update { it.copy(micLevel = level.roundToInt()) } },
                    valueRange = 0f..200f,
                    steps = 7,
                )
            },
            trailingContent = { Text(stringResource(R.string.mic_level_value, level.roundToInt())) },
        ) { Text(stringResource(R.string.mic_level_title)) }
    }
}
