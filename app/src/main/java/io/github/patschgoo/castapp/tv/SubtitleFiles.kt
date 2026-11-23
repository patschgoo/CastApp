package io.github.patschgoo.castapp.tv

import androidx.media3.common.MimeTypes
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One subtitle file a sender hands a CastApp TV next to the video (`castapp:subfiles:`). */
data class SubtitleFile(
    /** http(s)://… */
    val url: String,
    val language: String? = null,
    /** What the TV's subtitle menu calls it ("Signs & Songs"). */
    val label: String? = null,
    /** The sender's type of the file ("text/x-ssa"); else it is told by the address's extension. */
    val mime: String? = null,
)

/**
 * The subtitle files a CastApp TV loads next to the video, in which format, and which one it shows.
 * No Android code (Media3's type names are plain constants), so it is tested on the JVM.
 *
 * Senders give one file the DLNA way (a second `<res>`, `sec:CaptionInfoEx` or `pv:subtitleFileUri`,
 * see [UpnpRenderer.parseMedia]) or several in `castapp:subfiles:` ([encode]). Shown as files: SRT,
 * WebVTT and ASS/SSA. PGS and VobSub only inside the video file.
 */
object SubtitleFiles {
    /** At most this many files from `castapp:subfiles:` (more are left out). */
    const val MAX = 20

    /** The id of the one subtitle file sent the DLNA way (Media3 puts "n:" in front of it). */
    const val SIDECAR_ID = "castapp-sidecar"

    /** Ids of the files from `castapp:subfiles:`: this and their place ("castapp-subfile-3"). */
    const val FILE_ID = "castapp-subfile-"

    private val SRT = setOf("application/x-subrip", "text/srt", "text/x-srt", "application/srt", "text/x-subrip", "srt", "subrip")
    private val VTT = setOf("text/vtt", "text/webvtt", "vtt", "webvtt")
    private val SSA = setOf("text/x-ssa", "text/x-ass", "application/x-ssa", "application/x-ass", "text/ssa", "text/ass", "ssa", "ass")
    /** Subtitle files the TV can't show (SAMI, picture subtitles as files). */
    private val UNSHOWN = setOf("smi/caption", "application/smil", "text/smi", "smi", "sami", "sup", "idx", "sub", "image/vnd.dvb.subtitle")
    private val UNSHOWN_EXTENSIONS = setOf("smi", "sami", "sup", "idx", "sub")

    /** A protocolInfo type of a `<res>` that is a subtitle file, not the video (or another copy of it). */
    fun isSubtitleMime(mime: String?): Boolean = mime?.let(::bare)?.let { it in SRT || it in VTT || it in SSA || it in UNSHOWN } == true

    /** "text/srt; charset=utf-8" → "text/srt". */
    private fun bare(mime: String): String = mime.substringBefore(';').trim().lowercase()

    /**
     * Media3's type for a subtitle file at [url]: from [declared] (the sender's MIME type, or a
     * `sec:type` like "srt"), else from the address's extension, else SRT (the usual DLNA subtitle
     * file). Null for a file it can't show (SAMI, picture subtitles): the TV leaves it out.
     */
    fun mimeFor(declared: String?, url: String): String? {
        fun of(type: String): String? = when (type) {
            in SSA -> MimeTypes.TEXT_SSA
            in VTT -> MimeTypes.TEXT_VTT
            in SRT -> MimeTypes.APPLICATION_SUBRIP
            else -> null
        }
        val type = declared?.let(::bare)?.takeIf { it.isNotEmpty() }
        type?.let(::of)?.let { return it }
        if (type != null && type in UNSHOWN) return null
        val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        of(ext)?.let { return it }
        if (ext in UNSHOWN_EXTENSIONS) return null
        return MimeTypes.APPLICATION_SUBRIP
    }

    /** The JSON of `castapp:subfiles:`: `[{"url": …, "language": …, "label": …, "mime": …}]`, only the fields given. */
    fun encode(files: List<SubtitleFile>): String = JSONArray().also { a ->
        files.take(MAX).forEach { f ->
            a.put(
                JSONObject().put("url", f.url).also { o ->
                    f.language?.let { o.put("language", it) }
                    f.label?.let { o.put("label", it) }
                    f.mime?.let { o.put("mime", it) }
                },
            )
        }
    }.toString()

