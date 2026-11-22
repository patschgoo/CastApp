package io.github.patschgoo.castapp.service

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.cast.CastClient
import io.github.patschgoo.castapp.cast.CastException
import io.github.patschgoo.castapp.cast.RunningApp
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.stream.FileServer
import io.github.patschgoo.castapp.stream.MediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Casts a video or audio file: the phone serves the file and the TV plays it
 * itself, so the phone's screen can be off or locked the whole time. Playback
 * is controlled from the app, the notification, the lock screen and the
 * phone's volume buttons (through a remote-playback media session).
 */
class FileCastSession(
    private val context: Context,
    override val device: CastDevice,
    private val uri: Uri,
    private val mimeType: String,
    private val title: String,
    private val port: Int,
    private val onEnded: (ActiveCast) -> Unit,
) : ActiveCast {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ended = AtomicBoolean(false)

    @Volatile private var client: CastClient? = null
    @Volatile private var app: RunningApp? = null
    @Volatile private var server: FileServer? = null
    @Volatile private var mediaSessionId = -1
    @Volatile private var loading = true
    @Volatile private var volume: Double? = null
    @Volatile private var muted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Lock-screen / headset / volume-key integration. Created and used on the main thread. */
    var mediaSession: MediaSession? = null
        private set
    private var volumeProvider: VolumeProvider? = null

    fun start() {
        CastController.begin(this, CastState.Active(device, CastPhase.CONNECTING, kind = CastKind.FILE, title = title))
        createMediaSession()
        scope.launch { run() }
    }

    private suspend fun run() {
        try {
            acquireLocks()
            val c = CastClient(device.host, device.port)
            c.listener = listener
            client = c
            try {
                c.connect(6000)
            } catch (e: Exception) {
                throw CastException("Can't reach ${device.name} at ${device.host}. Is it on the same Wi-Fi?")
            }
            val local = c.localAddress ?: throw CastException("No network path to the TV")

            val source = ContentMediaSource(context.contentResolver, uri)
            val s = FileServer(source, mimeType, title, port)
            s.start()
            server = s

            update { it.copy(phase = CastPhase.STARTING) }
            val a = c.launch(CastClient.DEFAULT_MEDIA_RECEIVER)
            app = a
            try { readVolume(c.getReceiverStatus()) } catch (_: Exception) {}

            update { it.copy(phase = CastPhase.BUFFERING) }
            val status = try {
                c.load(a, CastClient.fileMedia(s.urlFor(local, s.mediaPath), mimeType, title, device.name), timeoutMs = 45_000)
            } catch (e: CastException) {
                throw CastException("${device.name} couldn't play this file. It may use a format the TV doesn't support.")
            }
            mediaSessionId = status.optInt("mediaSessionId", -1)
            loading = false
            handleStatus(status)

            // The TV only reports changes, so refresh the position now and then.
            while (!ended.get()) {
                delay(5000)
                refreshStatus()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "File cast failed", e)
            finish(e.message ?: "Casting failed")
        }
    }

    // ---- playback controls ------------------------------------------------------

    fun togglePlay() {
        val s = CastController.state.value as? CastState.Active ?: return
        if (s.paused) play() else pause()
    }

    fun play() = command("PLAY")

    fun pause() = command("PAUSE")

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0)
        update { it.copy(positionMs = target, positionAt = SystemClock.elapsedRealtime()) }
        command("SEEK") { it.put("currentTime", target / 1000.0) }
    }

    fun seekBy(deltaMs: Long) {
        val s = CastController.state.value as? CastState.Active ?: return
        val now = s.positionNow(SystemClock.elapsedRealtime())
        val target = (now + deltaMs).let { if (s.durationMs > 0) it.coerceAtMost(s.durationMs - 1000) else it }
        seekTo(target)
    }

    private fun command(type: String, extra: (JSONObject) -> Unit = {}) {
        val c = client ?: return
        val a = app ?: return
        if (mediaSessionId < 0) return
        scope.launch {
            try {
                c.mediaCommand(a, mediaSessionId, type, extra)?.let { handleStatus(it) }
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun refreshStatus() {
        val c = client ?: return
        val a = app ?: return
        if (mediaSessionId < 0) return
        try {
            c.mediaCommand(a, mediaSessionId, "GET_STATUS")?.let { handleStatus(it) }
        } catch (_: Exception) {
        }
    }

    override fun changeVolume(delta: Double) {
        val c = client ?: return
        scope.launch {
            try {
                if (muted) c.setMuted(false)
                val target = ((volume ?: 0.5) + delta).coerceIn(0.0, 1.0)
                volume = target
                update { it.copy(volume = target.toFloat(), muted = false) }
                mainHandler.post { volumeProvider?.currentVolume = (target * VOLUME_STEPS).toInt() }
                c.setVolume(target)
            } catch (_: Exception) {
            }
        }
    }

    private fun setVolume(level: Double) = changeVolume(level - (volume ?: level))

    override fun toggleMute() {
        val c = client ?: return
        scope.launch { try { c.setMuted(!muted) } catch (_: Exception) {} }
    }

    // ---- receiver events ------------------------------------------------------------

    private val listener = object : CastClient.Listener {
        override fun onReceiverStatus(status: JSONObject) = readVolume(status)

        override fun onMediaStatus(status: JSONObject) {
            if (loading || mediaSessionId == -1) return
            val id = status.optInt("mediaSessionId", -1)
            if (id != -1 && id != mediaSessionId) return
            handleStatus(status)
        }

        override fun onAppClosed() = finish("Playback was stopped on the TV")

        override fun onDisconnected(error: Throwable?) = finish("Lost the connection to ${device.name}")
    }

    private fun handleStatus(status: JSONObject) {
        val now = SystemClock.elapsedRealtime()
        val durationMs = status.optJSONObject("media")?.optDouble("duration", 0.0)?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
        val positionMs = if (status.has("currentTime")) (status.optDouble("currentTime", 0.0) * 1000).toLong() else null
        when (status.optString("playerState")) {
            "PLAYING" -> update { it.copy(phase = CastPhase.LIVE, paused = false) }
            "PAUSED" -> update { it.copy(phase = CastPhase.LIVE, paused = true) }
            "BUFFERING" -> update { it.copy(phase = CastPhase.BUFFERING) }
            "IDLE" -> when (status.optString("idleReason")) {
                "FINISHED" -> finish("Finished playing $title")
                "ERROR" -> finish("${device.name} couldn't play this file. It may use a format the TV doesn't support.")
                "CANCELLED", "INTERRUPTED" -> finish("Playback was stopped on the TV")
            }
        }
        update {
            it.copy(
                positionMs = positionMs ?: it.positionMs,
                positionAt = if (positionMs != null) now else it.positionAt,
                durationMs = durationMs ?: it.durationMs,
            )
        }
        mainHandler.post { publishMediaSessionState() }
    }

    private fun readVolume(status: JSONObject) {
        val v = status.optJSONObject("volume") ?: return
        if (v.has("level")) volume = v.optDouble("level")
        muted = v.optBoolean("muted", muted)
        update { it.copy(volume = volume?.toFloat(), muted = muted) }
        val level = volume ?: return
        mainHandler.post { volumeProvider?.currentVolume = (level * VOLUME_STEPS).toInt() }
    }

    // ---- media session (lock screen, notification, volume keys) ------------------------

    private fun createMediaSession() {
        val session = MediaSession(context, "CastApp")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = play()
            override fun onPause() = pause()
            override fun onStop() = finish(null)
            override fun onSeekTo(pos: Long) = seekTo(pos)
            override fun onFastForward() = seekBy(FORWARD_MS)
            override fun onRewind() = seekBy(-REWIND_MS)
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
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND,
                )
                .setState(state, s?.positionMs ?: 0, if (state == PlaybackState.STATE_PLAYING) 1f else 0f, s?.positionAt ?: SystemClock.elapsedRealtime())
                .addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_REWIND, "Back 10 s", R.drawable.ic_rewind).build())
                .addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_FORWARD, "Forward 30 s", R.drawable.ic_forward).build())
                .addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_STOP, "Stop", R.drawable.ic_stop).build())
                .build(),
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "On ${device.name}")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, s?.durationMs ?: 0)
                .build(),
        )
    }

    // ---- teardown ----------------------------------------------------------------------

    override fun finish(message: String?, stopReceiver: Boolean) {
        if (!ended.compareAndSet(false, true)) return
        Thread({ teardown(message, stopReceiver) }, "file-cast-teardown").start()
    }

    private fun teardown(message: String?, stopReceiver: Boolean) {
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
        try { server?.stop() } catch (_: Exception) {}
        releaseLocks()
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

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        // Keep serving the file while the phone's screen is off.
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CastApp:file").apply { acquire() }
        } catch (_: Exception) {
        }
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "CastApp:file").apply {
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
        private const val TAG = "FileCastSession"
        private const val VOLUME_STEPS = 20
        const val REWIND_MS = 10_000L
        const val FORWARD_MS = 30_000L
        const val CUSTOM_REWIND = "rewind10"
        const val CUSTOM_FORWARD = "forward30"
        const val CUSTOM_STOP = "stop"

        /** Display name and size of a content URI, as far as the provider tells us. */
        fun describe(resolver: ContentResolver, uri: Uri): Pair<String?, Long> {
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
                        val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) } ?: -1L
                        return name to size
                    }
                }
            } catch (_: Exception) {
            }
            return null to -1L
        }
    }
}

/** Reads a content:// file from any offset (seeking with the file channel when possible). */
class ContentMediaSource(private val resolver: ContentResolver, private val uri: Uri) : MediaSource {
    override val length: Long by lazy {
        val fromProvider = FileCastSession.describe(resolver, uri).second
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
