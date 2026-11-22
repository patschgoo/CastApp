package io.github.patschgoo.castapp.service

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.capture.AudioEncoder
import io.github.patschgoo.castapp.capture.ScreenEncoder
import io.github.patschgoo.castapp.cast.CastClient
import io.github.patschgoo.castapp.cast.CastException
import io.github.patschgoo.castapp.cast.RunningApp
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.NetworkScanner
import io.github.patschgoo.castapp.render.DlnaRenderer
import io.github.patschgoo.castapp.render.MediaRenderer
import io.github.patschgoo.castapp.render.PlayState
import io.github.patschgoo.castapp.render.RendererMedia
import io.github.patschgoo.castapp.render.RendererStatus
import io.github.patschgoo.castapp.settings.CastSettings
import io.github.patschgoo.castapp.settings.PictureMode
import io.github.patschgoo.castapp.settings.Resolution
import io.github.patschgoo.castapp.settings.StreamMode
import io.github.patschgoo.castapp.stream.AdaptiveBitrate
import io.github.patschgoo.castapp.stream.AvcUtils
import io.github.patschgoo.castapp.stream.StreamHub
import io.github.patschgoo.castapp.stream.StreamServer
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One live casting session (screen, single app, or sound only): connects to the
 * TV, starts the encoders and the stream server, has the TV play the stream and
 * keeps watching it.
 *
 *  - Low latency: our own TV player if configured, else Google's Default Media
 *    Receiver with a progressive MP4 stream, falling back to HLS when needed.
 *  - Reconnects by itself for up to two minutes if the Wi-Fi drops or the TV
 *    restarts (and finds the TV again if it got a new IP address).
 *  - Lowers the bitrate automatically when the Wi-Fi can't keep up.
 */
