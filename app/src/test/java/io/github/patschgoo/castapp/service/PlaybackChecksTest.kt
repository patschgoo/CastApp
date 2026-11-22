package io.github.patschgoo.castapp.service

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceKind
import io.github.patschgoo.castapp.dlna.Dlna
import io.github.patschgoo.castapp.settings.ConvertMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which ways to deliver an item each TV gets, and what each TV is told about the file. */
class PlaybackChecksTest {
    private val chromecast = CastDevice("cc", "Living room", "10.0.0.2", kind = DeviceKind.CAST)
    private val samsung = CastDevice("uuid:tv", "Samsung", "10.0.0.3", 9197, model = "UE55", kind = DeviceKind.DLNA)
    private val castAppTv = CastDevice("uuid:shield", "Shield", "10.0.0.4", 49152, model = "CastApp TV (SHIELD)", kind = DeviceKind.DLNA)
    private val roku = CastDevice("roku", "Roku", "10.0.0.5", 8060, kind = DeviceKind.ROKU)

    /** A Plex / Jellyfin video: the server's converted HLS stream, and its file as it is. */
    private val plexItem = MediaItem(
        title = "Inuyasha 01",
        uri = "http://10.0.0.9:32400/video/:/transcode/universal/start.m3u8?X-Plex-Token=t",
        mimeType = "application/x-mpegurl",
        serverRef = "plex:1",
        original = "http://10.0.0.9:32400/library/parts/1/file.mkv?X-Plex-Token=t",
        originalMime = "video/x-matroska",
    )
    private val smbMkv = MediaItem("SpongeBob", "smb://share/TV/SpongeBob.mkv", "video/x-matroska")

    private fun plan(item: MediaItem, target: CastDevice, convert: ConvertMode = ConvertMode.AUTO, picked: Boolean = false) =
        PlaybackChecks.plan(item, target, convert, picked)

    @Test
    fun serverItemsOnEachKindOfTv() {
        // Chromecast: the server's stream only, never the original file.
        assertEquals(listOf(Delivery.PROXY, Delivery.DIRECT), plan(plexItem, chromecast))
        // CastApp TV: the original file first, the stream as the fallback.
        assertEquals(listOf(Delivery.ORIGINAL, Delivery.PROXY, Delivery.DIRECT), plan(plexItem, castAppTv))
        // Other DLNA TVs: the stream first (many take no HLS), the original file as the last try.
        assertEquals(listOf(Delivery.PROXY, Delivery.DIRECT, Delivery.ORIGINAL), plan(plexItem, samsung))
        // Roku and AirPlay play the stream.
        assertEquals(listOf(Delivery.PROXY, Delivery.DIRECT), plan(plexItem, roku))
    }

    @Test
    fun originalFileOnlyForVideosTheTvCanFetchItself() {
        val withLogin = plexItem.copy(headers = mapOf("Authorization" to "Basic x"))
        assertEquals(listOf(Delivery.PROXY), plan(withLogin, samsung))
        assertEquals(listOf(Delivery.PROXY), plan(withLogin, castAppTv))
        val song = plexItem.copy(mimeType = "audio/mpeg", uri = "http://10.0.0.9/a.mp3")
        assertFalse(Delivery.ORIGINAL in plan(song, samsung))
        assertFalse(Delivery.ORIGINAL in plan(song, castAppTv))
    }

    @Test
    fun filesKeepTheirPlans() {
        assertEquals(listOf(Delivery.SERVE, Delivery.CONVERT), plan(smbMkv, chromecast))
        assertEquals(listOf(Delivery.SERVE, Delivery.CONVERT), plan(smbMkv, samsung))
        assertEquals(listOf(Delivery.SERVE, Delivery.CONVERT), plan(smbMkv, castAppTv))
        assertEquals(listOf(Delivery.SERVE), plan(smbMkv, chromecast, ConvertMode.OFF))
        assertEquals(listOf(Delivery.CONVERT), plan(smbMkv, chromecast, ConvertMode.ALWAYS))
        // A quality or sound track picked by hand: converting first, the original as the fallback.
        assertEquals(listOf(Delivery.CONVERT, Delivery.SERVE), plan(smbMkv, samsung, picked = true))
        val web = MediaItem("Clip", "https://example.com/clip.mp4", "video/mp4")
        assertEquals(listOf(Delivery.DIRECT, Delivery.PROXY, Delivery.CONVERT), plan(web, chromecast))
    }

    @Test
    fun addedScreensGetTheOriginalFileOnlyWhereTheirPlanHasIt() {
        // The first screen (a CastApp TV) plays the original file.
        assertEquals(
            listOf(Delivery.ORIGINAL, Delivery.PROXY, Delivery.DIRECT),
            PlaybackChecks.extraScreenOptions(Delivery.ORIGINAL, plan(plexItem, castAppTv), castAppTv),
        )
        assertEquals(
            listOf(Delivery.PROXY, Delivery.DIRECT),
            PlaybackChecks.extraScreenOptions(Delivery.ORIGINAL, plan(plexItem, chromecast), chromecast),
        )
        // A Samsung added keeps the original file, as its last try.
        assertEquals(
            listOf(Delivery.PROXY, Delivery.DIRECT, Delivery.ORIGINAL),
            PlaybackChecks.extraScreenOptions(Delivery.ORIGINAL, plan(plexItem, samsung), samsung),
        )
        // The first screen plays the stream: the same for the others, then their own ways.
        assertEquals(
            listOf(Delivery.PROXY, Delivery.DIRECT, Delivery.ORIGINAL),
            PlaybackChecks.extraScreenOptions(Delivery.PROXY, plan(plexItem, samsung), samsung),
        )
        assertEquals(
            listOf(Delivery.DIRECT, Delivery.ORIGINAL, Delivery.PROXY),
            PlaybackChecks.extraScreenOptions(Delivery.DIRECT, plan(plexItem, castAppTv), castAppTv),
        )
        // A conversion is shared; no second one is started for another screen.
        assertEquals(
            listOf(Delivery.CONVERT, Delivery.SERVE),
            PlaybackChecks.extraScreenOptions(Delivery.CONVERT, plan(smbMkv, samsung), samsung),
        )
    }

