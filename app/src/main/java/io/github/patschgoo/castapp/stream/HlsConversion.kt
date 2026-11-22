package io.github.patschgoo.castapp.stream

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Cuts a converted video into fMP4 segments at key frames, on one continuous
 * timeline starting at 0. Feed samples in timestamp order; [onSegment] gets each
 * finished segment.
 */
class VodSegmenter(
    audioSampleRate: Int?,
    private val minSegmentUs: Long = 4_000_000,
    /** Past this, ask the encoder for a key frame so no segment gets too long. */
    private val maxSegmentUs: Long = 8_000_000,
    private val requestKeyFrame: () -> Unit = {},
    private val onSegment: (durationUs: Long, data: ByteArray) -> Unit,
) {
    private val serializer = Mp4FragmentSerializer(audioSampleRate)
    private var current: ByteArrayOutputStream? = null
    private var startPtsUs = 0L
    private var lastVideoEndUs = 0L
    private var keyRequested = false

    fun add(sample: EncodedSample) {
        val video = sample.trackId == FragmentedMp4.VIDEO_TRACK_ID
        if (video) {
            if (current == null) {
                if (!sample.isKey) return
                begin(sample.ptsUs)
            } else {
                val elapsed = sample.ptsUs - startPtsUs
                if (sample.isKey && elapsed >= minSegmentUs) {
                    finishSegment(elapsed)
                    begin(sample.ptsUs)
                } else if (elapsed >= maxSegmentUs && !keyRequested) {
                    keyRequested = true
                    requestKeyFrame()
                }
            }
            lastVideoEndUs = sample.ptsUs + sample.durationUs
        }
        val bytes = serializer.serialize(sample) ?: return
        current?.write(bytes)
    }

    /** The video ended: the last (shorter) segment. */
    fun finish() {
        if (current != null) finishSegment((lastVideoEndUs - startPtsUs).coerceAtLeast(1))
        current = null
    }

    private fun begin(ptsUs: Long) {
        current = ByteArrayOutputStream(512 * 1024)
        startPtsUs = ptsUs
        keyRequested = false
    }

    private fun finishSegment(durationUs: Long) {
        val data = current?.toByteArray() ?: return
        current = null
        onSegment(durationUs, data)
    }
}

/**
 * The segments of one conversion: kept as files, listed in a growing (EVENT)
 * playlist that is closed when the conversion is complete. The converter waits
 * when it is too far ahead of what the TV has fetched, and segments the TV is
 * well past are deleted, so a two-hour film never fills the phone.
 */
class SegmentStore(
    private val dir: File,
    private val aheadLimit: Int = 12,
    private val keepBehind: Int = 4,
    private val targetDurationS: Int = 10,
) {
    private class Info(val index: Int, val durationUs: Long, val file: File)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val segments = ArrayList<Info>()
    @Volatile var init: ByteArray? = null
        private set
    private var lastRequested = -1
    @Volatile var finished = false
        private set
    @Volatile var failed = false
        private set
    @Volatile private var closed = false

    init {
        dir.mkdirs()
    }

    val count: Int get() = lock.withLock { segments.size }

    fun setInit(data: ByteArray) {
        lock.withLock {
            init = data
            changed.signalAll()
        }
    }

    fun add(durationUs: Long, data: ByteArray) {
        val index = lock.withLock { segments.size }
        val file = File(dir, "seg$index.m4s")
        file.writeBytes(data)
        lock.withLock {
            segments += Info(index, durationUs, file)
            prune()
            changed.signalAll()
        }
    }

    fun finish(error: Boolean = false) {
        lock.withLock {
            finished = true
            failed = error
            changed.signalAll()
        }
    }

    /** Blocks the converter while it is [aheadLimit] segments ahead of the TV. */
    fun awaitRoom(timeoutMs: Long = 1000): Boolean = lock.withLock {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!closed && segments.size - 1 - lastRequested >= aheadLimit) {
            if (nanos <= 0) return false
            nanos = changed.awaitNanos(nanos)
        }
        true
    }

    val isAhead: Boolean get() = lock.withLock { segments.size - 1 - lastRequested >= aheadLimit }

    /** The playlist once [minSegments] are ready (or the conversion ended), or null after [timeoutMs]. */
    fun playlist(minSegments: Int = 2, timeoutMs: Long = 0): String? = lock.withLock {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!closed && !finished && segments.size < minSegments) {
            if (nanos <= 0) return null
            nanos = changed.awaitNanos(nanos)
        }
        if (segments.isEmpty() && failed) return null
        buildString {
            val target = maxOf(targetDurationS.toLong(), segments.maxOfOrNull { (it.durationUs + 999_999) / 1_000_000 } ?: 0)
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:7\n")
            append("#EXT-X-TARGETDURATION:").append(target).append('\n')
            append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
            append("#EXT-X-MEDIA-SEQUENCE:0\n")
            append("#EXT-X-START:TIME-OFFSET=0,PRECISE=YES\n")
            append("#EXT-X-INDEPENDENT-SEGMENTS\n")
            append("#EXT-X-MAP:URI=\"init.mp4\"\n")
            for (s in segments) {
                append("#EXTINF:").append(String.format(Locale.US, "%.3f", s.durationUs / 1_000_000.0)).append(",\n")
                append("seg").append(s.index).append(".m4s\n")
            }
            if (finished) append("#EXT-X-ENDLIST\n")
        }
    }

    /** Segment [index], waiting up to [timeoutMs] for it to be converted; null if gone or never coming. */
    fun segment(index: Int, timeoutMs: Long = 0): ByteArray? {
        val file = lock.withLock {
            if (index > lastRequested) {
                lastRequested = index
                changed.signalAll()
            }
            var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (!closed && !finished && index >= segments.size) {
                if (nanos <= 0) return null
                nanos = changed.awaitNanos(nanos)
            }
            prune()
            segments.getOrNull(index)?.file
        } ?: return null
        return try { file.readBytes() } catch (_: Exception) { null }
    }

    /** Index of the newest segment the TV asked for (-1: none yet). */
    val requested: Int get() = lock.withLock { lastRequested }

    private fun prune() {
        val before = lastRequested - keepBehind
        for (s in segments) if (s.index < before && s.file.exists()) s.file.delete()
    }

    fun close() {
        lock.withLock {
            closed = true
            changed.signalAll()
        }
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }
}

