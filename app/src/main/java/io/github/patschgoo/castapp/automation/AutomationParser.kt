package io.github.patschgoo.castapp.automation

import io.github.patschgoo.castapp.service.MediaItem
import java.security.MessageDigest

/** What the caller sent: the extras of an Intent, as plain values (also easy to fake in tests). */
interface ExtrasSource {
    operator fun get(key: String): Any?

    /** The Intent's data URI as text (`am start -d ...`), or null. */
    val data: String?

    /**
     * content:// addresses this Intent granted CastApp read access to: its ClipData and its data,
     * when it carries FLAG_GRANT_READ_URI_PERMISSION.
     */
    val grantedUris: Set<String> get() = emptySet()
}

class MapExtras(
    private val values: Map<String, Any?> = emptyMap(),
    override val data: String? = null,
    override val grantedUris: Set<String> = emptySet(),
) : ExtrasSource {
    override fun get(key: String): Any? = values[key]
}

/** A request that is wrong in itself; becomes "invalid_request" with this message. */
internal class BadRequest(message: String) : Exception(message)

/** The two settings that gate the API. */
data class AutomationConfig(val enabled: Boolean, val secret: String)

/** Which TV: the id, the name, or (either) a value that may be either. All null: the TV used last. */
data class DeviceRef(val id: String? = null, val name: String? = null, val either: String? = null) {
    val isLast: Boolean get() = id == null && name == null && either == null
    override fun toString(): String = id ?: name ?: either ?: "last used"
}

enum class MirrorMode(val wire: String) { SCREEN(AutomationApi.MODE_SCREEN), APP(AutomationApi.MODE_APP), AUDIO(AutomationApi.MODE_AUDIO) }

/** One validated request. */
sealed interface AutomationCommand {
    data class Mirror(val device: DeviceRef, val mode: MirrorMode = MirrorMode.SCREEN) : AutomationCommand
    data class CastUrl(val device: DeviceRef, val url: String, val title: String, val mime: String) : AutomationCommand
    data class CastFile(val device: DeviceRef, val uri: String, val title: String?, val mime: String?) : AutomationCommand
    data object Stop : AutomationCommand
    data object PlayPause : AutomationCommand
    data object Play : AutomationCommand
    data object Pause : AutomationCommand
    data object Next : AutomationCommand
    data object Previous : AutomationCommand
    data class SetVolume(val percent: Int) : AutomationCommand

    /** [amountPercent] null: one step of the TV remote. */
    data class ChangeVolume(val up: Boolean, val amountPercent: Int? = null) : AutomationCommand
    data object Mute : AutomationCommand
    data class Quality(val label: String) : AutomationCommand

    /** [enabled] null: switch. */
    data class Receive(val enabled: Boolean?) : AutomationCommand
    data class OpenRemote(val device: DeviceRef) : AutomationCommand

    /** A play list; [queue]'s report_to is checked already. */
    data class CastQueue(val device: DeviceRef, val queue: CastQueueRequest) : AutomationCommand
}

sealed interface ParsedRequest {
    data class Accepted(val command: AutomationCommand) : ParsedRequest
    data class Rejected(val result: AutomationResult) : ParsedRequest
}

/** Turns an Intent's action and extras into an [AutomationCommand], or says why not. No Android code. */
object AutomationParser {
    const val MAX_URL = 8192

    /** [caller]: who sent it, as far as Android tells (only CAST_QUEUE's report_to needs it). */
    fun parse(action: String?, extras: ExtrasSource, config: AutomationConfig, caller: CallerEvidence = CallerEvidence.NONE): ParsedRequest {
        // The switch and the secret come before anything else, so a closed door says nothing about the API.
        if (!config.enabled) return rejected(AutomationResult.DISABLED)
        if (!secretMatches(config.secret, extras[AutomationApi.EXTRA_SECRET])) return rejected(AutomationResult.UNAUTHORIZED)
        if (action == null || action !in AutomationApi.ALL_ACTIONS) {
            return rejected(AutomationResult.invalid("Unknown action: ${action ?: "(none)"}"))
        }
        return try {
            checked(build(action, extras), caller, secretVerified = config.secret.isNotEmpty())
        } catch (e: BadRequest) {
            rejected(AutomationResult.invalid(e.message ?: "Invalid request"))
        }
    }

    /**
     * CAST_QUEUE sent to [PlayOnTvActivity]: the user picks the TV, so neither the switch nor the
     * secret is needed (like sharing a file to "Cast to TV"). The secret only counts for report_to.
     */
    fun parseForConfirmation(action: String?, extras: ExtrasSource, config: AutomationConfig, caller: CallerEvidence): ParsedRequest {
        if (action != AutomationApi.ACTION_CAST_QUEUE) {
            return rejected(AutomationResult.invalid("Only CAST_QUEUE can be confirmed on the phone, not ${action ?: "(none)"}"))
        }
        val secretVerified = config.secret.isNotEmpty() && secretMatches(config.secret, extras[AutomationApi.EXTRA_SECRET])
        return try {
            checked(AutomationCommand.CastQueue(deviceRef(extras), queueRequest(extras)), caller, secretVerified)
        } catch (e: BadRequest) {
            rejected(AutomationResult.invalid(e.message ?: "Invalid request"))
        }
    }

