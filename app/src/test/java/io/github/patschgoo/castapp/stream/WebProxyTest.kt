package io.github.patschgoo.castapp.stream

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

/** The proxy must forward the site's headers, rewrite playlists and add CORS for the TV. */
class WebProxyTest {
    private val upstream = ServerSocket(0)
    private val server = MediaServer(0).also { it.start() }

    init {
        Thread {
            while (!upstream.isClosed) {
                val s = try { upstream.accept() } catch (_: Exception) { break }
                Thread {
                    s.use { sock ->
                        val head = StringBuilder()
                        val input = sock.getInputStream()
                        while (!head.endsWith("\r\n\r\n")) {
                            val b = input.read()
                            if (b < 0) break
                            head.append(b.toChar())
                        }
                        val path = head.lines().first().split(' ')[1]
                        val referer = head.lines().firstOrNull { it.startsWith("Referer:", true) }?.substringAfter(':')?.trim()
                        val out = sock.getOutputStream()
                        fun reply(code: Int, type: String, body: ByteArray) {
                            out.write("HTTP/1.1 $code X\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body)
                            out.flush()
                        }
                        when {
                            referer != "https://site.example/page" -> reply(403, "text/plain", "no".toByteArray())
                            path == "/live/master.m3u8" -> reply(200, "application/vnd.apple.mpegurl", "#EXTM3U\n#EXTINF:2.0,\nseg1.ts\n".toByteArray())
                            path == "/live/seg1.ts" -> reply(200, "video/mp2t", "SEGMENT-DATA".toByteArray())
                            else -> reply(404, "text/plain", ByteArray(0))
                        }
                    }
                }.start()
            }
        }.apply { isDaemon = true; start() }
    }

    @After
    fun tearDown() {
        upstream.close()
        server.stop()
    }

    private fun get(path: String): Pair<String, String> {
        Socket("127.0.0.1", server.port).use { s ->
            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: tv\r\n\r\n".toByteArray())
            val text = String(s.getInputStream().readBytes(), Charsets.ISO_8859_1)
            return text.substringBefore("\r\n\r\n") to text.substringAfter("\r\n\r\n")
        }
    }

    @Test
    fun proxiesHlsWithHeaders() {
        val proxy = WebProxy(server, mapOf("Referer" to "https://site.example/page"))
        val playlistPath = proxy.pathFor("http://127.0.0.1:${upstream.localPort}/live/master.m3u8")
        assertTrue(playlistPath.endsWith("/master.m3u8"))

        val (head, body) = get(playlistPath)
        assertTrue(head, head.startsWith("HTTP/1.1 200"))
        assertTrue(head.contains("Access-Control-Allow-Origin: *"))
        val segmentLine = body.lines().first { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(segmentLine, segmentLine.startsWith("/${server.token}/p/") && segmentLine.endsWith("/seg1.ts"))

        val (segHead, segBody) = get(segmentLine)
        assertTrue(segHead.startsWith("HTTP/1.1 200"))
        assertTrue(segHead.contains("Content-Type: video/mp2t"))
        assertEquals("SEGMENT-DATA", segBody)
    }

    @Test
    fun withoutSiteHeadersTheSiteRefuses() {
        val proxy = WebProxy(server, emptyMap())
        val (head, _) = get(proxy.pathFor("http://127.0.0.1:${upstream.localPort}/live/master.m3u8"))
        assertTrue(head, head.startsWith("HTTP/1.1 403"))
        // Garbage in the proxy path is rejected, not fetched.
        assertTrue(get("/${server.token}/p/%%%/x").first.startsWith("HTTP/1.1 404"))
    }
}
