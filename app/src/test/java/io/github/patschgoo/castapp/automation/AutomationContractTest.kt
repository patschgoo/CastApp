package io.github.patschgoo.castapp.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The manifest and AUTOMATION.md must list exactly the actions the parser knows; a renamed or
 * forgotten action would otherwise only show up on a phone.
 */
class AutomationContractTest {
    private fun file(vararg candidates: String): File? = candidates.map(::File).firstOrNull { it.exists() }

    private val manifest get() = file("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")

    @Test
    fun theActivityAndTheReceiverDeclareEveryAction() {
        val xml = manifest
        assumeTrue("manifest not found from ${File(".").absolutePath}", xml != null)
        val text = xml!!.readText()
        val declared = Regex("""android:name="(io\.github\.patschgoo\.castapp\.automation\.[A-Z_]+)"""").findAll(text).map { it.groupValues[1] }.toList()
        for (action in AutomationApi.ALL_ACTIONS - AutomationApi.ACTION_CAST_QUEUE) {
            // once in the activity's filter with data, once without, once in the receiver's filter
            assertEquals("$action in the manifest", 3, declared.count { it == action })
        }
        assertEquals("only known actions are declared", emptySet<String>(), declared.toSet() - AutomationApi.ALL_ACTIONS.toSet() - AutomationApi.ACTION_PLAYBACK)
        // PLAYBACK is not received, only listed in <queries> so that CastApp may send it to the apps that receive it.
        val queries = text.substring(text.indexOf("<queries>"), text.indexOf("</queries>"))
        assertEquals(1, declared.count { it == AutomationApi.ACTION_PLAYBACK })
        assertTrue(queries.contains(AutomationApi.ACTION_PLAYBACK))
        assertTrue(text.contains(".automation.AutomationActivity") && text.contains(".automation.AutomationReceiver"))
    }

    /**
     * CAST_QUEUE without a class name must land on the "Play on TV" sheet (which asks the user), never
     * on the invisible AutomationActivity: that one takes it only by class name. The receiver has it too.
     */
    @Test
    fun aQueueWithoutAClassNameOpensTheSheet() {
        val xml = manifest
        assumeTrue(xml != null)
        val text = xml!!.readText()
        fun block(name: String, end: String): String {
            val start = text.indexOf("android:name=\"$name\"")
            assertTrue("$name is declared", start >= 0)
            return text.substring(start, text.indexOf(end, start))
        }
        val queue = AutomationApi.ACTION_CAST_QUEUE
        assertTrue(block(".automation.PlayOnTvActivity", "</activity>").contains(queue))
        assertTrue(block(".automation.PlayOnTvActivity", "</activity>").contains("android:exported=\"true\""))
        assertTrue(block(".automation.AutomationReceiver", "</receiver>").contains(queue))
        assertTrue(!block(".automation.AutomationActivity", "</activity>").contains(queue))
        assertEquals(2, Regex(Regex.escape("android:name=\"$queue\"")).findAll(text).count())
    }

    @Test
    fun theDocumentationNamesEveryActionAndResultCode() {
        val md = file("../AUTOMATION.md", "AUTOMATION.md")
        assumeTrue("AUTOMATION.md not found", md != null)
        val text = md!!.readText()
        for (action in AutomationApi.ALL_ACTIONS) {
            val short = action.substringAfterLast('.')
            assertTrue("$short is documented", text.contains("`$short`"))
        }
        for (extra in listOf(
            AutomationApi.EXTRA_SECRET, AutomationApi.EXTRA_DEVICE, AutomationApi.EXTRA_DEVICE_ID, AutomationApi.EXTRA_DEVICE_NAME, AutomationApi.EXTRA_MODE,
            AutomationApi.EXTRA_URL, AutomationApi.EXTRA_URI, AutomationApi.EXTRA_TITLE, AutomationApi.EXTRA_MIME, AutomationApi.EXTRA_LEVEL,
            AutomationApi.EXTRA_AMOUNT, AutomationApi.EXTRA_QUALITY, AutomationApi.EXTRA_ENABLED, AutomationApi.EXTRA_REQUEST_ID, AutomationApi.EXTRA_RESULT_PACKAGE,
            AutomationApi.EXTRA_QUEUE, AutomationApi.EXTRA_START_INDEX, AutomationApi.EXTRA_START_POSITION, AutomationApi.EXTRA_REPORT_TO,
        )) {
            assertTrue("extra $extra is documented", text.contains("`$extra`"))
        }
        for (status in listOf("ok", "pending_user", "disabled", "unauthorized", "invalid_request", "device_not_found", "not_casting", "needs_activity", "failed", "unsupported", "cancelled")) {
            assertTrue("status $status is documented", text.contains("`$status`"))
        }
    }

    @Test
    fun theResultActionIsInTheDocumentationToo() {
        val md = file("../AUTOMATION.md", "AUTOMATION.md")
        assumeTrue(md != null)
        assertTrue(md!!.readText().contains(AutomationApi.ACTION_RESULT))
    }

    @Test
    fun thePlaybackBroadcastIsDocumented() {
        val md = file("../AUTOMATION.md", "AUTOMATION.md")
        assumeTrue(md != null)
        val text = md!!.readText()
        assertTrue(text.contains(AutomationApi.ACTION_PLAYBACK))
        assertTrue(text.contains("automation.PlayOnTvActivity"))
        for (word in listOf(
            AutomationApi.PLAYBACK_EXTRA_STATE, AutomationApi.PLAYBACK_EXTRA_ITEM_ID, AutomationApi.PLAYBACK_EXTRA_ITEM_INDEX,
            AutomationApi.PLAYBACK_EXTRA_POSITION, AutomationApi.PLAYBACK_EXTRA_DURATION, AutomationApi.PLAYBACK_EXTRA_DEVICE,
            AutomationApi.PLAYBACK_EXTRA_REASON, AutomationApi.PLAYBACK_EXTRA_MESSAGE,
            AutomationApi.STATE_STARTED, AutomationApi.STATE_PAUSED, AutomationApi.STATE_RESUMED, AutomationApi.STATE_PROGRESS,
            AutomationApi.STATE_STOPPED, AutomationApi.STATE_FINISHED, AutomationApi.STATE_FAILED,
            AutomationApi.REASON_CANNOT_PLAY, AutomationApi.REASON_CONNECTION_LOST, AutomationApi.REASON_CAST_FAILED,
        )) {
            assertTrue("$word is documented", text.contains("`$word`"))
        }
    }
}
