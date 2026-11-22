package io.github.patschgoo.castapp.subtitles

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Speech models for the accurate engine: the multilingual whisper.cpp models (quantised
 * to 5 bits, which keeps nearly all of the accuracy at a third of the size), downloaded
 * from the official whisper.cpp repository on Hugging Face and checked against SHA-256.
 */
object WhisperModels {
    data class Model(val id: String, val label: String, val file: String, val bytes: Long, val sha256: String) {
        val sizeMb: Int get() = (bytes / 1_000_000).toInt()
        val url: String get() = "$BASE/$file"
    }

    /** Pinned to a revision of https://huggingface.co/ggerganov/whisper.cpp, so the checksums stay right. */
    private const val BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1"

    val ALL = listOf(
        Model("tiny", "Tiny", "ggml-tiny-q5_1.bin", 32_152_673, "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"),
        Model("base", "Base", "ggml-base-q5_1.bin", 59_707_625, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"),
        Model("small", "Small", "ggml-small-q5_1.bin", 190_085_487, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
    )

    const val DEFAULT = "base"

    fun byId(id: String): Model? = ALL.firstOrNull { it.id == id }

    private val _downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Download progress per model id (0..1); absent when not downloading. */
    val downloads: StateFlow<Map<String, Float>> = _downloads.asStateFlow()

    private val _installed = MutableStateFlow<Set<String>>(emptySet())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun root(context: Context) = File(context.filesDir, "whisper")

    fun file(context: Context, model: Model): File = File(root(context), model.file)

    fun isInstalled(context: Context, model: Model): Boolean = file(context, model).length() == model.bytes

    fun refresh(context: Context) {
        _installed.value = ALL.filter { isInstalled(context, it) }.map { it.id }.toSet()
    }

    /** Downloads [model] in the background (keeps going when the screen closes). */
    fun startDownload(context: Context, model: Model) {
        val app = context.applicationContext
        if (model.id in _downloads.value) return
        _error.value = null
        background.launch {
            try {
                download(app, model)
            } catch (e: Exception) {
                _error.value = "Couldn't download the ${model.label} model: ${e.message ?: "no connection"}"
            }
        }
    }

    suspend fun download(context: Context, model: Model) = withContext(Dispatchers.IO) {
        if (isInstalled(context, model)) return@withContext
        _downloads.value = _downloads.value + (model.id to 0f)
        val part = File(context.cacheDir, model.file + ".part")
        try {
            val conn = URL(model.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) throw IOException("Download failed (${conn.responseCode})")
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        _downloads.value = _downloads.value + (model.id to (done.toFloat() / model.bytes).coerceAtMost(0.99f))
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
            if (hex != model.sha256) throw IOException("The download was damaged, please try again")
            val target = file(context, model)
            target.parentFile?.mkdirs()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
            }
        } finally {
            part.delete()
            _downloads.value = _downloads.value - model.id
            refresh(context)
        }
    }

    fun delete(context: Context, model: Model) {
        file(context, model).delete()
        refresh(context)
    }
}
