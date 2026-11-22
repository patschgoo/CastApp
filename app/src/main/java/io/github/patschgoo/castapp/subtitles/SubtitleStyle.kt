package io.github.patschgoo.castapp.subtitles

import org.json.JSONObject

enum class SubtitleSize(val scale: Float) {
    SMALL(0.75f), NORMAL(1.0f), LARGE(1.35f), HUGE(1.75f)
}

enum class SubtitleColor(val rgb: Int) {
    WHITE(0xFFFFFF), YELLOW(0xFFEB3B), CYAN(0x4DD0E1), GREEN(0x8BC34A)
}

enum class SubtitleBackground { NONE, SHADOW, BOX }

/**
 * How subtitles look on the TV. Google Cast applies it through the text-track
 * style of the LOAD request, CastApp TV receivers read it from the DIDL-Lite
 * metadata; most DLNA TVs and AirPlay can't be told.
 */
data class SubtitleStyle(
    val size: SubtitleSize = SubtitleSize.NORMAL,
    val color: SubtitleColor = SubtitleColor.WHITE,
    val background: SubtitleBackground = SubtitleBackground.BOX,
) {
    /** ARGB colours for Android (CastApp TV's player). */
    val foregroundArgb: Int get() = (0xFF shl 24) or color.rgb
    val backgroundArgb: Int get() = if (background == SubtitleBackground.BOX) BOX_ARGB else 0

    /** Google Cast TextTrackStyle. */
    fun toCastJson(): JSONObject = JSONObject()
        .put("fontScale", size.scale.toDouble())
        .put("foregroundColor", castColor(foregroundArgb))
        .put("backgroundColor", castColor(backgroundArgb))
        .put("edgeType", if (background == SubtitleBackground.SHADOW) "DROP_SHADOW" else "NONE")
        .put("edgeColor", "#000000FF")
        .put("windowType", "NONE")

    /** Compact form for the DIDL-Lite metadata: "size=1.35;fg=FFEB3B;bg=box". */
    fun encode(): String = "size=" + size.scale + ";fg=" + "%06X".format(java.util.Locale.ROOT, color.rgb) + ";bg=" + background.name.lowercase()

    companion object {
        private const val BOX_ARGB = 0xB3000000.toInt()

        /** Cast wants #RRGGBBAA. */
        fun castColor(argb: Int): String {
            val a = (argb ushr 24) and 0xFF
            return "#%06X%02X".format(java.util.Locale.ROOT, argb and 0xFFFFFF, a)
        }

        fun decode(text: String?): SubtitleStyle? {
            if (text.isNullOrBlank()) return null
            val parts = text.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
            if (parts.isEmpty()) return null
            val scale = parts["size"]?.toFloatOrNull()
            val size = scale?.let { s -> SubtitleSize.entries.minByOrNull { kotlin.math.abs(it.scale - s) } } ?: SubtitleSize.NORMAL
            val rgb = parts["fg"]?.toIntOrNull(16)
            val color = SubtitleColor.entries.firstOrNull { it.rgb == rgb } ?: SubtitleColor.WHITE
            val bg = SubtitleBackground.entries.firstOrNull { it.name.equals(parts["bg"], ignoreCase = true) } ?: SubtitleBackground.BOX
            return SubtitleStyle(size, color, bg)
        }
    }
}
