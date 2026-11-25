package io.github.patschgoo.castapp.support

import io.github.patschgoo.castapp.discovery.AddressEcho
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalBufferTest {
    @Test fun keepsTheNewestLines() {
        val b = JournalBuffer(10)
        repeat(13) { b.add("line $it") }
        // Trimming waits for 20 % extra, then goes back to the limit.
        assertEquals(13, b.lines().size)
        b.add("line 13")
        assertEquals(10, b.lines().size)
        assertEquals("line 4", b.lines().first())
        assertEquals("line 13", b.lines().last())
        assertTrue(b.trimmedSinceSave())
        assertFalse(b.trimmedSinceSave())
    }

    @Test fun loadsOnlyTheNewestSavedLines() {
        val b = JournalBuffer(3)
        b.load(listOf("a", "b", "c", "d"))
        assertEquals(listOf("b", "c", "d"), b.lines())
    }

    @Test fun secondsAreReadable() {
        assertEquals("1.2s", JournalBuffer.seconds(1234))
        assertEquals("0.0s", JournalBuffer.seconds(0))
    }
}

class SoundStateTest {
    @Test fun describesPlayersAndWhetherTheyCanBeRecorded() {
        val text = SoundState.summary(
            musicActive = true,
            players = listOf(
                SoundState.Player(usage = 1, contentType = 3, capturePolicy = 1),
                SoundState.Player(usage = 1, contentType = 2, capturePolicy = 3),
                SoundState.Player(usage = 2, contentType = 1, capturePolicy = 1),
            ),
        )
        assertEquals(
            "music active; 3 players: media/movie (may be recorded), media/music (app forbids recording), call/speech (never recordable)",
            text,
        )
    }

    @Test fun nothingPlaying() {
        assertEquals("no music playing; 0 players", SoundState.summary(false, emptyList()))
        assertEquals("no music playing; in a call; 0 players", SoundState.summary(false, emptyList(), mode = 3))
    }
}

class DebugReportSessionsTest {
    @Test fun reportHasTheCastDiaryAndRedactsIt() {
        val text = DebugLog.build(
            ReportInput(
                appVersion = "CastApp 4.3.1 (10)", androidVersion = "16 (API 36)", device = "Phone",
                settings = emptyList(), devices = emptyList(), logLines = emptyList(),
                sessions = listOf(
                    "",
                    "11-24 20:20:00.000 === Cast to Living room (CAST) at 192.168.1.20:8009; whole screen",
                    "11-24 20:20:00.400 +0.4s Connected in 380 ms",
                    "11-24 20:20:01.000 +1.0s token=abc123",
                    "11-24 20:25:00.000 +300.0s === ended: stopped",
                ),
                soundNow = "no music playing; 0 players",
            ),
        )
        assertTrue(text.contains("Recent cast sessions (1)"))
        assertTrue(text.contains("Connected in 380 ms"))
        assertTrue(text.contains("Sound on the phone right now: no music playing"))
        assertFalse(text.contains("abc123"))
        // The diary is in the short version that fits into a chat.
        assertTrue(DebugLog.shortVersion(text).contains("Connected in 380 ms"))
    }

    @Test fun noSessionsYet() {
        val text = DebugLog.build(
            ReportInput("a", "b", "c", emptyList(), emptyList(), emptyList()),
        )
        assertTrue(text.contains("Recent cast sessions (0)"))
        assertTrue(text.contains("none since this version was installed"))
    }
}

class AddressEchoTest {
    private val v4 = CastDevice(id = "abc123", name = "Chromecast", host = "192.168.1.13")
    private val v6 = CastDevice(id = "host:fe80::1%wlan0:8009", name = "Chromecast", host = "fe80::1%wlan0")

    @Test fun dropsTheIpv6CopyOfAnIpv4Tv() {
        assertEquals(setOf(v6.id), AddressEcho.echoes(listOf(v4, v6)))
    }

    @Test fun keepsAnIpv6OnlyTvAndOtherNames() {
        assertTrue(AddressEcho.echoes(listOf(v6)).isEmpty())
        assertTrue(AddressEcho.echoes(listOf(v4.copy(name = "Bedroom"), v6)).isEmpty())
        // A real id over IPv6 is a real device, not an echo.
        assertTrue(AddressEcho.echoes(listOf(v4, v6.copy(id = "def456"))).isEmpty())
        // Another kind with the same name isn't the same answer.
        assertTrue(AddressEcho.echoes(listOf(v4.copy(kind = DeviceKind.DLNA), v6)).isEmpty())
    }
}
