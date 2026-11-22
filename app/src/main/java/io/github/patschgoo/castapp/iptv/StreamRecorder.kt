package io.github.patschgoo.castapp.iptv

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** How often and how long a recording tries again when a stream drops. */
data class RetryPolicy(
    /** Failures in a row (without any data in between) after which a recording gives up. */
    val maxFailures: Int = 8,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 15_000,
    /** Tries for one HLS segment before it is left out (and counted as a gap). */
    val segmentAttempts: Int = 3,
    /** How often a live HLS playlist is read again, as a share of its target duration, within these limits. */
    val pollMinMs: Long = 1_000,
    val pollMaxMs: Long = 6_000,
    /** Live HLS: how many of the newest segments to start with. */
    val liveStartSegments: Int = 2,
) {
    /** The wait before try number [failure] (1 = the first retry): doubling, with a ceiling. */
    fun delayFor(failure: Int): Long {
        if (failure <= 0) return 0
        var d = baseDelayMs
        repeat(failure - 1) {
            d *= 2
            if (d >= maxDelayMs) return maxDelayMs
        }
        return minOf(d, maxDelayMs)
    }

    fun pollInterval(targetDurationSec: Int): Long = (targetDurationSec * 500L).coerceIn(pollMinMs, pollMaxMs)
}

class HttpFailure(val status: Int, message: String) : IOException(message)

/** An open answer to a request; closing it ends the connection. */
class HttpStream(
    val input: InputStream,
    val status: Int,
    val contentType: String?,
    /** -1 when unknown (live streams). */
    val contentLength: Long,
    val url: String,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        try {
            input.close()
        } catch (_: Exception) {
        } finally {
            onClose()
        }
    }
}

interface HttpFetcher {
    /** GETs [url] (with redirects). [range] asks for part of the file; a server that ignores it is handled. */
    fun open(url: String, headers: Map<String, String>, range: HlsByteRange? = null): HttpStream

    /** Cancels the request in progress (from another thread). */
    fun abort()
}

class UrlFetcher(private val connectTimeoutMs: Int = 15_000, private val readTimeoutMs: Int = 20_000) : HttpFetcher {
    @Volatile private var current: HttpURLConnection? = null
    @Volatile private var aborted = false

    override fun open(url: String, headers: Map<String, String>, range: HlsByteRange?): HttpStream {
        var target = url
        repeat(8) {
            if (aborted) throw IOException("Stopped")
            val conn = URL(target).openConnection() as HttpURLConnection
            current = conn
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "CastApp")
            conn.setRequestProperty("Accept-Encoding", "identity")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (range != null) conn.setRequestProperty("Range", "bytes=${range.offset}-${range.offset + range.length - 1}")
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                throw e
            }
            when (code) {
                301, 302, 303, 307, 308 -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) throw IOException("The address redirects nowhere")
                    target = URL(URL(target), location).toString()
                }
                in 200..299 -> {
                    var input: InputStream = conn.inputStream
                    var length = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                    if (range != null && code == 200) {
                        // The server sent the whole file: cut out the part that was asked for.
                        input = LimitedInputStream(input, range.offset, range.length)
                        length = range.length
                    }
                    return HttpStream(input, code, conn.contentType, length, target) { conn.disconnect() }
                }
                else -> {
                    conn.disconnect()
                    throw HttpFailure(code, "The server answered $code")
                }
            }
        }
        throw IOException("Too many redirects")
    }

    override fun abort() {
        aborted = true
        current?.disconnect()
    }
}

/** Skips [skip] bytes, then passes on at most [limit]. */
internal class LimitedInputStream(private val source: InputStream, skip: Long, private var left: Long) : InputStream() {
    private var toSkip = skip

    private fun skipAhead() {
        while (toSkip > 0) {
            val n = source.skip(toSkip)
            if (n <= 0) {
                if (source.read() < 0) {
                    toSkip = 0
                    left = 0
                    return
                }
                toSkip--
            } else toSkip -= n
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        skipAhead()
        if (left <= 0) return -1
        val n = source.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }

    override fun close() = source.close()
}

/** What a recording has got so far; read from other threads (the notification, the list). */
class RecordProgress {
    @Volatile var bytes = 0L
    @Volatile var recordedMs = 0L
    @Volatile var gaps = 0
    @Volatile var retries = 0
    @Volatile var extension = "ts"
    @Volatile var started = false
}

enum class RecordOutcome {
    /** Recorded to the end that was asked for. */
    COMPLETE,

