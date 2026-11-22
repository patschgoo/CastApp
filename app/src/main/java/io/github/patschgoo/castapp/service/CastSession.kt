package io.github.patschgoo.castapp.service

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.capture.AudioEncoder
import io.github.patschgoo.castapp.capture.CodecChooser
import io.github.patschgoo.castapp.capture.HevcMemory
import io.github.patschgoo.castapp.capture.ScreenEncoder
import io.github.patschgoo.castapp.capture.VideoCodecs
import io.github.patschgoo.castapp.cast.CastClient
import io.github.patschgoo.castapp.cast.CastException
import io.github.patschgoo.castapp.cast.RunningApp
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceKind
import io.github.patschgoo.castapp.discovery.NetworkScanner
import io.github.patschgoo.castapp.render.DlnaRenderer
import io.github.patschgoo.castapp.render.MediaRenderer
import io.github.patschgoo.castapp.render.PlayState
import io.github.patschgoo.castapp.render.RendererMedia
import io.github.patschgoo.castapp.render.RendererStatus
import io.github.patschgoo.castapp.settings.CastSettings
import io.github.patschgoo.castapp.settings.CodecPreference
import io.github.patschgoo.castapp.settings.PictureMode
import io.github.patschgoo.castapp.settings.Resolution
import io.github.patschgoo.castapp.settings.StreamMode
import io.github.patschgoo.castapp.stream.AdaptiveBitrate
import io.github.patschgoo.castapp.stream.StreamHub
import io.github.patschgoo.castapp.stream.StreamServer
import io.github.patschgoo.castapp.stream.VideoCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One live casting session (screen, single app, or sound only): connects to the
 * TV, starts the encoders and the stream server, has the TV play the stream and
 * keeps watching it.
 *
 *  - Low latency: our own TV player if configured, else Google's Default Media
 *    Receiver with a progressive MP4 stream, falling back to HLS when needed.
 *  - Reconnects by itself for up to two minutes if the Wi-Fi drops or the TV
 *    restarts (and finds the TV again if it got a new IP address).
 *  - Lowers the bitrate automatically when the Wi-Fi can't keep up.
 *  - Uses HEVC (a third less bitrate) on TVs known to decode it, and switches to
 *    H.264 by itself if the TV doesn't start playing.
 *  - Can mix the phone's microphone into the sound.
 */
