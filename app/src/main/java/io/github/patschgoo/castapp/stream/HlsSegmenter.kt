package io.github.patschgoo.castapp.stream

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Cuts the live stream into ~1 second fMP4 (CMAF) segments and serves a sliding
 * window HLS playlist. Used as the "compatible" mode for receivers that do not
 * play the progressive MP4 stream.
 */
class HlsSegmenter(
    private val hub: StreamHub,
    private val targetDurationUs: Long = 1_000_000,
    private val playlistSize: Int = 6,
    private val keepSegments: Int = 12,
) : StreamSubscriber {

    class Segment(val index: Long, val durationUs: Long, val data: ByteArray)

    private val lock = Any()
    private var serializer: Mp4FragmentSerializer? = null
    private var current: ByteArrayOutputStream? = null
    private var currentStartPtsUs = 0L
    private var nextIndex = 0L
    private var keyRequested = false
    private val segments = ArrayDeque<Segment>()

    override fun onSample(sample: EncodedSample) {
        var requestKey = false
        synchronized(lock) {
            val isVideoKey = sample.trackId == FragmentedMp4.VIDEO_TRACK_ID && sample.isKey
            if (current == null) {
                // Start at a key frame, and only once the track layout (init segment) is fixed.
                if (!isVideoKey || hub.currentInitSegment() == null) return
                serializer = Mp4FragmentSerializer(hub.publishedAudioRate)
                startSegment(sample.ptsUs)
            } else if (sample.trackId == FragmentedMp4.VIDEO_TRACK_ID) {
                val elapsed = sample.ptsUs - currentStartPtsUs
                if (isVideoKey && elapsed >= targetDurationUs / 2) {
                    finishSegment(elapsed)
                    startSegment(sample.ptsUs)
                } else if (elapsed >= targetDurationUs * 9 / 10 && !keyRequested) {
                    keyRequested = true
                    requestKey = true
                }
            }
            val bytes = serializer?.serialize(sample) ?: return
            current?.write(bytes)
        }
        if (requestKey) hub.requestKeyFrame()
    }

    private fun startSegment(ptsUs: Long) {
        current = ByteArrayOutputStream(256 * 1024)
        currentStartPtsUs = ptsUs
        keyRequested = false
    }

    private fun finishSegment(durationUs: Long) {
        val data = current?.toByteArray() ?: return
        segments.addLast(Segment(nextIndex++, durationUs, data))
        while (segments.size > keepSegments) segments.removeFirst()
    }

    val completedSegments: Int get() = synchronized(lock) { segments.size }

    fun segment(index: Long): ByteArray? = synchronized(lock) { segments.firstOrNull { it.index == index }?.data }

    fun playlist(): String = synchronized(lock) {
        val window = segments.toList().takeLast(playlistSize)
        val target = maxOf(1L, (targetDurationUs + 999_999) / 1_000_000)
        buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:7\n")
            append("#EXT-X-TARGETDURATION:").append(target).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:").append(window.firstOrNull()?.index ?: 0).append('\n')
            append("#EXT-X-INDEPENDENT-SEGMENTS\n")
            append("#EXT-X-MAP:URI=\"init.mp4\"\n")
            for (s in window) {
                append("#EXTINF:").append(String.format(Locale.US, "%.3f", s.durationUs / 1_000_000.0)).append(",\n")
                append("seg").append(s.index).append(".m4s\n")
            }
        }
    }

    companion object {
        /**
         * A master playlist with one variant (live.m3u8) that names its codecs, so
         * players set up the right decoder (needed for HEVC: "hvc1...").
         */
        fun masterPlaylist(video: VideoConfig, withAudio: Boolean, bandwidth: Int): String = buildString {
            val codecs = video.codecString + if (withAudio) ",mp4a.40.2" else ""
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:7\n")
            append("#EXT-X-INDEPENDENT-SEGMENTS\n")
            append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidth + if (withAudio) 128_000 else 0)
            append(",CODECS=\"").append(codecs).append('"')
            append(",RESOLUTION=").append(video.width).append('x').append(video.height).append('\n')
            append("live.m3u8\n")
        }
    }
}
