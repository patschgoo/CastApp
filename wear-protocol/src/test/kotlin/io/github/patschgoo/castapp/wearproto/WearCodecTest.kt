package io.github.patschgoo.castapp.wearproto

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            revision = WearCodec.REVISION,
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

    // ---- rotary volume (revision 2) -------------------------------------------------------------------------

    /** The decoder of CastApp 4.0 phones, word for word: what an old phone makes of a new watch's message. */
    private fun decodeLikeAnOldPhone(bytes: ByteArray): WearCommand? {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        if (o.optInt("v", 0) != 1) return null
        return WearCommand.fromWire(o.optString("cmd", ""))
    }

    /** The message as a map: the order of the keys differs between Android's org.json and the JVM's. */
    private fun json(m: WearMessage): Map<String, Any?> = map(String(WearCodec.encodeMessage(m), Charsets.UTF_8))

    private fun map(json: String): Map<String, Any?> {
        val o = JSONObject(json)
        return o.keys().asSequence().associateWith { o.get(it) }
    }

    private fun decode(s: String) = WearCodec.decodeMessage(s.toByteArray())

    @Test
    fun everyMessageSurvivesTheRoundTrip() {
        val repeats = (2..WearCodec.MAX_REPEAT).flatMap { n ->
            listOf(WearMessage.Command(WearCommand.VOLUME_UP, n), WearMessage.Command(WearCommand.VOLUME_DOWN, n))
        }
        val messages = WearCommand.entries.map { WearMessage.Command(it) } + repeats +
            listOf(0, 1, 5, 45, 99, 100).map { WearMessage.SetVolume(it) }
        for (m in messages) assertEquals(m, WearCodec.decodeMessage(WearCodec.encodeMessage(m)))
    }

    @Test
    fun aSingleStepIsTheSameJsonAsBefore() {
        assertEquals(map("""{"v":1,"cmd":"stop"}"""), json(WearMessage.Command(WearCommand.STOP)))
        assertEquals(map("""{"v":1,"cmd":"volume_up"}"""), json(WearMessage.Command(WearCommand.VOLUME_UP)))
        assertEquals("""{"v":1,"cmd":"volume_up"}""", String(WearCodec.encodeCommand(WearCommand.VOLUME_UP), Charsets.UTF_8))
    }

    @Test
    fun repeatedStepsAndLevelsAreSmallJson() {
        assertEquals(map("""{"v":1,"cmd":"volume_down","n":3}"""), json(WearMessage.Command(WearCommand.VOLUME_DOWN, 3)))
        assertEquals(map("""{"v":1,"cmd":"volume_set","level":40}"""), json(WearMessage.SetVolume(40)))
    }

    @Test
    fun anOldPhoneDoesOneStepForARepeatAndIgnoresALevel() {
        // That is why the watch sends repeats and levels only to a phone of revision 2 or newer.
        assertEquals(WearCommand.VOLUME_UP, decodeLikeAnOldPhone(WearCodec.encodeMessage(WearMessage.Command(WearCommand.VOLUME_UP, 4))))
        assertEquals(WearCommand.VOLUME_DOWN, decodeLikeAnOldPhone(WearCodec.encodeMessage(WearMessage.Command(WearCommand.VOLUME_DOWN, 2))))
        assertNull(decodeLikeAnOldPhone(WearCodec.encodeMessage(WearMessage.SetVolume(40))))
        for (c in WearCommand.entries) assertEquals(c, decodeLikeAnOldPhone(WearCodec.encodeMessage(WearMessage.Command(c))))
        // decodeCommand still answers what an old phone would
        assertEquals(WearCommand.VOLUME_UP, WearCodec.decodeCommand(WearCodec.encodeMessage(WearMessage.Command(WearCommand.VOLUME_UP, 4))))
        assertNull(WearCodec.decodeCommand(WearCodec.encodeMessage(WearMessage.SetVolume(40))))
    }

    @Test
    fun aNewPhoneReadsWhatAnOldWatchSends() {
        for (c in WearCommand.entries) {
            assertEquals(WearMessage.Command(c, 1), decode("""{"v":1,"cmd":"${c.wire}"}"""))
        }
    }

    @Test
    fun onlyVolumeRepeatsAndTheCountIsBounded() {
        assertEquals(WearMessage.Command(WearCommand.STOP, 1), decode("""{"v":1,"cmd":"stop","n":5}"""))
        assertEquals(WearMessage.Command(WearCommand.NEXT, 1), decode("""{"v":1,"cmd":"next","n":3}"""))
        assertEquals(WearMessage.Command(WearCommand.VOLUME_UP, WearCodec.MAX_REPEAT), decode("""{"v":1,"cmd":"volume_up","n":99}"""))
        assertEquals(WearMessage.Command(WearCommand.VOLUME_UP, 1), decode("""{"v":1,"cmd":"volume_up","n":0}"""))
        assertEquals(WearMessage.Command(WearCommand.VOLUME_DOWN, 1), decode("""{"v":1,"cmd":"volume_down","n":-3}"""))
        assertEquals(WearMessage.Command(WearCommand.VOLUME_DOWN, 1), decode("""{"v":1,"cmd":"volume_down","n":"3"}"""))
        assertEquals(WearMessage.Command(WearCommand.VOLUME_DOWN, 1), decode("""{"v":1,"cmd":"volume_down","n":null}"""))
        // and the encoder never writes more
        assertEquals(map("""{"v":1,"cmd":"stop"}"""), json(WearMessage.Command(WearCommand.STOP, 5)))
        assertEquals(map("""{"v":1,"cmd":"volume_up","n":${WearCodec.MAX_REPEAT}}"""), json(WearMessage.Command(WearCommand.VOLUME_UP, 50)))
        assertEquals(map("""{"v":1,"cmd":"volume_up"}"""), json(WearMessage.Command(WearCommand.VOLUME_UP, -2)))
    }

    @Test
    fun aBadLevelIsDroppedNotGuessed() {
        assertNull(decode("""{"v":1,"cmd":"volume_set"}"""))
        assertNull(decode("""{"v":1,"cmd":"volume_set","level":null}"""))
        assertNull(decode("""{"v":1,"cmd":"volume_set","level":"40"}"""))
        assertNull(decode("""{"v":1,"cmd":"volume_set","level":-1}"""))
        assertNull(decode("""{"v":1,"cmd":"volume_set","level":101}"""))
        assertNull(decode("""{"v":1,"cmd":"volume_set","level":250}"""))
        assertNull(decode("""{"v":2,"cmd":"volume_set","level":40}"""))
        assertEquals(WearMessage.SetVolume(46), decode("""{"v":1,"cmd":"volume_set","level":45.6}"""))
        assertEquals(WearMessage.SetVolume(100), decode("""{"v":1,"cmd":"volume_set","level":100}"""))
        // the encoder keeps it in range
        assertEquals(WearMessage.SetVolume(100), WearCodec.decodeMessage(WearCodec.encodeMessage(WearMessage.SetVolume(300))))
        assertEquals(WearMessage.SetVolume(0), WearCodec.decodeMessage(WearCodec.encodeMessage(WearMessage.SetVolume(-20))))
    }

    @Test
    fun theLevelCommandIsNoButtonName() {
        assertEquals("volume_set", WearCodec.CMD_SET_VOLUME)
        assertNull(WearCommand.fromWire(WearCodec.CMD_SET_VOLUME))
        assertTrue(WearCommand.VOLUME_UP.isVolumeStep)
        assertTrue(WearCommand.VOLUME_DOWN.isVolumeStep)
        assertEquals(2, WearCommand.entries.count { it.isVolumeStep })
        assertFalse(WearCommand.STOP.isVolumeStep)
    }

    @Test
    fun thePhoneRevisionTravelsWithTheState() {
        val s = NowPlaying(active = true, volumePercent = 30, revision = WearCodec.REVISION)
        assertEquals(WearCodec.REVISION, WearCodec.decodeState(WearCodec.encodeState(s))!!.revision)
        assertTrue(WearCodec.REVISION >= WearCodec.REVISION_VOLUME_LEVELS)
    }

    @Test
    fun aPhoneThatSendsNoRevisionIsRevision1() {
        // CastApp 4.0 phones send no "rev"
        assertEquals(1, WearCodec.decodeState("""{"v":1,"active":true,"vol":30}""".toByteArray())!!.revision)
        assertEquals(1, WearCodec.decodeState("""{"v":1,"active":true,"rev":0}""".toByteArray())!!.revision)
        assertEquals(1, WearCodec.decodeState("""{"v":1,"active":true,"rev":-4}""".toByteArray())!!.revision)
        assertEquals(1, NowPlaying.IDLE.revision)
    }

    @Test
    fun aStateFromANewerPhoneStillDecodes() {
        val s = WearCodec.decodeState("""{"v":1,"active":true,"vol":30,"rev":7,"something":"new"}""".toByteArray())!!
        assertEquals(7, s.revision)
        assertEquals(30, s.volumePercent)
    }
}
