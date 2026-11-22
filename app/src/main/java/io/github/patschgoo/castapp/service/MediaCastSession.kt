package io.github.patschgoo.castapp.service

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.capture.Transcoder
import io.github.patschgoo.castapp.cast.CastException
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.NetworkScanner
import io.github.patschgoo.castapp.render.MediaRenderer
import io.github.patschgoo.castapp.render.PlayState
import io.github.patschgoo.castapp.render.RendererMedia
import io.github.patschgoo.castapp.render.RendererStatus
import io.github.patschgoo.castapp.settings.CastSettings
import io.github.patschgoo.castapp.settings.ConvertMode
import io.github.patschgoo.castapp.stream.FragmentedMp4
import io.github.patschgoo.castapp.stream.InterleavingQueue
import io.github.patschgoo.castapp.stream.MediaServer
import io.github.patschgoo.castapp.stream.MediaSource
import io.github.patschgoo.castapp.stream.Mp4FragmentSerializer
import io.github.patschgoo.castapp.stream.Subtitles
import io.github.patschgoo.castapp.stream.WebProxy
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays a list of videos, music, photos or web videos on a TV. The TV fetches
 * everything itself (from the phone or the web), so the phone's screen can be off.
 *
 *  - Web videos are tried directly first, then through the phone (cookies, CORS).
 *  - Videos the TV can't play are converted on the phone (setting "Convert videos").
 *  - Photos are shrunk to TV size and shown as a slideshow.
 *  - Reconnects by itself if the Wi-Fi drops or the TV restarts.
 */
