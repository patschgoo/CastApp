package io.github.patschgoo.castapp.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.ui.CastLauncher
import io.github.patschgoo.castapp.ui.RemoteImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A film or a series in the poster grid. [extension] is set for films only. */
private class VodItem(val id: String, val name: String, val cover: String?, val categoryId: String?, val extension: String?, val meta: String?)

private sealed interface VodState {
    data object Loading : VodState
    class Failed(val message: String) : VodState
    class Ready(val categories: List<XtreamCategory>, val items: List<VodItem>) : VodState
}

/** Movies or series of an Xtream provider: category chips, poster grid; [query] filters by title. */
@Composable
internal fun VodPane(
    client: XtreamClient,
    series: Boolean,
    query: String,
    reload: Int,
    launcher: CastLauncher,
    modifier: Modifier = Modifier,
) {
    var state by remember { mutableStateOf<VodState>(VodState.Loading) }
    var category by rememberSaveable(series) { mutableStateOf<String?>(null) }
    var openShow by remember { mutableStateOf<VodItem?>(null) }

    LaunchedEffect(client.account, series, reload) {
        state = VodState.Loading
        state = try {
            withContext(Dispatchers.IO) {
                val categories = try {
                    if (series) client.seriesCategories() else client.vodCategories()
                } catch (e: Exception) {
                    emptyList() // The list itself matters more than its categories.
                }
                val items = if (series) {
                    client.series().map { VodItem(it.seriesId, it.name, it.cover, it.categoryId, null, it.year) }
                } else {
                    client.movies().map { VodItem(it.streamId, it.name, it.cover, it.categoryId, it.extension, listOfNotNull(it.year, it.rating?.let { r -> "★ $r" }).joinToString(" · ").ifEmpty { null }) }
                }
                VodState.Ready(categories, items)
            }
        } catch (e: Exception) {
            VodState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    Column(modifier) {
        when (val s = state) {
            VodState.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is VodState.Failed -> Text(
                stringResource(R.string.iptvg_load_failed, s.message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(24.dp),
            )
            is VodState.Ready -> {
                val q = query.trim()
                val used = remember(s) { s.items.mapNotNull { it.categoryId }.toSet() }
                val categories = remember(s) { s.categories.filter { it.id in used } }
                val shown = remember(s, category, q) {
                    s.items.filter { (category == null || it.categoryId == category) && (q.isEmpty() || it.name.contains(q, ignoreCase = true)) }
                }
                if (categories.size > 1) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "all") {
                            FilterChip(selected = category == null, onClick = { category = null }, label = { Text(stringResource(R.string.iptvg_all)) })
                        }
                        items(categories, key = { "c:${it.id}" }) { c ->
                            FilterChip(
                                selected = category == c.id,
                                onClick = { category = if (category == c.id) null else c.id },
                                label = { Text(c.name, maxLines = 1) },
                            )
                        }
                    }
                }
                if (shown.isEmpty()) {
                    Text(
                        stringResource(if (series) R.string.iptvg_no_series else R.string.iptvg_no_movies),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    Text(
                        stringResource(R.string.iptvg_item_count, shown.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 8.dp),
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(104.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(shown, key = { it.id }) { item ->
                            Poster(item, series) {
                                if (series) {
                                    openShow = item
                                } else {
                                    val ext = item.extension ?: "mp4"
                                    launcher.cast(
                                        listOf(
                                            MediaItem(
                                                title = item.name,
                                                uri = client.account.movieUrl(item.id, ext),
                                                mimeType = containerMime(ext),
                                            ),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    openShow?.let { show ->
        SeriesSheet(client, show.id, show.name, show.cover, launcher, onDismiss = { openShow = null })
    }
}

@Composable
private fun Poster(item: VodItem, series: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            RemoteImage(item.cover, Modifier.fillMaxSize(), sizePx = 160) {
                Icon(if (series) Icons.Rounded.Tv else Icons.Rounded.Movie, contentDescription = stringResource(R.string.iptvg_poster, item.name), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            }
        }
        Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        item.meta?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The seasons and episodes of one series; a tap plays that episode, with the following ones queued after it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeriesSheet(
    client: XtreamClient,
    seriesId: String,
    name: String,
    cover: String?,
    launcher: CastLauncher,
    onDismiss: () -> Unit,
) {
    var info by remember { mutableStateOf<SeriesInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var season by rememberSaveable(seriesId) { mutableStateOf<Int?>(null) }

    LaunchedEffect(seriesId) {
        try {
            val loaded = withContext(Dispatchers.IO) { client.seriesInfo(seriesId) }
            info = loaded
            if (season == null) season = loaded.episodes.firstOrNull()?.season
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 48.dp, height = 72.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                RemoteImage(info?.cover ?: cover, Modifier.fillMaxSize(), sizePx = 160) {
                    Icon(Icons.Rounded.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                info?.plot?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val loaded = info
        when {
            error != null -> Text(
                stringResource(R.string.iptvg_load_failed, error.orEmpty()),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
            loaded == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            loaded.episodes.isEmpty() -> Text(stringResource(R.string.iptvg_no_episodes), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
            else -> {
                val seasons = remember(loaded) { loaded.episodes.map { it.season }.distinct() }
                if (seasons.size > 1) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons, key = { it }) { s ->
                            FilterChip(selected = s == season, onClick = { season = s }, label = { Text(stringResource(R.string.iptvg_season, s)) })
                        }
                    }
                }
                val episodes = remember(loaded, season) { loaded.episodes.filter { season == null || it.season == season } }
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.navigationBarsPadding()) {
                    items(episodes, key = { it.id }) { ep ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                // Everything from this episode on is the play list, so Next plays the following episode.
                                val all = loaded.episodes
                                val queue = all.map { e ->
                                    MediaItem(
                                        title = "$name · ${e.code} ${e.title}",
                                        uri = client.account.episodeUrl(e.id, e.extension),
                                        mimeType = containerMime(e.extension),
                                    )
                                }
                                onDismiss()
                                launcher.cast(queue, all.indexOf(ep).coerceAtLeast(0), title = name)
                            }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(ep.code, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(64.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ep.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val line = listOfNotNull(
                                    ep.durationSeconds.takeIf { it > 0 }?.let { stringResource(R.string.iptvg_minutes, it / 60) },
                                    ep.plot,
                                ).joinToString(" · ")
                                if (line.isNotEmpty()) {
                                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
