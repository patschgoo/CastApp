package io.github.patschgoo.castapp.support

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.settings.CastSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class VersionsTest {
    @Test fun comparesNumbers() {
        assertTrue(Versions.isNewer("3.1.1", "3.1.0"))
        assertTrue(Versions.isNewer("3.10.0", "3.9.9"))
        assertTrue(Versions.isNewer("4.0", "3.9.9"))
        assertFalse(Versions.isNewer("3.1.0", "3.1.0"))
        assertFalse(Versions.isNewer("3.0.9", "3.1.0"))
    }

    @Test fun ignoresLeadingVAndMissingParts() {
        assertEquals(0, Versions.compare("v3.1", "3.1.0"))
        assertTrue(Versions.isNewer("v3.2.0", "3.1.0"))
    }

    @Test fun preReleaseIsOlderThanRelease() {
        assertTrue(Versions.isNewer("3.2.0", "3.2.0-beta1"))
        assertFalse(Versions.isNewer("3.2.0-beta1", "3.2.0"))
    }

    @Test fun garbageDoesNotCrash() {
        assertFalse(Versions.isNewer("", "3.1.0"))
        assertFalse(Versions.isNewer("abc", "3.1.0"))
    }
}

class ReleaseParsingTest {
    private val json = """
        {"tag_name":"v3.2.0","name":"Version 3.2","body":"- new things\r\n- fixes","html_url":"https://github.com/patschgoo/CastApp/releases/tag/v3.2.0",
         "prerelease":false,
         "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":5},
                   {"name":"CastApp.apk","browser_download_url":"https://github.com/patschgoo/CastApp/releases/download/v3.2.0/CastApp.apk","size":12345}]}
    """.trimIndent()

    @Test fun parsesRelease() {
        val r = ReleaseInfo.parse(json)!!
        assertEquals("v3.2.0", r.tag)
        assertEquals("3.2.0", r.version)
        assertEquals("Version 3.2", r.name)
        assertTrue(r.notes.startsWith("- new things"))
        assertEquals("https://github.com/patschgoo/CastApp/releases/download/v3.2.0/CastApp.apk", r.apkUrl)
        assertEquals(12345L, r.apkSize)
    }

    @Test fun releaseWithoutApkAsset() {
        val r = ReleaseInfo.parse("""{"tag_name":"v1","assets":[]}""")!!
        assertNull(r.apkUrl)
    }

    @Test fun notAReleaseIsNull() {
        assertNull(ReleaseInfo.parse("""{"message":"Not Found"}"""))
        assertNull(ReleaseInfo.parse("not json"))
    }

    @Test fun interpretsAnswers() {
        assertTrue(Updates.interpret(200, json, "3.1.0") is CheckResult.Newer)
        assertTrue(Updates.interpret(200, json, "3.2.0") is CheckResult.UpToDate)
        assertTrue(Updates.interpret(404, "{}", "3.1.0") is CheckResult.NoRelease)
        assertTrue(Updates.interpret(500, null, "3.1.0") is CheckResult.Failed)
        assertTrue(Updates.interpret(403, "{}", "3.1.0") is CheckResult.Failed)
        assertTrue(Updates.interpret(200, "garbage", "3.1.0") is CheckResult.Failed)
    }
}

class NetworkCheckTest {
    private fun base(
        transport: Transport = Transport.WIFI,
        phone: String? = "192.168.1.23",
        found: List<String> = emptyList(),
        mdns: Int = 0,
        ssdp: Int = 0,
        saved: List<SavedTvCheck> = emptyList(),
        vpn: Boolean = false,
        router: Boolean? = true,
    ) = NetworkMeasurements(
        transport = transport, vpnActive = vpn, phoneAddress = phone, routerAnswered = router,
        mdnsReplies = mdns, ssdpReplies = ssdp, foundDevices = found, savedTvs = saved,
    )

    private fun codes(m: NetworkMeasurements) = NetworkCheck.analyze(m).map { it.code }

    @Test fun mobileDataStopsEarly() {
        assertEquals(listOf(FindingCode.MOBILE_DATA), codes(base(transport = Transport.CELLULAR)))
    }

    @Test fun noNetwork() {
        assertEquals(listOf(FindingCode.NO_NETWORK), codes(base(transport = Transport.NONE, phone = null)))
    }

    @Test fun vpnIsReported() {
        assertTrue(FindingCode.VPN_ACTIVE in codes(base(vpn = true, found = listOf("TV"), mdns = 1, ssdp = 1)))
    }

    @Test fun silentWifiExplainsAisolation() {
        val f = NetworkCheck.analyze(base())
        val nothing = f.first { it.code == FindingCode.NOTHING_ANSWERED }
        assertEquals(Severity.PROBLEM, nothing.severity)
        assertEquals(nothing, f.first()) // the problem comes first
    }

    @Test fun routerSilent() {
        assertTrue(FindingCode.ROUTER_SILENT in codes(base(router = false)))
        assertFalse(FindingCode.ROUTER_SILENT in codes(base(router = null)))
    }

