package io.github.patschgoo.castapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.opensubtitles.OpenSubtitlesAccount
import io.github.patschgoo.castapp.settings.SpeechEngineKind
import io.github.patschgoo.castapp.settings.SubtitleSourceSettings
import io.github.patschgoo.castapp.subtitles.VoskModels
import io.github.patschgoo.castapp.subtitles.WhisperModels

private enum class SourcesDialog { ENGINE, MODEL, LANGUAGE, ACCOUNT }

/** Languages Whisper can be told to listen for (it knows more; "detect" covers the rest). */
private val WHISPER_LANGUAGES: List<Pair<String, String>> = VoskModels.ALL.map { it.code to it.label } + listOf(
    "ar" to "العربية", "cs" to "Čeština", "da" to "Dansk", "el" to "Ελληνικά", "fi" to "Suomi", "he" to "עברית",
    "hu" to "Magyar", "id" to "Bahasa Indonesia", "ko" to "한국어", "no" to "Norsk", "ro" to "Română", "sv" to "Svenska",
    "th" to "ไทย", "uk" to "Українська", "vi" to "Tiếng Việt",
)

private fun modelText(id: String): Int = when (id) {
    "tiny" -> R.string.subsrc_model_tiny
    "small" -> R.string.subsrc_model_small
    else -> R.string.subsrc_model_base
}

/**
 * The speech-engine and online-subtitle part of Settings → Subtitles. Self-contained: it keeps
 * its own settings ([SubtitleSourceSettings], [OpenSubtitlesAccount]) and dialogs.
 */
