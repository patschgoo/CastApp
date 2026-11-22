package io.github.patschgoo.castapp.iptv

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Recording streams from a real (local) HTTP server: HLS in its forms, progressive streams, drops and retries. */
class StreamRecorderTest {
    private lateinit var server: HttpServer
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val base get() = "http://127.0.0.1:${server.address.port}"

    /** Quick retries and polls so tests do not wait. */
    private val fast = RetryPolicy(maxFailures = 3, baseDelayMs = 10, maxDelayMs = 20, segmentAttempts = 3, pollMinMs = 20, pollMaxMs = 40, liveStartSegments = 2)

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    private fun count(path: String): Int = hits[path]?.get() ?: 0

    /** [handler] gets the number of this request to [path] (1, 2, 3…). */
    private fun route(path: String, handler: (HttpExchange, Int) -> Unit) {
        server.createContext(path) { ex ->
            val n = hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
            try {
                handler(ex, n)
            } catch (_: IOException) {
            } finally {
                ex.close()
            }
        }
    }

    private fun HttpExchange.send(body: ByteArray, status: Int = 200, type: String? = null) {
        type?.let { responseHeaders.add("Content-Type", it) }
        sendResponseHeaders(status, body.size.toLong())
        responseBody.write(body)
    }

    private fun HttpExchange.fail(status: Int) {
        sendResponseHeaders(status, -1)
    }

    private fun bytes(seed: Int, size: Int = 500) = ByteArray(size) { ((it * 7 + seed) % 256).toByte() }

