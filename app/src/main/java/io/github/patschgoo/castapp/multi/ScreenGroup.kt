package io.github.patschgoo.castapp.multi

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceKind
import io.github.patschgoo.castapp.render.MediaRenderer
import io.github.patschgoo.castapp.render.PlayState
import io.github.patschgoo.castapp.render.RendererMedia
import io.github.patschgoo.castapp.render.RendererStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** How one screen of a cast is doing, for the casting card. */
enum class ScreenState { CONNECTING, BUFFERING, PLAYING, PAUSED, ERROR }

/** One screen of a cast as the UI shows it (the first screen and every added one). */
data class ScreenInfo(
    val id: String,
    val name: String,
    val kindLabel: String?,
    val state: ScreenState,
    val volume: Float? = null,
    val muted: Boolean = false,
    /** The screen the cast started on; its controls are the cast's own. */
    val primary: Boolean = false,
    /** A CastApp receiver that keeps itself in sync; other TVs are best effort. */
    val synced: Boolean = false,
)

/** The stream formats of screen mirroring, best first for each kind of device. */
enum class MirrorFormat { LOW_LATENCY_MP4, MP4, HLS, TS, AAC }

object MirrorFormats {
    /**
     * What a screen gets from the one running capture, in the order to try: CastApp TV the
     * low-latency MP4, Google Cast MP4 then HLS, DLNA TVs a transport stream, AirPlay and
     * Roku HLS, speakers (sound only) AAC.
     */
    fun forDevice(device: CastDevice, audioOnly: Boolean): List<MirrorFormat> = when {
        audioOnly -> listOf(MirrorFormat.AAC)
        device.isCastAppTv -> listOf(MirrorFormat.LOW_LATENCY_MP4, MirrorFormat.TS)
        device.kind == DeviceKind.CAST -> listOf(MirrorFormat.MP4, MirrorFormat.HLS)
        device.kind == DeviceKind.DLNA -> listOf(MirrorFormat.TS)
        else -> listOf(MirrorFormat.HLS)
    }

    /** Same TV: same id, or same address and port (speaker groups share an address with a speaker). */
    fun sameDevice(a: CastDevice, b: CastDevice): Boolean = a.id == b.id || (a.host == b.host && a.port == b.port)
}

/**
 * The screens added to a running cast. Each one has its own connection (any kind of TV or speaker,
 * through [MediaRenderer]) and plays what [provider] gives it: for mirroring the same live stream
 * in its format, for files the same item. Commands ([fanOut]) go to every screen; one screen
 * failing never stops the others.
 */
