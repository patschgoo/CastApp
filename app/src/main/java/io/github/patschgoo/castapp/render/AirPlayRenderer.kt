package io.github.patschgoo.castapp.render

import android.util.Log
import io.github.patschgoo.castapp.airplay.AirPlayConnection
import io.github.patschgoo.castapp.airplay.AirPlayCredentials
import io.github.patschgoo.castapp.airplay.AirPlayFeatures
import io.github.patschgoo.castapp.airplay.AirPlayPairing
import io.github.patschgoo.castapp.airplay.AirPlayPairingStore
import io.github.patschgoo.castapp.airplay.BinaryPlist
import io.github.patschgoo.castapp.airplay.HapCrypto
import io.github.patschgoo.castapp.airplay.HapSession
import io.github.patschgoo.castapp.airplay.HapStream
import io.github.patschgoo.castapp.airplay.PinRequiredException
import io.github.patschgoo.castapp.cast.CastException
import io.github.patschgoo.castapp.discovery.CastDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * Plays media on an Apple TV (or a TV with AirPlay) by URL, the way pyatv does
 * with real Apple TVs: pairing (without a code where the TV allows it, else with
 * the code shown on the TV, remembered afterwards), an encrypted control
 * connection, and for AirPlay 2 an RTSP session with timing replies and an event
 * channel. The TV fetches the media from the phone or the web by itself.
 */
