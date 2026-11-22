package io.github.patschgoo.castapp.ui

import androidx.annotation.StringRes
import io.github.patschgoo.castapp.R
import java.text.Normalizer
import java.util.Locale

/**
 * One row of the settings list that the search can find: the section it is in, its title and an
 * optional description (all string resources, so the search works in the current language), and
 * the [anchor] of the row on the settings screen.
 */
class SearchableSetting(
    val anchor: String,
    @StringRes val section: Int,
    @StringRes val title: Int,
    @StringRes val description: Int? = null,
)

/** A [SearchableSetting] with its texts in the current language. */
data class SettingText(val anchor: String, val section: String, val title: String, val description: String = "")

/** Settings search: the searchable rows and the (case-, accent- and umlaut-insensitive) matching. */
object SettingsSearch {
    // Anchors of the rows on the settings screen.
    const val QUALITY = "quality"
    const val FPS = "fps"
    const val BITRATE = "bitrate"
    const val CODEC = "codec"
    const val ADAPTIVE = "adaptive"
    const val PICTURE = "picture"
    const val AUDIO = "audio"
    const val MICROPHONE = "microphone"
    const val SYNC = "sync"
    const val CONVERT = "convert"
    const val SLIDESHOW = "slideshow"
    const val SPOKEN = "spoken_language"
    const val AUTO_SUBS = "auto_subtitles"
    const val SUB_STYLE = "subtitle_style"
    const val LIVE_CAPTION = "live_caption"
    const val SUB_SOURCES = "subtitle_sources"
    const val RECEIVE = "receive"
    const val PROTECTION = "protection"
    const val STREAM_MODE = "stream_mode"
    const val PORT = "port"
    const val RECEIVER = "receiver"
    const val AUTOMATION = "automation"
    const val THEME = "theme"
    const val RECENT = "recent_videos"
    const val SEND_LOG = "send_log"
    const val NETWORK_CHECK = "network_check"
    const val SELF_TEST = "self_test"
    const val BATTERY = "battery"
    const val UPDATES = "updates"
    const val DND = "dnd"
    const val TOUR = "tour"
    const val RESET = "reset"