    @Test fun mdnsOnlySilent() {
        val c = codes(base(found = listOf("Living room"), mdns = 0, ssdp = 2))
        assertTrue(FindingCode.MDNS_SILENT in c)
        assertFalse(FindingCode.SSDP_SILENT in c)
        assertFalse(FindingCode.NOTHING_ANSWERED in c)
    }

    @Test fun allGoodWhenEverythingAnswers() {
        val c = codes(base(found = listOf("TV"), mdns = 3, ssdp = 4))
        assertTrue(FindingCode.ALL_GOOD in c)
        assertTrue(FindingCode.DEVICES_FOUND in c)
    }

    @Test fun savedTvsOnOtherSubnetAndOffline() {
        val saved = listOf(
            SavedTvCheck("Bedroom", "10.0.0.5", false),
            SavedTvCheck("Kitchen", "192.168.1.40", false),
        )
        val f = NetworkCheck.analyze(base(found = listOf("Den"), mdns = 1, ssdp = 1, saved = saved))
        assertEquals("Bedroom", f.first { it.code == FindingCode.SAVED_OTHER_SUBNET }.detail)
        assertEquals("Kitchen", f.first { it.code == FindingCode.SAVED_UNREACHABLE }.detail)
        assertFalse(FindingCode.ALL_GOOD in f.map { it.code })
    }

    @Test fun reachableButSilentTv() {
        val c = codes(base(saved = listOf(SavedTvCheck("Den", "192.168.1.50", true))))
        assertTrue(FindingCode.SAVED_REACHABLE_NOT_FOUND in c)
        assertTrue(FindingCode.NOTHING_ANSWERED in c)
    }

    @Test fun saverFlags() {
        val m = base(found = listOf("TV"), mdns = 1, ssdp = 1).copy(dataSaver = true, batterySaver = true)
        val c = codes(m)
        assertTrue(FindingCode.DATA_SAVER in c && FindingCode.BATTERY_SAVER in c)
    }

    @Test fun subnetMath() {
        assertTrue(NetworkCheck.sameSubnet("192.168.1.99", "192.168.1.23", 24))
        assertFalse(NetworkCheck.sameSubnet("192.168.2.99", "192.168.1.23", 24))
        assertTrue(NetworkCheck.sameSubnet("192.168.2.99", "192.168.1.23", 22))
        assertTrue(NetworkCheck.sameSubnet("tv.local", "192.168.1.23", 24))
    }
}

class WakeOnLanTest {
    @Test fun parsesMacFormats() {
        val expected = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        assertArrayEquals(expected, WakeOnLan.parseMac("aa:bb:cc:dd:ee:ff"))
        assertArrayEquals(expected, WakeOnLan.parseMac(" AA-BB-CC-DD-EE-FF "))
        assertArrayEquals(expected, WakeOnLan.parseMac("aabbccddeeff"))
        assertArrayEquals(expected, WakeOnLan.parseMac("aabb.ccdd.eeff"))
    }

    @Test fun rejectsBadMacs() {
        assertNull(WakeOnLan.parseMac(null))
        assertNull(WakeOnLan.parseMac(""))
        assertNull(WakeOnLan.parseMac("aa:bb:cc:dd:ee"))
        assertNull(WakeOnLan.parseMac("aa:bb:cc:dd:ee:gg"))
        assertNull(WakeOnLan.parseMac("00:00:00:00:00:00"))
        assertNull(WakeOnLan.parseMac("ff:ff:ff:ff:ff:ff"))
    }

    @Test fun normalizes() {
        assertEquals("AA:BB:CC:00:11:22", WakeOnLan.normalizeMac("aa-bb-cc-00-11-22"))
        assertNull(WakeOnLan.normalizeMac("nope"))
    }

    @Test fun magicPacketLayout() {
        val mac = WakeOnLan.parseMac("01:02:03:04:05:06")!!
        val p = WakeOnLan.magicPacket(mac)
        assertEquals(102, p.size)
        for (i in 0 until 6) assertEquals(0xFF.toByte(), p[i])
        for (r in 0 until 16) assertArrayEquals(mac, p.copyOfRange(6 + r * 6, 12 + r * 6))
    }

    @Test fun broadcastAddress() {
        val ip = InetAddress.getByName("192.168.1.23") as Inet4Address
        assertEquals("192.168.1.255", WakeOnLan.broadcastAddress(ip, 24).hostAddress)
        assertEquals("192.168.3.255", WakeOnLan.broadcastAddress(ip, 22).hostAddress)
        assertEquals("192.168.1.23", WakeOnLan.broadcastAddress(ip, 99).hostAddress) // clamped to /30
    }

    @Test fun samsungMac() {
        val json = """{"device":{"name":"TV","wifiMac":"a4:b1:c2:0d:0e:0f","modelName":"X"}}"""
        assertEquals("A4:B1:C2:0D:0E:0F", WakeOnLan.parseSamsungMac(json))
        assertNull(WakeOnLan.parseSamsungMac("""{"device":{}}"""))
        assertNull(WakeOnLan.parseSamsungMac("nope"))
    }