class CastSession(
    private val context: Context,
    device: CastDevice,
    private val projection: MediaProjection,
    private val settingsFlow: StateFlow<CastSettings>,
    /** The user shared a single app (Android 14+), so the phone screen may be blacked out. */
    private val singleApp: Boolean,
    /** Only the phone's sound is cast (speakers, or a TV without picture). */
    private val audioOnly: Boolean,
    private val onEnded: (ActiveCast) -> Unit,
) : ActiveCast {
    @Volatile override var device: CastDevice = device
        private set

    private var settings = settingsFlow.value
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ended = AtomicBoolean(false)
    private val loadMutex = Mutex()
    private val locks = CastLocks(context, "CastApp:screen")

    @Volatile private var client: CastClient? = null
    /** Set instead of [client] for TVs that play by URL: DLNA, CastApp TV, AirPlay, Roku. */
    @Volatile private var urlRenderer: MediaRenderer? = null
    @Volatile private var app: RunningApp? = null
    @Volatile private var server: StreamServer? = null
    @Volatile private var localAddress: InetAddress? = null
    private var hub: StreamHub? = null
    private var video: ScreenEncoder? = null
    private var audio: AudioEncoder? = null

    @Volatile private var usingHls = false
    @Volatile private var usingCustomPlayer = false
    @Volatile private var loading = false
    @Volatile private var mediaSessionId = -1
    @Volatile private var playing = false
    @Volatile private var volume: Double? = null
    @Volatile private var muted = false
    @Volatile private var reconnecting = false
    private var reloads = 0
    private var watchdog: Job? = null

    /** Video codec of the stream; HEVC falls back to H.264 when the TV can't play it. */
    @Volatile private var codec = VideoCodec.H264
    /** The TV has played our HEVC stream at least once (so later errors aren't the codec's fault). */
    @Volatile private var hevcPlayed = false
    @Volatile private var micOn = false
    private var echoWarned = false

    fun start() {
        CastController.begin(
            this,
            CastState.Active(
                device = device,
                phase = CastPhase.CONNECTING,
                compatibilityMode = settings.streamMode == StreamMode.COMPATIBLE,
                resolution = if (audioOnly) "" else settings.resolution.label,
                canDarkScreen = singleApp && !audioOnly && Build.VERSION.SDK_INT >= 34,
                audioOnly = audioOnly,
                audio = audioOnly,
                qualityLabel = if (audioOnly) "" else settings.resolution.short,
                qualityOptions = if (audioOnly) emptyList() else Resolution.entries.map { it.short },
            ),
        )
        scope.launch { run() }
    }

    private suspend fun run() {
        try {
            locks.acquire()
            val h = StreamHub()
            hub = h
            val s = StreamServer(h, settings.port)
            s.start()
            server = s
            codec = chooseCodec()
            startCapture(h)
            // Sound sync can be changed in Settings while casting.
            scope.launch { settingsFlow.collect { h.audioOffsetUs = it.audioDelayMs * 1000L } }

            try {
                connectAndPlay(device.host, device.port)
            } catch (e: CastException) {
                throw e
            } catch (e: Exception) {
                throw CastException("Can't reach ${device.name} at ${device.host}. Is it on the same Wi-Fi?")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cast session failed", e)
            finish(e.message ?: "Casting failed")
        }
    }

    private suspend fun startCapture(h: StreamHub) {
        val captureApps = Build.VERSION.SDK_INT >= 29 && (settings.audio || audioOnly)
        // The microphone also works on phones older than Android 10 (then it is the only sound).
        val wantMic = settings.microphone &&
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val wantAudio = captureApps || wantMic
        h.expectAudio(wantAudio)
        if (!audioOnly) {
            withContext(Dispatchers.Main) {
                val dpi = context.resources.displayMetrics.densityDpi
                val res = settings.resolution
                val v = ScreenEncoder(
                    context, projection, h, res.width, res.height, settings.fps, currentBitrate(), dpi,
                    fill = settings.picture == PictureMode.FILL,
                    codec = codec,
                ) { e ->
                    Log.w(TAG, "Video encoder error", e)
                    finish("Screen capture stopped")
                }
                v.start(mainHandler)
                video = v
                codec = v.videoCodec // H.264 if the phone's HEVC encoder didn't work out
            }
            if (codec == VideoCodec.HEVC) Log.i(TAG, "Mirroring in HEVC to ${device.name}")
            update { it.copy(bitrateKbps = currentBitrate() / 1000, videoCodec = codec.label) }
            installAdaptiveBitrate(h, currentBitrate())
        }
        var audioOn = false
        if (wantAudio) {
            val a = AudioEncoder(
                context, projection, h, captureApps = captureApps,
                micGain = if (wantMic) ({ settingsFlow.value.micLevel / 100f }) else null,
            ) { e ->
                Log.w(TAG, "Audio encoder error", e)
                finish("Audio capture stopped")
            }
            if (a.start()) {
                audio = a
                audioOn = true
                micOn = a.micActive
                if (wantMic && !a.micActive) CastController.message(context.getString(R.string.mic_unavailable))
            } else {
                h.expectAudio(false)
            }
        }
        if (audioOnly && !audioOn) {
            throw CastException(
                "Couldn't capture the phone's sound. Allow CastApp to record audio, and note that some apps block it.",
            )
        }
        update { it.copy(audio = audioOn, microphone = micOn) }
    }

    // ---- video codec ------------------------------------------------------------------

    private fun currentBitrate(): Int = settings.bitrateFor(codec == VideoCodec.HEVC)

    private fun installAdaptiveBitrate(h: StreamHub, bitrate: Int) {
        server?.bandwidthHint = bitrate
        if (!settings.adaptiveQuality) return
        h.congestionListener = AdaptiveBitrate.forCodec(codec, bitrate) { bps ->
            video?.setBitrate(bps)
            update { it.copy(bitrateKbps = bps / 1000) }
        }
    }

    /** H.264, or HEVC where the setting, the phone and the TV allow it. */
    private suspend fun chooseCodec(): VideoCodec {
        if (audioOnly || settings.videoCodec == CodecPreference.H264) return VideoCodec.H264
        val res = settings.resolution
        if (!VideoCodecs.canEncodeHevc(res.width, res.height) && !VideoCodecs.canEncodeHevc()) return VideoCodec.H264
        val target = when {
            device.isCastAppTv -> CodecChooser.Target.CASTAPP_TV
            device.isDlna -> CodecChooser.Target.DLNA
            device.kind == DeviceKind.CAST -> CodecChooser.Target.CAST
            else -> CodecChooser.Target.HLS_PLAYER
        }
        // A CastApp TV tells whether its decoder plays HEVC (older versions don't answer: H.264).
        val tvHevc = if (target == CodecChooser.Target.CASTAPP_TV) {
            withContext(Dispatchers.IO) {
                CastAppTvClient.capabilities(device)?.let { caps -> if (caps.has("hevc")) caps.optBoolean("hevc") else null }
            }
        } else {
            null
        }
        return CodecChooser.choose(
            preference = settings.videoCodec,
            target = target,
            phoneEncodesHevc = true,
            tvDecodesHevc = tvHevc,
            castModel = device.model,
            failedBefore = HevcMemory.failedBefore(context, device.id),
            compatibleMode = settings.streamMode == StreamMode.COMPATIBLE,
        )
    }

    /**
     * HEVC didn't play on this TV: rebuild the encoder for H.264 (the capture and
     * the user's permission stay). Returns false if there is nothing to switch or it failed.
     */
    private suspend fun switchToH264(reason: String): Boolean {
        if (codec != VideoCodec.HEVC || audioOnly || ended.get()) return false
        val v = video ?: return false
        val h = hub ?: return false
        val s = server ?: return false
        Log.i(TAG, "HEVC didn't work on ${device.name} ($reason), switching to H.264")
        if (settings.videoCodec == CodecPreference.AUTO) HevcMemory.markFailed(context, device.id)
        codec = VideoCodec.H264
        val bitrate = currentBitrate()
        update { it.copy(phase = CastPhase.BUFFERING, videoCodec = codec.label, bitrateKbps = bitrate / 1000) }
        loadMutex.withLock {
            withContext(Dispatchers.Main) {
                val res = settings.resolution
                v.reconfigure(res.width, res.height, bitrate, VideoCodec.H264) {
                    h.resetVideo()
                    s.restartStreams()
                }
            }
            installAdaptiveBitrate(h, bitrate)
        }
        CastController.message(context.getString(R.string.hevc_fallback_message, device.name))
        return h.awaitInitSegment(8_000) != null
    }

    /** The low-latency web player answers whether its browser plays these codecs (older versions don't answer). */
    private suspend fun customPlayerSupports(c: CastClient, a: RunningApp, codecs: String): Boolean = try {
        val reply = c.requestCustom(a, NS_PLAYER, JSONObject().put("type", "CAPS").put("codecs", codecs), timeoutMs = 2500)
        reply.optBoolean("supported", false)
    } catch (e: Exception) {
        false
    }

    /** Mixing the microphone in while the TV is loud makes the TV's sound come back as an echo. */
    private fun checkEcho() {
        val level = volume ?: return
        if (!micOn || echoWarned || muted || level < ECHO_VOLUME) return
        echoWarned = true
        CastController.message(context.getString(R.string.mic_echo_warning))
    }

    // ---- connecting and loading ------------------------------------------------------

    /** Connects to the TV at [host] and starts playback. Throws if anything fails. */
    private suspend fun connectAndPlay(host: String, port: Int) {
        if (device.isUrlRenderer) return connectUrlRenderer()
        val c = CastClient(host, port)
        c.listener = listenerFor(c)
        try {
            c.connect(6000)
        } catch (e: Exception) {
            c.close()
            throw e
        }
        client = c
        localAddress = c.localAddress ?: throw CastException("No network path to the TV")
        update { it.copy(phase = CastPhase.STARTING) }

        val h = hub ?: return
        if (audioOnly) {
            h.awaitAudioConfig(8_000) ?: throw CastException("Sound capture did not start")
        } else {
            // Capture is running and the track layout is fixed (video-only if audio never starts).
            h.awaitInitSegment(8_000) ?: throw CastException("Screen capture did not start")
        }

        if (!audioOnly && settings.receiverAppId.isNotBlank() && settings.streamMode == StreamMode.LOW_LATENCY) {
            if (tryCustomPlayer(c, settings.receiverAppId.trim())) return
        }
        val a = c.launch(CastClient.DEFAULT_MEDIA_RECEIVER)
        app = a
        try { readVolume(c.getReceiverStatus()) } catch (_: Exception) {}
        when {
            audioOnly -> loadAudio()
            settings.streamMode == StreamMode.COMPATIBLE || usingHls -> loadHls()
            else -> loadMp4()
        }
    }

    /**
     * TVs that play by URL. DLNA TVs get a live MPEG-TS stream, CastApp TV the
     * low-latency MP4 stream, AirPlay and Roku devices HLS (sound only: AAC).
     */
    private suspend fun connectUrlRenderer() {
        val r = MediaRenderer.create(device)
        r.listener = object : MediaRenderer.Listener {
            override fun onStatus(status: RendererStatus) {
                if (r !== urlRenderer) return
                when (status.state) {
                    PlayState.PLAYING -> {
                        playing = true
                        if (codec == VideoCodec.HEVC) hevcPlayed = true
                        update { it.copy(phase = CastPhase.LIVE) }
                    }
                    PlayState.BUFFERING -> if (!playing) update { it.copy(phase = CastPhase.BUFFERING) }
                    PlayState.ERROR -> when {
                        !playing && codec == VideoCodec.HEVC && !hevcPlayed -> scope.launch { urlRendererToH264(r, "player error") }
                        playing && !reconnecting -> finish("Casting was stopped on the TV")
                    }
                    PlayState.STOPPED, PlayState.FINISHED -> if (playing && !reconnecting) finish("Casting was stopped on the TV")
                    else -> Unit
                }
            }
            override fun onVolume(level: Double?, muted: Boolean) {
                if (r !== urlRenderer) return
                level?.let { volume = it }
                this@CastSession.muted = muted
                update { it.copy(volume = volume?.toFloat(), muted = muted) }
                checkEcho()
            }
            override fun onLost(error: Throwable?) {
                if (r === urlRenderer && !ended.get()) scope.launch { reconnect() }
            }
            override fun onClosedOnDevice() {
                if (r === urlRenderer) finish("Casting was stopped on the TV")
            }
        }
        r.connect()
        urlRenderer = r
        localAddress = r.localAddress ?: throw CastException("No network path to the TV")
        update { it.copy(phase = CastPhase.STARTING) }
        val h = hub ?: return
        val s = server ?: return
        if (audioOnly) {
            h.awaitAudioConfig(8_000) ?: throw CastException("Sound capture did not start")
        } else {
            h.awaitInitSegment(8_000) ?: throw CastException("Screen capture did not start")
        }
        playing = false
        val local = localAddress!!
        r.load(mirrorMedia(s, local))
        update { it.copy(phase = CastPhase.BUFFERING) }
        if (codec == VideoCodec.HEVC && !hevcPlayed) {
            // No picture after a while: the TV probably can't decode HEVC after all.
            watchdog?.cancel()
            watchdog = scope.launch {
                delay(MP4_START_TIMEOUT_MS)
                if (!playing && !ended.get() && !reconnecting && r === urlRenderer) urlRendererToH264(r, "no playback")
            }
        }
    }

    /** A URL-playing TV didn't play our HEVC stream: switch to H.264 and load it again. */
    private suspend fun urlRendererToH264(r: MediaRenderer, reason: String) {
        if (codec != VideoCodec.HEVC || ended.get()) return
        if (!switchToH264(reason)) return
        val s = server ?: return
        val local = localAddress ?: return
        try {
            playing = false
            if (r === urlRenderer) r.load(mirrorMedia(s, local))
        } catch (e: Exception) {
            Log.w(TAG, "Reload in H.264 failed", e)
            finish("${device.name} couldn't play the stream")
        }
    }

    /** What a URL-playing TV gets for the screen (or the phone's sound). */
    private suspend fun mirrorMedia(s: StreamServer, local: java.net.InetAddress): RendererMedia = when {
        audioOnly -> RendererMedia(s.urlFor(local, s.audioPath), "audio/aac", "Phone sound", device.name, live = true, lowLatency = device.isCastAppTv)
        device.isCastAppTv -> RendererMedia(s.urlFor(local, s.mp4Path), "video/mp4", TITLE, device.name, live = true, lowLatency = true)
        device.isDlna -> RendererMedia(s.urlFor(local, s.tsPath), "video/mp2t", TITLE, device.name, live = true)
        else -> {
            // AirPlay and Roku players want HLS; give the segmenter a few segments first.
            val segmenter = s.enableHls()
            usingHls = true
            withTimeoutOrNull(12_000) { while (segmenter.completedSegments < 3) delay(100) }
            // HEVC needs the master playlist, which names the codec ("hvc1...").
            val path = if (codec == VideoCodec.HEVC) s.hlsMasterPath else s.hlsPath
            RendererMedia(s.urlFor(local, path), "application/x-mpegurl", TITLE, device.name, live = true)
        }
    }

    /** Our own low-latency TV player (see receiver/index.html). Returns false to fall back. */
    private suspend fun tryCustomPlayer(c: CastClient, appId: String): Boolean {
        val s = server ?: return false
        val h = hub ?: return false
        val local = localAddress ?: return false
        return try {
            val a = c.launch(appId, timeoutMs = 20_000)
            app = a
            try { readVolume(c.getReceiverStatus()) } catch (_: Exception) {}
            var codecs = buildString {
                append(h.videoConfig?.codecString ?: "avc1.42E01F")
                if (h.publishedAudioRate != null) append(",mp4a.40.2")
            }
            if (codec == VideoCodec.HEVC && !customPlayerSupports(c, a, codecs)) {
                // That TV's browser can't play HEVC (or it is an older player that can't tell): H.264.
                if (!switchToH264("low-latency player")) throw CastException("player can't play HEVC")
                codecs = buildString {
                    append(h.videoConfig?.codecString ?: "avc1.42E01F")
                    if (h.publishedAudioRate != null) append(",mp4a.40.2")
                }
            }
            val reply = c.requestCustom(
                a, NS_PLAYER,
                JSONObject().put("type", "PLAY").put("url", s.urlFor(local, s.mp4Path)).put("codecs", codecs),
                timeoutMs = 15_000,
            )
            if (reply.optString("state") == "error") {
                if (codec == VideoCodec.HEVC && switchToH264("low-latency player error")) return tryCustomPlayer(c, appId)
                throw CastException(reply.optString("message", "player error"))
            }
            usingCustomPlayer = true
            playing = true
            update { it.copy(phase = CastPhase.LIVE, lowLatencyPlayer = true, compatibilityMode = false) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Low-latency TV player not available ($appId): ${e.message}")
            CastController.message("Low-latency TV player didn't start, using the standard player")
            usingCustomPlayer = false
            false
        }
    }

    private suspend fun loadMp4(): Unit = loadMutex.withLock {
        val c = client ?: return
        val a = app ?: return
        val s = server ?: return
        val local = localAddress ?: return
        usingHls = false
        update { it.copy(phase = CastPhase.BUFFERING, compatibilityMode = false) }
        val media = CastClient.liveMedia(s.urlFor(local, s.mp4Path), hls = false, title = TITLE, subtitle = device.name)
        val ok = loadMedia(c, a, media)
        if (!ok) {
            scope.launch { fallbackToHls("load failed") }
            return
        }
        // If the TV hasn't started playing after a while, it probably can't handle the stream.
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(MP4_START_TIMEOUT_MS)
            // A connected stream client means the TV is at least trying; give it a bit longer.
            if (!playing && (server?.activeStreamClients ?: 0) > 0) delay(MP4_START_TIMEOUT_MS)
            if (!playing && !usingHls && !ended.get() && !reconnecting) fallbackToHls("no playback")
        }
    }

    private suspend fun loadHls(): Unit = loadMutex.withLock {
        val c = client ?: return
        val a = app ?: return
        val s = server ?: return
        val local = localAddress ?: return
        usingHls = true
        watchdog?.cancel()
        update { it.copy(phase = CastPhase.BUFFERING, compatibilityMode = true) }
        val segmenter = s.enableHls()
        withTimeoutOrNull(12_000) { while (segmenter.completedSegments < 3) delay(100) }
        // HEVC needs the master playlist, which names the codec ("hvc1...").
        val path = if (codec == VideoCodec.HEVC) s.hlsMasterPath else s.hlsPath
        val media = CastClient.liveMedia(s.urlFor(local, path), hls = true, title = TITLE, subtitle = device.name)
        if (!loadMedia(c, a, media)) {
            if (codec == VideoCodec.HEVC) {
                scope.launch { if (switchToH264("HLS load failed")) loadHls() else finish("${device.name} couldn't play the stream") }
            } else {
                finish("${device.name} couldn't play the stream")
            }
        }
    }

    private suspend fun loadAudio(): Unit = loadMutex.withLock {
        val c = client ?: return
        val a = app ?: return
        val s = server ?: return
        val local = localAddress ?: return
        update { it.copy(phase = CastPhase.BUFFERING) }
        val media = CastClient.liveAudioMedia(s.urlFor(local, s.audioPath), title = "Phone sound", subtitle = device.name)
        if (!loadMedia(c, a, media)) finish("${device.name} couldn't play the sound stream")
    }

    private suspend fun loadMedia(c: CastClient, a: RunningApp, media: JSONObject): Boolean {
        loading = true
        playing = false
        return try {
            val status = c.load(a, media)
            mediaSessionId = status.optInt("mediaSessionId", -1)
            loading = false
            handleMediaStatus(status)
            true
        } catch (e: CastException) {
            Log.w(TAG, "LOAD failed (hls=$usingHls): ${e.message}")
            loading = false
            false
        }
    }

    private suspend fun fallbackToHls(reason: String) {
        if (usingHls || audioOnly || ended.get()) return
        if (codec == VideoCodec.HEVC && !hevcPlayed) {
            // Most likely the TV can't decode HEVC: try H.264 the same way first.
            if (switchToH264(reason)) {
                reloads = 0
                loadMp4()
                return
            }
        }
        Log.i(TAG, "Switching to HLS: $reason")
        loadHls()
    }

    private suspend fun reload() {
        when {
            audioOnly -> loadAudio()
            usingHls -> loadHls()
            else -> loadMp4()
        }
    }

    // ---- reconnecting -------------------------------------------------------------------

    /** The connection to the TV broke (Wi-Fi drop, TV restart): keep capturing and try again. */
    private suspend fun reconnect() {
        if (ended.get() || reconnecting) return
        reconnecting = true
        watchdog?.cancel()
        update { it.copy(phase = CastPhase.RECONNECTING) }
        try { client?.close() } catch (_: Exception) {}
        try { urlRenderer?.close() } catch (_: Exception) {}
        playing = false
        val deadline = System.currentTimeMillis() + RECONNECT_FOR_MS
        var attempt = 0
        var host = device.host
        var port = device.port
        while (!ended.get() && System.currentTimeMillis() < deadline) {
            attempt++
            delay(if (attempt == 1) 1500 else 4000)
            if (attempt % 3 == 0 && !device.isUrlRenderer) {
                // The TV may have come back with a different IP address.
                NetworkScanner.findById(device.id)?.let { found ->
                    host = found.host
                    port = found.port
                    device = device.copy(host = found.host, port = found.port)
                    CastApp.get(context).devices.found(found)
                }
            }
            try {
                reloads = 0
                mediaSessionId = -1
                connectAndPlay(host, port)
                reconnecting = false
                Log.i(TAG, "Reconnected after $attempt attempt(s)")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "Reconnect attempt $attempt failed: ${e.message}")
            }
        }
        reconnecting = false
        finish("Lost the connection to ${device.name}")
    }

    // ---- receiver events -------------------------------------------------------

    private fun listenerFor(c: CastClient) = object : CastClient.Listener {
        override fun onReceiverStatus(status: JSONObject) {
            if (c === client) readVolume(status)
        }

        override fun onMediaStatus(status: JSONObject) {
            if (c !== client || loading || mediaSessionId == -1) return // nothing of ours is loaded (yet)
            val id = status.optInt("mediaSessionId", -1)
            if (id != -1 && id != mediaSessionId) return // status of media we replaced
            handleMediaStatus(status)
        }

        override fun onCustomMessage(namespace: String, json: JSONObject) {
            if (c !== client || namespace != NS_PLAYER || json.optString("type") != "STATUS") return
            when (json.optString("state")) {
                "playing" -> update { it.copy(phase = CastPhase.LIVE) }
                "buffering" -> update { it.copy(phase = CastPhase.BUFFERING) }
                "error" -> Log.w(TAG, "Low-latency player error: ${json.optString("message")}")
            }
        }

        override fun onAppClosed() {
            if (c === client && !reconnecting) finish("Casting was stopped on the TV")
        }

        override fun onDisconnected(error: Throwable?) {
            if (c === client && !ended.get()) scope.launch { reconnect() }
        }
    }

    private fun handleMediaStatus(status: JSONObject) {
        when (status.optString("playerState")) {
            "PLAYING" -> {
                playing = true
                if (codec == VideoCodec.HEVC) hevcPlayed = true
                reloads = 0
                update { it.copy(phase = CastPhase.LIVE) }
            }
            "BUFFERING" -> update { it.copy(phase = if (playing) CastPhase.BUFFERING else it.phase) }
            "PAUSED" -> {
                // A paused live stream would drift behind; just resume it.
                val c = client
                val a = app
                if (c != null && a != null && mediaSessionId >= 0) {
                    try {
                        c.send(
                            CastClient.NS_MEDIA, a.transportId,
                            JSONObject().put("type", "PLAY").put("mediaSessionId", mediaSessionId).put("requestId", 0),
                        )
                    } catch (_: Exception) {
                    }
                }
            }
            "IDLE" -> when (status.optString("idleReason")) {
                "ERROR", "FINISHED" -> scope.launch { recoverPlayback() }
                "CANCELLED", "INTERRUPTED" -> finish("Casting was stopped on the TV")
            }
        }
    }

    /** The TV stopped playing (network hiccup or playback error): reload, then fall back to HLS. */
    private suspend fun recoverPlayback() {
        if (ended.get() || reconnecting) return
        playing = false
        reloads++
        when {
            reloads > 3 -> finish("${device.name} keeps stopping the stream")
            // An HEVC stream that never played: don't retry, it's most likely the codec.
            !usingHls && !audioOnly && (reloads > 1 || (codec == VideoCodec.HEVC && !hevcPlayed)) -> fallbackToHls("playback error")
            else -> {
                delay(500)
                reload()
            }
        }
    }

    private fun readVolume(status: JSONObject) {
        val v = status.optJSONObject("volume") ?: return
        if (v.has("level")) volume = v.optDouble("level")
        muted = v.optBoolean("muted", muted)
        update { it.copy(volume = volume?.toFloat(), muted = muted) }
        checkEcho()
    }

    // ---- user controls ----------------------------------------------------------

    /** Switches the mirroring resolution while casting (e.g. "720p", "4K"). */
    fun setQuality(label: String) {
        val res = Resolution.entries.firstOrNull { it.short == label } ?: return
        if (audioOnly || res == settings.resolution) return
        scope.launch {
            val v = video ?: return@launch
            val h = hub ?: return@launch
            val s = server ?: return@launch
            settings = settings.copy(resolution = res)
            CastApp.get(context).settings.update { it.copy(resolution = res) } // remembered for next time
            val bitrate = currentBitrate()
            update { it.copy(phase = CastPhase.BUFFERING, qualityLabel = res.short, resolution = res.label, bitrateKbps = bitrate / 1000) }
            loadMutex.withLock {
                withContext(Dispatchers.Main) {
                    v.reconfigure(res.width, res.height, bitrate) {
                        h.resetVideo()
                        s.restartStreams()
                    }
                }
                if (v.videoCodec != codec) {
                    // No HEVC encoder for that size: it went back to H.264.
                    codec = v.videoCodec
                    update { it.copy(videoCodec = codec.label) }
                }
                installAdaptiveBitrate(h, bitrate)
            }
            if (h.awaitInitSegment(8_000) == null) {
                finish("The phone can't encode ${res.short}")
                return@launch
            }
            // The TV has to reload to pick up the new picture size.
            try {
                when {
                    device.isUrlRenderer -> urlRenderer?.let { r ->
                        val local = localAddress ?: return@let
                        r.load(mirrorMedia(s, local))
                    }
                    usingCustomPlayer -> client?.let { c -> if (!tryCustomPlayer(c, settings.receiverAppId.trim())) reload() }
                    else -> reload()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reload after resolution change failed", e)
            }
        }
    }

    override fun changeVolume(delta: Double) {
        val c = client
        val r = urlRenderer
        if (c == null && r == null) return
        scope.launch {
            try {
                val target = ((volume ?: 0.5) + delta).coerceIn(0.0, 1.0)
                volume = target
                update { it.copy(volume = target.toFloat(), muted = false) }
                if (r != null) {
                    if (muted) r.setMuted(false)
                    r.setVolume(target)
                } else if (c != null) {
                    if (muted) c.setMuted(false)
                    c.setVolume(target)
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun toggleMute() {
        scope.launch {
            try {
                urlRenderer?.setMuted(!muted) ?: client?.setMuted(!muted)
            } catch (_: Exception) {
            }
        }
    }

    // ---- teardown ---------------------------------------------------------------

    /**
     * Ends the session. [message] is shown to the user (null when the user stopped it).
     * [stopReceiver] closes the player on the TV; it is skipped when a new session is
     * about to take over the same TV.
     */
    override fun finish(message: String?, stopReceiver: Boolean) {
        if (!ended.compareAndSet(false, true)) return
        Thread({ teardown(message, stopReceiver) }, "cast-teardown").start()
    }

    private fun teardown(message: String?, stopReceiver: Boolean) {
        watchdog?.cancel()
        val c = client
        val a = app
        if (stopReceiver && c != null && a != null && c.isConnected) {
            try {
                runBlocking { withTimeoutOrNull(2500) { c.stopApp(a) } }
            } catch (_: Exception) {
            }
        }
        try { c?.close() } catch (_: Exception) {}
        urlRenderer?.let { r ->
            if (stopReceiver) try { runBlocking { withTimeoutOrNull(2500) { r.stop() } } } catch (_: Exception) {}
            r.close()
        }
        scope.cancel()
        if (Build.VERSION.SDK_INT >= 29) try { audio?.stop() } catch (_: Exception) {}
        try { video?.stop() } catch (_: Exception) {}
        try { server?.stop() } catch (_: Exception) {}
        try { projection.stop() } catch (_: Exception) {}
        locks.release()
        CastController.end(this)
        if (message != null) CastController.message(message)
        mainHandler.post { onEnded(this) }
    }

    private fun update(transform: (CastState.Active) -> CastState.Active) = CastController.update(this) {
        transform(it).copy(device = device)
    }

    companion object {
        private const val TAG = "CastSession"
        private const val TITLE = "Phone screen"
        private const val MP4_START_TIMEOUT_MS = 15_000L
        private const val RECONNECT_FOR_MS = 120_000L
        /** TV volume (0..1) from which the microphone probably picks up the TV: warn about echo. */
        private const val ECHO_VOLUME = 0.6

        /** Namespace of our own low-latency TV player (receiver/index.html). */
        const val NS_PLAYER = "urn:x-cast:io.github.patschgoo.castapp"
    }
}
