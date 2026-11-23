package io.github.patschgoo.castapp.artwork

import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.service.MediaType
import io.github.patschgoo.castapp.stream.MediaSource
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Where the picture of a "Continue watching" entry comes from. */
sealed interface ArtSource {
    /** A poster, episode still or logo on the web ([MediaItem.artUri]). */
    data class Picture(val url: String) : ArtSource

    /** A video (or long recording) on the phone: Android's own thumbnail of it. */
    data class PhoneFile(val uri: String) : ArtSource

    /**
     * A file on a network share (SMB or WebDAV): one frame of the video, or the cover in a music
     * file, read over the network. [share] says which share, so one that doesn't answer is left
     * alone for a while instead of being asked again for each of its files.
     */
    data class ShareFile(val share: String, val audio: Boolean) : ArtSource
}

/** The rules of [Artwork] that don't need Android: what to show, cache names and limits. */
object ArtworkRules {
    /** Pictures are kept at most this big (longer side), on disk and in memory. */
    const val MAX_PX = 320

    /** A frame from a network share may read at most this much of the file ... */
    const val FRAME_MAX_BYTES = 24L * 1024 * 1024
    /** ... take at most this long ... */
    const val FRAME_MAX_MS = 20_000L
    /** ... and open the file at most this often (each seek is a new request on SMB and HTTP). */
    const val FRAME_MAX_OPENS = 48

    /** A share that didn't answer is asked again after this. */
    const val SHARE_DOWN_RETRY_MS = 10 * 60_000L
    /** A file that gave no picture (format, size, codec) is tried again after this. */
    const val NO_PICTURE_RETRY_MS = 3 * 24 * 3600_000L

    /** The disk cache keeps at most this many files and bytes, the least recently used go first. */
    const val CACHE_MAX_FILES = 200
    const val CACHE_MAX_BYTES = 16L * 1024 * 1024

    /**
     * What gives [item]'s picture, or null when nothing can: its [MediaItem.artUri] first, else the
     * file itself when it's on the phone or on a network share. Live streams, photos and web videos
     * without a poster have none. [webDavFolders] are the addresses of the saved WebDAV folders.
     */
    fun sourceFor(item: MediaItem, webDavFolders: List<String>): ArtSource? {
        MediaItem.pictureAddress(item.artUri)?.let { return ArtSource.Picture(it) }
        if (item.live || item.type == MediaType.PHOTO) return null
        val audio = item.type == MediaType.AUDIO
        val uri = item.uri
        return when {
            uri.startsWith("content://") || uri.startsWith("file://") -> ArtSource.PhoneFile(uri)
            item.isSmb -> ArtSource.ShareFile("smb:" + uri.removePrefix("smb://").substringBefore('/'), audio)
            item.isWeb && !item.isHls && !isDash(item) && item.serverRef == null ->
                webDavFolders.firstOrNull { sameServer(uri, it) }?.let { ArtSource.ShareFile("dav:" + origin(it), audio) }
            else -> null
        }
    }

    private fun isDash(item: MediaItem) = item.mimeType.contains("dash", ignoreCase = true)

