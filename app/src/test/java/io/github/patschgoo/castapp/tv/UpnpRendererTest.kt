package io.github.patschgoo.castapp.tv

import io.github.patschgoo.castapp.dlna.Dlna
import io.github.patschgoo.castapp.dlna.SsdpBrowser
import io.github.patschgoo.castapp.render.DlnaRenderer
import io.github.patschgoo.castapp.render.MediaRenderer
import io.github.patschgoo.castapp.render.PlayState
import io.github.patschgoo.castapp.render.RendererMedia
import io.github.patschgoo.castapp.render.RendererStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** The TV receiver's DLNA server, driven by CastApp's own DLNA client. */
class UpnpRendererTest {
    private class FakeTv : RendererBackend {
        val calls = ArrayList<String>()
        var current: TvMedia? = null
        override var snapshot = TvSnapshot()
        override fun setMedia(media: TvMedia) { calls += "set"; current = media; snapshot = snapshot.copy(state = TransportState.STOPPED) }
        override fun play() { calls += "play"; snapshot = snapshot.copy(state = TransportState.PLAYING, positionMs = 61_000, durationMs = 3_600_000) }
        override fun pause() { calls += "pause"; snapshot = snapshot.copy(state = TransportState.PAUSED) }
        override fun stop() { calls += "stop"; snapshot = snapshot.copy(state = TransportState.STOPPED) }
        override fun seek(positionMs: Long) { calls += "seek:$positionMs" }
        override fun setVolume(percent: Int) { calls += "vol:$percent"; snapshot = snapshot.copy(volume = percent) }
        override fun setMuted(muted: Boolean) { calls += "mute:$muted"; snapshot = snapshot.copy(muted = muted) }
    }

    private val tv = FakeTv()
    private val renderer = UpnpRenderer("Living Room Fire TV", "1234-abcd", tv, "3.1.0")

    @After
    fun tearDown() = renderer.stop()

    @Test
    fun phoneControlsTheTvReceiver() = runBlocking {
        renderer.start(0, ssdp = false)
        val location = "http://127.0.0.1:${renderer.port}/description.xml"
        val description = Dlna.parseDescription(Dlna.fetch(location)!!, location)
        val device = SsdpBrowser.rendererDevice(location, description)
        assertNotNull(device)
        device!!
        assertEquals("Living Room Fire TV", device.name)
        assertTrue("recognised as CastApp TV: ${device.model}", device.isCastAppTv)
        assertEquals("CastApp TV", device.kindLabel)

        val statuses = ArrayList<RendererStatus>()
        val r = DlnaRenderer(device)
        r.listener = object : MediaRenderer.Listener {
            override fun onStatus(status: RendererStatus) { synchronized(statuses) { statuses += status } }
            override fun onLost(error: Throwable?) {}
            override fun onClosedOnDevice() {}
        }
        r.connect()
        r.load(
            RendererMedia(
                url = "http://192.168.1.5:8090/tok/stream.mp4?x=1&y=2",
                mimeType = "video/mp4",
                title = "Phone screen & more",
                subtitle = "TV",
                live = true,
                subtitleSrtUrl = "http://192.168.1.5:8090/tok/sub/0/0.srt",
                lowLatency = true,
            ),
        )
        val m = tv.current!!
        assertEquals("http://192.168.1.5:8090/tok/stream.mp4?x=1&y=2", m.uri)
        assertEquals("Phone screen & more", m.title)
        assertEquals(TvMediaKind.VIDEO, m.kind)
        assertEquals("video/mp4", m.mimeType)
        assertEquals("http://192.168.1.5:8090/tok/sub/0/0.srt", m.subtitleUri)
        assertTrue(m.live)
        assertTrue(m.lowLatency)
        assertTrue(tv.calls.containsAll(listOf("stop", "set", "play")))

        val status = r.refresh()!!
        assertEquals(PlayState.PLAYING, status.state)
        assertEquals(61_000L, status.positionMs)
        assertEquals(3_600_000L, status.durationMs)

        r.pause()
        r.seek(125_000)
        r.setVolume(0.3)
        r.setMuted(true)
        assertTrue(tv.calls.containsAll(listOf("pause", "seek:125000", "vol:30", "mute:true")))
        assertEquals(PlayState.PAUSED, r.refresh()!!.state)
        r.stop()
        assertEquals("stop", tv.calls.last())
        r.close()
    }

    @Test
    fun servesServiceDescriptionsAndRejectsUnknownActions() {
        renderer.start(0, ssdp = false)
        for (path in listOf("AVTransport", "RenderingControl", "ConnectionManager")) {
            val xml = Dlna.fetch("http://127.0.0.1:${renderer.port}/$path.xml")!!
            assertTrue(xml.contains("<scpd"))
        }
        val proto = Dlna.call("http://127.0.0.1:${renderer.port}/control/ConnectionManager", UpnpRenderer.CONNECTION_MANAGER, "GetProtocolInfo", emptyList())
        assertTrue(Dlna.tag(proto, "Sink")!!.contains("http-get:*:video/mp4:*"))
        val failed = try {
            Dlna.call("http://127.0.0.1:${renderer.port}/control/AVTransport", Dlna.AV_TRANSPORT, "Explode", listOf("InstanceID" to "0"))
            false
        } catch (e: Dlna.SoapException) {
            e.message!!.contains("Invalid Action")
        }
        assertTrue(failed)
    }

