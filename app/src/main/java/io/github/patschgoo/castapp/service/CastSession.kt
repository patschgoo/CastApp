package io.github.patschgoo.castapp.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.projection.MediaProjection
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import io.github.patschgoo.castapp.capture.AudioEncoder
import io.github.patschgoo.castapp.capture.ScreenEncoder
import io.github.patschgoo.castapp.cast.CastClient
import io.github.patschgoo.castapp.cast.CastException
import io.github.patschgoo.castapp.cast.RunningApp
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.settings.CastSettings
import io.github.patschgoo.castapp.settings.StreamMode
import io.github.patschgoo.castapp.stream.StreamHub
import io.github.patschgoo.castapp.stream.StreamServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * One casting session: connects to the TV, starts the encoders and the stream
 * server, asks the TV's Default Media Receiver to play our stream and watches
 * it. Falls back from the low-latency MP4 stream to HLS when the TV can't play it.
 */
class CastSession(
    private val context: Context,
    val device: CastDevice,
    private val projection: MediaProjection,
    private val settings: CastSettings,
    private val onEnded: (CastSession) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ended = AtomicBoolean(false)
    private val loadMutex = Mutex()

    @Volatile private var client: CastClient? = null
    @Volatile private var app: RunningApp? = null
    @Volatile private var server: StreamServer? = null
    @Volatile private var localAddress: InetAddress? = null
    private var hub: StreamHub? = null
    private var video: ScreenEncoder? = null
    private var audio: AudioEncoder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var usingHls = false
    @Volatile private var loading = false
    @Volatile private var mediaSessionId = -1
    @Volatile private var playing = false
    @Volatile private var volume: Double? = null
    @Volatile private var muted = false
    private var reloads = 0
    private var watchdog: Job? = null

    fun start() {
        CastController.begin(
            this,
            CastState.Active(
                device = device,
                phase = CastPhase.CONNECTING,
                compatibilityMode = settings.streamMode == StreamMode.COMPATIBLE,
                resolution = settings.resolution.label,
            ),
        )
        scope.launch { run() }
    }

    private suspend fun run() {
        try {
            acquireLocks()
            val c = CastClient(device.host, device.port)
            c.listener = castListener
            client = c
            try {
                c.connect(6000)
            } catch (e: Exception) {
                throw CastException("Can't reach ${device.name} at ${device.host}. Is it on the same Wi-Fi?")
            }
            val local = c.localAddress ?: throw CastException("No network path to the TV")
            localAddress = local

            val h = StreamHub()
            hub = h
            val s = StreamServer(h, settings.port)
            s.start()
            server = s
            startCapture(h)

            update { it.copy(phase = CastPhase.STARTING) }
            val a = c.launch(CastClient.DEFAULT_MEDIA_RECEIVER)
            app = a
            try { readVolume(c.getReceiverStatus()) } catch (_: Exception) {}
            // Make sure capture is running and the track layout is fixed (falls back to
            // video-only if audio capture never starts).
            h.awaitInitSegment(8_000) ?: throw CastException("Screen capture did not start")

            if (settings.streamMode == StreamMode.COMPATIBLE) loadHls() else loadMp4()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cast session failed", e)
            finish(e.message ?: "Casting failed")
        }
    }

    private suspend fun startCapture(h: StreamHub) {
        val wantAudio = Build.VERSION.SDK_INT >= 29 && settings.audio
        h.expectAudio(wantAudio)
        withContext(Dispatchers.Main) {
            val dpi = context.resources.displayMetrics.densityDpi
            val res = settings.resolution
            val v = ScreenEncoder(projection, h, res.width, res.height, settings.fps, settings.effectiveBitrate, dpi) { e ->
                Log.w(TAG, "Video encoder error", e)
                finish("Screen capture stopped")
            }
            v.start(mainHandler)
            video = v
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
        update { it.copy(audio = audioOn) }
    }

    // ---- loading the stream on the TV ----------------------------------------

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
            if (!playing && !usingHls && !ended.get()) fallbackToHls("no playback")
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
        if (usingHls || ended.get()) return
        Log.i(TAG, "Switching to HLS: $reason")
        loadHls()
    }

    // ---- receiver events -------------------------------------------------------

    private val castListener = object : CastClient.Listener {
        override fun onReceiverStatus(status: JSONObject) = readVolume(status)

        override fun onMediaStatus(status: JSONObject) {
            if (loading || mediaSessionId == -1) return // nothing of ours is loaded (yet)
            val id = status.optInt("mediaSessionId", -1)
            if (id != -1 && id != mediaSessionId) return // status of media we replaced
            handleMediaStatus(status)
        }

        override fun onAppClosed() = finish("Casting was stopped on the TV")

        override fun onDisconnected(error: Throwable?) = finish("Lost the connection to ${device.name}")
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
        if (ended.get()) return
        playing = false
        reloads++
        when {
            reloads > 3 -> finish("${device.name} keeps stopping the stream")
            !usingHls && reloads > 1 -> fallbackToHls("playback error")
            else -> {
                delay(500)
                if (usingHls) loadHls() else loadMp4()
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

    fun changeVolume(delta: Double) {
        val c = client ?: return
        scope.launch {
            try {
                if (muted) c.setMuted(false)
                val target = ((volume ?: 0.5) + delta).coerceIn(0.0, 1.0)
                volume = target
                update { it.copy(volume = target.toFloat(), muted = false) }
                c.setVolume(target)
            } catch (_: Exception) {
            }
        }
    }

    fun toggleMute() {
        val c = client ?: return
        scope.launch {
            try { c.setMuted(!muted) } catch (_: Exception) {}
        }
    }

    // ---- teardown ---------------------------------------------------------------

    /**
     * Ends the session. [message] is shown to the user (null when the user stopped it).
     * [stopReceiver] closes the player on the TV; it is skipped when a new session is
     * about to take over the same TV.
     */
    fun finish(message: String?, stopReceiver: Boolean = true) {
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
        scope.cancel()
        if (Build.VERSION.SDK_INT >= 29) try { audio?.stop() } catch (_: Exception) {}
        try { video?.stop() } catch (_: Exception) {}
        try { server?.stop() } catch (_: Exception) {}
        try { projection.stop() } catch (_: Exception) {}
        releaseLocks()
        CastController.end(this)
        if (message != null) CastController.message(message)
        mainHandler.post { onEnded(this) }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CastApp:casting").apply { acquire() }
        } catch (_: Exception) {
        }
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "CastApp:casting").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    private fun update(transform: (CastState.Active) -> CastState.Active) = CastController.update(this, transform)

    companion object {
        private const val TAG = "CastSession"
        private const val TITLE = "Phone screen"
        private const val MP4_START_TIMEOUT_MS = 15_000L
    }
}