@Composable
fun SubtitleSourcesSection() {
    val context = LocalContext.current
    val store = remember { SubtitleSourceSettings.get(context) }
    val account = remember { OpenSubtitlesAccount.get(context) }
    val s by store.state.collectAsStateWithLifecycle()
    val hasKey by account.hasKey.collectAsStateWithLifecycle()
    val installed by WhisperModels.installed.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SourcesDialog?>(null) }
    LaunchedEffect(Unit) { WhisperModels.refresh(context) }
    val whisper = s.engine == SpeechEngineKind.WHISPER

    Column {
        ListItem(
            onClick = { dialog = SourcesDialog.ENGINE },
            supportingContent = {
                Text(stringResource(if (whisper) R.string.subsrc_engine_whisper else R.string.subsrc_engine_vosk))
            },
        ) { Text(stringResource(R.string.subsrc_engine)) }
        if (whisper) {
            val model = WhisperModels.byId(s.whisperModel)
            ListItem(
                onClick = { dialog = SourcesDialog.MODEL },
                supportingContent = {
                    Text(
                        when {
                            model == null -> stringResource(R.string.subsrc_whisper_model_none)
                            model.id in installed -> stringResource(R.string.subsrc_whisper_model_ready, model.label)
                            else -> stringResource(R.string.subsrc_whisper_model_missing, model.label, model.sizeMb)
                        },
                    )
                },
            ) { Text(stringResource(R.string.subsrc_whisper_model)) }
            ListItem(
                onClick = { dialog = SourcesDialog.LANGUAGE },
                supportingContent = {
                    Text(WHISPER_LANGUAGES.firstOrNull { it.first == s.whisperLanguage }?.second ?: stringResource(R.string.subsrc_detect_language))
                },
            ) { Text(stringResource(R.string.subsrc_whisper_language)) }
        }
        ListItem(
            onClick = { if (whisper) store.update { it.copy(translateToEnglish = !it.translateToEnglish) } },
            supportingContent = {
                Text(stringResource(if (whisper) R.string.subsrc_translate_on else R.string.subsrc_translate_needs_whisper))
            },
            trailingContent = {
                Switch(
                    checked = whisper && s.translateToEnglish,
                    enabled = whisper,
                    onCheckedChange = { c -> store.update { it.copy(translateToEnglish = c) } },
                )
            },
        ) { Text(stringResource(R.string.subsrc_translate)) }

        Text(
            stringResource(R.string.subsrc_online_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        )
        ListItem(
            onClick = { dialog = SourcesDialog.ACCOUNT },
            supportingContent = {
                Text(
                    when {
                        !hasKey -> stringResource(R.string.subsrc_account_off)
                        else -> {
                            val user = remember(hasKey, dialog) { account.credentials().username }
                            if (user.isNotBlank()) stringResource(R.string.subsrc_account_on_user, user)
                            else stringResource(R.string.subsrc_account_on)
                        }
                    },
                )
            },
        ) { Text(stringResource(R.string.subsrc_account)) }
    }

    when (dialog) {
        SourcesDialog.ENGINE -> EngineDialog(
            selected = s.engine,
            onSelect = { e -> store.update { it.copy(engine = e) } },
            onDismiss = { dialog = null },
        )
        SourcesDialog.MODEL -> WhisperModelDialog(
            selected = s.whisperModel,
            onSelect = { id -> store.update { it.copy(whisperModel = id) } },
            onDismiss = { dialog = null },
        )
        SourcesDialog.LANGUAGE -> WhisperLanguageDialog(
            selected = s.whisperLanguage,
            onSelect = { code -> store.update { it.copy(whisperLanguage = code) } },
            onDismiss = { dialog = null },
        )
        SourcesDialog.ACCOUNT -> AccountDialog(
            account = account,
            languages = s.searchLanguages,
            // Saved in the order typed from now on (older versions sorted them).
            onSaveLanguages = { l -> store.update { it.copy(searchLanguages = l, searchLanguagesOrdered = true) } },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun RadioRow(selected: Boolean, title: String, text: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (text != null) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EngineDialog(selected: SpeechEngineKind, onSelect: (SpeechEngineKind) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subsrc_engine)) },
        text = {
            Column(Modifier.selectableGroup()) {
                RadioRow(selected == SpeechEngineKind.VOSK, stringResource(R.string.subsrc_engine_vosk), stringResource(R.string.subsrc_engine_vosk_text)) {
                    onSelect(SpeechEngineKind.VOSK)
                    onDismiss()
                }
                RadioRow(selected == SpeechEngineKind.WHISPER, stringResource(R.string.subsrc_engine_whisper), stringResource(R.string.subsrc_engine_whisper_text)) {
                    onSelect(SpeechEngineKind.WHISPER)
                    onDismiss()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Whisper models: download (once), pick, delete. */
@Composable
private fun WhisperModelDialog(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val installed by WhisperModels.installed.collectAsStateWithLifecycle()
    val downloads by WhisperModels.downloads.collectAsStateWithLifecycle()
    val error by WhisperModels.error.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subsrc_whisper_model)) },
        text = {
            LazyColumn {
                item {
                    Text(
                        stringResource(R.string.subsrc_whisper_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                items(WhisperModels.ALL.size) { i ->
                    val model = WhisperModels.ALL[i]
                    val progress = downloads[model.id]
                    val ready = model.id in installed
                    ListItem(
                        onClick = {
                            onSelect(model.id)
                            if (!ready) WhisperModels.startDownload(context, model)
                        },
                        leadingContent = { RadioButton(selected = model.id == selected, onClick = null) },
                        supportingContent = {
                            Text(
                                when {
                                    progress != null -> stringResource(R.string.subsrc_model_downloading, (progress * 100).toInt())
                                    ready -> stringResource(R.string.subsrc_model_downloaded)
                                    else -> stringResource(R.string.subsrc_model_download, model.sizeMb)
                                },
                            )
                        },
                        trailingContent = {
                            when {
                                progress != null -> CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                                ready -> IconButton(onClick = { WhisperModels.delete(context, model) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.subsrc_delete_model, model.label))
                                }
                                else -> Icon(Icons.Rounded.Download, contentDescription = null)
                            }
                        },
                    ) { Text(stringResource(modelText(model.id))) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.subsrc_done)) } },
    )
}

@Composable
private fun WhisperLanguageDialog(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val options = listOf("" to stringResource(R.string.subsrc_detect_language)) + WHISPER_LANGUAGES
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subsrc_whisper_language)) },
        text = {
            LazyColumn(Modifier.selectableGroup()) {
                items(options.size) { i ->
                    val (code, label) = options[i]
                    RadioRow(code == selected, label, null) {
                        onSelect(code)
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AccountDialog(account: OpenSubtitlesAccount, languages: String, onSaveLanguages: (String) -> Unit, onDismiss: () -> Unit) {
    val saved = remember { account.credentials() }
    var key by remember { mutableStateOf(saved.apiKey) }
    var user by remember { mutableStateOf(saved.username) }
    var password by remember { mutableStateOf(saved.password) }
    var langs by remember { mutableStateOf(languages) }
    val phoneLanguage = LocalConfiguration.current.locales[0].language.ifEmpty { "en" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subsrc_account)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.subsrc_account_text), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim() },
                    singleLine = true,
                    label = { Text(stringResource(R.string.subsrc_api_key)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.subsrc_username)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.subsrc_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = langs,
                    onValueChange = { langs = it.filter { c -> c.isLetter() || c == ',' || c == ' ' || c == '-' }.take(40) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.subsrc_search_languages)) },
                    placeholder = { Text(stringResource(R.string.subsrc_search_languages_default, phoneLanguage)) },
                    supportingText = { Text(stringResource(R.string.subsrc_search_languages_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                account.save(OpenSubtitlesAccount.Credentials(key, user, password))
                // Kept in the order typed: the first is the preferred one for subtitle files next to a video
                // (the online search sorts them itself).
                onSaveLanguages(
                    langs.split(',', ' ', ';').map { it.trim().lowercase(java.util.Locale.ROOT).replace('_', '-') }
                        .filter { it.isNotEmpty() }.distinct().joinToString(", "),
                )
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (saved.apiKey.isNotEmpty()) {
                    TextButton(onClick = {
                        account.clear()
                        onDismiss()
                    }) { Text(stringResource(R.string.subsrc_remove)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