class CastSession(
    private val context: Context,
    device: CastDevice,
    private val projection: MediaProjection,
    private val settingsFlow: StateFlow<CastSettings>,
    /** The user shared a single app (Android 14+), so the phone screen may be blacked out. */
    private val singleApp: Boolean,
    /** Only the phone's sound is cast (speakers, or a TV without picture). */
    private val audioOnly: Boolean,
    private val onEnded: (ActiveCast) -> Unit,
) : ActiveCast {
    @Volatile override var device: CastDevice = device
        private set

    private var settings = settingsFlow.value
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ended = AtomicBoolean(false)
    private val loadMutex = Mutex()
    private val locks = CastLocks(context, "CastApp:screen")

    @Volatile private var client: CastClient? = null
    /** Set instead of [client] for TVs that play by URL: DLNA, CastApp TV, AirPlay, Roku. */
    @Volatile private var urlRenderer: MediaRenderer? = null
    @Volatile private var app: RunningApp? = null
    @Volatile private var server: StreamServer? = null
    @Volatile private var localAddress: InetAddress? = null
    private var hub: StreamHub? = null
    private var video: ScreenEncoder? = null
    private var audio: AudioEncoder? = null

    @Volatile private var usingHls = false
    @Volatile private var usingCustomPlayer = false
    @Volatile private var loading = false
    @Volatile private var mediaSessionId = -1
    @Volatile private var playing = false
    @Volatile private var volume: Double? = null
    @Volatile private var muted = false
    @Volatile private var reconnecting = false
    private var reloads = 0
    private var watchdog: Job? = null

    fun start() {
        CastController.begin(
            this,
            CastState.Active(
                device = device,
                phase = CastPhase.CONNECTING,
                compatibilityMode = settings.streamMode == StreamMode.COMPATIBLE,
                resolution = if (audioOnly) "" else settings.resolution.label,
                canDarkScreen = singleApp && !audioOnly && Build.VERSION.SDK_INT >= 34,
                audioOnly = audioOnly,
                audio = audioOnly,
                qualityLabel = if (audioOnly) "" else settings.resolution.short,
                qualityOptions = if (audioOnly) emptyList() else Resolution.entries.map { it.short },
            ),
        )
        scope.launch { run() }
    }

    private suspend fun run() {
        try {
            locks.acquire()
            val h = StreamHub()
            hub = h
            val s = StreamServer(h, settings.port)
            s.start()
            server = s
            startCapture(h)
            // Sound sync can be changed in Settings while casting.
            scope.launch { settingsFlow.collect { h.audioOffsetUs = it.audioDelayMs * 1000L } }

            try {
                connectAndPlay(device.host, device.port)
            } catch (e: CastException) {
                throw e
            } catch (e: Exception) {
                throw CastException("Can't reach ${device.name} at ${device.host}. Is it on the same Wi-Fi?")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cast session failed", e)
            finish(e.message ?: "Casting failed")
        }
    }

    private suspend fun startCapture(h: StreamHub) {
        val wantAudio = Build.VERSION.SDK_INT >= 29 && (settings.audio || audioOnly)
        h.expectAudio(wantAudio)
        if (!audioOnly) {
            withContext(Dispatchers.Main) {
                val dpi = context.resources.displayMetrics.densityDpi
                val res = settings.resolution
                val v = ScreenEncoder(
                    context, projection, h, res.width, res.height, settings.fps, settings.effectiveBitrate, dpi,
                    fill = settings.picture == PictureMode.FILL,
                ) { e ->
                    Log.w(TAG, "Video encoder error", e)
                    finish("Screen capture stopped")
                }
                v.start(mainHandler)
                video = v
            }
            update { it.copy(bitrateKbps = settings.effectiveBitrate / 1000) }
            if (settings.adaptiveQuality) {
                h.congestionListener = AdaptiveBitrate(settings.effectiveBitrate) { bps ->
                    video?.setBitrate(bps)
                    update { it.copy(bitrateKbps = bps / 1000) }
                }
            }
        }
        var audioOn = false
        if (wantAudio) {
            val a = AudioEncoder(context, projection, h) { e ->
                Log.w(TAG, "Audio encoder error", e)
                finish("Audio capture stopped")
            }
            if (a.start()) {
                audio = a
                audioOn = true
            } else {
                h.expectAudio(false)
            }
        }
        if (audioOnly && !audioOn) {
            throw CastException(
                "Couldn't capture the phone's sound. Allow CastApp to record audio, and note that some apps block it.",
            )
        }
        update { it.copy(audio = audioOn) }
    }

    // ---- connecting and loading ------------------------------------------------------

    /** Connects to the TV at [host] and starts playback. Throws if anything fails. */
    private suspend fun connectAndPlay(host: String, port: Int) {
        if (device.isUrlRenderer) return connectUrlRenderer()
        val c = CastClient(host, port)
        c.listener = listenerFor(c)
        try {
            c.connect(6000)
        } catch (e: Exception) {
            c.close()
            throw e
        }
        client = c
        localAddress = c.localAddress ?: throw CastException("No network path to the TV")
        update { it.copy(phase = CastPhase.STARTING) }

        val h = hub ?: return
        if (audioOnly) {
            h.awaitAudioConfig(8_000) ?: throw CastException("Sound capture did not start")
        } else {
            // Capture is running and the track layout is fixed (video-only if audio never starts).
            h.awaitInitSegment(8_000) ?: throw CastException("Screen capture did not start")
        }

        if (!audioOnly && settings.receiverAppId.isNotBlank() && settings.streamMode == StreamMode.LOW_LATENCY) {
            if (tryCustomPlayer(c, settings.receiverAppId.trim())) return
        }
        val a = c.launch(CastClient.DEFAULT_MEDIA_RECEIVER)
        app = a
        try { readVolume(c.getReceiverStatus()) } catch (_: Exception) {}
        when {
            audioOnly -> loadAudio()
            settings.streamMode == StreamMode.COMPATIBLE || usingHls -> loadHls()
            else -> loadMp4()
        }
    }

    /**
     * TVs that play by URL. DLNA TVs get a live MPEG-TS stream, CastApp TV the
     * low-latency MP4 stream, AirPlay and Roku devices HLS (sound only: AAC).
     */
    private suspend fun connectUrlRenderer() {
        val r = MediaRenderer.create(device)
        r.listener = object : MediaRenderer.Listener {
            override fun onStatus(status: RendererStatus) {
                if (r !== urlRenderer) return
                when (status.state) {
                    PlayState.PLAYING -> { playing = true; update { it.copy(phase = CastPhase.LIVE) } }
                    PlayState.BUFFERING -> if (!playing) update { it.copy(phase = CastPhase.BUFFERING) }
                    PlayState.STOPPED, PlayState.FINISHED, PlayState.ERROR -> if (playing && !reconnecting) finish("Casting was stopped on the TV")
                    else -> Unit
                }
            }
            override fun onVolume(level: Double?, muted: Boolean) {
                if (r !== urlRenderer) return
                level?.let { volume = it }
                this@CastSession.muted = muted
                update { it.copy(volume = volume?.toFloat(), muted = muted) }
            }
            override fun onLost(error: Throwable?) {
                if (r === urlRenderer && !ended.get()) scope.launch { reconnect() }
            }
            override fun onClosedOnDevice() {
                if (r === urlRenderer) finish("Casting was stopped on the TV")
            }
        }
        r.connect()
        urlRenderer = r
        localAddress = r.localAddress ?: throw CastException("No network path to the TV")
        update { it.copy(phase = CastPhase.STARTING) }
        val h = hub ?: return
        val s = server ?: return
        if (audioOnly) {
            h.awaitAudioConfig(8_000) ?: throw CastException("Sound capture did not start")
        } else {
            h.awaitInitSegment(8_000) ?: throw CastException("Screen capture did not start")
        }
        playing = false
        val local = localAddress!!
        r.load(mirrorMedia(s, local))
        update { it.copy(phase = CastPhase.BUFFERING) }
    }

    /** What a URL-playing TV gets for the screen (or the phone's sound). */
    private suspend fun mirrorMedia(s: StreamServer, local: java.net.InetAddress): RendererMedia = when {
        audioOnly -> RendererMedia(s.urlFor(local, s.audioPath), "audio/aac", "Phone sound", device.name, live = true, lowLatency = device.isCastAppTv)
        device.isCastAppTv -> RendererMedia(s.urlFor(local, s.mp4Path), "video/mp4", TITLE, device.name, live = true, lowLatency = true)
        device.isDlna -> RendererMedia(s.urlFor(local, s.tsPath), "video/mp2t", TITLE, device.name, live = true)
        else -> {
            // AirPlay and Roku players want HLS; give the segmenter a few segments first.
            val segmenter = s.enableHls()
            usingHls = true
            withTimeoutOrNull(12_000) { while (segmenter.completedSegments < 3) delay(100) }
            RendererMedia(s.urlFor(local, s.hlsPath), "application/x-mpegurl", TITLE, device.name, live = true)
        }
    }

    /** Our own low-latency TV player (see receiver/index.html). Returns false to fall back. */
    private suspend fun tryCustomPlayer(c: CastClient, appId: String): Boolean {
        val s = server ?: return false
        val h = hub ?: return false
        val local = localAddress ?: return false
        return try {
            val a = c.launch(appId, timeoutMs = 20_000)
            app = a
            try { readVolume(c.getReceiverStatus()) } catch (_: Exception) {}
            val codecs = buildString {
                append(h.videoConfig?.sps?.let { AvcUtils.codecString(it) } ?: "avc1.42E01F")
                if (h.publishedAudioRate != null) append(",mp4a.40.2")
            }
            val reply = c.requestCustom(
                a, NS_PLAYER,
                JSONObject().put("type", "PLAY").put("url", s.urlFor(local, s.mp4Path)).put("codecs", codecs),
                timeoutMs = 15_000,
            )
            if (reply.optString("state") == "error") throw CastException(reply.optString("message", "player error"))
            usingCustomPlayer = true
            playing = true
            update { it.copy(phase = CastPhase.LIVE, lowLatencyPlayer = true, compatibilityMode = false) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Low-latency TV player not available ($appId): ${e.message}")
            CastController.message("Low-latency TV player didn't start, using the standard player")
            usingCustomPlayer = false
            false
        }
    }

    private suspend fun loadMp4(): Unit = loadMutex.withLock {
        val c = client ?: return
        val a = app ?: return
        val s = server ?: return
        val local = localAddress ?: return
        usingHls = false
        update { it.copy(phase = CastPhase.BUFFERING, compatibilityMode = false) }
        val media = CastClient.liveMedia(s.urlFor(local, s.mp4Path), hls = false, title = TITLE, subtitle = device.name)
        val ok = loadMedia(c, a, media)
        if (!ok) {
            scope.launch { fallbackToHls("load failed") }
            return
        }
        // If the TV hasn't started playing after a while, it probably can't handle the stream.
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(MP4_START_TIMEOUT_MS)
            // A connected stream client means the TV is at least trying; give it a bit longer.
            if (!playing && (server?.activeStreamClients ?: 0) > 0) delay(MP4_START_TIMEOUT_MS)
            if (!playing && !usingHls && !ended.get() && !reconnecting) fallbackToHls("no playback")
        }
    }

    private suspend fun loadHls(): Unit = loadMutex.withLock {
        val c = client ?: return
        val a = app ?: return
        val s = server ?: return
        val local = localAddress ?: return
        usingHls = true
        watchdog?.cancel()
        update { it.copy(phase = CastPhase.BUFFERING, compatibilityMode = true) }
        val segmenter = s.enableHls()
        withTimeoutOrNull(12_000) { while (segmenter.completedSegments < 3) delay(100) }
        val media = CastClient.liveMedia(s.urlFor(local, s.hlsPath), hls = true, title = TITLE, subtitle = device.name)
        if (!loadMedia(c, a, media)) finish("${device.name} couldn't play the stream")
    }

    private suspend fun loadAudio(): Unit = loadMutex.withLock {
        val c = client ?: return
        val a = app ?: return
        val s = server ?: return
        val local = localAddress ?: return
        update { it.copy(phase = CastPhase.BUFFERING) }
        val media = CastClient.liveAudioMedia(s.urlFor(local, s.audioPath), title = "Phone sound", subtitle = device.name)
        if (!loadMedia(c, a, media)) finish("${device.name} couldn't play the sound stream")
    }

    private suspend fun loadMedia(c: CastClient, a: RunningApp, media: JSONObject): Boolean {
        loading = true
        playing = false
        return try {
            val status = c.load(a, media)
            mediaSessionId = status.optInt("mediaSessionId", -1)
            loading = false
            handleMediaStatus(status)
            true
        } catch (e: CastException) {
            Log.w(TAG, "LOAD failed (hls=$usingHls): ${e.message}")
            loading = false
            false
        }
    }

    private suspend fun fallbackToHls(reason: String) {
        if (usingHls || audioOnly || ended.get()) return
        Log.i(TAG, "Switching to HLS: $reason")
        loadHls()
    }

    private suspend fun reload() {
        when {
            audioOnly -> loadAudio()
            usingHls -> loadHls()
            else -> loadMp4()
        }
    }

    // ---- reconnecting -------------------------------------------------------------------

    /** The connection to the TV broke (Wi-Fi drop, TV restart): keep capturing and try again. */
    private suspend fun reconnect() {
        if (ended.get() || reconnecting) return
        reconnecting = true
        watchdog?.cancel()
        update { it.copy(phase = CastPhase.RECONNECTING) }
        try { client?.close() } catch (_: Exception) {}
        try { urlRenderer?.close() } catch (_: Exception) {}
        playing = false
        val deadline = System.currentTimeMillis() + RECONNECT_FOR_MS
        var attempt = 0
        var host = device.host
        var port = device.port
        while (!ended.get() && System.currentTimeMillis() < deadline) {
            attempt++
            delay(if (attempt == 1) 1500 else 4000)
            if (attempt % 3 == 0 && !device.isUrlRenderer) {
                // The TV may have come back with a different IP address.
                NetworkScanner.findById(device.id)?.let { found ->
                    host = found.host
                    port = found.port
                    device = device.copy(host = found.host, port = found.port)
                    CastApp.get(context).devices.found(found)
                }
            }
            try {
                reloads = 0
                mediaSessionId = -1
                connectAndPlay(host, port)
                reconnecting = false
                Log.i(TAG, "Reconnected after $attempt attempt(s)")
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

    // ---- receiver events -------------------------------------------------------

    private fun listenerFor(c: CastClient) = object : CastClient.Listener {
        override fun onReceiverStatus(status: JSONObject) {
            if (c === client) readVolume(status)
        }

        override fun onMediaStatus(status: JSONObject) {
            if (c !== client || loading || mediaSessionId == -1) return // nothing of ours is loaded (yet)
            val id = status.optInt("mediaSessionId", -1)
            if (id != -1 && id != mediaSessionId) return // status of media we replaced
            handleMediaStatus(status)
        }

        override fun onCustomMessage(namespace: String, json: JSONObject) {
            if (c !== client || namespace != NS_PLAYER || json.optString("type") != "STATUS") return
            when (json.optString("state")) {
                "playing" -> update { it.copy(phase = CastPhase.LIVE) }
                "buffering" -> update { it.copy(phase = CastPhase.BUFFERING) }
                "error" -> Log.w(TAG, "Low-latency player error: ${json.optString("message")}")
            }
        }

        override fun onAppClosed() {
            if (c === client && !reconnecting) finish("Casting was stopped on the TV")
        }

        override fun onDisconnected(error: Throwable?) {
            if (c === client && !ended.get()) scope.launch { reconnect() }
        }
    }

    private fun handleMediaStatus(status: JSONObject) {
        when (status.optString("playerState")) {
            "PLAYING" -> {
                playing = true
                reloads = 0
                update { it.copy(phase = CastPhase.LIVE) }
            }
            "BUFFERING" -> update { it.copy(phase = if (playing) CastPhase.BUFFERING else it.phase) }
            "PAUSED" -> {
                // A paused live stream would drift behind; just resume it.
                val c = client
                val a = app
                if (c != null && a != null && mediaSessionId >= 0) {
                    try {
                        c.send(
                            CastClient.NS_MEDIA, a.transportId,
                            JSONObject().put("type", "PLAY").put("mediaSessionId", mediaSessionId).put("requestId", 0),
                        )
                    } catch (_: Exception) {
                    }
                }
            }
            "IDLE" -> when (status.optString("idleReason")) {
                "ERROR", "FINISHED" -> scope.launch { recoverPlayback() }
                "CANCELLED", "INTERRUPTED" -> finish("Casting was stopped on the TV")
            }
        }
    }

    /** The TV stopped playing (network hiccup or playback error): reload, then fall back to HLS. */
    private suspend fun recoverPlayback() {
        if (ended.get() || reconnecting) return
        playing = false
        reloads++
        when {
            reloads > 3 -> finish("${device.name} keeps stopping the stream")
            !usingHls && !audioOnly && reloads > 1 -> fallbackToHls("playback error")
            else -> {
                delay(500)
                reload()
            }
        }
    }

    private fun readVolume(status: JSONObject) {
        val v = status.optJSONObject("volume") ?: return
        if (v.has("level")) volume = v.optDouble("level")
        muted = v.optBoolean("muted", muted)
        update { it.copy(volume = volume?.toFloat(), muted = muted) }
    }

    // ---- user controls ----------------------------------------------------------

    /** Switches the mirroring resolution while casting (e.g. "720p", "4K"). */
    fun setQuality(label: String) {
        val res = Resolution.entries.firstOrNull { it.short == label } ?: return
        if (audioOnly || res == settings.resolution) return
        scope.launch {
            val v = video ?: return@launch
            val h = hub ?: return@launch
            val s = server ?: return@launch
            settings = settings.copy(resolution = res)
            CastApp.get(context).settings.update { it.copy(resolution = res) } // remembered for next time
            val bitrate = settings.effectiveBitrate
            update { it.copy(phase = CastPhase.BUFFERING, qualityLabel = res.short, resolution = res.label, bitrateKbps = bitrate / 1000) }
            loadMutex.withLock {
                withContext(Dispatchers.Main) {
                    v.reconfigure(res.width, res.height, bitrate) {
                        h.resetVideo()
                        s.restartStreams()
                    }
                }
                if (settings.adaptiveQuality) {
                    h.congestionListener = AdaptiveBitrate(bitrate) { bps ->
                        video?.setBitrate(bps)
                        update { it.copy(bitrateKbps = bps / 1000) }
                    }
                }
            }
            if (h.awaitInitSegment(8_000) == null) {
                finish("The phone can't encode ${res.short}")
                return@launch
            }
            // The TV has to reload to pick up the new picture size.
            try {
                when {
                    device.isUrlRenderer -> urlRenderer?.let { r ->
                        val local = localAddress ?: return@let
                        r.load(mirrorMedia(s, local))
                    }
                    usingCustomPlayer -> client?.let { c -> if (!tryCustomPlayer(c, settings.receiverAppId.trim())) reload() }
                    else -> reload()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reload after resolution change failed", e)
            }
        }
    }

    override fun changeVolume(delta: Double) {
        val c = client
        val r = urlRenderer
        if (c == null && r == null) return
        scope.launch {
            try {
                val target = ((volume ?: 0.5) + delta).coerceIn(0.0, 1.0)
                volume = target
                update { it.copy(volume = target.toFloat(), muted = false) }
                if (r != null) {
                    if (muted) r.setMuted(false)
                    r.setVolume(target)
                } else if (c != null) {
                    if (muted) c.setMuted(false)
                    c.setVolume(target)
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun toggleMute() {
        scope.launch {
            try {
                urlRenderer?.setMuted(!muted) ?: client?.setMuted(!muted)
            } catch (_: Exception) {
            }
        }
    }

    // ---- teardown ---------------------------------------------------------------

    /**
     * Ends the session. [message] is shown to the user (null when the user stopped it).
     * [stopReceiver] closes the player on the TV; it is skipped when a new session is
     * about to take over the same TV.
     */
    override fun finish(message: String?, stopReceiver: Boolean) {
        if (!ended.compareAndSet(false, true)) return
        Thread({ teardown(message, stopReceiver) }, "cast-teardown").start()
    }

    private fun teardown(message: String?, stopReceiver: Boolean) {
        watchdog?.cancel()
        val c = client
        val a = app
        if (stopReceiver && c != null && a != null && c.isConnected) {
            try {
                runBlocking { withTimeoutOrNull(2500) { c.stopApp(a) } }
            } catch (_: Exception) {
            }
        }
        try { c?.close() } catch (_: Exception) {}
        urlRenderer?.let { r ->
            if (stopReceiver) try { runBlocking { withTimeoutOrNull(2500) { r.stop() } } } catch (_: Exception) {}
            r.close()
        }
        scope.cancel()
        if (Build.VERSION.SDK_INT >= 29) try { audio?.stop() } catch (_: Exception) {}
        try { video?.stop() } catch (_: Exception) {}
        try { server?.stop() } catch (_: Exception) {}
        try { projection.stop() } catch (_: Exception) {}
        locks.release()
        CastController.end(this)
        if (message != null) CastController.message(message)
        mainHandler.post { onEnded(this) }
    }

    private fun update(transform: (CastState.Active) -> CastState.Active) = CastController.update(this) {
        transform(it).copy(device = device)
    }

    companion object {
        private const val TAG = "CastSession"
        private const val TITLE = "Phone screen"
        private const val MP4_START_TIMEOUT_MS = 15_000L
        private const val RECONNECT_FOR_MS = 120_000L

        /** Namespace of our own low-latency TV player (receiver/index.html). */
        const val NS_PLAYER = "urn:x-cast:io.github.patschgoo.castapp"
    }
}
