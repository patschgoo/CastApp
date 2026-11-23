package io.github.patschgoo.castapp.wear

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastPhase
import io.github.patschgoo.castapp.service.CastState
import io.github.patschgoo.castapp.wearproto.NowPlaying
import io.github.patschgoo.castapp.wearproto.WearCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearStateTest {
    private val tv = CastDevice("tv1", "Living room TV", "192.168.1.5")

    @Test
    fun idleShowsNothingButKeepsTheLastTvForTheMirrorButton() {
        val n = WearState.nowPlaying(CastState.Idle, "Bedroom TV")
        assertFalse(n.active)
        assertEquals("Bedroom TV", n.lastTv)
        assertEquals(NowPlaying(lastTv = "Bedroom TV", revision = WearCodec.REVISION), n)
    }

    @Test
    fun theWatchLearnsThatThisPhoneTakesVolumeLevels() {
        // Without it the watch falls back to one VOLUME_UP / VOLUME_DOWN message per step.
        assertEquals(WearCodec.REVISION, WearState.nowPlaying(CastState.Idle, "").revision)
        assertEquals(WearCodec.REVISION, WearState.nowPlaying(CastState.Active(tv, CastPhase.LIVE, volume = 0.3f), "").revision)
        assertTrue(WearCodec.REVISION >= WearCodec.REVISION_VOLUME_LEVELS)
        val sent = WearState.nowPlaying(CastState.Active(tv, CastPhase.LIVE, volume = 0.3f), "")
        assertEquals(WearCodec.REVISION, WearCodec.decodeState(WearCodec.encodeState(sent))!!.revision)
    }

    @Test
    fun aFileShowsTitleDeviceQueueAndVolume() {
        val state = CastState.Active(tv, CastPhase.LIVE, kind = CastKind.FILE, title = "Holiday", paused = true, queueIndex = 2, queueSize = 7, volume = 0.456f)
        val n = WearState.nowPlaying(state, "")
        assertTrue(n.active)
        assertEquals("Holiday", n.title)
        assertEquals("Living room TV", n.device)
        assertTrue(n.paused)
        assertFalse(n.mirroring)
        assertEquals(2, n.queueIndex)
        assertEquals(7, n.queueSize)
        assertEquals(46, n.volumePercent)
    }

    @Test
    fun mirroringHasNoTitleAndIsMarkedSo() {
        val n = WearState.nowPlaying(CastState.Active(tv, CastPhase.LIVE, kind = CastKind.SCREEN, title = "ignored"), "")
        assertTrue(n.mirroring)
        assertEquals("", n.title)
        assertEquals(-1, n.volumePercent)
    }

    @Test
    fun theVolumeStaysInRange() {
        assertEquals(100, WearState.nowPlaying(CastState.Active(tv, CastPhase.LIVE, volume = 1.7f), "").volumePercent)
        assertEquals(0, WearState.nowPlaying(CastState.Active(tv, CastPhase.LIVE, volume = -0.2f), "").volumePercent)
    }

    @Test
    fun whatTheWatchGetsSurvivesTheEncoding() {
        val state = CastState.Active(tv, CastPhase.LIVE, kind = CastKind.FILE, title = "Tschüss – 再见 \"quoted\"", queueSize = 3, volume = 0.2f)
        val sent = WearState.nowPlaying(state, "Bedroom TV")
        assertEquals(sent, WearCodec.decodeState(WearCodec.encodeState(sent)))
    }

    @Test
    fun theSameCastGivesTheSameStateSoNothingIsSentTwice() {
        // WearSync only sends when the state differs; the playback position must not be part of it.
        val a = CastState.Active(tv, CastPhase.LIVE, kind = CastKind.FILE, title = "x", positionMs = 1000)
        val b = a.copy(positionMs = 9000, positionAt = 42)
        assertEquals(WearState.nowPlaying(a, ""), WearState.nowPlaying(b, ""))
    }
}
