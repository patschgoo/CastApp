package io.github.patschgoo.castapp.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import io.github.patschgoo.castapp.stream.AudioConfig
import io.github.patschgoo.castapp.stream.AvcUtils
import io.github.patschgoo.castapp.stream.FragmentedMp4
import io.github.patschgoo.castapp.stream.InterleavingQueue
import io.github.patschgoo.castapp.stream.StreamHub
import io.github.patschgoo.castapp.stream.VideoConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts a video the TV can't play (HEVC, MKV, odd audio, 4K on an older
 * Chromecast...) into H.264 + AAC on the phone, starting at [startUs]. Output goes
 * into [hub]; subscribe an [InterleavingQueue] before calling [start].
 *
 * Video: extractor → hardware decoder → OpenGL scale (max 1920×1080) → H.264 encoder.
 * Audio: extractor → decoder → 16-bit stereo PCM → AAC encoder.
 */
class Transcoder(
    private val context: Context,
    private val input: TranscodeInput,
    private val startUs: Long,
    private val queue: InterleavingQueue,
    /** Height limit of the output (1080 = Full HD; 720, 480 for weaker Wi-Fi). */
    private val maxHeight: Int = 1080,
    val hub: StreamHub = StreamHub(),
) {
    @Volatile private var running = false
    private val threads = ArrayList<Thread>()
    @Volatile var error: Throwable? = null
        private set

    /** Total length of the source in microseconds (0 if unknown). */
    @Volatile var durationUs = 0L
        private set

    fun start() {
        val probe = newExtractor()
        var videoTrack = -1
        var audioTrack = -1
        for (i in 0 until probe.trackCount) {
            val mime = probe.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i
            if (mime.startsWith("audio/") && audioTrack < 0) audioTrack = i
        }
        val videoFormat = if (videoTrack >= 0) probe.getTrackFormat(videoTrack) else null
        val audioFormat = if (audioTrack >= 0) probe.getTrackFormat(audioTrack) else null
        durationUs = maxOf(
            videoFormat?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION) ?: 0,
            audioFormat?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION) ?: 0,
        )
        probe.release()
        if (videoFormat == null) throw IllegalStateException("No video track")

        val audioPossible = audioFormat != null && hasDecoder(audioFormat)
        hub.expectAudio(audioPossible)
        if (!audioPossible) queue.endTrack(FragmentedMp4.AUDIO_TRACK_ID)
        running = true
        threads += Thread({ runVideo(videoTrack, videoFormat) }, "transcode-video").apply { start() }
        if (audioPossible) threads += Thread({ runAudio(audioTrack, audioFormat) }, "transcode-audio").apply { start() }
    }

    fun stop() {
        running = false
        queue.close()
        threads.forEach { it.interrupt() }
    }

    private fun newExtractor(): MediaExtractor = input.open(context)

    private fun hasDecoder(format: MediaFormat): Boolean = try {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        MediaCodec.createDecoderByType(mime).release()
        true
    } catch (_: Exception) {
        false
    }

    // ---- video ----------------------------------------------------------------------------

    private fun runVideo(track: Int, format: MediaFormat) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var scaler: GlScaler? = null
        var drain: Thread? = null
        try {
            extractor = newExtractor().apply {
                selectTrack(track)
                seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val srcW = format.getInteger(MediaFormat.KEY_WIDTH)
            val srcH = format.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val (dispW, dispH) = if (rotation % 180 != 0) srcH to srcW else srcW to srcH
            // Long side at most 1920, short side at most 1080 (portrait videos stay portrait).
            val maxShort = minOf(MAX_H, maxHeight)
            val maxLong = maxShort * 16 / 9
            val scale = minOf(1f, maxLong.toFloat() / maxOf(dispW, dispH).coerceAtLeast(1), maxShort.toFloat() / minOf(dispW, dispH).coerceAtLeast(1))
            val outW = ((dispW * scale).toInt() / 2) * 2
            val outH = ((dispH * scale).toInt() / 2) * 2
            val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(10, 60) else 30

            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, when { minOf(outW, outH) >= 1080 -> 8_000_000; minOf(outW, outH) >= 720 -> 5_000_000; else -> 2_500_000 })
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encSurface = enc.createInputSurface()
            enc.start()
            encoder = enc
            drain = Thread({ drainVideoEncoder(enc, outW, outH) }, "transcode-venc").apply { start() }

            val gl = GlScaler(encSurface, outW, outH, fill = false)
            val decSurface = gl.start(dispW, dispH)
            scaler = gl

            val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            dec.configure(format, decSurface, null, 0)
            dec.start()
            decoder = dec

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (running) {
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val render = info.size > 0 && info.presentationTimeUs >= startUs
                    val before = gl.framesDrawn
                    dec.releaseOutputBuffer(outIdx, render)
                    // Draw synchronously: one decoded frame in, one encoded frame out, nothing dropped.
                    if (render) gl.awaitFrameAfter(before, 2000)
                    if (eos) {
                        enc.signalEndOfInputStream()
                        break
                    }
                }
            }
            drain.join()
        } catch (e: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "Video conversion failed", e)
            error = e
        } finally {
            queue.endTrack(FragmentedMp4.VIDEO_TRACK_ID)
            running = false
            drain?.interrupt()
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            scaler?.release()
            extractor?.release()
        }
    }

    private fun drainVideoEncoder(enc: MediaCodec, width: Int, height: Int) {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, 50_000)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!running) return
                    continue
                }
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = enc.outputFormat
                    val sps = f.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                    val pps = f.getByteBuffer("csd-1")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                    if (sps != null && pps != null && hub.videoConfig == null) {
                        val s = AvcUtils.splitAnnexB(sps).firstOrNull() ?: sps
                        val p = AvcUtils.splitAnnexB(pps).firstOrNull() ?: pps
                        hub.setVideoConfig(VideoConfig(width, height, s, p))
                    }
                    continue
                }
                if (idx < 0) continue
                val buf = enc.getOutputBuffer(idx)
                if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.get(bytes)
                    val nals = AvcUtils.splitAnnexB(bytes)
                    val sample = AvcUtils.toAvccSample(nals)
                    if (sample.isNotEmpty()) {
                        hub.pushVideo(sample, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 || AvcUtils.containsIdr(nals))
                    }
                }
                enc.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        } catch (_: Exception) {
        }
    }

    // ---- audio ------------------------------------------------------------------------------

    private fun runAudio(track: Int, format: MediaFormat) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        try {
            extractor = newExtractor().apply {
                selectTrack(track)
                seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            dec.configure(format, null, null, 0)
            dec.start()
            decoder = dec

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var outRate = 0
            var enc: MediaCodec? = null
            val pending = ShortArrayBuilder()
            var firstPtsUs = -1L
            var framesQueued = 0L
            val info = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decodeDone = false

            fun ensureEncoder(): MediaCodec {
                enc?.let { return it }
                outRate = if (rate > 48_000) rate / ((rate + 47_999) / 48_000) else rate
                val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                e.configure(
                    MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, outRate, 2).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 4 * 2)
                    },
                    null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
                e.start()
                enc = e
                encoder = e
                return e
            }

            fun drainEncoder(e: MediaCodec) {
                while (true) {
                    val idx = e.dequeueOutputBuffer(encInfo, 0)
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        e.outputFormat.getByteBuffer("csd-0")?.let { b ->
                            if (hub.audioConfig == null) hub.setAudioConfig(AudioConfig(outRate, 2, ByteArray(b.remaining()).also { b.get(it) }, 160_000))
                        }
                        continue
                    }
                    if (idx < 0) return
                    val buf = e.getOutputBuffer(idx)
                    if (buf != null && encInfo.size > 0 && encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val bytes = ByteArray(encInfo.size)
                        buf.position(encInfo.offset)
                        buf.limit(encInfo.offset + encInfo.size)
                        buf.get(bytes)
                        hub.pushAudio(bytes, encInfo.presentationTimeUs)
                    }
                    e.releaseOutputBuffer(idx, false)
                }
            }

            fun feedEncoder(e: MediaCodec, flush: Boolean) {
                val chunk = 1024 * 2 // 1024 stereo frames
                while (pending.size >= chunk || (flush && pending.size > 0)) {
                    val inIdx = e.dequeueInputBuffer(10_000)
                    if (inIdx < 0) { drainEncoder(e); continue }
                    val n = minOf(chunk, pending.size)
                    val buf = e.getInputBuffer(inIdx)!!.order(ByteOrder.nativeOrder())
                    buf.clear()
                    buf.asShortBuffer().put(pending.data, 0, n)
                    val pts = firstPtsUs + framesQueued * 1_000_000L / outRate
                    e.queueInputBuffer(inIdx, 0, n * 2, pts, 0)
                    framesQueued += n / 2
                    pending.drop(n)
                    drainEncoder(e)
                }
            }

            while (running && !decodeDone) {
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val size = extractor.readSampleData(dec.getInputBuffer(inIdx)!!, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                } else if (outIdx >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decodeDone = true
                    val buf = dec.getOutputBuffer(outIdx)
                    if (buf != null && info.size > 0 && info.presentationTimeUs >= startUs) {
                        if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        appendStereo(buf.slice().order(ByteOrder.nativeOrder()), channels, floatPcm, rate, pending)
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    if (firstPtsUs >= 0) feedEncoder(ensureEncoder(), flush = false)
                }
            }
            enc?.let { e ->
                feedEncoder(e, flush = true)
                drainEncoder(e)
            }
        } catch (e: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "Audio conversion failed", e)
        } finally {
            queue.endTrack(FragmentedMp4.AUDIO_TRACK_ID)
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            extractor?.release()
        }
    }

    /** Converts decoded PCM (16-bit or float, any channel count, any rate) to 16-bit stereo ≤ 48 kHz. */
    private fun appendStereo(buf: ByteBuffer, channels: Int, isFloat: Boolean, rate: Int, out: ShortArrayBuilder) {
        val step = if (rate > 48_000) (rate + 47_999) / 48_000 else 1
        val frames = buf.remaining() / (channels * if (isFloat) 4 else 2)
        val sample = { frame: Int, ch: Int ->
            val i = frame * channels + ch
            if (isFloat) buf.getFloat(i * 4).coerceIn(-1f, 1f) else buf.getShort(i * 2) / 32768f
        }
        var f = 0
        while (f < frames) {
            val l: Float
            val r: Float
            when {
                channels == 1 -> { l = sample(f, 0); r = l }
                channels == 2 -> { l = sample(f, 0); r = sample(f, 1) }
                else -> {
                    // 5.1 / 7.1 (L R C LFE Ls Rs ...): fold centre and surrounds into stereo.
                    val c = sample(f, 2) * 0.707f
                    val ls = if (channels > 4) sample(f, 4) * 0.707f else 0f
                    val rs = if (channels > 5) sample(f, 5) * 0.707f else 0f
                    l = (sample(f, 0) + c + ls) / 1.8f
                    r = (sample(f, 1) + c + rs) / 1.8f
                }
            }
            out.add((l * 32767).toInt().coerceIn(-32768, 32767).toShort())
            out.add((r * 32767).toInt().coerceIn(-32768, 32767).toShort())
            f += step
        }
    }

    private class ShortArrayBuilder {
        var data = ShortArray(16_384)
        var size = 0
        fun add(v: Short) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun drop(n: Int) {
            System.arraycopy(data, n, data, 0, size - n)
            size -= n
        }
    }

    companion object {
        private const val TAG = "Transcoder"
        private const val MAX_W = 1920
        private const val MAX_H = 1080

        /** Length of a video in milliseconds, or 0 if it can't be read. */
        fun probeDurationMs(context: Context, input: TranscodeInput): Long = try {
            val ex = input.open(context)
            var d = 0L
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.containsKey(MediaFormat.KEY_DURATION)) d = maxOf(d, f.getLong(MediaFormat.KEY_DURATION))
            }
            ex.release()
            d / 1000
        } catch (_: Exception) {
            0
        }
    }
}

