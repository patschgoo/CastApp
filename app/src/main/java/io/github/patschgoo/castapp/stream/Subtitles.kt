package io.github.patschgoo.castapp.stream

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Subtitle helpers: Cast devices want WebVTT, most downloaded subtitles are SRT. */
object Subtitles {
    private val BOM = Char(0xFEFF)
    private val SRT_TIME = Regex("""(\d{1,2}:\d{2}:\d{2}),(\d{1,3})""")

    /** SRT, WebVTT and ASS/SSA files (the TV gets them all as WebVTT or SRT). */
    fun isSubtitleFile(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".srt") || n.endsWith(".vtt") || n.endsWith(".ass") || n.endsWith(".ssa")
    }

    /** Decodes subtitle bytes: UTF-8/UTF-16 with BOM, UTF-8, else Windows-1252 (old SRTs with umlauts). */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    /** Converts SRT (or passes through WebVTT) to WebVTT. */
    fun toWebVtt(text: String): String {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trimStart(BOM)
        if (normalized.trimStart().startsWith("WEBVTT")) return normalized
        val out = StringBuilder("WEBVTT\n\n")
        for (line in normalized.lines()) {
            if (line.contains("-->")) {
                out.append(SRT_TIME.replace(line) { m -> m.groupValues[1].padStart(8, '0') + "." + m.groupValues[2].padEnd(3, '0') })
            } else {
                out.append(line)
            }
            out.append('\n')
        }
        return out.toString()
    }

    private val CUE_TIME = Regex("""^\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})(.*)$""")

    /**
     * Moves every cue of a WebVTT or SRT text [offsetMs] earlier (for a video that is
     * converted from the middle, so its clock starts at 0). Cues that are over by
     * then are dropped.
     */
    fun shift(text: String, offsetMs: Long): String {
        if (offsetMs == 0L) return text
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n{2,}"))
        val out = StringBuilder()
        for (block in blocks) {
            val lines = block.lines()
            val timeIdx = lines.indexOfFirst { it.contains("-->") }
            val m = if (timeIdx >= 0) CUE_TIME.find(lines[timeIdx]) else null
            if (m == null) {
                if (block.isNotBlank()) out.append(block).append("\n\n")
                continue
            }
            val sep = if (m.groupValues[1].contains(',')) ',' else '.'
            val start = parseTime(m.groupValues[1]) - offsetMs
            val end = parseTime(m.groupValues[2]) - offsetMs
            if (end <= 0) continue
            val newLines = lines.toMutableList()
            newLines[timeIdx] = formatTime(start.coerceAtLeast(0), sep) + " --> " + formatTime(end, sep) + m.groupValues[3]
            out.append(newLines.joinToString("\n")).append("\n\n")
        }
        return out.toString()
    }

    fun parseTime(t: String): Long {
        val parts = t.replace(',', '.').split(':')
        val sec = parts.last().toDouble()
        val min = parts.getOrNull(parts.size - 2)?.toLong() ?: 0
        val hours = if (parts.size >= 3) parts[0].toLong() else 0
        return ((hours * 3600 + min * 60) * 1000) + Math.round(sec * 1000)
    }

    fun formatTime(ms: Long, sep: Char): String =
        "%02d:%02d:%02d%c%03d".format(java.util.Locale.ROOT, ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, sep, ms % 1000)
}
