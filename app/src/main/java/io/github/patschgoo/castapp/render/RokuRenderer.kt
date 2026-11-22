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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder

/**
 * Plays media on a Roku player or Roku TV through Roku's External Control
 * Protocol (port 8060): the "Play on Roku" input takes a URL, the media player
 * query reports the state, and remote-control key presses pause, play and
 * change the volume. Roku offers no way to jump to a position.
 */
class RokuRenderer(override val device: CastDevice) : MediaRenderer {
    override var listener: MediaRenderer.Listener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val base = "http://${device.host}:${device.port}"
    @Volatile private var local: InetAddress? = null
    @Volatile private var poller: Job? = null
    @Volatile private var closed = false
    @Volatile private var started = false
    @Volatile private var loadedAt = 0L
    @Volatile private var lastPosition = 0L
    @Volatile private var lastDuration = 0L
    @Volatile private var failures = 0
    @Volatile private var paused = false
    @Volatile private var volume = 0.5

    override val localAddress: InetAddress? get() = local

    override suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(device.host, device.port), 4000)
                local = s.localAddress
            }
            http("GET", "/query/device-info")
        } catch (e: Exception) {
            throw CastException("Can't reach ${device.name} at ${device.host}. Is it on the same Wi-Fi?")
        }
        startPolling()
    }

    override suspend fun load(media: RendererMedia): RendererStatus = withContext(Dispatchers.IO) {
        started = false
        paused = false
        lastPosition = 0
        lastDuration = 0
        loadedAt = System.currentTimeMillis()
        val type = when {
            media.mimeType.startsWith("audio/") -> "a"
            media.mimeType.startsWith("image/") -> "p"
            else -> "v"
        }
        val format = when {
            media.mimeType.contains("mpegurl", true) -> "hls"
            media.mimeType.contains("dash", true) -> "dash"
            media.mimeType.contains("matroska", true) || media.mimeType.contains("webm", true) -> "mkv"
            media.mimeType.contains("mp2t", true) -> "ts"
            media.mimeType.startsWith("audio/mpeg") -> "mp3"
            media.mimeType.startsWith("audio/") -> "aac"
            media.mimeType.startsWith("image/") -> "jpg"
            else -> "mp4"
        }
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val query = "t=$type&u=${enc(media.url)}&k=(null)&videoName=${enc(media.title)}&videoFormat=$format"
        val code = http("POST", "/input/$PLAY_ON_ROKU?$query")
        if (code !in 200..299) throw CastException("${device.name} refused the video ($code)")
        RendererStatus(PlayState.BUFFERING)
    }

    override suspend fun play() = withContext(Dispatchers.IO) {
        if (paused) key("Play")
        paused = false
        Unit
    }

    override suspend fun pause() = withContext(Dispatchers.IO) {
        if (!paused) key("Play")
        paused = true
        Unit
    }

    override suspend fun seek(positionMs: Long) {
        // Not possible over ECP.
    }

    override suspend fun refresh(): RendererStatus? = withContext(Dispatchers.IO) { poll() }

    override suspend fun setSubtitlesVisible(on: Boolean) {}

    /** Roku only knows "louder" and "quieter": press once in the asked direction. */
    override suspend fun setVolume(level: Double) = withContext(Dispatchers.IO) {
        key(if (level >= volume) "VolumeUp" else "VolumeDown")
        volume = level
    }

    override suspend fun setMuted(muted: Boolean) = withContext(Dispatchers.IO) { key("VolumeMute") }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        poller?.cancel()
        try { key("Back") } catch (_: Exception) {}
        Unit
    }

    override fun close() {
        closed = true
        scope.cancel()
    }

    private fun key(name: String) {
        http("POST", "/keypress/$name")
    }

    private fun http(method: String, path: String): Int {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            if (method == "POST") {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(0)
                conn.outputStream.close()
            }
            val code = conn.responseCode
            if (code == 403) throw CastException("${device.name} blocks control by phones. On the Roku: Settings → System → Advanced system settings → Control by mobile apps: turn it on (network access Default or Permissive).")
            return code
        } finally {
            conn.disconnect()
        }
    }

    private fun startPolling() {
        poller?.cancel()
        poller = scope.launch {
            while (!closed) {
                delay(1000)
                poll()
            }
        }
    }

    private fun poll(): RendererStatus? {
        return try {
            val xml = Dlna.fetch("$base/query/media-player", timeoutMs = 3000) ?: throw IOException("No answer")
            failures = 0
            val state = Regex("<player[^>]*state=\"([a-z]+)\"").find(xml)?.groupValues?.get(1).orEmpty()
            val error = Regex("<player[^>]*error=\"true\"").containsMatchIn(xml)
            val position = Dlna.tag(xml, "position")?.substringBefore(' ')?.toLongOrNull()
            val duration = Dlna.tag(xml, "duration")?.substringBefore(' ')?.toLongOrNull()?.takeIf { it > 0 }
            position?.let { lastPosition = it }
            duration?.let { lastDuration = it }
            val play = when {
                error -> PlayState.ERROR
                state == "play" -> { started = true; paused = false; PlayState.PLAYING }
                state == "pause" -> { started = true; paused = true; PlayState.PAUSED }
                state == "buffer" || state == "startup" -> PlayState.BUFFERING
                started -> if (lastDuration > 0 && lastPosition >= lastDuration - 4000) PlayState.FINISHED else PlayState.STOPPED
                loadedAt > 0 && System.currentTimeMillis() - loadedAt > 30_000 -> PlayState.ERROR
                loadedAt > 0 -> PlayState.BUFFERING
                else -> PlayState.IDLE
            }
            val status = RendererStatus(play, position, duration)
            listener?.onStatus(status)
            if (play == PlayState.FINISHED || play == PlayState.STOPPED) {
                started = false
                loadedAt = 0
            }
            status
        } catch (e: Exception) {
            if (++failures >= 5 && !closed) {
                failures = 0
                listener?.onLost(e)
            }
            null
        }
    }

    companion object {
        /** Roku's built-in "Play on Roku" receiver. */
        private const val PLAY_ON_ROKU = 15985
        const val SSDP_TARGET = "roku:ecp"

        /** A Roku found by SSDP; its friendly name comes from /query/device-info. */
        fun deviceFrom(location: String): CastDevice? {
            val uri = try { java.net.URI(location) } catch (_: Exception) { return null }
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 8060
            val info = Dlna.fetch("http://$host:$port/query/device-info", 3000) ?: return null
            fun t(name: String) = Dlna.tag(info, name)?.let(Dlna::unescape)?.takeIf { it.isNotBlank() }
            val serial = t("serial-number") ?: t("device-id") ?: host
            val name = t("user-device-name") ?: t("friendly-device-name") ?: t("default-device-name") ?: "Roku"
            return CastDevice(
                id = "roku:$serial",
                name = name,
                host = host,
                port = port,
                model = listOfNotNull(t("vendor-name"), t("model-name")).joinToString(" ").ifEmpty { "Roku" },
                kind = io.github.patschgoo.castapp.discovery.DeviceKind.ROKU,
            )
        }
    }
}
