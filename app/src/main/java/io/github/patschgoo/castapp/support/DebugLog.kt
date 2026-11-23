package io.github.patschgoo.castapp.support

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import io.github.patschgoo.castapp.BuildConfig
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.settings.CastSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One device line of the report: what was found where. */
data class DeviceLine(val name: String, val kind: String, val address: String, val online: Boolean, val saved: Boolean)

/** Everything that goes into a debug report. */
data class ReportInput(
    val appVersion: String,
    val androidVersion: String,
    val device: String,
    val settings: List<Pair<String, String>>,
    val devices: List<DeviceLine>,
    val logLines: List<String>,
    val generated: String = "",
    /** The newest "Test this phone" result, if the self-test was run. */
    val selfTest: List<String> = emptyList(),
)

/**
 * Builds the text file the user can send to the developer. It only holds this
 * app's own log, the version, the non-secret settings and the devices found on
 * the local network. Passwords, tokens and web addresses with credentials are
 * blanked out, and nothing is ever sent automatically.
 */
object DebugLog {
    const val MAX_LOG_LINES = 1500
    const val MAX_ERRORS = 60

    private val secretWords = Regex(
        """(?i)\b(password|passwd|pwd|pass|token|secret|api[_-]?key|apikey|authorization|cookie|pin|signature)\b(\s*[=:]\s*)("[^"]*"|'[^']*'|\S+)""",
    )
    private val bearer = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}""")
    private val urlCredentials = Regex("""(?i)\b([a-z][a-z0-9+.-]*://)([^/\s:@]+):([^/\s@]+)@""")
    private val urlQuery = Regex("""(?i)([?&](?:password|token|key|apikey|api_key|auth|sig|signature|access_token)=)[^&\s]+""")

    /** Blanks out anything in [line] that looks like a secret. */
    fun redact(line: String): String {
        var s = line
        s = urlCredentials.replace(s) { "${it.groupValues[1]}***:***@" }
        s = urlQuery.replace(s) { "${it.groupValues[1]}***" }
        s = bearer.replace(s) { "${it.groupValues[1]} ***" }
        s = secretWords.replace(s) { "${it.groupValues[1]}${it.groupValues[2]}***" }
        return s
    }

    /** True for warning/error lines of `logcat -v threadtime` output. */
    fun isProblem(line: String): Boolean {
        // "11-22 10:11:12.123  1234  1250 E Tag: message"
        val parts = line.trimStart().split(Regex("\\s+"), limit = 6)
        if (parts.size < 5) return false
        return parts[4] == "E" || parts[4] == "W" || parts[4] == "F"
    }

    fun build(input: ReportInput): String = buildString {
        appendLine("CastApp debug report")
        appendLine("====================")
        appendLine("This file was made on the phone and only shared when you choose to send it.")
        appendLine("It holds no passwords, accounts or media. Device addresses are from your local network.")
        appendLine()
        appendLine("App:      ${input.appVersion}")
        appendLine("Android:  ${input.androidVersion}")
        appendLine("Phone:    ${input.device}")
        if (input.generated.isNotEmpty()) appendLine("Created:  ${input.generated}")
        appendLine()
        appendLine("Settings")
        appendLine("--------")
        for ((k, v) in input.settings) appendLine("$k: $v")
        appendLine()
        appendLine("Devices found (${input.devices.size})")
        appendLine("--------------------")
        if (input.devices.isEmpty()) appendLine("none")
        for (d in input.devices) {
            appendLine("${d.name} | ${d.kind} | ${d.address} | ${if (d.online) "answers" else "no answer"}${if (d.saved) " | remembered" else ""}")
        }
        appendLine()
        if (input.selfTest.isNotEmpty()) {
            appendLine("Self-test (Test this phone)")
            appendLine("---------------------------")
            input.selfTest.forEach { appendLine(it) }
            appendLine()
        }
        val clean = input.logLines.map { redact(it) }
        val errors = clean.filter { isProblem(it) }.takeLast(MAX_ERRORS)
        appendLine("Recent errors and warnings (${errors.size})")
        appendLine("-----------------------------")
        if (errors.isEmpty()) appendLine("none")
        errors.forEach { appendLine(it) }
        appendLine()
        appendLine("Log")
        appendLine("---")
        val tail = clean.takeLast(MAX_LOG_LINES)
        if (tail.isEmpty()) appendLine("(empty)")
        tail.forEach { appendLine(it) }
    }

    /** The settings that are safe to share, as readable lines. */
    fun settingsLines(s: CastSettings): List<Pair<String, String>> = listOf(
        "Resolution" to s.resolution.short,
        "Frame rate" to "${s.fps} fps",
        "Bitrate" to if (s.bitrateMbps == 0) "automatic" else "${s.bitrateMbps} Mbit/s",
        "Audio" to s.audio.toString(),
        "Stream mode" to s.streamMode.name,
        "Stream port" to s.port.toString(),
        "Picture" to s.picture.name,
        "Adaptive quality" to s.adaptiveQuality.toString(),
        "Audio delay" to "${s.audioDelayMs} ms",
        "Low-latency TV player" to if (s.receiverAppId.isBlank()) "off" else "on",
        "Convert videos" to s.convert.name,
        "Slideshow" to "${s.slideshowSeconds} s",
        "Automatic subtitles" to s.autoSubtitles.toString(),
    )

    /** Reads this app's own log (apps may read their own process's logcat). Blocking. */
    fun readOwnLogcat(): List<String> = try {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}", "-t", "4000")
            .redirectErrorStream(true)
            .start()
        val lines = ArrayList<String>()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().useLines { seq -> seq.forEach { lines.add(it) } }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        reader.join(8000)
        if (reader.isAlive) process.destroy()
        synchronized(lines) { lines.toList() }
    } catch (e: Exception) {
        listOf("(could not read the log: ${e.message})")
    }

    /** Collects everything, writes the report to the cache and returns the file. */
    suspend fun createReport(context: Context): File = withContext(Dispatchers.IO) {
        val app = CastApp.get(context)
        val input = ReportInput(
            appVersion = "CastApp ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            settings = settingsLines(app.settings.current),
            devices = app.devices.devices.value.map {
                DeviceLine(it.device.name, it.device.kind.name, "${it.device.host}:${it.device.port}", it.online, it.saved)
            },
            logLines = readOwnLogcat(),
            selfTest = SelfTest.last,
            generated = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date()),
        )
        val dir = File(context.cacheDir, "support/logs").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, "castapp-debug-log.txt").apply { writeText(build(input)) }
    }

    /** The share sheet for [file]; the user picks where it goes (mail, messenger, ...). */
    fun shareIntent(context: Context, file: File, subject: String): Intent {
        val uri = Updates.fileUri(context, file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TITLE, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The read grant travels with ClipData; some share targets ignore it on EXTRA_STREAM alone.
            .apply { clipData = android.content.ClipData.newRawUri(file.name, uri) }
        return Intent.createChooser(send, subject).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** The part of a [build] report before the long log: versions, settings, devices, self-test, errors. */
    fun shortVersion(report: String, maxChars: Int = SHORT_MAX_CHARS): String {
        val cut = report.indexOf(LOG_HEADING)
        val head = if (cut >= 0) report.substring(0, cut).trimEnd() else report
        return if (head.length <= maxChars) head else head.take(maxChars) + "\n…"
    }

    /** A file name for saving the report, e.g. castapp-debug-log-20261123-1530.txt. */
    fun fileName(now: java.util.Date = java.util.Date()): String =
        "castapp-debug-log-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(now) + ".txt"

    /** Pasted into a chat, a few dozen kilobytes is the most that goes through. */
    const val SHORT_MAX_CHARS = 40_000
    private const val LOG_HEADING = "\nLog\n---\n"
}
