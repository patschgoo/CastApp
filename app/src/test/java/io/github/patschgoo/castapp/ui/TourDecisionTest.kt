package io.github.patschgoo.castapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TourDecisionTest {
    private val launcher = setOf(TourDecision.CATEGORY_LAUNCHER)

    @Test
    fun launcherStartIsLauncher() {
        assertEquals(LaunchSource.LAUNCHER, TourDecision.sourceOf(TourDecision.ACTION_MAIN, launcher))
    }

    @Test
    fun tileLongPressIsTile() {
        assertEquals(LaunchSource.TILE, TourDecision.sourceOf(TourDecision.ACTION_QS_TILE_PREFERENCES, emptySet()))
    }

    @Test
    fun shareAndOpenWithAreShare() {
        assertEquals(LaunchSource.SHARE, TourDecision.sourceOf("android.intent.action.SEND", emptySet()))
        assertEquals(LaunchSource.SHARE, TourDecision.sourceOf("android.intent.action.SEND_MULTIPLE", emptySet()))
        assertEquals(LaunchSource.SHARE, TourDecision.sourceOf("android.intent.action.VIEW", emptySet()))
    }

    @Test
    fun customActionsAreAutomation() {
        assertEquals(LaunchSource.AUTOMATION, TourDecision.sourceOf("io.github.patschgoo.castapp.action.CAST", emptySet()))
        assertEquals(LaunchSource.AUTOMATION, TourDecision.sourceOf("net.dinglisch.android.tasker.ACTION", launcher))
        // The automation API's actions (AutomationActivity, see AUTOMATION.md).
        assertEquals(LaunchSource.AUTOMATION, TourDecision.sourceOf("io.github.patschgoo.castapp.automation.MIRROR", setOf("android.intent.category.DEFAULT")))
        assertEquals(LaunchSource.AUTOMATION, TourDecision.sourceOf("io.github.patschgoo.castapp.automation.CAST_URL", emptySet()))
    }

    @Test
    fun explicitIntentsWithoutActionAreOther() {
        // Widget, Cast tile tap, notification, ProjectionActivity: plain explicit intents.
        assertEquals(LaunchSource.OTHER, TourDecision.sourceOf(null, emptySet()))
        // MAIN without the launcher category (e.g. started by another app by component).
        assertEquals(LaunchSource.OTHER, TourDecision.sourceOf(TourDecision.ACTION_MAIN, emptySet()))
    }

    @Test
    fun theSourceExtraWins() {
        assertEquals(LaunchSource.WIDGET, TourDecision.sourceOf(null, emptySet(), sourceExtra = "WIDGET"))
        assertEquals(LaunchSource.AUTOMATION, TourDecision.sourceOf(TourDecision.ACTION_MAIN, launcher, sourceExtra = "automation"))
        // Unknown values are ignored.
        assertEquals(LaunchSource.LAUNCHER, TourDecision.sourceOf(TourDecision.ACTION_MAIN, launcher, sourceExtra = "nonsense"))
    }

    @Test
    fun showsOnlyOnTheFirstLauncherStartOfAPhone() {
        assertTrue(TourDecision.shouldShow(seen = false, isTv = false, source = LaunchSource.LAUNCHER))
        assertFalse(TourDecision.shouldShow(seen = true, isTv = false, source = LaunchSource.LAUNCHER))
        assertFalse(TourDecision.shouldShow(seen = false, isTv = true, source = LaunchSource.LAUNCHER))
        for (source in LaunchSource.entries - LaunchSource.LAUNCHER) {
            assertFalse(source.name, TourDecision.shouldShow(seen = false, isTv = false, source = source))
        }
    }
}
