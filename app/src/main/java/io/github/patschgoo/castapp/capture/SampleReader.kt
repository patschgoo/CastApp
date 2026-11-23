package io.github.patschgoo.castapp.capture

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * What the converter reads a file's samples with: the calls of Android's MediaExtractor it uses, so
 * Media3's AVI reader can stand in where MediaExtractor can't open the file.
 */
interface SampleReader {
    val kind: ReaderKind
    val trackCount: Int

    fun trackFormat(index: Int): MediaFormat

    fun selectTrack(index: Int)

    /** To the key frame at or before [timeUs]. */
    fun seekTo(timeUs: Long)

    /**
     * Copies the current sample into [buffer] from [offset] (position and limit around it, as
     * MediaExtractor does); its size, or -1 at the end. Throws [IllegalArgumentException] when it
     * doesn't fit.
     */
    fun readSampleData(buffer: ByteBuffer, offset: Int): Int

    /** Time of the current sample in microseconds, -1 at the end. */
    val sampleTime: Long

    /** The current sample is a key frame. */
    val sampleIsSync: Boolean

    fun advance(): Boolean

    /**
     * The sample times are the pictures' places in decoding order (AVI), not when they are shown: with
     * B-frames a decoder gives pictures back in another order, with these times.
     */
    val decodeOrderTimes: Boolean

    fun release()
}

/** Android's MediaExtractor. */
class AndroidSampleReader(private val ex: MediaExtractor) : SampleReader {
    override val kind = ReaderKind.ANDROID
    override val trackCount: Int get() = ex.trackCount
    override fun trackFormat(index: Int): MediaFormat = ex.getTrackFormat(index)
    override fun selectTrack(index: Int) = ex.selectTrack(index)
    override fun seekTo(timeUs: Long) = ex.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
        if (Build.VERSION.SDK_INT >= 28) {
            val size = ex.sampleSize
            if (size > buffer.capacity() - offset) throw IllegalArgumentException("sample of $size bytes")
        }
        return ex.readSampleData(buffer, offset)
    }

    override val sampleTime: Long get() = ex.sampleTime
    override val sampleIsSync: Boolean get() = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
    override fun advance(): Boolean = ex.advance()
    override val decodeOrderTimes = false
    override fun release() = ex.release()
}

/** Media3's AVI reader ([Media3Samples]) with its formats as Android's MediaFormat. */
@OptIn(UnstableApi::class)
class Media3SampleReader(private val samples: Media3Samples) : SampleReader {
    override val kind = ReaderKind.MEDIA3_AVI
    override val trackCount: Int get() = samples.tracks.size
    private val formats = HashMap<Int, MediaFormat>()

    override fun trackFormat(index: Int): MediaFormat = formats.getOrPut(index) {
        val format = samples.tracks[index].format ?: throw IllegalStateException("track $index has no format")
        MediaFormatUtil.createMediaFormatFromFormat(format).apply {
            if (samples.durationUs > 0) setLong(MediaFormat.KEY_DURATION, samples.durationUs)
            // XviD / DivX in AVI: the set-up (VOL header) is only in the very first frame, which a
            // conversion starting later never reads: the decoder gets it from the start.
            if (format.sampleMimeType == MimeTypes.VIDEO_MP4V && format.initializationData.isEmpty()) {
                samples.firstVideoSample?.let { Mpeg4Video.config(it) }?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            }
        }
    }

    override fun selectTrack(index: Int) = samples.select(index)
    override fun seekTo(timeUs: Long) = samples.seekTo(timeUs)

    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
        val s = samples.current() ?: return -1
        if (s.data.size > buffer.capacity() - offset) throw IllegalArgumentException("sample of ${s.data.size} bytes")
        buffer.clear()
        buffer.position(offset)
        buffer.put(s.data)
        buffer.limit(offset + s.data.size)
        buffer.position(offset)
        return s.data.size
    }

    override val sampleTime: Long get() = samples.current()?.timeUs ?: -1
    override val sampleIsSync: Boolean get() = samples.current()?.isKey == true
    override fun advance(): Boolean = samples.advance()
    override val decodeOrderTimes = true
    override fun release() = samples.close()
}
