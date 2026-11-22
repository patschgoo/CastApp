package io.github.patschgoo.castapp.mediaservers

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.NowPlayingBar
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.service.CastController
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastState
import io.github.patschgoo.castapp.service.ResumeRules
import io.github.patschgoo.castapp.service.WatchHistory
import io.github.patschgoo.castapp.ui.CastLauncher
import io.github.patschgoo.castapp.ui.RemoteImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- entries in "Network & cloud" ---------------------------------------------------------------------

/** The "Plex & Jellyfin" section of the Network & cloud screen. */
@Composable
fun MediaServersHomeEntries(onOpen: (ServerKind) -> Unit) {
    val context = LocalContext.current
    val servers = remember { MediaServers.get(context) }
    val accounts by servers.accounts.collectAsStateWithLifecycle()
    val plexUser by servers.plexUser.collectAsStateWithLifecycle()
    val jellyfin = accounts.filter { !it.kind.isPlex }
    Column {
        Text(
            stringResource(R.string.ms_section),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        ListItem(
            onClick = { onOpen(ServerKind.PLEX) },
            leadingContent = { IconBox(Icons.Rounded.VideoLibrary) },
            supportingContent = {
                Text(
                    if (plexUser == null) stringResource(R.string.ms_plex_signed_out)
                    else stringResource(R.string.ms_plex_signed_in, plexUser.orEmpty().ifEmpty { "plex.tv" }),
                )
            },
        ) { Text(stringResource(R.string.ms_plex)) }
        ListItem(
            onClick = { onOpen(ServerKind.JELLYFIN) },
            leadingContent = { IconBox(Icons.Rounded.Tv) },
            supportingContent = {
                Text(
                    if (jellyfin.isEmpty()) stringResource(R.string.ms_jellyfin_none)
                    else stringResource(R.string.ms_jellyfin_signed_in, jellyfin.joinToString { it.name }),
                )
            },
        ) { Text(stringResource(R.string.ms_jellyfin)) }
    }
}

@Composable
private fun IconBox(icon: ImageVector) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
}

// ---- playing -----------------------------------------------------------------------------------------------

private class ResumePrompt(val plan: PlaybackPlan, val title: String)

/** Builds the play list for an item, asks "Resume at ... / Start over" when needed, and casts it. */
@Stable
private class ServerPlayer(private val context: android.content.Context, private val scope: CoroutineScope, private val launcher: CastLauncher) {
    var busy by mutableStateOf(false)
    var prompt by mutableStateOf<ResumePrompt?>(null)

    fun play(client: MediaServerClient, item: ServerItem) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val plan = withContext(Dispatchers.IO) { client.plan(item) }
                val resumable = ResumeRules.resumeAt(plan.resumeMs, plan.entries.firstOrNull()?.durationMs ?: 0, false) != null
                if (resumable) prompt = ResumePrompt(plan, item.title) else start(plan, item.title, resume = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.ms_cant_play, errorMessage(context, e)), Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    fun start(plan: PlaybackPlan, title: String, resume: Boolean) {
        prompt = null
        MediaServers.syncHistory(WatchHistory.get(context), plan, resume)
        launcher.cast(plan.items, 0, title)
    }
}

@Composable
private fun rememberServerPlayer(launcher: CastLauncher): ServerPlayer {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(launcher) { ServerPlayer(context, scope, launcher) }
}

@Composable
private fun ServerPlayerDialogs(player: ServerPlayer) {
    val prompt = player.prompt
    if (prompt != null) {
        val at = formatPosition(prompt.plan.resumeMs)
        AlertDialog(
            onDismissRequest = { player.prompt = null },
            title = { Text(stringResource(R.string.ms_resume_title)) },
            text = { Text(stringResource(R.string.ms_resume_text, prompt.title, at)) },
            confirmButton = { TextButton(onClick = { player.start(prompt.plan, prompt.title, resume = true) }) { Text(stringResource(R.string.ms_resume_at, at)) } },
            dismissButton = { TextButton(onClick = { player.start(prompt.plan, prompt.title, resume = false) }) { Text(stringResource(R.string.ms_start_over)) } },
        )
    }
    if (player.busy) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.ms_starting))
                }
            },
        )
    }
}