    @Test
    fun phoneTellsTheTvWhichTrackInsideThePlexFileToShow() = runBlocking {
        renderer.start(0, ssdp = false)
        val location = "http://127.0.0.1:${renderer.port}/description.xml"
        val device = SsdpBrowser.rendererDevice(location, Dlna.parseDescription(Dlna.fetch(location)!!, location))!!
        val r = DlnaRenderer(device)
        r.connect()
        val hint = TextTrackHint(track = 1, language = "eng", title = "Full Subtitles", languages = listOf("de", "en"))
        r.load(
            RendererMedia(
                url = "http://192.168.1.5:32400/library/parts/611/1/file.mkv?X-Plex-Token=t",
                mimeType = "video/x-matroska",
                title = "Vela · S1 E1",
                subtitle = "TV",
                subtitleTrack = hint.encode(),
            ),
        )
        val m = tv.current!!
        assertEquals(hint, TextTrackHint.decode(m.subtitleTrack))
        assertEquals(null, m.subtitleUri) // no copy of the track next to the file
        r.close()
    }

    @Test
    fun phoneTellsOnlyCastAppTvsWhichSoundTrackToPlay() = runBlocking {
        renderer.start(0, ssdp = false)
        val location = "http://127.0.0.1:${renderer.port}/description.xml"
        val castAppTv = SsdpBrowser.rendererDevice(location, Dlna.parseDescription(Dlna.fetch(location)!!, location))!!
        val media = RendererMedia(
            url = "http://192.168.1.5:32400/library/parts/611/1/file.mkv?X-Plex-Token=t",
            mimeType = "video/x-matroska",
            title = "Vela · S1 E1",
            subtitle = "TV",
            subtitleTrack = "lang=eng",
            audioTrack = TrackHint(track = 1, language = "ger", title = "German Dub").encode(),
            syncUrl = "http://192.168.1.5:8091/sync",
        )
        DlnaRenderer(castAppTv).apply { connect(); load(media); close() }
        val m = tv.current!!
        assertEquals(TrackHint(track = 1, language = "ger", title = "German Dub"), TrackHint.decode(m.audioTrack))
        assertEquals("lang=eng", m.subtitleTrack)

        // The same renderer seen as a plain DLNA TV: exactly the metadata such TVs always got.
        val samsung = castAppTv.copy(model = "UE55 Samsung")
        assertFalse(samsung.isCastAppTv)
        DlnaRenderer(samsung).apply { connect(); load(media); close() }
        val plain = tv.current!!
        assertEquals(Dlna.didl(media.title, media.url, media.mimeType, false), plain.metadata)
        assertEquals(null, plain.audioTrack)
        assertFalse(plain.metadata.contains("castapp"))
    }

    @Test
    fun anotherSenderHandsOverSeveralSubtitleFilesAndTheSoundTrack() {
        renderer.start(0, ssdp = false)
        val files = listOf(
            SubtitleFile("http://10.0.0.7:43210/s/1.ass?k=a&f=1", "en", "Signs & Songs"),
            SubtitleFile("http://10.0.0.7:43210/s/2?k=a", "de", "Full", "text/x-ssa"),
        )
        val didl = Dlna.didl(
            "Vela 01", "http://10.0.0.7:43210/v/1.mkv?k=a", "video/x-matroska", false,
            subtitleTrack = TrackHint(languages = listOf("de", "en")).encode(),
            audioTrack = TrackHint(languages = listOf("ja")).encode(),
            subtitleFiles = SubtitleFiles.encode(files),
        )
        // As a sender would, straight over SOAP: the metadata travels XML-escaped once more.
        Dlna.call(
            "http://127.0.0.1:${renderer.port}/control/AVTransport", Dlna.AV_TRANSPORT, "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to "http://10.0.0.7:43210/v/1.mkv?k=a", "CurrentURIMetaData" to didl),
        )
        val m = tv.current!!
        assertEquals(files, m.subtitleFiles)
        assertEquals("prefer=ja", m.audioTrack)
        val plan = SubtitleFiles.plan(m)
        assertEquals(listOf("text/x-ssa", "text/x-ssa"), plan.files.map { it.mime })
        assertEquals(1, plan.shown) // German first
    }

    @Test
    fun parsesMetadataFromOtherApps() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="1" parentID="0" restricted="1"><dc:title>Song &amp; Dance</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo="http-get:*:audio/mpeg:DLNA.ORG_OP=01">http://nas/song.mp3</res></item></DIDL-Lite>"""
        val m = UpnpRenderer.parseMedia("http://nas/song.mp3", didl)
        assertEquals("Song & Dance", m.title)
        assertEquals(TvMediaKind.AUDIO, m.kind)
        assertFalse(m.live)
        assertFalse(m.lowLatency)
        val bare = UpnpRenderer.parseMedia("http://host/path/clip.mkv", "")
        assertEquals("clip.mkv", bare.title)
        assertEquals(TvMediaKind.VIDEO, bare.kind)
    }

    @Test
    fun answersSsdpSearches() {
        val bound = try {
            renderer.start(0, ssdp = true, interfaces = emptyList())
            true
        } catch (_: Exception) {
            false
        }
        assumeTrue(bound)
        val search = Dlna.searchMessage(Dlna.MEDIA_RENDERER).toByteArray()
        DatagramSocket().use { s ->
            s.soTimeout = 3000
            s.send(DatagramPacket(search, search.size, InetAddress.getLoopbackAddress(), Dlna.SSDP_PORT))
            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)
            val got = try { s.receive(packet); true } catch (_: SocketTimeoutException) { false }
            assumeTrue("port 1900 not usable here", got)
            val headers = Dlna.parseSsdp(String(buf, 0, packet.length))
            assertEquals(Dlna.MEDIA_RENDERER, headers["st"])
            assertTrue(headers["location"]!!.endsWith(":${renderer.port}/description.xml"))
            assertEquals("uuid:1234-abcd::${Dlna.MEDIA_RENDERER}", headers["usn"])
        }
    }
}
