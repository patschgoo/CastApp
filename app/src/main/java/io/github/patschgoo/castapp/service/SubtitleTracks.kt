package io.github.patschgoo.castapp.service

import io.github.patschgoo.castapp.subtitles.Cue
import io.github.patschgoo.castapp.subtitles.CueBuilder
import java.util.concurrent.ConcurrentHashMap

/** Where the subtitles on the TV come from. */
enum class SubtitleKind {
    OFF,
    /** The .srt/.vtt file picked or found next to the video. */
    FILE,
    /** A text track inside the video file (MKV/MP4). */
    EMBEDDED,
    /** Written on the phone by speech recognition. */
    WRITTEN,
    /** Found online (OpenSubtitles and the like). */
    ONLINE,
}

/** One choice in the subtitle picker; [track] is the embedded track's ordinal. */
data class SubtitleChoice(val kind: SubtitleKind, val track: Int = 0) {
    /** Short form used in addresses and intents: off, file, emb2, written, online. */
    val key: String
        get() = when (kind) {
            SubtitleKind.OFF -> "off"
            SubtitleKind.FILE -> "file"
            SubtitleKind.EMBEDDED -> "emb$track"
            SubtitleKind.WRITTEN -> "written"
            SubtitleKind.ONLINE -> "online"
        }

    companion object {
        val OFF = SubtitleChoice(SubtitleKind.OFF)
        val FILE = SubtitleChoice(SubtitleKind.FILE)
        val WRITTEN = SubtitleChoice(SubtitleKind.WRITTEN)
        val ONLINE = SubtitleChoice(SubtitleKind.ONLINE)

        fun embedded(track: Int) = SubtitleChoice(SubtitleKind.EMBEDDED, track)

        fun fromKey(key: String?): SubtitleChoice? = when {
            key == null -> null
            key == "off" -> OFF
            key == "file" -> FILE
            key == "written" -> WRITTEN
            key == "online" -> ONLINE
            key.startsWith("emb") -> key.removePrefix("emb").toIntOrNull()?.takeIf { it >= 0 }?.let { embedded(it) }
            else -> null
        }

        /**
         * What plays when the viewer hasn't picked anything: the subtitle file that
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
    private val chosen = ConcurrentHashMap<String, SubtitleChoice>()
    private val offsets = ConcurrentHashMap<String, Long>()
    private val online = ConcurrentHashMap<String, Pair<String, List<Cue>>>()
    private val embedded = ConcurrentHashMap<String, ConcurrentHashMap<Int, List<Cue>>>()
    private val fileCues = ConcurrentHashMap<String, List<Cue>>()

    fun choose(uri: String, choice: SubtitleChoice) {
        chosen[uri] = choice
    }

    fun chosen(uri: String): SubtitleChoice? = chosen[uri]

    /** The picked choice if it is still possible, else the default. */
    fun effective(uri: String, available: Collection<SubtitleChoice>): SubtitleChoice {
        val c = chosen[uri]
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

    fun setFileCues(uri: String, cues: List<Cue>) {
        fileCues[uri] = cues
    }

    fun fileCues(uri: String): List<Cue>? = fileCues[uri]

    /** The stored cues of [choice] (written ones come from [written]), or null when there are none (yet). */
    fun cuesFor(uri: String, choice: SubtitleChoice, written: List<Cue>?): List<Cue>? = when (choice.kind) {
        SubtitleKind.OFF -> null
        SubtitleKind.FILE -> fileCues[uri]
        SubtitleKind.EMBEDDED -> embeddedCues(uri, choice.track)
        SubtitleKind.WRITTEN -> written
        SubtitleKind.ONLINE -> online[uri]?.second
    }?.takeIf { it.isNotEmpty() }

    companion object {
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
