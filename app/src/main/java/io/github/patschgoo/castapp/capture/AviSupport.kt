package io.github.patschgoo.castapp.capture

import io.github.patschgoo.castapp.stream.MediaSource
import java.io.IOException
import java.io.InputStream

/**
 * The stream list at the start of an AVI file ("hdrl"): which picture and sound codecs it declares.
 * Media3's AVI reader drops a picture stream it has no name for (DivX 3 is "DIV3"), and so may a
 * phone's own AVI reader; this tells "the file has no picture" from "no decoder for DivX 3".
 */
object AviHeader {
    /** One stream of the file: [type] "vids", "auds", "txts"; [codec] the picture's biCompression or the sound's format tag. */
    data class Stream(val type: String, val handler: String, val codec: String)

    /** "RIFF....AVI ": an AVI file (also .divx, which is AVI inside). */
    fun isAvi(head: ByteArray): Boolean =
        head.size >= 12 && fourcc(head, 0) == "RIFF" && fourcc(head, 8) == "AVI "

    /** The streams of the AVI whose first bytes are [data] (up to the end of its "hdrl" list); empty if it isn't one. */
    fun streams(data: ByteArray): List<Stream> {
        if (!isAvi(data)) return emptyList()
        val streams = ArrayList<Stream>()
        // Top level after "RIFF size AVI ": look for LIST hdrl.
        var pos = 12
        while (pos + 12 <= data.size) {
            val id = fourcc(data, pos)
            val size = le32(data, pos + 4)
            if (size < 0) break
            if (id == "LIST" && fourcc(data, pos + 8) == "hdrl") {
                readList(data, pos + 12, minOf(data.size, pos + 8 + size), streams)
                break
            }
            pos += 8 + size + (size and 1)
        }
        return streams
    }

    /** Reads the "hdrl" list of [source]: at most [limit] bytes from the start. */
    fun read(source: MediaSource, limit: Int = HEADER_LIMIT): List<Stream> {
        val head = source.open(0).use { readUpTo(it, 12 + 12) }
        if (!isAvi(head) || head.size < 24) return emptyList()
        // RIFF header, then normally LIST hdrl right away (its size says how much to read).
        val hdrlSize = if (fourcc(head, 12) == "LIST") le32(head, 16) else -1
        val want = if (hdrlSize in 4..limit) 20 + hdrlSize else limit
        val data = source.open(0).use { readUpTo(it, minOf(want, limit)) }
        return streams(data)
    }

    /** The first picture stream's codec, null if the file declares none. */
    fun videoCodec(streams: List<Stream>): String? = streams.firstOrNull { it.type == "vids" }?.codec

    private fun readList(data: ByteArray, start: Int, end: Int, out: MutableList<Stream>) {
        var pos = start
        var type: String? = null
        var handler = ""
        while (pos + 8 <= end) {
            val id = fourcc(data, pos)
            val size = le32(data, pos + 4)
            if (size < 0) return
            val body = pos + 8
            when {
                id == "LIST" && body + 4 <= end && fourcc(data, body) == "strl" -> {
                    readList(data, body + 4, minOf(end, body + size), out)
                }
                id == "strh" && body + 8 <= end -> {
                    type = fourcc(data, body)
                    handler = fourcc(data, body + 4)
                }
                id == "strf" && type != null -> {
                    val codec = when (type) {
                        // BITMAPINFOHEADER: biSize, biWidth, biHeight, biPlanes, biBitCount, biCompression
                        "vids" -> if (body + 20 <= end) fourcc(data, body + 16) else handler
                        // WAVEFORMATEX: wFormatTag
                        "auds" -> if (body + 2 <= end) {
                            String.format(java.util.Locale.ROOT, "0x%04X", (data[body].toInt() and 0xFF) or ((data[body + 1].toInt() and 0xFF) shl 8))
                        } else {
                            ""
                        }
                        else -> handler
                    }
                    out += Stream(type, handler, codec)
                    type = null
                }
            }
            pos = body + size + (size and 1)
        }
    }

    private fun fourcc(b: ByteArray, at: Int): String =
        if (at + 4 > b.size) "" else String(CharArray(4) { (b[at + it].toInt() and 0xFF).toChar() })

