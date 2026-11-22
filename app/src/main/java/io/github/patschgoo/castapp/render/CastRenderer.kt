package io.github.patschgoo.castapp.render

import io.github.patschgoo.castapp.cast.CastClient
import io.github.patschgoo.castapp.cast.CastException
import io.github.patschgoo.castapp.cast.RunningApp
import io.github.patschgoo.castapp.discovery.CastDevice
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress

/** Plays media on a Google Cast device through Google's Default Media Receiver. */
class CastRenderer(override val device: CastDevice) : MediaRenderer {
    override var listener: MediaRenderer.Listener? = null

    @Volatile private var client: CastClient? = null
    @Volatile private var app: RunningApp? = null
    @Volatile private var mediaSessionId = -1
    @Volatile private var loading = false

    override val localAddress: InetAddress? get() = client?.localAddress

    override suspend fun connect() {
        val c = CastClient(device.host, device.port)
        c.listener = object : CastClient.Listener {
            override fun onReceiverStatus(status: JSONObject) {
                if (c !== client) return
                val v = status.optJSONObject("volume") ?: return
                listener?.onVolume(if (v.has("level")) v.optDouble("level") else null, v.optBoolean("muted", false))
            }

            override fun onMediaStatus(status: JSONObject) {
                if (c !== client || loading || mediaSessionId == -1) return
                val id = status.optInt("mediaSessionId", -1)
                if (id != -1 && id != mediaSessionId) return
                listener?.onStatus(parse(status))
            }

            override fun onAppClosed() {
                if (c === client) listener?.onClosedOnDevice()
            }

            override fun onDisconnected(error: Throwable?) {
                if (c === client) listener?.onLost(error)
            }
        }
        try {
            c.connect(6000)
        } catch (e: Exception) {
            c.close()
            throw CastException("Can't reach ${device.name} at ${device.host}. Is it on the same Wi-Fi?")
        }
        client = c
        app = c.launch(CastClient.DEFAULT_MEDIA_RECEIVER)
        try {
            c.getReceiverStatus().optJSONObject("volume")?.let { v ->
                listener?.onVolume(if (v.has("level")) v.optDouble("level") else null, v.optBoolean("muted", false))
            }
        } catch (_: Exception) {
        }
    }

    override suspend fun load(media: RendererMedia): RendererStatus {
        val c = client ?: throw CastException("Not connected")
        val a = app ?: throw CastException("Not connected")
        val mime = media.mimeType
        val metadata = JSONObject()
            .put("metadataType", if (mime.startsWith("audio/")) 3 else if (mime.startsWith("image/")) 4 else 0)
            .put("title", media.title)
            .put(if (mime.startsWith("audio/")) "artist" else "subtitle", media.subtitle)
        val info = JSONObject()
            .put("contentId", media.url)
            .put("contentUrl", media.url)
            .put("contentType", mime)
            .put("streamType", if (media.live) "LIVE" else "BUFFERED")
            .put("metadata", metadata)
        val extra = JSONObject()
        if (media.subtitleUrl != null) {
            info.put(
                "tracks",
                JSONArray().put(
                    JSONObject()
                        .put("trackId", SUBTITLE_TRACK)
                        .put("type", "TEXT")
                        .put("subtype", "SUBTITLES")
                        .put("trackContentId", media.subtitleUrl)
                        .put("trackContentType", "text/vtt")
                        .put("name", "Subtitles"),
                ),
            )
            info.put(
                "textTrackStyle",
                media.subtitleStyle?.toCastJson()
                    ?: JSONObject().put("backgroundColor", "#00000099").put("foregroundColor", "#FFFFFFFF").put("fontScale", 1.0),
            )
            if (media.subtitlesOn) extra.put("activeTrackIds", JSONArray().put(SUBTITLE_TRACK))
        }
        loading = true
        try {
            val payload = JSONObject()
                .put("type", "LOAD")
                .put("sessionId", a.sessionId)
                .put("media", info)
                .put("autoplay", true)
                .put("currentTime", media.startMs / 1000.0)
            extra.keys().forEach { payload.put(it, extra.get(it)) }
            val status = c.loadRaw(a, payload)
            mediaSessionId = status.optInt("mediaSessionId", -1)
            return parse(status)
        } finally {
            loading = false
        }
    }

    private suspend fun command(type: String, extra: (JSONObject) -> Unit = {}): RendererStatus? {
        val c = client ?: return null
        val a = app ?: return null
        if (mediaSessionId < 0) return null
        return c.mediaCommand(a, mediaSessionId, type, extra)?.let { parse(it) }?.also { listener?.onStatus(it) }
    }

    override suspend fun play() { command("PLAY") }
    override suspend fun pause() { command("PAUSE") }
    override suspend fun seek(positionMs: Long) { command("SEEK") { it.put("currentTime", positionMs / 1000.0) } }
    override suspend fun refresh(): RendererStatus? = command("GET_STATUS")

    override suspend fun setSubtitlesVisible(on: Boolean) {
        command("EDIT_TRACKS_INFO") { it.put("activeTrackIds", if (on) JSONArray().put(SUBTITLE_TRACK) else JSONArray()) }
    }

    override suspend fun setVolume(level: Double) { client?.setVolume(level) }
    override suspend fun setMuted(muted: Boolean) { client?.setMuted(muted) }

    override suspend fun stop() {
        val c = client ?: return
        val a = app ?: return
        if (c.isConnected) c.stopApp(a)
    }

    override fun close() {
        client?.close()
    }

    companion object {
        private const val SUBTITLE_TRACK = 1

        fun parse(status: JSONObject): RendererStatus {
            val state = when (status.optString("playerState")) {
                "PLAYING" -> PlayState.PLAYING
                "PAUSED" -> PlayState.PAUSED
                "BUFFERING", "LOADING" -> PlayState.BUFFERING
                "IDLE" -> when (status.optString("idleReason")) {
                    "FINISHED" -> PlayState.FINISHED
                    "ERROR" -> PlayState.ERROR
                    "CANCELLED", "INTERRUPTED" -> PlayState.STOPPED
                    else -> PlayState.IDLE
                }
                else -> PlayState.IDLE
            }
            val duration = status.optJSONObject("media")?.optDouble("duration", 0.0)?.takeIf { it > 0 && !it.isInfinite() }
            val position = if (status.has("currentTime")) status.optDouble("currentTime", 0.0) else null
            return RendererStatus(state, position?.let { (it * 1000).toLong() }, duration?.let { (it * 1000).toLong() })
        }
    }
}
