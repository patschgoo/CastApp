package io.github.patschgoo.castapp.subtitles

/**
 * Reads SRT and WebVTT subtitle files into cues, so every subtitle source (the
 * file next to the video, tracks inside it, written or found online) can be
 * moved in time and served the same way.
 */
object CueParser {
    private val TIME_LINE = Regex("""^\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val TAGS = Regex("""<[^>]+>|\{\\[^}]*\}""")

    fun parse(text: String): List<Cue> {
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').trimStart(Char(0xFEFF)).split(Regex("\n\\s*\n"))
        val cues = ArrayList<Cue>()
        for (block in blocks) {
            val lines = block.lines()
            val timeIdx = lines.indexOfFirst { TIME_LINE.containsMatchIn(it) }
            if (timeIdx < 0) continue
            val m = TIME_LINE.find(lines[timeIdx]) ?: continue
            val start = seconds(m.groupValues[1])
            val end = seconds(m.groupValues[2])
            val body = lines.drop(timeIdx + 1).map { clean(it) }.filter { it.isNotBlank() }.joinToString("\n")
            if (body.isEmpty() || end <= start) continue
            cues += Cue(start, end, body)
        }
        return cues.sortedBy { it.start }
    }

    /** Drops HTML-like and ASS override tags the TV would otherwise show as text. */
    fun clean(line: String): String = TAGS.replace(line, "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()

    private fun seconds(t: String): Double {
        val parts = t.replace(',', '.').split(':')
        val sec = parts.last().toDouble()
        val min = parts.getOrNull(parts.size - 2)?.toLong() ?: 0
        val hours = if (parts.size >= 3) parts[0].toLong() else 0
        return hours * 3600 + min * 60 + sec
    }

    /** The cues [offsetMs] later (positive) or earlier (negative), for the timing buttons. */
    fun delay(cues: List<Cue>, offsetMs: Long): List<Cue> = if (offsetMs == 0L) cues else CueBuilder.shift(cues, -offsetMs / 1000.0)
}
