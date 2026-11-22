package io.github.patschgoo.castapp.settings

import android.content.Context
import androidx.core.content.edit
import io.github.patschgoo.castapp.subtitles.WhisperModels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which speech engine writes subtitles: the fast Vosk one or the accurate Whisper one. */
enum class SpeechEngineKind { VOSK, WHISPER }

/** Settings of the subtitle sources: which speech engine writes subtitles, and online search. */
data class SubtitleSourceOptions(
    val engine: SpeechEngineKind = SpeechEngineKind.VOSK,
    val whisperModel: String = WhisperModels.DEFAULT,
    /** Language Whisper listens for ("" = detect it). */
    val whisperLanguage: String = "",
    /** Whisper writes English subtitles whatever language is spoken. */
    val translateToEnglish: Boolean = false,
    /** Languages to look for online, e.g. "en,de" ("" = the phone's language). */
    val searchLanguages: String = "",
    /**
     * [searchLanguages] are in the order typed, the first preferred for subtitle files next to a
     * video. Older versions saved them sorted by the alphabet ("en, de" became "de, en"); only
     * saving them again (in the subtitle search dialog) sets this.
     */
    val searchLanguagesOrdered: Boolean = false,
)

/** Kept apart from [AppSettings] in its own preferences file. */
class SubtitleSourceSettings private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("subtitle_sources", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SubtitleSourceOptions> = _state.asStateFlow()
    val current: SubtitleSourceOptions get() = _state.value

    private fun load(): SubtitleSourceOptions {
        val d = SubtitleSourceOptions()
        return SubtitleSourceOptions(
            engine = SpeechEngineKind.entries.firstOrNull { it.name == prefs.getString("engine", null) } ?: d.engine,
            whisperModel = prefs.getString("whisper_model", d.whisperModel) ?: d.whisperModel,
            whisperLanguage = prefs.getString("whisper_language", d.whisperLanguage) ?: "",
            translateToEnglish = prefs.getBoolean("translate_to_english", d.translateToEnglish),
            searchLanguages = prefs.getString("search_languages", d.searchLanguages) ?: "",
            searchLanguagesOrdered = prefs.getBoolean("search_languages_ordered", d.searchLanguagesOrdered),
        )
    }

    fun update(transform: (SubtitleSourceOptions) -> SubtitleSourceOptions) {
        val s = transform(_state.value)
        prefs.edit {
            putString("engine", s.engine.name)
            putString("whisper_model", s.whisperModel)
            putString("whisper_language", s.whisperLanguage)
            putBoolean("translate_to_english", s.translateToEnglish)
            putString("search_languages", s.searchLanguages)
            putBoolean("search_languages_ordered", s.searchLanguagesOrdered)
        }
        _state.value = s
    }

    companion object {
        @Volatile private var instance: SubtitleSourceSettings? = null

        fun get(context: Context): SubtitleSourceSettings =
            instance ?: synchronized(this) { instance ?: SubtitleSourceSettings(context).also { instance = it } }
    }
}
