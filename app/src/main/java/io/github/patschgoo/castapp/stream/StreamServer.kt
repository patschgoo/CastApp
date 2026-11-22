package io.github.patschgoo.castapp.stream

import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.util.Collections

/**
 * Serves the live screen stream to the Cast receiver.
 *
 *  - /<token>/stream.mp4       progressive fragmented MP4 (lowest latency)
 *  - /<token>/hls/live.m3u8    HLS playlist (compatibility mode)
 *  - /<token>/hls/init.mp4, /<token>/hls/segN.m4s
 */
class StreamServer(private val hub: StreamHub, preferredPort: Int) : TokenHttpServer(preferredPort, "stream-http") {

    @Volatile private var hls: HlsSegmenter? = null
    private val liveClients = Collections.synchronizedSet(HashSet<LiveClientQueue>())

    val mp4Path: String get() = "/$token/stream.mp4"
    val hlsPath: String get() = "/$token/hls/live.m3u8"

    val activeStreamClients: Int get() = liveClients.size

    /** Starts the HLS segmenter (idempotent) and returns it. */
    fun enableHls(): HlsSegmenter {
        hls?.let { return it }
        val segmenter = HlsSegmenter(hub)
        hls = segmenter
        hub.subscribe(segmenter)
        return segmenter
    }

    override fun stop() {
        hls?.let { hub.unsubscribe(it) }
        hls = null
        synchronized(liveClients) { liveClients.forEach { it.close() } }
        super.stop()
    }

    override fun handle(request: Request, subPath: String, socket: Socket, out: OutputStream) {
        val head = request.isHead
        when {
            subPath == "stream.mp4" -> serveLiveMp4(socket, out, head)
            subPath == "hls/live.m3u8" -> {
                val segmenter = hls ?: return notFound(out)
                serveBytes(out, segmenter.playlist().toByteArray(), "application/vnd.apple.mpegurl", head)
            }
            subPath == "hls/init.mp4" -> {
                val init = hub.currentInitSegment() ?: return notFound(out)
                serveBytes(out, init, "video/mp4", head)
            }
            subPath.startsWith("hls/seg") && subPath.endsWith(".m4s") -> {
                val index = subPath.removePrefix("hls/seg").removeSuffix(".m4s").toLongOrNull()
                val data = index?.let { hls?.segment(it) } ?: return notFound(out)
                serveBytes(out, data, "video/mp4", head)
            }
            else -> notFound(out)
        }
    }

    private fun serveLiveMp4(socket: Socket, out: OutputStream, head: Boolean) {
        val init = hub.awaitInitSegment(10_000)
        if (init == null) {
            writeHead(out, 503, "Service Unavailable", "text/plain", 0)
            return
        }
        writeHead(out, 200, "OK", "video/mp4", -1)
        if (head) {
            out.flush()
            return
        }
        out.write(init)
        out.flush()

        val queue = LiveClientQueue(hub)
        val serializer = Mp4FragmentSerializer(hub.publishedAudioRate)
        liveClients.add(queue)
        hub.subscribe(queue)
        try {
            socket.soTimeout = 0
            while (running && !queue.isClosed) {
                val item = queue.take(1000) ?: continue
                val bytes = serializer.serialize(item.sample, item.discontinuity) ?: continue
                out.write(bytes)
                out.flush()
            }
        } catch (_: SocketException) {
        } finally {
            hub.unsubscribe(queue)
            queue.close()
            liveClients.remove(queue)
        }
    }
}
