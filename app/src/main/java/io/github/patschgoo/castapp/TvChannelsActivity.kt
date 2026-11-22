package io.github.patschgoo.castapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.iptv.Channel
import io.github.patschgoo.castapp.iptv.IptvRepository
import io.github.patschgoo.castapp.service.CastController
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastService
import io.github.patschgoo.castapp.service.CastState
import io.github.patschgoo.castapp.service.MediaType
import io.github.patschgoo.castapp.ui.CastLauncherSheet
import io.github.patschgoo.castapp.ui.RemoteImage
import io.github.patschgoo.castapp.ui.rememberCastLauncher
import io.github.patschgoo.castapp.ui.theme.CastAppTheme
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * IPTV: TV channel lists (M3U / M3U8 playlists from a provider, a public list or a
 * file). Pick a channel and it plays on the TV; Next/Previous on the phone or the
 * lock screen switch channels.
 */
class TvChannelsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = CastApp.get(this)
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            CastAppTheme(settings.theme) {
                ChannelsScreen(app.iptv, onBack = { finish() })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        CastApp.get(this).devices.startDiscovery()
    }

    override fun onStop() {
        CastApp.get(this).devices.stopDiscovery()
        super.onStop()
    }

    companion object {
        const val FAVORITES = "favorites"
        /** More channels than this are cut around the picked one (the TV list is the play list). */
        const val MAX_QUEUE = 1500
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelsScreen(repo: IptvRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val launcher = rememberCastLauncher()
    val playlists by repo.playlists.collectAsStateWithLifecycle()
    val favorites by repo.favorites.collectAsStateWithLifecycle()
    val castState by CastController.state.collectAsStateWithLifecycle()

    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = playlists.firstOrNull { it.id == selectedId }
    val showingFavorites = selectedId == TvChannelsActivity.FAVORITES
    LaunchedEffect(playlists) {
        if (selectedId == null || (selected == null && !showingFavorites)) selectedId = playlists.firstOrNull()?.id
    }

    var group by rememberSaveable(selectedId) { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var refreshNext by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(selectedId, reload, if (showingFavorites) favorites else null) {
        loading = true
        val refresh = refreshNext
        refreshNext = false
        channels = try {
            when {
                showingFavorites -> playlists.flatMap { p -> runCatching { repo.channels(p) }.getOrDefault(emptyList()) }
                    .filter { it.url in favorites }.distinctBy { it.url }
                selected != null -> repo.channels(selected, refresh)
                else -> emptyList()
            }
        } catch (e: Exception) {
            snackbar.showSnackbar(e.message ?: "Couldn't load the channels")
            emptyList()
        }
        loading = false
    }

    val groups = remember(channels) { channels.mapNotNull { it.group }.distinct() }
    val shown = remember(channels, group, query) {
        val q = query.trim()
        channels.filter { c ->
            (group == null || c.group == group) && (q.isEmpty() || c.name.contains(q, ignoreCase = true))
        }
    }

    fun play(index: Int) {
        // The shown list becomes the play list, so Next/Previous zap through it.
        val from = (index - TvChannelsActivity.MAX_QUEUE / 2).coerceIn(0, (shown.size - TvChannelsActivity.MAX_QUEUE).coerceAtLeast(0))
        val window = shown.subList(from, minOf(shown.size, from + TvChannelsActivity.MAX_QUEUE))
        launcher.cast(IptvRepository.toItems(window), index - from)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TV channels") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) { Icon(Icons.Rounded.Add, contentDescription = "Add a channel list") }
                    if (selected != null) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Update channel list") },
                                    leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        refreshNext = true
                                        reload++
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Remove \"${selected.name}\"") },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        repo.remove(selected.id)
                                        selectedId = null
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            val live = (castState as? CastState.Active)?.takeIf { it.kind == CastKind.FILE }
            if (live != null) NowPlayingBar(live)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (playlists.isEmpty()) {
                EmptyChannels(onAdd = { showAdd = true })
                return@Column
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(playlists, key = { it.id }) { p ->
                    FilterChip(selected = p.id == selectedId, onClick = { selectedId = p.id }, label = { Text(p.name, maxLines = 1) })
                }
                item(key = "fav") {
                    FilterChip(
                        selected = showingFavorites,
                        onClick = { selectedId = TvChannelsActivity.FAVORITES },
                        label = { Text("Favourites") },
                        leadingIcon = { Icon(Icons.Rounded.Star, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search channels") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, contentDescription = "Clear") }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (groups.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "all") { FilterChip(selected = group == null, onClick = { group = null }, label = { Text("All") }) }
                    items(groups, key = { "g:$it" }) { g ->
                        FilterChip(selected = g == group, onClick = { group = if (g == group) null else g }, label = { Text(g, maxLines = 1) })
                    }
                }
            }
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                shown.isEmpty() -> Text(
                    when {
                        showingFavorites && query.isEmpty() -> "No favourites yet. Tap the star next to a channel."
                        else -> "No channels found"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
                else -> {
                    val playingTitle = (castState as? CastState.Active)?.takeIf { it.kind == CastKind.FILE && it.live }?.title
                    Text(
                        "${shown.size} channels",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 8.dp),
                    )
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.weight(1f)) {
                        itemsIndexed(shown, key = { i, c -> "$i:${c.url}" }) { i, c ->
                            ChannelRow(
                                channel = c,
                                playing = c.name == playingTitle,
                                favorite = c.url in favorites,
                                onPlay = { play(i) },
                                onFavorite = { repo.toggleFavorite(c.url) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddPlaylistDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, source ->
                val p = repo.add(name, source)
                try {
                    val found = repo.channels(p, refresh = true)
                    selectedId = p.id
                    reload++
                    scope.launch { snackbar.showSnackbar("${found.size} channels added") }
                    null
                } catch (e: Exception) {
                    repo.remove(p.id)
                    e.message ?: "Couldn't read this channel list"
                }
            },
        )
    }
    CastLauncherSheet(launcher)
}

@Composable
private fun ChannelRow(channel: Channel, playing: Boolean, favorite: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit) {
    val radio = IptvRepository.toItems(listOf(channel)).first().type == MediaType.AUDIO
    ListItem(
        onClick = onPlay,
        leadingContent = {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                RemoteImage(channel.logo, Modifier.size(40.dp), sizePx = 96) {
                    Icon(if (radio) Icons.Rounded.Radio else Icons.Rounded.LiveTv, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        supportingContent = {
            val details = listOfNotNull(
                if (playing) "On the TV now" else null,
                channel.group,
                if (radio && channel.group?.contains("radio", ignoreCase = true) != true) "Radio" else null,
            )
            if (details.isNotEmpty()) Text(details.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            IconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = if (favorite) "Remove from favourites" else "Add to favourites",
                    tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        Text(
            channel.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** What's playing, with channel up/down and stop. */
@Composable
fun NowPlayingBar(state: CastState.Active) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(state.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${state.phase.label} · ${state.device.name}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.queueSize > 1) {
                IconButton(onClick = { CastService.send(context, CastService.ACTION_PREVIOUS) }, enabled = state.queueIndex > 0) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
                }
                IconButton(onClick = { CastService.send(context, CastService.ACTION_NEXT) }, enabled = state.queueIndex + 1 < state.queueSize) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                }
            }
            IconButton(onClick = { CastService.stop(context) }) { Icon(Icons.Rounded.Stop, contentDescription = "Stop") }
        }
    }
}

@Composable
private fun EmptyChannels(onAdd: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Icon(Icons.Rounded.LiveTv, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(12.dp))
            Text("Watch TV channels", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Add a channel list (an M3U or M3U8 playlist): the link from your IPTV provider, your TV provider's " +
                    "or router's channel list, a public list, or a file on the phone. Radio stations work too.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAdd) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add a channel list")
            }
        }
    }
}

/** Link, file or provider log-in (Xtream Codes). [onAdd] returns an error text, or null when it worked. */
@Composable
private fun AddPlaylistDialog(onDismiss: () -> Unit, onAdd: suspend (name: String, source: String) -> String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    val passwordState = rememberTextFieldState()
    val password = passwordState.text.toString()
    var file by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            file = uri.toString()
            if (name.isBlank()) name = io.github.patschgoo.castapp.service.MediaItem.displayName(context.contentResolver, uri)
                ?.substringBeforeLast('.').orEmpty()
        }
    }

    val source = when (kind) {
        0 -> link.trim().let { if (it.isNotEmpty() && !it.contains("://")) "http://$it" else it }
        1 -> file.orEmpty()
        else -> if (server.isBlank() || user.isBlank()) "" else {
            val base = server.trim().trimEnd('/').let { if (it.contains("://")) it else "http://$it" }
            "$base/get.php?username=${URLEncoder.encode(user.trim(), "UTF-8")}&password=${URLEncoder.encode(password, "UTF-8")}" +
                "&type=m3u_plus&output=ts"
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Add a channel list") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Link", "File", "Log-in").forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = kind == i,
                            onClick = { kind = i; error = null },
                            shape = SegmentedButtonDefaults.itemShape(i, 3),
                        ) { Text(label) }
                    }
                }
                when (kind) {
                    0 -> OutlinedTextField(
                        value = link,
                        onValueChange = { link = it; error = null },
                        label = { Text("Playlist address (M3U)") },
                        placeholder = { Text("https://…/playlist.m3u") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    1 -> OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (file == null) "Choose an .m3u file" else "File chosen ✓", maxLines = 1)
                    }
                    else -> {
                        Text("For providers that give you a server, user name and password (Xtream Codes).", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = server, onValueChange = { server = it; error = null },
                            label = { Text("Server") }, placeholder = { Text("http://example.tv:8080") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = user, onValueChange = { user = it; error = null },
                            label = { Text("User name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedSecureTextField(
                            state = passwordState,
                            label = { Text("Password") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && source.isNotEmpty(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val problem = onAdd(name.trim().ifEmpty { if (kind == 2) server.trim().substringAfter("://").substringBefore(':') else "" }, source)
                        busy = false
                        if (problem == null) onDismiss() else error = problem
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
