package io.github.patschgoo.castapp.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/** The receiver's decisions: who may cast, the "Allow?" prompt and pairing by code. */
class ReceiverGuardTest {
    private var now = 1_000_000L
    private val store = MemoryGuardStore()

    private fun guard(mode: ProtectionMode, askMs: Long = 2_000) =
        ReceiverGuard(store, ProtectionMode.ANYONE, clock = { now }, askTimeoutMs = askMs).also { it.setMode(mode) }

    private val phone = Sender("192.168.1.20", "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/AP1A)")
    private val otherApp = Sender("192.168.1.20", "BubbleUPnP/3.4")

    /** Answers the first prompt that shows up, like the owner pressing a button. */
    private fun answerLater(g: ReceiverGuard, decision: Decision): Thread = thread {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            val p = g.pending.value.firstOrNull()
            if (p != null) {
                g.answer(p.id, decision)
                return@thread
            }
            Thread.sleep(5)
        }
    }

    private fun denied(v: Verdict) = (v as Verdict.Denied).message

    @Test
    fun anyoneModeAllowsEverything() {
        val g = guard(ProtectionMode.ANYONE)
        assertEquals(Verdict.Allowed, g.authorize(phone, Access.START))
        assertEquals(Verdict.Allowed, g.authorize(Sender("8.8.8.8", local = false), Access.CONTROL))
        assertTrue(g.pending.value.isEmpty())
    }

    @Test
    fun defaultModeFollowsTheDeviceUntilChosen() {
        val tv = ReceiverGuard(MemoryGuardStore(), ProtectionMode.ANYONE)
        val phoneGuard = ReceiverGuard(MemoryGuardStore(), ProtectionMode.ASK)
        assertEquals(ProtectionMode.ANYONE, tv.mode)
        assertEquals(ProtectionMode.ASK, phoneGuard.mode)
        phoneGuard.setMode(ProtectionMode.PAIRED)
        assertEquals(ProtectionMode.PAIRED, phoneGuard.mode)
        // The choice survives a restart (it is stored).
        assertEquals(ProtectionMode.PAIRED, ReceiverGuard(MemoryGuardStore(GuardData.fromJson(phoneGuard.data.value.toJson())), ProtectionMode.ASK).mode)
    }

    @Test
    fun askFirstLetsHarmlessRequestsThroughAndRefusesControlWithoutPrompt() {
        val g = guard(ProtectionMode.ASK)
        assertEquals(Verdict.Allowed, g.authorize(phone, Access.PUBLIC))
        assertEquals(Verdict.Allowed, g.authorize(phone, Access.STATUS))
        assertEquals(ReceiverGuard.NOT_ALLOWED, denied(g.authorize(phone, Access.CONTROL)))
        assertTrue("no prompt for a stop", g.pending.value.isEmpty())
    }

    @Test
    fun askFirstAllowAlwaysIsRememberedPerSenderAndAgent() {
        val g = guard(ProtectionMode.ASK)
        val answer = answerLater(g, Decision.ALLOW_ALWAYS)
        assertEquals(Verdict.Allowed, g.authorize(phone, Access.START))
        answer.join()
        assertEquals("Pixel 8", g.data.value.allowed.single().name)
        // Next time no question, also for control...
        assertEquals(Verdict.Allowed, g.authorize(phone, Access.START))
        assertEquals(Verdict.Allowed, g.authorize(phone, Access.CONTROL))
        assertTrue(g.pending.value.isEmpty())
        // ... but another app on the same phone is a new sender.
        assertEquals(ReceiverGuard.NOT_ALLOWED, denied(g.authorize(otherApp, Access.CONTROL)))
        // And it survives a restart.
        val again = ReceiverGuard(store, ProtectionMode.ASK, clock = { now })
        assertEquals(Verdict.Allowed, again.authorize(phone, Access.CONTROL))
        // Forgetting asks again.
        g.forgetAllowed(phone.key)
        assertEquals(ReceiverGuard.NOT_ALLOWED, denied(g.authorize(phone, Access.CONTROL)))
    }

    @Test
    fun allowOnceExpiresAfterAWhile() {
        val g = guard(ProtectionMode.ASK)
        val answer = answerLater(g, Decision.ALLOW_ONCE)
        assertEquals(Verdict.Allowed, g.authorize(phone, Access.START))
        answer.join()
        assertTrue(g.data.value.allowed.isEmpty())
        now += 10 * 60_000
        assertEquals(Verdict.Allowed, g.authorize(phone, Access.CONTROL)) // still in use: stays allowed
        now += 31 * 60_000
        assertEquals(ReceiverGuard.NOT_ALLOWED, denied(g.authorize(phone, Access.CONTROL)))
    }

    @Test
    fun blockIsRememberedAndNeverAsksAgain() {
        val g = guard(ProtectionMode.ASK)
        val answer = answerLater(g, Decision.BLOCK)
        assertEquals(ReceiverGuard.BLOCKED, denied(g.authorize(phone, Access.START)))
        answer.join()
        assertEquals(ReceiverGuard.BLOCKED, denied(g.authorize(phone, Access.START)))
        assertTrue(g.pending.value.isEmpty())
        g.forgetBlocked(phone.key)
        assertTrue(g.data.value.blocked.isEmpty())
    }

    @Test
    fun unansweredPromptTimesOutButStaysForALaterRetry() {
        val g = guard(ProtectionMode.ASK, askMs = 100)
        assertEquals(ReceiverGuard.WAITING, denied(g.authorize(phone, Access.START)))
        val prompt = g.pending.value.single()
        assertEquals("Pixel 8", prompt.name)
        // A retry does not stack a second prompt...
        assertEquals(ReceiverGuard.WAITING, denied(g.authorize(phone, Access.START)))
        assertEquals(1, g.pending.value.size)
        // ... and works once the owner answers.
        g.answer(prompt.id, Decision.ALLOW_ONCE)
        assertEquals(Verdict.Allowed, g.authorize(phone, Access.START))
        // A prompt nobody answers goes away after its lifetime and decides nothing.
        val g2 = guard(ProtectionMode.ASK, askMs = 50)
        g2.authorize(otherApp, Access.START)
        now += 31_000
        g2.prune()
        assertTrue(g2.pending.value.isEmpty())
        assertTrue(g2.data.value.allowed.isEmpty() && g2.data.value.blocked.isEmpty())
    }

    @Test
    fun twoRequestsFromOneSenderShareOnePrompt() {
        val g = guard(ProtectionMode.ASK)
        var attention = 0
        g.onAttention = { attention++ }
        val first = thread { g.authorize(phone, Access.START) }
        val deadline = System.currentTimeMillis() + 2000
        while (g.pending.value.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        val second = thread { g.authorize(phone, Access.START) }
        Thread.sleep(50)
        assertEquals(1, g.pending.value.size)
        g.answer(g.pending.value.single().id, Decision.ALLOW_ALWAYS)
        first.join()
        second.join()
        assertEquals(1, attention)
    }

    @Test
    fun strangersOutsideTheLocalNetworkAreRefused() {
        val g = guard(ProtectionMode.ASK)
        assertEquals(ReceiverGuard.REMOTE, denied(g.authorize(Sender("8.8.8.8", "x", local = false), Access.START)))
        assertTrue(g.pending.value.isEmpty())
        assertEquals(Verdict.Allowed, g.authorize(Sender("8.8.8.8", "x", local = false), Access.PUBLIC))
    }

    @Test
    fun pairedModeRefusesEveryoneWithoutAToken() {
        val g = guard(ProtectionMode.PAIRED)
        assertEquals(Verdict.Allowed, g.authorize(phone, Access.PUBLIC))
        for (access in listOf(Access.STATUS, Access.CONTROL, Access.START)) {
            assertEquals(ReceiverGuard.PAIRING_REQUIRED, denied(g.authorize(phone, access)))
        }
        // "Always allowed" from the ask mode doesn't count here.
        val ask = guard(ProtectionMode.ASK)
        answerLater(ask, Decision.ALLOW_ALWAYS).also { ask.authorize(phone, Access.START) }.join()
        ask.setMode(ProtectionMode.PAIRED)
        assertEquals(ReceiverGuard.PAIRING_REQUIRED, denied(ask.authorize(phone, Access.CONTROL)))
    }

    @Test
    fun pairingWithTheCodeGivesATokenThatOpensTheDoor() {
        val g = guard(ProtectionMode.PAIRED)
        val code = g.code
        assertTrue(Regex("\\d{4}").matches(code))
        var attention = 0
        g.onAttention = { attention++ }

        assertEquals(PairOutcome.CodeRequired, g.pair(phone, "Pixel 8", null))
        assertEquals("Pixel 8", g.pairing.value!!.name)
        assertEquals(1, attention)

        val wrong = if (code == "0000") "1111" else "0000"
        assertEquals(PairOutcome.WrongCode(4), g.pair(phone, "Pixel 8", wrong))

        val issued = g.pair(phone, "Pixel 8", " $code ") as PairOutcome.Issued
        assertEquals(64, issued.token.length)
        assertEquals(null, g.pairing.value)
        // The receiver keeps only a hash of the token.
        assertFalse(store.data.toJson().contains(issued.token))
        assertEquals(ReceiverGuard.hash(issued.token), g.data.value.paired.single().hash)
        assertEquals("Pixel 8", g.data.value.paired.single().name)

        val withToken = Sender("192.168.1.99", "SomethingElse/1", token = issued.token)
        assertEquals(Verdict.Allowed, g.authorize(withToken, Access.START))
        assertEquals(Verdict.Allowed, g.authorize(withToken, Access.CONTROL))
        assertEquals(PairOutcome.Paired, g.pair(withToken, "Pixel 8", null))
        assertEquals(ReceiverGuard.PAIRING_REQUIRED, denied(g.authorize(Sender("192.168.1.99", "x", token = "00".repeat(32)), Access.START)))

        // A code works once: the receiver shows a new one now.
        assertTrue(Regex("\\d{4}").matches(g.code))
        assertEquals(PairOutcome.CodeRequired, g.pair(phone, "Other", null))

        // A token is valid in the other modes too, and "Forget" revokes it.
        g.setMode(ProtectionMode.ASK)
        assertEquals(Verdict.Allowed, g.authorize(withToken, Access.CONTROL))
        g.setMode(ProtectionMode.PAIRED)
        g.forgetPaired(g.data.value.paired.single().hash)
        assertEquals(ReceiverGuard.PAIRING_REQUIRED, denied(g.authorize(withToken, Access.CONTROL)))
    }

    @Test
    fun guessingTheCodeLocksPairingAndChangesTheCode() {
        val g = guard(ProtectionMode.PAIRED)
        val code = g.code
        val wrong = if (code == "0000") "1111" else "0000"
        for (left in 4 downTo 1) assertEquals(PairOutcome.WrongCode(left), g.pair(phone, "x", wrong))
        assertEquals(PairOutcome.Locked(60), g.pair(phone, "x", wrong))
        // Even the right (old) code is refused while locked.
        assertEquals(PairOutcome.Locked(60), g.pair(phone, "x", code))
        now += 30_000
        assertEquals(PairOutcome.Locked(30), g.pair(phone, "x", code))
        now += 31_000
        assertEquals(PairOutcome.CodeRequired, g.pair(phone, "x", null))
        assertTrue(g.data.value.paired.isEmpty())
    }

    @Test
    fun otherModesDontAskForACode() {
        assertEquals(PairOutcome.NotRequired(ProtectionMode.ANYONE), guard(ProtectionMode.ANYONE).pair(phone, "x", null))
        assertEquals(PairOutcome.NotRequired(ProtectionMode.ASK), guard(ProtectionMode.ASK).pair(phone, "x", "1234"))
    }

    @Test
    fun aSwapTheUserStartedIsNotQuestioned() {
        val g = guard(ProtectionMode.ASK)
        val tv = Sender("192.168.1.50", "Dalvik/2.1.0 (Linux; U; Android 12; AFTGAZL Build/x)")
        g.expectSwap("192.168.1.50")
        assertEquals(Verdict.Allowed, g.authorize(tv, Access.START))
        g.setMode(ProtectionMode.PAIRED)
        assertEquals(PairOutcome.Paired, g.pair(tv, "TV", null))
        now += 121_000
        assertEquals(ReceiverGuard.PAIRING_REQUIRED, denied(g.authorize(tv, Access.START)))
    }

    @Test
    fun forgetAllClearsEverything() {
        val g = guard(ProtectionMode.ASK)
        answerLater(g, Decision.ALLOW_ALWAYS).also { g.authorize(phone, Access.START) }.join()
        answerLater(g, Decision.BLOCK).also { g.authorize(otherApp, Access.START) }.join()
        g.setMode(ProtectionMode.PAIRED)
        g.pair(phone, "Pixel", g.code)
        assertEquals(3, g.data.value.allowed.size + g.data.value.blocked.size + g.data.value.paired.size)
        g.forgetAll()
        assertEquals(0, g.data.value.allowed.size + g.data.value.blocked.size + g.data.value.paired.size)
        assertEquals(ProtectionMode.PAIRED, g.mode)
    }

    @Test
    fun whatEachSoapActionNeeds() {
        assertEquals(Access.START, ReceiverGuard.accessFor("AVTransport", "SetAVTransportURI"))
        assertEquals(Access.CONTROL, ReceiverGuard.accessFor("AVTransport", "Play"))
        assertEquals(Access.CONTROL, ReceiverGuard.accessFor("AVTransport", "Stop"))
        assertEquals(Access.STATUS, ReceiverGuard.accessFor("AVTransport", "GetPositionInfo"))
        assertEquals(Access.PUBLIC, ReceiverGuard.accessFor("AVTransport", "GetTransportInfo"))
        assertEquals(Access.PUBLIC, ReceiverGuard.accessFor("RenderingControl", "GetVolume"))
        assertEquals(Access.CONTROL, ReceiverGuard.accessFor("RenderingControl", "SetVolume"))
        assertEquals(Access.PUBLIC, ReceiverGuard.accessFor("ConnectionManager", "GetProtocolInfo"))
        assertEquals(Access.CONTROL, ReceiverGuard.accessFor("Unknown", "Explode"))
    }

    @Test
    fun sendersAreNamedForTheOwner() {
        assertEquals("Pixel 8", phone.label)
        assertEquals("BubbleUPnP", otherApp.label)
        assertEquals("Living room", Sender("1.2.3.4", "x", name = " Living room ").label)
        assertEquals("1.2.3.4", Sender("1.2.3.4").label)
        assertEquals("1.2.3.4", Sender("1.2.3.4", "okhttp/4.12").label)
        assertEquals("SM-S918B", Sender("1.2.3.4", "Dalvik/2.1.0 (Linux; U; Android 14; SM-S918B Build/UP1A)").label)
        assertEquals("1.2.3.4|BubbleUPnP/3.4", Sender("1.2.3.4", " BubbleUPnP/3.4 ").key)
        assertEquals("1.2.3.4", Sender("1.2.3.4").key)
    }

    @Test
    fun dataSurvivesJson() {
        val data = GuardData(
            ProtectionMode.ASK, "0420",
            listOf(KnownSender("a|b", "Pixel", "a", 5)), listOf(KnownSender("c", "Bad", "c", 6)), listOf(PairedPhone("ff", "Phone", 7)),
        )
        assertEquals(data, GuardData.fromJson(data.toJson()))
        assertEquals(GuardData(), GuardData.fromJson("not json"))
        assertEquals(GuardData(), GuardData.fromJson(null))
    }
}
