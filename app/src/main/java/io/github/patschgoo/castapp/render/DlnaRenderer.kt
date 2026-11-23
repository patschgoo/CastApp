package io.github.patschgoo.castapp.render

import android.util.Log
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.cast.CastException
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.dlna.Dlna
import io.github.patschgoo.castapp.service.CastAppTvClient
import io.github.patschgoo.castapp.service.SwapDirection
import io.github.patschgoo.castapp.tv.AppPairing
import io.github.patschgoo.castapp.tv.PairingClient
import io.github.patschgoo.castapp.tv.PlaybackFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Plays media on a DLNA TV (Samsung, LG, Sony, ...) through UPnP AVTransport.
 * DLNA has no push events we can rely on, so the state is polled every second.
 */
class DlnaRenderer(
    override val device: CastDevice,
    /** Pairs with CastApp TVs that only accept paired phones; null = no pairing (tests). */
    private val pairing: PairingClient? = AppPairing.client,
    /** Other TVs: how long after Play an ERROR_OCCURRED may still be left over from before (see [poll]). */
    private val errorSettleMs: Long = ERROR_SETTLE_MS,
) : MediaRenderer {
    override var listener: MediaRenderer.Listener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val avt = device.avTransportUrl ?: ""
    private val rc = device.renderingControlUrl
    @Volatile private var local: InetAddress? = null
    @Volatile private var poller: Job? = null
    @Volatile private var sawPlaying = false
    @Volatile private var lastPosition = 0L
    @Volatile private var lastDuration = 0L
    @Volatile private var failures = 0
    @Volatile private var closed = false
    @Volatile private var loadedAt = 0L
    /** CastApp TVs: who we are and the pairing token (for receivers that only accept paired phones). */
    @Volatile private var authHeaders: Map<String, String> = emptyMap()
    /** Counts [load]s: a poll that began before or during one can't tell about the new address. */
    private val loads = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var loading = false
    @Volatile private var playSentAt = 0L
    /** Polls in a row that said ERROR_OCCURRED while nothing played. */
    @Volatile private var errorPolls = 0
    /** CastApp TV's reason for the failure of a load (its number to the text), asked once. */
    @Volatile private var reasonOfLoad: Pair<Int, String?>? = null

    private fun soapCall(url: String, service: String, action: String, args: List<Pair<String, String>>, timeoutMs: Int = 5000): String =
        Dlna.call(url, service, action, args, timeoutMs, authHeaders)

    /** Pairs with a CastApp TV that wants a code (asks the user once) and prepares the headers for it. */
    private suspend fun pairIfNeeded() {
        val p = pairing ?: return
        if (!device.isCastAppTv) return
        val base = SwapDirection.baseUrl(device) ?: return
        try {
            p.ensure(device, base)
        } catch (e: PairingClient.PairingException) {
            throw CastException(e.message ?: Txt.s(R.string.err_pairing_failed))
        }
        authHeaders = p.headersFor(device)
    }

    override val localAddress: InetAddress? get() = local

    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (avt.isEmpty()) throw CastException(Txt.s(R.string.err_cant_play_media, device.name))
        val uri = URI(avt)
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(uri.host, if (uri.port > 0) uri.port else 80), 4000)
                local = s.localAddress
            }
            soapCall(avt, Dlna.AV_TRANSPORT, "GetTransportInfo", listOf("InstanceID" to "0"))
        } catch (e: Exception) {
            throw CastException(Txt.s(R.string.err_cant_reach, device.name, device.host))
        }
        pairIfNeeded()
        readVolume()
        startPolling()
    }

    override suspend fun load(media: RendererMedia): RendererStatus = withContext(Dispatchers.IO) {
        sawPlaying = false
        lastPosition = 0
        lastDuration = 0
        loadedAt = System.currentTimeMillis()
        loads.incrementAndGet()
        loading = true
        errorPolls = 0
        try {
            start(media)
        } finally {
            loading = false
            playSentAt = System.currentTimeMillis()
        }
        if (media.startMs > 0) {
            // Most TVs only accept a seek once playback has started.
            for (i in 0 until 20) {
                if (sawPlaying) break
                delay(500)
                poll()
            }
            try { seekNow(media.startMs) } catch (_: Exception) {}
        }
        RendererStatus(PlayState.BUFFERING)
    }

    /** Stop, the new address and Play. */
    private suspend fun start(media: RendererMedia) {
        try { soapCall(avt, Dlna.AV_TRANSPORT, "Stop", listOf("InstanceID" to "0")) } catch (_: Exception) {}
        val meta = Dlna.didl(
            media.title, media.url, media.mimeType, media.live, media.subtitleSrtUrl ?: media.subtitleUrl, media.lowLatency,
            // Only CastApp's own receiver understands the subtitle look; other TVs keep theirs.
            subtitleStyle = media.subtitleStyle?.takeIf { device.isCastAppTv }?.encode(),
            syncUrl = media.syncUrl?.takeIf { device.isCastAppTv },
            subtitleTrack = media.subtitleTrack?.takeIf { device.isCastAppTv },
        )
        val setUri = {
            soapCall(
                avt, Dlna.AV_TRANSPORT, "SetAVTransportURI",
                listOf("InstanceID" to "0", "CurrentURI" to media.url, "CurrentURIMetaData" to meta),
                timeoutMs = 10_000,
            )
        }
        try {
            setUri()
        } catch (e: Dlna.SoapException) {
            // The TV forgot this phone: pair again (asks for the code) and retry once.
            if (pairing == null || !device.isCastAppTv || e.message?.contains(PairingClient.PAIRING_REQUIRED_MARKER) != true) throw e
            pairing.forget(device)
            pairIfNeeded()
            setUri()
        }
        soapCall(avt, Dlna.AV_TRANSPORT, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
    }

    override suspend fun play() { call("Play", listOf("InstanceID" to "0", "Speed" to "1")) }
    override suspend fun pause() { call("Pause", listOf("InstanceID" to "0")) }
    override suspend fun seek(positionMs: Long) = withContext(Dispatchers.IO) { seekNow(positionMs) }

    private fun seekNow(positionMs: Long) {
        soapCall(avt, Dlna.AV_TRANSPORT, "Seek", listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to Dlna.formatTime(positionMs)))
    }

    override suspend fun refresh(): RendererStatus? = withContext(Dispatchers.IO) { poll() }

    override suspend fun setSubtitlesVisible(on: Boolean) {
        // DLNA has no standard way to switch subtitles; Samsung TVs show them from the metadata.
    }

    override suspend fun setVolume(level: Double) = withContext(Dispatchers.IO) {
        val url = rc ?: return@withContext
        soapCall(url, Dlna.RENDERING_CONTROL, "SetVolume", listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to (level * 100).toInt().coerceIn(0, 100).toString()))
        listener?.onVolume(level, false)
    }

    override suspend fun setMuted(muted: Boolean) = withContext(Dispatchers.IO) {
        val url = rc ?: return@withContext
        soapCall(url, Dlna.RENDERING_CONTROL, "SetMute", listOf("InstanceID" to "0", "Channel" to "Master", "DesiredMute" to if (muted) "1" else "0"))
        readVolume()
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        poller?.cancel()
        try { soapCall(avt, Dlna.AV_TRANSPORT, "Stop", listOf("InstanceID" to "0")) } catch (_: Exception) {}
        Unit
    }

    override fun close() {
        closed = true
        scope.cancel()
    }

    private suspend fun call(action: String, args: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        soapCall(avt, Dlna.AV_TRANSPORT, action, args)
        poll()
        Unit
    }

    private fun readVolume() {
        val url = rc ?: return
        try {
            val vol = Dlna.tag(soapCall(url, Dlna.RENDERING_CONTROL, "GetVolume", listOf("InstanceID" to "0", "Channel" to "Master")), "CurrentVolume")?.toIntOrNull()
            val mute = Dlna.tag(soapCall(url, Dlna.RENDERING_CONTROL, "GetMute", listOf("InstanceID" to "0", "Channel" to "Master")), "CurrentMute")
            listener?.onVolume(vol?.let { it / 100.0 }, mute == "1" || mute.equals("true", true))
        } catch (_: Exception) {
        }
    }

    private fun startPolling() {
        poller?.cancel()
        poller = scope.launch {
            var tick = 0
            while (!closed) {
                delay(1000)
                poll()
                if (++tick % 5 == 0) readVolume()
            }
        }
    }

    /** Reads transport state and position; reports them, or a lost connection after repeated failures. */
    private fun poll(): RendererStatus? {
        // Asked before the answers come: a load in between makes this poll's error an old one.
        val load = loads.get()
        val settled = !loading
        return try {
            val info = soapCall(avt, Dlna.AV_TRANSPORT, "GetTransportInfo", listOf("InstanceID" to "0"), timeoutMs = 3000)
            val pos = soapCall(avt, Dlna.AV_TRANSPORT, "GetPositionInfo", listOf("InstanceID" to "0"), timeoutMs = 3000)
            failures = 0
            val position = Dlna.parseTime(Dlna.tag(pos, "RelTime"))
            val duration = Dlna.parseTime(Dlna.tag(pos, "TrackDuration"))?.takeIf { it > 0 }
            position?.let { lastPosition = it }
            duration?.let { lastDuration = it }
            val transport = Dlna.tag(info, "CurrentTransportState")
            val failed = tvFailed(transport, Dlna.tag(info, "CurrentTransportStatus"), settled && load == loads.get() && !loading)
            val nearEnd = sawPlaying && lastDuration > 0 && lastPosition >= lastDuration - 4000
            val state = if (failed && !nearEnd) PlayState.ERROR else when (transport) {
                "PLAYING" -> { sawPlaying = true; PlayState.PLAYING }
                "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> PlayState.PAUSED
                "TRANSITIONING" -> PlayState.BUFFERING
                "STOPPED", "NO_MEDIA_PRESENT" -> when {
                    // Never started: the TV refused the file (most TVs don't say why).
                    !sawPlaying && System.currentTimeMillis() - loadedAt > 8000 -> PlayState.ERROR
                    !sawPlaying -> PlayState.BUFFERING
                    lastDuration > 0 && lastPosition >= lastDuration - 4000 -> PlayState.FINISHED
                    else -> PlayState.STOPPED
                }
                else -> if (sawPlaying) PlayState.STOPPED else PlayState.IDLE
            }
            val reason = if (state == PlayState.ERROR && failed && device.isCastAppTv) reasonFor(load) else null
            val status = RendererStatus(state, position, duration, reason)
            listener?.onStatus(status)
            if (state == PlayState.FINISHED || state == PlayState.STOPPED || state == PlayState.ERROR) sawPlaying = false
            status
        } catch (e: Exception) {
            if (++failures >= 5 && !closed) {
                failures = 0
                listener?.onLost(e)
            }
            null
        }
    }

    /**
     * The TV says playback failed: `ERROR_OCCURRED` while nothing plays (most TVs never say it; then a
     * file that doesn't start counts as failed after 8 s, as before). Only an answer to a poll [current]
     * for the address loaded now counts. CastApp TV clears it with each new address, so its first answer
     * counts; other TVs may still carry it over from the file before for a moment, so they must say it
     * twice in a row, [errorSettleMs] after Play.
     */
    private fun tvFailed(transport: String?, status: String?, current: Boolean): Boolean {
        val error = current && status.equals("ERROR_OCCURRED", ignoreCase = true) &&
            transport != "PLAYING" && transport != "PAUSED_PLAYBACK" && transport != "PAUSED_RECORDING"
        errorPolls = if (error) errorPolls + 1 else 0
        if (!error) return false
        return device.isCastAppTv || (errorPolls >= 2 && System.currentTimeMillis() - playSentAt >= errorSettleMs)
    }

    /** CastApp TV's reason for the failure of load number [load] (`/castapp/status`), asked once per load. */
    private fun reasonFor(load: Int): String? {
        reasonOfLoad?.takeIf { it.first == load }?.let { return it.second }
        val error = CastAppTvClient.failure(device, authHeaders)
        if (error != null) Log.i(TAG, "${device.name} can't play it: ${error.code}: ${error.message}")
        return error?.let(PlaybackFailure::describe).also { reasonOfLoad = load to it }
    }

    private companion object {
        const val TAG = "DlnaRenderer"

        /** Other TVs: an ERROR_OCCURRED this soon after Play may still be about the file before. */
        const val ERROR_SETTLE_MS = 3000L
    }
}