/** 83 s = "1:23", 5000 s = "1:23:20". */
internal fun formatPosition(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total / 60) % 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun runtimeText(ms: Long): String? {
    if (ms <= 0) return null
    val minutes = (ms / 60_000).toInt().coerceAtLeast(1)
    return if (minutes >= 60) stringResource(R.string.ms_duration_h_min, minutes / 60, minutes % 60) else stringResource(R.string.ms_duration_min, minutes)
}

// ---- the screens ----------------------------------------------------------------------------------------------

private sealed interface Screen {
    data object Start : Screen
    data class Home(val account: ServerAccount) : Screen
    data class Browse(val account: ServerAccount, val parent: ServerItem) : Screen
}

/** Plex or Jellyfin (with Emby): sign-in, servers, libraries, browsing and playing on the TV. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaServersHost(kind: ServerKind, launcher: CastLauncher, onBack: () -> Unit) {
    val context = LocalContext.current
    val servers = remember { MediaServers.get(context) }
    val stack = remember { mutableStateListOf<Screen>(Screen.Start) }
    val castState by CastController.state.collectAsStateWithLifecycle()
    val player = rememberServerPlayer(launcher)
    var refresh by remember { mutableIntStateOf(0) }
    val plex = kind.isPlex

    BackHandler { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack() }

    val screen = stack.last()
    val title = when (screen) {
        Screen.Start -> stringResource(if (plex) R.string.ms_title_plex else R.string.ms_title_jellyfin)
        is Screen.Home -> screen.account.name
        is Screen.Browse -> screen.parent.title
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = {
            val playing = (castState as? CastState.Active)?.takeIf { it.kind == CastKind.FILE }
            if (playing != null) NowPlayingBar(playing)
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (screen) {
                Screen.Start -> StartScreen(servers, plex, onOpen = { stack.add(Screen.Home(it)) })
                is Screen.Home -> HomeScreen(
                    servers, screen.account, player, refresh,
                    onOpen = { item -> stack.add(Screen.Browse(screen.account, item)) },
                )
                is Screen.Browse -> BrowseScreen(
                    servers, screen.account, screen.parent, player, refresh,
                    onOpen = { item -> stack.add(Screen.Browse(screen.account, item)) },
                    onChanged = { refresh++ },
                )
            }
        }
    }
    ServerPlayerDialogs(player)
}

// ---- start: accounts, sign-in, discovery ----------------------------------------------------------------------------

@Composable
private fun StartScreen(servers: MediaServers, plex: Boolean, onOpen: (ServerAccount) -> Unit) {
    if (plex) PlexStart(servers, onOpen) else JellyfinStart(servers, onOpen)
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun JellyfinStart(servers: MediaServers, onOpen: (ServerAccount) -> Unit) {
    val context = LocalContext.current
    val accounts by servers.accounts.collectAsStateWithLifecycle()
    val mine = accounts.filter { !it.kind.isPlex }
    var signIn by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<FoundServer>?>(null) }

    LaunchedEffect(Unit) {
        found = withContext(Dispatchers.IO) { withMulticastLock(context) { LanDiscovery.findJellyfin() } }
    }
    val signedInIds = mine.map { it.serverId }.toSet()
    val newOnes = found.orEmpty().filter { it.id !in signedInIds }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(mine, key = { it.id }) { a -> AccountRow(servers, a, onOpen) }
        item { SectionTitle(stringResource(R.string.ms_found_wifi)) }
        if (found == null) {
            item { Searching(stringResource(R.string.ms_searching)) }
        }
        items(newOnes, key = { "found:" + it.address }) { s ->
            ListItem(
                onClick = { signIn = s.address },
                leadingContent = { IconBox(Icons.Rounded.Dns) },
                supportingContent = { Text(s.address, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            ) { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        item {
            ListItem(
                onClick = { signIn = "" },
                leadingContent = { IconBox(Icons.Rounded.Add) },
            ) { Text(stringResource(R.string.ms_add_server)) }
        }
    }
    signIn?.let { address ->
        JellyfinSignInDialog(servers, address, onDone = { signIn = null; onOpen(it) }, onDismiss = { signIn = null })
    }
}

@Composable
private fun Searching(text: String) {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AccountRow(servers: MediaServers, a: ServerAccount, onOpen: (ServerAccount) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        onClick = { onOpen(a) },
        leadingContent = { IconBox(if (a.kind.isPlex) Icons.Rounded.VideoLibrary else Icons.Rounded.Tv) },
        supportingContent = {
            Text(
                if (a.userName.isNotEmpty() && !a.kind.isPlex) "${a.userName} · ${a.baseUrl}" else a.baseUrl,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = if (a.kind.isPlex) null else ({
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.ms_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.ms_sign_out)) }, onClick = { menu = false; servers.remove(a.id) })
                }
            }
        }),
    ) { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun PlexStart(servers: MediaServers, onOpen: (ServerAccount) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user by servers.plexUser.collectAsStateWithLifecycle()
    val accounts by servers.accounts.collectAsStateWithLifecycle()
    val mine = accounts.filter { it.kind.isPlex }
    val signedIn = user != null
    var showSignIn by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var asked by remember { mutableStateOf(false) }

    fun refresh() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val gdm = withMulticastLock(context) { LanDiscovery.findPlex() }
                    servers.refreshPlexServers(gdm)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorMessage(context, e)
            } finally {
                busy = false
            }
        }
    }

    // Right after signing in there are no servers yet: ask plex.tv once.
    LaunchedEffect(signedIn) {
        if (signedIn && mine.isEmpty() && !asked) {
            asked = true
            refresh()
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        if (!signedIn) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.ms_plex_intro), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = { showSignIn = true }) { Text(stringResource(R.string.ms_plex_signin_button)) }
                    }
                }
            }
        } else {
            item { SectionTitle(stringResource(R.string.ms_servers)) }
            items(mine, key = { it.id }) { a -> AccountRow(servers, a, onOpen) }
            if (busy) item { Searching(stringResource(R.string.ms_plex_refreshing)) }
            else if (mine.isEmpty() && error == null) item {
                Text(
                    stringResource(R.string.ms_plex_no_servers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) } }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { refresh() }, enabled = !busy) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ms_plex_refresh))
                    }
                }
            }
            item {
                TextButton(onClick = { servers.signOutPlex(); asked = false }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.ms_sign_out) + (user?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""))
                }
            }
        }
    }
    if (showSignIn) PlexSignInDialog(servers, onDone = { showSignIn = false }, onDismiss = { showSignIn = false })
}

// ---- home of a server --------------------------------------------------------------------------------------------

private class HomeData(val libraries: List<ServerItem>, val resume: List<ServerItem>, val next: List<ServerItem>)

@Composable
private fun HomeScreen(
    servers: MediaServers,
    account: ServerAccount,
    player: ServerPlayer,
    refresh: Int,
    onOpen: (ServerItem) -> Unit,
) {
    var data by remember(account.id) { mutableStateOf<HomeData?>(null) }
    var error by remember(account.id) { mutableStateOf<Throwable?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val client by produceClient(servers, account)

    LaunchedEffect(client, reload, refresh) {
        val c = client ?: return@LaunchedEffect
        error = null
        try {
            data = withContext(Dispatchers.IO) {
                val libs = c.libraries()
                // Continue watching and Next up are nice to have: a hiccup there must not hide the libraries.
                val resume = try { c.continueWatching() } catch (_: ServerHttpException) { emptyList() }
                val next = try { c.nextUp() } catch (_: ServerHttpException) { emptyList() }
                HomeData(libs, resume, next.filter { n -> resume.none { it.id == n.id } })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }

    val d = data
    val failure = error
    when {
        failure != null -> ErrorBox(failure) { reload++ }
        d == null -> Loading()
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (d.resume.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.ms_continue)) }
                item { CardRow(d.resume) { item -> client?.let { player.play(it, item) } } }
            }
            if (d.next.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.ms_next_up)) }
                item { CardRow(d.next) { item -> client?.let { player.play(it, item) } } }
            }
            item { SectionTitle(stringResource(R.string.ms_libraries)) }
            if (d.libraries.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.ms_no_libraries),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(d.libraries, key = { "lib:" + it.id }) { lib ->
                ListItem(
                    onClick = { onOpen(lib) },
                    leadingContent = { IconBox(libraryIcon(lib)) },
                ) { Text(lib.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun produceClient(servers: MediaServers, account: ServerAccount) = androidx.compose.runtime.produceState<MediaServerClient?>(null, account.id) {
    // Plex: finds an address of the server that answers (the home network first).
    value = try {
        val connected = withContext(Dispatchers.IO) { servers.connect(servers.get(account.id) ?: account) }
        servers.client(connected)
    } catch (e: ServerHttpException) {
        servers.client(servers.get(account.id) ?: account) // the first request shows the error
    }
}

private fun libraryIcon(item: ServerItem): ImageVector = when (item.collectionType) {
    "movies", "movie" -> Icons.Rounded.Movie
    "tvshows", "show" -> Icons.Rounded.Tv
    "music", "artist" -> Icons.Rounded.LibraryMusic
    "photos", "homevideos", "photo" -> Icons.Rounded.Image
    else -> Icons.Rounded.Folder
}

@Composable
private fun CardRow(items: List<ServerItem>, onClick: (ServerItem) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.id }) { item ->
            PosterCard(item, Modifier.width(if (item.wide) 200.dp else 120.dp), onClick = { onClick(item) })
        }
    }
}

// ---- browsing ----------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseScreen(
    servers: MediaServers,
    account: ServerAccount,
    parent: ServerItem,
    player: ServerPlayer,
    refresh: Int,
    onOpen: (ServerItem) -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val markedWatched = stringResource(R.string.ms_marked_watched)
    val markedUnwatched = stringResource(R.string.ms_marked_unwatched)
    var items by remember(parent.id) { mutableStateOf<List<ServerItem>?>(null) }
    var error by remember(parent.id) { mutableStateOf<Throwable?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<ServerItem?>(null) }
    val client by produceClient(servers, account)

    LaunchedEffect(client, reload, refresh) {
        val c = client ?: return@LaunchedEffect
        error = null
        try {
            items = withContext(Dispatchers.IO) { c.children(parent) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }

    fun setWatched(item: ServerItem, watched: Boolean) {
        val c = client ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { c.setWatched(item, watched) }
                Toast.makeText(context, if (watched) markedWatched else markedUnwatched, Toast.LENGTH_SHORT).show()
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, errorMessage(context, e), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun open(item: ServerItem) {
        when {
            item.kind == ItemKind.EPISODE || item.kind == ItemKind.TRACK -> client?.let { player.play(it, item) }
            item.isPlayable -> sheet = item
            else -> onOpen(item)
        }
    }

    val list = items
    val failure = error
    when {
        failure != null -> ErrorBox(failure) { reload++ }
        list == null -> Loading()
        list.isEmpty() -> Text(stringResource(R.string.ms_empty), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        list.any { it.kind in POSTER_KINDS } -> LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(list, key = { it.kind.name + it.id }) { item -> PosterCard(item, Modifier, onClick = { open(item) }) }
        }
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            val playable = list.filter { it.isPlayable }
            if (playable.isNotEmpty()) {
                item {
                    TextButton(onClick = { client?.let { player.play(it, playable.first()) } }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.ms_play_all))
                    }
                }
            }
            items(list, key = { it.kind.name + it.id }) { item ->
                ItemRow(item, onClick = { open(item) }, onWatched = { setWatched(item, it) })
            }
        }
    }

    sheet?.let { item ->
        ItemSheet(
            item = item,
            onDismiss = { sheet = null },
            onPlay = { sheet = null; client?.let { player.play(it, item) } },
            onWatched = { sheet = null; setWatched(item, it) },
        )
    }
}

private val POSTER_KINDS = setOf(ItemKind.MOVIE, ItemKind.SERIES, ItemKind.SEASON, ItemKind.ALBUM, ItemKind.ARTIST, ItemKind.COLLECTION, ItemKind.LIBRARY, ItemKind.PHOTO)

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun ErrorBox(error: Throwable, onRetry: () -> Unit) {
    Column(Modifier.padding(24.dp)) {
        Text(errorText(error), color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRetry) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ms_try_again))
        }
    }
}

private fun placeholderIcon(item: ServerItem): ImageVector = when (item.kind) {
    ItemKind.TRACK, ItemKind.ALBUM, ItemKind.ARTIST -> Icons.Rounded.Audiotrack
    ItemKind.SERIES, ItemKind.SEASON, ItemKind.EPISODE -> Icons.Rounded.Tv
    ItemKind.PHOTO -> Icons.Rounded.Image
    ItemKind.FOLDER, ItemKind.COLLECTION, ItemKind.LIBRARY -> Icons.Rounded.Folder
    else -> Icons.Rounded.Movie
}

/** A poster (or a wide still) with title, a watched check mark and the progress bar. */
@Composable
private fun PosterCard(item: ServerItem, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(if (item.wide) 16f / 9f else 2f / 3f)
                .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            RemoteImage(item.posterUrl, Modifier.fillMaxSize(), sizePx = 400) {
                Icon(placeholderIcon(item), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            if (item.played) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.ms_watched), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(2.dp))
                }
            } else if ((item.unplayedCount ?: 0) > 0 && (item.kind == ItemKind.SERIES || item.kind == ItemKind.SEASON)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                ) {
                    Text(
                        stringResource(R.string.ms_unwatched_count, item.unplayedCount ?: 0),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (item.progress >= 0) {
                LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        (item.subtitle ?: item.episodeLabel)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** An episode, track or folder in a list. */
@Composable
private fun ItemRow(item: ServerItem, onClick: () -> Unit, onWatched: (Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val runtime = runtimeText(item.runtimeMs)
    ListItem(
        onClick = onClick,
        leadingContent = {
            Box(
                Modifier.width(if (item.wide) 112.dp else 56.dp).aspectRatio(if (item.wide) 16f / 9f else 1f)
                    .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                RemoteImage(item.posterUrl, Modifier.fillMaxSize(), sizePx = 256) {
                    Icon(placeholderIcon(item), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                if (item.progress >= 0) {
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                }
            }
        },
        supportingContent = {
            Column {
                val line = listOfNotNull(item.episodeLabel.takeIf { item.kind == ItemKind.EPISODE }, runtime).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, maxLines = 1)
                item.overview?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
            }
        },
        trailingContent = if (item.isPlayable) ({
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.played) Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.ms_watched), tint = MaterialTheme.colorScheme.primary)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.ms_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (item.played) R.string.ms_mark_unwatched else R.string.ms_mark_watched)) },
                            onClick = { menu = false; onWatched(!item.played) },
                        )
                    }
                }
            }
        }) else null,
    ) { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

/** Details of a movie (or other single video) with the play button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemSheet(item: ServerItem, onDismiss: () -> Unit, onPlay: () -> Unit, onWatched: (Boolean) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row {
                Box(
                    Modifier.width(100.dp).aspectRatio(if (item.wide) 16f / 9f else 2f / 3f)
                        .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    RemoteImage(item.posterUrl, Modifier.fillMaxSize(), sizePx = 400) {
                        Icon(placeholderIcon(item), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(item.year?.toString(), runtimeText(item.runtimeMs)).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.played) Text(stringResource(R.string.ms_watched), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (item.progress >= 0) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item.overview?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onPlay) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ms_play))
                }
                TextButton(onClick = { onWatched(!item.played) }) {
                    Text(stringResource(if (item.played) R.string.ms_mark_unwatched else R.string.ms_mark_watched))
                }
            }
        }
    }
}
