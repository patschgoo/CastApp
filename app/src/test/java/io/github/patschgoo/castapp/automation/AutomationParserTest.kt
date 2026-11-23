package io.github.patschgoo.castapp.automation

import io.github.patschgoo.castapp.automation.AutomationApi as Api
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationParserTest {
    private val open = AutomationConfig(enabled = true, secret = "")
    private val locked = AutomationConfig(enabled = true, secret = "s3cret")

    private fun parse(action: String?, extras: Map<String, Any?> = emptyMap(), config: AutomationConfig = open, data: String? = null) =
        AutomationParser.parse(action, MapExtras(extras, data), config)

    private fun accepted(action: String, extras: Map<String, Any?> = emptyMap(), config: AutomationConfig = open, data: String? = null): AutomationCommand {
        val p = parse(action, extras, config, data)
        assertTrue("expected a command, got $p", p is ParsedRequest.Accepted)
        return (p as ParsedRequest.Accepted).command
    }

    private fun rejectedWith(p: ParsedRequest): AutomationResult {
        assertTrue("expected a rejection, got $p", p is ParsedRequest.Rejected)
        return (p as ParsedRequest.Rejected).result
    }

    // ---- the switch and the secret --------------------------------------------------------------

    @Test
    fun everythingIsRefusedWhileTheSwitchIsOff() {
        for (action in Api.ALL_ACTIONS) {
            val r = rejectedWith(parse(action, config = AutomationConfig(enabled = false, secret = "")))
            assertEquals(AutomationResult.CODE_DISABLED, r.code)
        }
    }

    @Test
    fun theSwitchIsCheckedBeforeTheSecretAndBeforeTheAction() {
        val off = AutomationConfig(enabled = false, secret = "x")
        assertEquals(AutomationResult.CODE_DISABLED, rejectedWith(parse("nonsense", config = off)).code)
        assertEquals(AutomationResult.CODE_DISABLED, rejectedWith(parse(Api.ACTION_STOP, mapOf(Api.EXTRA_SECRET to "x"), off)).code)
    }

    @Test
    fun aMissingOrWrongSecretIsUnauthorized() {
        assertEquals(AutomationResult.CODE_UNAUTHORIZED, rejectedWith(parse(Api.ACTION_STOP, config = locked)).code)
        assertEquals(AutomationResult.CODE_UNAUTHORIZED, rejectedWith(parse(Api.ACTION_STOP, mapOf(Api.EXTRA_SECRET to "wrong"), locked)).code)
        assertEquals(AutomationResult.CODE_UNAUTHORIZED, rejectedWith(parse(Api.ACTION_STOP, mapOf(Api.EXTRA_SECRET to ""), locked)).code)
        assertEquals(AutomationResult.CODE_UNAUTHORIZED, rejectedWith(parse(Api.ACTION_STOP, mapOf(Api.EXTRA_SECRET to "s3cret "), locked)).code)
        // a wrong secret says nothing about the rest of the request, even if that is invalid too
        assertEquals(AutomationResult.CODE_UNAUTHORIZED, rejectedWith(parse("nonsense", mapOf(Api.EXTRA_SECRET to "no"), locked)).code)
    }

    @Test
    fun theRightSecretIsAccepted() {
        assertEquals(AutomationCommand.Stop, accepted(Api.ACTION_STOP, mapOf(Api.EXTRA_SECRET to "s3cret"), locked))
    }

    @Test
    fun aSecretSentAsANumberStillMatches() {
        val numeric = AutomationConfig(enabled = true, secret = "123456")
        assertEquals(AutomationCommand.Stop, accepted(Api.ACTION_STOP, mapOf(Api.EXTRA_SECRET to 123456), numeric))
    }

    @Test
    fun withoutASecretInSettingsNothingHasToBeSent() {
        assertEquals(AutomationCommand.Stop, accepted(Api.ACTION_STOP))
        assertEquals(AutomationCommand.Stop, accepted(Api.ACTION_STOP, mapOf(Api.EXTRA_SECRET to "whatever")))
    }

    @Test
    fun anUnknownActionIsInvalid() {
        val r = rejectedWith(parse("io.github.patschgoo.castapp.automation.FLY"))
        assertEquals(AutomationResult.CODE_INVALID, r.code)
        assertEquals("invalid_request", r.status)
        assertEquals(AutomationResult.CODE_INVALID, rejectedWith(parse(null)).code)
        assertEquals(AutomationResult.CODE_INVALID, rejectedWith(parse("android.intent.action.MAIN")).code)
    }

    @Test
    fun theSecretNeverAppearsInAnAnswer() {
        val r = rejectedWith(parse(Api.ACTION_VOLUME_SET, mapOf(Api.EXTRA_SECRET to "s3cret", Api.EXTRA_LEVEL to "loud"), locked))
        assertFalse(r.message.contains("s3cret"))
    }

    // ---- simple actions ------------------------------------------------------------------------------

    @Test
    fun simpleActionsNeedNoExtras() {
        assertEquals(AutomationCommand.Stop, accepted(Api.ACTION_STOP))
        assertEquals(AutomationCommand.PlayPause, accepted(Api.ACTION_PLAY_PAUSE))
        assertEquals(AutomationCommand.Play, accepted(Api.ACTION_PLAY))
        assertEquals(AutomationCommand.Pause, accepted(Api.ACTION_PAUSE))
        assertEquals(AutomationCommand.Next, accepted(Api.ACTION_NEXT))
        assertEquals(AutomationCommand.Previous, accepted(Api.ACTION_PREVIOUS))
        assertEquals(AutomationCommand.Mute, accepted(Api.ACTION_MUTE))
    }

    // ---- mirror -------------------------------------------------------------------------------------------

    @Test
    fun mirrorDefaultsToTheLastTvAndTheWholeScreen() {
        val c = accepted(Api.ACTION_MIRROR) as AutomationCommand.Mirror
        assertTrue(c.device.isLast)
        assertEquals(MirrorMode.SCREEN, c.mode)
    }

    @Test
    fun mirrorTakesADeviceAndAMode() {
        val c = accepted(Api.ACTION_MIRROR, mapOf(Api.EXTRA_DEVICE to "Living room", Api.EXTRA_MODE to "AUDIO")) as AutomationCommand.Mirror
        assertEquals(DeviceRef(either = "Living room"), c.device)
        assertEquals(MirrorMode.AUDIO, c.mode)
        val byId = accepted(Api.ACTION_MIRROR, mapOf(Api.EXTRA_DEVICE_ID to "abc", Api.EXTRA_DEVICE_NAME to "TV", Api.EXTRA_MODE to "app")) as AutomationCommand.Mirror
        assertEquals(DeviceRef(id = "abc", name = "TV"), byId.device)
        assertEquals(MirrorMode.APP, byId.mode)
    }

    @Test
    fun mirrorRejectsAnUnknownMode() {
        val r = rejectedWith(parse(Api.ACTION_MIRROR, mapOf(Api.EXTRA_MODE to "hologram")))
        assertEquals(AutomationResult.CODE_INVALID, r.code)
    }

    @Test
    fun blankDeviceExtrasCountAsMissing() {
        val c = accepted(Api.ACTION_MIRROR, mapOf(Api.EXTRA_DEVICE to "   ", Api.EXTRA_DEVICE_ID to "")) as AutomationCommand.Mirror
        assertTrue(c.device.isLast)
    }

    // ---- cast a URL ---------------------------------------------------------------------------------------

    @Test
    fun castUrlNeedsAnAddress() {
        assertEquals(AutomationResult.CODE_INVALID, rejectedWith(parse(Api.ACTION_CAST_URL)).code)
        assertEquals(AutomationResult.CODE_INVALID, rejectedWith(parse(Api.ACTION_CAST_URL, mapOf(Api.EXTRA_URL to "  "))).code)
    }

    @Test
    fun castUrlOnlyTakesHttpAndHttps() {
        for (bad in listOf("ftp://host/video.mp4", "javascript:alert(1)", "file:///sdcard/a.mp4", "content://x/1", "example.com/video.mp4", "http://", "https:// spaces .mp4", "intent://x#Intent;end")) {
            val r = rejectedWith(parse(Api.ACTION_CAST_URL, mapOf(Api.EXTRA_URL to bad)))
            assertEquals("for $bad", AutomationResult.CODE_INVALID, r.code)
        }
    }

    @Test
    fun castUrlFillsInTitleAndMime() {
        val c = accepted(Api.ACTION_CAST_URL, mapOf(Api.EXTRA_URL to "https://example.com/media/Movie%201.mkv?token=1")) as AutomationCommand.CastUrl
        assertEquals("https://example.com/media/Movie%201.mkv?token=1", c.url)
        assertEquals("Movie%201.mkv", c.title)
        assertEquals("video/x-matroska", c.mime)
        assertTrue(c.device.isLast)
    }

    @Test
    fun castUrlKeepsAGivenTitleMimeAndDevice() {
        val c = accepted(
            Api.ACTION_CAST_URL,
            mapOf(Api.EXTRA_URL to "http://nas.local/stream", Api.EXTRA_TITLE to "News", Api.EXTRA_MIME to "application/x-mpegurl", Api.EXTRA_DEVICE_NAME to "Bedroom"),
        ) as AutomationCommand.CastUrl
        assertEquals("News", c.title)
        assertEquals("application/x-mpegurl", c.mime)
        assertEquals(DeviceRef(name = "Bedroom"), c.device)
    }

    @Test
    fun castUrlRejectsAMalformedMime() {
        val r = rejectedWith(parse(Api.ACTION_CAST_URL, mapOf(Api.EXTRA_URL to "https://a.b/c.mp4", Api.EXTRA_MIME to "video")))
        assertEquals(AutomationResult.CODE_INVALID, r.code)
    }

    @Test
    fun castUrlCanComeFromTheIntentData() {
        val c = accepted(Api.ACTION_CAST_URL, data = "https://example.com/a.mp4") as AutomationCommand.CastUrl
        assertEquals("https://example.com/a.mp4", c.url)
        assertEquals("a.mp4", c.title)
    }

    @Test
    fun castUrlRefusesAnAbsurdlyLongAddress() {
        val r = rejectedWith(parse(Api.ACTION_CAST_URL, mapOf(Api.EXTRA_URL to "https://a.b/" + "x".repeat(AutomationParser.MAX_URL))))
        assertEquals(AutomationResult.CODE_INVALID, r.code)
    }

    // ---- cast a file ----------------------------------------------------------------------------------------

    @Test
    fun castFileNeedsAContentUri() {
        assertEquals(AutomationResult.CODE_INVALID, rejectedWith(parse(Api.ACTION_CAST_FILE)).code)
        val file = rejectedWith(parse(Api.ACTION_CAST_FILE, mapOf(Api.EXTRA_URI to "file:///sdcard/Movies/a.mp4")))
        assertEquals(AutomationResult.CODE_INVALID, file.code)
        assertTrue(file.message.contains("content://"))
        assertEquals(AutomationResult.CODE_INVALID, rejectedWith(parse(Api.ACTION_CAST_FILE, mapOf(Api.EXTRA_URI to "/sdcard/a.mp4"))).code)
        assertEquals(AutomationResult.CODE_INVALID, rejectedWith(parse(Api.ACTION_CAST_FILE, mapOf(Api.EXTRA_URI to "content://"))).code)
    }

    @Test
    fun castFileTakesTheUriFromExtraOrData() {
        val a = accepted(Api.ACTION_CAST_FILE, mapOf(Api.EXTRA_URI to "content://media/external/video/media/42", Api.EXTRA_TITLE to "Holiday")) as AutomationCommand.CastFile
        assertEquals("content://media/external/video/media/42", a.uri)
        assertEquals("Holiday", a.title)
        val b = accepted(Api.ACTION_CAST_FILE, data = "content://media/external/video/media/43") as AutomationCommand.CastFile
        assertEquals("content://media/external/video/media/43", b.uri)
        assertEquals(null, b.title)
    }

    // ---- volume -------------------------------------------------------------------------------------------------

    @Test
    fun volumeSetNeedsALevelBetween0And100() {
        assertEquals(AutomationResult.CODE_INVALID, rejectedWith(parse(Api.ACTION_VOLUME_SET)).code)
        assertEquals(AutomationResult.CODE_INVALID, rejectedWith(parse(Api.ACTION_VOLUME_SET, mapOf(Api.EXTRA_LEVEL to 101))).code)
        assertEquals(AutomationResult.CODE_INVALID, rejectedWith(parse(Api.ACTION_VOLUME_SET, mapOf(Api.EXTRA_LEVEL to -1))).code)
        assertEquals(AutomationResult.CODE_INVALID, rejectedWith(parse(Api.ACTION_VOLUME_SET, mapOf(Api.EXTRA_LEVEL to "loud"))).code)
        assertEquals(AutomationResult.CODE_INVALID, rejectedWith(parse(Api.ACTION_VOLUME_SET, mapOf(Api.EXTRA_LEVEL to Double.NaN))).code)
    }

    @Test
    fun volumeLevelsComeAsNumbersOrText() {
        assertEquals(AutomationCommand.SetVolume(40), accepted(Api.ACTION_VOLUME_SET, mapOf(Api.EXTRA_LEVEL to 40)))
        assertEquals(AutomationCommand.SetVolume(40), accepted(Api.ACTION_VOLUME_SET, mapOf(Api.EXTRA_LEVEL to "40")))
        assertEquals(AutomationCommand.SetVolume(40), accepted(Api.ACTION_VOLUME_SET, mapOf(Api.EXTRA_LEVEL to " 40% ")))
        assertEquals(AutomationCommand.SetVolume(41), accepted(Api.ACTION_VOLUME_SET, mapOf(Api.EXTRA_LEVEL to 40.6)))
        assertEquals(AutomationCommand.SetVolume(0), accepted(Api.ACTION_VOLUME_SET, mapOf(Api.EXTRA_LEVEL to 0L)))
        assertEquals(AutomationCommand.SetVolume(100), accepted(Api.ACTION_VOLUME_SET, mapOf(Api.EXTRA_LEVEL to "100")))
    }

    @Test
    fun volumeUpAndDownHaveAnOptionalAmount() {
        assertEquals(AutomationCommand.ChangeVolume(up = true), accepted(Api.ACTION_VOLUME_UP))
        assertEquals(AutomationCommand.ChangeVolume(up = false, amountPercent = 15), accepted(Api.ACTION_VOLUME_DOWN, mapOf(Api.EXTRA_AMOUNT to "15")))
        assertEquals(AutomationResult.CODE_INVALID, rejectedWith(parse(Api.ACTION_VOLUME_UP, mapOf(Api.EXTRA_AMOUNT to 0))).code)
        assertEquals(AutomationResult.CODE_INVALID, rejectedWith(parse(Api.ACTION_VOLUME_UP, mapOf(Api.EXTRA_AMOUNT to 500))).code)
        assertEquals(AutomationResult.CODE_INVALID, rejectedWith(parse(Api.ACTION_VOLUME_DOWN, mapOf(Api.EXTRA_AMOUNT to "a bit"))).code)
    }

    // ---- quality / receive / remote -----------------------------------------------------------------------------

    @Test
    fun qualityNeedsALabel() {
        assertEquals(AutomationResult.CODE_INVALID, rejectedWith(parse(Api.ACTION_QUALITY)).code)
        assertEquals(AutomationCommand.Quality("720p"), accepted(Api.ACTION_QUALITY, mapOf(Api.EXTRA_QUALITY to " 720p ")))
    }

    @Test
    fun receiveTakesABooleanInAnyCommonSpelling() {
        assertEquals(AutomationCommand.Receive(null), accepted(Api.ACTION_RECEIVE))
        for (yes in listOf<Any>(true, "true", "TRUE", "1", "on", "yes", 1)) {
            assertEquals("for $yes", AutomationCommand.Receive(true), accepted(Api.ACTION_RECEIVE, mapOf(Api.EXTRA_ENABLED to yes)))
        }
        for (no in listOf<Any>(false, "false", "0", "off", "No", 0)) {
            assertEquals("for $no", AutomationCommand.Receive(false), accepted(Api.ACTION_RECEIVE, mapOf(Api.EXTRA_ENABLED to no)))
        }
        assertEquals(AutomationResult.CODE_INVALID, rejectedWith(parse(Api.ACTION_RECEIVE, mapOf(Api.EXTRA_ENABLED to "maybe"))).code)
    }

    @Test
    fun openRemoteTakesAnOptionalDevice() {
        assertEquals(AutomationCommand.OpenRemote(DeviceRef()), accepted(Api.ACTION_OPEN_REMOTE))
        assertEquals(AutomationCommand.OpenRemote(DeviceRef(either = "Shield")), accepted(Api.ACTION_OPEN_REMOTE, mapOf(Api.EXTRA_DEVICE to "Shield")))
    }

    // ---- the documented API --------------------------------------------------------------------------------------

    @Test
    fun everyActionIsParsedByTheParser() {
        // Needs-extras actions get valid ones; all must produce a command, none the "Unknown action" error.
        val extras = mapOf(
            Api.EXTRA_URL to "https://example.com/a.mp4", Api.EXTRA_URI to "content://x/1", Api.EXTRA_LEVEL to 10, Api.EXTRA_QUALITY to "720p",
            Api.EXTRA_QUEUE to """["https://example.com/a.mp4"]""",
        )
        for (action in Api.ALL_ACTIONS) accepted(action, extras)
    }

    @Test
    fun resultCodesAreDistinct() {
        val codes = listOf(
            AutomationResult.CODE_OK, AutomationResult.CODE_PENDING_USER, AutomationResult.CODE_DISABLED, AutomationResult.CODE_UNAUTHORIZED,
            AutomationResult.CODE_INVALID, AutomationResult.CODE_NO_DEVICE, AutomationResult.CODE_NOT_CASTING, AutomationResult.CODE_NEEDS_ACTIVITY,
            AutomationResult.CODE_FAILED, AutomationResult.CODE_UNSUPPORTED, AutomationResult.CODE_CANCELLED,
        )
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(AutomationResult.ok().ok)
        assertTrue(AutomationResult.pendingUser("x").ok)
        assertFalse(AutomationResult.DISABLED.ok)
    }

    @Test
    fun defaultTitleIsTheFileNameOrTheAddress() {
        assertEquals("a.mp4", AutomationParser.defaultTitle("https://h/p/a.mp4?x=1#t"))
        assertEquals("p", AutomationParser.defaultTitle("https://h/p/"))
        assertEquals("h", AutomationParser.defaultTitle("https://h"))
    }
}