    @Test fun deviceKeepsMacThroughJson() {
        val d = CastDevice(id = "x", name = "TV", host = "192.168.1.5", mac = "AA:BB:CC:DD:EE:FF")
        assertEquals("AA:BB:CC:DD:EE:FF", CastDevice.fromJson(d.toJson())!!.mac)
        assertNull(CastDevice.fromJson(d.copy(mac = null).toJson())!!.mac)
    }
}

class DebugLogTest {
    private val input = ReportInput(
        appVersion = "CastApp 3.1.0 (5)",
        androidVersion = "14 (API 34)",
        device = "Xiaomi 23",
        settings = DebugLog.settingsLines(CastSettings(receiverAppId = "ABCD1234")),
        devices = listOf(DeviceLine("Living room", "CAST", "192.168.1.20:8009", true, true)),
        logLines = listOf(
            "11-22 10:00:00.000  100  100 I CastService: started",
            "11-22 10:00:01.000  100  101 E CastClient: connect failed password=hunter2 to smb://bob:secret@nas/share",
            "11-22 10:00:02.000  100  101 W Http: GET http://x/y?token=abc123&a=1 Authorization: Bearer abcdefghijkl",
        ),
    )

    @Test fun reportHasTheUsefulParts() {
        val text = DebugLog.build(input)
        assertTrue(text.contains("CastApp 3.1.0 (5)"))
        assertTrue(text.contains("14 (API 34)"))
        assertTrue(text.contains("Xiaomi 23"))
        assertTrue(text.contains("Living room | CAST | 192.168.1.20:8009 | answers | remembered"))
        assertTrue(text.contains("Resolution: 720p"))
        assertTrue(text.contains("Recent errors and warnings (2)"))
    }

    @Test fun reportHasNoSecrets() {
        val text = DebugLog.build(input)
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("bob:secret"))
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("abcdefghijkl"))
        // the low-latency app id is only reported as on/off
        assertFalse(text.contains("ABCD1234"))
        assertTrue(text.contains("Low-latency TV player: on"))
    }

    @Test fun redactKeepsNormalText() {
        assertEquals("connected to 192.168.1.20 code=200", DebugLog.redact("connected to 192.168.1.20 code=200"))
        assertEquals("pin: ***", DebugLog.redact("pin: 1234"))
    }

    @Test fun picksProblemLines() {
        assertTrue(DebugLog.isProblem("11-22 10:00:01.000  100  101 E Tag: x"))
        assertTrue(DebugLog.isProblem("11-22 10:00:01.000  100  101 W Tag: x"))
        assertFalse(DebugLog.isProblem("11-22 10:00:01.000  100  101 I Tag: x"))
        assertFalse(DebugLog.isProblem("short"))
    }

    @Test fun longLogsAreCut() {
        val lines = (1..5000).map { "11-22 10:00:00.000  1  1 I T: line$it" }
        val text = DebugLog.build(input.copy(logLines = lines))
        assertTrue(text.contains("line5000"))
        assertFalse(text.contains("line100\n"))
        assertNotNull(text)
    }

    @Test fun shortVersionLeavesOutTheLog() {
        val report = DebugLog.build(input.copy(logLines = listOf("11-22 10:11:12.123  1234  1250 I Tag: hello there")))
        val short = DebugLog.shortVersion(report)
        assertTrue(short.contains("Settings"))
        assertFalse(short.contains("hello there"))
        assertTrue(report.contains("hello there"))
        assertEquals(1001, DebugLog.shortVersion("x".repeat(5000), maxChars = 999).length)
    }

    @Test fun fileNameHasTheTime() {
        val name = DebugLog.fileName(java.util.Date(0))
        assertTrue(name.matches(Regex("castapp-debug-log-\\d{8}-\\d{4}\\.txt")))
    }
}

class SupportRulesTest {
    @Test fun brandDetection() {
        assertEquals(Brand.XIAOMI, Brand.of("Xiaomi", "POCO"))
        assertEquals(Brand.XIAOMI, Brand.of("Xiaomi", "Redmi"))
        assertEquals(Brand.SAMSUNG, Brand.of("samsung", "samsung"))
        assertEquals(Brand.HUAWEI, Brand.of("HUAWEI", "HONOR"))
        assertEquals(Brand.ONEPLUS_OPPO, Brand.of("OPPO", "realme"))
        assertEquals(Brand.OTHER, Brand.of("Google", "google"))
    }

    @Test fun batteryPromptRule() {
        assertTrue(BatteryPrompt.shouldAsk(alreadyAsked = false, exempt = false))
        assertFalse(BatteryPrompt.shouldAsk(alreadyAsked = true, exempt = false))
        assertFalse(BatteryPrompt.shouldAsk(alreadyAsked = false, exempt = true))
    }

    @Test fun dndRestoreRule() {
        // we set 2 (priority), earlier mode was 1 (all): restore when still 2
        assertTrue(DoNotDisturb.shouldRestore(current = 2, setByUs = 2, saved = 1))
        // the user changed it meanwhile: leave alone
        assertFalse(DoNotDisturb.shouldRestore(current = 3, setByUs = 2, saved = 1))
        assertFalse(DoNotDisturb.shouldRestore(current = 2, setByUs = 2, saved = -1))
    }
}
