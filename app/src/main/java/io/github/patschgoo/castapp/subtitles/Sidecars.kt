package io.github.patschgoo.castapp.subtitles

import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.network.NaturalOrder
import io.github.patschgoo.castapp.settings.SubtitleSourceSettings
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale
import java.util.MissingResourceException

/**
 * Subtitle files next to a video ("sidecars"): which video each one belongs to, which language it
 * is in, and which one plays first. Folders hold one with the video's name ("Film.srt"), or one per
 * language: "Film.English.srt", "Film .German.srt", "Film_eng.srt", "Film [Englisch].ass",
 * "Film.English2.srt", "Film-en.srt".
 */
object Sidecars {
    private val SEPARATORS = Regex("""[\s._\-\[\]()]+""")
    private val MARKS = Regex("""\p{Mn}+""")
    private val TEXT_FORMATS = setOf("srt", "vtt")
    private val STYLED_FORMATS = setOf("ass", "ssa")

    /** ISO 639-2 bibliographic codes ("ger") where they differ from the terminology ones Java knows ("deu"). */
    private val BIBLIOGRAPHIC = mapOf(
        "sq" to "alb", "hy" to "arm", "eu" to "baq", "bo" to "tib", "my" to "bur", "cs" to "cze", "zh" to "chi",
        "cy" to "wel", "de" to "ger", "nl" to "dut", "el" to "gre", "fa" to "per", "fr" to "fre", "ka" to "geo",
        "is" to "ice", "mk" to "mac", "mi" to "mao", "ms" to "may", "ro" to "rum", "sk" to "slo",
    )

    /** Words subtitle tools use for languages whose names have two words ("Norwegian Bokmål"). */
    private val EXTRA_WORDS = mapOf("bokmal" to "nb", "nynorsk" to "nn")

    fun stem(name: String): String = name.substringBeforeLast('.')

    /**
     * A subtitle belongs to a video when its name (without the extension) is the video's, or the
     * video's followed by something that is not a letter or digit: "Ep 1.en.srt" and "Ep 1 [eng].srt"
     * go with "Ep 1.mkv", "Ep 10.srt" does not. Case doesn't matter.
     */
    fun matches(subtitleStem: String, videoStem: String): Boolean =
        videoStem.isNotEmpty() &&
            subtitleStem.length >= videoStem.length &&
            subtitleStem.regionMatches(0, videoStem, 0, videoStem.length, ignoreCase = true) &&
            (subtitleStem.length == videoStem.length || !subtitleStem[videoStem.length].isLetterOrDigit())

    /**
     * For each of [videos] (file names), the indices of the [subtitles] (file names) that belong to
     * it. A subtitle goes to the video with the longest name it matches, so "Ep. 06-deleted.srt"
     * goes with "Ep. 06-deleted.mkv" and not with "Ep. 06.mkv"; videos of the same name share it.
     */
    fun assign(videos: List<String>, subtitles: List<String>): List<List<Int>> {
        val stems = videos.map(::stem)
        val owned = List(videos.size) { ArrayList<Int>() }
        subtitles.forEachIndexed { s, name ->
            val st = stem(name)
            val longest = stems.filter { matches(st, it) }.maxOfOrNull { it.length } ?: return@forEachIndexed
            stems.forEachIndexed { v, vs -> if (vs.length == longest && matches(st, vs)) owned[v] += s }
        }
        return owned
    }

    /**
     * The words a subtitle's name adds to the video's: ["English2"] for "Film.English2.srt",
     * ["eng", "forced"] for "Film [eng] (forced).srt", none for "Film.srt". For a subtitle with
     * another name ([videoName] null or not matching), the part after its last dot.
     */
    fun tagWords(videoName: String?, subtitleName: String): List<String> {
        val st = stem(subtitleName)
        val vs = videoName?.let(::stem)
        val rest = if (vs != null && matches(st, vs)) st.substring(vs.length) else st.substringAfterLast('.', "")
        return rest.split(SEPARATORS).filter { it.isNotEmpty() }
    }

