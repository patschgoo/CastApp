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
        for (action in AutomationApi.ALL_ACTIONS) {
            // once in the activity's filter with data, once without, once in the receiver's filter
            assertEquals("$action in the manifest", 3, declared.count { it == action })
        }
        assertEquals("only known actions are declared", emptySet<String>(), declared.toSet() - AutomationApi.ALL_ACTIONS.toSet())
        assertTrue(text.contains(".automation.AutomationActivity") && text.contains(".automation.AutomationReceiver"))
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
        )) {
            assertTrue("extra $extra is documented", text.contains("`$extra`"))
        }
        for (status in listOf("ok", "pending_user", "disabled", "unauthorized", "invalid_request", "device_not_found", "not_casting", "needs_activity", "failed", "unsupported")) {
            assertTrue("status $status is documented", text.contains("`$status`"))
        }
    }

    @Test
    fun theResultActionIsInTheDocumentationToo() {
        val md = file("../AUTOMATION.md", "AUTOMATION.md")
        assumeTrue(md != null)
        assertTrue(md!!.readText().contains(AutomationApi.ACTION_RESULT))
    }
}
