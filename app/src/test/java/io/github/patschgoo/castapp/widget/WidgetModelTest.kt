package io.github.patschgoo.castapp.widget

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastPhase
import io.github.patschgoo.castapp.service.CastState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetModelTest {
    private val tv = CastDevice("tv1", "Living room TV", "192.168.1.5")

    private fun active(vararg change: (CastState.Active) -> CastState.Active): CastState.Active =
        change.fold(CastState.Active(tv, CastPhase.LIVE)) { s, f -> f(s) }

    @Test
    fun idleOffersTheLastTv() {
        val m = WidgetModel.from(CastState.Idle, "Bedroom TV")
        assertEquals(false, m.active)
        assertEquals("Bedroom TV", m.lastTv)
        assertTrue(m.controls(6).isEmpty())
    }

    @Test
    fun mirroringHasVolumeAndStop() {
        val m = WidgetModel.from(active(), null)
        assertEquals(listOf(WidgetControl.VOLUME_DOWN, WidgetControl.VOLUME_UP, WidgetControl.STOP), m.controls(4))
        assertEquals("Living room TV", m.deviceName)
    }

    @Test
    fun aFileQueueHasPlaybackButtonsAndVolumeWhenThereIsRoom() {
        val m = WidgetModel.from(active({ it.copy(kind = CastKind.FILE, queueSize = 5, queueIndex = 1, title = "Song") }), null)
        assertEquals(listOf(WidgetControl.PREVIOUS, WidgetControl.PLAY_PAUSE, WidgetControl.NEXT, WidgetControl.STOP), m.controls(4))
        assertEquals(
            listOf(
                WidgetControl.PREVIOUS, WidgetControl.PLAY_PAUSE, WidgetControl.NEXT,
                WidgetControl.VOLUME_DOWN, WidgetControl.VOLUME_UP, WidgetControl.STOP,
            ),
            m.controls(6),
        )
    }

    @Test
    fun singleFilesAndPhotosSkipWhatDoesNotApply() {
        val single = WidgetModel.from(active({ it.copy(kind = CastKind.FILE) }), null)
        assertEquals(listOf(WidgetControl.PLAY_PAUSE, WidgetControl.STOP), single.controls(4))
        val photos = WidgetModel.from(active({ it.copy(kind = CastKind.FILE, isPhoto = true, queueSize = 3) }), null)
        assertEquals(listOf(WidgetControl.PREVIOUS, WidgetControl.NEXT, WidgetControl.STOP), photos.controls(4))
    }

    @Test
    fun whileConnectingOnlyStopIsOffered() {
        val m = WidgetModel.from(active({ it.copy(phase = CastPhase.CONNECTING, kind = CastKind.FILE) }), null)
        assertEquals(listOf(WidgetControl.STOP), m.controls(6))
    }

    @Test
    fun theWidgetIsOnlyRedrawnForWhatItShows() {
        val a = active({ it.copy(kind = CastKind.FILE, title = "Song", positionMs = 1000, volume = 0.3f) })
        val sameLater = a.copy(positionMs = 5000, durationMs = 9000, volume = 0.5f, positionAt = 77)
        assertEquals(WidgetModel.from(a, "TV"), WidgetModel.from(sameLater, "TV"))
        assertNotEquals(WidgetModel.from(a, "TV"), WidgetModel.from(a.copy(paused = true), "TV"))
        assertNotEquals(WidgetModel.from(a, "TV"), WidgetModel.from(a.copy(title = "Other"), "TV"))
    }
}
