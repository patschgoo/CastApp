package io.github.patschgoo.castapp.tv

import io.github.patschgoo.castapp.dlna.Dlna
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/** What is being played on the TV. */
enum class TvMediaKind { VIDEO, AUDIO, PHOTO }

data class TvMedia(
    val uri: String,
    val metadata: String,
    val title: String,
    val mimeType: String?,
    val kind: TvMediaKind,
    val subtitleUri: String?,
    val live: Boolean,
    /** Screen mirroring from CastApp: play with as little buffer as possible. */
    val lowLatency: Boolean,
)

enum class TransportState(val upnp: String) {
    NO_MEDIA_PRESENT("NO_MEDIA_PRESENT"),
    STOPPED("STOPPED"),
    PLAYING("PLAYING"),
    PAUSED("PAUSED_PLAYBACK"),
    TRANSITIONING("TRANSITIONING"),
}

data class TvSnapshot(
    val state: TransportState = TransportState.NO_MEDIA_PRESENT,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 100,
    val muted: Boolean = false,
)

/**
 * CastApp's own extras on top of DLNA (only between CastApp devices): a phone
 * asks a CastApp TV to mirror its screen back to it ("swap direction").
 */
interface CastAppCommands {
    /** Mirror this device's screen to the receiver described at [descriptionUrl]; returns null, or what went wrong. */
    fun mirrorTo(descriptionUrl: String): String?

    /** Stop mirroring this device's screen. */
    fun stopMirror()
}

/** The TV side of the renderer: called from network threads, must not block. */
interface RendererBackend {
    fun setMedia(media: TvMedia)
    fun play()
    fun pause()
    fun stop()
    fun seek(positionMs: Long)
    fun setVolume(percent: Int)
    fun setMuted(muted: Boolean)
    val snapshot: TvSnapshot
}

/**
 * A UPnP/DLNA media renderer, so this TV box (Fire TV, Android TV, Google TV…)
 * can be cast to: CastApp on a phone finds it like any smart TV, and so do other
 * DLNA apps. It answers SSDP searches, serves its device description and handles
 * the AVTransport, RenderingControl and ConnectionManager SOAP actions.
 */