class ExtraScreens(
    private val scope: CoroutineScope,
    /** Mirroring: the stream never ends, so a screen that stops playing was closed on the TV. */
    private val live: Boolean,
    private val factory: (CastDevice) -> MediaRenderer = MediaRenderer::create,
    /** How long a screen may take to start playing a format before the next one is tried. */
    private val startTimeoutMs: Long = 15_000,
    /** Called whenever a screen's state changes (to republish the cast's state). */
    private val onChange: () -> Unit = {},
    /** A screen went away by itself (closed on the TV, connection lost for good). */
    private val onGone: (CastDevice, String?) -> Unit = { _, _ -> },
) {
    inner class Screen(val device: CastDevice, val renderer: MediaRenderer) {
        @Volatile var state: ScreenState = ScreenState.CONNECTING
            internal set
        @Volatile var volume: Double? = null
            internal set
        @Volatile var muted: Boolean = false
            internal set
        @Volatile internal var played = false
        @Volatile internal var waiter: CompletableDeferred<Boolean>? = null
        @Volatile internal var job: Job? = null
        @Volatile internal var removed = false
        /** Index of the format it plays now (in what [provider] returned). */
        @Volatile var formatIndex = 0
            internal set
    }

    /** What screen [device] (reached through [local], our address on its network) should play, best first. */
    @Volatile var provider: (suspend (device: CastDevice, local: InetAddress) -> List<RendererMedia>)? = null

    private val screens = CopyOnWriteArrayList<Screen>()

    val all: List<Screen> get() = screens.toList()
    val devices: List<CastDevice> get() = screens.map { it.device }
    val isEmpty: Boolean get() = screens.isEmpty()
    val size: Int get() = screens.size

    fun contains(device: CastDevice): Boolean = screens.any { MirrorFormats.sameDevice(it.device, device) }

    fun find(id: String): Screen? = screens.firstOrNull { it.device.id == id }

    /** Connects to [device] and has it play; false if it is already part of the cast. */
    fun add(device: CastDevice): Boolean {
        if (contains(device)) return false
        val renderer = factory(device)
        val screen = Screen(device, renderer)
        renderer.listener = listenerFor(screen)
        screens += screen
        onChange()
        screen.job = scope.launch {
            try {
                renderer.connect()
                load(screen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(screen, e.message)
            }
        }
        return true
    }

    /** Everybody plays from the start again (new item, new picture size, new codec). */
    fun reloadAll() {
        for (s in screens) {
            if (s.removed || s.state == ScreenState.CONNECTING && s.job?.isActive == true) continue
            s.job?.cancel()
            s.job = scope.launch {
                try {
                    load(s)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(s, e.message)
                }
            }
        }
    }

    private suspend fun load(s: Screen) {
        val local = s.renderer.localAddress ?: throw IllegalStateException("No network path to ${s.device.name}")
        val options = provider?.invoke(s.device, local).orEmpty()
        if (options.isEmpty()) throw IllegalStateException("Nothing to play")
        for ((i, media) in options.withIndex()) {
            if (s.removed) return
            s.formatIndex = i
            s.played = false
            setState(s, ScreenState.BUFFERING)
            val waiter = CompletableDeferred<Boolean>()
            s.waiter = waiter
            try {
                val status = s.renderer.load(media)
                onStatus(s, status)
                if (media.mimeType.startsWith("image/")) {
                    waiter.complete(true)
                }
                val ok = withTimeoutOrNull(startTimeoutMs) { waiter.await() }
                if (ok == true) return
                // Still trying (a slow TV): keep this format if it is the last one.
                if (ok == null && i == options.lastIndex) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (i == options.lastIndex) throw e
            } finally {
                s.waiter = null
            }
        }
        throw IllegalStateException("${s.device.name} couldn't play the stream")
    }

    private fun fail(s: Screen, message: String?) {
        if (s.removed) return
        setState(s, ScreenState.ERROR)
        lastError = message
    }

    /** Why the last screen failed (for a message). */
    @Volatile var lastError: String? = null
        private set

    private fun setState(s: Screen, state: ScreenState) {
        if (s.state == state) return
        s.state = state
        onChange()
    }

    private fun listenerFor(s: Screen) = object : MediaRenderer.Listener {
        override fun onStatus(status: RendererStatus) = onStatus(s, status)

        override fun onVolume(level: Double?, muted: Boolean) {
            level?.let { s.volume = it }
            s.muted = muted
            onChange()
        }

        override fun onLost(error: Throwable?) {
            if (s.removed) return
            setState(s, ScreenState.ERROR)
            // One quiet attempt to get it back; the other screens carry on regardless.
            s.job?.cancel()
            s.job = scope.launch {
                delay(2000)
                try {
                    s.renderer.close()
                    s.renderer.connect()
                    load(s)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    gone(s, e.message)
                }
            }
        }

        override fun onClosedOnDevice() = gone(s, null)
    }

    private fun onStatus(s: Screen, status: RendererStatus) {
        if (s.removed) return
        when (status.state) {
            PlayState.PLAYING -> {
                s.played = true
                s.waiter?.complete(true)
                setState(s, ScreenState.PLAYING)
            }
            PlayState.PAUSED -> {
                s.waiter?.complete(true)
                setState(s, if (live) ScreenState.PLAYING else ScreenState.PAUSED)
            }
            PlayState.BUFFERING -> if (!s.played) setState(s, ScreenState.BUFFERING)
            PlayState.ERROR -> {
                val w = s.waiter
                if (w != null) w.complete(false) else if (s.played) setState(s, ScreenState.ERROR)
            }
            // A start whose answer already came counts as done (the loader clears the waiter a moment later).
            PlayState.STOPPED, PlayState.FINISHED -> if (live && s.played && s.waiter?.isCompleted != false) gone(s, null)
            PlayState.IDLE -> Unit
        }
    }

    private fun gone(s: Screen, message: String?) {
        if (s.removed) return
        s.removed = true
        screens.remove(s)
        s.job?.cancel()
        try { s.renderer.close() } catch (_: Exception) {}
        onChange()
        onGone(s.device, message)
    }

    /** Removes the screen [id] and stops it ([stopPlayer]: closes the player on that TV). */
    fun remove(id: String, stopPlayer: Boolean = true): Boolean {
        val s = find(id) ?: return false
        s.removed = true
        screens.remove(s)
        s.job?.cancel()
        onChange()
        scope.launch {
            if (stopPlayer) try { withTimeoutOrNull(2500) { s.renderer.stop() } } catch (_: Exception) {}
            try { s.renderer.close() } catch (_: Exception) {}
        }
        return true
    }

    /**
     * Takes screen [id] out of the group without stopping it: it becomes the cast's first screen
     * (the caller gives it a listener of its own).
     */
    fun detach(id: String): Screen? {
        val s = find(id) ?: return null
        s.removed = true
        screens.remove(s)
        s.job?.cancel()
        s.renderer.listener = null
        onChange()
        return s
    }

    /** Sends a command to every screen at once (play, pause, seek, subtitles...). */
    fun fanOut(action: suspend (MediaRenderer) -> Unit) {
        for (s in screens) {
            if (s.state == ScreenState.CONNECTING || s.state == ScreenState.ERROR) continue
            scope.launch {
                try { action(s.renderer) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
        }
    }

    fun changeVolume(id: String, delta: Double) {
        val s = find(id) ?: return
        val target = ((s.volume ?: 0.5) + delta).coerceIn(0.0, 1.0)
        s.volume = target
        onChange()
        scope.launch {
            try {
                if (s.muted) s.renderer.setMuted(false)
                s.renderer.setVolume(target)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun toggleMute(id: String) {
        val s = find(id) ?: return
        val mute = !s.muted
        s.muted = mute
        onChange()
        scope.launch {
            try { s.renderer.setMuted(mute) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
    }

    /** Ends every screen (the cast is over). Blocks for a moment; call off the main thread. */
    fun stopAll(stopPlayers: Boolean) {
        val list = screens.toList()
        screens.clear()
        list.forEach { it.removed = true; it.job?.cancel() }
        if (stopPlayers && list.isNotEmpty()) {
            try {
                runBlocking {
                    list.map { s -> launch { try { withTimeoutOrNull(2500) { s.renderer.stop() } } catch (_: Exception) {} } }
                }
            } catch (_: Exception) {
            }
        }
        list.forEach { try { it.renderer.close() } catch (_: Exception) {} }
    }

    fun infos(): List<ScreenInfo> = screens.map { s ->
        ScreenInfo(
            id = s.device.id,
            name = s.device.name,
            kindLabel = s.device.kindLabel,
            state = s.state,
            volume = s.volume?.toFloat(),
            muted = s.muted,
            synced = s.device.isCastAppTv && !live,
        )
    }
}
