package io.github.patschgoo.castapp.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfTestTest {
    private fun item(i: SelfTestInput, c: SelfCheck) = SelfTest.evaluate(i).single { it.check == c }

    @Test fun healthyPhonePassesEverythingThatMatters() {
        val items = SelfTest.evaluate(
            SelfTestInput(
                h264Encoders = listOf("c2.qti.avc.encoder"),
                hevcEncoders = listOf("c2.qti.hevc.encoder"),
                aacEncoders = listOf("c2.android.aac.encoder"),
                infraredEmitter = true,
                installedModels = listOf("Vosk en"),
            ),
        )
        assertEquals(SelfCheck.entries.size, items.size)
        assertTrue(items.all { it.status == SelfStatus.PASS })
    }

    @Test fun missingEncodersAreProblemsExceptHevc() {
        val i = SelfTestInput()
        assertEquals(SelfStatus.FAIL, item(i, SelfCheck.H264).status)
        assertEquals(SelfStatus.FAIL, item(i, SelfCheck.AAC).status)
        assertEquals(SelfStatus.WARN, item(i, SelfCheck.HEVC).status)
    }

    @Test fun encoderDetailListsAtMostThreeNames() {
        val i = SelfTestInput(h264Encoders = listOf("a", "b", "c", "d"))
        assertEquals("a, b, c", item(i, SelfCheck.H264).detail)
    }

    @Test fun appAudioNeedsAndroid10() {
        assertEquals(SelfStatus.WARN, item(SelfTestInput(sdk = 28), SelfCheck.APP_AUDIO).status)
        assertEquals(SelfStatus.PASS, item(SelfTestInput(sdk = 29), SelfCheck.APP_AUDIO).status)
    }

    @Test fun networkRules() {
        assertEquals(SelfStatus.FAIL, item(SelfTestInput(transport = Transport.NONE), SelfCheck.NETWORK).status)
        assertEquals(SelfStatus.FAIL, item(SelfTestInput(transport = Transport.CELLULAR), SelfCheck.NETWORK).status)
        assertEquals("net_vpn", item(SelfTestInput(vpnActive = true), SelfCheck.NETWORK).why)
        assertEquals(SelfStatus.PASS, item(SelfTestInput(transport = Transport.ETHERNET), SelfCheck.NETWORK).status)
    }

    @Test fun bluetoothVariants() {
        assertEquals("bt_none", item(SelfTestInput(hasBluetooth = false), SelfCheck.BLUETOOTH).why)
        assertEquals("bt_no_hid", item(SelfTestInput(sdk = 27), SelfCheck.BLUETOOTH).why)
        assertEquals("bt_unknown", item(SelfTestInput(hidDeviceSupported = null), SelfCheck.BLUETOOTH).why)
        assertEquals("bt_no_hid", item(SelfTestInput(hidDeviceSupported = false), SelfCheck.BLUETOOTH).why)
        assertEquals(SelfStatus.PASS, item(SelfTestInput(hidDeviceSupported = true), SelfCheck.BLUETOOTH).status)
    }

    @Test fun portInUseByOurselvesIsFine() {
        assertEquals(SelfStatus.WARN, item(SelfTestInput(receiverPortFree = false), SelfCheck.PORT).status)
        assertEquals(SelfStatus.PASS, item(SelfTestInput(receiverPortFree = false, receiverRunning = true), SelfCheck.PORT).status)
    }

    @Test fun everyResultHasAnExplanationText() {
        val inputs = listOf(
            SelfTestInput(),
            SelfTestInput(h264Encoders = listOf("x"), hevcEncoders = listOf("x"), aacEncoders = listOf("x"), screenCapture = false, sdk = 26,
                notificationsAllowed = false, batteryExempt = false, infraredEmitter = true, hasBluetooth = false, transport = Transport.CELLULAR,
                multicastLockWorks = false, receiverPortFree = false, installedModels = listOf("Vosk en")),
            SelfTestInput(hidDeviceSupported = null, transport = Transport.NONE, receiverPortFree = false, receiverRunning = true),
            SelfTestInput(hidDeviceSupported = false, vpnActive = true),
            SelfTestInput(sdk = 27),
        )
        for (input in inputs) for (it in SelfTest.evaluate(input)) assertNotNull(it.why, explanationOf(it.why))
    }

    @Test fun logLinesSummariseAndListEverything() {
        val items = SelfTest.evaluate(SelfTestInput())
        val lines = SelfTest.logLines(items)
        assertEquals(items.size + 1, lines.size)
        assertTrue(lines[0].startsWith("Self-test: "))
        assertTrue(lines.any { it.startsWith("[FAIL] H264 (h264_missing)") })
        val (p, w, f) = SelfTest.counts(items)
        assertEquals(items.size, p + w + f)
    }

    @Test fun reportContainsTheSelfTest() {
        val report = DebugLog.build(
            ReportInput("v", "a", "d", emptyList(), emptyList(), emptyList(), selfTest = listOf("Self-test: 1 ok, 0 warnings, 0 failed")),
        )
        assertTrue(report.contains("Self-test (Test this phone)"))
        assertTrue(report.contains("Self-test: 1 ok"))
    }
}