/**
 * One conversion served as HLS (Apple TV and Roku can't play an endless MP4). It
 * keeps running across the TV's many playlist and segment requests and is
 * stopped when replaced (seeking, next file), idle, or when casting ends.
 */
class HlsConversion(
    val id: String,
    /** What it converts; equal keys reuse the running conversion. */
    val key: String,
    val store: SegmentStore,
    private val clock: () -> Long,
) {
    @Volatile var lastAccess: Long = clock()
        private set
    @Volatile private var stopper: (() -> Unit)? = null
    @Volatile var stopped = false
        private set

    fun touch() {
        lastAccess = clock()
    }

    /** Hands over how to stop the converter once it runs. */
    fun attach(stop: () -> Unit) {
        stopper = stop
        if (stopped) stop()
    }

    fun idleFor(now: Long): Long = now - lastAccess

    fun stop() {
        if (stopped) return
        stopped = true
        try { stopper?.invoke() } catch (_: Exception) {}
        store.close()
    }
}

/** The HLS conversions of a cast: at most one is current, the others are cleaned up. */
class HlsConversions(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val running = ConcurrentHashMap<String, HlsConversion>()
    private var counter = 0
    @Volatile var current: HlsConversion? = null
        private set

    operator fun get(id: String): HlsConversion? = running[id]?.also { it.touch() }

    val size: Int get() = running.size

    /** The running conversion of [key], without starting one (segment requests). */
    fun find(key: String): HlsConversion? = running.values.firstOrNull { it.key == key && !it.stopped }?.also { it.touch() }

    /**
     * The conversion for [key]: the running one if it matches, otherwise a new one
     * (started by [start], which returns how to stop it). Every other conversion stops.
     */
    @Synchronized
    fun obtain(key: String, start: (HlsConversion) -> (() -> Unit)): HlsConversion {
        current?.takeIf { it.key == key && !it.stopped && !it.store.failed }?.let {
            it.touch()
            return it
        }
        val id = "c" + (++counter) + "-" + Integer.toHexString(key.hashCode())
        val conversion = HlsConversion(id, key, SegmentStore(File(root, id)), clock)
        running.values.forEach { it.stop() }
        running.clear()
        running[id] = conversion
        current = conversion
        try {
            conversion.attach(start(conversion))
        } catch (e: Exception) {
            conversion.stop()
            running.remove(id)
            current = null
            throw e
        }
        return conversion
    }

    /** Stops conversions nobody fetched from for [idleMs] (the TV moved on or was turned off). */
    @Synchronized
    fun sweep(idleMs: Long = IDLE_MS) {
        val now = clock()
        val it = running.values.iterator()
        while (it.hasNext()) {
            val c = it.next()
            if (c.stopped || c.idleFor(now) > idleMs) {
                c.stop()
                it.remove()
                if (current === c) current = null
            }
        }
    }

    @Synchronized
    fun stopAll() {
        running.values.forEach { it.stop() }
        running.clear()
        current = null
        root.listFiles()?.forEach { f -> f.listFiles()?.forEach { it.delete() }; f.delete() }
    }

    companion object {
        /** A paused TV fetches nothing; give it a good while before throwing the conversion away. */
        const val IDLE_MS = 15 * 60_000L
    }
}
