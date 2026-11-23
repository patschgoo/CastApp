package io.github.patschgoo.castapp.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import io.github.patschgoo.castapp.network.HttpMediaSource
import io.github.patschgoo.castapp.service.ContentMediaSource
import io.github.patschgoo.castapp.stream.AudioConfig
import io.github.patschgoo.castapp.stream.AvcUtils
import io.github.patschgoo.castapp.stream.FragmentedMp4
import io.github.patschgoo.castapp.stream.InterleavingQueue
import io.github.patschgoo.castapp.stream.MediaSource
import io.github.patschgoo.castapp.stream.StreamHub
import io.github.patschgoo.castapp.stream.VideoConfig
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts a video the TV can't play (HEVC, MKV, odd audio, 4K on an older
 * Chromecast...) into H.264 + AAC on the phone, starting at [startUs]. Output goes
 * into [hub]; subscribe an [InterleavingQueue] before calling [start].
 *
 * Video: extractor → hardware decoder → OpenGL scale (max 1920×1080) → H.264 encoder.
 * Audio: extractor → decoder → 16-bit stereo PCM → AAC encoder.
 *
 * An H.264 picture the TV plays as it is (TV recordings, most MKV files: [Remux.videoRoute]) is copied
 * instead, sample by sample, and AAC-LC stereo sound with it; only other sound is converted then. If the
 * copy can't start (no key frame, no SPS), the picture is converted after all.
 *
 * Files Android's MediaExtractor can't open are read by Media3's AVI reader where they're AVI
 * ([TranscodeInput.openReader]).
 */
