package io.github.patschgoo.castapp.service

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
import io.github.patschgoo.castapp.capture.TranscodeInput
import io.github.patschgoo.castapp.capture.Transcoder
import io.github.patschgoo.castapp.cast.CastException
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.NetworkScanner
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
import io.github.patschgoo.castapp.subtitles.SubtitleGenerator
import io.github.patschgoo.castapp.subtitles.VoskModels
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

    private enum class Delivery { DIRECT, PROXY, SERVE, CONVERT }

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

    /** The play list; replaced as a whole when something else is cast to the same TV. */
    @Volatile private var items: List<MediaItem> = initialItems

    // Caches by item address, so they stay right when the play list is replaced.
    private val photoCache = ConcurrentHashMap<String, ByteArray>()
    private val durationCache = ConcurrentHashMap<String, Long>()
    private val variantCache = ConcurrentHashMap<String, List<HlsVariant>>()
    private val generated = ConcurrentHashMap<String, List<Cue>>()
    private val generatedDone: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Channel addresses already checked and found not to be HLS. */
    private val notHls: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile private var index = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
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

    var mediaSession: MediaSession? = null
        private set
    private var volumeProvider: VolumeProvider? = null

    private val current: MediaItem get() = items[index]

    fun start() {
        CastController.begin(this, baseState(CastPhase.CONNECTING))
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
        return copy(
            hasSubtitles = item.subtitleUri != null || !generated[item.uri].isNullOrEmpty(),
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
            playIndex(index, 0)
            // The TV reports changes only; refresh the position now and then.
            while (!ended.get()) {
                delay(5000)
                if (!reconnecting) try { renderer?.refresh() } catch (_: Exception) {}
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Media cast failed", e)
            finish(e.message ?: "Casting failed")
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
                items = newItems
                index = startIndex.coerceIn(newItems.indices)
                direction = 1
                skipped = 0
            }
            playIndex(index, 0)
        }
        return true
    }

    // ---- where the files come from ----------------------------------------------------------

    /** A file on a network share: smb://<source id>/<path inside the share>. */
    private fun smbLocation(uri: String): Pair<NetworkSource, String> {
        val rest = uri.removePrefix("smb://")
        val source = network.get(rest.substringBefore('/')) ?: throw IOException("This network share was removed from CastApp")
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
        // Paths: file/<i>/<name>, photo/<i>.jpg, sub/<i>/<offsetMs>.vtt|srt,
        // gen/<i>/<offsetMs>.vtt|srt (written subtitles), tx/<i>/<startMs>/<height>.mp4 (converted).
        server.addPrefixRoute("file/") { request, socket, out ->
            val item = itemAt(request.path, "file/")?.takeIf { !it.isWeb } ?: return@addPrefixRoute notFound(out)
            val source = try { sourceFor(item) } catch (_: IOException) { return@addPrefixRoute notFound(out) }
            server.ranged(source, CastService.normalizeMime(item.mimeType)).handle(request, socket, out)
        }
        server.addPrefixRoute("photo/") { request, socket, out ->
            val item = itemAt(request.path, "photo/") ?: return@addPrefixRoute notFound(out)
            server.bytes("image/jpeg") { photo(item) }.handle(request, socket, out)
        }
        server.addPrefixRoute("sub/") { request, socket, out ->
            val item = itemAt(request.path, "sub/") ?: return@addPrefixRoute notFound(out)
            val offset = offsetIn(request.path)
            if (request.path.endsWith(".srt")) {
                server.bytes("application/x-subrip") { subtitleText(item)?.let { Subtitles.shift(it, offset).toByteArray() } }
            } else {
                server.bytes("text/vtt") { subtitleText(item)?.let { Subtitles.shift(Subtitles.toWebVtt(it), offset).toByteArray() } }
            }.handle(request, socket, out)
        }
        server.addPrefixRoute("gen/") { request, socket, out ->
            val item = itemAt(request.path, "gen/") ?: return@addPrefixRoute notFound(out)
            val cues = generated[item.uri] ?: return@addPrefixRoute notFound(out)
            val shifted = CueBuilder.shift(cues, offsetIn(request.path) / 1000.0)
            if (request.path.endsWith(".srt")) {
                server.bytes("application/x-subrip") { CueBuilder.toSrt(shifted).toByteArray() }
            } else {
                server.bytes("text/vtt") { CueBuilder.toWebVtt(shifted).toByteArray() }
            }.handle(request, socket, out)
        }
        // A new conversion per request.
        server.addPrefixRoute("tx/") { request, socket, out ->
            val parts = request.path.substringAfter("/tx/").removeSuffix(".mp4").split('/')
            val item = parts.getOrNull(0)?.toIntOrNull()?.let { items.getOrNull(it) } ?: return@addPrefixRoute notFound(out)
            val startMs = parts.getOrNull(1)?.toLongOrNull() ?: 0
            val height = parts.getOrNull(2)?.toIntOrNull() ?: 1080
            serveConverted(item, startMs, height, request, socket, out)
        }
    }

    private fun notFound(out: OutputStream) = server.writeResponseHead(out, 404, "Not Found", "text/plain", 0, emptyMap())

    private fun itemAt(path: String, prefix: String): MediaItem? =
        path.substringAfter("/$prefix").substringBefore('/').substringBefore('.').toIntOrNull()?.let { items.getOrNull(it) }

    /** The <offsetMs> part of sub/<i>/<offsetMs>.vtt. */
    private fun offsetIn(path: String): Long = path.substringAfterLast('/').substringBefore('.').toLongOrNull() ?: 0

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

    private fun subtitleText(item: MediaItem): String? {
        val uri = item.subtitleUri ?: return null
        val bytes = when {
            uri.startsWith("http") -> HttpMediaSource(uri, item.headers, item.trustAll).open(0).use { HttpMediaSource.readUpTo(it, MAX_SUBTITLE_BYTES) }
            uri.startsWith("smb://") -> smbLocation(uri).let { (source, path) ->
                network.smb(source).open(path, 0).use { HttpMediaSource.readUpTo(it, MAX_SUBTITLE_BYTES) }
            }
            else -> resolver.openInputStream(uri.toUri())?.use { HttpMediaSource.readUpTo(it, MAX_SUBTITLE_BYTES) }
        } ?: return null
        return Subtitles.decode(bytes)
    }

    private fun serveConverted(item: MediaItem, startMs: Long, height: Int, request: TokenHttpServer.Request, socket: Socket, out: OutputStream) {
        val queue = InterleavingQueue(setOf(FragmentedMp4.VIDEO_TRACK_ID, FragmentedMp4.AUDIO_TRACK_ID))
        val transcoder = try {
            Transcoder(context, inputFor(item), startMs * 1000, queue, maxHeight = height)
        } catch (_: IOException) {
            server.writeResponseHead(out, 500, "Conversion failed", "text/plain", 0, emptyMap())
            return
        }
        transcoder.hub.subscribe(queue)
        try {
            transcoder.start()
        } catch (e: Exception) {
            Log.w(TAG, "Can't convert ${item.title}", e)
            server.writeResponseHead(out, 500, "Conversion failed", "text/plain", 0, emptyMap())
            return
        }
        try {
            server.stream("video/mp4") { body ->
                val init = transcoder.hub.awaitInitSegment(20_000) ?: return@stream
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
        items = items.toMutableList().also { if (it.getOrNull(i) == item) it[i] = resolved }
        return resolved
    }

    // ---- subtitles written by speech recognition -------------------------------------------------

    private fun subtitlesPossible(item: MediaItem) =
        (item.type == MediaType.VIDEO || item.type == MediaType.AUDIO) && !item.live && !item.isHls && item.subtitleUri == null

    private fun canCreateSubtitles(item: MediaItem) =
        subtitlesPossible(item) && item.uri !in generatedDone && subtitleFor != item.uri

    /**
     * Writes subtitles for the current file with offline speech recognition in the
     * language chosen in the settings. The TV gets them as soon as there is a good
     * stretch ahead of the viewer, and again when they are complete.
     */
    fun createSubtitles(auto: Boolean = false) {
        val item = current
        if (!canCreateSubtitles(item)) return
        if (subtitleJob?.isActive == true) {
            if (!auto) CastController.message("Still writing subtitles for another file")
            return
        }
        val lang = VoskModels.byCode(settings.subtitleLanguage)?.takeIf { VoskModels.isInstalled(context, it) }
        if (lang == null) {
            if (!auto) CastController.message("Choose and download the spoken language in Settings → Subtitles first")
            return
        }
        subtitleFor = item.uri
        subtitleProgress = 0
        refreshExtras()
        subtitleJob = scope.launch {
            var servedUntil = 0.0
            var lastReload = 0L
            try {
                val cues = SubtitleGenerator(context).generate(inputFor(item), lang) { fraction, partial ->
                    subtitleProgress = (fraction * 100).toInt().coerceIn(0, 99)
                    if (partial.isNotEmpty()) generated[item.uri] = partial
                    refreshExtras()
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
                saveCachedSubtitles(item, lang, cues)
                subtitleFor = null
                subtitleProgress = -1
                refreshExtras()
                if (cues.isEmpty()) CastController.message("No speech found in \"${item.title}\"")
                else {
                    if (!subtitlesOn) toggleSubtitles()
                    reloadForSubtitles(item)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Writing subtitles failed", e)
                if (item.uri !in generatedDone) generated.remove(item.uri)
                subtitleFor = null
                subtitleProgress = -1
                refreshExtras()
                CastController.message("Couldn't write subtitles: ${e.message ?: "unknown error"}")
            }
        }
    }

    /** Reloads the item on the TV where it is, so the TV picks up the newest subtitles. */
    private suspend fun reloadForSubtitles(item: MediaItem) {
        if (ended.get() || reconnecting || current.uri != item.uri || !started) return
        val s = CastController.state.value as? CastState.Active ?: return
        val position = s.positionNow(SystemClock.elapsedRealtime())
        playIndex(index, position, onlyFrom = delivery)
        if (s.paused) pause()
    }

    private fun cacheFile(item: MediaItem, lang: VoskModels.Language): File {
        val digest = MessageDigest.getInstance("SHA-1").digest("${item.uri}|${lang.code}".toByteArray())
        return File(File(context.filesDir, "subtitles"), digest.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) } + ".json")
    }

    private fun loadCachedSubtitles(item: MediaItem) {
        if (!subtitlesPossible(item) || item.uri in generatedDone) return
        val lang = VoskModels.byCode(settings.subtitleLanguage) ?: return
        val file = cacheFile(item, lang)
        if (!file.exists()) return
        try {
            generated[item.uri] = CueBuilder.fromJson(file.readText())
            generatedDone += item.uri
        } catch (_: Exception) {
            file.delete()
        }
    }

    private fun saveCachedSubtitles(item: MediaItem, lang: VoskModels.Language, cues: List<Cue>) {
        try {
            val file = cacheFile(item, lang)
            file.parentFile?.mkdirs()
            file.writeText(CueBuilder.toJson(cues))
        } catch (e: Exception) {
            Log.i(TAG, "Couldn't keep the subtitles: ${e.message}")
        }
    }

    // ---- playing ------------------------------------------------------------------------------

    private fun plan(item: MediaItem): List<Delivery> {
        val convert = settings.convert
        // The TV can't log in to a password-protected server or accept a self-signed certificate.
        val needsPhone = item.trustAll || item.headers.keys.any { it.equals("Authorization", ignoreCase = true) }
        val direct = if (needsPhone) emptyList() else listOf(Delivery.DIRECT)
        val base = when {
            item.type == MediaType.PHOTO -> if (item.isWeb) direct + Delivery.PROXY else listOf(Delivery.SERVE)
            item.isHls -> listOf(Delivery.PROXY) + direct
            item.isWeb && item.type == MediaType.AUDIO -> direct + Delivery.PROXY
            item.isWeb -> when (convert) {
                ConvertMode.ALWAYS -> listOf(Delivery.CONVERT)
                ConvertMode.OFF -> direct + Delivery.PROXY
                ConvertMode.AUTO -> direct + Delivery.PROXY + Delivery.CONVERT
            }
            item.type == MediaType.AUDIO -> listOf(Delivery.SERVE)
            else -> when (convert) {
                ConvertMode.ALWAYS -> listOf(Delivery.CONVERT)
                ConvertMode.OFF -> listOf(Delivery.SERVE)
                ConvertMode.AUTO -> listOf(Delivery.SERVE, Delivery.CONVERT)
            }
        }
        // A quality picked by hand: convert to it (the original is the fallback).
        return if (convertHeight > 0 && item.type == MediaType.VIDEO && !item.isHls) {
            listOf(Delivery.CONVERT) + base.filter { it != Delivery.CONVERT }
        } else {
            base
        }
    }

    private fun mediaFor(i: Int, d: Delivery, startMs: Long): RendererMedia {
        val item = items[i]
        val local = renderer?.localAddress ?: throw CastException("No network path to the TV")
        val t = server.token
        val url = when (d) {
            Delivery.DIRECT -> item.uri
            Delivery.PROXY -> server.urlFor(local, proxy.pathFor(item.uri))
            Delivery.SERVE -> server.urlFor(
                local,
                if (item.type == MediaType.PHOTO) "/$t/photo/$i.jpg" else "/$t/file/$i/" + MediaServer.safeName(item.title),
            )
            Delivery.CONVERT -> server.urlFor(local, "/$t/tx/$i/$startMs/${convertHeightNow()}.mp4")
        }
        val mime = when {
            d == Delivery.CONVERT -> "video/mp4"
            item.type == MediaType.PHOTO -> if (d == Delivery.SERVE) "image/jpeg" else item.mimeType
            item.isHls -> "application/x-mpegurl"
            else -> CastService.normalizeMime(item.mimeType)
        }
        // A converted video starts its clock at 0: move the subtitles along.
        val offset = if (d == Delivery.CONVERT) startMs else 0
        val ownSubs = item.subtitleUri != null
        val written = !ownSubs && !generated[item.uri].isNullOrEmpty()
        val vtt = when {
            ownSubs -> server.urlFor(local, "/$t/sub/$i/$offset.vtt")
            written -> server.urlFor(local, "/$t/gen/$i/$offset.vtt")
            else -> null
        }
        val srt = when {
            ownSubs && item.subtitleUri.lowercase().substringBefore('?').endsWith(".srt") -> server.urlFor(local, "/$t/sub/$i/$offset.srt")
            written -> server.urlFor(local, "/$t/gen/$i/$offset.srt")
            else -> null
        }
        return RendererMedia(
            url = url,
            mimeType = mime,
            title = item.title,
            subtitle = device.name,
            live = item.live || d == Delivery.CONVERT,
            subtitleUrl = vtt,
            subtitleSrtUrl = srt,
            subtitlesOn = subtitlesOn,
            startMs = if (d == Delivery.CONVERT || item.live) 0 else startMs,
        )
    }

    /** Plays item [i] from [startMs], trying each way of delivering it until the TV accepts one. */
    private suspend fun playIndex(i: Int, startMs: Long, onlyFrom: Delivery? = null): Unit = playMutex.withLock {
        if (ended.get() || i !in items.indices) return
        slideshow?.cancel()
        if (i != index) liveRetries = 0
        index = i
        var item = current
        started = false
        CastController.update(this) {
            baseState(CastPhase.BUFFERING).copy(
                volume = volume?.toFloat(),
                muted = muted,
                positionMs = if (item.live) 0 else startMs,
                positionAt = SystemClock.elapsedRealtime(),
            )
        }
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
        for (d in options) {
            if (ended.get()) return
            val r = renderer ?: return
            try {
                delivery = d
                convertStartMs = if (d == Delivery.CONVERT) startMs else 0
                if (d == Delivery.CONVERT) {
                    if (!item.live) durationCache.getOrPut(item.uri) { runInterruptible { Transcoder.probeDurationMs(context, inputFor(item)) } }
                    update { it.copy(phase = CastPhase.CONVERTING, converting = true).withExtras() }
                } else {
                    update { it.copy(converting = false).withExtras() }
                }
                val waiter = CompletableDeferred<Boolean>()
                outcome = waiter
                val status = r.load(mediaFor(i, d, startMs))
                onStatus(r, status)
                if (item.type == MediaType.PHOTO) {
                    started = true
                    skipped = 0
                    update { it.copy(phase = CastPhase.LIVE) }
                    scheduleSlideshow()
                    return
                }
                // Wait for the TV to actually start (or to report an error).
                val ok = withTimeoutOrNull(if (d == Delivery.CONVERT) 45_000 else 25_000) { waiter.await() }
                if (ok != false) {
                    started = true
                    skipped = 0
                    refreshExtras()
                    if (d == Delivery.CONVERT && convertHeight == 0) {
                        CastController.message("Converting \"${item.title}\" on the phone so the TV can play it")
                    }
                    if (settings.autoSubtitles && canCreateSubtitles(item)) createSubtitles(auto = true)
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
        // Nothing worked for this item: skip it (the way the user was going).
        CastController.message("${device.name} couldn't play \"${item.title}\"")
        val nextIndex = i + direction
        if (nextIndex in items.indices && skipped < MAX_SKIPS) {
            skipped++
            scope.launch { playIndex(nextIndex, 0) }
        } else {
            finish(null)
        }
    }

    private fun scheduleSlideshow() {
        slideshow?.cancel()
        if (slideshowPaused || index + 1 >= items.size || items[index + 1].type != MediaType.PHOTO) return
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

    fun play() = control { it.play() }

    fun pause() = control { it.pause() }

    fun seekTo(positionMs: Long) {
        if (current.live) return
        val target = positionMs.coerceAtLeast(0)
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
        if (index + 1 < items.size) scope.launch { playIndex(index + 1, 0) }
    }

    fun previous() {
        direction = -1
        val s = CastController.state.value as? CastState.Active
        if (s != null && !s.isPhoto && !current.live && s.positionNow(SystemClock.elapsedRealtime()) > 5000) seekTo(0)
        else if (index > 0) scope.launch { playIndex(index - 1, 0) }
    }

    /** Jumps to item [i] of the play list (e.g. a TV channel picked from the list). */
    fun playAt(i: Int) {
        direction = 1
        if (i in items.indices) scope.launch { playIndex(i, 0) }
    }

    fun toggleSubtitles() {
        subtitlesOn = !subtitlesOn
        update { it.copy(subtitlesOn = subtitlesOn) }
        control { it.setSubtitlesVisible(subtitlesOn) }
    }

    private fun control(action: suspend (MediaRenderer) -> Unit) {
        val r = renderer ?: return
        scope.launch {
            try { action(r) } catch (e: Exception) { Log.i(TAG, "Control failed: ${e.message}") }
        }
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

    override fun toggleMute() = control { it.setMuted(!muted) }

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
            if (r === renderer && !reconnecting) finish("Playback was stopped on the TV")
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
                when {
                    // A TV channel doesn't end: it dropped out, so tune in again.
                    current.live -> scope.launch { restartLive() }
                    index + 1 < items.size -> next()
                    else -> finish("Finished playing")
                }
            }
            PlayState.STOPPED -> if (started && waiter == null && !reconnecting) finish("Playback was stopped on the TV")
            PlayState.IDLE -> Unit
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
        } else if (index + 1 < items.size) {
            CastController.message("${device.name} couldn't play \"${current.title}\"")
            playIndex(index + 1, 0)
        } else {
            finish("${device.name} couldn't play \"${current.title}\"")
        }
    }

    /** A TV channel stopped: tune in again a few times before giving up. */
    private suspend fun restartLive() {
        if (ended.get() || reconnecting) return
        if (liveRetries >= MAX_LIVE_RETRIES) {
            finish("\"${current.title}\" stopped sending")
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
        finish("Lost the connection to ${device.name}")
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
        if (items.size > 1) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val builder = PlaybackState.Builder()
            .setActions(actions)
            .setState(state, s?.positionMs ?: 0, if (state == PlaybackState.STATE_PLAYING) 1f else 0f, s?.positionAt ?: SystemClock.elapsedRealtime())
        if (items.size <= 1 && !live) {
            builder.addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_REWIND, "Back 10 s", R.drawable.ic_rewind).build())
            builder.addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_FORWARD, "Forward 30 s", R.drawable.ic_forward).build())
        }
        builder.addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_STOP, "Stop", R.drawable.ic_stop).build())
        session.setPlaybackState(builder.build())
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, current.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "On ${device.name}")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, if (live) -1 else s?.durationMs ?: 0)
                .build(),
        )
    }

    // ---- teardown --------------------------------------------------------------------------------

    override fun finish(message: String?, stopReceiver: Boolean) {
        if (!ended.compareAndSet(false, true)) return
        Thread({ teardown(message, stopReceiver) }, "media-cast-teardown").start()
    }

    private fun teardown(message: String?, stopReceiver: Boolean) {
        slideshow?.cancel()
        val r = renderer
        if (stopReceiver && r != null) {
            try { runBlocking { withTimeoutOrNull(2500) { r.stop() } } } catch (_: Exception) {}
        }
        try { r?.close() } catch (_: Exception) {}
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

    private fun update(transform: (CastState.Active) -> CastState.Active) = CastController.update(this) {
        transform(it).copy(device = device)
    }

    companion object {
        private const val TAG = "MediaCastSession"
        private const val VOLUME_STEPS = 20
        private const val MAX_SKIPS = 10
        private const val MAX_LIVE_RETRIES = 4
        private const val MAX_PHOTO_BYTES = 80 * 1024 * 1024
        private const val MAX_SUBTITLE_BYTES = 8 * 1024 * 1024
        const val ORIGINAL = "Original"
        const val AUTO = "Auto"
        val FILE_QUALITIES = listOf(ORIGINAL, "1080p", "720p", "480p")
        const val REWIND_MS = 10_000L
        const val FORWARD_MS = 30_000L
        const val CUSTOM_REWIND = "rewind10"
        const val CUSTOM_FORWARD = "forward30"
        const val CUSTOM_STOP = "stop"
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
