package io.github.patschgoo.castapp.multi

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceKind
import io.github.patschgoo.castapp.dlna.Dlna
import io.github.patschgoo.castapp.dlna.SsdpBrowser
import io.github.patschgoo.castapp.render.DlnaRenderer
import io.github.patschgoo.castapp.render.MediaRenderer
import io.github.patschgoo.castapp.render.PlayState
import io.github.patschgoo.castapp.render.RendererMedia
import io.github.patschgoo.castapp.render.RendererStatus
import io.github.patschgoo.castapp.tv.RendererBackend
import io.github.patschgoo.castapp.tv.TransportState
import io.github.patschgoo.castapp.tv.TvMedia
import io.github.patschgoo.castapp.tv.TvSnapshot
import io.github.patschgoo.castapp.tv.UpnpRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Casting to several screens: every kind of TV, its own format, commands to all, failures kept apart. */
class ExtraScreensTest {
    /** A TV that plays what it is given; [refuse] formats fail to play, [unreachable] can't be connected to. */
    private class FakeTv(
        override val device: CastDevice,
        val refuse: Set<String> = emptySet(),
        val unreachable: Boolean = false,
    ) : MediaRenderer {
        override var listener: MediaRenderer.Listener? = null
        override val localAddress: InetAddress? = InetAddress.getByName("192.168.1.10")
        val log = CopyOnWriteArrayList<String>()
        val loaded = CopyOnWriteArrayList<RendererMedia>()

        override suspend fun connect() {
            if (unreachable) throw IllegalStateException("can't reach ${device.name}")
            log += "connect"
        }

        override suspend fun load(media: RendererMedia): RendererStatus {
            loaded += media
            log += "load:${media.mimeType}"
            val ok = media.mimeType !in refuse
            val status = RendererStatus(if (ok) PlayState.PLAYING else PlayState.ERROR)
            // Reports come in after the load answer, like a real TV.
            Thread { Thread.sleep(20); listener?.onStatus(status) }.start()
            return RendererStatus(PlayState.BUFFERING)
        }

        override suspend fun play() { log += "play" }
        override suspend fun pause() { log += "pause" }
        override suspend fun seek(positionMs: Long) { log += "seek:$positionMs" }
        override suspend fun refresh(): RendererStatus? = null
        override suspend fun setSubtitlesVisible(on: Boolean) { log += "subs:$on" }
        override suspend fun setVolume(level: Double) { log += "volume:$level" }
        override suspend fun setMuted(muted: Boolean) { log += "mute:$muted" }
        override suspend fun stop() { log += "stop" }
        override fun close() { log += "close" }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val upnp = ArrayList<UpnpRenderer>()

    @After
    fun tearDown() {
        scope.cancel()
        upnp.forEach { it.stop() }
    }

    private val chromecast = CastDevice("cast-1", "Living Room", "192.168.1.20")
    private val samsung = CastDevice(
        "uuid:samsung", "Samsung", "192.168.1.21", 9197, model = "UE55", kind = DeviceKind.DLNA,
        avTransportUrl = "http://192.168.1.21:9197/avt",
    )
    private val castAppTv = CastDevice(
        "uuid:fire", "Fire TV", "192.168.1.22", 49595, model = "CastApp TV 4.0", kind = DeviceKind.DLNA,
        avTransportUrl = "http://192.168.1.22:49595/control/AVTransport",
    )
    private val appleTv = CastDevice("ap-1", "Apple TV", "192.168.1.23", 7000, kind = DeviceKind.AIRPLAY)
    private val roku = CastDevice("roku-1", "Roku", "192.168.1.24", 8060, kind = DeviceKind.ROKU)
    private val speaker = CastDevice("cast-2", "Kitchen speaker", "192.168.1.25", video = false)

    private fun waitFor(what: String, timeoutMs: Long = 3000, check: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun eachKindOfDeviceGetsItsFormat() {
        assertEquals(listOf(MirrorFormat.MP4, MirrorFormat.HLS), MirrorFormats.forDevice(chromecast, audioOnly = false))
        assertEquals(listOf(MirrorFormat.TS), MirrorFormats.forDevice(samsung, audioOnly = false))
        assertEquals(listOf(MirrorFormat.LOW_LATENCY_MP4, MirrorFormat.TS), MirrorFormats.forDevice(castAppTv, audioOnly = false))
        assertEquals(listOf(MirrorFormat.HLS), MirrorFormats.forDevice(appleTv, audioOnly = false))
        assertEquals(listOf(MirrorFormat.HLS), MirrorFormats.forDevice(roku, audioOnly = false))
        assertEquals(listOf(MirrorFormat.AAC), MirrorFormats.forDevice(speaker, audioOnly = true))
        assertTrue(MirrorFormats.sameDevice(chromecast, chromecast.copy(id = "other")))
        assertFalse(MirrorFormats.sameDevice(chromecast, samsung))
    }

    /** What [ExtraScreens] is given for each device, as CastSession builds it for mirroring. */
    private fun mirrorMedia(d: CastDevice): List<RendererMedia> = MirrorFormats.forDevice(d, audioOnly = !d.video).map { f ->
        when (f) {
            MirrorFormat.LOW_LATENCY_MP4 -> RendererMedia("http://phone/t/stream.mp4", "video/mp4", "Phone screen", d.name, live = true, lowLatency = true)
            MirrorFormat.MP4 -> RendererMedia("http://phone/t/stream.mp4", "video/mp4", "Phone screen", d.name, live = true)
            MirrorFormat.HLS -> RendererMedia("http://phone/t/hls/live.m3u8", "application/x-mpegurl", "Phone screen", d.name, live = true)
            MirrorFormat.TS -> RendererMedia("http://phone/t/live.ts", "video/mp2t", "Phone screen", d.name, live = true)
            MirrorFormat.AAC -> RendererMedia("http://phone/t/audio.aac", "audio/aac", "Phone sound", d.name, live = true)
        }
    }

    @Test
    fun plainTvsPlayTheirUsualFormat() {
        val tvs = ConcurrentTvs()
        val screens = ExtraScreens(scope, live = true, factory = tvs::create, startTimeoutMs = 2000)
        screens.provider = { d, _ -> mirrorMedia(d) }
        assertTrue(screens.add(chromecast))
        assertTrue(screens.add(samsung))
        assertTrue(screens.add(appleTv))
        assertFalse("already part of the cast", screens.add(chromecast.copy(id = "cast-1")))
        waitFor("all playing") { screens.infos().all { it.state == ScreenState.PLAYING } }
        assertEquals(listOf("video/mp4"), tvs[chromecast].loaded.map { it.mimeType })
        assertEquals(listOf("video/mp2t"), tvs[samsung].loaded.map { it.mimeType })
        assertEquals(listOf("application/x-mpegurl"), tvs[appleTv].loaded.map { it.mimeType })
        // Plain TVs get exactly what a single-screen cast gives them: no CastApp extras.
        assertTrue(tvs.all().flatMap { it.loaded }.all { it.syncUrl == null && !it.lowLatency })
        assertTrue(screens.infos().none { it.synced })
    }

    @Test
    fun aCastThatRefusesMp4GetsHls() {
        val tvs = ConcurrentTvs(refuse = mapOf(chromecast.id to setOf("video/mp4")))
        val screens = ExtraScreens(scope, live = true, factory = tvs::create, startTimeoutMs = 2000)
        screens.provider = { d, _ -> mirrorMedia(d) }
        screens.add(chromecast)
        waitFor("playing HLS") { screens.infos().single().state == ScreenState.PLAYING }
        assertEquals(listOf("video/mp4", "application/x-mpegurl"), tvs[chromecast].loaded.map { it.mimeType })
        assertEquals(1, screens.all.single().formatIndex)
    }

    @Test
    fun oneBrokenScreenDoesntStopTheOthers() {
        val tvs = ConcurrentTvs(unreachable = setOf(roku.id), refuse = mapOf(samsung.id to setOf("video/mp2t")))
        val gone = CopyOnWriteArrayList<String>()
        val screens = ExtraScreens(scope, live = true, factory = tvs::create, startTimeoutMs = 2000, onGone = { d, _ -> gone += d.id })
        screens.provider = { d, _ -> mirrorMedia(d) }
        screens.add(roku)
        screens.add(samsung)
        screens.add(chromecast)
        waitFor("states settled") {
            val s = screens.infos().associate { it.id to it.state }
            s[roku.id] == ScreenState.ERROR && s[samsung.id] == ScreenState.ERROR && s[chromecast.id] == ScreenState.PLAYING
        }
        assertTrue(gone.isEmpty())
        // Removing the broken ones leaves the good one playing.
        assertTrue(screens.remove(roku.id))
        assertTrue(screens.remove(samsung.id))
        assertEquals(listOf(chromecast.id), screens.infos().map { it.id })
        assertFalse(screens.remove("nope"))
    }

    @Test
    fun commandsGoToEveryScreen() {
        val tvs = ConcurrentTvs()
        val screens = ExtraScreens(scope, live = false, factory = tvs::create, startTimeoutMs = 2000)
        screens.provider = { d, _ -> listOf(RendererMedia("http://phone/t/file/1/film.mp4", "video/mp4", "Film", d.name, startMs = 61_000)) }
        screens.add(chromecast)
        screens.add(samsung)
        waitFor("both playing") { screens.infos().all { it.state == ScreenState.PLAYING } }
        assertEquals(61_000, tvs[samsung].loaded.single().startMs)
        screens.fanOut { it.pause() }
        screens.fanOut { it.seek(90_000) }
        waitFor("both paused and moved") { tvs.all().all { it.log.contains("pause") && it.log.contains("seek:90000") } }

        // Volume is per screen.
        screens.changeVolume(samsung.id, 0.1)
        waitFor("volume") { tvs[samsung].log.any { it.startsWith("volume:") } }
        assertFalse(tvs[chromecast].log.any { it.startsWith("volume:") })
        assertEquals(0.6f, screens.infos().first { it.id == samsung.id }.volume!!, 0.001f)
        screens.toggleMute(chromecast.id)
        waitFor("mute") { tvs[chromecast].log.contains("mute:true") }

        // A new item: everybody loads it again.
        screens.reloadAll()
        waitFor("reloaded") { tvs.all().all { it.loaded.size == 2 } }

        screens.stopAll(stopPlayers = true)
        assertTrue(tvs.all().all { it.log.contains("stop") && it.log.contains("close") })
        assertTrue(screens.isEmpty)
    }

    @Test
    fun aScreenClosedOnTheTvLeaves() {
        val tvs = ConcurrentTvs()
        val gone = CopyOnWriteArrayList<String>()
        val screens = ExtraScreens(scope, live = true, factory = tvs::create, startTimeoutMs = 2000, onGone = { d, _ -> gone += d.id })
        screens.provider = { d, _ -> mirrorMedia(d) }
        screens.add(chromecast)
        waitFor("playing") { screens.infos().single().state == ScreenState.PLAYING }
        // Mirroring never ends by itself: a stopped player was closed on the TV.
        tvs[chromecast].listener!!.onStatus(RendererStatus(PlayState.STOPPED))
        waitFor("gone") { gone.isNotEmpty() }
        assertTrue(screens.isEmpty)
    }

    @Test
    fun detachKeepsThePlayerRunning() {
        val tvs = ConcurrentTvs()
        val screens = ExtraScreens(scope, live = false, factory = tvs::create, startTimeoutMs = 2000)
        screens.provider = { d, _ -> mirrorMedia(d) }
        screens.add(chromecast)
        waitFor("playing") { screens.infos().single().state == ScreenState.PLAYING }
        val s = screens.detach(chromecast.id)!!
        assertTrue(screens.isEmpty)
        assertNull(s.renderer.listener)
        assertFalse(tvs[chromecast].log.contains("stop"))
    }

    /**
     * A plain DLNA TV and a CastApp TV (both played by CastApp's own DLNA server here) through the
     * multi-screen path with the real DLNA client: the plain TV gets exactly the usual metadata,
     * only the CastApp TV gets the shared timeline.
     */
    @Test
    fun plainDlnaTvGetsNoCastAppExtras() = runBlocking {
        val plainTv = RecordingTv()
        val castAppBox = RecordingTv()
        val plain = startUpnp(plainTv).copy(name = "Samsung", model = "UE55 Series 7")
        val box = startUpnp(castAppBox)
        assertFalse(plain.isCastAppTv)
        assertTrue(box.isCastAppTv)
        val screens = ExtraScreens(scope, live = false, factory = { DlnaRenderer(it, pairing = null) }, startTimeoutMs = 3000)
        screens.provider = { d, _ ->
            listOf(RendererMedia("http://192.168.1.10:8090/t/file/1/film.mp4", "video/mp4", "Film", d.name, syncUrl = "http://192.168.1.10:8765/castapp/timeline"))
        }
        screens.add(plain)
        screens.add(box)
        waitFor("both loaded", 5000) { plainTv.got != null && castAppBox.got != null }
        assertNull("plain DLNA: no sync marker", plainTv.got!!.syncUrl)
        assertFalse(plainTv.got!!.metadata.contains("castapp:sync"))
        assertEquals(
            "plain DLNA metadata is exactly a single cast's",
            Dlna.didl("Film", "http://192.168.1.10:8090/t/file/1/film.mp4", "video/mp4", false),
            plainTv.got!!.metadata,
        )
        assertEquals("http://192.168.1.10:8765/castapp/timeline", castAppBox.got!!.syncUrl)
        screens.stopAll(stopPlayers = false)
    }

    private class RecordingTv : RendererBackend {
        @Volatile var got: TvMedia? = null
        override var snapshot = TvSnapshot()
        override fun setMedia(media: TvMedia) { got = media }
        override fun play() { snapshot = snapshot.copy(state = TransportState.PLAYING) }
        override fun pause() { snapshot = snapshot.copy(state = TransportState.PAUSED) }
        override fun stop() { snapshot = snapshot.copy(state = TransportState.STOPPED) }
        override fun seek(positionMs: Long) {}
        override fun setVolume(percent: Int) {}
        override fun setMuted(muted: Boolean) {}
    }

    private fun startUpnp(backend: RendererBackend): CastDevice {
        val r = UpnpRenderer("Fire TV ${upnp.size}", "uuid-${upnp.size}", backend, "4.0")
        r.start(0, ssdp = false)
        upnp += r
        val location = "http://127.0.0.1:${r.port}/description.xml"
        return SsdpBrowser.rendererDevice(location, Dlna.parseDescription(Dlna.fetch(location)!!, location))!!
    }

    /** Creates a [FakeTv] per device and finds it again. */
    private class ConcurrentTvs(
        val refuse: Map<String, Set<String>> = emptyMap(),
        val unreachable: Set<String> = emptySet(),
    ) {
        private val tvs = java.util.concurrent.ConcurrentHashMap<String, FakeTv>()
        fun create(d: CastDevice): MediaRenderer = FakeTv(d, refuse[d.id].orEmpty(), d.id in unreachable).also { tvs[d.id] = it }
        operator fun get(d: CastDevice): FakeTv = tvs.getValue(d.id)
        fun all(): Collection<FakeTv> = tvs.values
    }
}
