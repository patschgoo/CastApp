package io.github.patschgoo.castapp.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentalLockTest {
    private class MemoryStore(var text: String? = null) : TextStore {
        override fun read() = text
        override fun write(text: String) {
            this.text = text
        }
    }

    private var clock = 1_000_000_000L
    private val store = MemoryStore()
    private fun rules(s: TextStore = store) = ParentalRules(s) { clock }

    private val news = Channel("News 24", "http://tv/news", group = "News")
    private val kids = Channel("Cartoons", "http://tv/kids", group = "Kids")
    private val adult = Channel("Night", "http://tv/night", group = "Adult")
    private val plain = Channel("Plain", "http://tv/plain")

    @Test
    fun withoutAPinNothingIsProtected() {
        val r = rules()
        assertFalse(r.hasPin)
        assertFalse(r.setGroup("Adult", Protection.HIDDEN))
        assertFalse(r.setChannel(news.url, Protection.LOCKED))
        val s = r.state.value
        assertFalse(s.needsPin(adult, clock))
        assertFalse(s.isHidden(adult, clock))
    }

    @Test
    fun pinNeedsFourDigits() {
        val r = rules()
        assertFalse(r.setPin("123"))
        assertFalse(r.setPin("12ab"))
        assertFalse(r.setPin(""))
        assertTrue(r.setPin("1234"))
        assertTrue(r.hasPin)
        assertFalse("a second set must not replace the PIN", r.setPin("9999"))
        assertTrue(r.unlock("1234"))
    }

    @Test
    fun theFirstPinOpensTheSettingsForAMoment() {
        val r = rules()
        r.setPin("1234")
        assertTrue(r.state.value.unlocked(clock))
        assertTrue(r.setGroup("Adult", Protection.HIDDEN))
    }

    @Test
    fun lockedChannelsAskForThePinAndHiddenOnesDisappear() {
        val r = rules()
        r.setPin("1234")
        r.setGroup("Kids", Protection.LOCKED)
        r.setGroup("Adult", Protection.HIDDEN)
        r.setChannel(news.url, Protection.LOCKED)
        r.lock()
        val s = r.state.value
        assertFalse(s.unlocked(clock))
        assertTrue(s.needsPin(kids, clock))
        assertFalse(s.isHidden(kids, clock))
        assertTrue(s.needsPin(adult, clock))
        assertTrue(s.isHidden(adult, clock))
        assertTrue("a single channel", s.needsPin(news, clock))
        assertFalse(s.isHidden(news, clock))
        assertFalse(s.needsPin(plain, clock))
        assertFalse(s.isHidden(plain, clock))
    }

    @Test
    fun theStricterRuleWins() {
        val r = rules()
        r.setPin("1234")
        r.setGroup("Kids", Protection.HIDDEN)
        r.setChannel(kids.url, Protection.LOCKED)
        r.setGroup("News", Protection.LOCKED)
        r.setChannel(news.url, Protection.HIDDEN)
        val s = r.state.value
        assertEquals(Protection.HIDDEN, s.protectionOf(kids))
        assertEquals(Protection.HIDDEN, s.protectionOf(news))
        assertEquals(Protection.LOCKED, s.groupProtection("News"))
        assertEquals(Protection.NONE, s.groupProtection(null))
        assertEquals(Protection.NONE, s.groupProtection("Other"))
    }

    @Test
    fun enteringThePinOpensEverythingForFifteenMinutes() {
        val r = rules()
        r.setPin("1234")
        r.setGroup("Adult", Protection.HIDDEN)
        r.lock()
        assertTrue(r.state.value.isHidden(adult, clock))
        assertFalse(r.unlock("0000"))
        assertTrue(r.state.value.isHidden(adult, clock))
        assertTrue(r.unlock("1234"))
        assertFalse(r.state.value.isHidden(adult, clock))
        assertFalse(r.state.value.needsPin(adult, clock))
        // Still open after 14 minutes, closed again after 15.
        assertFalse(r.state.value.needsPin(adult, clock + 14 * 60_000L))
        assertTrue(r.state.value.needsPin(adult, clock + 15 * 60_000L))
        assertTrue(r.state.value.isHidden(adult, clock + 15 * 60_000L))
    }

    @Test
    fun rulesCanOnlyBeChangedWhileOpen() {
        val r = rules()
        r.setPin("1234")
        r.lock()
        assertFalse(r.setGroup("Kids", Protection.LOCKED))
        assertEquals(Protection.NONE, r.state.value.groupProtection("Kids"))
        assertFalse("clearing needs the PIN too", r.setGroup("Kids", Protection.NONE))
        r.unlock("1234")
        assertTrue(r.setGroup("Kids", Protection.LOCKED))
        clock += 16 * 60_000L
        assertFalse("the opening has run out", r.setGroup("Kids", Protection.NONE))
        assertEquals(Protection.LOCKED, r.state.value.groupProtection("Kids"))
    }

    @Test
    fun settingsSurviveARestartButTheOpeningDoesNot() {
        val r = rules()
        r.setPin("4321")
        r.setGroup("Adult", Protection.HIDDEN)
        r.setChannel(news.url, Protection.LOCKED)
        val again = rules()
        assertTrue(again.hasPin)
        assertFalse(again.state.value.unlocked(clock))
        assertEquals(Protection.HIDDEN, again.state.value.groupProtection("Adult"))
        assertEquals(Protection.LOCKED, again.state.value.channelProtection(news))
        assertTrue(again.state.value.isHidden(adult, clock))
        assertFalse(again.unlock("1234"))
        assertTrue(again.unlock("4321"))
    }

    @Test
    fun thePinIsNotStoredInThePlain() {
        rules().setPin("2468")
        assertFalse(store.text!!.contains("2468"))
        val h1 = PinHash.hash("2468", "aa")
        assertNotEquals(h1, PinHash.hash("2468", "bb"))
        assertNotEquals(h1, PinHash.hash("2469", "aa"))
        assertEquals(h1, PinHash.hash("2468", "aa"))
        assertTrue(PinHash.matches("2468", "aa", h1))
        assertFalse(PinHash.matches("0000", "aa", h1))
        assertNotEquals(PinHash.newSalt(), PinHash.newSalt())
    }

    @Test
    fun tooManyWrongPinsPauseTheChecks() {
        val r = rules()
        r.setPin("1234")
        r.lock()
        repeat(ParentalRules.MAX_FAILURES) { assertFalse(r.unlock("0000")) }
        assertTrue(r.waitSeconds() in 1..30)
        // During the pause even the right PIN is refused.
        assertFalse(r.unlock("1234"))
        clock += ParentalRules.LOCKOUT_MS + 1
        assertEquals(0, r.waitSeconds())
        assertTrue(r.unlock("1234"))
    }

    @Test
    fun aRightPinResetsTheCountOfWrongOnes() {
        val r = rules()
        r.setPin("1234")
        repeat(ParentalRules.MAX_FAILURES - 1) { r.unlock("0000") }
        assertTrue(r.unlock("1234"))
        repeat(ParentalRules.MAX_FAILURES - 1) { r.unlock("0000") }
        assertEquals(0, r.waitSeconds())
        assertTrue(r.unlock("1234"))
    }

    @Test
    fun changingAndRemovingThePin() {
        val r = rules()
        r.setPin("1234")
        r.setGroup("Adult", Protection.HIDDEN)
        assertFalse(r.changePin("0000", "5555"))
        assertFalse("new PIN too short", r.changePin("1234", "55"))
        assertTrue(r.changePin("1234", "5555"))
        r.lock()
        assertFalse(r.unlock("1234"))
        assertTrue(r.unlock("5555"))
        // The rules stay with the new PIN.
        assertEquals(Protection.HIDDEN, r.state.value.groupProtection("Adult"))
        assertFalse(r.removePin("1234"))
        assertTrue(r.removePin("5555"))
        assertFalse(r.hasPin)
        assertFalse(r.state.value.isHidden(adult, clock))
        assertEquals(emptyMap<String, Protection>(), r.state.value.rules)
    }

    @Test
    fun tappingCyclesThroughTheProtections() {
        assertEquals(Protection.LOCKED, Protection.NONE.next())
        assertEquals(Protection.HIDDEN, Protection.LOCKED.next())
        assertEquals(Protection.NONE, Protection.HIDDEN.next())
    }

    @Test
    fun aBrokenStoreStartsWithoutALock() {
        val r = ParentalRules(MemoryStore("garbage")) { clock }
        assertFalse(r.hasPin)
        assertTrue(r.setPin("1234"))
    }
}
