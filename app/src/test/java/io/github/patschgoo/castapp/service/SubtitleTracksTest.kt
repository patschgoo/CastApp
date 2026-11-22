package io.github.patschgoo.castapp.service

import io.github.patschgoo.castapp.subtitles.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTracksTest {
    private val uri = "content://media/film"
    private val cues = listOf(Cue(10.0, 12.0, "Hello"), Cue(20.0, 22.0, "World"))

    @Test
    fun choiceKeysRoundTrip() {
        val all = listOf(SubtitleChoice.OFF, SubtitleChoice.FILE, SubtitleChoice.embedded(3), SubtitleChoice.WRITTEN, SubtitleChoice.ONLINE)
        for (c in all) assertEquals(c, SubtitleChoice.fromKey(c.key))
        assertEquals("emb3", SubtitleChoice.embedded(3).key)
        assertNull(SubtitleChoice.fromKey("emb-1"))
        assertNull(SubtitleChoice.fromKey("nonsense"))
        assertNull(SubtitleChoice.fromKey(null))
    }

    @Test
    fun defaultPrefersTheFileThenOnlineThenWritten() {
        assertEquals(SubtitleChoice.OFF, SubtitleChoice.default(emptyList()))
        assertEquals(SubtitleChoice.OFF, SubtitleChoice.default(listOf(SubtitleChoice.embedded(0)))) // only when picked
        assertEquals(SubtitleChoice.WRITTEN, SubtitleChoice.default(listOf(SubtitleChoice.WRITTEN, SubtitleChoice.embedded(0))))
        assertEquals(SubtitleChoice.ONLINE, SubtitleChoice.default(listOf(SubtitleChoice.WRITTEN, SubtitleChoice.ONLINE)))
        assertEquals(SubtitleChoice.FILE, SubtitleChoice.default(listOf(SubtitleChoice.ONLINE, SubtitleChoice.FILE)))
    }

    @Test
    fun pickedChoiceWinsWhileItIsAvailable() {
        val t = SubtitleTracks()
        val available = listOf(SubtitleChoice.FILE, SubtitleChoice.embedded(1))
        assertEquals(SubtitleChoice.FILE, t.effective(uri, available))
        t.choose(uri, SubtitleChoice.embedded(1))
        assertEquals(SubtitleChoice.embedded(1), t.effective(uri, available))
        t.choose(uri, SubtitleChoice.OFF)
        assertEquals(SubtitleChoice.OFF, t.effective(uri, available))
        t.choose(uri, SubtitleChoice.ONLINE) // not there (yet): the default plays
        assertEquals(SubtitleChoice.FILE, t.effective(uri, available))
    }

    @Test
    fun onlineSubtitlesPlugIn() {
        val t = SubtitleTracks()
        t.setOnline(uri, "OpenSubtitles", cues)
        assertTrue(t.hasOnline(uri))
        assertEquals("OpenSubtitles", t.onlineLabel(uri))
        assertEquals(cues, t.cuesFor(uri, SubtitleChoice.ONLINE, written = null))
        assertEquals(cues, t.cuesFor(uri, SubtitleChoice.WRITTEN, written = cues))
        assertNull(t.cuesFor(uri, SubtitleChoice.OFF, written = cues))
        assertNull(t.cuesFor(uri, SubtitleChoice.embedded(0), written = null))
        t.setEmbedded(uri, 0, cues)
        assertEquals(cues, t.cuesFor(uri, SubtitleChoice.embedded(0), written = null))
        assertNull(t.cuesFor(uri, SubtitleChoice.WRITTEN, written = emptyList())) // nothing to show
    }

    @Test
    fun offsetSteps() {
        val t = SubtitleTracks()
        assertEquals(500L, t.adjustOffset(uri, SubtitleTracks.STEP_MS))
        assertEquals(1000L, t.adjustOffset(uri, SubtitleTracks.STEP_MS))
        assertEquals(-500L, t.adjustOffset(uri, -3 * SubtitleTracks.STEP_MS))
        assertEquals(-500L, t.offsetMs(uri))
        assertEquals(0L, t.offsetMs("other"))
        assertEquals(SubtitleTracks.MAX_OFFSET_MS, t.adjustOffset(uri, 100 * 60_000L))
        t.resetOffset(uri)
        assertEquals(0L, t.offsetMs(uri))
    }

    @Test
    fun timedMovesByOffsetAndStreamStart() {
        // Half a second later.
        assertEquals(listOf(Cue(10.5, 12.5, "Hello"), Cue(20.5, 22.5, "World")), SubtitleTracks.timed(cues, 0, 500))
        // A second earlier.
        assertEquals(9.0, SubtitleTracks.timed(cues, 0, -1000).first().start, 1e-9)
        // Converted from 15 s: the first cue is over, the second starts at 5 s (+0.5 s later).
        assertEquals(listOf(Cue(5.5, 7.5, "World")), SubtitleTracks.timed(cues, 15_000, 500))
        assertEquals(cues, SubtitleTracks.timed(cues, 0, 0))
    }

    @Test
    fun formatsTheOffset() {
        assertEquals("+0.5 s", SubtitleTracks.formatOffset(500))
        assertEquals("−1.5 s", SubtitleTracks.formatOffset(-1500))
        assertEquals("0.0 s", SubtitleTracks.formatOffset(0))
    }
}
