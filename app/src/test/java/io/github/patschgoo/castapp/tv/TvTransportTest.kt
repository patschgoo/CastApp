package io.github.patschgoo.castapp.tv

import io.github.patschgoo.castapp.dlna.Dlna
import io.github.patschgoo.castapp.dlna.SsdpBrowser
import io.github.patschgoo.castapp.render.DlnaRenderer
import io.github.patschgoo.castapp.render.PlayState
import io.github.patschgoo.castapp.render.RendererMedia
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * CastApp TV's transport over real HTTP: why it couldn't play (ERROR_OCCURRED, /castapp/status), the
 * next item a sender queues (SetNextAVTransportURI, Next, starting it at the end), and CastApp's own
 * DLNA client reading the failure.
 */
class TvTransportTest {
    /** Behaves like [TvReceiver] towards the renderer; [fail] and [end] stand for the player. */
    private class FakeTv : RendererBackend {
        val calls = CopyOnWriteArrayList<String>()
        @Volatile var current: TvMedia? = null
        @Volatile var events: RendererEvents? = null
        @Volatile var failOnPlay: TvError? = null
        @Volatile override var snapshot = TvSnapshot()

        override fun setMedia(media: TvMedia) {
            calls += "set:${media.uri}"
            current = media
            snapshot = snapshot.copy(state = TransportState.STOPPED, positionMs = 0, durationMs = 0, error = null, warning = null)
        }

        override fun play() {
            calls += "play"
            val error = failOnPlay
            snapshot = if (error != null) {
                snapshot.copy(state = TransportState.STOPPED, error = error)
            } else {
                snapshot.copy(state = TransportState.PLAYING, positionMs = 1_000, durationMs = 1_440_000, error = null)
            }
        }

        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop"; snapshot = snapshot.copy(state = TransportState.STOPPED) }
        override fun seek(positionMs: Long) { calls += "seek:$positionMs" }
        override fun setVolume(percent: Int) {}
        override fun setMuted(muted: Boolean) {}

        override fun advance(media: TvMedia) {
            calls += "advance:${media.uri}"
            current = media
            snapshot = snapshot.copy(state = TransportState.TRANSITIONING, positionMs = 0, durationMs = 0, error = null, warning = null)
        }

        override fun attach(events: RendererEvents?) { this.events = events }

        fun fail(error: TvError) { snapshot = snapshot.copy(state = TransportState.STOPPED, error = error) }

        /** The player reached the end of [current]: as TvReceiver, ask the renderer for the next item. */
        fun end(): Boolean {
            val advanced = events?.playedToEnd(current!!) == true
            if (!advanced) snapshot = snapshot.copy(state = TransportState.STOPPED, positionMs = snapshot.durationMs)
            return advanced
        }
    }

    private val tv = FakeTv()
    private val renderer = UpnpRenderer("Living Room Fire TV", "1234-abcd", tv, "4.1.0")

    @After
    fun tearDown() = renderer.stop()

    private val avt get() = "http://127.0.0.1:${renderer.port}/control/AVTransport"

    private fun soap(action: String, vararg args: Pair<String, String>): String =
        Dlna.call(avt, Dlna.AV_TRANSPORT, action, listOf("InstanceID" to "0") + args)