    private fun le32(b: ByteArray, at: Int): Int =
        if (at + 4 > b.size) -1 else (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    internal fun readUpTo(input: InputStream, max: Int): ByteArray {
        val out = ByteArray(max)
        var n = 0
        while (n < max) {
            val r = input.read(out, n, max - n)
            if (r < 0) break
            n += r
        }
        return if (n == max) out else out.copyOf(n)
    }

    /** Most "hdrl" lists are a few KB; some writers pad them (JUNK) for later editing. */
    const val HEADER_LIMIT = 1024 * 1024
}

/** The picture codecs found in AVI files, by their FourCC. */
object AviCodecs {
    /** [mime]: the decoder type to ask Android for (null: none exists); [name]: what the user is told. */
    data class Codec(val mime: String?, val name: String, val noDecoder: Boolean = false)

    // The FourCCs as FFmpeg's AVI reader knows them.
    private val MPEG4_ASP = setOf(
        "XVID", "DIVX", "DX50", "FMP4", "3VID", "MP4V", "MP4S", "XVIX", "DIV1", "BLZ0", "DXGM", "M4S2", "RMP4", "SMP4", "UMP4",
        "WV1F", "SEDG", "3IV2", "FFDS", "FVFW", "HDX4", "DM4V",
    )
    // DivX ;-) 3.11 and its clones: Microsoft's MPEG-4 v3.
    private val MS_MPEG4_V3 = setOf("DIV3", "DIV4", "DIV5", "DIV6", "MP43", "MPG3", "DVX3", "AP41", "COL0", "COL1")
    private val MS_MPEG4_V2 = setOf("MP42", "DIV2")
    private val MS_MPEG4_V1 = setOf("MP41", "MPG4")
    private val H264 = setOf("H264", "AVC1", "X264", "DAVC", "VSSH")

    fun video(fourcc: String): Codec {
        val f = fourcc.trim().uppercase()
        return when (f) {
            in MPEG4_ASP -> Codec("video/mp4v-es", "MPEG-4 (XviD/DivX)")
            in MS_MPEG4_V3 -> Codec(MIME_MS_MPEG4_V3, DIVX3, noDecoder = true)
            in MS_MPEG4_V2 -> Codec(MIME_MS_MPEG4_V2, "MS-MPEG4 v2", noDecoder = true)
            in MS_MPEG4_V1 -> Codec(MIME_MS_MPEG4_V1, "MS-MPEG4 v1", noDecoder = true)
            in H264 -> Codec("video/avc", "H.264")
            "MJPG" -> Codec("video/mjpeg", "MJPEG")
            "WMV3" -> Codec("video/x-ms-wmv", "WMV 9", noDecoder = true)
            "WVC1" -> Codec("video/wvc1", "VC-1")
            else -> Codec(null, fourcc.trim().ifEmpty { "?" })
        }
    }

    /**
     * Microsoft's MPEG-4 variants (DivX 3 among them), which no Android decoder plays: a readable name
     * for a track of that type (Media3 calls them video/mp42 and video/mp43), null for anything else.
     */
    fun msMpeg4Name(mime: String?): String? = when (mime?.lowercase()) {
        MIME_MS_MPEG4_V3, "video/divx3", "video/divx311", "video/x-msmpeg4v3" -> DIVX3
        MIME_MS_MPEG4_V2, "video/x-msmpeg4v2" -> "MS-MPEG4 v2"
        MIME_MS_MPEG4_V1, "video/x-msmpeg4" -> "MS-MPEG4 v1"
        else -> null
    }

    private const val DIVX3 = "DivX 3"
    const val MIME_MS_MPEG4_V3 = "video/mp43"
    const val MIME_MS_MPEG4_V2 = "video/mp42"
    const val MIME_MS_MPEG4_V1 = "video/mp41"
}

/** Which reader the converter takes for a file. */
enum class ReaderKind {
    /** Android's MediaExtractor: everything it opens (MP4, MKV, TS, ... and AVI on phones with a vendor reader). */
    ANDROID,

    /** Media3's AVI reader, for AVI and .divx files Android's MediaExtractor can't open (Pixels and others). */
    MEDIA3_AVI,
}

object ReaderChoice {
    /**
     * [extractorOpened]: Android's MediaExtractor opened the file; then it stays the reader, whatever the
     * file is: what plays today keeps playing the same way, vendor decoders included. Otherwise an AVI
     * ([head]: the file's first bytes) goes to Media3; null: nothing here reads it.
     */
    fun choose(extractorOpened: Boolean, head: ByteArray?): ReaderKind? = when {
        extractorOpened -> ReaderKind.ANDROID
        head != null && AviHeader.isAvi(head) -> ReaderKind.MEDIA3_AVI
        else -> null
    }

    /** The first bytes [choose] looks at. */
    fun head(source: MediaSource): ByteArray? = try {
        source.open(0).use { AviHeader.readUpTo(it, 12) }
    } catch (e: IOException) {
        null
    }
}