    /** Lower case without accents, and without a number at the end ("Español" → "espanol", "English10" → "english"). */
    private fun fold(word: String): String =
        MARKS.replace(Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFD), "").trimEnd { it in '0'..'9' }

    /** Java's old codes for Hebrew, Indonesian and Yiddish. */
    private fun canonical(code: String): String = when (val c = code.lowercase(Locale.ROOT)) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        else -> c
    }

    private fun iso3(code: String): String? = try {
        Locale.forLanguageTag(code).isO3Language.lowercase(Locale.ROOT).ifEmpty { null }
    } catch (_: MissingResourceException) {
        null
    }

    private fun displayNames(code: String): List<String> {
        val l = Locale.forLanguageTag(code)
        return listOf(Locale.ENGLISH, Locale.GERMAN, l, Locale.getDefault()).map { l.getDisplayLanguage(it) }
    }

    /** How the language [code] can be written in a file name: "en", "eng", "English", "Englisch"… (folded). */
    fun aliases(code: String): Set<String> {
        val c = canonical(code)
        val words = EXTRA_WORDS.filterValues { it == c }.keys
        return (listOf(c) + listOfNotNull(iso3(c), BIBLIOGRAPHIC[c]) + displayNames(c) + words).map(::fold).filter { it.isNotEmpty() }.toSet()
    }

    /** Every language's aliases → its code, for naming files in the picker. Codes win over names ("ga" is Irish). */
    private val allAliases: Map<String, String> by lazy {
        val codes = (Locale.getISOLanguages().asList() + Locale.getAvailableLocales().map { it.language })
            .map(::canonical).filter { it.length in 2..3 }.distinct()
        val map = HashMap<String, String>()
        for (c in codes) map.putIfAbsent(c, c)
        for (c in codes) iso3(c)?.let { map.putIfAbsent(it, c) }
        for ((c, b) in BIBLIOGRAPHIC) map.putIfAbsent(b, c)
        for (c in codes) for (name in displayNames(c)) fold(name).takeIf { it.isNotEmpty() && ' ' !in it }?.let { map.putIfAbsent(it, c) }
        for ((w, c) in EXTRA_WORDS) map.putIfAbsent(w, c)
        map
    }

    /** The language one word of a file name names ("English2", "Englisch", "eng", "Español"), as a code ("en", "es"). */
    fun language(word: String): String? = allAliases[fold(word)]

    /**
     * The languages subtitle files are preferred in: those of the subtitle search setting in their
     * order ("de,en"; empty = the phone's language), then the phone's language, then English.
     *
     * Not [ordered]: the setting was saved before it kept the order typed, sorted by the alphabet
     * ("en, de" became "de, en"). Then the phone's language goes first if it is one of them, then
     * the others, English last.
     */
    fun preferredLanguages(searchLanguages: String, phone: Locale, ordered: Boolean = true): List<String> {
        val chosen = searchLanguages.split(',', ' ', ';')
            .map { it.trim().lowercase(Locale.ROOT).replace('_', '-').substringBefore('-') }
            .filter { it.isNotEmpty() }
            .map { w -> canonical(if (w.length == 3) twoLetter(w) else w) }
            .distinct()
        val own = canonical(phone.language)
        val inOrder = if (ordered || chosen.size < 2) {
            chosen
        } else {
            chosen.sortedBy { if (it == own) 0 else if (it == "en") 2 else 1 }
        }
        return (inOrder.ifEmpty { listOf(own) } + own + "en").filter { it.isNotEmpty() }.distinct()
    }

    /** The same from the app's settings. */
    fun preferredLanguages(): List<String> {
        val s = try {
            SubtitleSourceSettings.get(CastApp.instance).current
        } catch (_: UninitializedPropertyAccessException) {
            null // unit tests run without an Application
        }
        return preferredLanguages(s?.searchLanguages.orEmpty(), Locale.getDefault(), s?.searchLanguagesOrdered ?: true)
    }

    private fun twoLetter(code: String): String =
        BIBLIOGRAPHIC.entries.firstOrNull { it.value == code }?.key
            ?: Locale.getISOLanguages().firstOrNull { iso3(it) == code }
            ?: code

    private fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** An ASS/SSA file ("Film.English.ass"): styled subtitles, often only signs or karaoke. */
    fun isStyled(name: String): Boolean = extension(name) in STYLED_FORMATS

    /**
     * The [subtitles] of the video [videoName], best first: the [preferred] languages in their
     * order, then one with just the video's name, then the rest. Within each, SRT and WebVTT come
     * before ASS/SSA (TVs show their text alike, and the SRT is usually the cleaner one), then
     * names in natural order ("English2" before "English10").
     */
    fun <T> ranked(videoName: String?, subtitles: List<T>, preferred: List<String>, name: (T) -> String): List<T> {
        if (subtitles.size < 2) return subtitles
        val wanted = preferred.map(::aliases)
        fun languageRank(n: String): Int {
            val words = tagWords(videoName, n)
            if (words.isEmpty()) return wanted.size
            val folded = words.map(::fold)
            val i = wanted.indexOfFirst { a -> folded.any { it in a } }
            return if (i >= 0) i else wanted.size + 1
        }
        return subtitles
            .map { Triple(it, name(it), languageRank(name(it))) }
            .sortedWith(
                compareBy<Triple<T, String, Int>> { it.third }
                    .thenBy { if (extension(it.second) in TEXT_FORMATS) 0 else 1 }
                    .thenBy(NaturalOrder) { it.second }
                    .thenBy { it.second },
            )
            .map { it.first }
    }

    /**
     * What the subtitle picker calls a subtitle file: its language in [display]'s language
     * ("English", "Englisch"), with any other words of its name ("English (forced)"); else those
     * words ("Bokmal"); null when the name says nothing more than the video's.
     */
    fun label(videoName: String?, subtitleName: String, display: Locale): String? {
        val words = tagWords(videoName, subtitleName)
        val at = words.indexOfFirst { language(it) != null }
        if (at < 0) return words.joinToString(" ").ifEmpty { null }
        val code = language(words[at])!!
        val languageName = Locale.forLanguageTag(code).getDisplayLanguage(display)
            .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
            ?.replaceFirstChar { it.titlecase(display) }
            ?: words[at]
        val others = words.filterIndexed { i, w -> i != at && w.any { !it.isDigit() } }
        return if (others.isEmpty()) languageName else "$languageName (${others.joinToString(" ")})"
    }

    /** The file name in a subtitle address (smb://, content://, http(s)://), decoded. */
    fun fileName(uri: String): String {
        if (uri.startsWith("smb://")) return uri.substringAfterLast('/')
        val last = uri.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val decoded = try {
            URLDecoder.decode(last.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            last
        }
        val name = decoded.substringAfterLast('/')
        // Storage documents: "primary:Movies/Film.srt"
        return if (uri.startsWith("content:")) name.substringAfterLast(':') else name
    }
}
