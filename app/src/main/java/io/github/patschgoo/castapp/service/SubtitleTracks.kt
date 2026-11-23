package io.github.patschgoo.castapp.service

import io.github.patschgoo.castapp.subtitles.Cue
import io.github.patschgoo.castapp.subtitles.CueBuilder
import io.github.patschgoo.castapp.subtitles.Sidecars
import java.util.concurrent.ConcurrentHashMap

/** Where the subtitles on the TV come from. */
enum class SubtitleKind {
    OFF,
    /** A subtitle file (.srt/.vtt/.ass) picked or found next to the video. */
    FILE,
    /** A text track inside the video file (MKV/MP4). */
    EMBEDDED,
    /** Written on the phone by speech recognition. */
    WRITTEN,
    /** Found online (OpenSubtitles and the like). */
    ONLINE,
}

/**
 * One choice in the subtitle picker; [track] is the embedded track's ordinal, or for a file which
 * of [MediaItem.subtitleFiles] (0 = [MediaItem.subtitleUri]).
 */
data class SubtitleChoice(val kind: SubtitleKind, val track: Int = 0) {
    /** Short form used in addresses and intents: off, file, file3, emb2, written, online. */
    val key: String
        get() = when (kind) {
            SubtitleKind.OFF -> "off"
            SubtitleKind.FILE -> if (track == 0) "file" else "file$track"
            SubtitleKind.EMBEDDED -> "emb$track"
            SubtitleKind.WRITTEN -> "written"
            SubtitleKind.ONLINE -> "online"
        }

    companion object {
        val OFF = SubtitleChoice(SubtitleKind.OFF)
        /** The video's first subtitle file ([MediaItem.subtitleUri]). */
        val FILE = SubtitleChoice(SubtitleKind.FILE)
        val WRITTEN = SubtitleChoice(SubtitleKind.WRITTEN)
        val ONLINE = SubtitleChoice(SubtitleKind.ONLINE)

        fun embedded(track: Int) = SubtitleChoice(SubtitleKind.EMBEDDED, track)

        /** Subtitle file [index] of [MediaItem.subtitleFiles]. */
        fun file(index: Int) = SubtitleChoice(SubtitleKind.FILE, index)

        fun fromKey(key: String?): SubtitleChoice? = when {
            key == null -> null
            key == "off" -> OFF
            key == "file" -> FILE
            key == "written" -> WRITTEN
            key == "online" -> ONLINE
            key.startsWith("file") -> key.removePrefix("file").toIntOrNull()?.takeIf { it >= 0 }?.let { file(it) }
            key.startsWith("emb") -> key.removePrefix("emb").toIntOrNull()?.takeIf { it >= 0 }?.let { embedded(it) }
            else -> null
        }

        /**
         * What plays when the viewer hasn't picked anything: the (first) subtitle file that
         * came with the video, else ones found online, else written ones, else none.
         * Tracks inside the file are only used when picked (reading them out takes a while).
         */
        fun default(available: Collection<SubtitleChoice>): SubtitleChoice =
            listOf(FILE, ONLINE, WRITTEN).firstOrNull { it in available } ?: OFF
    }
}

/**
 * The subtitle choice for each file of a cast and the subtitles collected for it
 * (read out of the file, found online), plus the timing correction. One place
 * decides what the TV gets, whatever the source.
 *
 * Another source plugs in by storing its cues ([setOnline]) and choosing it.
 */
class SubtitleTracks {
    /** A pick, with the address of the subtitle file picked: its place in the list can change. */
    private class Pick(val choice: SubtitleChoice, val file: String?)

    private val chosen = ConcurrentHashMap<String, Pick>()
    private val offsets = ConcurrentHashMap<String, Long>()
    private val online = ConcurrentHashMap<String, Pair<String, List<Cue>>>()
    private val embedded = ConcurrentHashMap<String, ConcurrentHashMap<Int, List<Cue>>>()
    /** Cues read from subtitle files: video address → subtitle file address → cues. */
    private val fileCues = ConcurrentHashMap<String, ConcurrentHashMap<String, List<Cue>>>()

    /**
     * Picks [choice] for the video [uri]. A subtitle file is kept by its address in [files] (the
     * video's [MediaItem.subtitleFiles]), so the pick follows it when the play list is replaced
     * or added to and the files come in another order (they are ranked by the language setting).
     */
    fun choose(uri: String, choice: SubtitleChoice, files: List<String> = emptyList()) {
        chosen[uri] = Pick(choice, files.getOrNull(choice.track)?.takeIf { choice.kind == SubtitleKind.FILE })
    }

    /**
     * The pick for [uri]; a picked subtitle file at its place in [files] now, or null when it is
     * no longer one of them.
     */
    fun chosen(uri: String, files: List<String> = emptyList()): SubtitleChoice? {
        val pick = chosen[uri] ?: return null
        val file = pick.file ?: return pick.choice
        return files.indexOf(file).takeIf { it >= 0 }?.let { SubtitleChoice.file(it) }
    }

