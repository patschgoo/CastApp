package io.github.patschgoo.castapp.iptv

import java.io.IOException
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** How a segment is encrypted (only AES-128 can be recorded). */
data class HlsKey(val uri: String, val iv: ByteArray?) {
    override fun equals(other: Any?) = other is HlsKey && other.uri == uri && (other.iv contentEquals iv)
    override fun hashCode() = uri.hashCode() * 31 + (iv?.contentHashCode() ?: 0)
}

/** A piece of the file at [uri] (the whole file when [offset] is null). */
data class HlsByteRange(val length: Long, val offset: Long)

data class HlsSegment(
    val uri: String,
    val durationSec: Double,
    val sequence: Long,
    /** fMP4: the initialisation segment (`#EXT-X-MAP`) that goes in front of the fragments. */
    val init: HlsInit? = null,
    val key: HlsKey? = null,
    val range: HlsByteRange? = null,
)

data class HlsInit(val uri: String, val range: HlsByteRange? = null)

data class HlsVariant(val uri: String, val bandwidth: Long, val resolution: String? = null)

sealed interface HlsPlaylist {
    /** A list of quality levels: one of them is the stream to record. */
    class Master(val variants: List<HlsVariant>) : HlsPlaylist {
        /** The best quality that is not above [maxBandwidth] (the lowest one when all are above; the best when it is 0). */
        fun pick(maxBandwidth: Long = 0): HlsVariant? {
            if (variants.isEmpty()) return null
            if (maxBandwidth <= 0) return variants.maxByOrNull { it.bandwidth }
            return variants.filter { it.bandwidth <= maxBandwidth }.maxByOrNull { it.bandwidth } ?: variants.minByOrNull { it.bandwidth }
        }
    }

    class Media(
        val targetDurationSec: Int,
        val mediaSequence: Long,
        val segments: List<HlsSegment>,
        val endList: Boolean,
    ) : HlsPlaylist {
        /** Fragmented MP4 (has an initialisation segment, or .m4s / .mp4 / .cmf* parts); otherwise MPEG-TS. */
        val isFmp4: Boolean
            get() = segments.any { s ->
                s.init != null || s.uri.substringBefore('?').substringAfterLast('.').lowercase() in FMP4_EXTENSIONS
            }

        val isAudioOnly: Boolean
            get() = segments.isNotEmpty() && segments.all { it.uri.substringBefore('?').substringAfterLast('.').lowercase() in AUDIO_EXTENSIONS }

        val extension: String get() = if (isFmp4) "mp4" else if (isAudioOnly) "aac" else "ts"
    }

    companion object {
        private val FMP4_EXTENSIONS = setOf("m4s", "mp4", "m4v", "cmfv", "cmfa", "m4a")
        private val AUDIO_EXTENSIONS = setOf("aac", "adts")

        fun looksLikePlaylist(head: ByteArray, length: Int): Boolean {
            var i = 0
            // UTF-8 byte order mark
            if (length >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
            val tag = "#EXTM3U".toByteArray()
            if (length - i < tag.size) return false
            for (k in tag.indices) if (head[i + k] != tag[k]) return false
            return true
        }

        /**
         * Reads an M3U8 playlist; [baseUrl] is the address it came from (relative addresses are
         * resolved against it). Throws [IOException] for streams that can't be recorded.
         */
        fun parse(text: String, baseUrl: String): HlsPlaylist {
            val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.firstOrNull()?.startsWith("#EXTM3U") != true) throw IOException("Not a playlist")
            return if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) parseMaster(lines, baseUrl) else parseMedia(lines, baseUrl)
        }

        private fun resolve(base: String, ref: String): String = try {
            URL(URL(base), ref).toString()
        } catch (_: Exception) {
            ref
        }

        private fun attributes(line: String): Map<String, String> {
            val out = HashMap<String, String>()
            val body = line.substringAfter(':', "")
            Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""").findAll(body).forEach { m ->
                out[m.groupValues[1]] = m.groupValues[2].removeSurrounding("\"")
            }
            return out
        }

        private fun parseMaster(lines: List<String>, base: String): Master {
            val variants = ArrayList<HlsVariant>()
            var pending: Map<String, String>? = null
            for (line in lines) {
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    pending = attributes(line)
                } else if (!line.startsWith("#") && pending != null) {
                    variants += HlsVariant(resolve(base, line), (pending["AVERAGE-BANDWIDTH"] ?: pending["BANDWIDTH"])?.toLongOrNull() ?: 0L, pending["RESOLUTION"])
                    pending = null
                }
            }
            return Master(variants)
        }

        private fun parseRange(spec: String, previousEnd: Long?): HlsByteRange? {
            val length = spec.substringBefore('@').trim().toLongOrNull() ?: return null
            val offset = spec.substringAfter('@', "").trim().toLongOrNull() ?: previousEnd ?: 0L
            return HlsByteRange(length, offset)
        }

        private fun hexIv(s: String?): ByteArray? {
            val hex = s?.removePrefix("0x")?.removePrefix("0X") ?: return null
            if (hex.isEmpty() || hex.length > 32 || hex.any { !it.isLetterOrDigit() }) return null
            val padded = hex.padStart(32, '0')
            return ByteArray(16) { padded.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        private fun parseMedia(lines: List<String>, base: String): Media {
            var target = 0
            var sequence = 0L
            var endList = false
            var duration = 0.0
            var init: HlsInit? = null
            var key: HlsKey? = null
            var range: HlsByteRange? = null
            var lastRangeEnd: Long? = null
            val segments = ArrayList<HlsSegment>()
            var seq = 0L
            var seqSet = false
            for (line in lines) {
                when {
                    line.startsWith("#EXT-X-TARGETDURATION:") -> target = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                        sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                        if (!seqSet) {
                            seq = sequence
                            seqSet = true
                        }
                    }
                    line.startsWith("#EXT-X-ENDLIST") -> endList = true
                    line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    line.startsWith("#EXT-X-BYTERANGE:") -> range = parseRange(line.substringAfter(':'), lastRangeEnd)
                    line.startsWith("#EXT-X-MAP:") -> {
                        val a = attributes(line)
                        val uri = a["URI"]
                        init = if (uri == null) null else HlsInit(resolve(base, uri), a["BYTERANGE"]?.let { parseRange(it, null) })
                    }
                    line.startsWith("#EXT-X-KEY:") -> {
                        val a = attributes(line)
                        when (a["METHOD"]?.uppercase()) {
                            null, "NONE" -> key = null
                            "AES-128" -> {
                                val uri = a["URI"] ?: throw IOException("Encrypted stream without a key address")
                                key = HlsKey(resolve(base, uri), hexIv(a["IV"]))
                            }
                            else -> throw IOException("This stream is encrypted (${a["METHOD"]}) and can't be recorded")
                        }
                    }
                    !line.startsWith("#") -> {
                        val uri = resolve(base, line)
                        val r = range
                        if (r != null) lastRangeEnd = r.offset + r.length
                        segments += HlsSegment(uri, duration, seq, init, key, r)
                        seq++
                        range = null
                        duration = 0.0
                    }
                }
            }
            return Media(target, sequence, segments, endList)
        }

        /** Decrypts one AES-128 segment (CBC, PKCS#7). The IV is the segment's sequence number when the key line has none. */
        fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray?, sequence: Long): ByteArray {
            val vector = iv ?: ByteArray(16).also { v ->
                for (i in 0 until 8) v[15 - i] = (sequence ushr (8 * i)).toByte()
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(vector))
            return cipher.doFinal(data)
        }
    }
}
