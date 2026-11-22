package io.github.patschgoo.castapp.iptv

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import io.github.patschgoo.castapp.service.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

enum class RecordingState { SCHEDULED, RECORDING, DONE, PARTIAL, FAILED }

/** Where recordings are kept. */
enum class RecordingTarget {
    /** The app's own Movies folder on the phone (no permission needed; removed with the app). */
    PHONE,

    /** The phone's shared Movies folder (Android 10+; stays when the app is removed). */
    GALLERY,

    /** A saved network folder (Windows / Samba share or WebDAV). */
    NETWORK,
}

/** One recording, planned, running or finished. */
data class Recording(
    val id: String,
    /** The programme's title, or the channel's name for "record now". */
    val title: String,
    val channelName: String,
    val channelUrl: String,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** When recording begins (with the padding before the programme). */
    val plannedStartMs: Long,
    /** When it ends (with the padding after), 0 to go on until stopped. */
    val plannedEndMs: Long = 0,
    /** The programme's own start, for the file name; 0 when it is not a programme. */
    val programmeStartMs: Long = 0,
    val state: RecordingState = RecordingState.SCHEDULED,
    val target: RecordingTarget = RecordingTarget.PHONE,
    val sourceId: String = "",
    val fileName: String = "",
    /** The file: a path (PHONE), a content address (GALLERY), `smb://<source>/<path>` or a web address (NETWORK). */
    val location: String = "",
    val bytes: Long = 0,
    val recordedMs: Long = 0,
    val gaps: Int = 0,
    val error: String? = null,
    val warning: String? = null,
) {
    val isActive: Boolean get() = state == RecordingState.SCHEDULED || state == RecordingState.RECORDING
    val hasFile: Boolean get() = location.isNotEmpty() && (state == RecordingState.DONE || state == RecordingState.PARTIAL || state == RecordingState.RECORDING)

    fun toChannel(): Channel = Channel(name = channelName, url = channelUrl, userAgent = userAgent, referrer = referrer)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("cn", channelName).put("cu", channelUrl)
        .put("ua", userAgent ?: "").put("ref", referrer ?: "")
        .put("from", plannedStartMs).put("to", plannedEndMs).put("ps", programmeStartMs)
        .put("state", state.name).put("target", target.name).put("source", sourceId)
        .put("file", fileName).put("loc", location).put("bytes", bytes).put("ms", recordedMs).put("gaps", gaps)
        .put("err", error ?: "").put("warn", warning ?: "")

    companion object {
        fun fromJson(o: JSONObject) = Recording(
            id = o.getString("id"),
            title = o.optString("title"),
            channelName = o.optString("cn"),
            channelUrl = o.optString("cu"),
            userAgent = o.optString("ua").ifEmpty { null },
            referrer = o.optString("ref").ifEmpty { null },
            plannedStartMs = o.getLong("from"),
            plannedEndMs = o.optLong("to"),
            programmeStartMs = o.optLong("ps"),
            state = runCatching { RecordingState.valueOf(o.optString("state")) }.getOrDefault(RecordingState.FAILED),
            target = runCatching { RecordingTarget.valueOf(o.optString("target")) }.getOrDefault(RecordingTarget.PHONE),
            sourceId = o.optString("source"),
            fileName = o.optString("file"),
            location = o.optString("loc"),
            bytes = o.optLong("bytes"),
            recordedMs = o.optLong("ms"),
            gaps = o.optInt("gaps"),
            error = o.optString("err").ifEmpty { null },
            warning = o.optString("warn").ifEmpty { null },
        )
    }
}

/** How recordings are named. */
object RecordingNames {
    private const val MAX_BASE = 110
    private val FORBIDDEN = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    /** `Channel - Title - 2026-11-22 20-15.ts`: safe on phones, Windows and web servers. */
    fun fileName(channel: String, title: String, startMs: Long, extension: String, zone: TimeZone = TimeZone.getDefault()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).apply { timeZone = zone }.format(Date(startMs))
        val parts = listOf(clean(channel), clean(title).takeIf { it != clean(channel) }, stamp).filterNotNull().filter { it.isNotEmpty() }
        var base = parts.joinToString(" - ")
        if (base.length > MAX_BASE) {
            // Shorten the long parts, keep the time stamp.
            val keep = stamp.length + 3
            base = base.substring(0, (MAX_BASE - keep).coerceAtLeast(10)).trimEnd(' ', '-', '.') + " - " + stamp
        }
        return base + "." + extension.trimStart('.').ifEmpty { "ts" }
    }

    fun clean(s: String): String = FORBIDDEN.replace(s, " ").replace(Regex("""\s+"""), " ").trim().trim('.')

    /** [name], or `name (2).ext` when it is [taken]. */
    fun unique(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 2
        while ("$base ($i)$ext" in taken) i++
        return "$base ($i)$ext"
    }

    fun mimeFor(extension: String): String = when (extension.lowercase()) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "aac" -> "audio/aac"
        "mp3" -> "audio/mpeg"
        else -> "video/mp2t"
    }
}

