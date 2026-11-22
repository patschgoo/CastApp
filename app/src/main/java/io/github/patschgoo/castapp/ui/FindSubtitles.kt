package io.github.patschgoo.castapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.opensubtitles.OpenSubtitlesAccount
import io.github.patschgoo.castapp.opensubtitles.OpenSubtitlesClient
import io.github.patschgoo.castapp.opensubtitles.SubtitleFinder
import io.github.patschgoo.castapp.service.CastController
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastState
import io.github.patschgoo.castapp.service.MediaCastSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** Whether the "Find subtitles" chip belongs on the casting card (a file casts and a key is set). */
@Composable
fun canFindSubtitles(state: CastState.Active): Boolean {
    val context = LocalContext.current
    val hasKey by remember { OpenSubtitlesAccount.get(context).hasKey }.collectAsStateWithLifecycle()
    return hasKey && state.kind == CastKind.FILE && !state.isPhoto && !state.live && !state.audioOnly
}

/** The "Find subtitles" chip of the casting card; opens the search on OpenSubtitles.com. */
@Composable
fun FindSubtitlesChip() {
    var open by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { open = true },
        label = { Text(stringResource(R.string.subsrc_find)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
    if (open) FindSubtitlesDialog(onDismiss = { open = false })
}

private sealed interface FindState {
    data object Searching : FindState
    data class Results(val found: SubtitleFinder.Found) : FindState
    data class Downloading(val found: SubtitleFinder.Found) : FindState
    data class Failed(val message: String, val found: SubtitleFinder.Found? = null) : FindState
}

@Composable
private fun FindSubtitlesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val finder = remember { SubtitleFinder(context) }
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<FindState>(FindState.Searching) }
    val notVideo = stringResource(R.string.subsrc_not_video)
    val emptyFile = stringResource(R.string.subsrc_empty_file)
    val using = stringResource(R.string.subsrc_using)
    val resources = LocalResources.current

    LaunchedEffect(attempt) {
        state = FindState.Searching
        val session = CastController.activeCast as? MediaCastSession
        val file = session?.subtitleSearchFile()
        state = if (file == null) {
            FindState.Failed(notVideo)
        } else {
            try {
                FindState.Results(finder.search(file.first, file.second))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FindState.Failed(e.message ?: OpenSubtitlesClient.OFFLINE_MESSAGE)
            }
        }
    }

    fun download(found: SubtitleFinder.Found, result: OpenSubtitlesClient.Result) {
        state = FindState.Downloading(found)
        scope.launch {
            try {
                val downloaded = finder.download(result)
                val session = CastController.activeCast as? MediaCastSession
                if (session == null) {
                    state = FindState.Failed(notVideo, found)
                } else if (!session.useSubtitles(downloaded.text)) {
                    state = FindState.Failed(emptyFile, found)
                } else {
                    CastController.message(downloaded.remaining?.let { resources.getQuantityString(R.plurals.subsrc_using_remaining, it, it) } ?: using)
                    onDismiss()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = FindState.Failed(e.message ?: OpenSubtitlesClient.OFFLINE_MESSAGE, found)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subsrc_find)) },
        text = {
            when (val s = state) {
                FindState.Searching -> Busy(stringResource(R.string.subsrc_searching))
                is FindState.Downloading -> Busy(stringResource(R.string.subsrc_downloading))
                is FindState.Failed -> Column {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    s.found?.let { found ->
                        Spacer(Modifier.height(8.dp))
                        ResultList(found) { download(found, it) }
                    }
                }
                is FindState.Results -> ResultList(s.found) { download(s.found, it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.subsrc_close)) } },
        dismissButton = {
            if (state is FindState.Failed && (state as FindState.Failed).found == null) {
                TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.subsrc_search_again)) }
            }
        },
    )
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
        Spacer(Modifier.width(16.dp))
        Text(text)
    }
}

@Composable
private fun ResultList(found: SubtitleFinder.Found, onPick: (OpenSubtitlesClient.Result) -> Unit) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Column {
        Text(
            stringResource(R.string.subsrc_searched_for, found.query.text, found.languages.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (found.byHash) {
            Text(
                stringResource(R.string.subsrc_searched_hash),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.heightIn(max = 420.dp)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                items(found.results.size) { i ->
                    val r = found.results[i]
                    val details = buildList {
                        add(r.language.uppercase())
                        add(stringResource(R.string.subsrc_downloads, numbers.format(r.downloads)))
                        if (r.hearingImpaired) add(stringResource(R.string.subsrc_hearing_impaired))
                        if (r.machineTranslated) add(stringResource(R.string.subsrc_machine_translated))
                    }.joinToString(" · ")
                    ListItem(
                        onClick = { onPick(r) },
                        modifier = Modifier.fillMaxWidth(),
                        supportingContent = {
                            Column {
                                Text(details)
                                if (r.hashMatch) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Rounded.Verified,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.subsrc_hash_match), color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        },
                    ) { Text(r.label, maxLines = 2) }
                }
            }
        }
    }
}
