package io.github.patschgoo.castapp.service

import io.github.patschgoo.castapp.mediaservers.ServerReporting
import io.github.patschgoo.castapp.automation.AppReports
import io.github.patschgoo.castapp.automation.AutomationApi
import io.github.patschgoo.castapp.Txt
import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import androidx.core.net.toUri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.capture.ConvertCheck
import io.github.patschgoo.castapp.capture.ConvertChecks
import io.github.patschgoo.castapp.capture.ConvertException
import io.github.patschgoo.castapp.capture.ConvertProblem
import io.github.patschgoo.castapp.capture.TranscodeInput
import io.github.patschgoo.castapp.capture.Transcoder
import io.github.patschgoo.castapp.cast.CastException
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.NetworkScanner
import io.github.patschgoo.castapp.multi.ExtraScreens
import io.github.patschgoo.castapp.multi.HostTimeline
import io.github.patschgoo.castapp.multi.JoinMedia
import io.github.patschgoo.castapp.multi.MirrorFormats
import io.github.patschgoo.castapp.multi.PendingStart
import io.github.patschgoo.castapp.multi.Timeline
import java.net.InetAddress
import io.github.patschgoo.castapp.network.HttpMediaSource
import io.github.patschgoo.castapp.network.NetworkSource
import io.github.patschgoo.castapp.render.MediaRenderer
import io.github.patschgoo.castapp.render.PlayState
import io.github.patschgoo.castapp.render.RendererMedia
import io.github.patschgoo.castapp.render.RendererStatus
import io.github.patschgoo.castapp.settings.CastSettings
import io.github.patschgoo.castapp.settings.ConvertMode
import io.github.patschgoo.castapp.stream.FragmentedMp4
import io.github.patschgoo.castapp.stream.HlsVariant
import io.github.patschgoo.castapp.stream.HlsVariants
import io.github.patschgoo.castapp.stream.InterleavingQueue
import io.github.patschgoo.castapp.stream.MediaServer
import io.github.patschgoo.castapp.stream.MediaSource
import io.github.patschgoo.castapp.stream.Mp4FragmentSerializer
import io.github.patschgoo.castapp.stream.Subtitles
import io.github.patschgoo.castapp.stream.TokenHttpServer
import io.github.patschgoo.castapp.stream.WebProxy
import io.github.patschgoo.castapp.subtitles.Cue
import io.github.patschgoo.castapp.subtitles.CueBuilder
import io.github.patschgoo.castapp.subtitles.SpeechEngines
import io.github.patschgoo.castapp.subtitles.SubtitleGenerator
import io.github.patschgoo.castapp.subtitles.VoskModels
import io.github.patschgoo.castapp.capture.TrackProbe
import io.github.patschgoo.castapp.capture.startHlsTranscode
import io.github.patschgoo.castapp.discovery.DeviceKind
import io.github.patschgoo.castapp.settings.SubtitleStyleSettings
import io.github.patschgoo.castapp.stream.HlsConversions
import io.github.patschgoo.castapp.subtitles.CueParser
import io.github.patschgoo.castapp.subtitles.EmbeddedSubtitles
import io.github.patschgoo.castapp.subtitles.Sidecars
import io.github.patschgoo.castapp.subtitles.SubtitleImport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays a list of videos, music, photos, web videos or TV channels on a TV. The TV
 * fetches everything itself (from the phone, a network share or the web), so the
 * phone's screen can be off.
 *
 *  - Web videos are tried directly first, then through the phone (cookies, CORS).
 *  - Files on network shares (SMB) are served by the phone; password-protected
 *    servers (WebDAV, Nextcloud) go through the phone, which logs in.
 *  - Videos the TV can't play are converted on the phone (setting "Convert videos").
 *  - The picture quality can be changed at any time: a smaller conversion for files,
 *    a fixed quality level for HLS streams.
 *  - Subtitles can be written on the phone by speech recognition when a file has none.
 *  - Photos are shrunk to TV size and shown as a slideshow.
 *  - Reconnects by itself if the Wi-Fi drops or the TV restarts.
 */
