package io.github.patschgoo.castapp.service

import android.content.Context
import io.github.patschgoo.castapp.network.Secrets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where a video or long recording was left off. */
data class WatchEntry(
    val uri: String,
    val title: String,
    val mimeType: String,
    val positionMs: Long,
    val durationMs: Long,
    /** System.currentTimeMillis() of the last update. */
    val updatedAt: Long,
    val finished: Boolean,
    /** Everything needed to play it again ([MediaItem.toJson]). */
    val itemJson: String,
) {
    val item: MediaItem? get() = try { MediaItem.fromJson(JSONObject(itemJson)) } catch (_: Exception) { null }

    /** 0..1, or -1 when the length is unknown. */
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else -1f

    fun toJson(): JSONObject = JSONObject()
        .put("uri", uri)
        .put("title", title)
        .put("mime", mimeType)
        .put("pos", positionMs)
        .put("dur", durationMs)
        .put("at", updatedAt)
        .put("done", finished)
        .put("item", itemJson)

    companion object {
        fun fromJson(o: JSONObject): WatchEntry? {
            val uri = o.optString("uri").ifEmpty { return null }
            return WatchEntry(
                uri = uri,
                title = o.optString("title"),
                mimeType = o.optString("mime"),
                positionMs = o.optLong("pos"),
                durationMs = o.optLong("dur"),
                updatedAt = o.optLong("at"),
                finished = o.optBoolean("done"),
                itemJson = o.optString("item"),
            )
        }
    }
}

/** When playing again should continue where it stopped. */
object ResumeRules {
    /** Less than this watched: start from the beginning. */
    const val MIN_POSITION_MS = 30_000L
    /** Less than this left (or 5 % of the length, whichever is more): it counts as watched. */
    const val END_MARGIN_MS = 30_000L
    /** Music is only resumed for long recordings (audio books, podcasts, concerts). */
    const val MIN_AUDIO_DURATION_MS = 10 * 60_000L

    /** Whether an item of this kind is remembered at all. */
    fun remembers(item: MediaItem, durationMs: Long): Boolean = when {
        item.live || item.type == MediaType.PHOTO -> false
        item.type == MediaType.AUDIO -> durationMs >= MIN_AUDIO_DURATION_MS
        else -> true
    }

    fun isFinished(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= durationMs - maxOf(END_MARGIN_MS, durationMs / 20)

    /** The position to resume at, or null to start from the beginning. */
    fun resumeAt(positionMs: Long, durationMs: Long, finished: Boolean): Long? = when {
        finished -> null
        positionMs < MIN_POSITION_MS -> null
        isFinished(positionMs, durationMs) -> null
        // Start a few seconds earlier, so the viewer finds the thread again.
        else -> (positionMs - 3000).coerceAtLeast(0)
    }
}

/**
 * Remembers how far each video was watched, so casting it again continues there
 * ("Continue watching"). Kept in a small JSON file; written at most every
 * [saveEveryMs] while playing and right away when asked to [flush].
 */
class WatchHistory(
    private val store: Store,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 50,
    private val saveEveryMs: Long = 15_000,
) {
    interface Store {
        fun read(): String?
        fun write(text: String)
    }

    private val lock = Any()
    private val _entries = MutableStateFlow(load())
    /** Newest first. */
    val entries: StateFlow<List<WatchEntry>> = _entries.asStateFlow()
    private var lastSave = 0L
    private var dirty = false

    private fun load(): List<WatchEntry> = try {
        val text = store.read()
        if (text.isNullOrBlank()) emptyList() else {
            val a = JSONArray(text)
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(WatchEntry::fromJson) }.sortedByDescending { it.updatedAt }
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** Unfinished items worth continuing, newest first. */
    fun continueWatching(limit: Int = 12): List<WatchEntry> =
        _entries.value.filter { ResumeRules.resumeAt(it.positionMs, it.durationMs, it.finished) != null }.take(limit)

    /** Where to resume [item], or null to start at the beginning. */
    fun resumePosition(item: MediaItem): Long? {
        val e = _entries.value.firstOrNull { it.uri == item.uri } ?: return null
        return ResumeRules.resumeAt(e.positionMs, e.durationMs, e.finished)
    }

    /** Notes how far [item] is; [force] writes the file now (item changed, casting stopped). */
    fun record(item: MediaItem, positionMs: Long, durationMs: Long, force: Boolean = false) {
        val known = synchronized(lock) { _entries.value.firstOrNull { it.uri == item.uri } }
        val duration = if (durationMs > 0) durationMs else known?.durationMs ?: 0
        if (!ResumeRules.remembers(item, duration)) return
        // The TV reported nothing useful yet: keep what we had.
        if (positionMs <= 0 && known != null && !force) return
        val now = clock()
        val entry = WatchEntry(
            uri = item.uri,
            title = item.title,
            mimeType = item.mimeType,
            positionMs = positionMs.coerceAtLeast(0),
            durationMs = duration,
            updatedAt = now,
            finished = ResumeRules.isFinished(positionMs, duration),
            itemJson = item.toJson().toString(),
        )
        // Barely started and never seen before: not worth an entry.
        if (known == null && positionMs < ResumeRules.MIN_POSITION_MS) return
        synchronized(lock) {
            _entries.value = (listOf(entry) + _entries.value.filter { it.uri != item.uri }).take(maxEntries)
            dirty = true
        }
        if (force || now - lastSave >= saveEveryMs) flush()
    }

    /** It played to the end. */
    fun markFinished(item: MediaItem, durationMs: Long) {
        val d = if (durationMs > 0) durationMs else 1
        record(item, d, d, force = true)
    }

    fun remove(uri: String) {
        synchronized(lock) {
            _entries.value = _entries.value.filter { it.uri != uri }
            dirty = true
        }
        flush()
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
            dirty = true
        }
        flush()
    }

    fun flush() {
        val text = synchronized(lock) {
            if (!dirty) return
            dirty = false
            lastSave = clock()
            JSONArray().also { a -> _entries.value.forEach { a.put(it.toJson()) } }.toString()
        }
        try {
            store.write(text)
        } catch (_: Exception) {
        }
    }

    companion object {
        @Volatile private var instance: WatchHistory? = null

        fun get(context: Context): WatchHistory = instance ?: synchronized(this) {
            instance ?: WatchHistory(FileStore(File(context.applicationContext.filesDir, "watch_history.json"))).also { instance = it }
        }
    }

    /**
     * The history file, encrypted with the app's Keystore key: saved items can carry
     * server tokens (Plex, Jellyfin) and sign-in headers. Older plain files are still
     * read and get encrypted with the next save.
     */
    class FileStore(private val file: File) : Store {
        override fun read(): String? {
            if (!file.exists()) return null
            val text = file.readText()
            return if (text.startsWith(ENCRYPTED)) Secrets.decrypt(text.removePrefix(ENCRYPTED)) else text
        }

        override fun write(text: String) {
            // A phone whose Keystore doesn't work keeps a plain file rather than no history.
            val stored = try { ENCRYPTED + Secrets.encrypt(text) } catch (_: Exception) { text }
            writeFile(stored)
        }

        private fun writeFile(text: String) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }

        private companion object {
            const val ENCRYPTED = "castapp-enc1:"
        }
    }
}
