package io.github.patschgoo.castapp.stream

import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.R
import java.net.URI

/** One quality level of an HLS stream (from its master playlist). */
data class HlsVariant(val url: String, val bandwidth: Long, val width: Int, val height: Int) {
    val label: String
        get() = when {
            height > 0 -> "${height}p"
            bandwidth > 0 -> "%.1f Mbit/s".format(java.util.Locale.ROOT, bandwidth / 1_000_000.0)
            else -> Txt.s(R.string.hls_stream)
        }
}

/**
 * Reads the quality levels of an HLS master playlist, and builds a copy that
 * contains only one of them (so the TV plays exactly the quality you picked).
 */
object HlsVariants {
    private val BANDWIDTH = Regex("""(?:^|[:,])BANDWIDTH=(\d+)""")
    private val RESOLUTION = Regex("""RESOLUTION=(\d+)x(\d+)""")

    fun isMaster(playlist: String): Boolean = playlist.contains("#EXT-X-STREAM-INF")

    fun parse(playlist: String, base: URI): List<HlsVariant> {
        val out = ArrayList<HlsVariant>()
        val lines = playlist.lines().map { it.trim() }
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val bw = BANDWIDTH.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val res = RESOLUTION.find(line)
            out += HlsVariant(
                HlsRewriter.resolve(base, uri), bw,
                res?.groupValues?.get(1)?.toIntOrNull() ?: 0, res?.groupValues?.get(2)?.toIntOrNull() ?: 0,
            )
        }
        // Highest quality first, one entry per resolution.
        return out.sortedByDescending { it.height * 1_000_000_000L + it.bandwidth }.distinctBy { if (it.height > 0) it.height.toLong() else it.bandwidth }
    }

    /** A master playlist with only the variant at [keepUrl] (audio/subtitle groups are kept). */
    fun keepOnly(playlist: String, base: URI, keepUrl: String): String {
        val lines = playlist.lines()
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.trim().startsWith("#EXT-X-STREAM-INF")) {
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].trim().startsWith("#"))) j++
                val uri = lines.getOrNull(j)?.trim()
                if (uri != null && HlsRewriter.resolve(base, uri) == keepUrl) {
                    for (k in i..j) out.append(lines[k]).append('\n')
                }
                i = j + 1
                continue
            }
            if (!line.trim().startsWith("#EXT-X-I-FRAME-STREAM-INF")) out.append(line).append('\n')
            i++
        }
        return out.toString()
    }
}