    /** The file name of [item]'s picture in the cache (also the memory key): the same for the same item and poster. */
    fun cacheKey(itemUri: String, artUri: String?): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("v1\n$itemUri\n${artUri.orEmpty()}".toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    /** Where in a video of [durationMs] to take its picture (µs): a tenth in, past the opening titles; 60 s when the length is unknown. */
    fun frameTimeUs(durationMs: Long): Long = when {
        durationMs <= 0 -> 60_000_000L
        durationMs < 20_000 -> durationMs * 1000 / 2
        else -> durationMs * 100 // 10 %, in µs
    }

    /** [width] × [height] shrunk to fit [max] (never enlarged), keeping its shape. */
    fun fitInside(width: Int, height: Int, max: Int = MAX_PX): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        if (width <= max && height <= max) return width to height
        val scale = max.toDouble() / maxOf(width, height)
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    /** How a card shows a picture of this shape. */
    enum class Fit {
        /** Fills the card, cut to its shape: stills, frames, wide pictures. */
        FILL,
        /** A tall poster: whole, in the middle, over a faint copy of itself that fills the card. */
        POSTER,
        /** A logo or cover (square-ish, or with see-through parts): whole, with room around it. */
        LOGO,
    }

    fun fitFor(width: Int, height: Int, hasAlpha: Boolean): Fit {
        if (width <= 0 || height <= 0) return Fit.LOGO
        val ratio = width.toFloat() / height
        return when {
            hasAlpha -> Fit.LOGO
            ratio < 0.8f -> Fit.POSTER
            ratio <= 1.25f -> Fit.LOGO
            else -> Fit.FILL
        }
    }

    /** One file in the disk cache. */
    data class CachedFile(val name: String, val bytes: Long, val lastUsed: Long)

    /** The files to delete so that at most [maxFiles] and [maxBytes] stay, keeping the most recently used. */
    fun toPrune(files: List<CachedFile>, maxFiles: Int = CACHE_MAX_FILES, maxBytes: Long = CACHE_MAX_BYTES): List<CachedFile> {
        var count = 0
        var total = 0L
        val out = ArrayList<CachedFile>()
        for (f in files.sortedByDescending { it.lastUsed }) {
            if (count < maxFiles && total + f.bytes <= maxBytes) {
                count++
                total += f.bytes
            } else {
                out += f
            }
        }
        return out
    }

    /** Whether a "no picture" note written at [writtenAt] still holds at [now]. */
    fun noPictureStillHolds(writtenAt: Long, now: Long): Boolean = now >= writtenAt && now - writtenAt < NO_PICTURE_RETRY_MS

    private fun sameServer(uri: String, folder: String): Boolean {
        val a = origin(uri)
        return a.isNotEmpty() && a == origin(folder)
    }

    /** scheme://host:port, lower case, with the default port filled in; empty when [url] is no web address. */
    internal fun origin(url: String): String = try {
        val u = URI(url.trim())
        val scheme = u.scheme?.lowercase(Locale.ROOT).orEmpty()
        val host = u.host?.lowercase(Locale.ROOT).orEmpty()
        if ((scheme != "http" && scheme != "https") || host.isEmpty()) "" else {
            val port = if (u.port > 0) u.port else if (scheme == "https") 443 else 80
            "$scheme://$host:$port"
        }
    } catch (_: Exception) {
        ""
    }
}

/** Remembers what failed until when, so a share that is off isn't asked again for every one of its files. */
class FailureMemory(private val retryAfterMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val until = ConcurrentHashMap<String, Long>()

    fun mayTry(key: String): Boolean {
        val t = until[key] ?: return true
        if (clock() < t) return false
        until.remove(key, t)
        return true
    }

    fun failed(key: String) {
        until[key] = clock() + retryAfterMs
    }

    fun succeeded(key: String) {
        until.remove(key)
    }
}

/**
 * How much one picture may read from the network: bytes, time and the number of times the file is
 * opened (seeks). Once one runs out, every further read fails, and the reader gives up.
 */
class ReadBudget(
    private val maxBytes: Long = ArtworkRules.FRAME_MAX_BYTES,
    maxMs: Long = ArtworkRules.FRAME_MAX_MS,
    private val maxOpens: Int = ArtworkRules.FRAME_MAX_OPENS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val deadline = clock() + maxMs
    private val bytes = AtomicLong()
    private val opens = AtomicInteger()
    /** Opening the file failed (share or server gone), as opposed to running out of budget. */
    @Volatile var openFailed = false
        internal set
    @Volatile var exceeded = false
        private set

    val bytesRead: Long get() = bytes.get()

    fun check() {
        val why = when {
            bytes.get() > maxBytes -> "more than $maxBytes bytes"
            clock() > deadline -> "too slow"
            opens.get() > maxOpens -> "opened more than $maxOpens times"
            else -> return
        }
        exceeded = true
        throw IOException("Picture budget used up: $why")
    }

    fun opened() {
        opens.incrementAndGet()
        check()
    }

    fun read(n: Int) {
        if (n > 0) bytes.addAndGet(n.toLong())
    }

    /** How many bytes a read may still ask for (at least 1, so running out shows on the next [check]). */
    fun allowance(wanted: Int): Int = (maxBytes - bytes.get() + 1).coerceIn(1L, wanted.toLong().coerceAtLeast(1L)).toInt()
}

/** [source] read within [budget]: for pictures from network shares. */
class BudgetedSource(private val source: MediaSource, private val budget: ReadBudget) : MediaSource {
    override val length: Long get() = source.length

    override fun open(offset: Long): InputStream {
        budget.opened()
        val input = try {
            source.open(offset)
        } catch (e: IOException) {
            budget.openFailed = true
            throw e
        } catch (e: RuntimeException) {
            budget.openFailed = true
            throw IOException(e)
        }
        return object : InputStream() {
            override fun read(): Int {
                budget.check()
                val b = input.read()
                if (b >= 0) budget.read(1)
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                budget.check()
                val n = input.read(b, off, budget.allowance(len))
                budget.read(n)
                return n
            }

            override fun close() {
                try { input.close() } catch (_: Exception) {}
            }
        }
    }
}