    /**
     * Reads `castapp:subfiles:`. Forgiving, as a receiver is: entries without an http(s) address or
     * of the wrong kind are skipped, the same address only once, at most [MAX]; text that isn't a
     * JSON array is no files.
     */
    fun decode(json: String?): List<SubtitleFile> {
        val a = try {
            JSONArray(json?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList())
        } catch (_: JSONException) {
            return emptyList()
        }
        fun JSONObject.text(key: String): String? = (opt(key) as? String)?.trim()?.ifEmpty { null }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.opt(i) as? JSONObject ?: return@mapNotNull null
            val url = o.text("url")?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: return@mapNotNull null
            SubtitleFile(url, o.text("language"), o.text("label"), o.text("mime"))
        }.distinctBy { it.url }.take(MAX)
    }

    /**
     * Which of [files] to show: the first in the hint's languages ([TrackHint.preferred]: its
     * `lang=` first, then `prefer=`, each in the files' order; the file named like its `title=`
     * among those in `lang=`), else the first file. None when the hint says "off".
     */
    fun shown(files: List<SubtitleFile>, hint: TrackHint?): Int? {
        if (files.isEmpty() || hint?.off == true) return null
        val h = hint ?: return 0
        for (language in h.preferred) {
            val same = files.withIndex().filter { (_, f) -> f.language != null && TrackHint.sameLanguage(language, f.language) }
            if (same.isEmpty()) continue
            val named = h.title?.takeIf { language == h.language }?.let { t -> same.firstOrNull { it.value.label.equals(t, ignoreCase = true) } }
            return (named ?: same.first()).index
        }
        return 0
    }

    /** One subtitle file for the TV's player: its id there, address, Media3 type, language and name. */
    data class Sidecar(val id: String, val url: String, val mime: String, val language: String? = null, val label: String? = null)

    /** How a CastApp TV handles the subtitles of one item. */
    data class Plan(
        /** The files to load next to the video, in order. */
        val files: List<Sidecar> = emptyList(),
        /** Which of [files] to show; null: none of them. */
        val shown: Int? = null,
        /** Subtitles switched off (the hint said "off"). */
        val off: Boolean = false,
        /** The text languages to prefer; null: the TV's own (its caption settings). */
        val languages: List<String>? = null,
        /** Without files: the track inside the file to show ([TrackHint.pick]). */
        val inFile: TrackHint? = null,
    )

    /**
     * What the TV does with [media]'s subtitles:
     * - files from `castapp:subfiles:` (and the plain one if it isn't among them, last): all loaded,
     *   the one [shown] picks is shown; `castapp:subtrack:` only chooses among them (or "off").
     * - else one plain subtitle file: loaded and shown, as always; `castapp:subtrack:` doesn't count.
     * - else `castapp:subtrack:` picks the track inside the file (or "off").
     * Files the TV can't show are left out ([mimeFor]).
     */
    fun plan(media: TvMedia): Plan {
        val hint = TrackHint.decode(media.subtitleTrack)
        val listed = media.subtitleFiles.mapNotNull { f -> mimeFor(f.mime, f.url)?.let { f to it } }
        val plain = media.subtitleUri?.let { uri -> mimeFor(media.subtitleMime, uri)?.let { SubtitleFile(uri) to it } }
        if (listed.isNotEmpty()) {
            val all = listed + listOfNotNull(plain?.takeIf { (p, _) -> listed.none { it.first.url == p.url } })
            return Plan(
                files = all.mapIndexed { i, (f, mime) -> Sidecar(FILE_ID + i, f.url, mime, f.language, f.label) },
                shown = shown(listed.map { it.first }, hint),
                off = hint?.off == true,
                languages = hint?.preferred,
            )
        }
        if (plain != null) return Plan(files = listOf(Sidecar(SIDECAR_ID, plain.first.url, plain.second)), shown = 0)
        return Plan(off = hint?.off == true, languages = hint?.preferred, inFile = hint?.takeUnless { it.off })
    }

    /** One text track the TV's player found (Media3's format id, language and name). */
    data class FoundText(val id: String?, val language: String?, val label: String?)

    /** What to do once the player knows the tracks ([pickText]). */
    sealed class TextPick {
        /** The file to show isn't among the tracks yet: look again when they change. */
        object NotYet : TextPick()

        /** Leave it to the player (its default track, or none). */
        object Player : TextPick()

        /** Show the track at this place of the found ones. */
        data class Show(val index: Int) : TextPick()
    }

    private fun isFile(found: FoundText, id: String) = found.id?.endsWith(id) == true

    private fun isOwnFile(found: FoundText) = found.id?.let { it.endsWith(SIDECAR_ID) || it.substringAfterLast(':').startsWith(FILE_ID) } == true

    /** Which of the text tracks the player [found] to show for [plan]: a file the TV loaded wins over the file's own default track. */
    fun pickText(plan: Plan, found: List<FoundText>): TextPick {
        if (plan.files.isNotEmpty()) {
            val file = plan.shown?.let { plan.files.getOrNull(it) } ?: return TextPick.Player
            val at = found.indexOfFirst { isFile(it, file.id) }
            return if (at < 0) TextPick.NotYet else TextPick.Show(at)
        }
        val hint = plan.inFile ?: return TextPick.Player
        val inFile = found.withIndex().filterNot { isOwnFile(it.value) }
        return hint.pick(inFile.map { TrackHint.Found(it.value.language, it.value.label) })?.let { TextPick.Show(inFile[it].index) } ?: TextPick.Player
    }
}
