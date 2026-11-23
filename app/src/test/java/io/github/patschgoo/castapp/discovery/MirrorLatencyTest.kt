package io.github.patschgoo.castapp.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorLatencyTest {
    private val shieldCast = CastDevice("cast-1", "Living room", "192.0.2.10", model = "SHIELD Android TV")
    private val shieldTv = CastDevice(
        "dlna-1", "Living room", "192.0.2.10", port = 49595, model = "CastApp TV 4.1.0", kind = DeviceKind.DLNA,
    )

    @Test fun findsCastAppTvOnTheSameBox() {
        val other = shieldTv.copy(id = "dlna-2", host = "192.0.2.11")
        assertEquals(shieldTv, MirrorLatency.fasterTwin(shieldCast, listOf(other, shieldTv)))
        assertNull(MirrorLatency.fasterTwin(shieldCast, listOf(other)))
    }

    @Test fun onlyForGoogleCastWithPicture() {
        assertNull(MirrorLatency.fasterTwin(shieldTv, listOf(shieldTv)))
        assertNull(MirrorLatency.fasterTwin(shieldCast.copy(video = false), listOf(shieldTv)))
    }

    @Test fun knowsAndroidTvBoxes() {
        assertTrue(MirrorLatency.couldRunCastAppTv(shieldCast))
        assertTrue(MirrorLatency.couldRunCastAppTv(shieldCast.copy(model = "Google TV Streamer")))
        assertFalse(MirrorLatency.couldRunCastAppTv(shieldCast.copy(model = "Chromecast")))
        assertFalse(MirrorLatency.couldRunCastAppTv(shieldCast.copy(model = null)))
        assertFalse(MirrorLatency.couldRunCastAppTv(shieldTv))
    }
}