    val entries: List<SearchableSetting> = listOf(
        SearchableSetting(QUALITY, R.string.set_sec_picture, R.string.set_quality, R.string.polish_search_quality),
        SearchableSetting(FPS, R.string.set_sec_picture, R.string.set_fps),
        SearchableSetting(BITRATE, R.string.set_sec_picture, R.string.set_bitrate),
        SearchableSetting(CODEC, R.string.set_sec_picture, R.string.codec_setting_title, R.string.codec_hevc_desc),
        SearchableSetting(ADAPTIVE, R.string.set_sec_picture, R.string.set_adaptive_title, R.string.set_adaptive_summary),
        SearchableSetting(PICTURE, R.string.set_sec_picture, R.string.set_picture_shape, R.string.picture_landscape_desc),
        SearchableSetting(AUDIO, R.string.set_sec_sound, R.string.set_audio_title, R.string.set_audio_on),
        SearchableSetting(MICROPHONE, R.string.set_sec_sound, R.string.mic_setting_title, R.string.mic_setting_off),
        SearchableSetting(SYNC, R.string.set_sec_sound, R.string.set_sync_title, R.string.set_sync_ok),
        SearchableSetting(CONVERT, R.string.set_sec_media, R.string.set_convert, R.string.convert_auto_desc),
        SearchableSetting(SLIDESHOW, R.string.set_sec_media, R.string.set_slideshow),
        SearchableSetting(SPOKEN, R.string.set_sec_subtitles, R.string.set_spoken_language, R.string.set_lang_text),
        SearchableSetting(AUTO_SUBS, R.string.set_sec_subtitles, R.string.set_auto_subs_title, R.string.set_auto_subs_summary),
        SearchableSetting(SUB_STYLE, R.string.set_sec_subtitles, R.string.style_title, R.string.style_hint),
        SearchableSetting(LIVE_CAPTION, R.string.set_sec_subtitles, R.string.set_live_caption, R.string.set_live_caption_text),
        SearchableSetting(SUB_SOURCES, R.string.set_sec_subtitles, R.string.subsrc_engine),
        SearchableSetting(SUB_SOURCES, R.string.set_sec_subtitles, R.string.subsrc_translate),
        SearchableSetting(SUB_SOURCES, R.string.set_sec_subtitles, R.string.subsrc_online_title),
        SearchableSetting(RECEIVE, R.string.set_sec_connection, R.string.receive_title, R.string.receive_summary_off),
        SearchableSetting(PROTECTION, R.string.set_sec_connection, R.string.protect_title, R.string.protect_mode_paired_hint),
        SearchableSetting(STREAM_MODE, R.string.set_sec_connection, R.string.set_stream_mode, R.string.mode_low_latency_desc),
        SearchableSetting(PORT, R.string.set_sec_connection, R.string.set_stream_port, R.string.set_port_text),
        SearchableSetting(RECEIVER, R.string.set_sec_connection, R.string.set_receiver_title),
        SearchableSetting(AUTOMATION, R.string.automation_section, R.string.automation_title, R.string.automation_summary_off),
        SearchableSetting(AUTOMATION, R.string.automation_section, R.string.automation_secret_title),
        SearchableSetting(THEME, R.string.set_sec_appearance, R.string.set_theme, R.string.polish_search_theme),
        SearchableSetting(RECENT, R.string.set_sec_appearance, R.string.set_recent_title, R.string.set_recent_summary),
        SearchableSetting(SEND_LOG, R.string.support_section, R.string.send_log_title, R.string.send_log_summary),
        SearchableSetting(NETWORK_CHECK, R.string.support_section, R.string.network_check_title, R.string.network_check_summary),
        SearchableSetting(SELF_TEST, R.string.support_section, R.string.selftest_title, R.string.selftest_summary),
        SearchableSetting(BATTERY, R.string.support_section, R.string.battery_title),
        SearchableSetting(UPDATES, R.string.support_section, R.string.check_updates_title, R.string.check_updates_summary),
        SearchableSetting(UPDATES, R.string.support_section, R.string.auto_update_title, R.string.auto_update_summary),
        SearchableSetting(DND, R.string.support_section, R.string.dnd_title, R.string.dnd_summary),
        SearchableSetting(TOUR, R.string.support_section, R.string.tour_show_again, R.string.tour_show_again_summary),
        SearchableSetting(RESET, R.string.set_sec_about, R.string.set_reset, R.string.set_reset_summary),
    )

    /** Lower case, without accents and diacritics ("Größe" → "grosse", "Café" → "cafe"). */
    fun normalize(text: String): String {
        val lower = text.lowercase(Locale.ROOT).replace("ß", "ss")
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replace(DIACRITICS, "")
    }

    /** Without anything but letters and digits, so "Wi-Fi" is found as "wifi". */
    private fun compact(normalized: String) = normalized.replace(NON_ALNUM, "")

    /** The ways a typed word can be meant: "groesse" is also "grosse" (German umlauts typed as ae, oe, ue). */
    private fun variants(word: String): List<String> {
        val folded = word.replace("ae", "a").replace("oe", "o").replace("ue", "u")
        return if (folded == word) listOf(word) else listOf(word, folded)
    }

    /**
     * The settings matching [query]: every word of the query must appear in the title or the
     * description. Rows whose title matches come first; the order of the list is kept otherwise.
     * A blank query finds nothing.
     */
    fun search(settings: List<SettingText>, query: String): List<SettingText> {
        val words = normalize(query).split(WHITESPACE).filter { compact(it).isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        fun matches(text: String): Boolean {
            val n = normalize(text)
            val c = compact(n)
            return words.all { w -> variants(w).any { v -> n.contains(v) || c.contains(compact(v)) } }
        }
        val inTitle = ArrayList<SettingText>()
        val inText = ArrayList<SettingText>()
        for (s in settings) {
            when {
                matches(s.title) -> inTitle += s
                matches(s.title + " " + s.description) -> inText += s
            }
        }
        return (inTitle + inText).distinctBy { it.anchor + "|" + it.title }
    }

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val WHITESPACE = Regex("\\s+")
}
