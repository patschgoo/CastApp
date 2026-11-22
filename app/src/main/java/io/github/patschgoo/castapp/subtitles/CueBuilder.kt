package io.github.patschgoo.castapp.subtitles

import org.json.JSONObject

/** A recognised word with its time in seconds. */
data class Word(val text: String, val start: Double, val end: Double)

/** One subtitle on screen. */
data class Cue(val start: Double, val end: Double, val text: String)

/**
 * Turns the words a speech recogniser heard into readable subtitles: a new
 * subtitle starts at pauses, after a few seconds, or when two lines are full.
 */
object CueBuilder {
    const val LINE = 42
    private const val MAX_CHARS = LINE * 2
    private const val MAX_SECONDS = 6.0
    private const val PAUSE = 0.8

    /** Words from a Vosk result ({"result":[{"word","start","end"}…]}), shifted by [offsetSeconds]. */
    fun wordsFromVosk(json: String, offsetSeconds: Double = 0.0): List<Word> {
        val o = try { JSONObject(json) } catch (_: Exception) { return emptyList() }
        val arr = o.optJSONArray("result") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val w = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = w.optString("word").trim()
            if (text.isEmpty() || text == "[unk]") null
            else Word(text, w.optDouble("start") + offsetSeconds, w.optDouble("end") + offsetSeconds)
        }
    }

    fun build(words: List<Word>): List<Cue> {
        val cues = ArrayList<Cue>()
        var current = ArrayList<Word>()
        fun flush() {
            if (current.isEmpty()) return
            val text = wrap(current.joinToString(" ") { it.text })
            // Keep short subtitles on screen long enough to read.
            val end = maxOf(current.last().end, current.first().start + 1.2)
            cues += Cue(current.first().start, end, text)
            current = ArrayList()
        }
        for (w in words) {
            val last = current.lastOrNull()
            val chars = current.sumOf { it.text.length + 1 } + w.text.length
            if (last != null && (w.start - last.end > PAUSE || w.end - current.first().start > MAX_SECONDS || chars > MAX_CHARS)) flush()
            current.add(w)
        }
        flush()
        // Never overlap the next subtitle.
        return cues.mapIndexed { i, c -> if (i + 1 < cues.size && c.end > cues[i + 1].start) c.copy(end = cues[i + 1].start) else c }
    }

    /** Splits text into at most two balanced lines. */
    fun wrap(text: String): String {
        if (text.length <= LINE) return text
        val mid = text.length / 2
        var best = -1
        for (i in text.indices) if (text[i] == ' ' && (best < 0 || kotlin.math.abs(i - mid) < kotlin.math.abs(best - mid))) best = i
        return if (best < 0) text else text.substring(0, best) + "\n" + text.substring(best + 1)
    }

    fun toWebVtt(cues: List<Cue>): String = buildString {
        append("WEBVTT\n\n")
        for (c in cues) {
            append(time(c.start, '.')).append(" --> ").append(time(c.end, '.')).append('\n')
            append(c.text).append("\n\n")
        }
    }

    fun toSrt(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n')
            append(time(c.start, ',')).append(" --> ").append(time(c.end, ',')).append('\n')
            append(c.text).append("\n\n")
        }
    }

    fun time(seconds: Double, sep: Char): String {
        val ms = (seconds * 1000).toLong().coerceAtLeast(0)
        return "%02d:%02d:%02d%c%03d".format(java.util.Locale.ROOT, ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, sep, ms % 1000)
    }

    /** The cues [offsetSeconds] earlier (converted video starting in the middle). */
    fun shift(cues: List<Cue>, offsetSeconds: Double): List<Cue> =
        if (offsetSeconds == 0.0) cues
        else cues.filter { it.end > offsetSeconds }.map { Cue((it.start - offsetSeconds).coerceAtLeast(0.0), it.end - offsetSeconds, it.text) }

    fun toJson(cues: List<Cue>): String = org.json.JSONArray().also { a ->
        cues.forEach { a.put(org.json.JSONArray().put(it.start).put(it.end).put(it.text)) }
    }.toString()

    fun fromJson(json: String): List<Cue> {
        val a = org.json.JSONArray(json)
        return (0 until a.length()).map { i -> a.getJSONArray(i).let { Cue(it.getDouble(0), it.getDouble(1), it.getString(2)) } }
    }
}
