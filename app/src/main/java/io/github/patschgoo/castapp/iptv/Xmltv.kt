package io.github.patschgoo.castapp.iptv

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.Writer
import java.nio.charset.Charset
import java.text.Normalizer

/** One entry of the TV guide. Times are milliseconds since 1970 (UTC). */
data class Programme(val start: Long, val stop: Long, val title: String, val desc: String? = null) {
    fun isOn(at: Long): Boolean = at in start until stop

    /** 0..1: how far into the programme [at] is. */
    fun progress(at: Long): Float = if (stop <= start) 0f else ((at - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

/**
 * The part of a TV guide that matters for one playlist: programmes per XMLTV channel
 * id (lower case), and the XMLTV channels that matched a playlist channel by name.
 */
class EpgData(
    val programmes: Map<String, List<Programme>>,
    /** Normalised channel name ([EpgNames.normalise]) to XMLTV channel id (lower case). */
    val names: Map<String, String>,
    val fetchedAt: Long,
    /** Which guide addresses this was built from; a different set invalidates the copy on disk. */
    val signature: String = "",
) {
    /** Programmes of [channel], by its tvg-id first, then by its name; sorted by start time. */
    fun forChannel(channel: Channel): List<Programme> {
        channel.tvgId?.lowercase()?.let { id -> programmes[id]?.let { if (it.isNotEmpty()) return it } }
        val byName = names[EpgNames.normalise(channel.name)] ?: return emptyList()
        return programmes[byName].orEmpty()
    }

    fun nowAndNext(channel: Channel, at: Long): Pair<Programme?, Programme?> {
        val list = forChannel(channel)
        val now = list.firstOrNull { it.isOn(at) }
        val next = list.firstOrNull { it.start >= (now?.stop ?: at) && it.start > at }
        return now to next
    }

    val isEmpty: Boolean get() = programmes.isEmpty()

    fun write(out: Writer) {
        out.write("EPG1\t$fetchedAt\t${signature.clean()}\n")
        for ((name, id) in names) out.write("C\t${name.clean()}\t${id.clean()}\n")
        for ((id, list) in programmes) {
            for (p in list) out.write("P\t${id.clean()}\t${p.start}\t${p.stop}\t${p.title.clean()}\t${p.desc.orEmpty().clean()}\n")
        }
        out.flush()
    }

    private fun String.clean() = replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    companion object {
        val EMPTY = EpgData(emptyMap(), emptyMap(), 0L)

        fun read(input: Reader): EpgData {
            val reader = if (input is BufferedReader) input else BufferedReader(input)
            val head = reader.readLine()?.split('\t') ?: throw java.io.IOException("Empty guide copy")
            if (head.firstOrNull() != "EPG1") throw java.io.IOException("Not a guide copy")
            val fetched = head.getOrNull(1)?.toLongOrNull() ?: 0L
            val signature = head.getOrNull(2).orEmpty()
            val programmes = HashMap<String, ArrayList<Programme>>()
            val names = HashMap<String, String>()
            while (true) {
                val parts = (reader.readLine() ?: break).split('\t')
                when (parts[0]) {
                    "C" -> if (parts.size >= 3) names[parts[1]] = parts[2]
                    "P" -> if (parts.size >= 5) {
                        val start = parts[2].toLongOrNull() ?: continue
                        val stop = parts[3].toLongOrNull() ?: continue
                        programmes.getOrPut(parts[1]) { ArrayList() } += Programme(start, stop, parts[4], parts.getOrNull(5)?.ifEmpty { null })
                    }
                }
            }
            return EpgData(programmes, names, fetched, signature)
        }

        /** Joins the guides of several playlists (for the favourites view). */
        fun merge(all: List<EpgData>): EpgData {
            if (all.size == 1) return all[0]
            val programmes = HashMap<String, List<Programme>>()
            val names = HashMap<String, String>()
            for (g in all) {
                for ((id, list) in g.programmes) {
                    programmes[id] = programmes[id]?.let { (it + list).distinct().sortedBy { p -> p.start } } ?: list
                }
                names.putAll(g.names)
            }
            return EpgData(programmes, names, all.maxOfOrNull { it.fetchedAt } ?: 0L)
        }
    }
}

/** Channel names as the guide and the playlist spell them differently ("DE: Das Erste HD" / "Das Erste"). */
object EpgNames {
    private val COUNTRY_PREFIX = Regex("""^\s*(\|[A-Za-z0-9]{2,4}\|\s*|[A-Za-z]{2,3}\s*[:|]\s*)""")
    private val QUALITY = setOf("hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "1080p", "720p", "1080", "720")
    private val MARKS = Regex("""\p{M}+""")

    fun normalise(name: String): String {
        val plain = MARKS.replace(Normalizer.normalize(name.replace("ß", "ss"), Normalizer.Form.NFD), "")
        val stripped = COUNTRY_PREFIX.replace(plain, "").ifBlank { plain }
        val tokens = stripped.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
        val kept = tokens.filter { it !in QUALITY }
        return (kept.ifEmpty { tokens }).joinToString("")
    }
}

/** What [Xmltv.parse] looks for: the playlist's channels, and the time window to keep. */
class GuideWindow(val from: Long, val to: Long) {
    companion object {
        const val BACK_MS = 2 * 3600_000L
        const val AHEAD_MS = 36 * 3600_000L
        fun around(now: Long) = GuideWindow(now - BACK_MS, now + AHEAD_MS)
    }
}

/**
 * A small streaming XMLTV reader in plain Kotlin (Android's XmlPullParser is not
 * available in JVM tests). Only programmes of the given channels and time window are
 * kept, so even a guide of several hundred MB needs little memory.
 */
object Xmltv {
    private const val MAX_DESC = 400

    fun parse(
        input: InputStream,
        channels: List<Channel>,
        now: Long,
        window: GuideWindow = GuideWindow.around(now),
        signature: String = "",
    ): EpgData {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
        return parse(InputStreamReader(buffered, charsetOf(buffered)), channels, now, window, signature)
    }

    fun parse(
        reader: Reader,
        channels: List<Channel>,
        now: Long,
        window: GuideWindow = GuideWindow.around(now),
        signature: String = "",
    ): EpgData {
        val wantedIds = HashSet<String>()
        val wantedNames = HashSet<String>()
        for (c in channels) {
            c.tvgId?.lowercase()?.let { wantedIds += it }
            EpgNames.normalise(c.name).takeIf { it.isNotEmpty() }?.let { wantedNames += it }
        }
        val relevant = HashSet<String>()
        val names = HashMap<String, String>()
        val programmes = HashMap<String, ArrayList<Programme>>()
        val scan = XmlScanner(reader)

        try {
            while (true) {
                val kind = scan.next()
                if (kind == XmlScanner.Kind.EOF) break
                if (kind != XmlScanner.Kind.START) continue
                when (scan.name) {
                    "channel" -> {
                        val id = scan.attrs["id"]?.trim()?.lowercase().orEmpty()
                        val displayNames = ArrayList<String>()
                        if (!scan.selfClosing) {
                            while (true) {
                                val k = scan.next()
                                if (k == XmlScanner.Kind.EOF || (k == XmlScanner.Kind.END && scan.name == "channel")) break
                                if (k == XmlScanner.Kind.START && scan.name == "display-name") displayNames += elementText(scan)
                            }
                        }
                        val matchedNames = displayNames.map { EpgNames.normalise(it) }.filter { it in wantedNames }
                        if (id.isNotEmpty() && (id in wantedIds || matchedNames.isNotEmpty())) {
                            relevant += id
                            for (n in matchedNames) names.putIfAbsent(n, id)
                        }
                    }
                    "programme" -> {
                        if (scan.selfClosing) continue
                        val id = scan.attrs["channel"]?.trim()?.lowercase().orEmpty()
                        val start = scan.attrs["start"]?.let { parseTime(it) }
                        val stop = scan.attrs["stop"]?.let { parseTime(it) }
                        val inWindow = start != null && (stop ?: start) > window.from && start < window.to
                        if (!inWindow || (id !in relevant && id !in wantedIds)) {
                            scan.skipElement("programme")
                            continue
                        }
                        var title = ""
                        var desc: String? = null
                        while (true) {
                            val k = scan.next()
                            if (k == XmlScanner.Kind.EOF || (k == XmlScanner.Kind.END && scan.name == "programme")) break
                            if (k != XmlScanner.Kind.START) continue
                            when (scan.name) {
                                "title" -> elementText(scan).let { if (title.isEmpty()) title = it }
                                "sub-title" -> elementText(scan).let { if (title.isEmpty() && it.isNotEmpty()) title = it }
                                "desc" -> elementText(scan).let { if (desc == null && it.isNotEmpty()) desc = it.take(MAX_DESC) }
                            }
                        }
                        if (title.isNotEmpty()) {
                            programmes.getOrPut(id) { ArrayList() } += Programme(start!!, stop ?: start, title, desc)
                        }
                    }
                }
            }
        } catch (_: java.io.EOFException) {
            // A cut-off download: keep what was read.
        } catch (e: java.io.IOException) {
            // Corrupt gzip or a dropped connection in the middle: keep what we have if it is something.
            if (programmes.isEmpty()) throw e
        }

        val sorted = HashMap<String, List<Programme>>(programmes.size * 2)
        for ((id, list) in programmes) {
            list.sortBy { it.start }
            val fixed = ArrayList<Programme>(list.size)
            for ((i, p) in list.withIndex()) {
                val stop = if (p.stop > p.start) p.stop else list.getOrNull(i + 1)?.start?.takeIf { it > p.start } ?: (p.start + 3600_000L)
                // Overlapping or repeated entries (guides listing a programme twice) are dropped.
                if (fixed.isNotEmpty() && fixed.last().start == p.start) continue
                fixed += if (stop == p.stop) p else p.copy(stop = stop)
            }
            sorted[id] = fixed
        }
        // Only keep name matches whose programmes survived.
        return EpgData(sorted, names.filterValues { it in sorted }, now, signature)
    }

    private fun elementText(scan: XmlScanner): String {
        if (scan.selfClosing) return ""
        val name = scan.name
        val sb = StringBuilder()
        while (true) {
            when (scan.next()) {
                XmlScanner.Kind.TEXT -> sb.append(scan.text)
                XmlScanner.Kind.END -> if (scan.name == name) break
                XmlScanner.Kind.EOF -> break
                else -> Unit
            }
        }
        return sb.toString().trim()
    }

    /** Peeks at the XML prolog for `encoding="…"`; UTF-8 when absent or unknown. */
    private fun charsetOf(input: BufferedInputStream): Charset {
        input.mark(512)
        val head = ByteArray(200)
        var n = 0
        while (n < head.size) {
            val r = input.read(head, n, head.size - n)
            if (r < 0) break
            n += r
        }
        input.reset()
        val m = Regex("""encoding\s*=\s*["']([A-Za-z0-9_.:-]+)["']""").find(String(head, 0, n, Charsets.ISO_8859_1))
        return try {
            m?.let { Charset.forName(it.groupValues[1]) } ?: Charsets.UTF_8
        } catch (_: Exception) {
            Charsets.UTF_8
        }
    }

    /**
     * XMLTV time stamps: `20261122203000 +0100`, also without seconds or zone (UTC),
     * with `Z`/`UTC`/`GMT`, or a zone written `+01:00` / `+01`.
     */
    fun parseTime(raw: String): Long? {
        val s = raw.trim()
        var i = 0
        while (i < s.length && s[i].isDigit()) i++
        if (i < 8) return null
        fun part(from: Int, len: Int) = if (from + len <= i) s.substring(from, from + len).toInt() else 0
        val year = part(0, 4)
        val month = part(4, 2)
        val day = part(6, 2)
        val hour = part(8, 2)
        val minute = part(10, 2)
        val second = part(12, 2)
        if (month !in 1..12 || day !in 1..31 || hour > 24 || minute > 59 || second > 60) return null
        var offsetMin = 0
        val zone = s.substring(i).trim().substringBefore(' ')
        if (zone.isNotEmpty() && (zone[0] == '+' || zone[0] == '-')) {
            val digits = zone.substring(1).replace(":", "")
            if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
            val h = digits.take(2).toInt()
            val m = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
            offsetMin = (h * 60 + m) * if (zone[0] == '-') -1 else 1
        }
        val days = daysFromCivil(year, month, day)
        return ((days * 24 + hour) * 60 + minute - offsetMin) * 60_000L + second * 1000L
    }

    /** Days since 1970-01-01 of a calendar date (proleptic Gregorian). */
    internal fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }
}

/**
 * Tolerant tokenizer for XML: start tags (with attributes), end tags and text, with
 * entities decoded. Comments, processing instructions and DOCTYPE are skipped; CDATA
 * becomes text. No validation: broken or truncated files just end early.
 */
internal class XmlScanner(private val reader: Reader) {
    enum class Kind { START, END, TEXT, EOF }

    private val buf = CharArray(32 * 1024)
    private var len = 0
    private var pos = 0
    private var tagPending = false

    var name = ""
        private set
    var attrs: Map<String, String> = emptyMap()
        private set
    var text = ""
        private set
    var selfClosing = false
        private set

    private fun read(): Int {
        if (pos >= len && !fill()) return -1
        return buf[pos++].code
    }

    private fun peek(): Int {
        if (pos >= len && !fill()) return -1
        return buf[pos].code
    }

    private fun fill(): Boolean {
        val n = reader.read(buf, 0, buf.size)
        if (n <= 0) {
            len = 0
            pos = 0
            return false
        }
        len = n
        pos = 0
        return true
    }

    fun next(): Kind {
        selfClosing = false
        while (true) {
            if (!tagPending) {
                // Character data up to the next tag.
                var sb: StringBuilder? = null
                var sawText = false
                while (true) {
                    val c = read()
                    if (c < 0) {
                        if (sb != null && sawText) {
                            text = sb.toString()
                            return Kind.TEXT
                        }
                        return Kind.EOF
                    }
                    if (c == '<'.code) {
                        val p = peek()
                        if (p == '?'.code) {
                            skipUntil("?>")
                            continue
                        }
                        if (p == '!'.code) {
                            read()
                            when {
                                matches("--") -> skipUntil("-->")
                                matches("[CDATA[") -> {
                                    if (sb == null) sb = StringBuilder()
                                    sb.append(readUntil("]]>"))
                                    sawText = true
                                }
                                else -> skipDoctype()
                            }
                            continue
                        }
                        tagPending = true
                        break
                    }
                    if (c == '&'.code) {
                        if (sb == null) sb = StringBuilder()
                        appendEntity(sb)
                        sawText = true
                        continue
                    }
                    if (sb == null) {
                        if (c <= ' '.code) continue // leading whitespace
                        sb = StringBuilder()
                    }
                    sb.append(c.toChar())
                    sawText = true
                }
                if (sb != null && sawText) {
                    text = sb.toString()
                    return Kind.TEXT
                }
            }
            tagPending = false
            return readTag()
        }
    }

    /** Skips to the end of the current element; used for programmes we do not want. */
    fun skipElement(elementName: String) {
        val end = "</$elementName"
        var matched = 0
        while (true) {
            val c = read()
            if (c < 0) return
            if (c == end[matched].code) {
                matched++
                if (matched == end.length) {
                    skipUntil(">")
                    return
                }
            } else {
                matched = if (c == end[0].code) 1 else 0
            }
        }
    }

    private fun readTag(): Kind {
        val first = read()
        if (first < 0) return Kind.EOF
        if (first == '/'.code) {
            val sb = StringBuilder()
            while (true) {
                val c = read()
                if (c < 0 || c == '>'.code) break
                sb.append(c.toChar())
            }
            name = sb.toString().trim()
            attrs = emptyMap()
            return Kind.END
        }
        val nameBuilder = StringBuilder().append(first.toChar())
        var c = read()
        while (c >= 0 && c > ' '.code && c != '>'.code && c != '/'.code) {
            nameBuilder.append(c.toChar())
            c = read()
        }
        name = nameBuilder.toString()
        var map: HashMap<String, String>? = null
        while (true) {
            while (c >= 0 && c <= ' '.code) c = read()
            if (c < 0) break
            if (c == '>'.code) break
            if (c == '/'.code) {
                selfClosing = true
                c = read()
                continue
            }
            val key = StringBuilder()
            while (c >= 0 && c > ' '.code && c != '='.code && c != '>'.code && c != '/'.code) {
                key.append(c.toChar())
                c = read()
            }
            while (c >= 0 && c <= ' '.code) c = read()
            var value = ""
            if (c == '='.code) {
                c = read()
                while (c >= 0 && c <= ' '.code) c = read()
                val sb = StringBuilder()
                if (c == '"'.code || c == '\''.code) {
                    val quote = c
                    while (true) {
                        val d = read()
                        if (d < 0 || d == quote) break
                        if (d == '&'.code) appendEntity(sb) else sb.append(d.toChar())
                    }
                    c = read()
                } else {
                    while (c >= 0 && c > ' '.code && c != '>'.code) {
                        sb.append(c.toChar())
                        c = read()
                    }
                }
                value = sb.toString()
            }
            if (key.isNotEmpty()) {
                if (map == null) map = HashMap(8)
                map[key.toString()] = value
            }
        }
        attrs = map ?: emptyMap()
        return Kind.START
    }

    /** After a '&': reads up to ';' and appends the character, or the raw text when unknown. */
    private fun appendEntity(sb: StringBuilder) {
        val ent = StringBuilder()
        while (ent.length < 12) {
            val p = peek()
            if (p < 0 || p == '<'.code || p == '&'.code || p <= ' '.code) break
            read()
            if (p == ';'.code) {
                val decoded = decode(ent.toString())
                if (decoded != null) sb.append(decoded) else sb.append('&').append(ent).append(';')
                return
            }
            ent.append(p.toChar())
        }
        sb.append('&').append(ent)
    }

    private fun decode(e: String): String? = when {
        e == "amp" -> "&"
        e == "lt" -> "<"
        e == "gt" -> ">"
        e == "quot" -> "\""
        e == "apos" -> "'"
        e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { cp(it) }
        e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { cp(it) }
        else -> null
    }

    private fun cp(code: Int): String? = if (code in 1..0x10FFFF) String(Character.toChars(code)) else null

    /** True (and consumed) when the next characters are [s]. */
    private fun matches(s: String): Boolean {
        for (ch in s) {
            // "--" and "[CDATA[" differ in their first character, so a miss at the start consumes nothing.
            if (peek() != ch.code) return false
            read()
        }
        return true
    }

    private fun skipUntil(end: String) {
        var matched = 0
        while (true) {
            val c = read()
            if (c < 0) return
            if (c == end[matched].code) {
                matched++
                if (matched == end.length) return
            } else {
                matched = if (c == end[0].code) 1 else 0
            }
        }
    }

    private fun readUntil(end: String): String {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0) return sb.toString()
            sb.append(c.toChar())
            if (sb.length >= end.length && c == end.last().code && sb.endsWith(end)) {
                sb.setLength(sb.length - end.length)
                return sb.toString()
            }
        }
    }

    /** `<!DOCTYPE …>` possibly with an `[internal subset]`. The `<!` is already consumed. */
    private fun skipDoctype() {
        var depth = 0
        while (true) {
            val c = read()
            if (c < 0) return
            when (c) {
                '['.code -> depth++
                ']'.code -> depth--
                '>'.code -> if (depth <= 0) return
            }
        }
    }
}