class MediaCastSession(
    private val context: Context,
    device: CastDevice,
    private val items: List<MediaItem>,
    startIndex: Int,
    private val settingsFlow: StateFlow<CastSettings>,
    private val onEnded: (ActiveCast) -> Unit,
) : ActiveCast {
    @Volatile override var device: CastDevice = device
        private set

    private enum class Delivery { DIRECT, PROXY, SERVE, CONVERT }

    private val settings get() = settingsFlow.value
    private val resolver: ContentResolver = context.contentResolver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ended = AtomicBoolean(false)
    private val playMutex = Mutex()
    private val locks = CastLocks(context, "CastApp:media")

    @Volatile private var renderer: MediaRenderer? = null
    private lateinit var server: MediaServer
    private lateinit var proxy: WebProxy
    private val photoCache = ConcurrentHashMap<Int, ByteArray>()
    private val durationCache = ConcurrentHashMap<Int, Long>()

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
        hasSubtitles = current.subtitleUri != null,
        subtitlesOn = subtitlesOn,
        audio = true,
    )

    private suspend fun run() {
        try {
            locks.acquire()
            server = MediaServer(settings.port)
            proxy = WebProxy(server)
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

    // ---- what the phone serves ----------------------------------------------------------

    private fun registerRoutes() {
        items.forEachIndexed { i, item ->
            if (!item.isWeb) {
                when (item.type) {
                    MediaType.PHOTO -> server.addRoute("photo/$i.jpg", server.bytes("image/jpeg") { photo(i) })
                    else -> server.addRoute(
                        "file/$i/" + MediaServer.safeName(item.title),
                        server.ranged(ContentMediaSource(resolver, Uri.parse(item.uri)), CastService.normalizeMime(item.mimeType)),
                    )
                }
            }
            if (item.subtitleUri != null) {
                server.addRoute("sub/$i.vtt", server.bytes("text/vtt") { subtitleText(item)?.let { Subtitles.toWebVtt(it).toByteArray() } })
                server.addRoute("sub/$i.srt", server.bytes("application/x-subrip") { subtitleText(item)?.toByteArray() })
            }
        }
        // Converted videos: /tx/<index>/<startMs>.mp4 (a new conversion per request).
        server.addPrefixRoute("tx/") { request, socket, out ->
            val parts = request.path.substringAfter("/tx/").removeSuffix(".mp4").split('/')
            val i = parts.getOrNull(0)?.toIntOrNull()
            val startMs = parts.getOrNull(1)?.toLongOrNull() ?: 0
            val item = i?.let { items.getOrNull(it) }
            if (item == null) {
                server.writeResponseHead(out, 404, "Not Found", "text/plain", 0, emptyMap())
                return@addPrefixRoute
            }
            serveConverted(item, startMs, request, socket, out)
        }
    }

    private fun photo(i: Int): ByteArray? = photoCache[i] ?: MediaItem.loadPhotoForTv(resolver, Uri.parse(items[i].uri))?.also {
        if (photoCache.size > 4) photoCache.clear()
        photoCache[i] = it
    }

    private fun subtitleText(item: MediaItem): String? {
        val uri = item.subtitleUri ?: return null
        val bytes = if (uri.startsWith("http")) {
            val conn = URL(uri).openConnection() as HttpURLConnection
            item.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            try { conn.inputStream.use { it.readBytes() } } finally { conn.disconnect() }
        } else {
            resolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() } ?: return null
        }
        return Subtitles.decode(bytes)
    }

    private fun serveConverted(item: MediaItem, startMs: Long, request: io.github.patschgoo.castapp.stream.TokenHttpServer.Request, socket: java.net.Socket, out: java.io.OutputStream) {
        val queue = InterleavingQueue(setOf(FragmentedMp4.VIDEO_TRACK_ID, FragmentedMp4.AUDIO_TRACK_ID))
        val transcoder = Transcoder(context, Uri.parse(item.uri), item.headers, startMs * 1000, queue)
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

    // ---- playing ------------------------------------------------------------------------------

    private fun plan(item: MediaItem): List<Delivery> {
        val convert = settings.convert
        return when {
            item.type == MediaType.PHOTO -> if (item.isWeb) listOf(Delivery.DIRECT, Delivery.PROXY) else listOf(Delivery.SERVE)
            item.isWeb && item.isHls -> listOf(Delivery.PROXY, Delivery.DIRECT)
            item.isWeb && item.type == MediaType.AUDIO -> listOf(Delivery.DIRECT, Delivery.PROXY)
            item.isWeb -> when (convert) {
                ConvertMode.ALWAYS -> listOf(Delivery.CONVERT)
                ConvertMode.OFF -> listOf(Delivery.DIRECT, Delivery.PROXY)
                ConvertMode.AUTO -> listOf(Delivery.DIRECT, Delivery.PROXY, Delivery.CONVERT)
            }
            item.type == MediaType.AUDIO -> listOf(Delivery.SERVE)
            else -> when (convert) {
                ConvertMode.ALWAYS -> listOf(Delivery.CONVERT)
                ConvertMode.OFF -> listOf(Delivery.SERVE)
                ConvertMode.AUTO -> listOf(Delivery.SERVE, Delivery.CONVERT)
            }
        }
    }

    private fun mediaFor(i: Int, d: Delivery, startMs: Long): RendererMedia {
        val item = items[i]
        val local = renderer?.localAddress ?: throw CastException("No network path to the TV")
        val url = when (d) {
            Delivery.DIRECT -> item.uri
            Delivery.PROXY -> server.urlFor(local, proxy.pathFor(item.uri))
            Delivery.SERVE -> server.urlFor(
                local,
                if (item.type == MediaType.PHOTO) "/${server.token}/photo/$i.jpg" else "/${server.token}/file/$i/" + MediaServer.safeName(item.title),
            )
            Delivery.CONVERT -> server.urlFor(local, "/${server.token}/tx/$i/$startMs.mp4")
        }
        val mime = when {
            d == Delivery.CONVERT -> "video/mp4"
            item.type == MediaType.PHOTO && d == Delivery.SERVE -> "image/jpeg"
            item.isHls -> "application/x-mpegurl"
            else -> CastService.normalizeMime(item.mimeType)
        }
        val hasSubs = item.subtitleUri != null
        return RendererMedia(
            url = url,
            mimeType = mime,
            title = item.title,
            subtitle = device.name,
            live = d == Delivery.CONVERT,
            subtitleUrl = if (hasSubs) server.urlFor(local, "/${server.token}/sub/$i.vtt") else null,
            subtitleSrtUrl = if (hasSubs && item.subtitleUri.lowercase().substringBefore('?').endsWith(".srt")) {
                server.urlFor(local, "/${server.token}/sub/$i.srt")
            } else null,
            subtitlesOn = subtitlesOn,
            startMs = if (d == Delivery.CONVERT) 0 else startMs,
        )
    }

    /** Plays item [i] from [startMs], trying each way of delivering it until the TV accepts one. */
    private suspend fun playIndex(i: Int, startMs: Long, onlyFrom: Delivery? = null): Unit = playMutex.withLock {
        if (ended.get()) return
        slideshow?.cancel()
        index = i
        val item = current
        proxy.headers = item.headers
        started = false
        CastController.update(this) {
            baseState(CastPhase.BUFFERING).copy(volume = volume?.toFloat(), muted = muted, positionMs = startMs, positionAt = SystemClock.elapsedRealtime())
        }
        mainHandler.post { publishMediaSessionState() }

        var options = plan(item)
        if (onlyFrom != null) options = options.dropWhile { it != onlyFrom }
        for (d in options) {
            if (ended.get()) return
            val r = renderer ?: return
            try {
                delivery = d
                convertStartMs = if (d == Delivery.CONVERT) startMs else 0
                if (d == Delivery.CONVERT) {
                    durationCache.getOrPut(i) { Transcoder.probeDurationMs(context, Uri.parse(item.uri), item.headers) }
                    update { it.copy(phase = CastPhase.CONVERTING, converting = true) }
                }
                val waiter = CompletableDeferred<Boolean>()
                outcome = waiter
                val status = r.load(mediaFor(i, d, startMs))
                onStatus(r, status)
                if (item.type == MediaType.PHOTO) {
                    started = true
                    update { it.copy(phase = CastPhase.LIVE) }
                    scheduleSlideshow()
                    return
                }
                // Wait for the TV to actually start (or to report an error).
                val ok = withTimeoutOrNull(if (d == Delivery.CONVERT) 45_000 else 25_000) { waiter.await() }
                if (ok != false) {
                    started = true
                    if (d == Delivery.CONVERT) CastController.message("Converting \"${item.title}\" on the phone so the TV can play it")
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
        // Nothing worked for this item.
        CastController.message("${device.name} couldn't play \"${item.title}\"")
        if (i + 1 < items.size) scope.launch { playIndex(i + 1, 0) } else finish(null)
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
        val s = CastController.state.value as? CastState.Active ?: return
        val now = s.positionNow(SystemClock.elapsedRealtime())
        val target = (now + deltaMs).let { if (s.durationMs > 0) it.coerceAtMost(s.durationMs - 1000) else it }
        seekTo(target)
    }

    fun next() {
        if (index + 1 < items.size) scope.launch { playIndex(index + 1, 0) }
    }

    fun previous() {
        val s = CastController.state.value as? CastState.Active
        if (s != null && !s.isPhoto && s.positionNow(SystemClock.elapsedRealtime()) > 5000) seekTo(0)
        else if (index > 0) scope.launch { playIndex(index - 1, 0) }
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
                if (index + 1 < items.size) next() else finish("Finished playing")
            }
            PlayState.STOPPED -> if (started && waiter == null && !reconnecting) finish("Playback was stopped on the TV")
            PlayState.IDLE -> Unit
        }
        val converted = delivery == Delivery.CONVERT
        update {
            it.copy(
                positionMs = status.positionMs?.let { p -> p + if (converted) convertStartMs else 0 } ?: it.positionMs,
                positionAt = if (status.positionMs != null) now else it.positionAt,
                durationMs = (if (converted) durationCache[index] else status.durationMs) ?: it.durationMs,
            )
        }
        mainHandler.post { publishMediaSessionState() }
    }

    /** The TV stopped with an error mid-way: convert from where it stopped, or skip to the next item. */
    private suspend fun recoverFromError() {
        val s = CastController.state.value as? CastState.Active
        val position = s?.positionNow(SystemClock.elapsedRealtime()) ?: 0
        if (current.type == MediaType.VIDEO && delivery != Delivery.CONVERT && settings.convert != ConvertMode.OFF) {
            playIndex(index, position, onlyFrom = Delivery.CONVERT)
        } else if (index + 1 < items.size) {
            CastController.message("${device.name} couldn't play \"${current.title}\"")
            playIndex(index + 1, 0)
        } else {
            finish("${device.name} couldn't play \"${current.title}\"")
        }
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
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
        if (items.size > 1) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val builder = PlaybackState.Builder()
            .setActions(actions)
            .setState(state, s?.positionMs ?: 0, if (state == PlaybackState.STATE_PLAYING) 1f else 0f, s?.positionAt ?: SystemClock.elapsedRealtime())
        if (items.size <= 1) {
            builder.addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_REWIND, "Back 10 s", R.drawable.ic_rewind).build())
            builder.addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_FORWARD, "Forward 30 s", R.drawable.ic_forward).build())
        }
        builder.addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_STOP, "Stop", R.drawable.ic_stop).build())
        session.setPlaybackState(builder.build())
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, current.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "On ${device.name}")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, s?.durationMs ?: 0)
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