/** Two recordings, or a recording and what is playing, on the same provider account. */
object ConnectionGuard {
    private val KINDS = setOf("live", "movie", "series", "timeshift")

    /** Host, port and (when the address has one) user name of a stream address; null if it is not a web address. */
    fun accountKey(url: String): String? {
        val uri = try {
            java.net.URI(url.trim())
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        val query = uri.rawQuery.orEmpty().split('&').associate { it.substringBefore('=').lowercase() to it.substringAfter('=', "") }
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val user = query["username"]?.takeIf { it.isNotEmpty() }
            ?: if (segments.size >= 3 && segments.last().substringBeforeLast('.').all(Char::isDigit)) {
                if (segments[0].lowercase() in KINDS) segments.getOrNull(1) else segments[0]
            } else null
        return "$host:$port|${user.orEmpty()}"
    }

    fun sameAccount(a: String, b: String): Boolean {
        val ka = accountKey(a) ?: return false
        return ka == accountKey(b)
    }
}

/** The list of recordings, kept in [store]. */
class RecordingBook(private val store: TextStore) {
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Recording>> = _items.asStateFlow()

    fun get(id: String): Recording? = _items.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(r: Recording) {
        _items.value = _items.value.filter { it.id != r.id } + r
        save()
    }

    @Synchronized
    fun update(id: String, change: (Recording) -> Recording) {
        val list = _items.value
        if (list.none { it.id == id }) return
        _items.value = list.map { if (it.id == id) change(it) else it }
        save()
    }

    @Synchronized
    fun remove(id: String) {
        _items.value = _items.value.filter { it.id != id }
        save()
    }

    fun takenNames(): Set<String> = _items.value.map { it.fileName }.filter { it.isNotEmpty() }.toSet()

    fun hasScheduled(channelUrl: String, programmeStartMs: Long): Recording? =
        _items.value.firstOrNull { it.state == RecordingState.SCHEDULED && it.channelUrl == channelUrl && it.programmeStartMs == programmeStartMs }

    /** When the next scheduled recording should be woken: its start minus [earlyMs]. */
    fun nextTrigger(earlyMs: Long): Long? =
        _items.value.filter { it.state == RecordingState.SCHEDULED }.minOfOrNull { it.plannedStartMs - earlyMs }

    /** Scheduled recordings that should start by [now] (given [earlyMs]), oldest first. */
    fun due(now: Long, earlyMs: Long): List<Recording> =
        _items.value.filter { it.state == RecordingState.SCHEDULED && it.plannedStartMs - earlyMs <= now }.sortedBy { it.plannedStartMs }

    /** After the app was killed: recordings that were running are partial (or failed when empty). */
    @Synchronized
    fun markInterrupted() {
        var changed = false
        _items.value = _items.value.map {
            if (it.state != RecordingState.RECORDING) it
            else {
                changed = true
                it.copy(state = if (it.bytes > 0) RecordingState.PARTIAL else RecordingState.FAILED, error = it.error ?: INTERRUPTED)
            }
        }
        if (changed) save()
    }

    private fun load(): List<Recording> = try {
        val arr = JSONArray(store.read() ?: "[]")
        (0 until arr.length()).mapNotNull { i -> runCatching { Recording.fromJson(arr.getJSONObject(i)) }.getOrNull() }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save() {
        store.write(JSONArray().also { a -> _items.value.forEach { a.put(it.toJson()) } }.toString())
    }

    companion object {
        const val INTERRUPTED = "interrupted"
    }
}

/** Where recordings go and how much extra is recorded around a programme. */
class RecordingSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("iptv_extras", Context.MODE_PRIVATE)

    data class Values(val target: RecordingTarget, val sourceId: String, val padBeforeMin: Int, val padAfterMin: Int)

    private val _values = MutableStateFlow(read())
    val values: StateFlow<Values> = _values.asStateFlow()

    private fun read() = Values(
        target = runCatching { RecordingTarget.valueOf(prefs.getString("rec_target", null) ?: "") }.getOrDefault(RecordingTarget.PHONE),
        sourceId = prefs.getString("rec_source", "") ?: "",
        padBeforeMin = prefs.getInt("rec_pad_before", DEFAULT_PAD_BEFORE_MIN),
        padAfterMin = prefs.getInt("rec_pad_after", DEFAULT_PAD_AFTER_MIN),
    )

    fun setTarget(target: RecordingTarget, sourceId: String = "") {
        prefs.edit {
            putString("rec_target", target.name)
            putString("rec_source", sourceId)
        }
        _values.value = read()
    }

    fun setPadding(beforeMin: Int, afterMin: Int) {
        prefs.edit {
            putInt("rec_pad_before", beforeMin.coerceIn(0, 60))
            putInt("rec_pad_after", afterMin.coerceIn(0, 120))
        }
        _values.value = read()
    }