class UpnpRenderer(
    @Volatile var friendlyName: String,
    private val uuid: String,
    private val backend: RendererBackend,
    private val modelNumber: String = "1",
) {
    @Volatile var port = 0
        private set

    /** Handles CastApp's swap-direction requests; null = not offered. */
    @Volatile var commands: CastAppCommands? = null
    @Volatile private var running = false
    @Volatile private var media: TvMedia? = null
    private var server: ServerSocket? = null
    private var ssdpSocket: MulticastSocket? = null
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "upnp-http").apply { isDaemon = true } }
    private val ifacesLock = Any()
    private var ifaces: List<NetworkInterface> = emptyList()

    /** Starts the HTTP server (on [preferredPort] if free) and, if [ssdp], answers network searches. */
    fun start(preferredPort: Int = 0, ssdp: Boolean = true, interfaces: List<NetworkInterface> = emptyList()) {
        if (running) return
        running = true
        val ss = try {
            ServerSocket(preferredPort)
        } catch (_: IOException) {
            ServerSocket(0)
        }
        server = ss
        port = ss.localPort
        Thread({ acceptLoop(ss) }, "upnp-accept").apply { isDaemon = true; start() }
        if (ssdp) {
            synchronized(ifacesLock) { ifaces = interfaces }
            startSsdp()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try { sendNotify("ssdp:byebye") } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        try { ssdpSocket?.close() } catch (_: Exception) {}
        server = null
        ssdpSocket = null
    }

    /** The Wi-Fi changed: join the search group again on the new interfaces and announce ourselves. */
    fun networkChanged(interfaces: List<NetworkInterface>) {
        synchronized(ifacesLock) { ifaces = interfaces }
        joinGroups()
        try { sendNotify("ssdp:alive") } catch (_: Exception) {}
    }

    // ---- HTTP --------------------------------------------------------------------------------

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try { ss.accept() } catch (_: IOException) { break }
            workers.execute { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 10_000
                val input = BufferedInputStream(s.getInputStream())
                val request = readRequest(input) ?: return
                val response = handle(request, s.localAddress, s.inetAddress)
                val out = s.getOutputStream()
                val head = StringBuilder()
                    .append("HTTP/1.1 ").append(response.code).append(' ').append(response.reason).append("\r\n")
                    .append("Content-Type: ").append(response.type).append("\r\n")
                    .append("Content-Length: ").append(response.body.size).append("\r\n")
                    .append("Server: ").append(SERVER).append("\r\n")
                    .append("Connection: close\r\n")
                response.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
                head.append("\r\n")
                out.write(head.toString().toByteArray(Charsets.UTF_8))
                if (request.method != "HEAD") out.write(response.body)
                out.flush()
            } catch (_: IOException) {
            }
        }
    }

    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    class Response(
        val code: Int,
        val reason: String,
        val type: String,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap(),
    )

    private fun readRequest(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return Request(parts[0].uppercase(), parts[1], headers, String(body, 0, read, Charsets.UTF_8))
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) return out.toString("UTF-8").trimEnd('\r')
            if (out.size() > 8192) throw IOException("Header line too long")
            out.write(b)
        }
    }

    internal fun handle(request: Request, local: InetAddress?, remote: InetAddress? = null): Response {
        val path = request.path.substringBefore('?')
        return when {
            request.method == "GET" || request.method == "HEAD" -> when (path) {
                "/description.xml" -> xml(descriptionXml())
                "/AVTransport.xml" -> xml(Scpd.AV_TRANSPORT)
                "/RenderingControl.xml" -> xml(Scpd.RENDERING_CONTROL)
                "/ConnectionManager.xml" -> xml(Scpd.CONNECTION_MANAGER)
                else -> Response(404, "Not Found", "text/plain", ByteArray(0))
            }
            request.method == "POST" && path.startsWith("/control/") -> soap(path.removePrefix("/control/"), request)
            request.method == "POST" && path.startsWith("/castapp/") -> castApp(path.removePrefix("/castapp/"), request, remote)
            request.method == "SUBSCRIBE" -> Response(
                200, "OK", "text/plain", ByteArray(0),
                mapOf("SID" to (request.headers["sid"] ?: "uuid:" + java.util.UUID.randomUUID()), "TIMEOUT" to "Second-1800"),
            )
            request.method == "UNSUBSCRIBE" -> Response(200, "OK", "text/plain", ByteArray(0))
            else -> Response(405, "Method Not Allowed", "text/plain", ByteArray(0))
        }
    }

    /** POST /castapp/mirror {"description": "http://<requester>/description.xml"} and POST /castapp/stop-mirror. */
    private fun castApp(command: String, request: Request, remote: InetAddress?): Response {
        val c = commands ?: return plain(404, "Not Found", "Not supported here")
        if (!isLocal(remote)) return plain(403, "Forbidden", "Only devices on the local network may ask that")
        return when (command) {
            "mirror" -> {
                val description = try {
                    org.json.JSONObject(request.body).optString("description")
                } catch (_: Exception) {
                    ""
                }
                if (!mirrorAllowed(remote, description)) return plain(403, "Forbidden", "A device can only ask to receive the screen itself")
                val error = c.mirrorTo(description)
                if (error == null) plain(200, "OK", "OK") else plain(409, "Conflict", error)
            }
            "stop-mirror" -> {
                c.stopMirror()
                plain(200, "OK", "OK")
            }
            else -> plain(404, "Not Found", "Unknown command")
        }
    }

    private fun plain(code: Int, reason: String, text: String) =
        Response(code, reason, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))

    private fun xml(text: String) = Response(200, "OK", "text/xml; charset=\"utf-8\"", text.toByteArray(Charsets.UTF_8))

    fun descriptionXml(): String = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion>