    @Test
    fun mkvIsWebmOnlyForChromecasts() {
        assertEquals("video/webm", PlaybackChecks.mimeFor("video/x-matroska", DeviceKind.CAST))
        assertEquals("video/webm", PlaybackChecks.mimeFor("video/mkv", DeviceKind.CAST))
        assertEquals("video/x-matroska", PlaybackChecks.mimeFor("video/x-matroska", DeviceKind.DLNA))
        assertEquals("video/x-matroska", PlaybackChecks.mimeFor("video/mkv", DeviceKind.DLNA))
        assertEquals("video/x-matroska", PlaybackChecks.mimeFor("video/x-matroska", DeviceKind.ROKU))
        // The other corrections stay the same for every TV.
        for (kind in DeviceKind.entries) {
            assertEquals("video/mp4", PlaybackChecks.mimeFor("video/quicktime", kind))
            assertEquals("video/mp4", PlaybackChecks.mimeFor("video/3gpp", kind))
            assertEquals("video/mp4", PlaybackChecks.mimeFor("application/octet-stream", kind))
            assertEquals("video/mp2t", PlaybackChecks.mimeFor("video/mp2t", kind))
            assertEquals("video/x-msvideo", PlaybackChecks.mimeFor("video/x-msvideo", kind))
        }
    }

    @Test
    fun dlnaTvIsToldMatroskaInItsMetadata() {
        val url = "http://10.0.0.10:8080/t/file/1/SpongeBob.mkv"
        val forSamsung = Dlna.didl("SpongeBob", url, PlaybackChecks.mimeFor(smbMkv.mimeType, samsung.kind), live = false)
        assertTrue(forSamsung, forSamsung.contains("protocolInfo=\"http-get:*:video/x-matroska:DLNA.ORG_OP=01;"))
        assertTrue(forSamsung.contains("object.item.videoItem"))
        val forCastAppTv = Dlna.didl("SpongeBob", url, PlaybackChecks.mimeFor(smbMkv.mimeType, castAppTv.kind), live = false)
        assertTrue(forCastAppTv.contains("protocolInfo=\"http-get:*:video/x-matroska:"))
        assertEquals("video/webm", PlaybackChecks.mimeFor(smbMkv.mimeType, chromecast.kind))
    }

    @Test
    fun chromecastWithDtsSound() {
        val dts = AudioTrackInfo(0, "ger", "audio/vnd.dts", 6)
        val dtsHd = AudioTrackInfo(1, "eng", "audio/vnd.dts.hd", 8)
        val trueHd = AudioTrackInfo(0, "eng", "audio/true-hd", 8)
        val ac3 = AudioTrackInfo(1, "eng", "audio/ac3", 6)
        assertEquals(CastSoundWarning("DTS", otherTrack = true), PlaybackChecks.castSoundWarning(DeviceKind.CAST, Delivery.SERVE, listOf(dts, ac3)))
        assertEquals(CastSoundWarning("TrueHD", otherTrack = true), PlaybackChecks.castSoundWarning(DeviceKind.CAST, Delivery.DIRECT, listOf(trueHd, ac3)))
        // No other track it could play: CastApp TV or the media server instead.
        assertEquals(CastSoundWarning("DTS", otherTrack = false), PlaybackChecks.castSoundWarning(DeviceKind.CAST, Delivery.SERVE, listOf(dts)))
        assertEquals(CastSoundWarning("DTS", otherTrack = false), PlaybackChecks.castSoundWarning(DeviceKind.CAST, Delivery.SERVE, listOf(dts, dtsHd)))
        // Nothing to say: the TV plays the first track, which is fine; or the phone converts; or it's no Chromecast.
        assertNull(PlaybackChecks.castSoundWarning(DeviceKind.CAST, Delivery.SERVE, listOf(ac3.copy(ordinal = 0), dts.copy(ordinal = 1))))
        assertNull(PlaybackChecks.castSoundWarning(DeviceKind.CAST, Delivery.CONVERT, listOf(dts, ac3)))
        assertNull(PlaybackChecks.castSoundWarning(DeviceKind.CAST, Delivery.PROXY, listOf(dts, ac3)))
        assertNull(PlaybackChecks.castSoundWarning(DeviceKind.DLNA, Delivery.SERVE, listOf(dts, ac3)))
        assertNull(PlaybackChecks.castSoundWarning(DeviceKind.CAST, Delivery.SERVE, emptyList()))
    }

    @Test
    fun soundChromecastsCantPlay() {
        for (mime in listOf("audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.uhd", "audio/dts", "audio/true-hd", "audio/truehd")) {
            assertTrue(mime, PlaybackChecks.castCantPlaySound(mime))
        }
        for (mime in listOf("audio/ac3", "audio/eac3", "audio/mp4a-latm", "audio/mpeg", "audio/opus", "audio/flac")) {
            assertFalse(mime, PlaybackChecks.castCantPlaySound(mime))
        }
    }
}