class MediaCastSession(
    private val context: Context,
    device: CastDevice,
    initialItems: List<MediaItem>,
    startIndex: Int,
    private val settingsFlow: StateFlow<CastSettings>,
    private val onEnded: (ActiveCast) -> Unit,
) : ActiveCast {
    @Volatile override var device: CastDevice = device
        private set

    private val settings get() = settingsFlow.value
    private val resolver: ContentResolver = context.contentResolver
    private val network get() = CastApp.get(context).network
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ended = AtomicBoolean(false)
    private val playMutex = Mutex()
    private val locks = CastLocks(context, "CastApp:media")

    @Volatile private var renderer: MediaRenderer? = null
    private lateinit var server: MediaServer
    private lateinit var proxy: WebProxy

    /** The play list, editable while playing; replaced as a whole when something else is cast to the same TV. */
    private val queue = PlayQueue(initialItems, startIndex)
    private val items: List<MediaItem> get() = queue.items

    /** Which subtitles go to the TV (file, inside the file, written, found online) and their timing. */
    private val subtitleTracks = SubtitleTracks()
    private val history = WatchHistory.get(context)
    /** Apple TV / Roku: conversions served as HLS. */
    private val hls = HlsConversions(File(context.cacheDir, "hls-convert"))
    private val trackCache = ConcurrentHashMap<String, TrackList>()
    /** Sound track picked per file (its place among the file's sound tracks). */
    private val audioChoice = ConcurrentHashMap<String, Int>()
    @Volatile private var probingFor: String? = null
    @Volatile private var extractJob: Job? = null
    @Volatile private var extractProgress = -1
    @Volatile private var offsetReload: Job? = null

    // Caches by item address, so they stay right when the play list is replaced.
    private val photoCache = ConcurrentHashMap<String, ByteArray>()
    private val durationCache = ConcurrentHashMap<String, Long>()
    /** Whether the phone can convert a file, by [convertKey]. */
    private val convertChecks = ConcurrentHashMap<String, ConvertCheck>()
    /** Files whose missing sound was already pointed out (converted without sound / a Chromecast and DTS). */
    private val noSoundWarned: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val castSoundWarned: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val variantCache = ConcurrentHashMap<String, List<HlsVariant>>()
    private val generated = ConcurrentHashMap<String, List<Cue>>()
    private val generatedDone: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Items whose subtitles were picked online: they win over the file's own subtitles. */
    /** Channel addresses already checked and found not to be HLS. */
    private val notHls: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Position of the playing item; the queue keeps it right when the order changes. */
    private var index: Int
        get() = queue.index
        set(value) {
            queue.index = value
        }
    @Volatile private var delivery = Delivery.SERVE
    @Volatile private var convertStartMs = 0L
    @Volatile private var outcome: CompletableDeferred<Boolean>? = null
    @Volatile private var started = false
    @Volatile private var subtitlesOn = true
    @Volatile private var slideshowPaused = false
    @Volatile private var reconnecting = false
    @Volatile private var volume: Double? = null
    @Volatile private var muted = false
    private var slideshow: Job? = null

    /** Picked by hand: convert videos to this height (0 = play the original). */
    @Volatile private var convertHeight = 0
    /** Picked by hand for HLS streams: a quality label, or [AUTO]. */
    @Volatile private var hlsChoice = AUTO
    @Volatile private var variants: List<HlsVariant> = emptyList()

    /** Zapping direction, so a dead channel is skipped the way the user was going. */
    @Volatile private var direction = 1
    @Volatile private var skipped = 0
    @Volatile private var liveRetries = 0

    @Volatile private var subtitleJob: Job? = null
    @Volatile private var subtitleFor: String? = null
    @Volatile private var subtitleProgress = -1
    /** The running subtitle job writes ahead for a later file. */
    @Volatile private var writingAhead = false

    var mediaSession: MediaSession? = null
        private set
    private var volumeProvider: VolumeProvider? = null

    private val current: MediaItem get() = items[index]

    // ---- several screens ----------------------------------------------------------------------
    @Volatile override var handingOver = false
    private val extras: ExtraScreens = ExtraScreens(
        scope, live = false,
        onChange = { update { it } },
        onGone = { d, _ -> onExtraGone(d) },
    ).also { it.provider = { d, local -> extraMedia(d, local) } }
    /** Where playback is, on this phone's clock: CastApp receivers and viewers follow it. */
    private val timeline = HostTimeline(SystemClock::elapsedRealtime)

    fun start() {
        AppReports.onQueue(this, items)
        CastController.begin(this, baseState(CastPhase.CONNECTING))
        PlaybackHub.attach(this)
        publishQueue()
        createMediaSession()
        scope.launch { run() }
    }

    private fun baseState(phase: CastPhase) = CastState.Active(
        device = device,
        phase = phase,
        kind = CastKind.FILE,
        title = current.title,
        queueIndex = index,
        queueSize = items.size,
        isPhoto = current.type == MediaType.PHOTO,
        subtitlesOn = subtitlesOn,
        audio = true,
        live = current.live,
    ).withExtras()

    /** Fills in quality and subtitle details of the current item. */
    private fun CastState.Active.withExtras(): CastState.Active {
        val item = current
        publishTracks(item)
        return copy(
            hasSubtitles = subtitlesReady(item),
            canCreateSubtitles = canCreateSubtitles(item),
            subtitleProgress = if (subtitleFor == item.uri) subtitleProgress else -1,
            qualityOptions = qualityOptions(item),
            qualityLabel = qualityLabel(item),
            live = item.live,
        )
    }

    private fun refreshExtras() {
        update { it.withExtras() }
    }

    private suspend fun run() {
        try {
            locks.acquire()
            server = MediaServer(settings.port)
            proxy = WebProxy(server)
            proxy.onVariants = { list ->
                val item = current
                if (item.isHls && list.isNotEmpty() && list != variants) {
                    variantCache[item.uri] = list
                    variants = list
                    refreshExtras()
                }
            }
            registerRoutes()
            server.start()
            connect()
            playIndex(index, 0, resume = true)
            // The TV reports changes only; refresh the position now and then.
            while (!ended.get()) {
                delay(5000)
                if (!reconnecting) try { renderer?.refresh() } catch (_: Exception) {}
                recordPosition()
                hls.sweep()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Media cast failed", e)
            AppReports.failed(this@MediaCastSession, current, AutomationApi.REASON_CAST_FAILED)
            finish(e.message ?: Txt.s(R.string.err_cast_failed))
        }
    }

    private suspend fun connect() {
        val r = MediaRenderer.create(device)
        r.listener = listenerFor(r)
        r.connect()
        renderer = r
    }

    /** Something else was cast to this TV: play it without connecting again. */
    fun replaceQueue(newItems: List<MediaItem>, startIndex: Int): Boolean {
        if (newItems.isEmpty() || ended.get() || renderer == null) return false
        scope.launch {
            playMutex.withLock {
                recordPosition(force = true)
                queue.replaceAll(newItems, startIndex.coerceIn(newItems.indices))
                AppReports.onQueue(this@MediaCastSession, newItems)
                direction = 1
                skipped = 0
            }
            publishQueue()
            playIndex(index, 0, resume = true)
        }
        return true
    }

    // ---- where the files come from ----------------------------------------------------------

    /** A file on a network share: smb://<source id>/<path inside the share>. */
    private fun smbLocation(uri: String): Pair<NetworkSource, String> {
        val rest = uri.removePrefix("smb://")
        val source = network.get(rest.substringBefore('/')) ?: throw IOException(Txt.s(R.string.err_share_removed))
        return source to rest.substringAfter('/', "")
    }

    private fun sourceFor(item: MediaItem): MediaSource = when {
        item.isSmb -> smbLocation(item.uri).let { (source, path) -> network.smb(source).mediaSource(path) }
        item.isWeb -> HttpMediaSource(item.uri, item.headers, item.trustAll)
        else -> ContentMediaSource(resolver, item.uri.toUri())
    }

    /** What the converter and the subtitle writer read. */
    private fun inputFor(item: MediaItem): TranscodeInput = when {
        item.isSmb || (item.isWeb && item.trustAll) -> TranscodeInput.FromSource(sourceFor(item))
        else -> TranscodeInput.FromUri(item.uri.toUri(), item.headers)
    }

    // ---- what the phone serves ----------------------------------------------------------

    private fun registerRoutes() {
        // Paths use the queue entry id, which stays the same when the play list is edited:
        // file/<id>/<name> (file/<id>/m/<name>: told video/x-matroska), photo/<id>.jpg,
        // st/<id>/<choice>/<streamStartMs>/<offsetMs>.vtt|srt (subtitles),
        // tx/<id>/<startMs>/<height>/<audio>.mp4 (converted), hx/<id>/<startMs>/<height>/<audio>/… (converted, HLS).
        server.addPrefixRoute("file/") { request, socket, out ->
            val item = itemAt(request.path, "file/")?.takeIf { !it.isWeb } ?: return@addPrefixRoute notFound(out)
            val source = try { sourceFor(item) } catch (_: IOException) { return@addPrefixRoute notFound(out) }
            // The type this TV was told: a Chromecast and a DLNA TV may fetch the same MKV at once.
            server.ranged(source, PlaybackChecks.servedMime(request.path, item.mimeType)).handle(request, socket, out)
        }
        server.addPrefixRoute("photo/") { request, socket, out ->
            val item = itemAt(request.path, "photo/") ?: return@addPrefixRoute notFound(out)
            server.bytes("image/jpeg") { photo(item) }.handle(request, socket, out)
        }
        server.addPrefixRoute("st/") { request, socket, out ->
            val parts = request.path.substringAfter("/st/").split('/')
            val item = parts.getOrNull(0)?.toLongOrNull()?.let { queue.itemById(it) } ?: return@addPrefixRoute notFound(out)
            val choice = SubtitleChoice.fromKey(parts.getOrNull(1)) ?: return@addPrefixRoute notFound(out)
            val streamStart = parts.getOrNull(2)?.toLongOrNull() ?: 0
            val last = parts.getOrNull(3).orEmpty()
            val userOffset = last.substringBefore('.').toLongOrNull() ?: 0
            val srt = last.endsWith(".srt")
            server.bytes(if (srt) "application/x-subrip" else "text/vtt") {
                val cues = cuesFor(item, choice) ?: return@bytes null
                val timed = SubtitleTracks.timed(cues, streamStart, userOffset)
                (if (srt) CueBuilder.toSrt(timed) else CueBuilder.toWebVtt(timed)).toByteArray()
            }.handle(request, socket, out)
        }
        // A new conversion per request.
        server.addPrefixRoute("tx/") { request, socket, out ->
            val parts = request.path.substringAfter("/tx/").removeSuffix(".mp4").split('/')
            val item = parts.getOrNull(0)?.toLongOrNull()?.let { queue.itemById(it) } ?: return@addPrefixRoute notFound(out)
            val startMs = parts.getOrNull(1)?.toLongOrNull() ?: 0
            val height = parts.getOrNull(2)?.toIntOrNull() ?: 1080
            val audio = parts.getOrNull(3)?.toIntOrNull() ?: 0
            serveConverted(item, startMs, height, audio, request, socket, out)
        }
        // One conversion that lives across the TV's playlist and segment requests.
        server.addPrefixRoute("hx/") { request, socket, out -> serveHls(request, socket, out) }
    }

    private fun notFound(out: OutputStream) = server.writeResponseHead(out, 404, "Not Found", "text/plain", 0, emptyMap())

    private fun itemAt(path: String, prefix: String): MediaItem? =
        path.substringAfter("/$prefix").substringBefore('/').substringBefore('.').toLongOrNull()?.let { queue.itemById(it) }

    private fun serveHls(request: TokenHttpServer.Request, socket: Socket, out: OutputStream) {
        val parts = request.path.substringAfter("/hx/").split('/')
        if (parts.size < 5) return notFound(out)
        val item = parts[0].toLongOrNull()?.let { queue.itemById(it) } ?: return notFound(out)
        val startMs = parts[1].toLongOrNull() ?: 0
        val height = parts[2].toIntOrNull() ?: 1080
        val audio = parts[3].toIntOrNull() ?: 0
        val key = parts.take(4).joinToString("/")
        val file = parts[4]
        when {
            file == "index.m3u8" -> {
                val conversion = try {
                    hls.obtain(key) { c -> startHlsTranscode(context, inputFor(item), startMs, height, audio, c) }
                } catch (e: Exception) {
                    Log.w(TAG, "Can't convert ${item.title}", e)
                    return server.writeResponseHead(out, 500, "Conversion failed", "text/plain", 0, emptyMap())
                }
                val playlist = conversion.store.playlist(minSegments = 2, timeoutMs = 40_000)
                    ?: return server.writeResponseHead(out, 503, "Converting", "text/plain", 0, emptyMap())
                server.bytes("application/vnd.apple.mpegurl") { playlist.toByteArray() }.handle(request, socket, out)
            }
            file == "init.mp4" -> {
                val init = hls.find(key)?.store?.init ?: return notFound(out)
                server.bytes("video/mp4") { init }.handle(request, socket, out)
            }
            file.startsWith("seg") && file.endsWith(".m4s") -> {
                val n = file.removePrefix("seg").removeSuffix(".m4s").toIntOrNull() ?: return notFound(out)
                val conversion = hls.find(key) ?: return notFound(out)
                val data = conversion.store.segment(n, timeoutMs = 30_000) ?: return notFound(out)
                server.bytes("video/mp4") { data }.handle(request, socket, out)
            }
            else -> notFound(out)
        }
    }

    private fun photo(item: MediaItem): ByteArray? = photoCache[item.uri] ?: run {
        val jpeg = if (item.isSmb) {
            sourceFor(item).open(0).use { HttpMediaSource.readUpTo(it, MAX_PHOTO_BYTES) }?.let { MediaItem.loadPhotoForTv(it) }
        } else {
            MediaItem.loadPhotoForTv(resolver, item.uri.toUri())
        }
        jpeg?.also {
            if (photoCache.size > 4) photoCache.clear()
            photoCache[item.uri] = it
        }
    }

    /** The text of the subtitle file at [uri], one of [item]'s ([MediaItem.subtitleFiles]). */
    private fun subtitleText(item: MediaItem, uri: String): String? {
        val bytes = when {
            uri.startsWith("http") -> HttpMediaSource(uri, item.headers, item.trustAll).open(0).use { HttpMediaSource.readUpTo(it, MAX_SUBTITLE_BYTES) }
            uri.startsWith("smb://") -> smbLocation(uri).let { (source, path) ->
                network.smb(source).open(path, 0).use { HttpMediaSource.readUpTo(it, MAX_SUBTITLE_BYTES) }
            }
            else -> resolver.openInputStream(uri.toUri())?.use { HttpMediaSource.readUpTo(it, MAX_SUBTITLE_BYTES) }
        } ?: return null
        return Subtitles.decode(bytes)
    }

    /** The cues of [choice] for [item], reading the subtitle file the first time (kept by its address). */
    private fun cuesFor(item: MediaItem, choice: SubtitleChoice): List<Cue>? {
        val file = item.subtitleFiles.getOrNull(choice.track)?.takeIf { choice.kind == SubtitleKind.FILE }
        if (file != null && subtitleTracks.fileCues(item.uri, file) == null) {
            subtitleText(item, file)?.let { text ->
                val cues = CueParser.parse(text)
                subtitleTracks.setFileCues(item.uri, file, cues)
                // No text in it: writing them becomes possible ([SubtitleTracks.mayWrite]).
                if (cues.isEmpty() && current.uri == item.uri) refreshExtras()
            }
        }
        return subtitleTracks.cuesFor(item.uri, choice, generated[item.uri], item.subtitleFiles)
    }

    private fun serveConverted(item: MediaItem, startMs: Long, height: Int, audio: Int, request: TokenHttpServer.Request, socket: Socket, out: OutputStream) {
        val queue = InterleavingQueue(setOf(FragmentedMp4.VIDEO_TRACK_ID, FragmentedMp4.AUDIO_TRACK_ID))
        val transcoder = try {
            Transcoder(context, inputFor(item), startMs * 1000, queue, maxHeight = height, audioTrackOrdinal = audio)
        } catch (_: IOException) {
            server.writeResponseHead(out, 500, "Conversion failed", "text/plain", 0, emptyMap())
            return
        }
        transcoder.hub.subscribe(queue)
        try {
            transcoder.start()
        } catch (e: Exception) {
            // Refused before any byte of video: the TV gets the error at once instead of waiting on an empty body.
            Log.w(TAG, "Can't convert ${item.title}: ${(e as? ConvertException)?.problem ?: e}", e)
            server.writeResponseHead(out, 500, "Conversion failed", "text/plain", 0, emptyMap())
            return
        }
        transcoder.droppedAudioMime?.let { warnNoSound(item, it) }
        try {
            server.stream("video/mp4") { body ->
                // A decoder that breaks right away ends the stream at once, not after the full wait.
                val init = transcoder.hub.awaitInitSegment(20_000, failed = { transcoder.error != null || ended.get() }) ?: return@stream
                body.write(init)
                val serializer = Mp4FragmentSerializer(transcoder.hub.publishedAudioRate)
                var unflushed = 0
                while (!queue.isFinished && !ended.get()) {
                    val sample = queue.take(500) ?: continue
                    val bytes = serializer.serialize(sample) ?: continue
                    body.write(bytes)
                    unflushed += bytes.size
                    if (unflushed > 256 * 1024) {
                        body.flush()
                        unflushed = 0
                    }
                }
                body.flush()
            }.handle(request, socket, out)
        } finally {
            transcoder.stop()
        }
    }

    // ---- picture quality --------------------------------------------------------------------------

    private fun qualityOptions(item: MediaItem): List<String> = when {
        item.type != MediaType.VIDEO -> emptyList()
        item.isHls -> if (variants.size > 1) listOf(AUTO) + variants.map { it.label } else emptyList()
        else -> FILE_QUALITIES
    }

    private fun qualityLabel(item: MediaItem): String = when {
        item.type != MediaType.VIDEO -> ""
        item.isHls -> chosenVariant()?.label ?: AUTO
        delivery == Delivery.CONVERT -> "${convertHeightNow()}p"
        else -> ORIGINAL
    }

    private fun convertHeightNow() = if (convertHeight > 0) convertHeight else 1080

    /** The HLS quality level to play: the one picked, or the closest one for a new stream. */
    private fun chosenVariant(): HlsVariant? {
        val choice = hlsChoice
        if (choice == AUTO || variants.isEmpty()) return null
        variants.firstOrNull { it.label == choice }?.let { return it }
        val height = choice.removeSuffix("p").toIntOrNull() ?: return null
        return variants.filter { it.height in 1..height }.maxByOrNull { it.height }
            ?: variants.filter { it.height > 0 }.minByOrNull { it.height }
    }

    /**
     * Changes the picture quality now: "Original" / "1080p" / "720p" / "480p" for
     * files and web videos (converted on the phone), or a quality level of an HLS
     * stream ("Auto" lets the TV choose). Playback continues where it was.
     */
    fun setQuality(label: String) {
        val item = current
        if (item.type != MediaType.VIDEO || ended.get()) return
        val s = CastController.state.value as? CastState.Active ?: return
        val position = if (item.live) 0 else s.positionNow(SystemClock.elapsedRealtime())
        val wasPaused = s.paused
        if (item.isHls) {
            if (label != AUTO && variants.none { it.label == label }) return
            hlsChoice = label
        } else {
            convertHeight = if (label == ORIGINAL) 0 else label.removeSuffix("p").toIntOrNull() ?: return
        }
        update { it.copy(qualityLabel = label) }
        scope.launch {
            playIndex(index, position, onlyFrom = if (item.isHls) Delivery.PROXY else null)
            if (wasPaused) pause()
        }
    }

    /** Quality levels of an HLS stream, read from its master playlist. */
    private fun loadVariants(item: MediaItem): List<HlsVariant> = try {
        val conn = HttpMediaSource.connect(item.uri, item.headers, item.trustAll, timeoutMs = 8000)
        try {
            if (conn.responseCode !in 200..299) emptyList() else {
                val text = conn.inputStream.use { HttpMediaSource.readUpTo(it, 2 * 1024 * 1024) }?.toString(Charsets.UTF_8).orEmpty()
                if (HlsVariants.isMaster(text)) HlsVariants.parse(text, URI(conn.url.toString())) else emptyList()
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** A TV channel without a telling address: look whether it is HLS. */
    private fun resolveLiveType(i: Int): MediaItem {
        val item = items[i]
        if (item.uri in notHls) return item
        val isHls = try {
            val conn = HttpMediaSource.connect(item.uri, item.headers, item.trustAll, timeoutMs = 8000)
            try {
                val type = conn.contentType.orEmpty()
                type.contains("mpegurl", ignoreCase = true) ||
                    (conn.responseCode in 200..299 && conn.inputStream.use { input ->
                        val head = ByteArray(7)
                        var n = 0
                        while (n < head.size) {
                            val r = input.read(head, n, head.size - n)
                            if (r < 0) break
                            n += r
                        }
                        String(head, 0, n, Charsets.US_ASCII) == "#EXTM3U"
                    })
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
        if (!isHls) {
            notHls += item.uri
            return item
        }
        val resolved = item.copy(mimeType = "application/x-mpegurl")
        queue.replaceItem(i, item, resolved)
        return resolved
    }

    // ---- subtitles written by speech recognition -------------------------------------------------

    /**
     * New subtitles may be written for [item] ([SubtitleTracks.mayWrite]: no subtitle file, or only
     * ASS/SSA ones, or the one shown is empty; [auto]: written by itself, without changing what shows).
     * Ones written before are loaded and offered whenever the file can have them
     * ([SubtitleTracks.canHaveWritten]), subtitle files or not.
     */
    private fun mayWrite(item: MediaItem) = subtitleTracks.mayWrite(item, activeSubtitle(item))

    private fun canCreateSubtitles(item: MediaItem) =
        mayWrite(item) && item.uri !in generatedDone && subtitleFor != item.uri

    /**
     * Writes subtitles for the current file with offline speech recognition in the
     * language chosen in the settings. The TV gets them as soon as there is a good
     * stretch ahead of the viewer, and again when they are complete.
     */
    fun createSubtitles(auto: Boolean = false) {
        val item = current
        if (!canCreateSubtitles(item)) return
        if (subtitleJob?.isActive == true) {
            if (writingAhead && subtitleFor != item.uri) {
                // Only writing ahead for a later file: the one playing now goes first.
                subtitleJob?.cancel()
            } else {
                if (!auto) CastController.message(context.getString(R.string.subtitles_busy))
                return
            }
        }
        val (setup, missing) = SpeechEngines.ready(context, settings.subtitleLanguage)
        if (setup == null) {
            if (!auto) CastController.message(missing ?: Txt.s(R.string.err_choose_language))
            return
        }
        // Asked for next to a subtitle file (ASS, or one without text): the written ones show as they come.
        if (!auto && item.subtitleFiles.isNotEmpty()) subtitleTracks.choose(item.uri, SubtitleChoice.WRITTEN)
        startSubtitleJob(item, setup, ahead = false)
    }

    /**
     * "Write subtitles automatically": once the file playing now has its subtitles,
     * write them for the next files in the queue, so they're ready when those start.
     */
    private fun writeAhead() {
        if (!settings.autoSubtitles || ended.get() || subtitleJob?.isActive == true) return
        if (canCreateSubtitles(current)) return
        val setup = SpeechEngines.ready(context, settings.subtitleLanguage).first ?: return
        val next = queue.upcoming(WRITE_AHEAD).map { it.item }.firstOrNull { item ->
            if (!mayWrite(item) || item.uri in generatedDone) return@firstOrNull false
            loadCachedSubtitles(item)
            item.uri !in generatedDone
        } ?: return
        Log.i(TAG, "Writing subtitles ahead for ${next.title}")
        startSubtitleJob(next, setup, ahead = true)
    }

    private fun startSubtitleJob(item: MediaItem, setup: io.github.patschgoo.castapp.subtitles.SpeechSetup, ahead: Boolean) {
        writingAhead = ahead
        subtitleFor = item.uri
        subtitleProgress = 0
        refreshExtras()
        subtitleJob = scope.launch {
            var servedUntil = 0.0
            var lastReload = 0L
            try {
                val cues = SubtitleGenerator(context).generate(inputFor(item), setup) { fraction, partial ->
                    subtitleProgress = (fraction * 100).toInt().coerceIn(0, 99)
                    if (partial.isNotEmpty()) generated[item.uri] = partial
                    if (current.uri == item.uri) refreshExtras()
                    // Hand the TV what we have when it covers a good stretch ahead of the viewer.
                    val covered = partial.lastOrNull()?.end ?: 0.0
                    val s = CastController.state.value as? CastState.Active
                    val watching = s?.positionNow(SystemClock.elapsedRealtime())?.div(1000.0) ?: 0.0
                    val now = SystemClock.elapsedRealtime()
                    if (current.uri == item.uri && started && fraction < 0.97 &&
                        covered - watching > 300 && watching > servedUntil - 60 && now - lastReload > 120_000
                    ) {
                        servedUntil = covered
                        lastReload = now
                        scope.launch { reloadForSubtitles(item) }
                    }
                }
                generated[item.uri] = cues
                generatedDone += item.uri
                saveCachedSubtitles(item, setup.cacheKey, cues)
                subtitleFor = null
                subtitleProgress = -1
                refreshExtras()
                val playingNow = current.uri == item.uri
                if (cues.isEmpty()) {
                    if (playingNow) CastController.message(Txt.s(R.string.err_no_speech, item.title))
                } else if (playingNow) {
                    if (!subtitlesOn) toggleSubtitles()
                    reloadForSubtitles(item)
                }
                writeAhead()
            } catch (e: CancellationException) {
                if (item.uri !in generatedDone) generated.remove(item.uri)
                if (subtitleFor == item.uri) {
                    subtitleFor = null
                    subtitleProgress = -1
                }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Writing subtitles failed", e)
                if (item.uri !in generatedDone) generated.remove(item.uri)
                subtitleFor = null
                subtitleProgress = -1
                refreshExtras()
                if (current.uri == item.uri) CastController.message(Txt.s(R.string.err_subs_failed, e.message ?: Txt.s(R.string.err_unknown)))
            }
        }
    }

    // ---- subtitles found online ---------------------------------------------------------------

    /** The video now playing and a way to read it, for finding subtitles online (null: not a video file). */
    fun subtitleSearchFile(): Pair<MediaItem, MediaSource?>? {
        val item = current
        if (item.type != MediaType.VIDEO || item.live || item.isHls) return null
        val source = if (item.isWeb) null else try { sourceFor(item) } catch (_: IOException) { null }
        return item to source
    }

    /**
     * Shows [text] (SRT or WebVTT) as the subtitles of the current video, instead of any it
     * had, and reloads it on the TV like written subtitles. False if it holds no subtitles.
     */
    fun useSubtitles(text: String): Boolean {
        val item = current
        if (item.type == MediaType.PHOTO || item.live) return false
        val cues = SubtitleImport.parse(text)
        if (cues.isEmpty()) return false
        if (subtitleFor == item.uri) {
            // Found ones replace the ones being written.
            subtitleJob?.cancel()
            subtitleFor = null
            subtitleProgress = -1
        }
        // Kept, so the next time this file plays they're there without another download.
        saveCachedSubtitles(item, DOWNLOADED_KEY, cues)
        subtitleTracks.setOnline(item.uri, context.getString(R.string.subs_online), cues)
        chooseSubtitles(SubtitleChoice.ONLINE.key)
        return true
    }

    /** Reloads the item on the TV where it is, so the TV picks up the newest subtitles. */
    private suspend fun reloadForSubtitles(item: MediaItem) {
        if (ended.get() || reconnecting || current.uri != item.uri || !started) return
        val s = CastController.state.value as? CastState.Active ?: return
        val position = s.positionNow(SystemClock.elapsedRealtime())
        playIndex(index, position, onlyFrom = delivery)
        if (s.paused) pause()
    }

    /** [key]: the speech setup's cache key, or [DOWNLOADED_KEY] for subtitles found online. */
    private fun cacheFile(item: MediaItem, key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest("${item.uri}|$key".toByteArray())
        return File(File(context.filesDir, "subtitles"), digest.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) } + ".json")
    }

    private fun loadCachedSubtitles(item: MediaItem) {
        // Subtitles found online earlier come back as the "found online" choice.
        if (!item.live && item.type != MediaType.PHOTO && !subtitleTracks.hasOnline(item.uri)) {
            val online = cacheFile(item, DOWNLOADED_KEY)
            if (online.exists()) {
                try {
                    subtitleTracks.setOnline(item.uri, context.getString(R.string.subs_online), CueBuilder.fromJson(online.readText()))
                } catch (_: Exception) {
                    online.delete()
                }
            }
        }
        // Written ones are kept even when the file came with subtitle files (an ASS one, say).
        if (!SubtitleTracks.canHaveWritten(item) || item.uri in generatedDone) return
        val key = SpeechEngines.chosen(context, settings.subtitleLanguage)?.cacheKey ?: return
        val file = cacheFile(item, key).takeIf { it.exists() } ?: return
        try {
            generated[item.uri] = CueBuilder.fromJson(file.readText())
            generatedDone += item.uri
        } catch (_: Exception) {
            file.delete()
        }
    }

    private fun saveCachedSubtitles(item: MediaItem, key: String, cues: List<Cue>) {
        try {
            val file = cacheFile(item, key)
            file.parentFile?.mkdirs()
            file.writeText(CueBuilder.toJson(cues))
        } catch (e: Exception) {
            Log.i(TAG, "Couldn't keep the subtitles: ${e.message}")
        }
    }

    // ---- playing ------------------------------------------------------------------------------

    /** The ways to try for [item] on [target] ([PlaybackChecks.plan]). */
    private fun plan(item: MediaItem, target: CastDevice = device): List<Delivery> =
        PlaybackChecks.plan(item, target, settings.convert, picked = convertPicked(item))

    /** A quality or sound track was picked by hand, which means converting [item]. */
    private fun convertPicked(item: MediaItem): Boolean = convertHeight > 0 || (audioChoice[item.uri] ?: 0) > 0

    /** Apple TV and Roku can't play an endless MP4: they get converted videos as HLS. */
    private val convertsToHls: Boolean get() = device.kind == DeviceKind.AIRPLAY || device.kind == DeviceKind.ROKU

    private fun mediaFor(
        i: Int,
        d: Delivery,
        startMs: Long,
        target: CastDevice = device,
        address: InetAddress? = null,
    ): RendererMedia {
        val item = items[i]
        val id = queue.idAt(i)
        val local = address ?: renderer?.localAddress ?: throw CastException(Txt.s(R.string.err_no_path))
        val convertsToHls = target.kind == DeviceKind.AIRPLAY || target.kind == DeviceKind.ROKU
        val t = server.token
        val audio = audioChoice[item.uri] ?: 0
        val mime = when {
            d == Delivery.CONVERT -> if (convertsToHls) "application/x-mpegurl" else "video/mp4"
            d == Delivery.ORIGINAL && item.original != null -> item.originalMime ?: MediaItem.mimeForUrl(item.original)
            item.type == MediaType.PHOTO -> if (d == Delivery.SERVE) "image/jpeg" else item.mimeType
            item.isHls -> "application/x-mpegurl"
            // MKV: "video/webm" for Chromecasts only; DLNA TVs know (and some insist on) video/x-matroska.
            else -> PlaybackChecks.mimeFor(item.mimeType, target.kind)
        }
        val url = when (d) {
            Delivery.ORIGINAL -> item.original ?: item.uri
            Delivery.DIRECT -> item.uri
            Delivery.PROXY -> server.urlFor(local, proxy.pathFor(item.uri))
            Delivery.SERVE -> server.urlFor(
                local,
                // The file route answers with the type this TV is told (in the address: per TV, not per file).
                if (item.type == MediaType.PHOTO) "/$t/photo/$id.jpg" else PlaybackChecks.filePath(t, id, item.title, mime),
            )
            Delivery.CONVERT -> if (convertsToHls) {
                server.urlFor(local, "/$t/hx/$id/$startMs/${convertHeightNow()}/$audio/index.m3u8")
            } else {
                server.urlFor(local, "/$t/tx/$id/$startMs/${convertHeightNow()}/$audio.mp4")
            }
        }
        // A converted video starts its clock at 0: move the subtitles along (and by the timing correction).
        val streamStart = if (d == Delivery.CONVERT) startMs else 0
        val choice = activeSubtitle(item)
        val subtitlePath = if (subtitlesReady(item)) "/$t/st/$id/${choice.key}/$streamStart/${subtitleTracks.offsetMs(item.uri)}" else null
        return RendererMedia(
            url = url,
            mimeType = mime,
            title = item.title,
            subtitle = target.name,
            live = item.live || d == Delivery.CONVERT,
            subtitleUrl = subtitlePath?.let { server.urlFor(local, "$it.vtt") },
            subtitleSrtUrl = subtitlePath?.let { server.urlFor(local, "$it.srt") },
            subtitlesOn = subtitlesOn,
            startMs = if (d == Delivery.CONVERT || item.live) 0 else startMs,
            subtitleStyle = SubtitleStyleSettings.get(context).current,
        )
    }

    /**
     * Plays item [i] from [startMs], trying each way of delivering it until the TV accepts one.
     * [resume]: a fresh start of the item, which continues where it was left last time.
     */
    private suspend fun playIndex(i: Int, startMs: Long, onlyFrom: Delivery? = null, resume: Boolean = false): Unit = playMutex.withLock {
        if (ended.get() || i !in items.indices) return
        slideshow?.cancel()
        if (i != index) {
            liveRetries = 0
            recordPosition(force = true)
        }
        index = i
        var item = current
        var start = startMs
        if (resume && onlyFrom == null && startMs == 0L) {
            // Taken over from another device: carry on where it was.
            val at = PendingStart.take(item.uri) ?: history.resumePosition(item)
            if (at != null) {
                start = at
                PlaybackHub.resumeFlow.value = ResumeNotice(item.uri, at)
            } else {
                PlaybackHub.resumeFlow.value = null
            }
        } else if (PlaybackHub.resumeFlow.value?.uri != item.uri) {
            PlaybackHub.resumeFlow.value = null
        }
        started = false
        CastController.update(this) {
            baseState(CastPhase.BUFFERING).copy(
                volume = volume?.toFloat(),
                muted = muted,
                positionMs = if (item.live) 0 else start,
                positionAt = SystemClock.elapsedRealtime(),
            )
        }
        publishQueue()
        mainHandler.post { publishMediaSessionState() }

        if (item.live && item.isWeb && !item.isHls) item = runInterruptible { resolveLiveType(i) }
        proxy.headers = item.headers
        proxy.trustAll = item.trustAll
        if (item.isHls && item.isWeb) {
            variants = variantCache[item.uri] ?: runInterruptible { loadVariants(item) }.also { if (it.isNotEmpty()) variantCache[item.uri] = it }
            proxy.variantUrl = chosenVariant()?.url
        } else {
            variants = emptyList()
            proxy.variantUrl = null
        }
        loadCachedSubtitles(item)
        refreshExtras()

        var options = plan(item)
        if (onlyFrom != null) options = options.dropWhile { it != onlyFrom }.ifEmpty { plan(item) }
        // Why the phone can't convert it, said once the other ways were tried too (one of them may play it).
        var refused: ConvertProblem? = null
        for (d in options) {
            if (ended.get()) return
            val r = renderer ?: return
            try {
                // Can the phone convert it at all? Asking the TV to wait for an empty stream helps nobody.
                val problem = if (d == Delivery.CONVERT && !item.live) convertCheck(item).problem else null
                if (problem != null) {
                    Log.i(TAG, "${item.title}: the phone can't convert it (${problem.describe()})")
                    refused = problem
                    // A read error may be passing: the converter tries anyway (it answers the TV at once if it can't).
                    if (problem != ConvertProblem.ReadFailed) continue
                }
                delivery = d
                convertStartMs = if (d == Delivery.CONVERT) start else 0
                if (d == Delivery.CONVERT) {
                    update { it.copy(phase = CastPhase.CONVERTING, converting = true).withExtras() }
                } else {
                    update { it.copy(converting = false).withExtras() }
                }
                val waiter = CompletableDeferred<Boolean>()
                outcome = waiter
                val status = r.load(mediaFor(i, d, start))
                onStatus(r, status)
                if (item.type == MediaType.PHOTO) {
                    started = true
                    skipped = 0
                    update { it.copy(phase = CastPhase.LIVE) }
                    scheduleSlideshow()
                    extras.reloadAll()
                    return
                }
                // Wait for the TV to actually start (or to report an error).
                val waitMs = if (d == Delivery.CONVERT) 45_000L else 25_000L
                val ok = withTimeoutOrNull(waitMs) { waiter.await() }
                if (ok == null) {
                    // Some TVs never report an error: silence counts as playing (shows up in the debug report).
                    Log.w(TAG, "${item.title}: ${device.kind} TV said nothing about $d within ${waitMs / 1000} s, taking it as playing")
                }
                if (ok != false) {
                    started = true
                    skipped = 0
                    refreshExtras()
                    reportStarted(item)
                    // The other screens get the same item, from where the first one is now.
                    extras.reloadAll()
                    if (d == Delivery.CONVERT && !convertPicked(item)) {
                        CastController.message(Txt.s(R.string.msg_converting, item.title))
                    }
                    if (d == Delivery.CONVERT) convertChecks[convertKey(item)]?.droppedAudioMime?.let { warnNoSound(item, it) }
                    // Converting was asked for by hand, but it plays as it is: say why.
                    PlaybackChecks.playedAsIsNote(refused, d, convertPicked(item))?.let { problem ->
                        CastController.message(Txt.s(R.string.convert_cant_played_original, item.title, problem.describe()))
                    }
                    if (settings.autoSubtitles && canCreateSubtitles(item)) createSubtitles(auto = true) else writeAhead()
                    probeTracks(item)
                    return
                }
                Log.i(TAG, "${item.title}: $d didn't play, trying the next way")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "${item.title}: $d failed: ${e.message}")
            } finally {
                outcome = null
            }
        }
        // Nothing worked for this item: skip it (the way the user was going), saying why where it's known.
        CastController.message(refused?.let { cantConvertMessage(item, it) } ?: Txt.s(R.string.err_cant_play_item, device.name, item.title))
        AppReports.failed(this, item, AutomationApi.REASON_CANNOT_PLAY)
        val nextIndex = i + direction
        if (nextIndex in items.indices && skipped < MAX_SKIPS) {
            skipped++
            scope.launch { playIndex(nextIndex, 0) }
        } else {
            finish(null)
        }
    }

    private fun convertKey(item: MediaItem, audio: Int = audioChoice[item.uri] ?: 0) = "${item.uri}|$audio"

    /** Whether the phone can convert [item] with the chosen sound track (read once per file; also its length). */
    private suspend fun convertCheck(item: MediaItem): ConvertCheck = runInterruptible { probeConvert(item, audioChoice[item.uri] ?: 0) }

    /** [convertCheck] for sound track [audio], on this thread (it reads the file's header the first time). */
    private fun probeConvert(item: MediaItem, audio: Int): ConvertCheck {
        val key = convertKey(item, audio)
        convertChecks[key]?.let { return it }
        val fileType = ConvertChecks.fileType(item.uri) ?: ConvertChecks.fileType(item.title)
        val check = Transcoder.probe(context, inputFor(item), audio, fileType)
        // A read error may be a passing network problem: ask again next time.
        if (check.problem != ConvertProblem.ReadFailed) convertChecks[key] = check
        // Unknown (0) leaves the TV's own length or a later reading.
        if (check.durationMs > 0) durationCache.putIfAbsent(item.uri, check.durationMs)
        return check
    }

    private fun cantConvertMessage(item: MediaItem, problem: ConvertProblem): String = Txt.s(
        when {
            device.isCastAppTv -> R.string.convert_cant_castapp
            device.isDlna -> R.string.convert_cant_dlna
            else -> R.string.convert_cant
        },
        item.title, problem.describe(),
    )

    /** The converted video has no sound: say so once per file. */
    private fun warnNoSound(item: MediaItem, mime: String) {
        if (noSoundWarned.add(item.uri)) CastController.message(Txt.s(R.string.convert_no_sound, ConvertChecks.codecName(mime)))
    }

    /**
     * A Chromecast playing the file as it is plays its DTS / TrueHD sound track: no sound. Say so once per
     * file. Not on the playing thread: it may read the file (can the phone convert another track?).
     */
    private fun warnCastSound(item: MediaItem, tracks: TrackList) {
        fun stillPlaying() = !ended.get() && started && current.uri == item.uri
        if (!stillPlaying() || item.uri in castSoundWarned) return
        val tv = device
        val warning = PlaybackChecks.castSoundWarning(tv.kind, delivery, tracks.audio) { track ->
            // Picking that track converts on the phone: only worth it if the phone decodes it (picture and sound).
            val check = try { probeConvert(item, track.ordinal) } catch (_: Exception) { null }
            check != null && check.problem == null && check.droppedAudioMime == null
        } ?: return
        if (!stillPlaying() || !castSoundWarned.add(item.uri)) return
        val text = if (warning.otherTrack) R.string.cast_sound_pick_other else R.string.cast_sound_elsewhere
        CastController.message(Txt.s(text, tv.name, warning.codec))
    }

    private fun scheduleSlideshow() {
        slideshow?.cancel()
        val following = queue.nextIndex(auto = false) ?: return
        if (slideshowPaused || following == index || items.getOrNull(following)?.type != MediaType.PHOTO) return
        slideshow = scope.launch {
            delay(settings.slideshowSeconds * 1000L)
            next()
        }
    }

    // ---- controls -------------------------------------------------------------------------------

    fun togglePlay() {
        val s = CastController.state.value as? CastState.Active ?: return
        if (s.isPhoto) {
            slideshowPaused = !slideshowPaused
            update { it.copy(paused = slideshowPaused) }
            if (slideshowPaused) slideshow?.cancel() else scheduleSlideshow()
            return
        }
        if (s.paused) play() else pause()
    }

    fun play() {
        jumpTimeline(playing = true)
        control { it.play() }
    }

    fun pause() {
        jumpTimeline(playing = false)
        control { it.pause() }
    }

    /** Play, pause and seek from here move the shared timeline at once (the first TV confirms later). */
    private fun jumpTimeline(playing: Boolean, positionMs: Long? = null) {
        val s = CastController.state.value as? CastState.Active ?: return
        val now = SystemClock.elapsedRealtime()
        // A moment ahead, so every receiver can be there at the same time.
        timeline.jump(positionMs ?: s.positionNow(now), playing, now + if (playing) SYNC_LEAD_MS else 0)
    }

    fun seekTo(positionMs: Long) {
        if (current.live) return
        val target = positionMs.coerceAtLeast(0)
        jumpTimeline((CastController.state.value as? CastState.Active)?.paused != true, target)
        update { it.copy(positionMs = target, positionAt = SystemClock.elapsedRealtime()) }
        if (delivery == Delivery.CONVERT) {
            // A converted stream can't seek on the TV: convert again from the new position.
            scope.launch { playIndex(index, target, onlyFrom = Delivery.CONVERT) }
        } else {
            control { it.seek(target) }
        }
    }

    fun seekBy(deltaMs: Long) {
        if (current.live) return
        val s = CastController.state.value as? CastState.Active ?: return
        val now = s.positionNow(SystemClock.elapsedRealtime())
        val target = (now + deltaMs).let { if (s.durationMs > 0) it.coerceAtMost(s.durationMs - 1000) else it }
        seekTo(target)
    }

    fun next() {
        direction = 1
        queue.nextIndex(auto = false)?.let { n -> scope.launch { playIndex(n, 0, resume = true) } }
    }

    fun previous() {
        direction = -1
        val s = CastController.state.value as? CastState.Active
        if (s != null && !s.isPhoto && !current.live && s.positionNow(SystemClock.elapsedRealtime()) > 5000) seekTo(0)
        else queue.previousIndex()?.let { p -> scope.launch { playIndex(p, 0, resume = true) } }
    }

    /** Jumps to item [i] of the play list (e.g. a TV channel picked from the list). */
    fun playAt(i: Int) {
        direction = 1
        if (i in items.indices) scope.launch { playIndex(i, 0, resume = true) }
    }

    fun toggleSubtitles() {
        subtitlesOn = !subtitlesOn
        update { it.copy(subtitlesOn = subtitlesOn) }
        control { it.setSubtitlesVisible(subtitlesOn) }
    }

    /** "Start over" after a resume. */
    fun startOver() {
        PlaybackHub.resumeFlow.value = null
        seekTo(0)
    }

    // ---- editing the queue ------------------------------------------------------------------------

    /** Plays the queue entry [id] now. */
    fun queueJump(id: Long) {
        direction = 1
        val i = queue.indexOf(id)
        if (i >= 0) scope.launch { playIndex(i, 0, resume = true) }
    }

    fun queueMove(id: Long, delta: Int) {
        if (queue.moveBy(id, delta)) onQueueChanged()
    }

    fun queueRemove(id: Long) {
        if (id == queue.currentEntryId) recordPosition(force = true)
        when (val r = queue.remove(id)) {
            PlayQueue.Removal.None -> Unit
            PlayQueue.Removal.Other -> onQueueChanged()
            is PlayQueue.Removal.Current -> if (r.index < 0) {
                finish(context.getString(R.string.queue_empty))
            } else {
                onQueueChanged()
                scope.launch { playIndex(r.index, 0, resume = true) }
            }
        }
    }

    /** Adds files (or web videos) at the end of the queue. */
    fun queueAdd(newItems: List<MediaItem>) {
        if (newItems.isEmpty()) return
        queue.add(newItems)
        onQueueChanged()
    }

    fun setShuffle(on: Boolean) {
        queue.setShuffle(on)
        onQueueChanged()
    }

    fun cycleRepeat() {
        queue.repeat = queue.repeat.cycle()
        onQueueChanged()
    }

    private fun onQueueChanged() {
        update { it.copy(queueIndex = index, queueSize = items.size) }
        publishQueue()
        mainHandler.post { publishMediaSessionState() }
        if (current.type == MediaType.PHOTO && started) scheduleSlideshow()
        writeAhead()
    }

    private fun publishQueue() {
        val entries = queue.entries
        PlaybackHub.queueFlow.value = QueueView(
            entries = entries.map { QueueEntryView(it.id, it.item.title, it.item.type, it.item.live) },
            currentId = queue.currentEntryId,
            repeat = queue.repeat,
            shuffled = queue.shuffled,
            editable = entries.none { it.item.live },
        )
    }

    // ---- subtitle and sound tracks ----------------------------------------------------------------

    /** The subtitle sources this file has (or can have). */
    private fun subtitleChoices(item: MediaItem): List<SubtitleChoice> = buildList {
        item.subtitleFiles.indices.forEach { add(SubtitleChoice.file(it)) }
        trackCache[item.uri]?.textSupported?.forEach { add(SubtitleChoice.embedded(it.ordinal)) }
        if (subtitleTracks.hasOnline(item.uri)) add(SubtitleChoice.ONLINE)
        if (!generated[item.uri].isNullOrEmpty()) add(SubtitleChoice.WRITTEN)
    }

    private fun activeSubtitle(item: MediaItem): SubtitleChoice = subtitleTracks.effective(item.uri, subtitleChoices(item), item.subtitleFiles)

    /** Subtitles go to the TV with this file (the file's own are read when the TV asks). */
    private fun subtitlesReady(item: MediaItem): Boolean {
        val choice = activeSubtitle(item)
        return when (choice.kind) {
            SubtitleKind.OFF -> false
            SubtitleKind.FILE -> true
            else -> subtitleTracks.cuesFor(item.uri, choice, generated[item.uri], item.subtitleFiles) != null
        }
    }

    /** Picks the subtitles by [key] (see [SubtitleChoice.key]) and reloads the TV where it is. */
    fun chooseSubtitles(key: String) {
        val choice = SubtitleChoice.fromKey(key) ?: return
        val item = current
        subtitleTracks.choose(item.uri, choice, item.subtitleFiles)
        when {
            choice.kind == SubtitleKind.EMBEDDED && subtitleTracks.embeddedCues(item.uri, choice.track) == null -> {
                extractEmbedded(item, choice.track)
                refreshExtras()
                return
            }
            choice.kind == SubtitleKind.WRITTEN && generated[item.uri].isNullOrEmpty() -> {
                createSubtitles()
                refreshExtras()
                return
            }
        }
        if (choice != SubtitleChoice.OFF && !subtitlesOn) {
            subtitlesOn = true
            update { it.copy(subtitlesOn = true) }
        }
        refreshExtras()
        scope.launch { reloadForSubtitles(item) }
    }

    /**
     * Subtitles found online, or from any other source, for the file playing now:
     * they become one more choice of the subtitle picker and are shown right away.
     */
    fun showOnlineSubtitles(text: String, label: String = context.getString(R.string.subs_online)) {
        val item = current
        val cues = CueParser.parse(text)
        if (cues.isEmpty()) return
        subtitleTracks.setOnline(item.uri, label, cues)
        chooseSubtitles(SubtitleChoice.ONLINE.key)
    }

    /** Moves the subtitles [deltaMs] later (positive) or earlier; the TV reloads shortly after the last tap. */
    fun shiftSubtitles(deltaMs: Long) {
        val item = current
        subtitleTracks.adjustOffset(item.uri, deltaMs)
        refreshExtras()
        offsetReload?.cancel()
        offsetReload = scope.launch {
            delay(1200)
            reloadForSubtitles(item)
        }
    }

    /** Plays sound track [ordinal]; anything but the first is converted on the phone. */
    fun chooseAudioTrack(ordinal: Int) {
        val item = current
        if (item.type != MediaType.VIDEO || item.live || ended.get()) return
        if ((audioChoice[item.uri] ?: 0) == ordinal) return
        if (ordinal <= 0) audioChoice.remove(item.uri) else audioChoice[item.uri] = ordinal
        val s = CastController.state.value as? CastState.Active ?: return
        val position = s.positionNow(SystemClock.elapsedRealtime())
        val wasPaused = s.paused
        refreshExtras()
        scope.launch {
            playIndex(index, position, onlyFrom = if (ordinal > 0) Delivery.CONVERT else null)
            if (wasPaused) pause()
        }
    }

    /** Looks for sound and subtitle tracks inside the file (in the background, once per file). */
    private fun probeTracks(item: MediaItem) {
        if (item.type != MediaType.VIDEO || item.live || item.isHls || probingFor == item.uri) return
        trackCache[item.uri]?.let { known ->
            if (item.uri !in castSoundWarned) scope.launch { warnCastSound(item, known) }
            return
        }
        probingFor = item.uri
        refreshExtras()
        scope.launch {
            val source = try { sourceFor(item) } catch (_: Exception) { null }
            val tracks = try { TrackProbe.probe(context, inputFor(item), source) } catch (_: Exception) { TrackList() }
            trackCache[item.uri] = tracks
            probingFor = null
            refreshExtras()
            warnCastSound(item, tracks)
        }
    }

    /** Reads a subtitle track out of the file, then shows it. */
    private fun extractEmbedded(item: MediaItem, track: Int) {
        extractJob?.cancel()
        extractProgress = 0
        extractJob = scope.launch {
            val job = coroutineContext[Job]
            try {
                var shown = 0
                val cues = EmbeddedSubtitles.extract(sourceFor(item), track, cancelled = { job?.isActive != true }) { p ->
                    val percent = (p * 100).toInt()
                    if (percent != shown) {
                        shown = percent
                        extractProgress = percent
                        refreshExtras()
                    }
                }
                if (job?.isActive != true) return@launch
                subtitleTracks.setEmbedded(item.uri, track, cues)
                extractProgress = -1
                refreshExtras()
                if (cues.isEmpty()) {
                    CastController.message(context.getString(R.string.subs_track_empty))
                } else if (current.uri == item.uri && subtitleTracks.chosen(item.uri) == SubtitleChoice.embedded(track)) {
                    if (!subtitlesOn) {
                        subtitlesOn = true
                        update { it.copy(subtitlesOn = true) }
                    }
                    reloadForSubtitles(item)
                }
            } catch (e: CancellationException) {
                extractProgress = -1
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reading the subtitle track failed", e)
                extractProgress = -1
                refreshExtras()
                CastController.message(context.getString(R.string.subs_track_failed))
            }
        }
    }

    private fun publishTracks(item: MediaItem) {
        val tracks = trackCache[item.uri]
        val active = activeSubtitle(item)
        val media = (item.type == MediaType.VIDEO || item.type == MediaType.AUDIO) && !item.live && !item.isHls
        val options = ArrayList<SubtitleOptionView>()
        fun add(choice: SubtitleChoice, label: String, detail: String?) {
            options += SubtitleOptionView(choice.key, label, detail, choice == active)
        }
        add(SubtitleChoice.OFF, context.getString(R.string.subs_off), null)
        // Each subtitle file by its language ("English", from "Film.English.srt"), else by what its name adds.
        val display = context.resources.configuration.locales[0]
        item.subtitleFiles.forEachIndexed { i, uri ->
            // The name it was listed or picked with: content:// addresses often carry none ("…/document/msf%3A123").
            val name = item.subtitleName(uri)
            val label = Sidecars.label(item.title, name, display) ?: context.getString(R.string.subs_file)
            add(SubtitleChoice.file(i), label, name.takeIf { it.isNotBlank() })
        }
        tracks?.textSupported?.forEach { add(SubtitleChoice.embedded(it.ordinal), it.describe(), context.getString(R.string.subs_in_file)) }
        subtitleTracks.onlineLabel(item.uri)?.takeIf { subtitleTracks.hasOnline(item.uri) }?.let { add(SubtitleChoice.ONLINE, it, context.getString(R.string.subs_online)) }
        val written = !generated[item.uri].isNullOrEmpty()
        if (written || canCreateSubtitles(item) || subtitleFor == item.uri) {
            add(
                SubtitleChoice.WRITTEN,
                context.getString(R.string.subs_written),
                when {
                    subtitleFor == item.uri -> context.getString(R.string.subs_writing, subtitleProgress.coerceAtLeast(0))
                    item.uri in generatedDone -> null
                    written -> context.getString(R.string.subs_partly_written)
                    else -> context.getString(R.string.subs_write_hint)
                },
            )
        }
        val audioPick = audioChoice[item.uri] ?: 0
        val audio = tracks?.audio.orEmpty().takeIf { it.size > 1 && item.type == MediaType.VIDEO }.orEmpty()
        PlaybackHub.tracksFlow.value = TracksView(
            subtitleOptions = if (media) options else emptyList(),
            audioOptions = audio.map { AudioOptionView(it.ordinal, it.describe(), it.ordinal == audioPick) },
            subtitlesActive = media && subtitlesReady(item),
            offsetMs = subtitleTracks.offsetMs(item.uri),
            extractProgress = extractProgress,
            probing = probingFor == item.uri,
            pictureSubtitles = tracks?.pictureSubtitles ?: 0,
            canPickSubtitles = media && options.size > 1,
        )
    }

    /** Notes how far the file playing now got, for "Continue watching". */
    private fun recordPosition(force: Boolean = false) {
        if (!started || ended.get() && !force) return
        val s = CastController.state.value as? CastState.Active ?: return
        if (s.kind != CastKind.FILE || s.isPhoto) return
        val item = current
        if (item.live) return
        val position = s.positionNow(SystemClock.elapsedRealtime())
        history.record(item, position, s.durationMs, force)
        // Plex / Jellyfin: keep the server's resume position and watched state in step.
        if (force) ServerReporting.stopped(context, item, position, s.durationMs, finished = false)
        else ServerReporting.progress(context, item, position, s.durationMs, s.paused)
        // An app that sent the queue and asked to hear how it plays (automation CAST_QUEUE).
        if (force) AppReports.stopped(this, item, position, s.durationMs, playedToEnd = false)
        else AppReports.progress(this, item, position, s.durationMs, s.paused)
    }

    /** The TV started the file: tell the server it is being watched (Plex / Jellyfin items), and an app that asked (CAST_QUEUE). */
    private fun reportStarted(item: MediaItem) {
        if (item.live) return
        val s = CastController.state.value as? CastState.Active
        val position = s?.positionNow(SystemClock.elapsedRealtime()) ?: 0
        AppReports.started(this, item, position, s?.durationMs ?: 0)
        if (item.serverRef == null) return
        ServerReporting.started(context, item, position, s?.durationMs ?: 0)
    }

    /** Sends a command to the TV, and with [allScreens] to every other screen of the cast too. */
    private fun control(allScreens: Boolean = true, action: suspend (MediaRenderer) -> Unit) {
        val r = renderer ?: return
        scope.launch {
            try { action(r) } catch (e: Exception) { Log.i(TAG, "Control failed: ${e.message}") }
        }
        if (allScreens) extras.fanOut(action)
    }

    override fun changeVolume(delta: Double) {
        val r = renderer ?: return
        scope.launch {
            try {
                val target = ((volume ?: 0.5) + delta).coerceIn(0.0, 1.0)
                volume = target
                update { it.copy(volume = target.toFloat(), muted = false) }
                mainHandler.post { volumeProvider?.currentVolume = (target * VOLUME_STEPS).toInt() }
                if (muted) r.setMuted(false)
                r.setVolume(target)
            } catch (_: Exception) {
            }
        }
    }

    private fun setVolume(level: Double) = changeVolume(level - (volume ?: level))

    override fun toggleMute() = control(allScreens = false) { it.setMuted(!muted) }

    // ---- TV events ---------------------------------------------------------------------------------

    private fun listenerFor(r: MediaRenderer) = object : MediaRenderer.Listener {
        override fun onStatus(status: RendererStatus) = onStatus(r, status)

        override fun onVolume(level: Double?, muted: Boolean) {
            if (r !== renderer) return
            level?.let { volume = it }
            this@MediaCastSession.muted = muted
            update { it.copy(volume = volume?.toFloat(), muted = muted) }
            val v = volume ?: return
            mainHandler.post { volumeProvider?.currentVolume = (v * VOLUME_STEPS).toInt() }
        }

        override fun onLost(error: Throwable?) {
            if (r === renderer && !ended.get()) scope.launch { reconnect() }
        }

        override fun onClosedOnDevice() {
            if (r === renderer && !reconnecting) primaryFailed(Txt.s(R.string.err_playback_stopped_tv))
        }
    }

    private fun onStatus(r: MediaRenderer, status: RendererStatus) {
        if (r !== renderer) return
        val now = SystemClock.elapsedRealtime()
        val waiter = outcome
        when (status.state) {
            PlayState.PLAYING -> { waiter?.complete(true); update { it.copy(phase = CastPhase.LIVE, paused = false) } }
            PlayState.PAUSED -> { waiter?.complete(true); update { it.copy(phase = CastPhase.LIVE, paused = true) } }
            PlayState.BUFFERING -> update { it.copy(phase = if (it.converting && !started) CastPhase.CONVERTING else CastPhase.BUFFERING) }
            PlayState.ERROR -> {
                if (waiter != null) waiter.complete(false)
                else if (started && current.type != MediaType.PHOTO) scope.launch { recoverFromError() }
            }
            PlayState.FINISHED -> if (started && current.type != MediaType.PHOTO && waiter == null) {
                if (!current.live) {
                    val duration = (CastController.state.value as? CastState.Active)?.durationMs ?: 0
                    history.markFinished(current, duration)
                    ServerReporting.stopped(context, current, duration, duration, finished = true)
                    AppReports.stopped(this, current, duration, duration, playedToEnd = true)
                }
                val following = queue.nextIndex(auto = true)
                when {
                    // A TV channel doesn't end: it dropped out, so tune in again.
                    current.live -> scope.launch { restartLive() }
                    SleepTimer.takeItemEnd() -> finish(context.getString(R.string.sleep_stopped))
                    following != null -> {
                        direction = 1
                        scope.launch { playIndex(following, 0, resume = following != index) }
                    }
                    else -> finish(Txt.s(R.string.err_finished))
                }
            }
            PlayState.STOPPED -> if (started && waiter == null && !reconnecting) primaryFailed(Txt.s(R.string.err_playback_stopped_tv))
            PlayState.IDLE -> Unit
        }
        // The first screen is the reference of the shared timeline.
        if (started && current.type != MediaType.PHOTO && status.positionMs != null &&
            (status.state == PlayState.PLAYING || status.state == PlayState.PAUSED)
        ) {
            val position = status.positionMs + if (delivery == Delivery.CONVERT) convertStartMs else 0
            timeline.observe(queue.idAt(index).toString(), position, status.state == PlayState.PLAYING, current.live)
        }
        val converted = delivery == Delivery.CONVERT
        update {
            it.copy(
                positionMs = status.positionMs?.let { p -> p + if (converted) convertStartMs else 0 } ?: it.positionMs,
                positionAt = if (status.positionMs != null) now else it.positionAt,
                durationMs = if (current.live) 0 else (if (converted) durationCache[current.uri] else status.durationMs) ?: it.durationMs,
            )
        }
        mainHandler.post { publishMediaSessionState() }
    }

    /** The TV stopped with an error mid-way: convert from where it stopped, or skip to the next item. */
    private suspend fun recoverFromError() {
        if (current.live) return restartLive()
        val s = CastController.state.value as? CastState.Active
        val position = s?.positionNow(SystemClock.elapsedRealtime()) ?: 0
        if (current.type == MediaType.VIDEO && !current.isHls && delivery != Delivery.CONVERT && settings.convert != ConvertMode.OFF) {
            playIndex(index, position, onlyFrom = Delivery.CONVERT)
        } else if (queue.nextIndex(auto = false)?.takeIf { it != index } != null) {
            CastController.message(Txt.s(R.string.err_cant_play_item, device.name, current.title))
            AppReports.failed(this, current, AutomationApi.REASON_CANNOT_PLAY)
            playIndex(queue.nextIndex(auto = false) ?: return, 0)
        } else {
            AppReports.failed(this, current, AutomationApi.REASON_CANNOT_PLAY)
            finish(Txt.s(R.string.err_cant_play_item, device.name, current.title))
        }
    }

    /** A TV channel stopped: tune in again a few times before giving up. */
    private suspend fun restartLive() {
        if (ended.get() || reconnecting) return
        if (liveRetries >= MAX_LIVE_RETRIES) {
            finish(Txt.s(R.string.err_stopped_sending, current.title))
            return
        }
        liveRetries++
        update { it.copy(phase = CastPhase.BUFFERING) }
        delay(1500L * liveRetries)
        playIndex(index, 0, onlyFrom = delivery)
    }

    // ---- reconnecting --------------------------------------------------------------------------------

    private suspend fun reconnect() {
        if (ended.get() || reconnecting) return
        if (handingOver || !extras.isEmpty) {
            // Another device's player took the TV, or other screens carry on: no waiting for this one.
            primaryFailed(if (handingOver) null else Txt.s(R.string.err_lost_connection, device.name))
            return
        }
        reconnecting = true
        val resumeAt = (CastController.state.value as? CastState.Active)?.positionNow(SystemClock.elapsedRealtime()) ?: 0
        val wasPaused = (CastController.state.value as? CastState.Active)?.paused == true
        update { it.copy(phase = CastPhase.RECONNECTING) }
        renderer?.close()
        val deadline = System.currentTimeMillis() + 120_000
        var attempt = 0
        while (!ended.get() && System.currentTimeMillis() < deadline) {
            attempt++
            delay(if (attempt == 1) 1500 else 4000)
            if (attempt % 3 == 0 && !device.isDlna) {
                NetworkScanner.findById(device.id)?.let { found ->
                    device = device.copy(host = found.host, port = found.port)
                    CastApp.get(context).devices.found(found)
                }
            }
            try {
                connect()
                reconnecting = false
                playIndex(index, resumeAt, onlyFrom = delivery)
                if (wasPaused) pause()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "Reconnect attempt $attempt failed: ${e.message}")
            }
        }
        reconnecting = false
        AppReports.failed(this, current, AutomationApi.REASON_CONNECTION_LOST)
        finish(Txt.s(R.string.err_lost_connection, device.name))
    }

    // ---- lock screen / notification / volume keys -------------------------------------------------

    private fun createMediaSession() {
        val session = MediaSession(context, "CastApp")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = if ((CastController.state.value as? CastState.Active)?.isPhoto == true) togglePlay() else play()
            override fun onPause() = if ((CastController.state.value as? CastState.Active)?.isPhoto == true) togglePlay() else pause()
            override fun onStop() = finish(null)
            override fun onSeekTo(pos: Long) = seekTo(pos)
            override fun onFastForward() = seekBy(FORWARD_MS)
            override fun onRewind() = seekBy(-REWIND_MS)
            override fun onSkipToNext() = next()
            override fun onSkipToPrevious() = previous()
            override fun onCustomAction(action: String, extras: Bundle?) {
                when (action) {
                    CUSTOM_REWIND -> seekBy(-REWIND_MS)
                    CUSTOM_FORWARD -> seekBy(FORWARD_MS)
                    CUSTOM_STOP -> finish(null)
                }
            }
        }, mainHandler)
        val provider = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, VOLUME_STEPS, VOLUME_STEPS / 2) {
            override fun onSetVolumeTo(volume: Int) = setVolume(volume.toDouble() / VOLUME_STEPS)
            override fun onAdjustVolume(direction: Int) {
                if (direction != 0) changeVolume(direction / VOLUME_STEPS.toDouble())
            }
        }
        session.setPlaybackToRemote(provider)
        volumeProvider = provider
        mediaSession = session
        publishMediaSessionState()
        session.isActive = true
    }

    private fun publishMediaSessionState() {
        val session = mediaSession ?: return
        val s = CastController.state.value as? CastState.Active
        val state = when {
            s == null -> PlaybackState.STATE_NONE
            s.phase == CastPhase.LIVE && s.paused -> PlaybackState.STATE_PAUSED
            s.phase == CastPhase.LIVE -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_BUFFERING
        }
        val live = current.live
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (!live) actions = actions or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
        if (items.size > 1 || queue.hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val builder = PlaybackState.Builder()
            .setActions(actions)
            .setState(state, s?.positionMs ?: 0, if (state == PlaybackState.STATE_PLAYING) 1f else 0f, s?.positionAt ?: SystemClock.elapsedRealtime())
        if (items.size <= 1 && !live) {
            builder.addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_REWIND, context.getString(R.string.action_rewind), R.drawable.ic_rewind).build())
            builder.addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_FORWARD, context.getString(R.string.action_forward), R.drawable.ic_forward).build())
        }
        builder.addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_STOP, context.getString(R.string.action_stop), R.drawable.ic_stop).build())
        session.setPlaybackState(builder.build())
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, current.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, context.getString(R.string.media_on_device, device.name))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, if (live) -1 else s?.durationMs ?: 0)
                .build(),
        )
    }

    // ---- teardown --------------------------------------------------------------------------------

    override fun finish(message: String?, stopReceiver: Boolean) {
        if (!ended.compareAndSet(false, true)) return
        // Handing over: the other device's player is on the TVs now; leave them alone and stay quiet.
        val handing = handingOver
        Thread({ teardown(if (handing) null else message, stopReceiver && !handing) }, "media-cast-teardown").start()
    }

    private fun teardown(message: String?, stopReceiver: Boolean) {
        slideshow?.cancel()
        recordPosition(force = true)
        AppReports.ended(this)
        history.flush()
        hls.stopAll()
        PlaybackHub.detach(this)
        val r = renderer
        if (stopReceiver && r != null) {
            try { runBlocking { withTimeoutOrNull(2500) { r.stop() } } } catch (_: Exception) {}
        }
        try { r?.close() } catch (_: Exception) {}
        extras.stopAll(stopReceiver)
        timeline.clear()
        scope.cancel()
        if (::server.isInitialized) try { server.stop() } catch (_: Exception) {}
        locks.release()
        CastController.end(this)
        if (message != null) CastController.message(message)
        mainHandler.post {
            mediaSession?.let {
                it.isActive = false
                it.release()
            }
            mediaSession = null
            onEnded(this)
        }
    }

    private fun update(transform: (CastState.Active) -> CastState.Active): Unit = CastController.update(this) {
        MultiScreens.withScreens(transform(it).copy(device = device, handingOver = handingOver), extras.infos(), primaryDetached = false)
    }

    // ---- several screens ------------------------------------------------------------------------

    override val screenDevices: List<CastDevice> get() = listOf(device) + extras.devices

    override fun addScreen(device: CastDevice) {
        if (ended.get() || handingOver) return
        if (MirrorFormats.sameDevice(device, this.device) || extras.contains(device)) return
        extras.add(device)
    }

    override fun removeScreen(id: String) {
        if (ended.get()) return
        if (id == device.id) {
            if (extras.isEmpty) finish(null) else promote(stopOld = true)
            return
        }
        if (extras.remove(id)) update { it }
    }

    override fun changeScreenVolume(id: String, delta: Double) {
        if (id == device.id) changeVolume(delta) else extras.changeVolume(id, delta)
    }

    override fun toggleScreenMute(id: String) {
        if (id == device.id) toggleMute() else extras.toggleMute(id)
    }

    /**
     * What an added screen plays: the item playing now, from where it is now, delivered the way
     * the first screen gets it (or the other ways that don't need converting).
     * CastApp receivers also get the shared timeline to keep in sync.
     */
    private suspend fun extraMedia(d: CastDevice, local: InetAddress): List<RendererMedia> {
        // Added while the first screen is still starting: wait for it (it decides how the item is delivered).
        withTimeoutOrNull(60_000) { while (!started && !ended.get()) delay(200) }
        if (!started || ended.get()) return emptyList()
        val i = index
        val item = items.getOrNull(i) ?: return emptyList()
        val now = SystemClock.elapsedRealtime()
        val position = (CastController.state.value as? CastState.Active)?.positionNow(now) ?: 0
        val used = delivery
        // The server's original file goes first only to CastApp receivers; others try their own ways.
        val options = PlaybackChecks.extraScreenOptions(used, plan(item, d), d)
        val sync = if (d.isCastAppTv && used != Delivery.CONVERT && item.type != MediaType.PHOTO) {
            io.github.patschgoo.castapp.multi.Peers.timelineUrl(local)
        } else {
            null
        }
        return options.mapNotNull { option ->
            try {
                mediaFor(i, option, if (option == Delivery.CONVERT || item.live) position.coerceAtLeast(0) else position, d, local)
                    .copy(syncUrl = sync)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** The first screen is gone but others play on: the next one becomes the first. */
    private fun primaryFailed(message: String?) {
        if (extras.isEmpty || ended.get()) {
            finish(message)
            return
        }
        val name = device.name
        promote(stopOld = false)
        if (message != null && !handingOver) CastController.message(Txt.s(R.string.multi_screen_left, name))
    }

    private fun promote(stopOld: Boolean) {
        val next = extras.all.firstOrNull { it.state != io.github.patschgoo.castapp.multi.ScreenState.ERROR } ?: extras.all.firstOrNull()
            ?: return finish(null)
        val s = extras.detach(next.device.id) ?: return finish(null)
        val old = renderer
        s.renderer.listener = listenerFor(s.renderer)
        renderer = s.renderer
        device = s.device
        volume = s.volume
        muted = s.muted
        update { it.copy(volume = volume?.toFloat(), muted = muted) }
        scope.launch {
            if (stopOld && !handingOver) try { withTimeoutOrNull(2500) { old?.stop() } } catch (_: Exception) {}
            try { old?.close() } catch (_: Exception) {}
            try { renderer?.refresh() } catch (_: Exception) {}
        }
    }

    private fun onExtraGone(d: CastDevice) {
        if (!handingOver && !ended.get()) CastController.message(Txt.s(R.string.multi_screen_left, d.name))
        update { it }
    }

    // ---- for other CastApp devices ---------------------------------------------------------------

    override fun joinMedia(local: InetAddress): JoinMedia? {
        if (!started || ended.get()) return null
        val i = index
        val item = items.getOrNull(i) ?: return null
        val now = SystemClock.elapsedRealtime()
        val position = (CastController.state.value as? CastState.Active)?.positionNow(now) ?: 0
        val used = delivery
        val media = try { mediaFor(i, used, position, device, local) } catch (_: Exception) { return null }
        return JoinMedia(
            url = media.url,
            mimeType = media.mimeType,
            title = media.title,
            lowLatency = false,
            live = item.live || used == Delivery.CONVERT,
            subtitleUrl = media.subtitleSrtUrl ?: media.subtitleUrl,
        )
    }

    /** A converted stream starts its clock where the conversion started: no shared position then. */
    override fun timeline(): Timeline? = timeline.current?.let { if (delivery == Delivery.CONVERT) it.copy(live = true) else it }

    override fun handOverQueue(): Pair<List<MediaItem>, Int> = items to index

    companion object {
        private const val TAG = "MediaCastSession"
        private const val VOLUME_STEPS = 20
        private const val MAX_SKIPS = 10
        private const val MAX_LIVE_RETRIES = 4
        private const val MAX_PHOTO_BYTES = 80 * 1024 * 1024
        private const val MAX_SUBTITLE_BYTES = 8 * 1024 * 1024
        /** How many of the next files get subtitles written ahead. */
        private const val WRITE_AHEAD = 2
        private const val DOWNLOADED_KEY = "downloaded"
        const val ORIGINAL = "Original"
        const val AUTO = "Auto"
        val FILE_QUALITIES = listOf(ORIGINAL, "1080p", "720p", "480p")
        const val REWIND_MS = 10_000L
        const val FORWARD_MS = 30_000L
        const val CUSTOM_REWIND = "rewind10"
        const val CUSTOM_FORWARD = "forward30"
        const val CUSTOM_STOP = "stop"
        /** Play/seek from this phone: the shared timeline moves this far ahead, so all receivers start together. */
        private const val SYNC_LEAD_MS = 300L
    }
}

/** Reads a content:// file from any offset (seeking with the file channel when possible). */
class ContentMediaSource(private val resolver: ContentResolver, private val uri: Uri) : MediaSource {
    override val length: Long by lazy {
        val fromProvider = try {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
        if (fromProvider > 0) fromProvider else {
            try {
                resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            } catch (_: Exception) {
                -1L
            }
        }
    }

    override fun open(offset: Long): InputStream {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Can't open $uri")
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        if (offset > 0) {
            try {
                stream.channel.position(offset)
            } catch (_: Exception) {
                var left = offset
                while (left > 0) {
                    val skipped = stream.skip(left)
                    if (skipped <= 0) {
                        if (stream.read() < 0) break
                        left--
                    } else {
                        left -= skipped
                    }
                }
            }
        }
        return stream
    }
}