    /**
     * The picked choice if it is still possible, else the default. [files]: the video's subtitle files now.
     * [startOff]: none until one is picked (the app that cast it asked for no subtitles).
     */
    fun effective(uri: String, available: Collection<SubtitleChoice>, files: List<String> = emptyList(), startOff: Boolean = false): SubtitleChoice {
        val c = chosen(uri, files)
        if (c == null && startOff) return SubtitleChoice.OFF
        return if (c != null && (c == SubtitleChoice.OFF || c in available)) c else SubtitleChoice.default(available)
    }

    /** Subtitles later (positive) or earlier (negative) than in the file, in ms. */
    fun offsetMs(uri: String): Long = offsets[uri] ?: 0

    fun adjustOffset(uri: String, deltaMs: Long): Long {
        val v = (offsetMs(uri) + deltaMs).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        if (v == 0L) offsets.remove(uri) else offsets[uri] = v
        return v
    }

    fun resetOffset(uri: String) {
        offsets.remove(uri)
    }

    fun setOnline(uri: String, label: String, cues: List<Cue>) {
        online[uri] = label to cues
    }

    fun onlineLabel(uri: String): String? = online[uri]?.first
    fun hasOnline(uri: String): Boolean = online[uri]?.second?.isNotEmpty() == true

    fun setEmbedded(uri: String, track: Int, cues: List<Cue>) {
        embedded.getOrPut(uri) { ConcurrentHashMap() }[track] = cues
    }

    fun embeddedCues(uri: String, track: Int): List<Cue>? = embedded[uri]?.get(track)

    /** The cues read from the subtitle file at address [file] of the video [uri]. */
    fun setFileCues(uri: String, file: String, cues: List<Cue>) {
        fileCues.getOrPut(uri) { ConcurrentHashMap() }[file] = cues
    }

    fun fileCues(uri: String, file: String): List<Cue>? = fileCues[uri]?.get(file)

    /**
     * The stored cues of [choice] (written ones come from [written], a file's by its address in
     * [files], the video's subtitle files), or null when there are none (yet).
     */
    fun cuesFor(uri: String, choice: SubtitleChoice, written: List<Cue>?, files: List<String> = emptyList()): List<Cue>? = when (choice.kind) {
        SubtitleKind.OFF -> null
        SubtitleKind.FILE -> files.getOrNull(choice.track)?.let { fileCues(uri, it) }
        SubtitleKind.EMBEDDED -> embeddedCues(uri, choice.track)
        SubtitleKind.WRITTEN -> written
        SubtitleKind.ONLINE -> online[uri]?.second
    }?.takeIf { it.isNotEmpty() }

    /**
     * Whether new subtitles may be written for [item] by speech recognition while [active] is
     * shown. Yes when no subtitle file came with it, when the one shown turned out to hold no
     * text, and when all of them are ASS/SSA (often only signs or karaoke), by hand or by itself:
     * the ASS file keeps showing and the written ones are offered next to it.
     * Written ones kept from before are offered either way ([canHaveWritten]).
     */
    fun mayWrite(item: MediaItem, active: SubtitleChoice): Boolean {
        if (!canHaveWritten(item)) return false
        val files = item.subtitleFiles
        if (files.isEmpty()) return true
        val shown = files.getOrNull(active.track)?.takeIf { active.kind == SubtitleKind.FILE }
        if (shown != null && fileCues(item.uri, shown)?.isEmpty() == true) return true
        return files.all { Sidecars.isStyled(item.subtitleName(it)) }
    }

    companion object {
        /** Subtitles written by speech recognition can be kept and shown for [item]: a video or a song, not live, not HLS. */
        fun canHaveWritten(item: MediaItem): Boolean =
            (item.type == MediaType.VIDEO || item.type == MediaType.AUDIO) && !item.live && !item.isHls

        const val STEP_MS = 500L
        const val MAX_OFFSET_MS = 10 * 60_000L

        /**
         * The cues as the TV needs them: moved by the timing correction [userOffsetMs]
         * (positive = later) and to a converted stream whose clock starts at [streamStartMs].
         */
        fun timed(cues: List<Cue>, streamStartMs: Long, userOffsetMs: Long): List<Cue> =
            CueBuilder.shift(cues, (streamStartMs - userOffsetMs) / 1000.0)

        /** "+0.5 s", "−1.0 s", "0.0 s" */
        fun formatOffset(ms: Long): String {
            val sign = when {
                ms > 0 -> "+"
                ms < 0 -> "−"
                else -> ""
            }
            return sign + "%.1f s".format(java.util.Locale.ROOT, kotlin.math.abs(ms) / 1000.0)
        }
    }
}
