package io.github.patschgoo.castapp.stream

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * An encoded audio or video access unit with its timing in microseconds. [ptsUs] is the decoding time,
 * which is also the presentation time unless [compositionOffsetUs] says the frame is shown later
 * (B-frames of an H.264 picture copied from the file); 0 for everything the phone encodes.
 */
class EncodedSample(
    val trackId: Int,
    val data: ByteArray,
    val ptsUs: Long,
    val durationUs: Long,
    val isKey: Boolean,
    val compositionOffsetUs: Long = 0,
)

fun interface StreamSubscriber {
    fun onSample(sample: EncodedSample)
}

/**
 * Fan-out point between the encoders and everybody who wants the stream
 * (HTTP clients, the HLS segmenter). Encoders push samples from their own
 * threads; subscribers must be fast and must not block.
 */
class StreamHub {
    @Volatile var videoConfig: VideoConfig? = null
        private set
    @Volatile var audioConfig: AudioConfig? = null
        private set
    @Volatile private var audioExpected = false
    @Volatile private var initSegment: ByteArray? = null
    @Volatile private var initHasAudio = false
    @Volatile var keyFrameRequester: (() -> Unit)? = null

    /** Shifts audio against video (positive = sound later), for TVs whose sound is off. */
    @Volatile var audioOffsetUs: Long = 0

    /** Told how far behind the slowest live client is, for automatic quality. */
    @Volatile var congestionListener: CongestionListener? = null

    interface CongestionListener {
        /** [lagUs]: media waiting to be sent to a client. */
        fun onClientLag(lagUs: Long)

        /** A client fell so far behind that queued frames had to be dropped. */
        fun onClientDropped()
    }

    private val audioConfigLock = ReentrantLock()
    private val audioReady = audioConfigLock.newCondition()

    private val initLock = ReentrantLock()
    private val initReady = initLock.newCondition()
    private val subscribers = CopyOnWriteArrayList<StreamSubscriber>()

    // The duration of a video frame is only known once the next frame arrives,
    // so the newest frame is held back by one frame interval.
    private val videoLock = Any()
    private var pendingVideo: EncodedSample? = null

    /** Audio sample rate of the published init segment, or null when it is video-only. */
    val publishedAudioRate: Int?
        get() = if (initHasAudio) audioConfig?.sampleRate else null

    fun expectAudio(expected: Boolean) {
        audioExpected = expected
        maybeBuildInit()
    }

    fun setVideoConfig(config: VideoConfig) {
        videoConfig = config
        maybeBuildInit()
    }

    fun setAudioConfig(config: AudioConfig) {
        audioConfig = config
        audioConfigLock.withLock { audioReady.signalAll() }
        maybeBuildInit()
    }