    companion object {
        const val DEFAULT_PAD_BEFORE_MIN = 2
        const val DEFAULT_PAD_AFTER_MIN = 5
    }
}

/** Plans recordings and wakes the recording service; the one place the UI and the alarm go through. */
class RecordingCenter(private val context: Context) {
    val book = RecordingBook(PrefsTextStore(context, "iptv_extras", "recordings")).also { it.markInterrupted() }
    val settings = RecordingSettings(context)
    private val alarm = AndroidAlarmPort(context, IptvAlarmReceiver.ACTION_RECORDING, 42)
    private val pendingDelete = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    val items: StateFlow<List<Recording>> get() = book.items

    private fun newRecording(channel: Channel, title: String, from: Long, to: Long, programmeStart: Long): Recording {
        val s = settings.values.value
        return Recording(
            id = UUID.randomUUID().toString().take(8),
            title = title,
            channelName = channel.name,
            channelUrl = channel.url,
            userAgent = channel.userAgent,
            referrer = channel.referrer,
            plannedStartMs = from,
            plannedEndMs = to,
            programmeStartMs = programmeStart,
            target = s.target,
            sourceId = s.sourceId,
        )
    }

    /** Plans [programme] of [channel], recording [padBeforeMs] earlier and [padAfterMs] later. */
    fun schedule(channel: Channel, programme: Programme, padBeforeMs: Long, padAfterMs: Long): Recording {
        book.hasScheduled(channel.url, programme.start)?.let { return it }
        val now = System.currentTimeMillis()
        val r = newRecording(channel, programme.title, maxOf(programme.start - padBeforeMs, now), programme.stop + padAfterMs, programme.start)
        book.add(r)
        if (r.plannedStartMs - EARLY_MS <= now) start(r.id) else reschedule(now)
        return r
    }

    /** Records [channel] now, for [durationMs] (0 = until stopped). */
    fun recordNow(channel: Channel, durationMs: Long, title: String = channel.name): Recording {
        val now = System.currentTimeMillis()
        val r = newRecording(channel, title, now, if (durationMs > 0) now + durationMs else 0L, 0L)
        book.add(r)
        start(r.id)
        return r
    }

    /** The recording of a programme was planned: take it off the list again. */
    fun cancelScheduled(id: String) {
        val r = book.get(id) ?: return
        if (r.state == RecordingState.SCHEDULED) {
            book.remove(id)
            reschedule(System.currentTimeMillis())
        } else if (r.state == RecordingState.RECORDING) {
            RecordingService.stop(context, id)
        }
    }

    fun stop(id: String) = RecordingService.stop(context, id)

    /** Removes the recording and its file. */
    fun delete(id: String) {
        val r = book.get(id) ?: return
        if (r.state == RecordingState.RECORDING) {
            pendingDelete += id
            RecordingService.stop(context, id)
            return
        }
        book.remove(id)
        reschedule(System.currentTimeMillis())
        deleteFile(r)
    }

    fun isDeleteRequested(id: String): Boolean = id in pendingDelete

    /** Called by the service when [id] has ended: true when it was to be deleted and now is. */
    fun deleteIfRequested(id: String): Boolean {
        if (!pendingDelete.remove(id)) return false
        val r = book.get(id) ?: return true
        book.remove(id)
        deleteFile(r)
        return true
    }

    private fun deleteFile(r: Recording) {
        if (r.location.isEmpty()) return
        Thread { runCatching { RecordingSinks.delete(context, r) } }.start()
    }

    fun start(id: String) {
        try {
            RecordingService.start(context, id)
        } catch (e: Exception) {
            // Android 12+ does not let an app in the background start this kind of service: ask with a notification instead.
            RecordingNotifier.showStartPrompt(context, book.get(id) ?: return)
        }
    }

    /** The wake-up: start what is due, give up on what was missed, set the next wake-up. */
    fun onAlarm() {
        val now = System.currentTimeMillis()
        for (r in book.due(now, EARLY_MS)) {
            if (r.plannedEndMs in 1..now) {
                book.update(r.id) { it.copy(state = RecordingState.FAILED, error = MISSED) }
            } else start(r.id)
        }
        reschedule(now)
    }

    fun rescheduleAll() = reschedule(System.currentTimeMillis())

    fun reschedule(now: Long) {
        val next = book.nextTrigger(EARLY_MS)
        alarm.schedule(next?.let { maxOf(it, now + ReminderScheduler.MIN_DELAY_MS) })
    }

    /** Opens the recording on a TV. */
    fun mediaItem(r: Recording): MediaItem? = RecordingSinks.mediaItem(context, r)

    companion object {
        /** Recordings are woken this long before they start, so a late alarm still catches the beginning. */
        const val EARLY_MS = 90_000L
        const val MISSED = "missed"
    }
}

/** Access to the recordings of the app. */
object Recordings {
    @SuppressLint("StaticFieldLeak") // the center only keeps the application context
    @Volatile private var instance: RecordingCenter? = null

    fun get(context: Context): RecordingCenter =
        instance ?: synchronized(this) { instance ?: RecordingCenter(context.applicationContext).also { instance = it } }
}