    private fun playlist(sequence: Int, segments: List<String>, end: Boolean = false, extra: String = "", target: Int = 1): ByteArray =
        buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:$target\n#EXT-X-MEDIA-SEQUENCE:$sequence\n")
            append(extra)
            for (s in segments) append("#EXTINF:1.0,\n$s\n")
            if (end) append("#EXT-X-ENDLIST\n")
        }.toByteArray()

    private class Capture {
        val out = ByteArrayOutputStream()
        var extension: String? = null
        val opener: (String) -> OutputStream = { ext ->
            extension = ext
            out
        }
    }

    private fun recorder(retry: RetryPolicy = fast, clock: () -> Long = System::currentTimeMillis) = StreamRecorder(UrlFetcher(2000, 2000), retry, clock)

    // ---- HLS --------------------------------------------------------------------------------------------

    @Test
    fun recordsAFinishedPlaylistInOrder() {
        route("/vod.m3u8") { ex, _ -> ex.send(playlist(0, listOf("s0.ts", "s1.ts", "s2.ts"), end = true)) }
        for (i in 0..2) route("/s$i.ts") { ex, _ -> ex.send(bytes(i)) }
        val cap = Capture()
        val rec = recorder()
        val r = rec.record("$base/vod.m3u8", emptyMap(), 0, cap.opener)
        assertEquals(RecordOutcome.COMPLETE, r.outcome)
        assertEquals("ts", cap.extension)
        assertArrayEquals(bytes(0) + bytes(1) + bytes(2), cap.out.toByteArray())
        assertEquals(1500L, r.bytes)
        assertEquals(3000L, r.recordedMs)
        assertEquals(0, r.gaps)
    }

    @Test
    fun picksTheBestQualityOfAMasterPlaylist() {
        route("/master.m3u8") { ex, _ ->
            ex.send("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\nhigh.m3u8\n".toByteArray())
        }
        route("/low.m3u8") { ex, _ -> ex.send(playlist(0, listOf("low0.ts"), end = true)) }
        route("/high.m3u8") { ex, _ -> ex.send(playlist(0, listOf("high0.ts"), end = true)) }
        route("/low0.ts") { ex, _ -> ex.send(bytes(1)) }
        route("/high0.ts") { ex, _ -> ex.send(bytes(2)) }
        val cap = Capture()
        recorder().record("$base/master.m3u8", emptyMap(), 0, cap.opener)
        assertArrayEquals(bytes(2), cap.out.toByteArray())
        assertEquals(0, count("/low0.ts"))
    }

    @Test
    fun recordsFragmentedMp4WithItsInitSegmentOnce() {
        route("/f.m3u8") { ex, _ ->
            ex.send(playlist(0, listOf("a.m4s", "b.m4s", "c.m4s"), end = true, extra = "#EXT-X-MAP:URI=\"init.mp4\"\n"))
        }
        route("/init.mp4") { ex, _ -> ex.send(bytes(9, 100)) }
        route("/a.m4s") { ex, _ -> ex.send(bytes(1)) }
        route("/b.m4s") { ex, _ -> ex.send(bytes(2)) }
        route("/c.m4s") { ex, _ -> ex.send(bytes(3)) }
        val cap = Capture()
        val r = recorder().record("$base/f.m3u8", emptyMap(), 0, cap.opener)
        assertEquals("mp4", cap.extension)
        assertEquals(RecordOutcome.COMPLETE, r.outcome)
        assertArrayEquals(bytes(9, 100) + bytes(1) + bytes(2) + bytes(3), cap.out.toByteArray())
        assertEquals(1, count("/init.mp4"))
    }

    @Test
    fun followsALivePlaylistAndFetchesOnlyNewSegments() {
        // Three polls: the window moves on by two segments each time, the last one ends the stream.
        route("/live.m3u8") { ex, n ->
            when (n) {
                1 -> ex.send(playlist(0, listOf("l0.ts", "l1.ts", "l2.ts")))
                2 -> ex.send(playlist(2, listOf("l2.ts", "l3.ts", "l4.ts")))
                else -> ex.send(playlist(4, listOf("l4.ts", "l5.ts"), end = true))
            }
        }
        for (i in 0..5) route("/l$i.ts") { ex, _ -> ex.send(bytes(i, 100)) }
        val cap = Capture()
        val r = recorder().record("$base/live.m3u8", emptyMap(), 0, cap.opener)
        assertEquals(RecordOutcome.COMPLETE, r.outcome)
        // Starts with the newest two (l1, l2), then l3, l4, l5; l0 is never fetched.
        assertArrayEquals((1..5).map { bytes(it, 100) }.reduce { a, b -> a + b }, cap.out.toByteArray())
        assertEquals(0, count("/l0.ts"))
        for (i in 1..5) assertEquals("segment $i fetched once", 1, count("/l$i.ts"))
        assertEquals(0, r.gaps)
    }

    @Test
    fun aSegmentThatFailsTwiceIsFetchedAgain() {
        route("/r.m3u8") { ex, _ -> ex.send(playlist(0, listOf("r0.ts", "r1.ts", "r2.ts"), end = true)) }
        route("/r0.ts") { ex, _ -> ex.send(bytes(0)) }
        route("/r1.ts") { ex, n -> if (n < 3) ex.fail(500) else ex.send(bytes(1)) }
        route("/r2.ts") { ex, _ -> ex.send(bytes(2)) }
        val cap = Capture()
        val rec = recorder()
        val r = rec.record("$base/r.m3u8", emptyMap(), 0, cap.opener)
        assertEquals(RecordOutcome.COMPLETE, r.outcome)
        assertArrayEquals(bytes(0) + bytes(1) + bytes(2), cap.out.toByteArray())
        assertEquals(3, count("/r1.ts"))
        assertEquals(2, rec.progress.retries)
        assertEquals(0, r.gaps)
    }

    @Test
    fun aSegmentThatNeverComesIsSkippedAndCounted() {
        route("/g.m3u8") { ex, _ -> ex.send(playlist(0, listOf("g0.ts", "g1.ts", "g2.ts"), end = true)) }
        route("/g0.ts") { ex, _ -> ex.send(bytes(0)) }
        route("/g1.ts") { ex, _ -> ex.fail(404) }
        route("/g2.ts") { ex, _ -> ex.send(bytes(2)) }
        val cap = Capture()
        val r = recorder().record("$base/g.m3u8", emptyMap(), 0, cap.opener)
        assertEquals(RecordOutcome.PARTIAL, r.outcome)
        assertEquals(1, r.gaps)
        assertArrayEquals(bytes(0) + bytes(2), cap.out.toByteArray())
        assertEquals(3, count("/g1.ts"))
    }

    @Test
    fun missedSegmentsBetweenPollsAreCountedAsAGap() {
        route("/m.m3u8") { ex, n ->
            if (n == 1) ex.send(playlist(0, listOf("m0.ts", "m1.ts"))) else ex.send(playlist(5, listOf("m5.ts", "m6.ts"), end = true))
        }
        for (i in listOf(0, 1, 5, 6)) route("/m$i.ts") { ex, _ -> ex.send(bytes(i, 50)) }
        val cap = Capture()
        val r = recorder().record("$base/m.m3u8", emptyMap(), 0, cap.opener)
        assertEquals(1, r.gaps)
        assertEquals(RecordOutcome.PARTIAL, r.outcome)
        assertEquals(4 * 50L, r.bytes)
    }

    @Test
    fun givesUpWhenThePlaylistKeepsFailingButKeepsWhatWasRecorded() {
        route("/d.m3u8") { ex, n -> if (n == 1) ex.send(playlist(0, listOf("d0.ts"))) else ex.fail(503) }
        route("/d0.ts") { ex, _ -> ex.send(bytes(0)) }
        val cap = Capture()
        val rec = recorder()
        val r = rec.record("$base/d.m3u8", emptyMap(), 0, cap.opener)
        assertEquals(RecordOutcome.PARTIAL, r.outcome)
        assertArrayEquals(bytes(0), cap.out.toByteArray())
        // The first read plus 3 failures in a row, then it gives up (a short wait between tries).
        assertTrue(count("/d.m3u8") >= 4)
    }

    @Test
    fun aPlaylistThatNeverWorksFailsWithAMessageAndNoFile() {
        route("/x.m3u8") { ex, _ -> ex.fail(403) }
        val cap = Capture()
        val r = recorder().record("$base/x.m3u8", emptyMap(), 0, cap.opener)
        assertEquals(RecordOutcome.FAILED, r.outcome)
        assertTrue(r.error!!.contains("403"))
        assertEquals("no file is created for nothing", null, cap.extension)
        assertEquals(0L, r.bytes)
    }

    @Test
    fun theTimeOfTheRecordingEndsALivePlaylist() {
        val now = AtomicInteger(0)
        // Never-ending live stream; the clock jumps past the end after the second segment.
        route("/n.m3u8") { ex, n -> ex.send(playlist(n, listOf("n${n}.ts", "n${n + 1}.ts"))) }
        for (i in 0..30) route("/n$i.ts") { ex, _ -> ex.send(bytes(i, 40)); now.incrementAndGet() }
        val cap = Capture()
        val r = recorder(clock = { if (now.get() >= 2) 2_000L else 1_000L }).record("$base/n.m3u8", emptyMap(), 1_500L, cap.opener)
        assertEquals(RecordOutcome.COMPLETE, r.outcome)
        assertTrue(r.bytes in 80L..200L)
    }

    @Test
    fun stopEndsARecordingAndKeepsTheFile() {
        route("/s.m3u8") { ex, n -> ex.send(playlist(n, listOf("s${n}.ts", "s${n + 1}.ts"))) }
        for (i in 0..200) route("/s$i.ts") { ex, _ -> ex.send(bytes(i, 40)) }
        val cap = Capture()
        val rec = recorder()
        val t = Thread {
            // Stops as soon as some data is there.
            val deadline = System.currentTimeMillis() + 5000
            while (rec.progress.bytes < 120 && System.currentTimeMillis() < deadline) Thread.sleep(5)
            rec.stop()
        }
        t.start()
        val r = rec.record("$base/s.m3u8", emptyMap(), 0, cap.opener)
        t.join()
        assertEquals(RecordOutcome.STOPPED, r.outcome)
        assertTrue(r.bytes >= 120)
        assertEquals(r.bytes, cap.out.size().toLong())
    }

    @Test
    fun recordsEncryptedSegments() {
        val key = ByteArray(16) { (it + 1).toByte() }
        fun encrypt(plain: ByteArray, sequence: Int): ByteArray {
            val iv = ByteArray(16).also { it[15] = sequence.toByte() }
            return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                doFinal(plain)
            }
        }
        route("/e.m3u8") { ex, _ -> ex.send(playlist(3, listOf("e3.ts", "e4.ts"), end = true, extra = "#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\"\n")) }
        route("/k.bin") { ex, _ -> ex.send(key) }
        route("/e3.ts") { ex, _ -> ex.send(encrypt(bytes(3, 300), 3)) }
        route("/e4.ts") { ex, _ -> ex.send(encrypt(bytes(4, 300), 4)) }
        val cap = Capture()
        val r = recorder().record("$base/e.m3u8", emptyMap(), 0, cap.opener)
        assertEquals(RecordOutcome.COMPLETE, r.outcome)
        assertArrayEquals(bytes(3, 300) + bytes(4, 300), cap.out.toByteArray())
        assertEquals("the key is fetched once", 1, count("/k.bin"))
    }

    @Test
    fun usesByteRangesOfOneFile() {
        val file = bytes(5, 3000)
        route("/b.m3u8") { ex, _ ->
            ex.send(
                ("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\n#EXT-X-BYTERANGE:1000@0\nbig.ts\n#EXTINF:1,\n#EXT-X-BYTERANGE:1000\nbig.ts\n" +
                    "#EXTINF:1,\n#EXT-X-BYTERANGE:1000@2000\nbig.ts\n#EXT-X-ENDLIST\n").toByteArray(),
            )
        }
        route("/big.ts") { ex, n ->
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null && n % 2 == 1) {
                // Answers with the part, as servers that support ranges do.
                val (from, to) = range.removePrefix("bytes=").split('-').map { it.toInt() }
                ex.responseHeaders.add("Content-Range", "bytes $from-$to/3000")
                ex.send(file.copyOfRange(from, to + 1), status = 206)
            } else {
                // Ignores the range and sends everything: the recorder cuts out the part itself.
                ex.send(file)
            }
        }
        val cap = Capture()
        val r = recorder().record("$base/b.m3u8", emptyMap(), 0, cap.opener)
        assertEquals(RecordOutcome.COMPLETE, r.outcome)
        assertArrayEquals(file, cap.out.toByteArray())
    }

    @Test
    fun foundByWhatTheServerSendsNotByTheAddress() {
        // No .m3u8 in the address (as many providers' "…/123" links): the answer is a playlist.
        route("/play/123") { ex, _ -> ex.send(playlist(0, listOf("/w0.ts"), end = true)) }
        route("/w0.ts") { ex, _ -> ex.send(bytes(0)) }
        val cap = Capture()
        val r = recorder().record("$base/play/123", emptyMap(), 0, cap.opener)
        assertEquals(RecordOutcome.COMPLETE, r.outcome)
        assertArrayEquals(bytes(0), cap.out.toByteArray())
    }

    @Test
    fun sendsTheChannelsHeaders() {
        var seen: String? = null
        route("/h.m3u8") { ex, _ ->
            seen = ex.requestHeaders.getFirst("User-Agent") + "|" + ex.requestHeaders.getFirst("Referer")
            ex.send(playlist(0, listOf("h0.ts"), end = true))
        }
        route("/h0.ts") { ex, _ -> ex.send(bytes(0)) }
        recorder().record("$base/h.m3u8", mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "http://ref/"), 0, Capture().opener)
        assertEquals("Mozilla/5.0|http://ref/", seen)
    }

    // ---- progressive streams ----------------------------------------------------------------------------------

    @Test
    fun copiesAFileWithAKnownLength() {
        val file = bytes(1, 100_000)
        route("/movie.mp4") { ex, _ -> ex.send(file, type = "video/mp4") }
        val cap = Capture()
        val r = recorder().record("$base/movie.mp4", emptyMap(), 0, cap.opener)
        assertEquals(RecordOutcome.COMPLETE, r.outcome)
        assertEquals("mp4", cap.extension)
        assertArrayEquals(file, cap.out.toByteArray())
    }

    @Test
    fun aDroppedLiveStreamIsOpenedAgainAndContinuesInTheSameFile() {
        // An open-ended stream (no Content-Length): closes after a while, as providers do.
        route("/live.ts") { ex, n ->
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.write(bytes(n, 1000))
            ex.responseBody.flush()
        }
        val cap = Capture()
        val rec = recorder()
        val t = Thread {
            val deadline = System.currentTimeMillis() + 5000
            while (rec.progress.bytes < 3000 && System.currentTimeMillis() < deadline) Thread.sleep(5)
            rec.stop()
        }
        t.start()
        val r = rec.record("$base/live.ts", emptyMap(), 0, cap.opener)
        t.join()
        assertEquals(RecordOutcome.STOPPED, r.outcome)
        assertEquals("ts", cap.extension)
        val out = cap.out.toByteArray()
        assertTrue(out.size >= 3000)
        // Connection 1, 2, 3… each contributed its block, in order.
        for (block in 0 until out.size / 1000) {
            assertArrayEquals("block $block", bytes(block + 1, 1000), out.copyOfRange(block * 1000, block * 1000 + 1000))
        }
        assertTrue(rec.progress.retries >= 2)
    }

    @Test
    fun aStreamThatNeverStartsFails() {
        route("/dead.ts") { ex, _ -> ex.fail(500) }
        val cap = Capture()
        val r = recorder().record("$base/dead.ts", emptyMap(), 0, cap.opener)
        assertEquals(RecordOutcome.FAILED, r.outcome)
        assertTrue(r.error!!.contains("500"))
        assertEquals(4, count("/dead.ts"))
        assertEquals(null, cap.extension)
    }

    @Test
    fun aStreamThatDiesAfterDataEndsPartial() {
        route("/die.ts") { ex, n ->
            if (n == 1) {
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.write(bytes(1, 500))
            } else ex.fail(500)
        }
        val cap = Capture()
        val r = recorder().record("$base/die.ts", emptyMap(), 0, cap.opener)
        assertEquals(RecordOutcome.PARTIAL, r.outcome)
        assertArrayEquals(bytes(1, 500), cap.out.toByteArray())
    }

    @Test
    fun theEndTimeStopsAProgressiveStream() {
        val ticks = AtomicInteger(0)
        route("/long.ts") { ex, _ ->
            ex.sendResponseHeaders(200, 0)
            for (i in 0 until 50) {
                ex.responseBody.write(bytes(i, 100))
                ex.responseBody.flush()
                Thread.sleep(10)
            }
        }
        val cap = Capture()
        // The clock says "time is up" from the third look.
        val r = recorder(clock = { if (ticks.incrementAndGet() > 3) 2_000L else 1_000L }).record("$base/long.ts", emptyMap(), 1_500L, cap.opener)
        assertEquals(RecordOutcome.COMPLETE, r.outcome)
        assertTrue(r.bytes in 1L..4000L)
    }

    // ---- the destination --------------------------------------------------------------------------------------

    @Test
    fun aDestinationThatCannotBeWrittenEndsTheRecordingWithoutRetries() {
        route("/o.ts") { ex, _ ->
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.write(bytes(1, 1000))
        }
        val r = recorder().record("$base/o.ts", emptyMap(), 0) {
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("Disk full")
                override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("Disk full")
            }
        }
        assertEquals(RecordOutcome.FAILED, r.outcome)
        assertEquals("Disk full", r.error)
        assertEquals("the stream is not asked again for a problem on our side", 1, count("/o.ts"))
    }

    @Test
    fun theOutputIsOpenedOnlyWhenThereIsData() {
        val opened = AtomicInteger(0)
        route("/empty.m3u8") { ex, _ -> ex.send(playlist(0, emptyList(), end = true)) }
        val r = recorder().record("$base/empty.m3u8", emptyMap(), 0) {
            opened.incrementAndGet()
            ByteArrayOutputStream()
        }
        assertEquals(0, opened.get())
        assertEquals(0L, r.bytes)
        assertFalse(r.outcome == RecordOutcome.PARTIAL)
        assertNotNull(r)
    }
}