/** Where a video to convert (or to transcribe for subtitles) comes from. */
sealed class TranscodeInput {
    abstract fun open(context: Context): MediaExtractor

    /** A content:// file, or a web address with request headers. */
    class FromUri(val uri: Uri, val headers: Map<String, String> = emptyMap()) : TranscodeInput() {
        override fun open(context: Context) = MediaExtractor().apply {
            if (uri.scheme == "http" || uri.scheme == "https") setDataSource(uri.toString(), headers)
            else setDataSource(context, uri, headers)
        }
    }

    /** Anything readable from any position, e.g. a file on a network share. */
    class FromSource(val source: io.github.patschgoo.castapp.stream.MediaSource) : TranscodeInput() {
        override fun open(context: Context) = MediaExtractor().apply { setDataSource(SourceDataSource(source)) }
    }
}

/** Lets Android's MediaExtractor read from a [io.github.patschgoo.castapp.stream.MediaSource]. */
class SourceDataSource(private val source: io.github.patschgoo.castapp.stream.MediaSource) : android.media.MediaDataSource() {
    private var stream: java.io.InputStream? = null
    private var streamPos = -1L

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val length = source.length
        if (length in 0..position) return -1
        if (size == 0) return 0
        if (stream == null || streamPos != position) {
            try { stream?.close() } catch (_: Exception) {}
            stream = source.open(position)
            streamPos = position
        }
        val n = stream!!.read(buffer, offset, size)
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
}
