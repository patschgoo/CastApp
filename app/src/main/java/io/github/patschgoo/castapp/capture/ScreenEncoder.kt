package io.github.patschgoo.castapp.capture

import android.content.Context
import android.graphics.Point
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
import io.github.patschgoo.castapp.stream.HevcUtils
import io.github.patschgoo.castapp.stream.StreamHub
import io.github.patschgoo.castapp.stream.VideoCodec
import io.github.patschgoo.castapp.stream.VideoConfig

/**
 * Captures the screen through MediaProjection into a hardware H.264 or HEVC
 * encoder and pushes the encoded frames into the [StreamHub].
 *
 * The stream is always 16:9 landscape (the shape of a TV). In "fit" mode Android
 * keeps the aspect ratio when mirroring, so a portrait phone shows up centred with
 * bars. In "fill" mode the screen is captured at its own shape and an OpenGL pass
 * zooms it to cover the whole TV picture. Rotating the phone never restarts the stream.
 */
class ScreenEncoder(
    private val context: Context,
    private var projection: MediaProjection,
    private val hub: StreamHub,
    private var width: Int,
    private var height: Int,
    private val fps: Int,
    private var bitrate: Int,
    private val densityDpi: Int,
    private val fill: Boolean,
    /** Wanted codec; falls back to H.264 when the phone has no usable HEVC encoder. */
    codec: VideoCodec = VideoCodec.H264,
    private val onError: (Throwable) -> Unit,
) {
    /** The codec actually in use (see [reconfigure]). */
    @Volatile var videoCodec: VideoCodec = codec
        private set

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var display: VirtualDisplay? = null
    /** What the virtual display draws into: the encoder's surface, or the scaler's in fill mode. */
    private var target: Surface? = null
    private var scaler: GlScaler? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var captureSize = Point(0, 0)
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
        val encoderSurface = c.createInputSurface()
        inputSurface = encoderSurface
        c.start()
        running = true

        val target: Surface
        if (fill) {
            // Capture at the phone's own shape, then zoom to cover the 16:9 picture.
            captureSize = phoneCaptureSize()
            val gl = GlScaler(encoderSurface, encodedWidth, encodedHeight, fill = true)
            target = gl.start(captureSize.x, captureSize.y)
            scaler = gl
        } else {
            captureSize = Point(encodedWidth, encodedHeight)
            target = encoderSurface
        }
        this.target = target
        display = createDisplay(projection, target, handler)
        if (fill) watchRotation(handler)
        hub.keyFrameRequester = { requestKeyFrame() }
        drainThread = Thread({ drainLoop(c) }, "video-encoder").apply { start() }
    }

    private fun createDisplay(p: MediaProjection, surface: Surface, handler: Handler): VirtualDisplay? = p.createVirtualDisplay(
        "CastApp", captureSize.x, captureSize.y, densityDpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        surface, null, handler,
    )

    /**
     * Captures from [newProjection] from now on (the user switched between the whole screen and one app,
     * which needs Android's permission again). The encoder and the stream go on, so the TV keeps playing
     * and only sees the picture change. Call on [handler]'s thread.
     */
    fun switchProjection(newProjection: MediaProjection, handler: Handler) {
        val surface = target ?: return
        // A surface takes one producer at a time: the old display lets go first.
        try { display?.surface = null } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        display = null
        projection = newProjection
        try {
            display = createDisplay(newProjection, surface, handler) ?: throw IllegalStateException("No virtual display")
        } catch (e: Exception) {
            onError(e)
            return
        }
        requestKeyFrame()
    }

    /** The phone's current screen shape, scaled so the long side is at most 1920 pixels. */
    private fun phoneCaptureSize(): Point {
        val dm = context.getSystemService(DisplayManager::class.java)
        val real = Point()
        @Suppress("DEPRECATION")
        dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.getRealSize(real)
        if (real.x <= 0 || real.y <= 0) return Point(encodedWidth, encodedHeight)
        val scale = minOf(1f, 1920f / maxOf(real.x, real.y))
        return Point(((real.x * scale).toInt() / 2) * 2, ((real.y * scale).toInt() / 2) * 2)
    }

    /** In fill mode the capture must follow the phone's orientation. */
    private fun watchRotation(handler: Handler) {
        val dm = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != android.view.Display.DEFAULT_DISPLAY || !running) return
                val size = phoneCaptureSize()
                if (size == captureSize) return
                captureSize = size
                scaler?.setInputSize(size.x, size.y)
                try { display?.resize(size.x, size.y, densityDpi) } catch (_: Exception) {}
                requestKeyFrame()
            }
        }
        dm.registerDisplayListener(listener, handler)
        displayListener = listener
    }

    /**
     * Switches to a new picture size while casting. The capture (and the user's
     * permission) stays; only the encoder is rebuilt. [beforeRestart] runs once the
     * old encoder has stopped, so the stream can be reset in between.
     */
    fun reconfigure(newWidth: Int, newHeight: Int, newBitrate: Int, newCodec: VideoCodec = videoCodec, beforeRestart: () -> Unit) {
        running = false
        try { drainThread?.join(1000) } catch (_: InterruptedException) {}
        drainThread = null
        try { display?.surface = null } catch (_: Exception) {}
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        scaler?.release()
        scaler = null
        try { inputSurface?.release() } catch (_: Exception) {}
        beforeRestart()

        width = newWidth
        height = newHeight
        bitrate = newBitrate
        videoCodec = newCodec
        val c = createConfiguredCodec()
        codec = c
        val encoderSurface = c.createInputSurface()
        inputSurface = encoderSurface
        c.start()
        running = true
        if (fill) {
            val gl = GlScaler(encoderSurface, encodedWidth, encodedHeight, fill = true)
            val input = gl.start(captureSize.x, captureSize.y)
            target = input
            display?.surface = input
            scaler = gl
        } else {
            captureSize = Point(encodedWidth, encodedHeight)
            try { display?.resize(encodedWidth, encodedHeight, densityDpi) } catch (_: Exception) {}
            target = encoderSurface
            display?.surface = encoderSurface
        }
        drainThread = Thread({ drainLoop(c) }, "video-encoder").apply { start() }
        requestKeyFrame()
    }

    /** Changes the bitrate on the fly (automatic quality). */
    fun setBitrate(bps: Int) {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) })
        } catch (_: Exception) {
        }
    }

    private fun createConfiguredCodec(): MediaCodec {
        if (videoCodec == VideoCodec.HEVC) {
            try {
                return createConfiguredCodec(VideoCodec.HEVC)
            } catch (e: Exception) {
                Log.w(TAG, "No usable HEVC encoder, using H.264", e)
                videoCodec = VideoCodec.H264
            }
        }
        return createConfiguredCodec(VideoCodec.H264)
    }

    private fun createConfiguredCodec(type: VideoCodec): MediaCodec {
        val mime = type.mime
        val sizes = linkedSetOf(width to height, 1280 to 720, 960 to 540)
        var lastError: Exception? = null
        for ((w, h) in sizes) {
            // HEVC: only a hardware encoder will do (the software one is far too slow).
            val hevcName = if (type == VideoCodec.HEVC) VideoCodecs.hardwareEncoder(mime, w, h) ?: continue else null
            // 2 = all tuning keys, 1 = basic keys, 0 = bare minimum for picky encoders.
            for (level in 2 downTo 0) {
                val codec = try {
                    if (hevcName != null) MediaCodec.createByCodecName(hevcName) else MediaCodec.createEncoderByType(mime)
                } catch (e: Exception) {
                    lastError = e
                    continue
                }
                try {
                    val caps = codec.codecInfo.getCapabilitiesForType(mime)
                    if (caps.videoCapabilities?.isSizeSupported(w, h) == false) {
                        codec.release()
                        break
                    }
                    codec.configure(buildFormat(type, w, h, caps, level), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
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
        throw IllegalStateException("No usable ${type.label} encoder on this phone", lastError)
    }

    private fun buildFormat(type: VideoCodec, w: Int, h: Int, caps: MediaCodecInfo.CodecCapabilities, level: Int): MediaFormat =
        MediaFormat.createVideoFormat(type.mime, w, h).apply {
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
                if (type == VideoCodec.HEVC) {
                    // 8-bit Main profile: what every HEVC TV decoder plays.
                    val main = caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain }
                    if (main) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                } else {
                    val high = caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }
                    if (high) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                }
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
        // H.264: SPS in csd-0, PPS in csd-1. HEVC: VPS + SPS + PPS together in csd-0.
        val csd0 = format.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } } ?: return
        val csd1 = format.getByteBuffer("csd-1")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } } ?: ByteArray(0)
        onCodecConfig(csd0 + csd1)
    }

    private fun onCodecConfig(bytes: ByteArray) {
        if (hub.videoConfig != null) return
        val nals = AvcUtils.splitAnnexB(bytes)
        val config = if (videoCodec == VideoCodec.HEVC) {
            val vps = nals.firstOrNull { HevcUtils.nalType(it) == HevcUtils.NAL_VPS } ?: return
            val sps = nals.firstOrNull { HevcUtils.nalType(it) == HevcUtils.NAL_SPS } ?: return
            val pps = nals.firstOrNull { HevcUtils.nalType(it) == HevcUtils.NAL_PPS } ?: return
            if (HevcUtils.parseSps(sps) == null) return
            VideoConfig(encodedWidth, encodedHeight, sps, pps, VideoCodec.HEVC, vps)
        } else {
            val sps = nals.firstOrNull { AvcUtils.nalType(it) == AvcUtils.NAL_SPS } ?: return
            val pps = nals.firstOrNull { AvcUtils.nalType(it) == AvcUtils.NAL_PPS } ?: return
            VideoConfig(encodedWidth, encodedHeight, sps, pps)
        }
        hub.setVideoConfig(config)
    }

    private fun onFrame(bytes: ByteArray, ptsUs: Long, keyFlag: Boolean) {
        val nals = AvcUtils.splitAnnexB(bytes)
        if (hub.videoConfig == null) {
            // Some encoders put the parameter sets in front of the first key frame instead of a config buffer.
            onCodecConfig(bytes)
            if (hub.videoConfig == null) return
        }
        val hevc = videoCodec == VideoCodec.HEVC
        val sample = if (hevc) HevcUtils.toSample(nals) else AvcUtils.toAvccSample(nals)
        if (sample.isEmpty()) return
        val key = keyFlag || if (hevc) HevcUtils.containsKeyFrame(nals) else AvcUtils.containsIdr(nals)
        hub.pushVideo(sample, ptsUs, key)
    }

    fun stop() {
        running = false
        hub.keyFrameRequester = null
        displayListener?.let { l -> try { context.getSystemService(DisplayManager::class.java).unregisterDisplayListener(l) } catch (_: Exception) {} }
        displayListener = null
        try { display?.release() } catch (_: Exception) {}
        display = null
        scaler?.release()
        scaler = null
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
    }
}