    /** report_to: only to the caller itself (see [ReportRules]). */
    private fun checked(command: AutomationCommand, caller: CallerEvidence, secretVerified: Boolean): ParsedRequest {
        val reportTo = (command as? AutomationCommand.CastQueue)?.queue?.reportTo ?: return ParsedRequest.Accepted(command)
        return ReportRules.check(reportTo, caller, secretVerified)?.let(::rejected) ?: ParsedRequest.Accepted(command)
    }

    /** No secret set in Settings: anything goes. Otherwise exact match, compared in constant time. */
    fun secretMatches(expected: String, given: Any?): Boolean {
        if (expected.isEmpty()) return true
        val g = (given as? CharSequence)?.toString() ?: given?.toString() ?: return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), g.toByteArray(Charsets.UTF_8))
    }

    private fun rejected(result: AutomationResult) = ParsedRequest.Rejected(result)

    private fun build(action: String, x: ExtrasSource): AutomationCommand = when (action) {
        AutomationApi.ACTION_MIRROR -> AutomationCommand.Mirror(deviceRef(x), mirrorMode(x))
        AutomationApi.ACTION_CAST_URL -> {
            val url = text(x, AutomationApi.EXTRA_URL) ?: x.data?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw BadRequest("Missing extra \"${AutomationApi.EXTRA_URL}\"")
            checkUrl(url)
            val mime = text(x, AutomationApi.EXTRA_MIME)?.also(::checkMime) ?: MediaItem.mimeForUrl(url)
            AutomationCommand.CastUrl(deviceRef(x), url, text(x, AutomationApi.EXTRA_TITLE) ?: defaultTitle(url), mime)
        }
        AutomationApi.ACTION_CAST_FILE -> {
            val uri = text(x, AutomationApi.EXTRA_URI) ?: x.data?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw BadRequest("Missing extra \"${AutomationApi.EXTRA_URI}\" (a content:// URI)")
            checkContentUri(uri)
            AutomationCommand.CastFile(deviceRef(x), uri, text(x, AutomationApi.EXTRA_TITLE), text(x, AutomationApi.EXTRA_MIME)?.also(::checkMime))
        }
        AutomationApi.ACTION_STOP -> AutomationCommand.Stop
        AutomationApi.ACTION_PLAY_PAUSE -> AutomationCommand.PlayPause
        AutomationApi.ACTION_PLAY -> AutomationCommand.Play
        AutomationApi.ACTION_PAUSE -> AutomationCommand.Pause
        AutomationApi.ACTION_NEXT -> AutomationCommand.Next
        AutomationApi.ACTION_PREVIOUS -> AutomationCommand.Previous
        AutomationApi.ACTION_VOLUME_SET -> {
            val level = number(x, AutomationApi.EXTRA_LEVEL) ?: throw BadRequest("Missing extra \"${AutomationApi.EXTRA_LEVEL}\" (0 to 100)")
            if (level !in 0..100) throw BadRequest("\"${AutomationApi.EXTRA_LEVEL}\" must be 0 to 100, got $level")
            AutomationCommand.SetVolume(level)
        }
        AutomationApi.ACTION_VOLUME_UP, AutomationApi.ACTION_VOLUME_DOWN -> {
            val amount = number(x, AutomationApi.EXTRA_AMOUNT)
            if (amount != null && amount !in 1..100) throw BadRequest("\"${AutomationApi.EXTRA_AMOUNT}\" must be 1 to 100, got $amount")
            AutomationCommand.ChangeVolume(up = action == AutomationApi.ACTION_VOLUME_UP, amountPercent = amount)
        }
        AutomationApi.ACTION_MUTE -> AutomationCommand.Mute
        AutomationApi.ACTION_QUALITY -> AutomationCommand.Quality(
            text(x, AutomationApi.EXTRA_QUALITY) ?: throw BadRequest("Missing extra \"${AutomationApi.EXTRA_QUALITY}\" (for example 720p)"),
        )
        AutomationApi.ACTION_RECEIVE -> AutomationCommand.Receive(flag(x, AutomationApi.EXTRA_ENABLED))
        AutomationApi.ACTION_OPEN_REMOTE -> AutomationCommand.OpenRemote(deviceRef(x))
        AutomationApi.ACTION_CAST_QUEUE -> AutomationCommand.CastQueue(deviceRef(x), queueRequest(x))
        else -> throw BadRequest("Unknown action: $action")
    }

    /** CAST_QUEUE's extras; start_index and start_position_ms as extras win over those in a JSON object. */
    private fun queueRequest(x: ExtrasSource): CastQueueRequest {
        val json = when (val raw = x[AutomationApi.EXTRA_QUEUE]) {
            null -> throw BadRequest("Missing extra \"${AutomationApi.EXTRA_QUEUE}\" (a JSON array of items)")
            is CharSequence -> raw.toString()
            else -> throw BadRequest("\"${AutomationApi.EXTRA_QUEUE}\" must be a string holding JSON")
        }
        val parsed = QueueParser.parse(json, x.grantedUris)
        val startIndex = number(x, AutomationApi.EXTRA_START_INDEX) ?: parsed.startIndex ?: 0
        if (startIndex !in parsed.items.indices) {
            throw BadRequest("\"${AutomationApi.EXTRA_START_INDEX}\" must be 0 to ${parsed.items.size - 1}, got $startIndex")
        }
        val startPosition = longNumber(x, AutomationApi.EXTRA_START_POSITION)?.also(QueueParser::checkStartPosition) ?: parsed.startPositionMs
        return CastQueueRequest(
            items = parsed.items,
            startIndex = startIndex,
            startPositionMs = startPosition,
            title = limited(x, AutomationApi.EXTRA_TITLE, QueueParser.MAX_TITLE) ?: parsed.title,
            reportTo = text(x, AutomationApi.EXTRA_REPORT_TO),
            requestId = limited(x, AutomationApi.EXTRA_REQUEST_ID, QueueParser.MAX_ID),
        )
    }

    private fun limited(x: ExtrasSource, key: String, max: Int): String? =
        text(x, key)?.also { if (it.length > max) throw BadRequest("\"$key\" is too long (at most $max characters)") }

    // ---- extras ----------------------------------------------------------------------------------

    private fun text(x: ExtrasSource, key: String): String? {
        val v = x[key] ?: return null
        return v.toString().trim().takeIf { it.isNotEmpty() }
    }

    /** An integer sent as a number or as text ("50", "50%", "50.0"). */
    private fun number(x: ExtrasSource, key: String): Int? {
        val v = x[key] ?: return null
        val d: Double = when (v) {
            is Number -> v.toDouble()
            else -> v.toString().trim().removeSuffix("%").trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                ?: throw BadRequest("\"$key\" must be a number, got \"$v\"")
        }
        if (d.isNaN() || d.isInfinite()) throw BadRequest("\"$key\" must be a number, got \"$v\"")
        return Math.round(d).toInt()
    }

    /** A whole number of milliseconds, sent as a number or as text. */
    private fun longNumber(x: ExtrasSource, key: String): Long? {
        val v = x[key] ?: return null
        val d: Double = when (v) {
            is Number -> v.toDouble()
            else -> v.toString().trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: throw BadRequest("\"$key\" must be a number, got \"$v\"")
        }
        if (d.isNaN() || d.isInfinite()) throw BadRequest("\"$key\" must be a number, got \"$v\"")
        return Math.round(d)
    }

    private fun flag(x: ExtrasSource, key: String): Boolean? {
        val v = x[key] ?: return null
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            else -> when (v.toString().trim().lowercase()) {
                "true", "1", "on", "yes" -> true
                "false", "0", "off", "no" -> false
                "" -> null
                else -> throw BadRequest("\"$key\" must be true or false, got \"$v\"")
            }
        }
    }

    private fun deviceRef(x: ExtrasSource) = DeviceRef(
        id = text(x, AutomationApi.EXTRA_DEVICE_ID),
        name = text(x, AutomationApi.EXTRA_DEVICE_NAME),
        either = text(x, AutomationApi.EXTRA_DEVICE),
    )

    private fun mirrorMode(x: ExtrasSource): MirrorMode {
        val raw = text(x, AutomationApi.EXTRA_MODE) ?: return MirrorMode.SCREEN
        return MirrorMode.entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) }
            ?: throw BadRequest("\"${AutomationApi.EXTRA_MODE}\" must be screen, app or audio, got \"$raw\"")
    }

    // ---- checks ----------------------------------------------------------------------------------

    private val urlPattern = Regex("^https?://[^\\s/?#]+[^\\s]*$", RegexOption.IGNORE_CASE)
    private val mimePattern = Regex("^[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+$")

    private fun checkUrl(url: String) {
        if (url.length > MAX_URL) throw BadRequest("The URL is too long")
        if (!urlPattern.matches(url)) throw BadRequest("\"${AutomationApi.EXTRA_URL}\" must be an http:// or https:// address")
    }

    private fun checkContentUri(uri: String) {
        val lower = uri.lowercase()
        when {
            lower.startsWith("content://") && lower.length > "content://".length -> Unit
            lower.startsWith("file:") -> throw BadRequest("file:// is not supported: Android does not let CastApp read other apps' files. Use a content:// URI that you grant read access to")
            else -> throw BadRequest("\"${AutomationApi.EXTRA_URI}\" must be a content:// URI")
        }
        if (uri.length > MAX_URL) throw BadRequest("The URI is too long")
    }

    private fun checkMime(mime: String) {
        if (!mimePattern.matches(mime)) throw BadRequest("\"${AutomationApi.EXTRA_MIME}\" is not a MIME type: \"$mime\"")
    }

    /** The file name part of an address, or the address. */
    fun defaultTitle(url: String): String =
        url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/').ifEmpty { url }
}
