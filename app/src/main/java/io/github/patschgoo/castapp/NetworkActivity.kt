package io.github.patschgoo.castapp

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.patschgoo.castapp.ui.readableWidth

import androidx.compose.ui.res.stringResource
import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.R
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.discovery.NetworkScanner
import io.github.patschgoo.castapp.dlna.Dlna
import io.github.patschgoo.castapp.network.MediaServerInfo
import io.github.patschgoo.castapp.network.NetworkSource
import io.github.patschgoo.castapp.network.NetworkSources
import io.github.patschgoo.castapp.network.RemoteEntry
import io.github.patschgoo.castapp.network.SourceKind
import io.github.patschgoo.castapp.service.CastController
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastState
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.service.MediaType
import io.github.patschgoo.castapp.stream.Subtitles
import io.github.patschgoo.castapp.ui.CastLauncher
import io.github.patschgoo.castapp.ui.CastLauncherSheet
import io.github.patschgoo.castapp.ui.RemoteImage
import io.github.patschgoo.castapp.ui.rememberCastLauncher
import io.github.patschgoo.castapp.ui.theme.CastAppTheme
import kotlinx.coroutines.launch

/**
 * Plays from the network and the cloud: media servers found on the Wi-Fi (Plex,
 * Jellyfin, Emby, NAS boxes, Windows), Windows / Samba shares, WebDAV folders
 * (Nextcloud, ownCloud, most NAS) and cloud apps through the system file picker.
 */
class NetworkActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = CastApp.get(this)
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            CastAppTheme(settings.theme) {
                NetworkScreen(app.network, onBack = { finish() }, startServers = intent?.getStringExtra(EXTRA_SERVERS)?.takeIf { name -> io.github.patschgoo.castapp.mediaservers.ServerKind.entries.any { it.name == name } })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = CastApp.get(this)
        app.devices.startDiscovery()
        app.network.startServerDiscovery(NetworkScanner.localNetworks().map { it.iface }.distinct())
    }

    override fun onStop() {
        val app = CastApp.get(this)
        app.devices.stopDiscovery()
        app.network.stopServerDiscovery()
        super.onStop()
    }

    companion object {
        private const val EXTRA_SERVERS = "servers"

        /** Opens Plex or Jellyfin / Emby directly (the tiles on the main screen); Back leaves again. */
        fun serversIntent(context: android.content.Context, kind: io.github.patschgoo.castapp.mediaservers.ServerKind): Intent =
            Intent(context, NetworkActivity::class.java).putExtra(EXTRA_SERVERS, kind.name)
    }
}

/** A folder being looked at. */
private sealed interface Place {
    val title: String

    data class Share(val sourceId: String, val path: String, override val title: String) : Place
    data class Server(val server: MediaServerInfo, val objectId: String, override val title: String) : Place
}