    /** Stopped by the user. */
    STOPPED,

    /** Ended early (the stream kept failing) or with gaps, but there is something to watch. */
    PARTIAL,

    /** Nothing was recorded. */
    FAILED,
}

class RecordResult(
    val outcome: RecordOutcome,
    val bytes: Long,
    val recordedMs: Long,
    val gaps: Int,
    val extension: String,
    val error: String? = null,
)

/**
 * Records one stream (as long as [until] says, or until [stop]) into the stream that
 * [openOutput] gives. Runs on the calling thread (blocking); [stop] may be called from any thread.
 *
 *  - Progressive streams (MPEG-TS from a provider, a plain file) are copied as they arrive; a dropped
 *    connection is opened again (with waits that grow) and recording continues in the same file.
 *  - HLS (an `.m3u8` address, found by looking at what the server sends) is recorded by fetching the
 *    segments in order and writing them one after the other: MPEG-TS segments as they are, fMP4
 *    segments after their initialisation segment. A segment that can't be fetched is skipped (a gap).
 */
class StreamRecorder(
    private val http: HttpFetcher = UrlFetcher(),
    private val retry: RetryPolicy = RetryPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** The highest quality (bit/s) to take from an HLS list of qualities; 0 = the best. */
    private val maxBandwidth: Long = 0,
) {
    @Volatile private var stopped = false
    val progress = RecordProgress()

    fun stop() {
        stopped = true
        http.abort()
    }

    private class Sink(private val openOutput: (String) -> OutputStream, private val progress: RecordProgress) {
        private var out: OutputStream? = null
        val isOpen: Boolean get() = out != null

        fun open(extension: String) {
            if (out == null) {
                progress.extension = extension
                out = openOutput(extension)
                progress.started = true
            }
        }

        fun write(buf: ByteArray, off: Int, len: Int) {
            out!!.write(buf, off, len)
            progress.bytes += len
        }

        fun close() {
            out?.close()
        }
    }

    /**
     * Records [request]. [until] is the time (clock time) to stop at, 0 to go on until [stop].
     * Failures to write to [openOutput]'s stream end the recording (they are not the stream's fault).
     */
    fun record(url: String, headers: Map<String, String>, until: Long, openOutput: (String) -> OutputStream): RecordResult {
        val sink = Sink(openOutput, progress)
        var error: String? = null
        var outcome: RecordOutcome
        try {
            outcome = run(url, headers, until, sink)
        } catch (e: OutputFailure) {
            error = e.cause?.message ?: "Can't write the recording"
            outcome = RecordOutcome.FAILED
        } catch (e: IOException) {
            error = e.message ?: e.javaClass.simpleName
            outcome = RecordOutcome.FAILED
        }
        try {
            sink.close()
        } catch (e: IOException) {
            error = error ?: (e.message ?: "Can't finish the file")
            outcome = RecordOutcome.FAILED
        }
        if (outcome == RecordOutcome.FAILED && progress.bytes > 0) outcome = RecordOutcome.PARTIAL
        return RecordResult(outcome, progress.bytes, progress.recordedMs, progress.gaps, progress.extension, error)
    }

    /** Wraps failures of the destination, which must not be retried like network drops. */
    private class OutputFailure(cause: Throwable) : IOException(cause)

    private fun write(sink: Sink, extension: String, buf: ByteArray, off: Int, len: Int) {
        try {
            sink.open(extension)
            sink.write(buf, off, len)
        } catch (e: IOException) {
            throw OutputFailure(e)
        }
    }

    private fun timeUp(until: Long): Boolean = until > 0 && clock() >= until

    private fun pause(ms: Long) {
        var left = ms
        while (left > 0 && !stopped) {
            val slice = minOf(left, 100L)
            Thread.sleep(slice)
            left -= slice
        }
    }

    private fun run(url: String, headers: Map<String, String>, until: Long, sink: Sink): RecordOutcome {
        var failures = 0
        var lastError: String? = null
        // The playlist found while looking at what the server sends, used at once instead of asking again.
        var firstHls: Pair<String, String>? = null
        while (true) {
            if (stopped) return RecordOutcome.STOPPED
            if (timeUp(until)) return finish(false)
            val received = progress.bytes
            try {
                http.open(url, headers).use { stream ->
                    val peek = BufferedInputStream(stream.input, 64 * 1024)
                    peek.mark(16)
                    val head = ByteArray(16)
                    var n = 0
                    while (n < head.size) {
                        val r = peek.read(head, n, head.size - n)
                        if (r < 0) break
                        n += r
                    }
                    peek.reset()
                    if (HlsPlaylist.looksLikePlaylist(head, n)) {
                        firstHls = stream.url to readText(peek)
                    } else {
                        val outcome = copyProgressive(peek, stream, sink, until)
                        if (outcome != null) return outcome
                        // The connection ended before the time was up: a drop.
                        throw IOException("The stream ended")
                    }
                }
            } catch (e: OutputFailure) {
                throw e
            } catch (e: IOException) {
                if (stopped) return RecordOutcome.STOPPED
                if (progress.bytes > received) failures = 0
                failures++
                lastError = e.message ?: e.javaClass.simpleName
                if (failures > retry.maxFailures) {
                    if (progress.bytes == 0L) throw IOException(lastError)
                    return RecordOutcome.PARTIAL
                }
                progress.retries++
                pause(retry.delayFor(failures))
                continue
            }
            val (playlistUrl, text) = firstHls ?: continue
            return recordHls(playlistUrl, text, headers, until, sink)
        }
    }

    private fun finish(stoppedByUser: Boolean): RecordOutcome = when {
        stoppedByUser -> RecordOutcome.STOPPED
        progress.gaps > 0 -> RecordOutcome.PARTIAL
        else -> RecordOutcome.COMPLETE
    }

    /** Copies until the end of the stream (null if it was cut short) or the time is up (COMPLETE) or [stop]. */
    private fun copyProgressive(input: InputStream, stream: HttpStream, sink: Sink, until: Long): RecordOutcome? {
        val extension = extensionFor(stream)
        val buf = ByteArray(64 * 1024)
        val startedAt = clock()
        val recordedBefore = progress.recordedMs
        val bytesBefore = progress.bytes
        while (true) {
            if (stopped) return RecordOutcome.STOPPED
            if (timeUp(until)) return finish(false)
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                if (stopped) return RecordOutcome.STOPPED
                throw e
            }
            if (n < 0) {
                progress.recordedMs = recordedBefore + (clock() - startedAt)
                // A file with a known length that arrived completely is a finished recording; a live stream that ends is a drop.
                val whole = stream.contentLength >= 0 && progress.bytes - bytesBefore >= stream.contentLength
                return if (whole) finish(false) else null
            }
            if (n > 0) {
                write(sink, extension, buf, 0, n)
                progress.recordedMs = recordedBefore + (clock() - startedAt)
            }
        }
    }

