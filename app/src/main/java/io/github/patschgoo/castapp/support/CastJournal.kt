package io.github.patschgoo.castapp.support

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short diary of the last cast sessions: when each step of connecting took place, which stream
 * the TV got, whether the phone's sound was captured and what Android reported while it ran.
 *
 * Android's own log is shared by every app and on many phones holds only the last few minutes, so a
 * debug report made after casting often had nothing about the session left. This diary is kept in the
 * app's own folder (the newest [MAX_LINES] lines), survives a restart of the app and goes into the
 * debug report. It holds no media, passwords or web addresses; device addresses are on the local network.
 */
object CastJournal {
    const val MAX_LINES = 600
    private const val TAG = "CastApp.Journal"
    private const val FILE = "support/cast-journal.txt"

    private val buffer = JournalBuffer(MAX_LINES)
    @Volatile private var file: File? = null
    @Volatile private var sessionStartMs = 0L
    private val clock = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Loads the diary from the app's folder (once). */
    fun attach(context: Context) {
        if (file != null) return
        synchronized(this) {
            if (file != null) return
            val f = File(context.applicationContext.filesDir, FILE)
            try {
                if (f.exists()) buffer.load(f.readLines())
            } catch (_: Exception) {
            }
            file = f
        }
    }

    @Volatile private var pending: String? = null
    @Volatile private var pendingAtMs = 0L

    /** A line written right after the heading of the session that starts within the next minute. */
    fun forNextSession(message: String) {
        pending = message
        pendingAtMs = System.currentTimeMillis()
    }

    /** A new session starts: a heading line, and later lines show the time since it. */
    fun begin(context: Context, summary: String) {
        attach(context)
        sessionStartMs = System.currentTimeMillis()
        write("", force = true)
        write("=== $summary", force = true)
        if (System.currentTimeMillis() - pendingAtMs < 60_000) pending?.let { write(it) }
        pending = null
    }

    /** One step of the running session, e.g. "Connected to the TV". */
    fun event(message: String) = write(message)

    /** The session ended. */
    fun end(reason: String) {
        write("=== ended: $reason")
        sessionStartMs = 0L
    }

    /** The diary as it is now, oldest first. */
    fun lines(): List<String> = buffer.lines()

    private fun write(message: String, force: Boolean = false) {
        if (message.isEmpty() && force) {
            add("")
            return
        }
        Log.i(TAG, message)
        val now = System.currentTimeMillis()
        val since = if (sessionStartMs > 0) " +" + JournalBuffer.seconds(now - sessionStartMs) else ""
        val stamp = synchronized(clock) { clock.format(Date(now)) }
        add("$stamp$since $message")
    }

    private fun add(line: String) {
        buffer.add(line)
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            if (buffer.trimmedSinceSave()) f.writeText(buffer.lines().joinToString("\n", postfix = "\n")) else f.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }
}

/** The newest [max] lines (plain logic, tested on the JVM). */
class JournalBuffer(private val max: Int) {
    private val lines = ArrayDeque<String>()
    private var trimmed = false

    @Synchronized fun add(line: String) {
        lines.addLast(line)
        // Trim in steps so the file is only rewritten now and then.
        if (lines.size > max + max / 5) {
            while (lines.size > max) lines.removeFirst()
            trimmed = true
        }
    }

    @Synchronized fun load(saved: List<String>) {
        lines.clear()
        saved.takeLast(max).forEach { lines.addLast(it) }
    }

    @Synchronized fun lines(): List<String> = lines.toList()

    /** True once after the oldest lines were dropped (then the whole file is written again). */
    @Synchronized fun trimmedSinceSave(): Boolean = trimmed.also { trimmed = false }

    companion object {
        /** 1234 ms -> "1.2s". */
        fun seconds(ms: Long): String = String.format(Locale.US, "%.1fs", ms / 1000.0)
    }
}
