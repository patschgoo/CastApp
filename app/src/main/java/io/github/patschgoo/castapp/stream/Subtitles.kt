package io.github.patschgoo.castapp.stream

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Subtitle helpers: Cast devices want WebVTT, most downloaded subtitles are SRT. */
object Subtitles {
    private val SRT_TIME = Regex("""(\d{1,2}:\d{2}:\d{2}),(\d{1,3})""")

    fun isSubtitleFile(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".srt") || n.endsWith(".vtt")
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
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trimStart('﻿')
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
}
