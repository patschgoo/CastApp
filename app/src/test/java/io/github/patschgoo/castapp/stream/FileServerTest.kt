package io.github.patschgoo.castapp.stream

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URI

class FileServerTest {
    private val data = ByteArray(100_000) { (it * 31 + 7).toByte() }
    private val source = object : MediaSource {
        override val length: Long = data.size.toLong()
        override fun open(offset: Long): InputStream = ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
    }
    private val server = FileServer(source, "video/mp4", "My Holiday (2026).mp4", 0).also { it.start() }

    @After
    fun tearDown() = server.stop()

    private class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun get(path: String, range: String? = null, method: String = "GET"): Response {
        Socket("127.0.0.1", server.port).use { s ->
            val req = buildString {
                append("$method $path HTTP/1.1\r\nHost: tv\r\n")
                if (range != null) append("Range: $range\r\n")
                append("\r\n")
            }
            s.getOutputStream().write(req.toByteArray())
            val raw = s.getInputStream().readBytes()
            val split = (0 until raw.size - 3).first { raw[it] == '\r'.code.toByte() && raw[it + 1] == '\n'.code.toByte() && raw[it + 2] == '\r'.code.toByte() && raw[it + 3] == '\n'.code.toByte() }
            val head = String(raw, 0, split, Charsets.ISO_8859_1).lines()
            val headers = head.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Response(head[0].split(' ')[1].toInt(), headers, raw.copyOfRange(split + 4, raw.size))
        }
    }

    private val path: String get() = URI(server.urlFor(InetAddress.getLoopbackAddress(), server.mediaPath)).path

    @Test
    fun servesWholeFile() {
        assertTrue(path.endsWith("/media/My_Holiday__2026_.mp4")) // safe file name, extension kept
        val r = get(path)
        assertEquals(200, r.status)
        assertEquals("bytes", r.headers["accept-ranges"])
        assertEquals("100000", r.headers["content-length"])
        assertArrayEquals(data, r.body)
    }

    @Test
    fun servesRanges() {
        val r = get(path, "bytes=1000-1999")
        assertEquals(206, r.status)
        assertEquals("bytes 1000-1999/100000", r.headers["content-range"])
        assertArrayEquals(data.copyOfRange(1000, 2000), r.body)

        val open = get(path, "bytes=99000-")
        assertEquals(206, open.status)
        assertArrayEquals(data.copyOfRange(99_000, 100_000), open.body)

        val suffix = get(path, "bytes=-500") // the last 500 bytes (where MP4 indexes often live)
        assertEquals("bytes 99500-99999/100000", suffix.headers["content-range"])
        assertArrayEquals(data.copyOfRange(99_500, 100_000), suffix.body)

        assertEquals(416, get(path, "bytes=200000-").status)
    }

    @Test
    fun headAndWrongTokenSendNoBody() {
        val head = get(path, method = "HEAD")
        assertEquals(200, head.status)
        assertEquals(0, head.body.size)
        assertEquals(404, get("/nottoken/media/My_Holiday__2026_.mp4").status)
    }

    @Test
    fun parseRangeEdgeCases() {
        assertNull(FileServer.parseRange("items=0-1", 10))
        assertEquals(0L to 9L, FileServer.parseRange("bytes=0-", 10))
        assertEquals(5L to 9L, FileServer.parseRange("bytes=5-500", 10)) // end clamped to the file
        assertEquals(FileServer.INVALID_RANGE, FileServer.parseRange("bytes=10-", 10))
        assertEquals(FileServer.INVALID_RANGE, FileServer.parseRange("bytes=7-3", 10))
        assertEquals(0L to 9L, FileServer.parseRange("bytes=-50", 10))
    }
}
