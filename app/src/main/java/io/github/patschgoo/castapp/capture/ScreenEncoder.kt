package io.github.patschgoo.castapp.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.Surface
import io.github.patschgoo.castapp.stream.AvcUtils
import io.github.patschgoo.castapp.stream.StreamHub
import io.github.patschgoo.castapp.stream.VideoConfig

/**
 * Captures the screen through MediaProjection into a hardware H.264 encoder and
 * pushes the encoded frames into the [StreamHub].
 *
 * The capture surface is always 16:9 landscape (the shape of a TV). Android keeps
 * the aspect ratio when mirroring, so a portrait phone shows up centred with bars
 * and rotating the phone never has to restart the stream.
 */
class ScreenEncoder(
    private val projection: MediaProjection,
    private val hub: StreamHub,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val densityDpi: Int,
    private val onError: (Throwable) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var display: VirtualDisplay? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var lastKeyRequest = 0L

    var encodedWidth = width
        private set
    var encodedHeight = height
        private set

    fun start(handler: Handler) {
        val c = createConfiguredCodec()
        codec = c
        inputSurface = c.createInputSurface()
        c.start()
        running = true
        display = projection.createVirtualDisplay(
            "CastApp", encodedWidth, encodedHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, handler,
        )
        hub.keyFrameRequester = { requestKeyFrame() }
        drainThread = Thread({ drainLoop(c) }, "video-encoder").apply { start() }
    }

    private fun createConfiguredCodec(): MediaCodec {
        val sizes = linkedSetOf(width to height, 1280 to 720, 960 to 540)
        var lastError: Exception? = null
        for ((w, h) in sizes) {
            // 2 = all tuning keys, 1 = basic keys, 0 = bare minimum for picky encoders.
            for (level in 2 downTo 0) {
                val codec = try { MediaCodec.createEncoderByType(MIME) } catch (e: Exception) { lastError = e; continue }
                try {
                    val caps = codec.codecInfo.getCapabilitiesForType(MIME)
                    if (caps.videoCapabilities?.isSizeSupported(w, h) == false) {
                        codec.release()
                        break
                    }
                    codec.configure(buildFormat(w, h, caps, level), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encodedWidth = w
                    encodedHeight = h
                    return codec
                } catch (e: Exception) {
                    Log.w(TAG, "Encoder config ${w}x$h level=$level failed", e)
                    lastError = e
                    codec.release()
                }
            }
        }
        throw IllegalStateException("No usable H.264 encoder on this phone", lastError)
    }

    private fun buildFormat(w: Int, h: Int, caps: MediaCodecInfo.CodecCapabilities, level: Int): MediaFormat =
        MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (level >= 1) {
                // Keep frames flowing while the screen is static so the TV never runs dry.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / 10)
                // Phones with 90/120 Hz screens would otherwise feed the encoder far too many frames.
                if (Build.VERSION.SDK_INT >= 29) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            }
            if (level >= 2) {
                setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
                if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                if (caps.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                }
                val high = caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }
                if (high) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            }
        }

    fun requestKeyFrame() {
        val c = codec ?: return
        val now = System.currentTimeMillis()
        if (now - lastKeyRequest < 250) return
        lastKeyRequest = now
        try {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {
        }
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = c.dequeueOutputBuffer(info, 100_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(c.outputFormat)
                    index >= 0 -> {
                        val buffer = c.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.get(bytes)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                onCodecConfig(bytes)
                            } else {
                                onFrame(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                            }
                        }
                        c.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } catch (e: Exception) {
            if (running) onError(e)
        }
    }

    private fun onFormat(format: MediaFormat) {
        if (hub.videoConfig != null) return
        val sps = format.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } } ?: return
        val pps = format.getByteBuffer("csd-1")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } } ?: return
        onCodecConfig(sps + pps)
    }

    private fun onCodecConfig(bytes: ByteArray) {
        if (hub.videoConfig != null) return
        val nals = AvcUtils.splitAnnexB(bytes)
        val sps = nals.firstOrNull { AvcUtils.nalType(it) == AvcUtils.NAL_SPS } ?: return
        val pps = nals.firstOrNull { AvcUtils.nalType(it) == AvcUtils.NAL_PPS } ?: return
        hub.setVideoConfig(VideoConfig(encodedWidth, encodedHeight, sps, pps))
    }

    private fun onFrame(bytes: ByteArray, ptsUs: Long, keyFlag: Boolean) {
        val nals = AvcUtils.splitAnnexB(bytes)
        if (hub.videoConfig == null) {
            // Some encoders put SPS/PPS in front of the first key frame instead of a config buffer.
            onCodecConfig(bytes)
            if (hub.videoConfig == null) return
        }
        val sample = AvcUtils.toAvccSample(nals)
        if (sample.isEmpty()) return
        hub.pushVideo(sample, ptsUs, keyFlag || AvcUtils.containsIdr(nals))
    }

    fun stop() {
        running = false
        hub.keyFrameRequester = null
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { drainThread?.join(500) } catch (_: InterruptedException) {}
        drainThread = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
    }

    companion object {
        private const val TAG = "ScreenEncoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }
}