    private fun extensionFor(stream: HttpStream): String {
        val type = stream.contentType?.lowercase().orEmpty()
        val path = stream.url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return when {
            type.contains("mp4") || path == "mp4" -> "mp4"
            type.contains("matroska") || path == "mkv" -> "mkv"
            type.contains("webm") || path == "webm" -> "webm"
            type.startsWith("audio/mpeg") || path == "mp3" -> "mp3"
            else -> "ts"
        }
    }

    private fun readText(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < MAX_PLAYLIST_BYTES) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    private fun fetchPlaylist(url: String, headers: Map<String, String>): Pair<String, String> =
        http.open(url, headers).use { it.url to readText(it.input) }

    // ---- HLS ---------------------------------------------------------------------------------------------

    private fun recordHls(firstUrl: String, firstText: String, headers: Map<String, String>, until: Long, sink: Sink): RecordOutcome {
        var url = firstUrl
        var text: String? = firstText
        val parsed = HlsPlaylist.parse(firstText, firstUrl)
        if (parsed is HlsPlaylist.Master) {
            val variant = parsed.pick(maxBandwidth) ?: throw IOException("The playlist lists no streams")
            url = variant.uri
            text = null
        }
        var lastSequence = Long.MIN_VALUE
        var first = true
        var failures = 0
        val keys = HashMap<String, ByteArray>()
        var writtenInit: HlsInit? = null
        var extension: String? = null
        while (true) {
            if (stopped) return RecordOutcome.STOPPED
            if (timeUp(until)) return finish(false)
            val media: HlsPlaylist.Media = try {
                val (u, t) = if (text != null) url to text else fetchPlaylist(url, headers)
                text = null
                val p = HlsPlaylist.parse(t, u)
                if (p !is HlsPlaylist.Media) throw IOException("Unexpected playlist of playlists")
                failures = 0
                p
            } catch (e: IOException) {
                if (stopped) return RecordOutcome.STOPPED
                failures++
                if (failures > retry.maxFailures) {
                    if (progress.bytes == 0L) throw e
                    return RecordOutcome.PARTIAL
                }
                progress.retries++
                pause(retry.delayFor(failures))
                continue
            }
            var segments = media.segments
            if (first) {
                if (!media.endList) segments = segments.takeLast(retry.liveStartSegments.coerceAtLeast(1))
                first = false
            } else {
                if (segments.isNotEmpty() && lastSequence != Long.MIN_VALUE && segments.first().sequence > lastSequence + 1) {
                    // The phone was too slow or the stream paused: segments went by unseen.
                    progress.gaps++
                }
                segments = segments.filter { it.sequence > lastSequence }
            }
            val ext = extension ?: media.extension.also { extension = it }
            for (segment in segments) {
                if (stopped) return RecordOutcome.STOPPED
                if (timeUp(until)) return finish(false)
                val ok = fetchSegment(segment, headers, keys, sink, ext, writtenInit)
                if (ok != null) {
                    if (segment.init != null) writtenInit = segment.init
                    progress.recordedMs += (segment.durationSec * 1000).toLong()
                } else {
                    if (stopped) return RecordOutcome.STOPPED
                    progress.gaps++
                }
                lastSequence = segment.sequence
            }
            if (media.endList) return finish(false)
            if (timeUp(until)) return finish(false)
            pause(retry.pollInterval(media.targetDurationSec))
        }
    }

