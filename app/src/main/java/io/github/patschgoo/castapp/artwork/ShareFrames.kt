package io.github.patschgoo.castapp.artwork

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.discovery.NetworkScanner
import io.github.patschgoo.castapp.network.HttpMediaSource
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.stream.MediaSource
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URI

/**
 * One frame of a video on a network share (SMB or WebDAV), read over the network within a
 * [ReadBudget]: MediaMetadataRetriever reads the file through a [MediaDataSource] on the same
 * seekable source the cast uses. Nothing is tried on metered networks or while the share doesn't
 * answer. Blocking: [Artwork] calls it on its own thread, one file at a time.
 */
internal object ShareFrames {
    sealed interface Result {
        class Picture(val bitmap: Bitmap) : Result
        /** The file gives none (format, codec, too big to find one): don't try again soon. */
        data object NoPicture : Result
        /** Not now (metered network, share off): try again later. */
        data object Later : Result
    }

    private val sharesDown = FailureMemory(ArtworkRules.SHARE_DOWN_RETRY_MS)

    fun grab(context: Context, item: MediaItem, source: ArtSource.ShareFile, durationMs: Long): Result {
        if (isMetered(context)) return Result.Later
        if (!sharesDown.mayTry(source.share)) return Result.Later
        val file = open(context, item) ?: return Result.NoPicture
        if (!answers(file.host, file.port)) {
            sharesDown.failed(source.share)
            return Result.Later
        }
        val budget = ReadBudget()
        val data = SourceReader(BudgetedSource(file.source, budget))
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(data)
            val bitmap = if (source.audio) {
                PhoneThumbnails.coverOf(retriever, ArtworkRules.MAX_PX)
            } else {
                val length = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: durationMs
                PhoneThumbnails.frameOf(retriever, ArtworkRules.MAX_PX, ArtworkRules.frameTimeUs(length))
            }
            sharesDown.succeeded(source.share)
            bitmap?.let { Result.Picture(it) } ?: Result.NoPicture
        } catch (_: Exception) {
            if (budget.openFailed && budget.bytesRead == 0L) {
                // Answered the knock but wouldn't give the file (signed out, asleep half-way): later.
                sharesDown.failed(source.share)
                Result.Later
            } else {
                Result.NoPicture
            }
        } finally {
            try { retriever.release() } catch (_: Exception) {}
            data.close()
        }
    }

    /** A file on a share as a seekable source, and the host and port to knock at first. */
    private class ShareFile(val source: MediaSource, val host: String, val port: Int)

    private fun open(context: Context, item: MediaItem): ShareFile? {
        try {
            if (item.isSmb) {
                val rest = item.uri.removePrefix("smb://")
                val network = CastApp.get(context).network
                val share = network.get(rest.substringBefore('/')) ?: return null // the share was removed
                val target = share.smbTarget
                return ShareFile(network.smb(share).mediaSource(rest.substringAfter('/', "")), target.host, target.port)
            }
            val u = URI(item.uri)
            val host = u.host ?: return null
            val port = if (u.port > 0) u.port else if (u.scheme.equals("https", ignoreCase = true)) 443 else 80
            return ShareFile(RangeSource(item.uri, item.headers, item.trustAll), host, port)
        } catch (_: Exception) {
            return null
        }
    }

    /** Whether the share's server takes a connection at all (a NAS asleep or away from home doesn't). */
    private fun answers(host: String, port: Int): Boolean = try {
        NetworkScanner.isPortOpen(InetAddress.getByName(host), port, 1500)
    } catch (_: Exception) {
        false
    }

    /**
     * A WebDAV file read with range requests only: a server that ignores them would send the
     * whole file up to the frame, past the [ReadBudget] (the cast's [HttpMediaSource] skips ahead itself).
     */
    private class RangeSource(private val url: String, private val headers: Map<String, String>, private val trustAll: Boolean) : MediaSource {
        private val file = HttpMediaSource(url, headers, trustAll)
        override val length: Long get() = file.length

        override fun open(offset: Long): InputStream {
            val conn = HttpMediaSource.connect(url, headers, trustAll, range = if (offset > 0) "bytes=$offset-" else null, timeoutMs = 5000)
            val code = conn.responseCode
            if (code !in 200..299 || (offset > 0 && code != 206)) {
                conn.disconnect()
                throw IOException("The server answered $code to a range request")
            }
            return object : FilterInputStream(conn.inputStream) {
                override fun close() {
                    try { super.close() } catch (_: IOException) {}
                    conn.disconnect()
                }
            }
        }
    }

    private fun isMetered(context: Context): Boolean = try {
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered ?: true
    } catch (_: Exception) {
        true
    }
}

/**
 * Lets MediaMetadataRetriever read a [MediaSource]. Reads that follow each other share one open
 * stream, and small skips forward are read through rather than opening the file again.
 */
private class SourceReader(private val source: MediaSource) : MediaDataSource() {
    private var stream: InputStream? = null
    private var streamPos = -1L
    private val skipBuffer = ByteArray(16 * 1024)

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val length = source.length
        if (length in 0..position) return -1
        if (size == 0) return 0
        var s = stream
        if (s != null && position > streamPos && position - streamPos <= SKIP_MAX) {
            while (streamPos < position) {
                val n = s.read(skipBuffer, 0, minOf(skipBuffer.size.toLong(), position - streamPos).toInt())
                if (n < 0) break
                streamPos += n
            }
        }
        if (s == null || streamPos != position) {
            try { s?.close() } catch (_: Exception) {}
            s = source.open(position)
            stream = s
            streamPos = position
        }
        val n = s.read(buffer, offset, size)
        if (n < 0) return -1
        streamPos += n
        return n
    }

    override fun getSize(): Long = source.length

    @Synchronized
    override fun close() {
        try { stream?.close() } catch (_: Exception) {}
        stream = null
    }

    private companion object {
        const val SKIP_MAX = 256 * 1024L
    }
}
