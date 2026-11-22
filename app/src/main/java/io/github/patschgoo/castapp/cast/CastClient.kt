package io.github.patschgoo.castapp.cast

import android.annotation.SuppressLint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class CastException(message: String) : IOException(message)

/** An application running on the receiver that we can talk to. */
class RunningApp(val appId: String, val sessionId: String, val transportId: String)

/**
 * A direct Google Cast v2 connection to a receiver (Chromecast, Android TV /
 * NVIDIA Shield, Google TV, ...). Talks to the device by IP address over
 * TLS on port 8009, so it does not depend on Google Play Services or on the
 * system's device discovery.
 */
class CastClient(
    val host: String,
    val port: Int = DEFAULT_PORT,
    private val useTls: Boolean = true,
) : Closeable {

    interface Listener {
        fun onReceiverStatus(status: JSONObject) {}
        fun onMediaStatus(status: JSONObject) {}
        /** The receiver app we connected to was closed (e.g. from the TV remote or another phone). */
        fun onAppClosed() {}
        fun onDisconnected(error: Throwable?) {}
        /** A message on an app-specific namespace (our low-latency TV player). */
        fun onCustomMessage(namespace: String, json: JSONObject) {}
    }

    class Incoming(val namespace: String, val sourceId: String, val json: JSONObject) {
        val type: String get() = json.optString("type")
        val requestId: Int get() = json.optInt("requestId", -1)
    }

    private class Waiter(val match: (Incoming) -> Boolean) {
        val result = CompletableDeferred<Incoming>()
    }

    @Volatile var listener: Listener? = null

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private val writeLock = Any()
    private val waiters = CopyOnWriteArrayList<Waiter>()
    private val requestIds = AtomicInteger(1)
    private val connectedTransports = HashSet<String>()
    private var heartbeat: ScheduledExecutorService? = null
    @Volatile private var lastMessageAt = 0L
    @Volatile private var closed = false
    @Volatile private var watchedTransport: String? = null

    /** The local address the receiver reaches us on; used to build the stream URL. */
    val localAddress: InetAddress? get() = socket?.localAddress

    val isConnected: Boolean get() = socket?.isConnected == true && !closed

    /** Opens the connection. Blocking; call from a background thread. */
    fun connect(timeoutMs: Int = 5000) {
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        val s: Socket = if (useTls) {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(TrustAllManager), SecureRandom())
            (ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket).apply {
                soTimeout = timeoutMs
                startHandshake()
            }
        } else {
            raw
        }
        s.soTimeout = 0
        socket = s
        output = s.getOutputStream()
        lastMessageAt = System.currentTimeMillis()

        Thread({ readLoop(DataInputStream(s.getInputStream().buffered())) }, "cast-reader").apply {
            isDaemon = true
            start()
        }
        connectChannel(RECEIVER_ID)
        heartbeat = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "cast-heartbeat").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay({ heartbeatTick() }, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun heartbeatTick() {
        if (closed) return
        if (System.currentTimeMillis() - lastMessageAt > HEARTBEAT_MS * 4) {
            fail(CastException("The TV stopped responding"))
            return
        }
        try {
            send(NS_HEARTBEAT, RECEIVER_ID, JSONObject().put("type", "PING"))
        } catch (e: IOException) {
            fail(e)
        }
    }

    private fun connectChannel(destination: String) {
        synchronized(connectedTransports) {
            if (!connectedTransports.add(destination)) return
        }
        val payload = JSONObject()
            .put("type", "CONNECT")
            .put("origin", JSONObject())
            .put("userAgent", "CastApp")
            .put(
                "senderInfo",
                JSONObject()
                    .put("sdkType", 2)
                    .put("version", "15.605.1.3")
                    .put("browserVersion", "44.0.2403.30")
                    .put("platform", 4)
                    .put("systemVersion", "Android")
                    .put("connectionType", 1),
            )
        send(NS_CONNECTION, destination, payload)
    }

    fun send(namespace: String, destination: String, payload: JSONObject) {
        val out = output ?: throw CastException("Not connected")
        val msg = CastMessage(SENDER_ID, destination, namespace, payload.toString())
        synchronized(writeLock) {
            CastMessage.writeFramed(out, msg)
        }
    }

    private fun readLoop(input: DataInputStream) {
        try {
            while (!closed) {
                val msg = CastMessage.readFramed(input)
                lastMessageAt = System.currentTimeMillis()
                val text = msg.payloadUtf8 ?: continue
                val json = try { JSONObject(text) } catch (_: Exception) { continue }
                dispatch(Incoming(msg.namespace, msg.sourceId, json))
            }
        } catch (e: Exception) {
            fail(if (closed) null else e)
        }
    }

    private fun dispatch(msg: Incoming) {
        when (msg.namespace) {
            NS_HEARTBEAT -> if (msg.type == "PING") {
                try { send(NS_HEARTBEAT, msg.sourceId, JSONObject().put("type", "PONG")) } catch (_: IOException) {}
            }
            NS_CONNECTION -> if (msg.type == "CLOSE") {
                if (msg.sourceId == RECEIVER_ID) {
                    fail(CastException("The TV closed the connection"))
                } else if (msg.sourceId == watchedTransport) {
                    listener?.onAppClosed()
                }
            }
            NS_RECEIVER -> if (msg.type == "RECEIVER_STATUS") {
                val status = msg.json.optJSONObject("status")
                if (status != null) {
                    val watched = watchedTransport
                    if (watched != null && findApp(status) { it.optString("transportId") == watched } == null) {
                        listener?.onAppClosed()
                    }
                    listener?.onReceiverStatus(status)
                }
            }
            NS_MEDIA -> if (msg.type == "MEDIA_STATUS") {
                val list = msg.json.optJSONArray("status")
                if (list != null && list.length() > 0) list.optJSONObject(0)?.let { listener?.onMediaStatus(it) }
            }
            else -> listener?.onCustomMessage(msg.namespace, msg.json)
        }
        for (w in waiters) {
            if (w.match(msg)) w.result.complete(msg)
        }
    }

    private suspend fun awaitMessage(timeoutMs: Long, match: (Incoming) -> Boolean, sendAction: () -> Unit): Incoming {
        val waiter = Waiter(match)
        waiters.add(waiter)
        try {
            sendAction()
            return withTimeout(timeoutMs) { waiter.result.await() }
        } catch (e: TimeoutCancellationException) {
            throw CastException("The TV did not answer in time")
        } finally {
            waiters.remove(waiter)
        }
    }

    private suspend fun request(namespace: String, destination: String, payload: JSONObject, timeoutMs: Long): Incoming {
        val id = requestIds.getAndIncrement()
        payload.put("requestId", id)
        return awaitMessage(timeoutMs, { it.namespace == namespace && it.requestId == id }) {
            send(namespace, destination, payload)
        }
    }

    suspend fun getReceiverStatus(timeoutMs: Long = 5000): JSONObject {
        val reply = request(NS_RECEIVER, RECEIVER_ID, JSONObject().put("type", "GET_STATUS"), timeoutMs)
        return reply.json.optJSONObject("status") ?: JSONObject()
    }

    /** Launches (or joins) [appId] on the receiver and opens a channel to it. */
    suspend fun launch(appId: String, timeoutMs: Long = 25_000): RunningApp {
        val id = requestIds.getAndIncrement()
        val payload = JSONObject().put("type", "LAUNCH").put("appId", appId).put("requestId", id)
        val reply = awaitMessage(timeoutMs, { m ->
            m.namespace == NS_RECEIVER && (
                (m.requestId == id && m.type != "RECEIVER_STATUS") ||
                    (m.type == "RECEIVER_STATUS" && appFrom(m.json.optJSONObject("status"), appId) != null)
                )
        }) { send(NS_RECEIVER, RECEIVER_ID, payload) }
        if (reply.type != "RECEIVER_STATUS") {
            throw CastException("The TV could not start the player (${reply.json.optString("reason", reply.type)})")
        }
        val app = appFrom(reply.json.optJSONObject("status"), appId)
            ?: throw CastException("The TV did not start the player")
        connectChannel(app.transportId)
        watchedTransport = app.transportId
        return app
    }

    /** Sends a media LOAD request and returns the first media status. */
    suspend fun load(app: RunningApp, media: JSONObject, timeoutMs: Long = 30_000): JSONObject {
        val payload = JSONObject()
            .put("type", "LOAD")
            .put("sessionId", app.sessionId)
            .put("media", media)
            .put("autoplay", true)
            .put("currentTime", 0)
        return loadRaw(app, payload, timeoutMs)
    }

    /** Sends a ready-made LOAD [payload] (with tracks, start time, ...) and returns the first media status. */
    suspend fun loadRaw(app: RunningApp, payload: JSONObject, timeoutMs: Long = 30_000): JSONObject {
        val reply = request(NS_MEDIA, app.transportId, payload, timeoutMs)
        if (reply.type != "MEDIA_STATUS") {
            val detail = reply.json.optString("detailedErrorCode", "").ifEmpty { reply.json.optString("reason", "") }
            throw CastException("The TV could not play this (${reply.type}${if (detail.isNotEmpty()) ", $detail" else ""})")
        }
        val list = reply.json.optJSONArray("status")
        return list?.optJSONObject(0) ?: JSONObject()
    }

    /**
     * Sends a media command (PLAY, PAUSE, SEEK, GET_STATUS, ...) for [mediaSessionId]
     * and returns the resulting media status, if any.
     */
    suspend fun mediaCommand(app: RunningApp, mediaSessionId: Int, type: String, extra: (JSONObject) -> Unit = {}): JSONObject? {
        val payload = JSONObject().put("type", type).put("mediaSessionId", mediaSessionId)
        extra(payload)
        val reply = request(NS_MEDIA, app.transportId, payload, 8000)
        return reply.json.optJSONArray("status")?.optJSONObject(0)
    }

    /** Sends [payload] to the app on [namespace] and waits for the reply with the same requestId. */
    suspend fun requestCustom(app: RunningApp, namespace: String, payload: JSONObject, timeoutMs: Long): JSONObject =
        request(namespace, app.transportId, payload, timeoutMs).json

    /**
     * Sends [payload] to the app on [namespace] and waits for the first message on that
     * namespace that [match]es (for apps such as YouTube that don't echo the requestId).
     */
    suspend fun requestCustomMatching(
        app: RunningApp,
        namespace: String,
        payload: JSONObject,
        timeoutMs: Long,
        match: (JSONObject) -> Boolean,
    ): JSONObject {
        payload.put("requestId", requestIds.getAndIncrement())
        return awaitMessage(timeoutMs, { it.namespace == namespace && match(it.json) }) {
            send(namespace, app.transportId, payload)
        }.json
    }

    suspend fun setVolume(level: Double) {
        val volume = JSONObject().put("level", level.coerceIn(0.0, 1.0))
        request(NS_RECEIVER, RECEIVER_ID, JSONObject().put("type", "SET_VOLUME").put("volume", volume), 5000)
    }

    suspend fun setMuted(muted: Boolean) {
        val volume = JSONObject().put("muted", muted)
        request(NS_RECEIVER, RECEIVER_ID, JSONObject().put("type", "SET_VOLUME").put("volume", volume), 5000)
    }

    /** Closes the receiver app (the TV returns to its home/ambient screen). */
    suspend fun stopApp(app: RunningApp) {
        watchedTransport = null
        request(NS_RECEIVER, RECEIVER_ID, JSONObject().put("type", "STOP").put("sessionId", app.sessionId), 5000)
    }

    private fun fail(error: Throwable?) {
        if (closed) return
        closed = true
        cleanup()
        for (w in waiters) w.result.completeExceptionally(error ?: CastException("Disconnected"))
        listener?.onDisconnected(error)
    }

    private fun cleanup() {
        heartbeat?.shutdownNow()
        heartbeat = null
        try { socket?.close() } catch (_: IOException) {}
    }

    override fun close() {
        if (closed) return
        try {
            send(NS_CONNECTION, RECEIVER_ID, JSONObject().put("type", "CLOSE"))
        } catch (_: Exception) {
        }
        closed = true
        cleanup()
        for (w in waiters) w.result.completeExceptionally(CastException("Closed"))
    }

    // Cast receivers present self-signed certificates and the connection never leaves the
    // local network, so (like other Cast senders) we do not validate them against a CA.
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        const val DEFAULT_PORT = 8009
        const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
        const val SENDER_ID = "sender-0"
        const val RECEIVER_ID = "receiver-0"
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        private const val HEARTBEAT_MS = 5000L

        private fun findApp(status: JSONObject, predicate: (JSONObject) -> Boolean): JSONObject? {
            val apps = status.optJSONArray("applications") ?: return null
            for (i in 0 until apps.length()) {
                val app = apps.optJSONObject(i) ?: continue
                if (predicate(app)) return app
            }
            return null
        }

        fun appFrom(status: JSONObject?, appId: String): RunningApp? {
            if (status == null) return null
            val app = findApp(status) { it.optString("appId") == appId && it.optString("transportId").isNotEmpty() }
                ?: return null
            return RunningApp(appId, app.optString("sessionId"), app.optString("transportId"))
        }

        /** Media info for a file served from the phone (seekable, with a known length). */
        fun fileMedia(url: String, mimeType: String, title: String, subtitle: String): JSONObject {
            val metadata = JSONObject()
                .put("metadataType", if (mimeType.startsWith("audio/")) 3 else 0)
                .put("title", title)
                .put(if (mimeType.startsWith("audio/")) "artist" else "subtitle", subtitle)
            return JSONObject()
                .put("contentId", url)
                .put("contentUrl", url)
                .put("contentType", mimeType)
                .put("streamType", "BUFFERED")
                .put("metadata", metadata)
        }

        /** Media info for the live sound-only stream (speakers, or a TV without picture). */
        fun liveAudioMedia(url: String, title: String, subtitle: String): JSONObject = JSONObject()
            .put("contentId", url)
            .put("contentUrl", url)
            .put("contentType", "audio/aac")
            .put("streamType", "LIVE")
            .put("metadata", JSONObject().put("metadataType", 3).put("title", title).put("artist", subtitle))

        /** Media info for the live stream, understood by the Default Media Receiver. */
        fun liveMedia(url: String, hls: Boolean, title: String, subtitle: String): JSONObject {
            val metadata = JSONObject().put("metadataType", 0).put("title", title).put("subtitle", subtitle)
            val media = JSONObject()
                .put("contentId", url)
                .put("contentUrl", url)
                .put("streamType", "LIVE")
                .put("metadata", metadata)
            if (hls) {
                media.put("contentType", "application/x-mpegurl")
                media.put("hlsSegmentFormat", "fmp4")
                media.put("hlsVideoSegmentFormat", "fmp4")
            } else {
                media.put("contentType", "video/mp4")
            }
            return media
        }
    }
}