    private fun setUri(uri: String, metadata: String = "") = soap("SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to metadata)
    private fun setNext(uri: String, metadata: String = "") = soap("SetNextAVTransportURI", "NextURI" to uri, "NextURIMetaData" to metadata)

    private fun transportInfo(): Pair<String?, String?> = soap("GetTransportInfo").let {
        Dlna.tag(it, "CurrentTransportState") to Dlna.tag(it, "CurrentTransportStatus")
    }

    private fun mediaInfo(field: String) = Dlna.tag(soap("GetMediaInfo"), field)?.let(Dlna::unescape).orEmpty()

    private fun get(path: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:${renderer.port}$path").openConnection() as HttpURLConnection
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val code = conn.responseCode
        val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        conn.disconnect()
        return code to text
    }

    private fun status(): JSONObject = get("/castapp/status").let { (code, text) ->
        assertEquals(text, 200, code)
        JSONObject(text)
    }

    private fun didl(title: String, uri: String, protocol: String = "http-get:*:video/mp4:DLNA.ORG_OP=01") =
        """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="1" parentID="0" restricted="1"><dc:title>$title</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="$protocol">$uri</res></item></DIDL-Lite>"""

    // ---- why it couldn't play ------------------------------------------------------------------------

    @Test
    fun aFailureIsErrorOccurredUntilNewMediaComes() {
        renderer.start(0, ssdp = false)
        setUri("http://192.168.1.5:8080/show/episode01.mkv")
        soap("Play")
        assertEquals("PLAYING" to "OK", transportInfo())

        tv.fail(PlaybackFailure.noVideoDecoder("10-bit H.264 (High 10)"))
        assertEquals("STOPPED" to "ERROR_OCCURRED", transportInfo())
        val s = status()
        assertEquals(1, s.getInt("version"))
        assertEquals("STOPPED", s.getString("state"))
        assertEquals("http://192.168.1.5:8080/show/episode01.mkv", s.getString("uri"))
        val error = s.getJSONObject("error")
        assertEquals(PlaybackFailure.NO_VIDEO_DECODER, error.getString("code"))
        assertEquals("No decoder for 10-bit H.264 (High 10) video on this TV", error.getString("message"))
        assertEquals("10-bit H.264 (High 10)", error.getString("format"))
        assertTrue(s.isNull("warning"))

        // Stop doesn't clear it, a new address does.
        soap("Stop")
        assertEquals("ERROR_OCCURRED", transportInfo().second)
        setUri("http://192.168.1.5:8080/show/episode02.mkv")
        assertEquals("STOPPED" to "OK", transportInfo())
        assertTrue(status().isNull("error"))

        // Play again after a failure is a new try.
        tv.fail(PlaybackFailure.network(httpStatus = 404))
        assertEquals(404, status().getJSONObject("error").getInt("http_status"))
        soap("Play")
        assertEquals("PLAYING" to "OK", transportInfo())
    }

    @Test
    fun statusWithNothingLoaded() {
        renderer.start(0, ssdp = false)
        val s = status()
        assertEquals("NO_MEDIA_PRESENT", s.getString("state"))
        assertTrue(s.isNull("uri"))
        assertTrue(s.isNull("next_uri"))
        assertTrue(s.isNull("error"))
        assertEquals(0, s.getLong("position_ms"))
    }

    @Test
    fun statusFollowsTheReceiverProtection() {
        renderer.guard = ReceiverGuard(MemoryGuardStore(), ProtectionMode.ANYONE).apply { setMode(ProtectionMode.PAIRED) }
        renderer.start(0, ssdp = false)
        assertEquals(401, get("/castapp/status").first)
        // Whether it failed is no secret: GetTransportInfo answers anyone, as before.
        assertEquals(Access.PUBLIC, ReceiverGuard.accessFor("AVTransport", "GetTransportInfo"))
        assertEquals(Access.START, ReceiverGuard.accessFor("AVTransport", "SetNextAVTransportURI"))
        assertEquals(Access.CONTROL, ReceiverGuard.accessFor("AVTransport", "Next"))
    }

    @Test
    fun theReceiverKeepsAFailureUntilNewMediaOrPlay() {
        // TvReceiver itself (its screen work is posted to the main thread, which plain unit tests don't run).
        val a = UpnpRenderer.parseMedia("http://nas.local/r1.mkv", "")
        TvReceiver.setMedia(a)
        TvReceiver.failed(a, PlaybackFailure.noVideoDecoder("VC-1"))
        assertEquals(PlaybackFailure.NO_VIDEO_DECODER, TvReceiver.snapshot.error?.code)
        assertEquals(TransportState.STOPPED, TvReceiver.snapshot.state)
        // The player's regular reports and a Stop keep it.
        TvReceiver.report(TransportState.STOPPED, 0, 0)
        TvReceiver.stop()
        assertEquals("VC-1", TvReceiver.snapshot.error?.format)

        val b = UpnpRenderer.parseMedia("http://nas.local/r2.mkv", "")
        TvReceiver.setMedia(b)
        assertNull(TvReceiver.snapshot.error)
        // A late report about the item before doesn't stick to the new one.
        TvReceiver.failed(a, PlaybackFailure.noVideoDecoder("VC-1"))
        assertNull(TvReceiver.snapshot.error)

        TvReceiver.failed(b, PlaybackFailure.network())
        TvReceiver.play()
        assertNull(TvReceiver.snapshot.error)
        assertEquals(TransportState.TRANSITIONING, TvReceiver.snapshot.state)

        TvReceiver.warn(b, PlaybackFailure.playsWithoutSound("DTS"))
        assertEquals(PlaybackFailure.NO_AUDIO_DECODER, TvReceiver.snapshot.warning?.code)
        TvReceiver.setMedia(UpnpRenderer.parseMedia("http://nas.local/r3.mkv", ""))
        assertNull(TvReceiver.snapshot.warning)
    }

    // ---- the next item ---------------------------------------------------------------------------------

    @Test
    fun theNextItemIsKeptAndShown() {
        renderer.start(0, ssdp = false)
        setUri("http://nas.local/a.mp4", didl("Episode 1", "http://nas.local/a.mp4"))
        assertEquals("", mediaInfo("NextURI"))
        val meta = didl("Episode 2 &amp; more", "http://nas.local/b.mp4")
        setNext("http://nas.local/b.mp4", meta)
        assertEquals("http://nas.local/b.mp4", mediaInfo("NextURI"))
        assertEquals(meta, mediaInfo("NextURIMetaData"))
        assertEquals("http://nas.local/a.mp4", mediaInfo("CurrentURI"))
        assertTrue(Dlna.tag(soap("GetCurrentTransportActions"), "Actions")!!.split(',').contains("Next"))
        assertEquals("http://nas.local/b.mp4", status().getString("next_uri"))
        // Nothing starts yet.
        assertEquals(listOf("set:http://nas.local/a.mp4"), tv.calls.toList())

        // An empty address takes it back.
        setNext("")
        assertEquals("", mediaInfo("NextURI"))
        assertFalse(Dlna.tag(soap("GetCurrentTransportActions"), "Actions")!!.contains("Next"))
    }

    @Test
    fun theNextItemStartsWhenTheCurrentOneEnds() {
        renderer.start(0, ssdp = false)
        setUri("http://nas.local/a.mp4", didl("Episode 1", "http://nas.local/a.mp4"))
        soap("Play")
        setNext("http://nas.local/b.mp4", didl("Episode 2", "http://nas.local/b.mp4"))

        assertTrue(tv.end())
        assertEquals("advance:http://nas.local/b.mp4", tv.calls.last())
        assertEquals("Episode 2", tv.current!!.title)
        assertEquals("TRANSITIONING" to "OK", transportInfo())
        assertEquals("http://nas.local/b.mp4", mediaInfo("CurrentURI"))
        assertEquals("", mediaInfo("NextURI"))
        val position = soap("GetPositionInfo")
        assertEquals("http://nas.local/b.mp4", Dlna.tag(position, "TrackURI")?.let(Dlna::unescape))
        assertTrue(Dlna.unescape(Dlna.tag(position, "TrackMetaData")!!).contains("Episode 2"))

        // Only once: the next end has nothing to follow it.
        assertFalse(tv.end())
        assertEquals("STOPPED", transportInfo().first)
    }

    @Test
    fun aNewAddressOrStopDropsTheNextItem() {
        renderer.start(0, ssdp = false)
        setUri("http://nas.local/a.mp4")
        setNext("http://nas.local/b.mp4")
        setUri("http://nas.local/c.mp4")
        assertEquals("", mediaInfo("NextURI"))
        assertFalse(tv.end())

        setNext("http://nas.local/d.mp4")
        soap("Stop")
        assertEquals("", mediaInfo("NextURI"))
        assertFalse(tv.end())
        assertFalse(tv.calls.any { it.startsWith("advance:") })
    }

    @Test
    fun nextJumpsToTheQueuedItem() {
        renderer.start(0, ssdp = false)
        setUri("http://nas.local/a.mp4")
        soap("Play")
        soap("Next") // nothing queued: nothing happens
        assertEquals(listOf("set:http://nas.local/a.mp4", "play"), tv.calls.toList())
        setNext("http://nas.local/b.mp4")
        soap("Next")
        assertEquals("advance:http://nas.local/b.mp4", tv.calls.last())
        assertEquals("http://nas.local/b.mp4", mediaInfo("CurrentURI"))
        assertEquals("", mediaInfo("NextURI"))
    }

    @Test
    fun liveStreamsDontMoveOnByThemselves() {
        renderer.start(0, ssdp = false)
        // DLNA.ORG_OP=00: no seeking, a live stream (a TV channel, a conversion).
        setUri("http://192.168.1.5:8090/tv/channel.ts", didl("Channel", "http://192.168.1.5:8090/tv/channel.ts", "http-get:*:video/mp2t:DLNA.ORG_OP=00"))
        soap("Play")
        setNext("http://nas.local/b.mp4")
        assertFalse(tv.end())
        assertEquals("http://nas.local/b.mp4", mediaInfo("NextURI")) // kept for Next
        assertFalse(tv.calls.any { it.startsWith("advance:") })
    }

    @Test
    fun whatMovesOnByItself() {
        val file = UpnpRenderer.parseMedia("http://nas.local/a.mp4", "")
        val next = UpnpRenderer.parseMedia("http://nas.local/b.mp4", "")
        assertTrue(UpnpRenderer.autoAdvances(file, next))
        assertTrue("a converted next item still follows", UpnpRenderer.autoAdvances(file, next.copy(live = true)))
        assertFalse(UpnpRenderer.autoAdvances(file.copy(live = true), next))
        assertFalse(UpnpRenderer.autoAdvances(file.copy(lowLatency = true), next))
        assertFalse(UpnpRenderer.autoAdvances(file.copy(kind = TvMediaKind.PHOTO), next))
        assertFalse(UpnpRenderer.autoAdvances(file, next.copy(kind = TvMediaKind.PHOTO)))
        assertFalse(UpnpRenderer.autoAdvances(file.copy(syncUrl = "http://192.168.1.5:8765/castapp/timeline"), next))
        assertFalse(UpnpRenderer.autoAdvances(file.copy(joinedFrom = "http://192.168.1.5:8765"), next))
        assertFalse(UpnpRenderer.autoAdvances(file, next.copy(lowLatency = true)))
        assertTrue(UpnpRenderer.autoAdvances(file.copy(kind = TvMediaKind.AUDIO), next.copy(kind = TvMediaKind.AUDIO)))
    }

    // ---- CastApp's DLNA client -------------------------------------------------------------------------

    private fun castAppTv() = "http://127.0.0.1:${renderer.port}/description.xml".let { location ->
        SsdpBrowser.rendererDevice(location, Dlna.parseDescription(Dlna.fetch(location)!!, location))!!
    }

    private val episode = RendererMedia(url = "http://192.168.1.5:8090/tok/f/1/episode.mkv", mimeType = "video/x-matroska", title = "Episode", subtitle = "TV")

    @Test
    fun theTvsErrorIsAnErrorWithItsReasonAtOnce() = runBlocking {
        renderer.start(0, ssdp = false)
        val device = castAppTv()
        assertTrue(device.isCastAppTv)
        tv.failOnPlay = PlaybackFailure.noVideoDecoder("10-bit H.264 (High 10)")
        val r = DlnaRenderer(device, pairing = null)
        r.connect()
        r.load(episode)
        // No waiting 8 s for a TV that never starts: it said it failed, and why.
        val status = r.refresh()!!
        assertEquals(PlayState.ERROR, status.state)
        assertEquals("The TV has no decoder for 10-bit H.264 (High 10) video", status.reason)

        // A new address clears it, and its first answers don't count the old error.
        tv.failOnPlay = null
        r.load(episode.copy(url = "http://192.168.1.5:8090/tok/tx/1/0.mp4"))
        assertEquals(PlayState.PLAYING, r.refresh()!!.state)

        // Failing while it plays is an error too (not "stopped on the TV").
        tv.fail(PlaybackFailure.network())
        val midway = r.refresh()!!
        assertEquals(PlayState.ERROR, midway.state)
        assertEquals("The TV couldn't load the file", midway.reason)
        r.close()
    }

    @Test
    fun otherTvsMustSayItTwiceAfterAMoment() = runBlocking {
        renderer.start(0, ssdp = false)
        // The same control address, but not a CastApp TV: a Samsung or LG answering ERROR_OCCURRED.
        val device = castAppTv().copy(model = "UE55 Smart TV")
        assertFalse(device.isCastAppTv)
        tv.failOnPlay = PlaybackFailure.unsupportedFormat("MKV")
        val r = DlnaRenderer(device, pairing = null, errorSettleMs = 1500)
        r.connect()
        r.load(episode)
        // Right after Play it may be left over from the file before: still starting.
        assertEquals(PlayState.BUFFERING, r.refresh()!!.state)
        Thread.sleep(1600)
        val status = r.refresh()!!
        assertEquals(PlayState.ERROR, status.state)
        assertNull("only CastApp TV is asked why", status.reason)
        r.close()
    }

    @Test
    fun withoutAnErrorAStopIsStillAStop() = runBlocking {
        renderer.start(0, ssdp = false)
        val r = DlnaRenderer(castAppTv(), pairing = null)
        r.connect()
        r.load(episode)
        assertEquals(PlayState.PLAYING, r.refresh()!!.state)
        tv.stop() // the viewer pressed Back on the TV
        assertEquals(PlayState.STOPPED, r.refresh()!!.state)
        r.close()
    }
}