class AirPlayRenderer(
    override val device: CastDevice,
    private val store: AirPlayPairingStore,
    private val askPin: suspend (deviceName: String) -> String?,
) : MediaRenderer {
    override var listener: MediaRenderer.Listener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val features = AirPlayFeatures.parse(device.airplayFeatures)
    private val v2 = AirPlayFeatures.isV2(features)
    @Volatile private var conn: AirPlayConnection? = null
    @Volatile private var shared: ByteArray? = null
    @Volatile private var closed = false
    @Volatile private var sessionReady = false
    @Volatile private var started = false
    @Volatile private var loadedAt = 0L
    @Volatile private var lastPosition = 0L
    @Volatile private var lastDuration = 0L
    @Volatile private var failures = 0
    @Volatile private var volume = 1.0
    private var poller: Job? = null
    private var feedback: Job? = null
    private var timing: DatagramSocket? = null
    private var events: HapStream? = null

    private val rtspSession = Random.nextLong(0, 1L shl 32)
    private val dacpId = Random.nextLong().toULong().toString(16).uppercase(Locale.ROOT)
    private val activeRemote = Random.nextLong(0, 1L shl 32)
    private val sessionUuid = UUID.randomUUID().toString()
    private var cseq = 0

    override val localAddress: InetAddress? get() = conn?.localAddress

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val c = try {
            open()
        } catch (e: CastException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "AirPlay connect failed", e)
            throw CastException("Can't connect to ${device.name}: ${e.message}")
        }
        conn = c
        startPolling()
    }

    /** Connects and authenticates, pairing with the code on the TV if needed. */
    private suspend fun open(): AirPlayConnection {
        var c = AirPlayConnection(device.host, device.port)
        val saved = store.load(device.id)
        try {
            when {
                saved != null -> shared = AirPlayPairing.verify(c, saved)
                AirPlayFeatures.needsPairing(features) -> shared = AirPlayPairing.transient(c)
            }
        } catch (e: PinRequiredException) {
            Log.i(TAG, "${device.name}: ${e.message}; pairing with a code")
            c.close()
            if (saved != null) store.clear(device.id)
            val creds = pairWithPin()
            c = AirPlayConnection(device.host, device.port)
            shared = AirPlayPairing.verify(c, creds)
        }
        shared?.let { AirPlayPairing.encrypt(c, it) }
        return c
    }

    private suspend fun pairWithPin(): AirPlayCredentials {
        val c = AirPlayConnection(device.host, device.port)
        try {
            val pairing = AirPlayPairing.WithPin(c)
            pairing.start()
            // The TV now shows a 4-digit code.
            val pin = askPin(device.name) ?: throw CastException("Pairing with ${device.name} was cancelled")
            val creds = try {
                pairing.finish(pin)
            } catch (_: AirPlayPairing.WrongPinException) {
                throw CastException("That code wasn't right. Cast again to get a new code.")
            }
            store.save(device.id, creds)
            return creds
        } finally {
            c.close()
        }
    }

    // ---- AirPlay 2 session -----------------------------------------------------------------------

    private fun rtsp(method: String, uri: String? = null, body: Any? = null, contentType: String? = null, extra: Map<String, Any> = emptyMap()): AirPlayConnection.Response {
        val c = conn ?: throw IOException("Not connected")
        val headers = LinkedHashMap<String, Any>()
        headers["CSeq"] = synchronized(this) { cseq++ }
        headers["DACP-ID"] = dacpId
        headers["Active-Remote"] = activeRemote
        headers["Client-Instance"] = dacpId
        headers.putAll(extra)
        val bytes = when (body) {
            null -> null
            is ByteArray -> body
            is String -> body.toByteArray(Charsets.UTF_8)
            else -> {
                headers["Content-Type"] = BPLIST
                BinaryPlist.encode(body)
            }
        }
        return c.request(method, uri ?: "rtsp://${c.localAddress.hostAddress}/$rtspSession", "RTSP/1.0", headers, bytes, contentType)
    }

    private fun setUpSession() {
        if (sessionReady || !v2) return
        val c = conn ?: return
        val timingSocket = DatagramSocket(0).also { timing = it }
        Thread({ timingLoop(timingSocket) }, "airplay-timing").apply { isDaemon = true; start() }
        val resp = rtsp(
            "SETUP",
            body = mapOf(
                "deviceID" to SENDER_MAC,
                "sessionUUID" to UUID.randomUUID().toString().uppercase(Locale.ROOT),
                "timingPort" to timingSocket.localPort,
                "timingProtocol" to "NTP",
                "isMultiSelectAirPlay" to true,
                "groupContainsGroupLeader" to false,
                "macAddress" to SENDER_MAC,
                "model" to SENDER_MODEL,
                "name" to "CastApp",
                "osBuildVersion" to OS_BUILD,
                "osName" to "iPhone OS",
                "osVersion" to "16.5",
                "senderSupportsRelay" to false,
                "sourceVersion" to "690.7.1",
                "statsCollectionEnabled" to false,
            ),
        )
        if (!resp.ok) throw IOException("SETUP failed (${resp.code})")
        val eventPort = (resp.plist()["eventPort"] as? Number)?.toInt() ?: 0
        if (eventPort > 0) openEventChannel(c.host, eventPort)
        feedback = scope.launch {
            while (!closed) {
                delay(2000)
                try { rtsp("POST", "/feedback") } catch (_: Exception) {}
            }
        }
        rtsp("RECORD")
        sessionReady = true
    }

    /** The TV reports events on a second connection; it only needs polite answers. */
    private fun openEventChannel(host: String, port: Int) {
        val socket = Socket()
        var tries = 0
        while (true) {
            try {
                socket.connect(InetSocketAddress(host, port), 4000)
                break
            } catch (e: IOException) {
                if (++tries >= 5) {
                    Log.i(TAG, "No event channel: ${e.message}")
                    return
                }
                Thread.sleep(500)
            }
        }
        val stream = HapStream(socket)
        shared?.let {
            stream.session = HapSession(
                HapCrypto.hkdf("Events-Salt", "Events-Read-Encryption-Key", it),
                HapCrypto.hkdf("Events-Salt", "Events-Write-Encryption-Key", it),
            )
        }
        events = stream
        Thread({
            try {
                while (!closed) {
                    val (first, headers, _) = stream.readMessage() ?: break
                    val protocol = first.substringAfterLast(' ').ifEmpty { "RTSP/1.0" }
                    val reply = StringBuilder("$protocol 200 OK\r\nContent-Length: 0\r\nAudio-Latency: 0\r\n")
                    headers["server"]?.let { reply.append("Server: ").append(it).append("\r\n") }
                    headers["cseq"]?.let { reply.append("CSeq: ").append(it).append("\r\n") }
                    reply.append("\r\n")
                    stream.write(reply.toString().toByteArray(Charsets.UTF_8))
                }
            } catch (_: Exception) {
            }
        }, "airplay-events").apply { isDaemon = true; start() }
    }

    /** Answers NTP-style timing requests (the TV syncs its clock to ours). */
    private fun timingLoop(socket: DatagramSocket) {
        val buf = ByteArray(128)
        while (!closed && !socket.isClosed) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                if (packet.length < 32) continue
                val (sec, frac) = ntpNow()
                val resp = ByteArray(32)
                resp[0] = buf[0]
                resp[1] = (0x53 or 0x80).toByte()
                resp[3] = 7
                System.arraycopy(buf, 24, resp, 8, 8) // their send time becomes our reference time
                putInt(resp, 16, sec); putInt(resp, 20, frac)
                putInt(resp, 24, sec); putInt(resp, 28, frac)
                socket.send(DatagramPacket(resp, resp.size, packet.socketAddress))
            } catch (_: Exception) {
                if (closed) return
            }
        }
    }

    private fun ntpNow(): Pair<Long, Long> {
        val nowUs = System.currentTimeMillis() * 1000
        val sec = nowUs / 1_000_000 + 0x83AA7E80L
        val frac = ((nowUs % 1_000_000) shl 32) / 1_000_000
        return sec to frac
    }

    private fun putInt(b: ByteArray, pos: Int, v: Long) {
        for (i in 0 until 4) b[pos + i] = (v ushr (24 - 8 * i)).toByte()
    }

    // ---- playing ---------------------------------------------------------------------------------

    override suspend fun load(media: RendererMedia): RendererStatus = withContext(Dispatchers.IO) {
        started = false
        lastPosition = 0
        lastDuration = 0
        loadedAt = System.currentTimeMillis()
        val c = conn ?: throw CastException("Not connected to ${device.name}")
        if (v2) {
            setUpSession()
            val body = mapOf(
                "Content-Location" to media.url,
                "Start-Position-Seconds" to media.startMs / 1000.0,
                "uuid" to UUID.randomUUID().toString(),
                "streamType" to 1,
                "mediaType" to "file",
                "mightSupportStorePastisKeyRequests" to true,
                "playbackRestrictions" to 0,
                "secureConnectionMs" to 22,
                "volume" to 1.0,
                "infoMs" to 122,
                "connectMs" to 18,
                "authMs" to 0,
                "bonjourMs" to 0,
                "referenceRestrictions" to 3,
                "SenderMACAddress" to SENDER_MAC,
                "model" to SENDER_MODEL,
                "postAuthMs" to 0,
                "clientBundleID" to "io.github.patschgoo.castapp",
                "clientProcName" to "CastApp",
                "osBuildVersion" to OS_BUILD,
                "rate" to 1.0,
            )
            postPlay(c, body, mapOf("X-Apple-ProtocolVersion" to "1", "X-Apple-Session-ID" to sessionUuid, "X-Apple-Stream-ID" to "1"))
            // Without these the TV starts paused.
            try {
                rtsp("PUT", "/setProperty?isInterestedInDateRange", body = mapOf("value" to true))
                rtsp("PUT", "/setProperty?actionAtItemEnd", body = mapOf("value" to 0))
                rtsp("POST", "/rate?value=1.000000")
                val zero = mapOf("flags" to 0, "value" to 0, "epoch" to 0, "timescale" to 0)
                rtsp("PUT", "/setProperty?forwardEndTime", body = mapOf("value" to zero))
                rtsp("PUT", "/setProperty?reverseEndTime", body = mapOf("value" to zero))
            } catch (e: Exception) {
                Log.i(TAG, "Property setup: ${e.message}")
            }
        } else {
            postPlay(
                c,
                mapOf("Content-Location" to media.url, "Start-Position" to 0.0, "X-Apple-Session-ID" to sessionUuid),
                mapOf("X-Apple-Session-ID" to sessionUuid),
                userAgent = "MediaControl/1.0",
            )
            if (media.startMs > 0) {
                scope.launch {
                    for (i in 0 until 20) {
                        if (started) break
                        delay(500)
                    }
                    try { seekNow(media.startMs) } catch (_: Exception) {}
                }
            }
        }
        RendererStatus(PlayState.BUFFERING)
    }

    private suspend fun postPlay(c: AirPlayConnection, body: Map<String, Any?>, headers: Map<String, Any>, userAgent: String = "AirPlay/550.10") {
        var tries = 0
        while (true) {
            val resp = c.request("POST", "/play", headers = headers, body = BinaryPlist.encode(body), contentType = BPLIST, userAgent = userAgent)
            when {
                resp.ok -> return
                // Apple TVs sometimes answer 500 at first; try again like pyatv does.
                resp.code == 500 && ++tries < 3 -> delay(1000)
                else -> throw CastException("${device.name} refused to play (${resp.code})")
            }
        }
    }

    private fun command(path: String) {
        val c = conn ?: return
        if (v2 && sessionReady) rtsp("POST", path) else c.request("POST", path, userAgent = "MediaControl/1.0")
    }

    override suspend fun play() = withContext(Dispatchers.IO) { command("/rate?value=1.000000"); poll(); Unit }
    override suspend fun pause() = withContext(Dispatchers.IO) { command("/rate?value=0.000000"); poll(); Unit }
    override suspend fun seek(positionMs: Long) = withContext(Dispatchers.IO) { seekNow(positionMs) }

    private fun seekNow(positionMs: Long) {
        command(String.format(Locale.ROOT, "/scrub?position=%.6f", positionMs / 1000.0))
    }

    override suspend fun refresh(): RendererStatus? = withContext(Dispatchers.IO) { poll() }

    override suspend fun setSubtitlesVisible(on: Boolean) {
        // AirPlay URL playback has no side-loaded subtitles.
    }

    override suspend fun setVolume(level: Double) = withContext(Dispatchers.IO) {
        volume = level.coerceIn(0.0, 1.0)
        sendVolume(volume)
        listener?.onVolume(volume, false)
        Unit
    }

    override suspend fun setMuted(muted: Boolean) = withContext(Dispatchers.IO) {
        sendVolume(if (muted) 0.0 else volume)
        listener?.onVolume(volume, muted)
        Unit
    }

    private fun sendVolume(level: Double) {
        if (!v2 || !sessionReady) return
        val db = if (level <= 0.0) -144.0 else -30.0 + 30.0 * level
        try {
            rtsp("SET_PARAMETER", body = String.format(Locale.ROOT, "volume: %.6f", db), contentType = "text/parameters")
        } catch (e: Exception) {
            Log.i(TAG, "Volume: ${e.message}")
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        poller?.cancel()
        try { conn?.request("POST", "/stop", userAgent = "MediaControl/1.0") } catch (_: Exception) {}
        if (v2 && sessionReady) try { rtsp("TEARDOWN", extra = mapOf("Session" to rtspSession)) } catch (_: Exception) {}
        Unit
    }

    override fun close() {
        closed = true
        scope.cancel()
        feedback?.cancel()
        try { timing?.close() } catch (_: Exception) {}
        events?.close()
        conn?.close()
    }

    // ---- status ----------------------------------------------------------------------------------

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
        val c = conn ?: return null
        return try {
            val resp = c.request("GET", "/playback-info", userAgent = "MediaControl/1.0")
            failures = 0
            val info = resp.plist()
            val duration = (info["duration"] as? Number)?.toDouble()?.takeIf { it > 0 && it.isFinite() }
            val position = (info["position"] as? Number)?.toDouble()?.takeIf { it.isFinite() }
            val rate = (info["rate"] as? Number)?.toDouble() ?: 0.0
            if (duration != null) lastDuration = (duration * 1000).toLong()
            if (position != null) lastPosition = (position * 1000).toLong()
            val state = when {
                info.containsKey("error") -> PlayState.ERROR
                duration != null || (position != null && position > 0) -> {
                    started = true
                    if (rate > 0) PlayState.PLAYING else PlayState.PAUSED
                }
                started -> if (lastDuration > 0 && lastPosition >= lastDuration - 4000) PlayState.FINISHED else PlayState.STOPPED
                loadedAt > 0 && System.currentTimeMillis() - loadedAt > 30_000 -> PlayState.ERROR
                loadedAt > 0 -> PlayState.BUFFERING
                else -> PlayState.IDLE
            }
            val status = RendererStatus(state, position?.let { (it * 1000).toLong() }, duration?.let { (it * 1000).toLong() })
            listener?.onStatus(status)
            if (state == PlayState.FINISHED || state == PlayState.STOPPED) {
                started = false
                loadedAt = 0
            }
            status
        } catch (e: Exception) {
            if (++failures >= 4 && !closed) {
                failures = 0
                listener?.onLost(e)
            }
            null
        }
    }

    companion object {
        private const val TAG = "AirPlayRenderer"
        private const val BPLIST = "application/x-apple-binary-plist"
        private const val SENDER_MAC = "AA:BB:CC:DD:EE:FF"
        private const val SENDER_MODEL = "iPhone14,3"
        private const val OS_BUILD = "20F66"
    }
}