    /** Writes one segment (with its initialisation segment when that is new); returns null if it could not be fetched. */
    private fun fetchSegment(
        segment: HlsSegment,
        headers: Map<String, String>,
        keys: MutableMap<String, ByteArray>,
        sink: Sink,
        extension: String,
        writtenInit: HlsInit?,
    ): Unit? {
        var attempt = 0
        while (true) {
            attempt++
            try {
                val body: ByteArray? = if (segment.key != null) {
                    val key = keys.getOrPut(segment.key.uri) { http.open(segment.key.uri, headers).use { it.input.readBytes() } }
                    val raw = http.open(segment.uri, headers, segment.range).use { it.input.readBytes() }
                    HlsPlaylist.decrypt(raw, key, segment.key.iv, segment.sequence)
                } else null
                val initBytes = if (segment.init != null && segment.init != writtenInit) {
                    http.open(segment.init.uri, headers, segment.init.range).use { it.input.readBytes() }
                } else null
                // Everything that can fail on the network comes first, so a retry never writes twice.
                if (initBytes != null) write(sink, extension, initBytes, 0, initBytes.size)
                if (body != null) {
                    write(sink, extension, body, 0, body.size)
                } else {
                    http.open(segment.uri, headers, segment.range).use { s ->
                        // Streamed straight to the file; a drop in the middle leaves a partial segment, which players cope with.
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = s.input.read(buf)
                            if (n < 0) break
                            write(sink, extension, buf, 0, n)
                        }
                    }
                }
                return Unit
            } catch (e: OutputFailure) {
                throw e
            } catch (e: IOException) {
                if (stopped) return null
                if (attempt >= retry.segmentAttempts) return null
                progress.retries++
                pause(retry.delayFor(attempt))
            } catch (e: java.security.GeneralSecurityException) {
                return null
            }
        }
    }

    companion object {
        private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024
    }
}