class Transcoder(
    private val context: Context,
    private val input: TranscodeInput,
    private val startUs: Long,
    private val queue: InterleavingQueue,
    /** Height limit of the output (1080 = Full HD; 720, 480 for weaker Wi-Fi). */
    private val maxHeight: Int = 1080,
    val hub: StreamHub = StreamHub(),
    /** Which sound track to use: its place among the file's sound tracks (0 = the first). */
    private val audioTrackOrdinal: Int = 0,
    /** The picture may be copied where it can be ([Remux]); false after a copy didn't play on this TV. */
    private val allowCopy: Boolean = true,
) {
    @Volatile private var running = false
    private val threads = ArrayList<Thread>()
    @Volatile var error: Throwable? = null
        private set

    /** Total length of the source in microseconds (0 if unknown). */
    @Volatile var durationUs = 0L
        private set

    /** The sound track has no decoder on this phone, so the converted video has no sound: its codec MIME. */
    @Volatile var droppedAudioMime: String? = null
        private set

    /** The picture is copied as it is, not converted (false again if the copy couldn't start). */
    @Volatile var copying = false
        private set

    /** The decoder [start] found for the picture track. */
    @Volatile private var videoDecoderName: String? = null

    /**
     * Checks the file and starts converting. Throws [ConvertException] when the phone can't convert
     * it (unknown container, no picture decoder), so callers can answer the TV with an error at once
     * instead of an empty stream.
     */
    fun start() {
        val probe = try {
            newReader()
        } catch (e: Exception) {
            Log.w(TAG, "Can't open the file for converting", e)
            throw ConvertException(if (input.readable(context)) ConvertProblem.UnreadableFileType(null) else ConvertProblem.ReadFailed)
                .apply { initCause(e) }
        }
        val decodeOrder = probe.decodeOrderTimes
        val formats = try {
            (0 until probe.trackCount).map { probe.trackFormat(it) }
        } finally {
            probe.release()
        }
        val check = check(formats, audioTrackOrdinal, declaredVideo = { input.declaredVideo(context) }) { videoDecoderName = it }
        val (videoTrack, audioTrack) = ConvertChecks.pickTracks(formats.map { ConvertChecks.TrackFacts(it.getString(MediaFormat.KEY_MIME)) }, audioTrackOrdinal)
        val videoFormat = formats.getOrNull(videoTrack)
        val audioFormat = formats.getOrNull(audioTrack)
        durationUs = maxOf(
            videoFormat?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION) ?: 0,
            audioFormat?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION) ?: 0,
        )
        check.problem?.let { throw ConvertException(it) }
        if (videoFormat == null) throw ConvertException(ConvertProblem.NoVideoTrack)

        val route = Remux.videoRoute(videoFacts(videoFormat, decodeOrder), maxHeight, allowCopy)
        copying = route == Remux.VideoRoute.Copy
        Log.i(TAG, "Picture: " + if (copying) "copied as it is" else "converted (${(route as Remux.VideoRoute.Convert).why})")
        // The sound is copied too where it's AAC the TV plays, but only next to a copied picture.
        val copyAudio = copying && audioFormat != null && Remux.canCopyAudio(
            audioFormat.getString(MediaFormat.KEY_MIME), audioFormat.bytes("csd-0"),
            audioFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 0, audioFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0,
        )

        droppedAudioMime = if (copyAudio) null else check.droppedAudioMime
        if (droppedAudioMime != null) Log.w(TAG, "No decoder for the sound ($droppedAudioMime): converting without sound")
        val audioPossible = audioFormat != null && droppedAudioMime == null
        hub.expectAudio(audioPossible)
        if (!audioPossible) queue.endTrack(FragmentedMp4.AUDIO_TRACK_ID)
        running = true
        threads += Thread({
            // A copy that can't start (no key frame, no SPS) leaves nothing behind: convert instead.
            if (!copying || !runVideoCopy(videoTrack, videoFormat)) {
                copying = false
                if (running) runVideo(videoTrack, videoFormat, decodeOrder) else queue.endTrack(FragmentedMp4.VIDEO_TRACK_ID)
            }
        }, "transcode-video").apply { start() }
        if (audioFormat != null && audioPossible) {
            threads += Thread({ if (copyAudio) runAudioCopy(audioTrack, audioFormat) else runAudio(audioTrack, audioFormat) }, "transcode-audio").apply { start() }
        }
    }

    fun stop() {
        running = false
        queue.close()
        threads.forEach { it.interrupt() }
    }

    private fun newReader(): SampleReader = input.openReader(context)

    // ---- video ----------------------------------------------------------------------------

    private fun runVideo(track: Int, format: MediaFormat, decodeOrder: Boolean) {
        var extractor: SampleReader? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var scaler: GlScaler? = null
        var drain: Thread? = null
        try {
            extractor = newReader().apply {
                selectTrack(track)
                seekTo(startUs)
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
            val fps = frameRate(format)?.coerceIn(10, 60) ?: 30

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
            // HLS output cuts segments at key frames and asks for one when a segment gets long.
            hub.keyFrameRequester = {
                if (running) {
                    try {
                        enc.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    } catch (_: Exception) {
                    }
                }
            }
            drain = Thread({ drainVideoEncoder(enc, outW, outH) }, "transcode-venc").apply { start() }

            val gl = GlScaler(encSurface, outW, outH, fill = false)
            val decSurface = gl.start(dispW, dispH)
            scaler = gl

            // The decoder that said it takes this picture (size, 10-bit), not just any one for the codec.
            val dec = videoDecoderName?.let { name -> try { MediaCodec.createByCodecName(name) } catch (_: Exception) { null } }
                ?: MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            dec.configure(format, decSurface, null, 0)
            dec.start()
            decoder = dec
            // AVI: the samples' times are their decoding order; pictures come back in display order.
            val shownAt = if (decodeOrder) DisplayOrderTimes() else null
            // XviD in AVI: the open GOP's B-pictures after the first key frame can't be decoded.
            val leading = if (decodeOrder && format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_MPEG4) Mpeg4Video.LeadingPictures() else null

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (running) {
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        var size = extractor.readSampleData(buf, 0)
                        while (size > 0 && leading != null && leading.skip(head(buf, size), extractor.sampleIsSync)) {
                            extractor.advance()
                            size = extractor.readSampleData(buf, 0)
                        }
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val time = extractor.sampleTime
                            if (size > 0) shownAt?.queued(time)
                            dec.queueInputBuffer(inIdx, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val timeUs = if (shownAt != null && info.size > 0) shownAt.shown(info.presentationTimeUs) else info.presentationTimeUs
                    val render = info.size > 0 && timeUs >= startUs
                    val before = gl.framesDrawn
                    if (render && shownAt != null) dec.releaseOutputBuffer(outIdx, timeUs * 1000) else dec.releaseOutputBuffer(outIdx, render)
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
            hub.keyFrameRequester = null
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

    /**
     * Copies the H.264 picture sample by sample (no decoder, no encoder): [PictureCopier]. Starts at the
     * key frame before [startUs] (the session moves the start there: [keyFrameBefore]). Returns false
     * when it couldn't start (no key frame or SPS / PPS soon enough, an SPS that isn't copied after all,
     * a reading error before anything went out): nothing was sent then, and the caller converts.
     */
    private fun runVideoCopy(track: Int, format: MediaFormat): Boolean {
        var reader: SampleReader? = null
        val copier = PictureCopier(
            format.bytes("csd-0"), format.bytes("csd-1"), maxHeight, COPY_LOOKAHEAD,
            onStart = { facts, sps, pps -> hub.setVideoConfig(VideoConfig(facts.width, facts.height, sps, pps)) },
            onSample = { data, dtsUs, key, offsetUs -> hub.pushVideo(data, dtsUs, key, offsetUs) },
        )
        try {
            reader = newReader().apply {
                selectTrack(track)
                seekTo(startUs)
            }
            var buffer = ByteBuffer.allocateDirect(inputSize(format))
            while (running) {
                val size = try {
                    reader.readSampleData(buffer, 0)
                } catch (e: IllegalArgumentException) {
                    if (buffer.capacity() >= MAX_SAMPLE_BYTES) throw e
                    buffer = ByteBuffer.allocateDirect(buffer.capacity() * 2)
                    continue
                }
                if (size < 0) break
                val ptsUs = reader.sampleTime
                val sync = reader.sampleIsSync
                val bytes = ByteArray(size)
                buffer.position(0)
                buffer.limit(size)
                buffer.get(bytes)
                reader.advance()
                if (!copier.add(bytes, ptsUs, sync)) {
                    Log.i(TAG, "The picture can't be copied (${copier.refused}): converting it")
                    return false
                }
            }
            // The file ended before a key frame came: the converter knows how to say so.
            if (!copier.started) return !running
            if (running) copier.finish()
            return true
        } catch (e: InterruptedException) {
            return true
        } catch (e: InterruptedIOException) {
            return true
        } catch (e: Exception) {
            if (!copier.started && running) {
                Log.w(TAG, "Copying the picture couldn't start: converting it", e)
                return false
            }
            Log.w(TAG, "Copying the picture failed", e)
            error = e
            return true
        } finally {
            reader?.release()
            if (copier.started || !running) {
                queue.endTrack(FragmentedMp4.VIDEO_TRACK_ID)
                running = false
            }
        }
    }

    // ---- audio ------------------------------------------------------------------------------

    /** Copies AAC sound frame by frame (next to a copied picture). */
    private fun runAudioCopy(track: Int, format: MediaFormat) {
        var reader: SampleReader? = null
        try {
            reader = newReader().apply {
                selectTrack(track)
                seekTo(startUs)
            }
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val asc = format.bytes("csd-0") ?: throw IOException("AAC without its configuration")
            hub.setAudioConfig(AudioConfig(rate, channels, asc, format.intOrNull(MediaFormat.KEY_BIT_RATE)?.takeIf { it > 0 } ?: 128_000))
            var buffer = ByteBuffer.allocate(16 * 1024)
            while (running) {
                val size = try {
                    reader.readSampleData(buffer, 0)
                } catch (e: IllegalArgumentException) {
                    if (buffer.capacity() >= MAX_SAMPLE_BYTES) throw e
                    buffer = ByteBuffer.allocate(buffer.capacity() * 2)
                    continue
                }
                if (size < 0) break
                val ptsUs = reader.sampleTime
                if (size > 0) {
                    val bytes = ByteArray(size)
                    buffer.position(0)
                    buffer.limit(size)
                    buffer.get(bytes)
                    hub.pushAudio(bytes, ptsUs)
                }
                reader.advance()
            }
        } catch (e: InterruptedException) {
        } catch (e: InterruptedIOException) {
        } catch (e: Exception) {
            Log.w(TAG, "Copying the sound failed", e)
        } finally {
            queue.endTrack(FragmentedMp4.AUDIO_TRACK_ID)
            reader?.release()
        }
    }

    private fun runAudio(track: Int, format: MediaFormat) {
        var extractor: SampleReader? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        try {
            extractor = newReader().apply {
                selectTrack(track)
                seekTo(startUs)
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
        private const val MAX_H = 1080

        /** Frames looked at for a key frame (and SPS / PPS) before the copy gives up and converts. */
        private const val COPY_LOOKAHEAD = 300
        private const val MAX_SAMPLE_BYTES = 32 * 1024 * 1024

        /**
         * Whether the phone can convert [input] (with sound track [audioOrdinal]), and its length.
         * Reads only the file's header; nothing is decoded. [fileType] ("AVI") names the type in the reason.
         */
        fun probe(context: Context, input: TranscodeInput, audioOrdinal: Int = 0, fileType: String? = null): ConvertCheck {
            val reader = try {
                // A second try covers a share or server connection that had gone stale.
                ConvertChecks.openTwice { input.openReader(context) }
            } catch (e: Exception) {
                Log.i(TAG, "Can't open the file for converting: ${e.message}")
                return ConvertCheck(ConvertChecks.openFailed(fileType) { input.readable(context) })
            }
            return try {
                val formats = (0 until reader.trackCount).map { reader.trackFormat(it) }
                val check = check(formats, audioOrdinal, declaredVideo = { input.declaredVideo(context) })
                val video = ConvertChecks.pickTracks(formats.map { ConvertChecks.TrackFacts(it.getString(MediaFormat.KEY_MIME)) }, audioOrdinal).first
                check.copy(video = formats.getOrNull(video)?.let { videoFacts(it, reader.decodeOrderTimes) })
            } catch (e: Exception) {
                Log.i(TAG, "Can't read the file's tracks: ${e.message}")
                ConvertCheck(ConvertProblem.ReadFailed)
            } finally {
                reader.release()
            }
        }

        /**
         * Where a copied picture starting at or after [timeUs] has to start: its key frame at or before it
         * (null if it can't be told). A conversion that copies the picture can only start there, so the
         * TV's clock and the subtitles are set to it.
         */
        fun keyFrameBefore(context: Context, input: TranscodeInput, timeUs: Long): Long? {
            val reader = input.openReader(context)
            return try {
                val formats = (0 until reader.trackCount).map { reader.trackFormat(it) }
                val video = ConvertChecks.pickTracks(formats.map { ConvertChecks.TrackFacts(it.getString(MediaFormat.KEY_MIME)) }, 0).first
                if (video < 0) return null
                reader.selectTrack(video)
                reader.seekTo(timeUs)
                reader.sampleTime.takeIf { it in 0..timeUs }
            } finally {
                reader.release()
            }
        }

        /** What [Remux.videoRoute] needs to know of a picture track. */
        private fun videoFacts(format: MediaFormat, decodeOrder: Boolean): Remux.VideoFacts {
            val mime = format.getString(MediaFormat.KEY_MIME)
            val sps = if (mime.equals("video/avc", ignoreCase = true)) format.bytes("csd-0")?.let { Remux.parseSps(it) } else null
            return Remux.VideoFacts(mime, sps, format.intOrNull(MediaFormat.KEY_ROTATION) ?: 0, decodeOrder)
        }

        /** [ConvertChecks.decide] for these track formats; [onDecoder] gets the picture decoder found. */
        private fun check(
            formats: List<MediaFormat>,
            audioOrdinal: Int,
            declaredVideo: () -> AviCodecs.Codec?,
            onDecoder: (String) -> Unit = {},
        ): ConvertCheck {
            val facts = formats.map { f ->
                ConvertChecks.TrackFacts(
                    f.getString(MediaFormat.KEY_MIME),
                    if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0,
                )
            }
            return ConvertChecks.decide(
                facts, audioOrdinal,
                // Microsoft's MPEG-4 (DivX 3) has no decoder on Android: not even asked.
                videoDecoder = { i -> AviCodecs.msMpeg4Name(facts[i].mime) == null && findVideoDecoder(formats[i])?.also(onDecoder) != null },
                audioDecoder = { i -> hasDecoder(formats[i]) },
                videoCodec = { i ->
                    AviCodecs.msMpeg4Name(facts[i].mime) ?: ConvertChecks.videoCodecName(facts[i].mime, formats[i].intOrNull(MediaFormat.KEY_PROFILE))
                },
                droppedVideo = declaredVideo,
            )
        }

        /** The same check the sound converter's start amounts to: any decoder for the codec. */
        private fun hasDecoder(format: MediaFormat): Boolean = try {
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            MediaCodec.createDecoderByType(mime).release()
            true
        } catch (_: Exception) {
            false
        }

        /**
         * A decoder of this phone for this picture: its codec, and its profile where that means 10-bit or
         * finer colour (Hi10P anime, HEVC Main 10), which a phone's decoder often lacks. One that lists the
         * picture's size is preferred, but the size doesn't rule a decoder out: some claim far less than
         * they decode (Google's MPEG-4 decoder says 352×288 and plays XviD at 640×480). Levels and frame
         * rates are left out for the same reason.
         */
        private fun findVideoDecoder(format: MediaFormat): String? {
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val width = format.intOrNull(MediaFormat.KEY_WIDTH)
            val height = format.intOrNull(MediaFormat.KEY_HEIGHT)
            val profile = format.intOrNull(MediaFormat.KEY_PROFILE)?.let { ConvertChecks.profileToCheck(mime, it) }
            fun query(w: Int?, h: Int?) = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, mime)
                if (w != null && h != null) {
                    setInteger(MediaFormat.KEY_WIDTH, w)
                    setInteger(MediaFormat.KEY_HEIGHT, h)
                }
                if (profile != null) setInteger(MediaFormat.KEY_PROFILE, profile)
            }
            return try {
                val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                val sized = if (width != null && height != null) {
                    // Portrait videos: some decoders only list landscape sizes.
                    codecs.findDecoderForFormat(query(width, height)) ?: codecs.findDecoderForFormat(query(height, width))
                } else {
                    null
                }
                sized ?: codecs.findDecoderForFormat(query(null, null))?.also {
                    Log.i(TAG, "$it claims less than ${width}x$height for $mime: trying it anyway")
                }
            } catch (e: Exception) {
                Log.i(TAG, "Looking for a $mime decoder failed: ${e.message}")
                null
            }
        }

        /** Room for one picture sample: what the file says, at least 2 MB (an H.264 key frame of a TV recording fits). */
        private fun inputSize(format: MediaFormat): Int =
            (format.intOrNull(MediaFormat.KEY_MAX_INPUT_SIZE) ?: 0).coerceIn(2 * 1024 * 1024, MAX_SAMPLE_BYTES)

        /** The first bytes of the sample [readSampleData][SampleReader.readSampleData] put in [buf] (its headers). */
        private fun head(buf: ByteBuffer, size: Int): ByteArray {
            val d = buf.duplicate()
            d.position(0)
            return ByteArray(minOf(size, 64)).also { d.get(it) }
        }

        /** Frames per second: an integer from MediaExtractor, a float from Media3's formats. */
        private fun frameRate(format: MediaFormat): Int? = try {
            if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                null
            } else {
                try {
                    format.getInteger(MediaFormat.KEY_FRAME_RATE)
                } catch (_: ClassCastException) {
                    Math.round(format.getFloat(MediaFormat.KEY_FRAME_RATE))
                }
            }
        } catch (_: Exception) {
            null
        }

        private fun MediaFormat.intOrNull(key: String): Int? = try {
            if (containsKey(key)) getInteger(key) else null
        } catch (_: Exception) {
            null
        }

        private fun MediaFormat.bytes(key: String): ByteArray? = try {
            getByteBuffer(key)?.let { b ->
                val d = b.duplicate()
                d.rewind()
                ByteArray(d.remaining()).also { d.get(it) }
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Where a video to convert (or to transcribe for subtitles) comes from. */
sealed class TranscodeInput {
    /** Android's MediaExtractor on this input (the subtitle writer reads the sound with it). */
    abstract fun open(context: Context): MediaExtractor

    /** Whether the file's first bytes can be read at all: tells "unknown file type" from "file not reachable". */
    abstract fun readable(context: Context): Boolean

    /** The file's bytes from any position (for Media3's readers), null if there's no way to get them. */
    abstract fun mediaSource(context: Context): MediaSource?

    /** What opened this input last time: an AVI MediaExtractor couldn't open goes straight to Media3 afterwards. */
    @Volatile private var known: ReaderKind? = null

    /**
     * A reader for the converter: Android's MediaExtractor wherever it opens the file (what works today
     * stays as it is), Media3's AVI reader for AVI and .divx files it can't open ([ReaderChoice]).
     */
    fun openReader(context: Context): SampleReader {
        if (known != ReaderKind.MEDIA3_AVI) {
            try {
                val ex = open(context)
                known = ReaderKind.ANDROID
                return AndroidSampleReader(ex)
            } catch (e: Exception) {
                if (e is InterruptedException || e is InterruptedIOException || Thread.currentThread().isInterrupted) throw e
                val source = try { mediaSource(context) } catch (_: Exception) { null } ?: throw e
                if (ReaderChoice.choose(extractorOpened = false, head = ReaderChoice.head(source)) != ReaderKind.MEDIA3_AVI) throw e
                Log.i("TranscodeInput", "MediaExtractor can't open this AVI: reading it with Media3")
                known = ReaderKind.MEDIA3_AVI
                return Media3SampleReader(Media3Samples.avi(source))
            }
        }
        val source = mediaSource(context) ?: throw IOException("No way to read the file")
        return Media3SampleReader(Media3Samples.avi(source))
    }

    /**
     * The picture codec an AVI file declares (asked when no reader hands out a picture track: a DivX 3
     * stream is dropped by Media3's AVI reader), null for other files.
     */
    fun declaredVideo(context: Context): AviCodecs.Codec? = try {
        val source = mediaSource(context)
        val head = source?.let { ReaderChoice.head(it) }
        if (source != null && head != null && AviHeader.isAvi(head)) AviHeader.videoCodec(AviHeader.read(source))?.let { AviCodecs.video(it) } else null
    } catch (e: Exception) {
        if (e is InterruptedException || e is InterruptedIOException) throw e
        null
    }

    /** A new extractor on this input, released again if the file can't be opened. */
    protected fun extractor(setSource: MediaExtractor.() -> Unit): MediaExtractor {
        val ex = MediaExtractor()
        try {
            ex.setSource()
        } catch (e: Exception) {
            ex.release()
            throw e
        }
        return ex
    }

    /** A content:// file, or a web address with request headers. */
    class FromUri(val uri: Uri, val headers: Map<String, String> = emptyMap()) : TranscodeInput() {
        private val web: Boolean get() = uri.scheme == "http" || uri.scheme == "https"

        override fun open(context: Context) = extractor {
            if (web) setDataSource(uri.toString(), headers) else setDataSource(context, uri, headers)
        }

        override fun mediaSource(context: Context): MediaSource =
            if (web) HttpMediaSource(uri.toString(), headers) else ContentMediaSource(context.contentResolver, uri)

        override fun readable(context: Context): Boolean = try {
            if (web) {
                // A real request with the item's log-in headers, for its first byte only.
                val conn = HttpMediaSource.connect(uri.toString(), headers, trustAll = false, range = "bytes=0-0")
                try {
                    conn.responseCode in 200..299 && conn.inputStream.use { it.read() >= 0 }
                } finally {
                    conn.disconnect()
                }
            } else {
                context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Anything readable from any position, e.g. a file on a network share. */
    class FromSource(val source: MediaSource) : TranscodeInput() {
        override fun open(context: Context) = extractor { setDataSource(SourceDataSource(source)) }

        override fun mediaSource(context: Context): MediaSource = source

        override fun readable(context: Context): Boolean = try {
            source.open(0).use { it.read() >= 0 }
        } catch (_: Exception) {
            false
        }
    }
}

/** Lets Android's MediaExtractor read from a [io.github.patschgoo.castapp.stream.MediaSource]. */
class SourceDataSource(private val source: MediaSource) : android.media.MediaDataSource() {
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