/** One line of a folder listing, from a share or a media server. */
private class Row(
    val title: String,
    val isFolder: Boolean,
    val type: MediaType?,
    val details: String?,
    val art: String? = null,
    val open: Place? = null,
    /** Index in the folder's play list. */
    val playIndex: Int = -1,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NetworkScreen(network: NetworkSources, onBack: () -> Unit, startServers: String? = null) {
    val context = LocalContext.current
    val launcher = rememberCastLauncher()
    val stack = remember { mutableStateListOf<Place>() }
    var showAdd by remember { mutableStateOf(false) }
    val castState by CastController.state.collectAsStateWithLifecycle()

    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

    // Files on the phone or in a cloud app (Drive, OneDrive, Dropbox… show up in the system picker).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        for (uri in uris) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        val items = MediaItem.fromUris(context.contentResolver, uris)
        if (items.isEmpty()) Toast.makeText(context, R.string.share_unsupported, Toast.LENGTH_LONG).show()
        else launcher.cast(items, 0, if (items.size == 1) items[0].title else Txt.n(R.plurals.share_count, items.size, items.size))
    }

    val place = stack.lastOrNull()
    var folderItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    // Plex / Jellyfin / Emby: their own screens (sign-in, libraries, Continue watching, playing).
    var serverKind by rememberSaveable { mutableStateOf(startServers) }
    serverKind?.let { name ->
        io.github.patschgoo.castapp.mediaservers.MediaServersHost(
            io.github.patschgoo.castapp.mediaservers.ServerKind.valueOf(name), launcher,
            // Opened straight from the main screen's tile: Back goes back there, not to the network list.
            onBack = { if (startServers != null) onBack() else serverKind = null },
        )
        CastLauncherSheet(launcher)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(place?.title ?: stringResource(R.string.net_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { if (stack.isEmpty()) onBack() else stack.removeAt(stack.lastIndex) }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (place == null) {
                        IconButton(onClick = { showAdd = true }) { Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.net_add_folder_cd)) }
                    } else if (folderItems.isNotEmpty()) {
                        TextButton(onClick = { launcher.cast(folderItems, 0, place.title) }) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.net_play_all))
                        }
                    }
                },
            )
        },
        bottomBar = {
            val playing = (castState as? CastState.Active)?.takeIf { it.kind == CastKind.FILE }
            if (playing != null) NowPlayingBar(playing)
        },
    ) { padding ->
        LaunchedEffect(place) { if (place == null) folderItems = emptyList() }
        Box(Modifier.padding(padding)) {
            if (place == null) {
                Home(
                    network = network,
                    onPickFiles = { picker.launch(arrayOf("video/*", "audio/*", "image/*", "application/x-subrip", "text/vtt", "text/x-ssa", "application/octet-stream")) },
                    onOpen = { stack.add(it) },
                    onOpenServers = { serverKind = it.name },
                )
            } else {
                Folder(network, place, launcher, onOpen = { stack.add(it) }, onItems = { folderItems = it })
            }
        }
    }

    if (showAdd) AddSourceDialog(network, onDismiss = { showAdd = false })
    CastLauncherSheet(launcher)
}

