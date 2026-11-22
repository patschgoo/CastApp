package io.github.patschgoo.castapp.render

import io.github.patschgoo.castapp.cast.CastException
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.dlna.Dlna
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Plays media on a DLNA TV (Samsung, LG, Sony, ...) through UPnP AVTransport.
 * DLNA has no push events we can rely on, so the state is polled every second.
 */
class DlnaRenderer(override val device: CastDevice) : MediaRenderer {
    override var listener: MediaRenderer.Listener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val avt = device.avTransportUrl ?: ""
    private val rc = device.renderingControlUrl
    @Volatile private var local: InetAddress? = null
    @Volatile private var poller: Job? = null
    @Volatile private var sawPlaying = false
    @Volatile private var lastPosition = 0L
    @Volatile private var lastDuration = 0L
    @Volatile private var failures = 0
    @Volatile private var closed = false
    @Volatile private var loadedAt = 0L

    override val localAddress: InetAddress? get() = local

    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (avt.isEmpty()) throw CastException("${device.name} can't play media")
        val uri = URI(avt)
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(uri.host, if (uri.port > 0) uri.port else 80), 4000)
                local = s.localAddress
            }
            Dlna.call(avt, Dlna.AV_TRANSPORT, "GetTransportInfo", listOf("InstanceID" to "0"))
        } catch (e: Exception) {
            throw CastException("Can't reach ${device.name} at ${device.host}. Is it on the same Wi-Fi?")
        }
        readVolume()
        startPolling()
    }

    override suspend fun load(media: RendererMedia): RendererStatus = withContext(Dispatchers.IO) {
        sawPlaying = false
        lastPosition = 0
        lastDuration = 0
        loadedAt = System.currentTimeMillis()
        try { Dlna.call(avt, Dlna.AV_TRANSPORT, "Stop", listOf("InstanceID" to "0")) } catch (_: Exception) {}
        val meta = Dlna.didl(
            media.title, media.url, media.mimeType, media.live, media.subtitleSrtUrl ?: media.subtitleUrl, media.lowLatency,
            // Only CastApp's own receiver understands the subtitle look; other TVs keep theirs.
            subtitleStyle = media.subtitleStyle?.takeIf { device.isCastAppTv }?.encode(),
        )
        Dlna.call(
            avt, Dlna.AV_TRANSPORT, "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to media.url, "CurrentURIMetaData" to meta),
            timeoutMs = 10_000,
        )
        Dlna.call(avt, Dlna.AV_TRANSPORT, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
        if (media.startMs > 0) {
            // Most TVs only accept a seek once playback has started.
            for (i in 0 until 20) {
                if (sawPlaying) break
                delay(500)
                poll()
            }
            try { seekNow(media.startMs) } catch (_: Exception) {}
        }
        RendererStatus(PlayState.BUFFERING)
    }

    override suspend fun play() { call("Play", listOf("InstanceID" to "0", "Speed" to "1")) }
    override suspend fun pause() { call("Pause", listOf("InstanceID" to "0")) }
    override suspend fun seek(positionMs: Long) = withContext(Dispatchers.IO) { seekNow(positionMs) }

    private fun seekNow(positionMs: Long) {
        Dlna.call(avt, Dlna.AV_TRANSPORT, "Seek", listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to Dlna.formatTime(positionMs)))
    }

    override suspend fun refresh(): RendererStatus? = withContext(Dispatchers.IO) { poll() }

    override suspend fun setSubtitlesVisible(on: Boolean) {
        // DLNA has no standard way to switch subtitles; Samsung TVs show them from the metadata.
    }

    override suspend fun setVolume(level: Double) = withContext(Dispatchers.IO) {
        val url = rc ?: return@withContext
        Dlna.call(url, Dlna.RENDERING_CONTROL, "SetVolume", listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to (level * 100).toInt().coerceIn(0, 100).toString()))
        listener?.onVolume(level, false)
    }

    override suspend fun setMuted(muted: Boolean) = withContext(Dispatchers.IO) {
        val url = rc ?: return@withContext
        Dlna.call(url, Dlna.RENDERING_CONTROL, "SetMute", listOf("InstanceID" to "0", "Channel" to "Master", "DesiredMute" to if (muted) "1" else "0"))
        readVolume()
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        poller?.cancel()
        try { Dlna.call(avt, Dlna.AV_TRANSPORT, "Stop", listOf("InstanceID" to "0")) } catch (_: Exception) {}
        Unit
    }

    override fun close() {
        closed = true
        scope.cancel()
    }

    private suspend fun call(action: String, args: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        Dlna.call(avt, Dlna.AV_TRANSPORT, action, args)
        poll()
        Unit
    }

    private fun readVolume() {
        val url = rc ?: return
        try {
            val vol = Dlna.tag(Dlna.call(url, Dlna.RENDERING_CONTROL, "GetVolume", listOf("InstanceID" to "0", "Channel" to "Master")), "CurrentVolume")?.toIntOrNull()
            val mute = Dlna.tag(Dlna.call(url, Dlna.RENDERING_CONTROL, "GetMute", listOf("InstanceID" to "0", "Channel" to "Master")), "CurrentMute")
            listener?.onVolume(vol?.let { it / 100.0 }, mute == "1" || mute.equals("true", true))
        } catch (_: Exception) {
        }
    }

    private fun startPolling() {
        poller?.cancel()
        poller = scope.launch {
            var tick = 0
            while (!closed) {
                delay(1000)
                poll()
                if (++tick % 5 == 0) readVolume()
            }
        }
    }

    /** Reads transport state and position; reports them, or a lost connection after repeated failures. */
    private fun poll(): RendererStatus? {
        return try {
            val info = Dlna.call(avt, Dlna.AV_TRANSPORT, "GetTransportInfo", listOf("InstanceID" to "0"), timeoutMs = 3000)
            val pos = Dlna.call(avt, Dlna.AV_TRANSPORT, "GetPositionInfo", listOf("InstanceID" to "0"), timeoutMs = 3000)
            failures = 0
            val position = Dlna.parseTime(Dlna.tag(pos, "RelTime"))
            val duration = Dlna.parseTime(Dlna.tag(pos, "TrackDuration"))?.takeIf { it > 0 }
            position?.let { lastPosition = it }
            duration?.let { lastDuration = it }
            val state = when (Dlna.tag(info, "CurrentTransportState")) {
                "PLAYING" -> { sawPlaying = true; PlayState.PLAYING }
                "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> PlayState.PAUSED
                "TRANSITIONING" -> PlayState.BUFFERING
                "STOPPED", "NO_MEDIA_PRESENT" -> when {
                    // Never started: the TV refused the file (most TVs don't say why).
                    !sawPlaying && System.currentTimeMillis() - loadedAt > 8000 -> PlayState.ERROR
                    !sawPlaying -> PlayState.BUFFERING
                    lastDuration > 0 && lastPosition >= lastDuration - 4000 -> PlayState.FINISHED
                    else -> PlayState.STOPPED
                }
                else -> if (sawPlaying) PlayState.STOPPED else PlayState.IDLE
            }
            val status = RendererStatus(state, position, duration)
            listener?.onStatus(status)
            if (state == PlayState.FINISHED || state == PlayState.STOPPED) sawPlaying = false
            status
        } catch (e: Exception) {
            if (++failures >= 5 && !closed) {
                failures = 0
                listener?.onLost(e)
            }
            null
        }
    }
}
