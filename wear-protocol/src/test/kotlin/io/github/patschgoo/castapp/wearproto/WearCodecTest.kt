package io.github.patschgoo.castapp.wearproto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearCodecTest {
    @Test
    fun everyCommandSurvivesTheRoundTrip() {
        for (c in WearCommand.entries) {
            assertEquals(c, WearCodec.decodeCommand(WearCodec.encodeCommand(c)))
        }
    }

    @Test
    fun wireNamesAreFixedBecauseOldWatchesStillSendThem() {
        assertEquals("play_pause", WearCommand.PLAY_PAUSE.wire)
        assertEquals("previous", WearCommand.PREVIOUS.wire)
        assertEquals("next", WearCommand.NEXT.wire)
        assertEquals("volume_up", WearCommand.VOLUME_UP.wire)
        assertEquals("volume_down", WearCommand.VOLUME_DOWN.wire)
        assertEquals("stop", WearCommand.STOP.wire)
        assertEquals("mirror_last", WearCommand.MIRROR_LAST.wire)
        assertEquals(WearCommand.entries.size, WearCommand.entries.map { it.wire }.toSet().size)
    }

    @Test
    fun theCommandIsReadableJson() {
        assertEquals("""{"v":1,"cmd":"stop"}""", String(WearCodec.encodeCommand(WearCommand.STOP), Charsets.UTF_8))
    }

    @Test
    fun garbageCommandsDecodeToNull() {
        assertNull(WearCodec.decodeCommand(null))
        assertNull(WearCodec.decodeCommand(ByteArray(0)))
        assertNull(WearCodec.decodeCommand("not json".toByteArray()))
        assertNull(WearCodec.decodeCommand("[1,2]".toByteArray()))
        assertNull(WearCodec.decodeCommand("""{"v":1}""".toByteArray()))
        assertNull(WearCodec.decodeCommand("""{"v":1,"cmd":"format_phone"}""".toByteArray()))
        assertNull(WearCodec.decodeCommand("""{"v":1,"cmd":null}""".toByteArray()))
        assertNull(WearCodec.decodeCommand("""{"v":1,"cmd":42}""".toByteArray()))
        assertNull(WearCodec.decodeCommand(ByteArray(64 * 1024) { 'a'.code.toByte() }))
    }

    @Test
    fun aCommandOfAnotherVersionIsIgnored() {
        assertNull(WearCodec.decodeCommand("""{"v":2,"cmd":"stop"}""".toByteArray()))
        assertNull(WearCodec.decodeCommand("""{"cmd":"stop"}""".toByteArray()))
    }

    @Test
    fun aFullStateSurvivesTheRoundTrip() {
        val s = NowPlaying(
            active = true, title = "Tschüss – 再见 \"quotes\" \\ and {braces}", device = "Wohnzimmer", paused = true,
            mirroring = false, queueIndex = 3, queueSize = 12, volumePercent = 35, lastTv = "Schlafzimmer",
        )
        assertEquals(s, WearCodec.decodeState(WearCodec.encodeState(s)))
    }

    @Test
    fun theIdleStateSurvivesTheRoundTrip() {
        assertEquals(NowPlaying.IDLE, WearCodec.decodeState(WearCodec.encodeState(NowPlaying.IDLE)))
        val withTv = NowPlaying(lastTv = "TV")
        assertEquals(withTv, WearCodec.decodeState(WearCodec.encodeState(withTv)))
    }

    @Test
    fun longTextsAreCutSoAMessageStaysSmall() {
        val s = NowPlaying(active = true, title = "t".repeat(5000), device = "d".repeat(5000), lastTv = "l".repeat(5000))
        val bytes = WearCodec.encodeState(s)
        assertTrue("was ${bytes.size} bytes", bytes.size < 1500)
        val back = WearCodec.decodeState(bytes)
        assertNotNull(back)
        assertEquals(WearCodec.MAX_TEXT, back!!.title.length)
        assertEquals(WearCodec.MAX_TEXT, back.device.length)
    }

    @Test
    fun garbageStatesDecodeToNull() {
        assertNull(WearCodec.decodeState(null))
        assertNull(WearCodec.decodeState(ByteArray(0)))
        assertNull(WearCodec.decodeState("nope".toByteArray()))
        assertNull(WearCodec.decodeState("{}".toByteArray()))
        assertNull(WearCodec.decodeState("""{"v":1}""".toByteArray()))
        assertNull(WearCodec.decodeState("""{"v":9,"active":true}""".toByteArray()))
    }

    @Test
    fun oddNumbersInAStateAreCorrected() {
        val s = WearCodec.decodeState("""{"v":1,"active":true,"qi":-4,"qs":0,"vol":250}""".toByteArray())!!
        assertEquals(0, s.queueIndex)
        assertEquals(1, s.queueSize)
        assertEquals(100, s.volumePercent)
        val unknown = WearCodec.decodeState("""{"v":1,"active":false,"vol":-7}""".toByteArray())!!
        assertEquals(-1, unknown.volumePercent)
    }

    @Test
    fun missingFieldsTakeTheirDefaults() {
        val s = WearCodec.decodeState("""{"v":1,"active":true}""".toByteArray())!!
        assertEquals(NowPlaying(active = true), s)
        assertEquals(false, s.hasQueue)
        assertEquals(true, NowPlaying(queueSize = 2).hasQueue)
    }

    @Test
    fun pathsAndCapabilitiesAreFixed() {
        assertEquals("/castapp/command", WearPaths.COMMAND)
        assertEquals("/castapp/state", WearPaths.STATE)
        assertEquals("/castapp/state/request", WearPaths.STATE_REQUEST)
        assertEquals("castapp_phone", WearPaths.CAPABILITY_PHONE)
        assertEquals("castapp_watch", WearPaths.CAPABILITY_WATCH)
        // the phone's listener only subscribes to this prefix
        for (p in listOf(WearPaths.COMMAND, WearPaths.STATE, WearPaths.STATE_REQUEST)) assertTrue(p.startsWith("/castapp/"))
    }
}
