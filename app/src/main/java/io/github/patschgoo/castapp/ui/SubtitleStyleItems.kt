package io.github.patschgoo.castapp.ui

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
import io.github.patschgoo.castapp.settings.SubtitleStyleSettings
import io.github.patschgoo.castapp.subtitles.SubtitleBackground
import io.github.patschgoo.castapp.subtitles.SubtitleColor
import io.github.patschgoo.castapp.subtitles.SubtitleSize

/** Settings → Subtitles: how they look on the TV (size, colour, background). */
@Composable
fun SubtitleStyleItems() {
    val context = LocalContext.current
    val store = remember { SubtitleStyleSettings.get(context) }
    val style by store.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Int?>(null) }

    val sizeLabels = mapOf(
        SubtitleSize.SMALL to stringResource(R.string.style_size_small),
        SubtitleSize.NORMAL to stringResource(R.string.style_size_normal),
        SubtitleSize.LARGE to stringResource(R.string.style_size_large),
        SubtitleSize.HUGE to stringResource(R.string.style_size_huge),
    )
    val colorLabels = mapOf(
        SubtitleColor.WHITE to stringResource(R.string.style_color_white),
        SubtitleColor.YELLOW to stringResource(R.string.style_color_yellow),
        SubtitleColor.CYAN to stringResource(R.string.style_color_cyan),
        SubtitleColor.GREEN to stringResource(R.string.style_color_green),
    )
    val backgroundLabels = mapOf(
        SubtitleBackground.NONE to stringResource(R.string.style_bg_none),
        SubtitleBackground.SHADOW to stringResource(R.string.style_bg_shadow),
        SubtitleBackground.BOX to stringResource(R.string.style_bg_box),
    )
    val summary = listOf(sizeLabels[style.size], colorLabels[style.color], backgroundLabels[style.background]).joinToString(" · ")

    ListItem(
        onClick = { open = 0 },
        supportingContent = { Text(summary + "\n" + stringResource(R.string.style_hint)) },
    ) { Text(stringResource(R.string.style_title)) }

    when (open) {
        0 -> StylePicker(stringResource(R.string.style_size), sizeLabels, style.size, onDismiss = { open = null }) { v ->
            store.update { it.copy(size = v) }
            open = 1
        }
        1 -> StylePicker(stringResource(R.string.style_color), colorLabels, style.color, onDismiss = { open = null }) { v ->
            store.update { it.copy(color = v) }
            open = 2
        }
        2 -> StylePicker(stringResource(R.string.style_background), backgroundLabels, style.background, onDismiss = { open = null }) { v ->
            store.update { it.copy(background = v) }
            open = null
        }
    }
}

@Composable
private fun <T> StylePicker(title: String, options: Map<T, String>, selected: T, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                for ((value, label) in options) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = value == selected, role = Role.RadioButton) { onSelect(value) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