    /** Waits for the AAC configuration (audio-only streams). */
    fun awaitAudioConfig(timeoutMs: Long): AudioConfig? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        audioConfigLock.withLock {
            while (audioConfig == null) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return null
                audioReady.awaitNanos(left)
            }
            return audioConfig
        }
    }

    private fun maybeBuildInit(forceWithoutAudio: Boolean = false) {
        initLock.withLock {
            if (initSegment != null) return
            val video = videoConfig ?: return
            val audio = audioConfig
            if (audioExpected && audio == null && !forceWithoutAudio) return
            initHasAudio = audioExpected && audio != null
            initSegment = FragmentedMp4.initSegment(video, if (initHasAudio) audio else null)
            initReady.signalAll()
        }
    }

    /**
     * Waits for the init segment. If audio was expected but never showed up,
     * the stream falls back to video-only after [audioGraceMs]. [failed]: the source gave up
     * (the converter's decoder broke), so there's no point waiting out [timeoutMs].
     */
    fun awaitInitSegment(timeoutMs: Long, audioGraceMs: Long = 2500, failed: () -> Boolean = { false }): ByteArray? {
        val start = System.nanoTime()
        initLock.withLock {
            while (initSegment == null) {
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                if (elapsedMs >= timeoutMs || failed()) return null
                if (elapsedMs >= audioGraceMs && videoConfig != null) {
                    maybeBuildInit(forceWithoutAudio = true)
                    continue
                }
                initReady.await(100, TimeUnit.MILLISECONDS)
            }
            return initSegment
        }
    }

    fun currentInitSegment(): ByteArray? = initSegment

    /**
     * Forgets the video format (the picture size is about to change): the next
     * codec config starts a new stream. Audio settings are kept.
     */
    fun resetVideo() {
        initLock.withLock {
            videoConfig = null
            initSegment = null
            initHasAudio = false
        }
        synchronized(videoLock) { pendingVideo = null }
    }

    fun subscribe(subscriber: StreamSubscriber) {
        subscribers.add(subscriber)
        requestKeyFrame()
    }

    fun unsubscribe(subscriber: StreamSubscriber) {
        subscribers.remove(subscriber)
    }

    val subscriberCount: Int get() = subscribers.size

    fun requestKeyFrame() {
        try {
            keyFrameRequester?.invoke()
        } catch (_: Exception) {
        }
    }

    /**
     * Called by the video encoder with an MP4-formatted (length-prefixed) access unit. A copied picture
     * passes its decoding time as [ptsUs] and shows the frame [compositionOffsetUs] later.
     */
    fun pushVideo(data: ByteArray, ptsUs: Long, isKey: Boolean, compositionOffsetUs: Long = 0) {
        val ready: EncodedSample?
        synchronized(videoLock) {
            val prev = pendingVideo
            if (prev != null && ptsUs <= prev.ptsUs) {
                // Out-of-order or duplicate timestamp: keep the newer data, drop the old frame
                // unless it was a key frame that clients may be waiting for.
                if (!prev.isKey || isKey) {
                    pendingVideo = EncodedSample(FragmentedMp4.VIDEO_TRACK_ID, data, prev.ptsUs, 0, isKey || prev.isKey, compositionOffsetUs)
                }
                return
            }
            ready = prev?.let {
                val duration = (ptsUs - it.ptsUs).coerceIn(1_000L, 10_000_000L)
                EncodedSample(it.trackId, it.data, it.ptsUs, duration, it.isKey, it.compositionOffsetUs)
            }
            pendingVideo = EncodedSample(FragmentedMp4.VIDEO_TRACK_ID, data, ptsUs, 0, isKey, compositionOffsetUs)
        }
        if (ready != null) publish(ready)
    }

    /** Called by the audio encoder with one raw AAC frame (1024 samples). */
    fun pushAudio(data: ByteArray, ptsUs: Long) {
        val rate = audioConfig?.sampleRate ?: return
        publish(EncodedSample(FragmentedMp4.AUDIO_TRACK_ID, data, ptsUs + audioOffsetUs, AAC_FRAME_SAMPLES * 1_000_000L / rate, true))
    }

    private fun publish(sample: EncodedSample) {
        for (s in subscribers) {
            try {
                s.onSample(sample)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val AAC_FRAME_SAMPLES = 1024
    }
}

/**
 * Converts samples into moof/mdat fragments on a continuous output timeline
 * that starts at zero with the first key frame. After a [discontinuity] (frames
 * were dropped to catch up) the timeline is stitched together so the player
 * simply continues with newer content instead of waiting for the gap.
 */
class Mp4FragmentSerializer(private val audioSampleRate: Int?) {
    private var sequence = 1
    private var started = false
    private var baseUs = 0L
    private var videoEndUs = 0L
    private var audioNextTicks = -1L

    val hasStarted: Boolean get() = started

    fun serialize(sample: EncodedSample, discontinuity: Boolean = false): ByteArray? {
        return if (sample.trackId == FragmentedMp4.VIDEO_TRACK_ID) video(sample, discontinuity) else audio(sample)
    }

    private fun video(s: EncodedSample, discontinuity: Boolean): ByteArray? {
        if (!started) {
            if (!s.isKey) return null
            started = true
            baseUs = s.ptsUs
        } else if (discontinuity) {
            baseUs = s.ptsUs - videoEndUs
            audioNextTicks = -1
        }
        val startUs = maxOf(s.ptsUs - baseUs, videoEndUs)
        val endUs = startUs + s.durationUs
        val startTicks = toTicks(startUs, FragmentedMp4.VIDEO_TIMESCALE)
        val endTicks = toTicks(endUs, FragmentedMp4.VIDEO_TIMESCALE)
        videoEndUs = endUs
        val duration = (endTicks - startTicks).toInt().coerceAtLeast(1)
        // A copied B-frame picture: shown later than decoded (rounded like the decoding times).
        val offset = if (s.compositionOffsetUs > 0) {
            (toTicks(startUs + s.compositionOffsetUs, FragmentedMp4.VIDEO_TIMESCALE) - startTicks).toInt()
        } else {
            0
        }
        return FragmentedMp4.fragment(
            sequence++, FragmentedMp4.VIDEO_TRACK_ID, startTicks,
            listOf(FragmentSample(s.data, duration, s.isKey, offset)),
        )
    }

    private fun audio(s: EncodedSample): ByteArray? {
        val rate = audioSampleRate ?: return null
        if (!started) return null
        val outUs = s.ptsUs - baseUs
        if (outUs < 0) return null
        val ptsTicks = toTicks(outUs, rate)
        val tolerance = rate / 20L // 50 ms
        if (audioNextTicks < 0 || ptsTicks > audioNextTicks + tolerance) {
            audioNextTicks = ptsTicks // first frame or a real gap: jump to the right position
        } else if (ptsTicks < audioNextTicks - tolerance) {
            return null // audio is running ahead of its timestamps: drop a frame to realign
        }
        val t = audioNextTicks
        audioNextTicks += StreamHub.AAC_FRAME_SAMPLES
        return FragmentedMp4.fragment(
            sequence++, FragmentedMp4.AUDIO_TRACK_ID, t,
            listOf(FragmentSample(s.data, StreamHub.AAC_FRAME_SAMPLES, true)),
        )
    }

    private fun toTicks(us: Long, timescale: Int): Long = (us * timescale + 500_000) / 1_000_000
}

/**
 * Per-connection queue for a progressive MP4 client. If the network cannot keep
 * up and more than [maxLagUs] of media piles up, everything queued is dropped and
 * the stream resumes at the next key frame, which keeps latency bounded.
 */
class LiveClientQueue(
    private val hub: StreamHub,
    private val maxLagUs: Long = 1_200_000,
    /** Audio-only streams have no key frames: start (and resync) on any audio frame, ignore video. */
    private val audioOnly: Boolean = false,
) : StreamSubscriber {
    class Item(val sample: EncodedSample, val discontinuity: Boolean)

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val queue = ArrayDeque<Item>()
    private var waitingForKey = true
    private var nextIsDiscontinuity = false
    private var closed = false
    @Volatile var droppedResyncs = 0
        private set

    override fun onSample(sample: EncodedSample) {
        var requestKey = false
        lock.withLock {
            if (closed) return
            if (audioOnly && sample.trackId != FragmentedMp4.AUDIO_TRACK_ID) return
            if (waitingForKey) {
                if (!audioOnly && (sample.trackId != FragmentedMp4.VIDEO_TRACK_ID || !sample.isKey)) return
                waitingForKey = false
            }
            queue.addLast(Item(sample, nextIsDiscontinuity && sample.trackId == FragmentedMp4.VIDEO_TRACK_ID))
            if (sample.trackId == FragmentedMp4.VIDEO_TRACK_ID) nextIsDiscontinuity = false
            val first = queue.first().sample
            if (sample.ptsUs - first.ptsUs > maxLagUs || queue.size > 1000) {
                queue.clear()
                waitingForKey = true
                nextIsDiscontinuity = true
                droppedResyncs++
                requestKey = true
            }
            notEmpty.signal()
        }
        if (requestKey) {
            hub.requestKeyFrame()
            hub.congestionListener?.onClientDropped()
        }
    }

    /** Returns the next item, or null after [timeoutMs] or once closed. */
    fun take(timeoutMs: Long): Item? {
        val item: Item
        val lag: Long
        lock.withLock {
            var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (queue.isEmpty()) {
                if (closed || nanos <= 0) return null
                nanos = notEmpty.awaitNanos(nanos)
            }
            item = queue.removeFirst()
            lag = if (queue.isEmpty()) 0 else queue.last().sample.ptsUs - item.sample.ptsUs
        }
        hub.congestionListener?.onClientLag(lag)
        return item
    }

    val isClosed: Boolean get() = lock.withLock { closed }

    fun close() {
        lock.withLock {
            closed = true
            queue.clear()
            notEmpty.signalAll()
        }
    }
}
