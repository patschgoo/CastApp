package io.github.patschgoo.castapp.tv

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reasons CastApp TV gives senders when it can't play something. */
class PlaybackFailureTest {
    @Test
    fun namesTheFormat() {
        assertEquals("10-bit H.264 (High 10)", PlaybackFailure.formatName("video/avc", "avc1.6E0033"))
        assertEquals("H.264", PlaybackFailure.formatName("video/avc", "avc1.640028"))
        assertEquals("H.264 High 4:2:2", PlaybackFailure.formatName("video/avc", "avc1.7A0028"))
        assertEquals("H.264", PlaybackFailure.formatName("video/avc", null))
        assertEquals("10-bit HEVC (Main 10)", PlaybackFailure.formatName("video/hevc", "hvc1.2.4.L153.B0"))
        assertEquals("HEVC", PlaybackFailure.formatName("video/hevc", "hev1.1.6.L93.B0"))
        assertEquals("10-bit AV1", PlaybackFailure.formatName("video/av01", "av01.0.08M.10"))
        assertEquals("AV1", PlaybackFailure.formatName("video/av01", "av01.0.04M.08"))
        assertEquals("10-bit VP9", PlaybackFailure.formatName("video/x-vnd.on2.vp9", "vp09.02.10.10"))
        assertEquals("DTS", PlaybackFailure.formatName("audio/vnd.dts", null))
        assertEquals("VC-1", PlaybackFailure.formatName("video/wvc1", null))
        assertNull(PlaybackFailure.formatName(null, null))
    }

    @Test
    fun namesTheFileType() {
        assertEquals("MKV", PlaybackFailure.containerName("http://192.168.1.5:32400/library/parts/611/1/file.mkv?X-Plex-Token=t", null))
        assertEquals("WMV", PlaybackFailure.containerName("http://nas.local/stream?id=4", "video/x-ms-wmv"))
        assertEquals("AVI", PlaybackFailure.containerName("http://nas.local/play/4", "video/x-msvideo"))
        assertNull(PlaybackFailure.containerName("http://nas.local/play/4", null))
    }

    @Test
    fun aDecoderThatNeverClaimedTheFormatIsNoDecoder() {
        // A Shield's H.264 decoder given High 10: Media3 tries it anyway, and it fails to start.
        val e = PlaybackFailure.fromPlayerError(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, "ERROR_CODE_DECODER_INIT_FAILED",
            trackType = C.TRACK_TYPE_VIDEO, claimed = false, format = "10-bit H.264 (High 10)", container = "MKV",
        )
        assertEquals(PlaybackFailure.NO_VIDEO_DECODER, e.code)
        assertEquals("No decoder for 10-bit H.264 (High 10) video on this TV", e.message)
        assertEquals("10-bit H.264 (High 10)", e.format)

        val sound = PlaybackFailure.fromPlayerError(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED",
            trackType = C.TRACK_TYPE_AUDIO, format = "TrueHD",
        )
        assertEquals(PlaybackFailure.NO_AUDIO_DECODER, sound.code)

        // One that claimed it and then broke off: a player error, naming what it was decoding.
        val broke = PlaybackFailure.fromPlayerError(
            PlaybackException.ERROR_CODE_DECODING_FAILED, "ERROR_CODE_DECODING_FAILED",
            trackType = C.TRACK_TYPE_VIDEO, claimed = true, format = "H.264",
        )
        assertEquals(PlaybackFailure.PLAYER_ERROR, broke.code)
        assertTrue(broke.message, broke.message.contains("H.264 video"))
    }

    @Test
    fun sourceAndFileTypeErrors() {
        val http = PlaybackFailure.fromPlayerError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, "ERROR_CODE_IO_BAD_HTTP_STATUS", httpStatus = 404)
        assertEquals(PlaybackFailure.NETWORK, http.code)
        assertEquals(404, http.httpStatus)
        assertEquals("The TV could not load the file: the server answered HTTP 404", http.message)
        assertEquals(
            PlaybackFailure.NETWORK,
            PlaybackFailure.fromPlayerError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT").code,
        )
        val wmv = PlaybackFailure.fromPlayerError(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED", container = "WMV",
        )
        assertEquals(PlaybackFailure.UNSUPPORTED_FORMAT, wmv.code)
        assertEquals("This TV can't open WMV files", wmv.message)
        assertEquals(
            PlaybackFailure.UNSUPPORTED_FORMAT,
            PlaybackFailure.fromPlayerError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, "ERROR_CODE_PARSING_CONTAINER_MALFORMED").code,
        )
        val other = PlaybackFailure.fromPlayerError(PlaybackException.ERROR_CODE_UNSPECIFIED, "ERROR_CODE_UNSPECIFIED")
        assertEquals(PlaybackFailure.PLAYER_ERROR, other.code)
        assertTrue(other.message.contains("ERROR_CODE_UNSPECIFIED"))
    }

    @Test
    fun travelsAsJson() {
        val e = PlaybackFailure.noVideoDecoder("10-bit H.264 (High 10)")
        val back = TvError.fromJson(JSONObject(e.toJson().toString()))
        assertEquals(e, back)
        assertEquals(PlaybackFailure.network(httpStatus = 503), TvError.fromJson(JSONObject(PlaybackFailure.network(httpStatus = 503).toJson().toString())))
        assertNull(TvError.fromJson(null))
        assertNull(TvError.fromJson(JSONObject()))
        assertEquals("No decoder for DTS sound on this TV: it plays without sound", PlaybackFailure.playsWithoutSound("DTS").message)
    }

    @Test
    fun thePhoneSaysItInItsOwnWords() {
        // Unit tests have no app: the English texts.
        assertEquals("The TV has no decoder for DTS sound", PlaybackFailure.describe(PlaybackFailure.noAudioDecoder("DTS")))
        assertEquals("The TV couldn't load the file (HTTP 404)", PlaybackFailure.describe(PlaybackFailure.network(httpStatus = 404)))
        assertEquals("The TV can't open this WMV file", PlaybackFailure.describe(PlaybackFailure.unsupportedFormat("WMV")))
        assertEquals(
            "The TV can only play the sound: it can't read the picture of this file",
            PlaybackFailure.describe(PlaybackFailure.noPicture("AVI")),
        )
        assertEquals("The TV's player stopped with an error", PlaybackFailure.describe(TvError("something_new", "Something new")))
    }
}
