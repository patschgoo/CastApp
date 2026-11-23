package io.github.patschgoo.castapp.service

import io.github.patschgoo.castapp.subtitles.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun eachSubtitleFileIsAChoice() {
        // The first file keeps the old key, so a "file" pick still means the shown one.
        assertEquals("file", SubtitleChoice.file(0).key)
        assertEquals(SubtitleChoice.FILE, SubtitleChoice.file(0))
        assertEquals(SubtitleChoice.FILE, SubtitleChoice.fromKey("file0"))
        assertEquals("file12", SubtitleChoice.file(12).key)
        assertEquals(SubtitleChoice.file(12), SubtitleChoice.fromKey("file12"))
        assertNull(SubtitleChoice.fromKey("file-1"))
        assertNull(SubtitleChoice.fromKey("filex"))
        // Without a pick the first file plays, a picked other one while it is there.
        val t = SubtitleTracks()
        val available = listOf(SubtitleChoice.file(0), SubtitleChoice.file(1), SubtitleChoice.file(2))
        assertEquals(SubtitleChoice.FILE, t.effective(uri, available))
        t.choose(uri, SubtitleChoice.file(2))
        assertEquals(SubtitleChoice.file(2), t.effective(uri, available))
        assertEquals(SubtitleChoice.FILE, t.effective(uri, available.take(2)))
        // Each file's cues are kept apart, by the file's address.
        val files = listOf("smb://src/Vela 01.English.srt", "smb://src/Vela 01.German.srt", "smb://src/Vela 01.Arabic.srt")
        val other = listOf(Cue(1.0, 2.0, "Hallo"))
        t.setFileCues(uri, files[0], cues)
        t.setFileCues(uri, files[2], other)
        assertEquals(cues, t.cuesFor(uri, SubtitleChoice.FILE, written = null, files = files))
        assertEquals(other, t.cuesFor(uri, SubtitleChoice.file(2), written = null, files = files))
        assertNull(t.cuesFor(uri, SubtitleChoice.file(1), written = null, files = files))
        assertNull(t.cuesFor(uri, SubtitleChoice.file(5), written = null, files = files))
        assertNull(t.fileCues("content://media/other", files[0]))
    }

    @Test
    fun aPickedFileAndItsCuesFollowTheFileWhenTheListChanges() {
        val en = "https://dav.example/Vela 02.English.srt"
        val de = "https://dav.example/Vela 02.German.srt"
        val ar = "https://dav.example/Vela 02.Arabic.srt"
        val t = SubtitleTracks()
        fun available(files: List<String>) = files.indices.map { SubtitleChoice.file(it) }
        // Listed with English first; German picked (the second), and read.
        val before = listOf(en, de, ar)
        t.choose(uri, SubtitleChoice.file(1), before)
        val german = listOf(Cue(1.0, 2.0, "Hallo"))
        t.setFileCues(uri, de, german)
        assertEquals(SubtitleChoice.file(1), t.effective(uri, available(before), before))
        // The queue is replaced: the same video, its files now ranked German first (another language setting).
        val after = listOf(de, en, ar)
        assertEquals(SubtitleChoice.file(0), t.chosen(uri, after))
        assertEquals(SubtitleChoice.file(0), t.effective(uri, available(after), after))
        assertEquals(german, t.cuesFor(uri, t.effective(uri, available(after), after), written = null, files = after))
        // English (now second) was never read: its place doesn't hand out German cues.
        assertNull(t.cuesFor(uri, SubtitleChoice.file(1), written = null, files = after))
        // The picked file is gone: the first one plays.
        val without = listOf(en, ar)
        assertNull(t.chosen(uri, without))
        assertEquals(SubtitleChoice.FILE, t.effective(uri, available(without), without))
        // Other picks don't depend on the files.
        t.choose(uri, SubtitleChoice.OFF, after)
        assertEquals(SubtitleChoice.OFF, t.effective(uri, available(without), without))
    }

    @Test
    fun writingSubtitlesNextToSubtitleFiles() {
        val t = SubtitleTracks()
        val bare = MediaItem("Vela 03.mkv", "smb://src/Vela 03.mkv", "video/x-matroska")
        val srt = bare.copy(subtitleUri = "smb://src/Vela 03.English.srt")
        val ass = bare.copy(subtitleUri = "smb://src/Vela 03.English.ass", moreSubtitles = listOf("smb://src/Vela 03.German.SSA"))
        // A picked file whose address has no name: the name it was picked with counts.
        val picked = MediaItem(
            "Vela 04.mkv", "content://docs/document/msf%3A40", "video/x-matroska",
            subtitleUri = "content://docs/document/msf%3A41", subtitleNames = mapOf("content://docs/document/msf%3A41" to "Vela 04.ass"),
        )
        val file = SubtitleChoice.FILE
        assertTrue(t.mayWrite(bare, SubtitleChoice.OFF))
        // An SRT shows: nothing to write.
        assertFalse(t.mayWrite(srt, file))
        // Only ASS/SSA files: subtitles may be written, also by themselves (the ASS file keeps showing).
        assertTrue(t.mayWrite(ass, file))
        assertTrue(t.mayWrite(picked, file))
        // The SRT shown turned out to hold no text: as if there were none.
        t.setFileCues(srt.uri, "smb://src/Vela 03.English.srt", emptyList())
        assertTrue(t.mayWrite(srt, file))
        // Never for photos, live streams and HLS; written ones can be kept for any file with sound.
        assertFalse(t.mayWrite(MediaItem("Pic.jpg", "content://p/1", "image/jpeg"), SubtitleChoice.OFF))
        assertFalse(t.mayWrite(bare.copy(live = true), SubtitleChoice.OFF))
        assertFalse(t.mayWrite(MediaItem("Show", "https://example.com/a.m3u8", "application/x-mpegurl"), SubtitleChoice.OFF))
        assertTrue(SubtitleTracks.canHaveWritten(srt))
        assertTrue(SubtitleTracks.canHaveWritten(ass))
        assertTrue(SubtitleTracks.canHaveWritten(MediaItem("Song.mp3", "content://p/2", "audio/mpeg")))
        assertFalse(SubtitleTracks.canHaveWritten(bare.copy(live = true)))
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
    fun anAppMayAskToStartWithoutSubtitles() {
        val t = SubtitleTracks()
        val available = listOf(SubtitleChoice.FILE, SubtitleChoice.file(1))
        assertEquals(SubtitleChoice.OFF, t.effective(uri, available, startOff = true))
        // Until something is picked on the phone.
        t.choose(uri, SubtitleChoice.file(1))
        assertEquals(SubtitleChoice.file(1), t.effective(uri, available, startOff = true))
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
