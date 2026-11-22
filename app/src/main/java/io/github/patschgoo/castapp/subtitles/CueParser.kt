package io.github.patschgoo.castapp.subtitles

/**
 * Reads SRT, WebVTT and ASS/SSA subtitle files into cues, so every subtitle source (the
 * file next to the video, tracks inside it, written or found online) can be moved in time
 * and served the same way.
 */
object CueParser {
    private val TIME_LINE = Regex("""^\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val TAGS = Regex("""<[^>]+>|\{\\[^}]*\}""")

    /** Reads [text] as ASS/SSA when it looks like it (an [Events] section or Dialogue lines), else as SRT/WebVTT. */
    fun parse(text: String): List<Cue> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trimStart(Char(0xFEFF))
        if (Ass.looksLike(normalized)) Ass.parse(normalized).let { if (it.isNotEmpty()) return it }
        val blocks = normalized.split(Regex("\n\\s*\n"))
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

    /**
     * Advanced SubStation Alpha (.ass) and SubStation Alpha (.ssa): the text of the Dialogue
     * lines of the [Events] section, without styling. Lines shown at the same time become one cue.
     */
    private object Ass {
        private val EVENTS = Regex("""^\s*\[events]\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        private val DIALOGUE = Regex("""^\s*dialogue\s*:""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        /** The v4+ (.ass) event fields, for files without a Format line (.ssa has "Marked" first, the same places). */
        private val DEFAULT_FORMAT = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        private val TIME = Regex("""^\s*(\d+):(\d{1,2}):(\d{1,2}(?:[.,]\d+)?)\s*$""")
        /** {\i1}, {\an8}, {\pos(10,20)}; renderers show nothing in braces. */
        private val OVERRIDES = Regex("""\{[^}]*\}""")
        /** {\p1} turns on drawing mode: the text is vector shapes, not words. */
        private val DRAWING = Regex("""\{[^}]*\\p[1-9]""")
        /** "WrapStyle: 2" in [Script Info]: no wrapping by the renderer, and \n breaks the line too. */
        private val WRAP_STYLE = Regex("""^\s*wrapstyle\s*:\s*(\d+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        private val SPACES = Regex(" {2,}")

        fun looksLike(text: String) = EVENTS.containsMatchIn(text) || DIALOGUE.containsMatchIn(text)

        fun parse(text: String): List<Cue> {
            val sectioned = EVENTS.containsMatchIn(text)
            // libass and VSFilter break the line at \n only with WrapStyle 2; otherwise it is a space.
            val noWrap = WRAP_STYLE.find(text)?.groupValues?.get(1)?.toIntOrNull() == 2
            var section = ""
            var format = DEFAULT_FORMAT
            val cues = ArrayList<Cue>()
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length - 1).trim().lowercase()
                    continue
                }
                if (sectioned && section != "events") continue
                val colon = line.indexOf(':')
                if (colon < 0) continue
                val value = line.substring(colon + 1)
                when (line.substring(0, colon).trim().lowercase()) {
                    "format" -> {
                        val f = value.split(',').map { it.trim().lowercase() }
                        // [V4+ Styles] has a Format line too; only the events' one names Start, End and Text.
                        if ("start" in f && "end" in f && "text" in f) format = f
                    }
                    "dialogue" -> event(value, format, noWrap)?.let(cues::add)
                    // Comment lines and the rest are not shown.
                }
            }
            return merge(cues)
        }

        /** [noWrap] (WrapStyle 2): \n breaks the line, else it is a space; \N always breaks it. */
        private fun event(value: String, format: List<String>, noWrap: Boolean): Cue? {
            // Text comes last and may hold commas: split only as often as there are fields.
            val fields = value.split(',', limit = format.size)
            if (fields.size < format.size) return null
            val start = time(fields[format.indexOf("start")]) ?: return null
            val end = time(fields[format.indexOf("end")]) ?: return null
            val raw = fields[format.indexOf("text")]
            if (DRAWING.containsMatchIn(raw)) return null
            val body = OVERRIDES.replace(raw, "")
                .replace("\\N", "\n").replace("\\n", if (noWrap) "\n" else " ").replace("\\h", " ")
                .lines().map { SPACES.replace(it, " ").trim() }.filter { it.isNotEmpty() }
                .joinToString("\n")
            if (body.isEmpty() || end <= start) return null
            return Cue(start, end, body)
        }

        /** "0:01:02.50" → 62.5 */
        private fun time(t: String): Double? {
            val m = TIME.find(t) ?: return null
            val (h, min, s) = m.destructured
            return h.toLong() * 3600 + min.toLong() * 60 + s.replace(',', '.').toDouble()
        }

        /**
         * One cue per time: the same line twice (layered outlines) once, different lines at the
         * same time (two speakers, a sign) together. In time order.
         */
        private fun merge(cues: List<Cue>): List<Cue> {
            val byTime = LinkedHashMap<Pair<Double, Double>, MutableList<String>>()
            for (c in cues) {
                val texts = byTime.getOrPut(c.start to c.end) { ArrayList() }
                if (c.text !in texts) texts += c.text
            }
            return byTime.map { (t, texts) -> Cue(t.first, t.second, texts.joinToString("\n")) }.sortedBy { it.start }
        }
    }
}
