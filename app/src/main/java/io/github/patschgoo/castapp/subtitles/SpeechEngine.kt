package io.github.patschgoo.castapp.subtitles

import android.content.Context
import io.github.patschgoo.castapp.settings.SpeechEngineKind
import io.github.patschgoo.castapp.settings.SubtitleSourceSettings
import org.vosk.Model
import org.vosk.Recognizer
import java.io.IOException

/**
 * Turns speech into timed subtitles. [SubtitleGenerator] decodes the file and feeds the
 * sound as 16 kHz mono 16-bit samples; the engine does its work inside [accept] and
 * [finish] (on the caller's thread), so both may take a while.
 */
interface SpeechEngine : AutoCloseable {
    /** Feeds the next [count] samples (16 kHz mono). */
    fun accept(pcm: ShortArray, count: Int)

    /** The sound is over: handles what is left. */
    fun finish()

    /** All subtitles written so far, in order. */
    fun cues(): List<Cue>

    /** Seconds of sound already turned into text (it can lag behind what was fed). */
    val processedSeconds: Double

    /** Stops work in progress as soon as possible (called from another thread). */
    fun cancel() {}

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}

/** The fast offline recogniser (Vosk): streams, word timings, one language per model. */
class VoskEngine(context: Context, lang: VoskModels.Language) : SpeechEngine {
    private val model: Model
    private val recognizer: Recognizer
    private val words = ArrayList<Word>()
    private var fed = 0L

    init {
        if (!VoskModels.isInstalled(context, lang)) throw IOException("The ${lang.label} language isn't downloaded")
        model = Model(VoskModels.modelDir(context, lang).absolutePath)
        recognizer = try {
            Recognizer(model, SpeechEngine.SAMPLE_RATE.toFloat()).apply { setWords(true) }
        } catch (e: Exception) {
            model.close()
            throw e
        }
    }

    override fun accept(pcm: ShortArray, count: Int) {
        if (recognizer.acceptWaveForm(pcm, count)) words += CueBuilder.wordsFromVosk(recognizer.result)
        fed += count
    }

    override fun finish() {
        words += CueBuilder.wordsFromVosk(recognizer.finalResult)
    }

    override fun cues(): List<Cue> = CueBuilder.build(words)

    override val processedSeconds: Double get() = fed.toDouble() / SpeechEngine.SAMPLE_RATE

    override fun close() {
        recognizer.close()
        model.close()
    }
}

/** What writes the subtitles, as chosen in the settings. */
sealed interface SpeechSetup {
    /** Names the cached subtitles of a file (Vosk keeps the plain language code it always used). */
    val cacheKey: String

    fun open(context: Context): SpeechEngine

    data class Vosk(val lang: VoskModels.Language) : SpeechSetup {
        override val cacheKey: String get() = lang.code
        override fun open(context: Context): SpeechEngine = VoskEngine(context, lang)
    }

    /** [language] "" = detect it; [translate] = English subtitles whatever is spoken. */
    data class Whisper(val model: WhisperModels.Model, val language: String, val translate: Boolean) : SpeechSetup {
        override val cacheKey: String
            get() = "whisper-${model.id}-${language.ifEmpty { "auto" }}" + if (translate) "-to-en" else ""

        override fun open(context: Context): SpeechEngine =
            WhisperEngine(WhisperModels.file(context, model), language, translate)
    }
}

object SpeechEngines {
    /** The setup the settings ask for (null if nothing usable is chosen), even when its download is missing. */
    fun chosen(context: Context, spokenLanguage: String): SpeechSetup? {
        val options = SubtitleSourceSettings.get(context).current
        return when (options.engine) {
            SpeechEngineKind.VOSK -> VoskModels.byCode(spokenLanguage)?.let { SpeechSetup.Vosk(it) }
            SpeechEngineKind.WHISPER -> WhisperModels.byId(options.whisperModel)?.let {
                SpeechSetup.Whisper(it, options.whisperLanguage, options.translateToEnglish)
            }
        }
    }

    /** The chosen setup if it is ready to run, else what the user has to do first. */
    fun ready(context: Context, spokenLanguage: String): Pair<SpeechSetup?, String?> {
        return when (val setup = chosen(context, spokenLanguage)) {
            is SpeechSetup.Vosk ->
                if (VoskModels.isInstalled(context, setup.lang)) setup to null
                else null to "Choose and download the spoken language in Settings → Subtitles first"
            is SpeechSetup.Whisper ->
                if (WhisperModels.isInstalled(context, setup.model)) setup to null
                else null to "Download the Whisper model in Settings → Subtitles first"
            null ->
                if (SubtitleSourceSettings.get(context).current.engine == SpeechEngineKind.WHISPER) {
                    null to "Choose a Whisper model in Settings → Subtitles first"
                } else {
                    null to "Choose and download the spoken language in Settings → Subtitles first"
                }
        }
    }
}
