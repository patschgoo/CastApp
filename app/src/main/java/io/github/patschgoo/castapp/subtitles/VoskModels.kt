package io.github.patschgoo.castapp.subtitles

import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.R
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Offline speech-recognition languages (Vosk "small" models, 30–50 MB each). */
object VoskModels {
    data class Language(val code: String, val label: String, val model: String, val sizeMb: Int) {
        val url: String get() = "https://alphacephei.com/vosk/models/$model.zip"
    }

    val ALL = listOf(
        Language("en", "English", "vosk-model-small-en-us-0.15", 40),
        Language("de", "Deutsch", "vosk-model-small-de-0.15", 45),
        Language("fr", "Français", "vosk-model-small-fr-0.22", 41),
        Language("es", "Español", "vosk-model-small-es-0.42", 38),
        Language("it", "Italiano", "vosk-model-small-it-0.22", 48),
        Language("nl", "Nederlands", "vosk-model-small-nl-0.22", 39),
        Language("pt", "Português", "vosk-model-small-pt-0.3", 31),
        Language("pl", "Polski", "vosk-model-small-pl-0.22", 51),
        Language("ru", "Русский", "vosk-model-small-ru-0.22", 44),
        Language("tr", "Türkçe", "vosk-model-small-tr-0.3", 35),
        Language("ja", "日本語", "vosk-model-small-ja-0.22", 48),
        Language("zh", "中文", "vosk-model-small-cn-0.22", 42),
        Language("hi", "हिन्दी", "vosk-model-small-hi-0.22", 42),
    )

    fun byCode(code: String): Language? = ALL.firstOrNull { it.code == code }

    /** Download progress per language code (0..1); absent when not downloading. */
    private val _downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloads: StateFlow<Map<String, Float>> = _downloads.asStateFlow()

    private val _installed = MutableStateFlow<Set<String>>(emptySet())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private fun root(context: Context) = File(context.filesDir, "vosk")

    fun modelDir(context: Context, lang: Language): File = File(root(context), lang.model)

    fun refresh(context: Context) {
        _installed.value = ALL.filter { File(modelDir(context, it), ".ready").exists() }.map { it.code }.toSet()
    }

    fun isInstalled(context: Context, lang: Language): Boolean = File(modelDir(context, lang), ".ready").exists()

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Last download problem, for the settings screen (null when fine). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Downloads [lang] in the background (keeps going when the screen closes), then calls [onReady]. */
    fun startDownload(context: Context, lang: Language, onReady: () -> Unit = {}) {
        val app = context.applicationContext
        if (lang.code in _downloads.value) return
        _error.value = null
        background.launch {
            try {
                download(app, lang)
                onReady()
            } catch (e: Exception) {
                _error.value = Txt.s(R.string.err_lang_download, lang.label, e.message ?: Txt.s(R.string.err_no_connection))
            }
        }
    }

    suspend fun download(context: Context, lang: Language) = withContext(Dispatchers.IO) {
        if (isInstalled(context, lang)) return@withContext
        _downloads.value = _downloads.value + (lang.code to 0f)
        val zip = File(context.cacheDir, "${lang.model}.zip")
        try {
            val conn = URL(lang.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            if (conn.responseCode != 200) throw IOException(Txt.s(R.string.err_download_failed_code, conn.responseCode))
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (lang.sizeMb * 1_048_576L)
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        _downloads.value = _downloads.value + (lang.code to (done.toFloat() / total).coerceAtMost(0.99f))
                    }
                }
            }
            unzip(zip, root(context))
            File(modelDir(context, lang), ".ready").writeText("ok")
        } finally {
            zip.delete()
            _downloads.value = _downloads.value - lang.code
            refresh(context)
        }
    }

    fun delete(context: Context, lang: Language) {
        modelDir(context, lang).deleteRecursively()
        refresh(context)
    }

    private fun unzip(zip: File, target: File) {
        target.mkdirs()
        val base = target.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val out = File(target, e.name)
                if (!out.canonicalPath.startsWith(base)) throw IOException(Txt.s(R.string.err_bad_language_file))
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
    }
}
