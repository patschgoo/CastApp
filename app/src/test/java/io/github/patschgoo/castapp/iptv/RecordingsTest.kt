package io.github.patschgoo.castapp.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class RecordingsTest {
    private val utc = TimeZone.getTimeZone("UTC")

    /** 2026-11-22 20:15 UTC. */
    private val start = Xmltv.parseTime("20261122201500 +0000")!!

    private class MemoryStore(var text: String? = null) : TextStore {
        override fun read() = text
        override fun write(text: String) {
            this.text = text
        }
    }

    // ---- file names ---------------------------------------------------------------------------------------

    @Test
    fun nameHasChannelTitleAndTime() {
        assertEquals("Das Erste - Tagesschau - 2026-11-22 20-15.ts", RecordingNames.fileName("Das Erste", "Tagesschau", start, "ts", utc))
        assertEquals("Das Erste - Tagesschau - 2026-11-22 21-15.mp4", RecordingNames.fileName("Das Erste", "Tagesschau", start, ".mp4", TimeZone.getTimeZone("Europe/Berlin")))
    }

    @Test
    fun recordNowUsesTheChannelNameOnce() {
        assertEquals("ZDF - 2026-11-22 20-15.ts", RecordingNames.fileName("ZDF", "ZDF", start, "ts", utc))
    }

    @Test
    fun unsafeCharactersAreRemoved() {
        val name = RecordingNames.fileName("DE: ARD/Das Erste HD", "Tatort: \"Der *Fall*\" <1/2>?", start, "ts", utc)
        assertEquals("DE ARD Das Erste HD - Tatort Der Fall 1 2 - 2026-11-22 20-15.ts", name)
        assertTrue(name.none { it in "\\/:*?\"<>|" })
        // Dots at the end of a part and control characters do not survive either.
        assertEquals("A - B - 2026-11-22 20-15.ts", RecordingNames.fileName("A...", "B\u0007\n", start, "ts", utc))
    }

    @Test
    fun longNamesAreShortenedButKeepTheTimeAndExtension() {
        val name = RecordingNames.fileName("Channel ".repeat(20), "Title ".repeat(40), start, "ts", utc)
        assertTrue(name.length <= 125)
        assertTrue(name, name.endsWith(" - 2026-11-22 20-15.ts"))
    }

    @Test
    fun emptyPartsAreLeftOut() {
        assertEquals("2026-11-22 20-15.ts", RecordingNames.fileName("", "", start, "ts", utc))
        assertEquals("2026-11-22 20-15.ts", RecordingNames.fileName("///", "???", start, "", utc))
    }

    @Test
    fun taken_namesGetANumber() {
        val taken = setOf("a.ts", "a (2).ts", "b")
        assertEquals("a (3).ts", RecordingNames.unique("a.ts", taken))
        assertEquals("c.ts", RecordingNames.unique("c.ts", taken))
        assertEquals("b (2)", RecordingNames.unique("b", taken))
    }

    @Test
    fun mimeTypesFollowTheExtension() {
        assertEquals("video/mp2t", RecordingNames.mimeFor("ts"))
        assertEquals("video/mp4", RecordingNames.mimeFor("MP4"))
        assertEquals("audio/aac", RecordingNames.mimeFor("aac"))
        assertEquals("video/mp2t", RecordingNames.mimeFor("unknown"))
    }

    // ---- provider connections ---------------------------------------------------------------------------------

    @Test
    fun sameProviderAccountIsFoundInXtreamAddresses() {
        val a = "http://tv.example:8080/live/alice/secret/101.ts"
        assertTrue(ConnectionGuard.sameAccount(a, "http://tv.example:8080/live/alice/secret/202.m3u8"))
        assertTrue(ConnectionGuard.sameAccount(a, "http://TV.example:8080/alice/secret/303"))
        assertTrue(ConnectionGuard.sameAccount(a, "http://tv.example:8080/get.php?username=alice&password=x"))
        assertFalse("another user", ConnectionGuard.sameAccount(a, "http://tv.example:8080/live/bob/secret/101.ts"))
        assertFalse("another server", ConnectionGuard.sameAccount(a, "http://other.example:8080/live/alice/secret/101.ts"))
        assertFalse("another port", ConnectionGuard.sameAccount(a, "http://tv.example:8081/live/alice/secret/101.ts"))
        assertFalse(ConnectionGuard.sameAccount(a, "file:///x"))
        assertFalse(ConnectionGuard.sameAccount("not a url", "not a url"))
    }

    @Test
    fun addressesWithoutAUserCompareByServer() {
        assertTrue(ConnectionGuard.sameAccount("https://cdn.example/a/b.m3u8", "https://cdn.example/c.m3u8"))
        assertEquals("cdn.example:443|", ConnectionGuard.accountKey("https://cdn.example/a/b.m3u8"))
        assertEquals("cdn.example:80|", ConnectionGuard.accountKey("http://cdn.example/x"))
        assertEquals("tv.example:8080|alice", ConnectionGuard.accountKey("http://tv.example:8080/live/alice/pw/5.ts"))
        assertNull(ConnectionGuard.accountKey("smb://x/y"))
    }

    // ---- the list of recordings -------------------------------------------------------------------------------

    private fun rec(id: String, from: Long, to: Long = from + 3_600_000L, state: RecordingState = RecordingState.SCHEDULED, programme: Long = 0) =
        Recording(id = id, title = "T$id", channelName = "C", channelUrl = "http://tv/$id", plannedStartMs = from, plannedEndMs = to, programmeStartMs = programme, state = state)

    @Test
    fun recordingsSurviveARestartWithAllFields() {
        val store = MemoryStore()
        val book = RecordingBook(store)
        val r = rec("a", start).copy(
            userAgent = "UA", referrer = "http://r/", target = RecordingTarget.NETWORK, sourceId = "src1", fileName = "x.ts", location = "smb://src1/CastApp/x.ts",
            bytes = 123, recordedMs = 456, gaps = 2, error = "oops", warning = "careful", state = RecordingState.PARTIAL,
        )
        book.add(r)
        val back = RecordingBook(store).items.value.single()
        assertEquals(r, back)
        assertEquals("UA", back.toChannel().userAgent)
    }

    @Test
    fun updateAndRemove() {
        val book = RecordingBook(MemoryStore())
        book.add(rec("a", start))
        book.add(rec("b", start + 1))
        book.update("a") { it.copy(bytes = 10) }
        book.update("zzz") { it.copy(bytes = 99) }
        assertEquals(10L, book.get("a")!!.bytes)
        book.remove("a")
        assertNull(book.get("a"))
        assertEquals(listOf("b"), book.items.value.map { it.id })
    }

    @Test
    fun theWakeUpComesEarlyForTheEarliestPlannedRecording() {
        val book = RecordingBook(MemoryStore())
        assertNull(book.nextTrigger(90_000))
        book.add(rec("late", start + 7_200_000L))
        book.add(rec("early", start))
        book.add(rec("done", start - 1_000_000L, state = RecordingState.DONE))
        assertEquals(start - 90_000L, book.nextTrigger(90_000))
    }

    @Test
    fun dueRecordingsAreThoseThatStartWithinTheEarlyWindow() {
        val book = RecordingBook(MemoryStore())
        book.add(rec("b", start + 60_000))
        book.add(rec("a", start))
        book.add(rec("later", start + 3_600_000))
        book.add(rec("running", start, state = RecordingState.RECORDING))
        assertEquals(listOf("a", "b"), book.due(start - 30_000, 90_000).map { it.id })
        assertTrue(book.due(start - 200_000, 90_000).isEmpty())
    }

    @Test
    fun aProgrammeCanBePlannedOnlyOnce() {
        val book = RecordingBook(MemoryStore())
        book.add(rec("a", start, programme = start + 120_000))
        assertNotNull(book.hasScheduled("http://tv/a", start + 120_000))
        assertNull(book.hasScheduled("http://tv/a", start))
        assertNull(book.hasScheduled("http://tv/b", start + 120_000))
    }

    @Test
    fun recordingsThatWereRunningWhenTheAppDiedAreMarked() {
        val store = MemoryStore()
        val book = RecordingBook(store)
        book.add(rec("data", start, state = RecordingState.RECORDING).copy(bytes = 5000))
        book.add(rec("none", start, state = RecordingState.RECORDING))
        book.add(rec("plan", start + 1))
        book.add(rec("done", start, state = RecordingState.DONE).copy(bytes = 1))
        RecordingBook(store).markInterrupted()
        val again = RecordingBook(store)
        again.markInterrupted()
        assertEquals(RecordingState.PARTIAL, again.get("data")!!.state)
        assertEquals(RecordingBook.INTERRUPTED, again.get("data")!!.error)
        assertEquals(RecordingState.FAILED, again.get("none")!!.state)
        assertEquals(RecordingState.SCHEDULED, again.get("plan")!!.state)
        assertEquals(RecordingState.DONE, again.get("done")!!.state)
    }

    @Test
    fun takenNamesCoverRecordedFiles() {
        val book = RecordingBook(MemoryStore())
        book.add(rec("a", start).copy(fileName = "one.ts"))
        book.add(rec("b", start))
        assertEquals(setOf("one.ts"), book.takenNames())
    }

    @Test
    fun displayHelpers() {
        assertEquals("0:05", RecordingFormat.duration(5_000))
        assertEquals("12:09", RecordingFormat.duration(729_000))
        assertEquals("1:05:09", RecordingFormat.duration(3_909_000))
        assertEquals("0:00", RecordingFormat.duration(-5))
        assertTrue(rec("a", start).isActive)
        assertFalse(rec("a", start, state = RecordingState.DONE).isActive)
        assertFalse(rec("a", start).hasFile)
        assertTrue(rec("a", start, state = RecordingState.PARTIAL).copy(location = "/x").hasFile)
    }
}