@Composable
private fun Home(
    network: NetworkSources,
    onPickFiles: () -> Unit,
    onOpen: (Place) -> Unit,
    onOpenServers: (io.github.patschgoo.castapp.mediaservers.ServerKind) -> Unit,
) {
    val servers by network.servers.collectAsStateWithLifecycle()
    val sources by network.sources.collectAsStateWithLifecycle()
    LazyColumn(Modifier.readableWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.net_phone_cloud), style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.net_phone_cloud_text),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = onPickFiles) { Text(stringResource(R.string.net_choose_files)) }
                }
            }
        }
        item { io.github.patschgoo.castapp.mediaservers.MediaServersHomeEntries(onOpenServers) }
        item { SectionTitle(stringResource(R.string.net_sec_servers)) }
        if (servers.isEmpty()) {
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        stringResource(R.string.net_looking_servers),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(servers, key = { "srv:" + it.id }) { s ->
            ListItem(
                onClick = { onOpen(Place.Server(s, "0", s.name)) },
                leadingContent = { Thumb(s.iconUrl, Icons.Rounded.Dns) },
                supportingContent = { Text(stringResource(R.string.net_server_dlna)) },
            ) { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        item { SectionTitle(stringResource(R.string.net_sec_folders)) }
        if (sources.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.net_folders_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        items(sources, key = { "src:" + it.id }) { s -> SourceRow(network, s, onOpen) }
    }
}

@Composable
private fun SourceRow(network: NetworkSources, s: NetworkSource, onOpen: (Place) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.polish_remove_title, s.name)) },
            text = { Text(stringResource(R.string.polish_remove_folder_text)) },
            confirmButton = { TextButton(onClick = { confirmRemove = false; network.remove(s.id) }) { Text(stringResource(R.string.net_remove)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    ListItem(
        onClick = { onOpen(Place.Share(s.id, s.rootPath, s.name)) },
        leadingContent = { Thumb(null, if (s.kind == SourceKind.SMB) Icons.Rounded.FolderShared else Icons.Rounded.Cloud) },
        supportingContent = {
            Text(
                if (s.kind == SourceKind.SMB) "\\\\${s.address}\\${s.share}" + s.folder.takeIf { it.isNotEmpty() }?.let { "\\" + it.replace('/', '\\') }.orEmpty() else s.address,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.net_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.net_remove)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        onClick = { menu = false; confirmRemove = true },
                    )
                }
            }
        },
    ) { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun Thumb(url: String?, icon: ImageVector) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        RemoteImage(url, Modifier.size(44.dp), sizePx = 128) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Folder(network: NetworkSources, place: Place, launcher: CastLauncher, onOpen: (Place) -> Unit, onItems: (List<MediaItem>) -> Unit) {
    var rows by remember(place) { mutableStateOf<List<Row>?>(null) }
    var items by remember(place) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var error by remember(place) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val removedText = stringResource(R.string.net_folder_removed)
    val cantOpenText = stringResource(R.string.net_cant_open_folder)

    LaunchedEffect(place, reload) {
        error = null
        rows = null
        try {
            when (place) {
                is Place.Share -> {
                    val source = network.get(place.sourceId) ?: throw java.io.IOException(removedText)
                    val entries = network.list(source, place.path)
                    val playable = network.itemsFor(source, entries)
                    items = playable
                    rows = entries.filter { it.isFolder || isShown(it) }.map { e ->
                        if (e.isFolder) Row(e.name, true, null, null, open = Place.Share(source.id, e.path, e.name))
                        else {
                            val i = playable.indexOfFirst { it.title == e.name }
                            Row(e.name, false, playable.getOrNull(i)?.type, e.size?.let(::formatSize), playIndex = i)
                        }
                    }
                }
                is Place.Server -> {
                    val entries = network.browseServer(place.server, place.objectId)
                    val files = entries.filter { !it.isFolder && it.url != null }
                    val playable = files.map { e -> MediaItem(e.title, e.url!!, e.mimeType ?: MediaItem.mimeForUrl(e.url)) }
                    items = playable
                    rows = entries.map { e ->
                        if (e.isFolder) Row(e.title, true, null, null, e.artUrl, open = Place.Server(place.server, e.id, e.title))
                        else {
                            val i = files.indexOf(e)
                            Row(e.title, false, playable.getOrNull(i)?.type, serverDetails(e), e.artUrl, playIndex = i)
                        }
                    }
                }
            }
            onItems(items)
        } catch (e: Exception) {
            error = e.message ?: cantOpenText
            rows = emptyList()
            onItems(emptyList())
        }
    }

    val list = rows
    when {
        list == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error != null -> Column(Modifier.readableWidth().padding(24.dp)) {
            Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { reload++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.net_try_again))
            }
        }
        list.isEmpty() -> Text(stringResource(R.string.net_folder_empty), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> LazyColumn(Modifier.readableWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list) { r ->
                ListItem(
                    onClick = {
                        when {
                            r.open != null -> onOpen(r.open)
                            r.playIndex >= 0 -> launcher.cast(items, r.playIndex, r.title)
                        }
                    },
                    leadingContent = {
                        Thumb(
                            r.art,
                            when {
                                r.isFolder -> Icons.Rounded.Folder
                                r.type == MediaType.AUDIO -> Icons.Rounded.Audiotrack
                                r.type == MediaType.PHOTO -> Icons.Rounded.Image
                                else -> Icons.Rounded.Movie
                            },
                        )
                    },
                    supportingContent = r.details?.let { { Text(it, maxLines = 1) } },
                ) { Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

private fun isShown(e: RemoteEntry) = !Subtitles.isSubtitleFile(e.name) &&
    (NetworkSources.isMediaName(e.name) || e.mimeType?.let { it.startsWith("video/") || it.startsWith("audio/") || it.startsWith("image/") } == true)

private fun serverDetails(e: Dlna.Entry): String? = listOfNotNull(
    e.durationMs?.takeIf { it > 0 }?.let { ms -> "%d:%02d".format(ms / 60_000, (ms / 1000) % 60) },
    e.resolution,
    e.size?.let(::formatSize),
).joinToString(" · ").ifEmpty { null }

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
}

/** Adds a Windows/Samba share or a WebDAV folder, after checking that it opens. */
@Composable
private fun AddSourceDialog(network: NetworkSources, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf(SourceKind.SMB) }
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var share by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    val passwordState = rememberTextFieldState()
    val password = passwordState.text.toString()
    var domain by rememberSaveable { mutableStateOf("") }
    var trustAll by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun build(): NetworkSource? {
        return if (kind == SourceKind.SMB) {
            // Also understand \\nas\Media, smb://nas/Media, nas/Media and a folder inside the share.
            val a = io.github.patschgoo.castapp.network.SmbAddress.parse(address, share) ?: return null
            NetworkSource(
                "", SourceKind.SMB, name.trim().ifEmpty { Txt.s(R.string.net_default_name, a.displayName, a.host) },
                a.host, a.share, user.trim(), password, domain.trim(), folder = a.folder,
            )
        } else {
            val url = address.trim().let { if (it.contains("://")) it else "https://$it" }.let { if (it.endsWith("/")) it else "$it/" }
            if (url.length < 12) return null
            val host = try { java.net.URI(url).host } catch (_: Exception) { null } ?: return null
            NetworkSource("", SourceKind.WEBDAV, name.trim().ifEmpty { host }, url, user = user.trim(), password = password, trustAll = trustAll)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.net_add_title)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = kind == SourceKind.SMB,
                            onClick = { kind = SourceKind.SMB; error = null },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text(stringResource(R.string.net_kind_smb)) }
                        SegmentedButton(
                            selected = kind == SourceKind.WEBDAV,
                            onClick = { kind = SourceKind.WEBDAV; error = null },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text(stringResource(R.string.net_kind_webdav)) }
                    }
                }
                item {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it; error = null },
                        label = { Text(stringResource(if (kind == SourceKind.SMB) R.string.net_server_name else R.string.net_folder_address)) },
                        placeholder = { Text(if (kind == SourceKind.SMB) "192.168.1.10 or nas.local" else "https://cloud.example.com/remote.php/dav/files/me/") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (kind == SourceKind.SMB) {
                    item {
                        OutlinedTextField(
                            value = share, onValueChange = { share = it; error = null },
                            label = { Text(stringResource(R.string.net_share_name)) }, placeholder = { Text("Media") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = user, onValueChange = { user = it; error = null },
                        label = { Text(stringResource(if (kind == SourceKind.SMB) R.string.net_user_guest else R.string.net_user)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedSecureTextField(
                            state = passwordState,
                            label = { Text(stringResource(if (kind == SourceKind.WEBDAV) R.string.net_password_app else R.string.net_password)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                }
                if (kind == SourceKind.SMB) {
                    item {
                        OutlinedTextField(
                            value = domain, onValueChange = { domain = it },
                            label = { Text(stringResource(R.string.net_domain)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = trustAll, onCheckedChange = { trustAll = it })
                            Text(stringResource(R.string.net_self_signed), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text(stringResource(R.string.net_name_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (kind == SourceKind.WEBDAV && address.trim().startsWith("http://", ignoreCase = true)) {
                    item {
                        Text(
                            stringResource(R.string.net_http_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                item {
                    Text(
                        stringResource(R.string.net_password_stored),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && address.isNotBlank(),
                onClick = {
                    val source = build()
                    if (source == null) {
                        error = Txt.s(if (kind == SourceKind.SMB) R.string.net_enter_smb else R.string.net_enter_address)
                        return@TextButton
                    }
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            network.test(source)
                            network.add(source)
                            onDismiss()
                        } catch (e: Exception) {
                            error = Txt.s(R.string.net_couldnt_open, e.message ?: e.javaClass.simpleName)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.net_test_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}
