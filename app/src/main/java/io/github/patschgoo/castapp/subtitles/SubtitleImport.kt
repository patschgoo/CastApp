package io.github.patschgoo.castapp.subtitles

import io.github.patschgoo.castapp.stream.Subtitles

/**
 * Reads SRT or WebVTT subtitles (e.g. downloaded ones) into [Cue]s, so they are served
 * and shifted the same way as subtitles written on the phone.
 */
object SubtitleImport {
    private val TIME_LINE = Regex("""^\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    /** {\an8}-style positioning codes some SRT files carry. */
    private val ASS_TAGS = Regex("""\{\\[^}]*\}""")

    fun parse(text: String): List<Cue> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trimStart(Char(0xFEFF))
        val cues = ArrayList<Cue>()
        for (block in normalized.split(Regex("\n\\s*\n"))) {
            val lines = block.lines()
            val timeIdx = lines.indexOfFirst { it.contains("-->") }
            if (timeIdx < 0) continue // WEBVTT header, NOTE, STYLE, REGION blocks
            val m = TIME_LINE.find(lines[timeIdx]) ?: continue
            val start = Subtitles.parseTime(m.groupValues[1]) / 1000.0
            val end = Subtitles.parseTime(m.groupValues[2]) / 1000.0
            val body = lines.drop(timeIdx + 1)
                .map { it.replace(ASS_TAGS, "").trimEnd() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
            if (body.isEmpty() || end <= start) continue
            cues += Cue(start, end, body)
        }
        return cues.sortedBy { it.start }
    }
}