<device>
<deviceType>${Dlna.MEDIA_RENDERER}</deviceType>
<friendlyName>${Dlna.escape(friendlyName)}</friendlyName>
<manufacturer>CastApp</manufacturer>
<manufacturerURL>https://github.com/patschgoo/CastApp</manufacturerURL>
<modelDescription>CastApp TV receiver</modelDescription>
<modelName>$MODEL_NAME</modelName>
<modelNumber>${Dlna.escape(modelNumber)}</modelNumber>
<UDN>uuid:$uuid</UDN>
<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
<serviceList>
<service><serviceType>${Dlna.RENDERING_CONTROL}</serviceType><serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId><SCPDURL>/RenderingControl.xml</SCPDURL><controlURL>/control/RenderingControl</controlURL><eventSubURL>/event/RenderingControl</eventSubURL></service>
<service><serviceType>$CONNECTION_MANAGER</serviceType><serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId><SCPDURL>/ConnectionManager.xml</SCPDURL><controlURL>/control/ConnectionManager</controlURL><eventSubURL>/event/ConnectionManager</eventSubURL></service>
<service><serviceType>${Dlna.AV_TRANSPORT}</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><SCPDURL>/AVTransport.xml</SCPDURL><controlURL>/control/AVTransport</controlURL><eventSubURL>/event/AVTransport</eventSubURL></service>
</serviceList>
</device>
</root>
"""

    // ---- SOAP --------------------------------------------------------------------------------

    private fun soap(serviceName: String, request: Request): Response {
        val soapAction = request.headers["soapaction"]?.trim('"', ' ') ?: ""
        val service = soapAction.substringBefore('#').ifEmpty { serviceTypeOf(serviceName) }
        val action = soapAction.substringAfter('#', "").ifEmpty {
            Regex("<(?:[A-Za-z0-9_]+:)?Body[^>]*>\\s*<(?:[A-Za-z0-9_]+:)?([A-Za-z]+)").find(request.body)?.groupValues?.get(1) ?: ""
        }
        val body = request.body
        fun arg(name: String) = Dlna.tag(body, name)?.let(Dlna::unescape)
        return try {
            val out: List<Pair<String, String>> = when (serviceName) {
                "AVTransport" -> avTransport(action, ::arg)
                "RenderingControl" -> renderingControl(action, ::arg)
                "ConnectionManager" -> connectionManager(action)
                else -> throw SoapFault(401, "Invalid Action")
            }
            xml(Dlna.soapEnvelope(service, action + "Response", out))
        } catch (e: SoapFault) {
            val fault = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>${e.code}</errorCode><errorDescription>${Dlna.escape(e.message ?: "")}</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
            Response(500, "Internal Server Error", "text/xml; charset=\"utf-8\"", fault.toByteArray(Charsets.UTF_8))
        }
    }

    private class SoapFault(val code: Int, message: String) : Exception(message)

    private fun serviceTypeOf(name: String) = when (name) {
        "AVTransport" -> Dlna.AV_TRANSPORT
        "RenderingControl" -> Dlna.RENDERING_CONTROL
        else -> CONNECTION_MANAGER
    }

    private fun avTransport(action: String, arg: (String) -> String?): List<Pair<String, String>> {
        val snap = backend.snapshot
        val current = media
        return when (action) {
            "SetAVTransportURI" -> {
                val uri = arg("CurrentURI")?.trim().orEmpty()
                if (uri.isEmpty()) throw SoapFault(716, "Resource not found")
                val parsed = parseMedia(uri, arg("CurrentURIMetaData").orEmpty())
                media = parsed
                backend.setMedia(parsed)
                emptyList()
            }
            "SetNextAVTransportURI" -> emptyList()
            "Play" -> {
                if (current == null) throw SoapFault(701, "Transition not available")
                backend.play()
                emptyList()
            }
            "Pause" -> { backend.pause(); emptyList() }
            "Stop" -> { backend.stop(); emptyList() }
            "Seek" -> {
                val unit = arg("Unit").orEmpty()
                val target = arg("Target").orEmpty()
                if (unit == "REL_TIME" || unit == "ABS_TIME") {
                    val ms = Dlna.parseTime(target) ?: throw SoapFault(711, "Illegal seek target")
                    backend.seek(ms)
                }
                emptyList()
            }
            "Next", "Previous" -> emptyList()
            "GetTransportInfo" -> listOf(
                "CurrentTransportState" to snap.state.upnp,
                "CurrentTransportStatus" to "OK",
                "CurrentSpeed" to "1",
            )
            "GetPositionInfo" -> listOf(
                "Track" to if (current != null) "1" else "0",
                "TrackDuration" to formatTime(snap.durationMs),
                "TrackMetaData" to (current?.metadata ?: ""),
                "TrackURI" to (current?.uri ?: ""),
                "RelTime" to formatTime(snap.positionMs),
                "AbsTime" to formatTime(snap.positionMs),
                "RelCount" to "2147483647",
                "AbsCount" to "2147483647",
            )
            "GetMediaInfo" -> listOf(
                "NrTracks" to if (current != null) "1" else "0",
                "MediaDuration" to formatTime(snap.durationMs),
                "CurrentURI" to (current?.uri ?: ""),
                "CurrentURIMetaData" to (current?.metadata ?: ""),
                "NextURI" to "",
                "NextURIMetaData" to "",
                "PlayMedium" to "NETWORK",
                "RecordMedium" to "NOT_IMPLEMENTED",
                "WriteStatus" to "NOT_IMPLEMENTED",
            )
            "GetTransportSettings" -> listOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "GetDeviceCapabilities" -> listOf(
                "PlayMedia" to "NETWORK",
                "RecMedia" to "NOT_IMPLEMENTED",
                "RecQualityModes" to "NOT_IMPLEMENTED",
            )
            "GetCurrentTransportActions" -> listOf("Actions" to "Play,Pause,Stop,Seek")
            else -> throw SoapFault(401, "Invalid Action")
        }
    }

    private fun renderingControl(action: String, arg: (String) -> String?): List<Pair<String, String>> {
        val snap = backend.snapshot
        return when (action) {
            "GetVolume" -> listOf("CurrentVolume" to snap.volume.toString())
            "SetVolume" -> {
                val v = arg("DesiredVolume")?.toIntOrNull() ?: throw SoapFault(402, "Invalid Args")
                backend.setVolume(v.coerceIn(0, 100))
                emptyList()
            }
            "GetMute" -> listOf("CurrentMute" to if (snap.muted) "1" else "0")
            "SetMute" -> {
                val m = arg("DesiredMute").orEmpty()
                backend.setMuted(m == "1" || m.equals("true", ignoreCase = true))
                emptyList()
            }
            "ListPresets" -> listOf("CurrentPresetNameList" to "FactoryDefaults")
            "SelectPreset" -> emptyList()
            else -> throw SoapFault(401, "Invalid Action")
        }
    }

    private fun connectionManager(action: String): List<Pair<String, String>> = when (action) {
        "GetProtocolInfo" -> listOf("Source" to "", "Sink" to SINK_PROTOCOLS)
        "GetCurrentConnectionIDs" -> listOf("ConnectionIDs" to "0")
        "GetCurrentConnectionInfo" -> listOf(
            "RcsID" to "0",
            "AVTransportID" to "0",
            "ProtocolInfo" to "",
            "PeerConnectionManager" to "",
            "PeerConnectionID" to "-1",
            "Direction" to "Input",
            "Status" to "OK",
        )
        else -> throw SoapFault(401, "Invalid Action")
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }

    // ---- SSDP --------------------------------------------------------------------------------

    private fun startSsdp() {
        val socket = try {
            MulticastSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(Dlna.SSDP_PORT))
                soTimeout = 1000
                timeToLive = 4
            }
        } catch (_: IOException) {
            return
        }
        ssdpSocket = socket
        joinGroups()
        Thread({ ssdpLoop(socket) }, "upnp-ssdp").apply { isDaemon = true; start() }
        Thread({ notifyLoop() }, "upnp-notify").apply { isDaemon = true; start() }
    }

    private fun joinGroups() {
        val socket = ssdpSocket ?: return
        val group = InetSocketAddress(InetAddress.getByName(Dlna.SSDP_ADDRESS), Dlna.SSDP_PORT)
        val list = synchronized(ifacesLock) { ifaces }
        var joined = false
        for (iface in list) {
            try {
                socket.joinGroup(group, iface)
                joined = true
            } catch (_: Exception) {
                // Already a member, or the interface went away.
            }
        }
        if (!joined && list.isEmpty()) try { socket.joinGroup(group, null) } catch (_: Exception) {}
    }

    private fun ssdpLoop(socket: MulticastSocket) {
        val buf = ByteArray(4096)
        while (running && !socket.isClosed) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val text = String(buf, 0, packet.length, Charsets.UTF_8)
                if (!text.startsWith("M-SEARCH", ignoreCase = true)) continue
                val headers = Dlna.parseSsdp(text)
                val st = headers["st"] ?: continue
                val targets = searchTargets(st)
                if (targets.isEmpty()) continue
                val sender = InetSocketAddress(packet.address, packet.port)
                val mx = headers["mx"]?.toIntOrNull()?.coerceIn(0, 2) ?: 1
                workers.execute {
                    try { Thread.sleep((Math.random() * mx * 400).toLong()) } catch (_: InterruptedException) {}
                    respondToSearch(sender, targets)
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
                if (!running) return
            }
        }
    }

    /** Which of our identities answer a search for [st] (as pairs of ST and USN). */
    internal fun searchTargets(st: String): List<Pair<String, String>> {
        val all = listOf(
            "upnp:rootdevice" to "uuid:$uuid::upnp:rootdevice",
            "uuid:$uuid" to "uuid:$uuid",
            Dlna.MEDIA_RENDERER to "uuid:$uuid::${Dlna.MEDIA_RENDERER}",
            Dlna.AV_TRANSPORT to "uuid:$uuid::${Dlna.AV_TRANSPORT}",
            Dlna.RENDERING_CONTROL to "uuid:$uuid::${Dlna.RENDERING_CONTROL}",
            CONNECTION_MANAGER to "uuid:$uuid::$CONNECTION_MANAGER",
        )
        return if (st == "ssdp:all") all else all.filter { it.first.equals(st, ignoreCase = true) }
    }

    private fun respondToSearch(to: InetSocketAddress, targets: List<Pair<String, String>>) {
        val local = localAddressFor(to.address) ?: return
        DatagramSocket().use { s ->
            for ((st, usn) in targets) {
                val msg = "HTTP/1.1 200 OK\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "EXT:\r\n" +
                    "LOCATION: http://${local.hostAddress}:$port/description.xml\r\n" +
                    "SERVER: $SERVER\r\n" +
                    "ST: $st\r\n" +
                    "USN: $usn\r\n" +
                    "Content-Length: 0\r\n\r\n"
                val data = msg.toByteArray(Charsets.UTF_8)
                try { s.send(DatagramPacket(data, data.size, to)) } catch (_: IOException) {}
            }
        }
    }

    private fun notifyLoop() {
        var round = 0
        while (running) {
            try { sendNotify("ssdp:alive") } catch (_: Exception) {}
            // A few quick announcements at the start, then every 30 s; re-join the group now and then.
            val wait = if (round < 3) 2000L else 30_000L
            if (++round % 4 == 0) joinGroups()
            try { Thread.sleep(wait) } catch (_: InterruptedException) { return }
        }
    }

    private fun sendNotify(nts: String) {
        val group = InetAddress.getByName(Dlna.SSDP_ADDRESS)
        val list = synchronized(ifacesLock) { ifaces }
        val addresses = list.flatMap { iface -> iface.inetAddresses.toList().filterIsInstance<Inet4Address>().map { iface to it } }
        for ((iface, address) in addresses) {
            MulticastSocket(null as java.net.SocketAddress?).use { s ->
                try {
                    s.bind(InetSocketAddress(address, 0))
                    s.networkInterface = iface
                    s.timeToLive = 4
                } catch (_: Exception) {
                    return@use
                }
                for ((nt, usn) in searchTargets("ssdp:all")) {
                    val msg = "NOTIFY * HTTP/1.1\r\n" +
                        "HOST: ${Dlna.SSDP_ADDRESS}:${Dlna.SSDP_PORT}\r\n" +
                        "CACHE-CONTROL: max-age=1800\r\n" +
                        "LOCATION: http://${address.hostAddress}:$port/description.xml\r\n" +
                        "NT: $nt\r\n" +
                        "NTS: $nts\r\n" +
                        "SERVER: $SERVER\r\n" +
                        "USN: $usn\r\n\r\n"
                    val data = msg.toByteArray(Charsets.UTF_8)
                    try { s.send(DatagramPacket(data, data.size, group, Dlna.SSDP_PORT)) } catch (_: IOException) {}
                }
            }
        }
    }

    /** Our address on the network that [remote] is on. */
    private fun localAddressFor(remote: InetAddress): InetAddress? = try {
        DatagramSocket().use { s ->
            s.connect(remote, Dlna.SSDP_PORT)
            s.localAddress.takeIf { !it.isAnyLocalAddress }
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val MODEL_NAME = "CastApp TV"

        /** Requests from this home network only (private, link-local or loopback addresses). */
        fun isLocal(remote: InetAddress?): Boolean =
            remote != null && (remote.isSiteLocalAddress || remote.isLinkLocalAddress || remote.isLoopbackAddress)

        /** A device may only ask to receive the screen itself: the receiver must be at the requester's address. */
        fun mirrorAllowed(remote: InetAddress?, descriptionUrl: String): Boolean {
            if (remote == null || !isLocal(remote)) return false
            val uri = try { java.net.URI(descriptionUrl) } catch (_: Exception) { return false }
            if (uri.scheme != "http" || uri.port !in 1..65535) return false
            val host = uri.host?.trim('[', ']') ?: return false
            return host == remote.hostAddress || (remote.isLoopbackAddress && (host == "localhost" || host.startsWith("127.")))
        }
        const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
        private const val SERVER = "Android/1 UPnP/1.0 CastApp/1.0"
        private const val MAX_BODY = 512 * 1024
        /** Marker CastApp puts into the metadata of its screen-mirroring stream. */
        const val LOW_LATENCY_MARKER = "castapp:lowlatency"

        private val SINK_PROTOCOLS = listOf(
            "video/mp4", "video/x-matroska", "video/webm", "video/mp2t", "video/MP2T", "video/quicktime", "video/x-msvideo",
            "video/mpeg", "application/x-mpegurl", "application/vnd.apple.mpegurl", "application/dash+xml",
            "audio/mpeg", "audio/mp4", "audio/aac", "audio/x-aac", "audio/flac", "audio/x-flac", "audio/ogg", "audio/wav",
            "audio/x-wav", "audio/L16", "image/jpeg", "image/png", "image/webp", "image/gif",
        ).joinToString(",") { "http-get:*:$it:*" }

        /** Reads what to play from the URI and its DIDL-Lite metadata. */
        fun parseMedia(uri: String, metadata: String): TvMedia {
            val title = Dlna.tag(metadata, "title")?.let(Dlna::unescape)?.takeIf { it.isNotBlank() }
                ?: uri.substringBefore('?').substringAfterLast('/').ifEmpty { "Video" }
            val upnpClass = Dlna.tag(metadata, "class").orEmpty()
            val protocolInfo = Regex("protocolInfo=\"([^\"]*)\"").find(metadata)?.groupValues?.get(1).orEmpty()
            val mime = protocolInfo.split(':').getOrNull(2)?.takeIf { it.contains('/') }
            val kind = when {
                upnpClass.contains("imageItem") || mime?.startsWith("image/") == true -> TvMediaKind.PHOTO
                upnpClass.contains("audioItem") || mime?.startsWith("audio/") == true -> TvMediaKind.AUDIO
                else -> TvMediaKind.VIDEO
            }
            val subtitle = Dlna.tag(metadata, "CaptionInfoEx") ?: Dlna.tag(metadata, "CaptionInfo")
                ?: Regex("<res[^>]*protocolInfo=\"http-get:\\*:(?:text/srt|text/vtt|application/x-subrip|smi/caption)[^\"]*\"[^>]*>([^<]*)</res>")
                    .find(metadata)?.groupValues?.get(1)
            return TvMedia(
                uri = uri,
                metadata = metadata,
                title = title,
                mimeType = mime,
                kind = kind,
                subtitleUri = subtitle?.let(Dlna::unescape)?.trim()?.takeIf { it.startsWith("http") },
                live = protocolInfo.contains("DLNA.ORG_OP=00"),
                lowLatency = metadata.contains(LOW_LATENCY_MARKER),
            )
        }
    }
}

/** Service descriptions (SCPD) some DLNA apps read before they cast. */
private object Scpd {
    private class Arg(val name: String, val dir: String, val variable: String)
    private class Action(val name: String, val args: List<Arg>)
    private class Variable(val name: String, val type: String, val events: Boolean = false, val allowed: List<String> = emptyList())

    private fun inArg(name: String, v: String) = Arg(name, "in", v)
    private fun outArg(name: String, v: String) = Arg(name, "out", v)
    private val instance = inArg("InstanceID", "A_ARG_TYPE_InstanceID")

    private fun build(actions: List<Action>, variables: List<Variable>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">")
        append("<specVersion><major>1</major><minor>0</minor></specVersion><actionList>")
        for (a in actions) {
            append("<action><name>").append(a.name).append("</name><argumentList>")
            for (arg in a.args) {
                append("<argument><name>").append(arg.name).append("</name><direction>").append(arg.dir)
                    .append("</direction><relatedStateVariable>").append(arg.variable).append("</relatedStateVariable></argument>")
            }
            append("</argumentList></action>")
        }
        append("</actionList><serviceStateTable>")
        for (v in variables) {
            append("<stateVariable sendEvents=\"").append(if (v.events) "yes" else "no").append("\"><name>").append(v.name)
                .append("</name><dataType>").append(v.type).append("</dataType>")
            if (v.allowed.isNotEmpty()) {
                append("<allowedValueList>")
                v.allowed.forEach { append("<allowedValue>").append(it).append("</allowedValue>") }
                append("</allowedValueList>")
            }
            append("</stateVariable>")
        }
        append("</serviceStateTable></scpd>")
    }

    val AV_TRANSPORT = build(
        listOf(
            Action("SetAVTransportURI", listOf(instance, inArg("CurrentURI", "AVTransportURI"), inArg("CurrentURIMetaData", "AVTransportURIMetaData"))),
            Action("SetNextAVTransportURI", listOf(instance, inArg("NextURI", "NextAVTransportURI"), inArg("NextURIMetaData", "NextAVTransportURIMetaData"))),
            Action("GetMediaInfo", listOf(
                instance, outArg("NrTracks", "NumberOfTracks"), outArg("MediaDuration", "CurrentMediaDuration"),
                outArg("CurrentURI", "AVTransportURI"), outArg("CurrentURIMetaData", "AVTransportURIMetaData"),
                outArg("NextURI", "NextAVTransportURI"), outArg("NextURIMetaData", "NextAVTransportURIMetaData"),
                outArg("PlayMedium", "PlaybackStorageMedium"), outArg("RecordMedium", "RecordStorageMedium"),
                outArg("WriteStatus", "RecordMediumWriteStatus"),
            )),
            Action("GetTransportInfo", listOf(
                instance, outArg("CurrentTransportState", "TransportState"),
                outArg("CurrentTransportStatus", "TransportStatus"), outArg("CurrentSpeed", "TransportPlaySpeed"),
            )),
            Action("GetPositionInfo", listOf(
                instance, outArg("Track", "CurrentTrack"), outArg("TrackDuration", "CurrentTrackDuration"),
                outArg("TrackMetaData", "CurrentTrackMetaData"), outArg("TrackURI", "CurrentTrackURI"),
                outArg("RelTime", "RelativeTimePosition"), outArg("AbsTime", "AbsoluteTimePosition"),
                outArg("RelCount", "RelativeCounterPosition"), outArg("AbsCount", "AbsoluteCounterPosition"),
            )),
            Action("GetDeviceCapabilities", listOf(
                instance, outArg("PlayMedia", "PossiblePlaybackStorageMedia"),
                outArg("RecMedia", "PossibleRecordStorageMedia"), outArg("RecQualityModes", "PossibleRecordQualityModes"),
            )),
            Action("GetTransportSettings", listOf(instance, outArg("PlayMode", "CurrentPlayMode"), outArg("RecQualityMode", "CurrentRecordQualityMode"))),
            Action("Stop", listOf(instance)),
            Action("Play", listOf(instance, inArg("Speed", "TransportPlaySpeed"))),
            Action("Pause", listOf(instance)),
            Action("Seek", listOf(instance, inArg("Unit", "A_ARG_TYPE_SeekMode"), inArg("Target", "A_ARG_TYPE_SeekTarget"))),
            Action("Next", listOf(instance)),
            Action("Previous", listOf(instance)),
            Action("GetCurrentTransportActions", listOf(instance, outArg("Actions", "CurrentTransportActions"))),
        ),
        listOf(
            Variable("TransportState", "string", allowed = TransportState.entries.map { it.upnp }),
            Variable("TransportStatus", "string", allowed = listOf("OK", "ERROR_OCCURRED")),
            Variable("PlaybackStorageMedium", "string"),
            Variable("RecordStorageMedium", "string"),
            Variable("PossiblePlaybackStorageMedia", "string"),
            Variable("PossibleRecordStorageMedia", "string"),
            Variable("CurrentPlayMode", "string", allowed = listOf("NORMAL")),
            Variable("TransportPlaySpeed", "string", allowed = listOf("1")),
            Variable("RecordMediumWriteStatus", "string"),
            Variable("CurrentRecordQualityMode", "string"),
            Variable("PossibleRecordQualityModes", "string"),
            Variable("NumberOfTracks", "ui4"),
            Variable("CurrentTrack", "ui4"),
            Variable("CurrentTrackDuration", "string"),
            Variable("CurrentMediaDuration", "string"),
            Variable("CurrentTrackMetaData", "string"),
            Variable("CurrentTrackURI", "string"),
            Variable("AVTransportURI", "string"),
            Variable("AVTransportURIMetaData", "string"),
            Variable("NextAVTransportURI", "string"),
            Variable("NextAVTransportURIMetaData", "string"),
            Variable("RelativeTimePosition", "string"),
            Variable("AbsoluteTimePosition", "string"),
            Variable("RelativeCounterPosition", "i4"),
            Variable("AbsoluteCounterPosition", "i4"),
            Variable("CurrentTransportActions", "string"),
            Variable("LastChange", "string", events = true),
            Variable("A_ARG_TYPE_SeekMode", "string", allowed = listOf("REL_TIME", "ABS_TIME", "TRACK_NR")),
            Variable("A_ARG_TYPE_SeekTarget", "string"),
            Variable("A_ARG_TYPE_InstanceID", "ui4"),
        ),
    )

    val RENDERING_CONTROL = build(
        listOf(
            Action("ListPresets", listOf(instance, outArg("CurrentPresetNameList", "PresetNameList"))),
            Action("SelectPreset", listOf(instance, inArg("PresetName", "A_ARG_TYPE_PresetName"))),
            Action("GetMute", listOf(instance, inArg("Channel", "A_ARG_TYPE_Channel"), outArg("CurrentMute", "Mute"))),
            Action("SetMute", listOf(instance, inArg("Channel", "A_ARG_TYPE_Channel"), inArg("DesiredMute", "Mute"))),
            Action("GetVolume", listOf(instance, inArg("Channel", "A_ARG_TYPE_Channel"), outArg("CurrentVolume", "Volume"))),
            Action("SetVolume", listOf(instance, inArg("Channel", "A_ARG_TYPE_Channel"), inArg("DesiredVolume", "Volume"))),
        ),
        listOf(
            Variable("PresetNameList", "string"),
            Variable("Mute", "boolean"),
            Variable("Volume", "ui2"),
            Variable("LastChange", "string", events = true),
            Variable("A_ARG_TYPE_Channel", "string", allowed = listOf("Master")),
            Variable("A_ARG_TYPE_InstanceID", "ui4"),
            Variable("A_ARG_TYPE_PresetName", "string", allowed = listOf("FactoryDefaults")),
        ),
    )

    val CONNECTION_MANAGER = build(
        listOf(
            Action("GetProtocolInfo", listOf(outArg("Source", "SourceProtocolInfo"), outArg("Sink", "SinkProtocolInfo"))),
            Action("GetCurrentConnectionIDs", listOf(outArg("ConnectionIDs", "CurrentConnectionIDs"))),
            Action("GetCurrentConnectionInfo", listOf(
                inArg("ConnectionID", "A_ARG_TYPE_ConnectionID"), outArg("RcsID", "A_ARG_TYPE_RcsID"),
                outArg("AVTransportID", "A_ARG_TYPE_AVTransportID"), outArg("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo"),
                outArg("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"), outArg("PeerConnectionID", "A_ARG_TYPE_ConnectionID"),
                outArg("Direction", "A_ARG_TYPE_Direction"), outArg("Status", "A_ARG_TYPE_ConnectionStatus"),
            )),
        ),
        listOf(
            Variable("SourceProtocolInfo", "string", events = true),
            Variable("SinkProtocolInfo", "string", events = true),
            Variable("CurrentConnectionIDs", "string", events = true),
            Variable("A_ARG_TYPE_ConnectionStatus", "string", allowed = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")),
            Variable("A_ARG_TYPE_ConnectionManager", "string"),
            Variable("A_ARG_TYPE_Direction", "string", allowed = listOf("Input", "Output")),
            Variable("A_ARG_TYPE_ProtocolInfo", "string"),
            Variable("A_ARG_TYPE_ConnectionID", "i4"),
            Variable("A_ARG_TYPE_AVTransportID", "i4"),
            Variable("A_ARG_TYPE_RcsID", "i4"),
        ),
    )
}
