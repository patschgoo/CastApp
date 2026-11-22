package io.github.patschgoo.castapp

import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.R
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
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.iptv.Channel
import io.github.patschgoo.castapp.iptv.ChannelEntry
import io.github.patschgoo.castapp.iptv.EpgData
import io.github.patschgoo.castapp.iptv.GuideGrid
import io.github.patschgoo.castapp.iptv.GuideList
import io.github.patschgoo.castapp.iptv.IptvExtrasHost
import io.github.patschgoo.castapp.iptv.rememberIptvExtras
import io.github.patschgoo.castapp.iptv.IptvRepository
import io.github.patschgoo.castapp.iptv.LiveStream
import io.github.patschgoo.castapp.iptv.NowNext
import io.github.patschgoo.castapp.iptv.Programme
import io.github.patschgoo.castapp.iptv.Protection
import io.github.patschgoo.castapp.iptv.ScheduleSheet
import io.github.patschgoo.castapp.iptv.VodPane
import io.github.patschgoo.castapp.iptv.rememberNow
import io.github.patschgoo.castapp.iptv.rememberTimeFormat
import io.github.patschgoo.castapp.service.CastController
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastService
import io.github.patschgoo.castapp.service.CastState
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.service.MediaType
import io.github.patschgoo.castapp.ui.CastLauncherSheet
import io.github.patschgoo.castapp.ui.RemoteImage
import io.github.patschgoo.castapp.ui.rememberCastLauncher
import io.github.patschgoo.castapp.ui.theme.CastAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * IPTV: TV channel lists (M3U / M3U8 playlists from a provider, a public list or a
 * file). Pick a channel and it plays on the TV; Next/Previous on the phone or the
 * lock screen switch channels. With a TV guide (XMLTV) each channel shows what is on
 * now and next; Xtream log-ins also list the provider's movies and series.
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
    val now by rememberNow()
    val time = rememberTimeFormat()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Reminders, recording, parental lock (see iptv/IptvExtras.kt)
    val extras = rememberIptvExtras()
    val parental by extras.parental.state.collectAsStateWithLifecycle()
    val extraReminders by extras.reminders.items.collectAsStateWithLifecycle()
    val extraRecordings by extras.recordings.items.collectAsStateWithLifecycle()

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
    /** Which list [channels] belongs to, so the guide is never matched against the previous list's channels. */
    var channelsOf by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    // Live / Movies / Series: the last two only for Xtream log-ins.
    var tab by rememberSaveable(selectedId) { mutableIntStateOf(0) }
    val xtream = remember(selected?.id, selected?.source) { selected?.let { repo.xtreamClient(it) } }
    val currentTab = if (xtream != null && !showingFavorites) tab else 0
    var vodReload by remember { mutableIntStateOf(0) }

    // TV guide
    var epg by remember { mutableStateOf<EpgData?>(null) }
    var guideBusy by remember { mutableStateOf(false) }
    var guideError by remember { mutableStateOf<String?>(null) }
    var guideReload by remember { mutableIntStateOf(0) }
    var forceGuide by remember { mutableStateOf(false) }
    var liveView by rememberSaveable { mutableIntStateOf(0) }
    var showGuideAddress by remember { mutableStateOf(false) }
    var scheduleFor by remember { mutableStateOf<ChannelEntry?>(null) }
    var archive by remember(selectedId) { mutableStateOf<Map<String, LiveStream>?>(null) }

    LaunchedEffect(selectedId, reload, if (showingFavorites) favorites else null) {
        loading = true
        val refresh = refreshNext
        refreshNext = false
        if (refresh) forceGuide = true
        channels = try {
            when {
                showingFavorites -> playlists.flatMap { p -> runCatching { repo.channels(p) }.getOrDefault(emptyList()) }
                    .filter { it.url in favorites }.distinctBy { it.url }
                selected != null -> repo.channels(selected, refresh)
                else -> emptyList()
            }
        } catch (e: Exception) {
            snackbar.showSnackbar(e.message ?: Txt.s(R.string.tvc_load_failed))
            emptyList()
        }
        channelsOf = selectedId
        loading = false
    }

    // The guide: what is saved on the phone shows at once, then it is refreshed in the background when it is old.
    LaunchedEffect(selectedId, channelsOf, channels, guideReload) {
        guideError = null
        if (channelsOf != selectedId) return@LaunchedEffect
        if (showingFavorites) {
            val all = playlists.mapNotNull { repo.cachedGuide(it) }
            epg = if (all.isEmpty()) null else EpgData.merge(all)
            return@LaunchedEffect
        }
        val p = selected
        if (p == null) {
            epg = null
            return@LaunchedEffect
        }
        epg = repo.cachedGuide(p)
        if (channels.isEmpty()) return@LaunchedEffect
        val force = forceGuide
        forceGuide = false
        guideBusy = true
        try {
            repo.updateGuide(p, channels, force)?.let { epg = it }
        } catch (e: Exception) {
            guideError = e.message ?: e.javaClass.simpleName
        }
        guideBusy = false
    }

    val plain = remember(channels) { channels.map { ChannelEntry(it, emptyList()) } }
    val scheduled by produceState<List<ChannelEntry>?>(null, channels, epg) {
        value = null
        val g = epg
        if (g != null) value = withContext(Dispatchers.Default) { channels.map { ChannelEntry(it, g.forChannel(it)) } }
    }
    val allEntries = scheduled ?: plain
    // Hidden channels (parental lock) are not shown until the PIN is entered.
    val entries = remember(allEntries, parental, parental.unlocked(now)) { allEntries.filter { !parental.isHidden(it.channel, now) } }

    val groups = remember(entries) { entries.mapNotNull { it.channel.group }.distinct() }
    val shown = remember(entries, group, query) {
        val q = query.trim()
        entries.filter { e ->
            (group == null || e.channel.group == group) &&
                (q.isEmpty() || e.channel.name.contains(q, ignoreCase = true) || e.schedule.any { it.title.contains(q, ignoreCase = true) })
        }
    }
    val hasGuide = epg?.isEmpty == false && entries.any { it.schedule.isNotEmpty() }
    val guideShown = remember(shown) { shown.filter { it.schedule.isNotEmpty() } }

    fun playNow(entry: ChannelEntry) {
        // The shown list becomes the play list, so Next/Previous zap through it (locked channels are left out while locked).
        val ps = extras.parental.state.value
        val open = ps.unlocked(System.currentTimeMillis())
        val queue = if (open) shown else shown.filter { it === entry || ps.protectionOf(it.channel) == Protection.NONE }
        val index = queue.indexOfFirst { it === entry }.takeIf { it >= 0 } ?: return
        val from = (index - TvChannelsActivity.MAX_QUEUE / 2).coerceIn(0, (queue.size - TvChannelsActivity.MAX_QUEUE).coerceAtLeast(0))
        val window = queue.subList(from, minOf(queue.size, from + TvChannelsActivity.MAX_QUEUE)).map { it.channel }
        launcher.cast(IptvRepository.toItems(window), index - from)
    }

    fun play(entry: ChannelEntry) = extras.guarded(entry.channel) { playNow(entry) }

    // Catch-up information is only fetched when a schedule is opened.
    LaunchedEffect(scheduleFor != null || extras.programme != null, selectedId) {
        if ((scheduleFor != null || extras.programme != null) && archive == null && xtream != null) {
            archive = try {
                withContext(Dispatchers.IO) { xtream.archiveChannels() }
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tvc_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) { Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.tvc_add_cd)) }
                    IconButton(onClick = { extras.showParental = true }) {
                        Icon(Icons.Rounded.Lock, contentDescription = stringResource(R.string.iptvx_menu_parental))
                    }
                    IconButton(onClick = { context.startActivity(Intent(context, RecordingsActivity::class.java)) }) {
                        Icon(Icons.Rounded.FiberManualRecord, contentDescription = stringResource(R.string.iptvx_menu_recordings))
                    }
                    if (selected != null) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.net_more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tvc_update_list)) },
                                    leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        refreshNext = true
                                        xtream?.clearCache()
                                        vodReload++
                                        reload++
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.iptvg_menu_update_guide)) },
                                    leadingIcon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        forceGuide = true
                                        guideReload++
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.iptvg_menu_guide_address)) },
                                    leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        showGuideAddress = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tvc_remove_list, selected.name)) },
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
                        label = { Text(stringResource(R.string.tvc_favourites)) },
                        leadingIcon = { Icon(Icons.Rounded.Star, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
            if (xtream != null && !showingFavorites) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    listOf(R.string.iptvg_tab_live, R.string.iptvg_tab_movies, R.string.iptvg_tab_series).forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = currentTab == i,
                            onClick = { tab = i },
                            shape = SegmentedButtonDefaults.itemShape(i, 3),
                        ) { Text(stringResource(label)) }
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(
                        when (currentTab) {
                            1 -> stringResource(R.string.iptvg_search_movies)
                            2 -> stringResource(R.string.iptvg_search_series)
                            else -> stringResource(R.string.iptvg_search_channels)
                        },
                    )
                },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.tvc_clear)) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (currentTab != 0 && xtream != null) {
                VodPane(
                    client = xtream,
                    series = currentTab == 2,
                    query = query,
                    reload = vodReload,
                    launcher = launcher,
                    modifier = Modifier.weight(1f),
                )
                return@Column
            }
            if (hasGuide) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                    listOf(R.string.iptvg_view_channels, R.string.iptvg_view_guide, R.string.iptvx_view_grid).forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = liveView == i,
                            onClick = { liveView = i },
                            shape = SegmentedButtonDefaults.itemShape(i, 3),
                        ) { Text(stringResource(label)) }
                    }
                }
            }
            if (groups.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "all") { FilterChip(selected = group == null, onClick = { group = null }, label = { Text(stringResource(R.string.tvc_all)) }) }
                    items(groups, key = { "g:$it" }) { g ->
                        FilterChip(selected = g == group, onClick = { group = if (g == group) null else g }, label = { Text(g, maxLines = 1) })
                    }
                }
            }
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                shown.isEmpty() -> Text(
                    when {
                        showingFavorites && query.isEmpty() -> stringResource(R.string.tvc_no_favourites)
                        else -> stringResource(R.string.tvc_no_channels)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
                else -> {
                    val playingTitle = (castState as? CastState.Active)?.takeIf { it.kind == CastKind.FILE && it.live }?.title
                    Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.iptvg_channel_count, shown.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (guideBusy) {
                            Spacer(Modifier.width(10.dp))
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.iptvg_updating_guide),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (guideError != null && !guideBusy && epg == null) {
                        Text(
                            stringResource(R.string.iptvg_guide_failed, guideError.orEmpty()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                    if (liveView >= 1 && hasGuide) {
                        if (guideShown.isEmpty()) {
                            Text(
                                stringResource(R.string.iptvg_guide_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        } else if (liveView == 2) {
                            GuideGrid(
                                entries = guideShown,
                                now = now,
                                reminders = extraReminders,
                                recordings = extraRecordings,
                                onPlay = { play(it) },
                                onProgramme = { entry, p -> extras.programme = entry to p },
                                onChannelMenu = { extras.channelMenu = it },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            GuideList(
                                entries = guideShown,
                                now = now,
                                onPlay = { play(it) },
                                onInfo = { scheduleFor = it },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.weight(1f)) {
                            itemsIndexed(shown, key = { i, e -> "$i:${e.channel.url}" }) { _, e ->
                                ChannelRow(
                                    entry = e,
                                    now = now,
                                    time = time,
                                    query = query.trim(),
                                    playing = e.channel.name == playingTitle,
                                    favorite = e.channel.url in favorites,
                                    onPlay = { play(e) },
                                    onFavorite = { repo.toggleFavorite(e.channel.url) },
                                    onInfo = { scheduleFor = e },
                                    onMenu = { extras.channelMenu = e },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Catch-up: the provider plays the programme again from its start (a little extra at the end). */
    fun replay(entry: ChannelEntry, p: Programme) {
        val account = selected?.let { repo.xtream(it) } ?: return
        val streamId = account.liveStreamId(entry.channel.url) ?: return
        val minutes = ((p.stop - p.start) / 60_000L).toInt().coerceAtLeast(1) + 5
        launcher.cast(
            listOf(
                MediaItem(
                    title = "${entry.channel.name} · ${p.title}",
                    uri = account.timeshiftUrl(streamId, p.start, minutes),
                    mimeType = "video/mp2t",
                    headers = entry.channel.headers,
                ),
            ),
        )
    }

    fun catchUpDays(entry: ChannelEntry): Int {
        val streamId = selected?.let { repo.xtream(it) }?.liveStreamId(entry.channel.url) ?: return 0
        return archive?.get(streamId)?.archiveDays ?: 0
    }

    scheduleFor?.let { entry ->
        ScheduleSheet(
            entry = entry,
            now = now,
            catchUpDays = catchUpDays(entry),
            onPlayLive = {
                scheduleFor = null
                play(entry)
            },
            onReplay = { p ->
                scheduleFor = null
                replay(entry, p)
            },
            onDismiss = { scheduleFor = null },
        )
    }

    IptvExtrasHost(
        extras = extras,
        now = now,
        parental = parental,
        groups = groups,
        catchUpDays = ::catchUpDays,
        onPlay = ::play,
        onReplay = ::replay,
        onSchedule = { scheduleFor = it },
        message = { text -> scope.launch { snackbar.showSnackbar(text) } },
    )

    if (showAdd) {
        AddPlaylistDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, source ->
                val p = repo.add(name, source)
                try {
                    val found = repo.channels(p, refresh = true)
                    selectedId = p.id
                    reload++
                    scope.launch { snackbar.showSnackbar(Txt.n(R.plurals.tvc_channels_added, found.size, found.size)) }
                    null
                } catch (e: Exception) {
                    repo.remove(p.id)
                    e.message ?: Txt.s(R.string.tvc_read_failed)
                }
            },
        )
    }
    if (showGuideAddress && selected != null) {
        GuideAddressDialog(
            current = selected.guide,
            detected = remember(selected, guideReload) { repo.guideUrls(selected) },
            onDismiss = { showGuideAddress = false },
            onSave = { url ->
                showGuideAddress = false
                repo.setGuide(selected.id, url)
                epg = null
                forceGuide = true
                guideReload++
            },
        )
    }
    CastLauncherSheet(launcher)
}

/** A guide (XMLTV) address for the open playlist; empty means the playlist's or provider's own. */
@Composable
private fun GuideAddressDialog(current: String, detected: List<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.iptvg_guide_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.iptvg_guide_dialog_text), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.iptvg_guide_dialog_field)) },
                    placeholder = { Text("https://…/guide.xml.gz") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (detected.isEmpty()) stringResource(R.string.iptvg_guide_dialog_none)
                    else stringResource(R.string.iptvg_guide_dialog_current, detected.joinToString(", ").replace(Regex("(password=)[^&]*"), "$1…")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(url.trim()) }) { Text(stringResource(R.string.iptvg_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.iptvg_cancel)) } },
    )
}

@Composable
private fun ChannelRow(
    entry: ChannelEntry,
    now: Long,
    time: (Long) -> String,
    query: String,
    playing: Boolean,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onInfo: () -> Unit,
    onMenu: () -> Unit = {},
) {
    val channel = entry.channel
    val radio = IptvRepository.toItems(listOf(channel)).first().type == MediaType.AUDIO
    ListItem(
        onClick = onPlay,
        onLongClick = onMenu,
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
                if (playing) stringResource(R.string.tvc_on_tv_now) else null,
                channel.group,
                if (radio && channel.group?.contains("radio", ignoreCase = true) != true) stringResource(R.string.tvc_radio) else null,
            )
            // A search hit inside the programme (not the channel name) shows the programme that matched.
            val hit = if (query.isNotEmpty() && !channel.name.contains(query, ignoreCase = true)) entry.matching(query, now) else null
            Column {
                if (details.isNotEmpty()) Text(details.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hit != null) {
                    Text(
                        stringResource(R.string.iptvg_found, time(hit.start), hit.title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.schedule.isNotEmpty()) NowNext(entry.schedule, now, time)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.schedule.isNotEmpty()) {
                    IconButton(onClick = onInfo) {
                        Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.iptvg_info, channel.name), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = stringResource(if (favorite) R.string.tvc_remove_favourite else R.string.tvc_add_favourite),
                        tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.tvc_previous))
                }
                IconButton(onClick = { CastService.send(context, CastService.ACTION_NEXT) }, enabled = state.queueIndex + 1 < state.queueSize) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.tvc_next))
                }
            }
            IconButton(onClick = { CastService.stop(context) }) { Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.tvc_stop)) }
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
            Text(stringResource(R.string.tvc_watch), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.tvc_watch_text),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAdd) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.tvc_add_list))
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
            if (name.isBlank()) name = MediaItem.displayName(context.contentResolver, uri)
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
        title = { Text(stringResource(R.string.tvc_add_list)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(R.string.tvc_tab_link, R.string.tvc_tab_file, R.string.tvc_tab_login).map { stringResource(it) }.forEachIndexed { i, label ->
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
                        label = { Text(stringResource(R.string.tvc_playlist_address)) },
                        placeholder = { Text("https://…/playlist.m3u") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    1 -> OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (file == null) R.string.tvc_choose_m3u else R.string.tvc_file_chosen), maxLines = 1)
                    }
                    else -> {
                        Text(stringResource(R.string.tvc_xtream_text), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = server, onValueChange = { server = it; error = null },
                            label = { Text(stringResource(R.string.tvc_server)) }, placeholder = { Text("http://example.tv:8080") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = user, onValueChange = { user = it; error = null },
                            label = { Text(stringResource(R.string.tvc_user)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedSecureTextField(
                            state = passwordState,
                            label = { Text(stringResource(R.string.tvc_password)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.tvc_name_optional)) },
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
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.tvc_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}
