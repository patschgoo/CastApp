package io.github.patschgoo.castapp.automation

import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.subtitles.Sidecars
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** One subtitle file of a queue item: an address, and what the subtitle picker calls it. */
data class QueueSubtitle(val url: String, val language: String? = null, val label: String? = null)

/** One item of a CAST_QUEUE request, checked. */
data class QueueItem(
    /** http(s):// or content:// (granted through the Intent's ClipData). */
    val url: String,
    /** The caller's own id, opaque to CastApp; it comes back in the playback broadcasts. */
    val id: String? = null,
    val title: String? = null,
    val mime: String? = null,
    val subtitles: List<QueueSubtitle> = emptyList(),
    /** Poster or cover (http(s)://), for the TV and the phone where CastApp shows one. */
    val art: String? = null,
) {
    val isContent: Boolean get() = isContentUri(url)
}

/** A checked CAST_QUEUE request. */
data class CastQueueRequest(
    val items: List<QueueItem>,
    val startIndex: Int = 0,
    /** Where to start the item at [startIndex]; null: where this phone stopped it last time; 0: from the beginning. */
    val startPositionMs: Long? = null,
    /** What the "Cast to TV" sheet shows; null: from the items. */
    val title: String? = null,
    /** The package that gets the playback broadcasts; checked by [ReportRules] before a request is accepted. */
    val reportTo: String? = null,
    val requestId: String? = null,
)

internal fun isContentUri(uri: String) = uri.startsWith("content://", ignoreCase = true)

/**
 * Reads the `queue` extra of CAST_QUEUE: a JSON array of items, or an object with "items" (and
 * optionally "start_index", "start_position_ms", "title"). Strict: an address of another kind, a
 * field of the wrong type, too many items or too long a text refuse the whole request, so the
 * caller learns about it at once. Unknown fields are ignored (newer callers, older CastApp).
 * No Android code.
 */
object QueueParser {
    const val MAX_ITEMS = 500
    /** Characters of the JSON text. Android's Binder limit (1 MB per transaction) comes first anyway. */
    const val MAX_JSON = 512 * 1024
    const val MAX_TITLE = 300
    const val MAX_ID = 200
    const val MAX_SUBTITLES = 20
    const val MAX_LABEL = 100
    /** Ten days: more than any recording, less than nonsense. */
    const val MAX_START_POSITION_MS = 10L * 24 * 60 * 60 * 1000

    /** What the JSON holds; the start values only in the object form. */
    data class Parsed(val items: List<QueueItem>, val startIndex: Int? = null, val startPositionMs: Long? = null, val title: String? = null)

    private val httpPattern = Regex("^https?://[^\\s/?#]+[^\\s]*$", RegexOption.IGNORE_CASE)
    private val mimePattern = Regex("^[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+$")
    /** BCP 47-ish: "en", "pt-BR", "zh-Hant", "eng". */
    private val languagePattern = Regex("^[A-Za-z]{2,3}([-_][A-Za-z0-9]{1,8}){0,3}$")

    /**
     * [granted]: the content:// addresses CastApp got read access to with this Intent (its
     * ClipData and data, with FLAG_GRANT_READ_URI_PERMISSION). Every content:// address in the
     * queue must be one of them: CastApp only reads what the caller itself may read and handed over.
     */
    fun parse(json: String, granted: Set<String>): Parsed {
        if (json.length > MAX_JSON) throw BadRequest("\"${AutomationApi.EXTRA_QUEUE}\" is too long (at most $MAX_JSON characters)")
        val root = try {
            val tokener = JSONTokener(json.trim().removePrefix("\uFEFF"))
            tokener.nextValue().also { if (tokener.nextClean() != 0.toChar()) throw BadRequest("\"${AutomationApi.EXTRA_QUEUE}\" has text after the JSON") }
        } catch (e: JSONException) {
            throw BadRequest("\"${AutomationApi.EXTRA_QUEUE}\" is not valid JSON: ${e.message}")
        }
        return when (root) {
            is JSONArray -> Parsed(items(root, granted))
            is JSONObject -> {
                val list = root.opt("items") as? JSONArray ?: throw BadRequest("\"${AutomationApi.EXTRA_QUEUE}\" needs \"items\", an array")
                Parsed(
                    items = items(list, granted),
                    startIndex = whole(root, "start_index", "\"start_index\"")?.let { checkInt(it, "\"start_index\"") },
                    startPositionMs = whole(root, "start_position_ms", "\"start_position_ms\"")?.also { checkStartPosition(it) },
                    title = text(root, "title", "\"title\"")?.also { checkLength(it, MAX_TITLE, "\"title\"") },
                )
            }
            else -> throw BadRequest("\"${AutomationApi.EXTRA_QUEUE}\" must be a JSON array of items")
        }
    }

    private fun items(list: JSONArray, granted: Set<String>): List<QueueItem> {
        if (list.length() == 0) throw BadRequest("The queue is empty")
        if (list.length() > MAX_ITEMS) throw BadRequest("The queue has ${list.length()} items; at most $MAX_ITEMS are allowed")
        val items = (0 until list.length()).map { i -> item(list.opt(i), i, granted) }
        val seen = HashMap<String, Int>()
        items.forEachIndexed { i, item ->
            seen.put(item.url, i)?.let { first -> throw BadRequest("Item $i has the same url as item $first: each address only once") }
        }
        return items
    }

    private fun item(value: Any?, i: Int, granted: Set<String>): QueueItem {
        val where = "Item $i"
        // A bare address is an item with just that.
        if (value is String) return QueueItem(url = address(value, "$where: \"url\"", granted))
        val o = value as? JSONObject ?: throw BadRequest("$where must be an object with a \"url\"")
        val url = text(o, "url", "$where: \"url\"") ?: throw BadRequest("$where has no \"url\"")
        return QueueItem(
            url = address(url, "$where: \"url\"", granted),
            id = id(o, "$where: \"id\""),
            title = text(o, "title", "$where: \"title\"", numbersToo = true)?.also { checkLength(it, MAX_TITLE, "$where: \"title\"") },
            mime = text(o, "mime", "$where: \"mime\"")?.also { if (!mimePattern.matches(it)) throw BadRequest("$where: \"mime\" is not a MIME type") },
            subtitles = subtitles(o.value("subtitles"), where, granted),
            art = text(o, "art", "$where: \"art\"")?.also { checkHttp(it, "$where: \"art\"") },
        )
    }

    private fun subtitles(value: Any?, where: String, granted: Set<String>): List<QueueSubtitle> {
        val list = when (value) {
            null -> return emptyList()
            is String -> JSONArray().put(value)
            is JSONArray -> value
            else -> throw BadRequest("$where: \"subtitles\" must be an array")
        }
        if (list.length() > MAX_SUBTITLES) throw BadRequest("$where has more than $MAX_SUBTITLES subtitles")
        return (0 until list.length()).map { s ->
            val at = "$where, subtitle $s"
            when (val sub = list.opt(s)) {
                is String -> QueueSubtitle(address(sub, "$at: \"url\"", granted))
                is JSONObject -> QueueSubtitle(
                    url = address(text(sub, "url", "$at: \"url\"") ?: throw BadRequest("$at has no \"url\""), "$at: \"url\"", granted),
                    language = text(sub, "language", "$at: \"language\"")?.also {
                        if (!languagePattern.matches(it)) throw BadRequest("$at: \"language\" must be a language code such as \"en\" or \"pt-BR\"")
                    },
                    label = text(sub, "label", "$at: \"label\"")?.also { checkLength(it, MAX_LABEL, "$at: \"label\"") },
                )
                else -> throw BadRequest("$at must be an object with a \"url\"")
            }
        }.distinctBy { it.url }
    }

    // ---- addresses ----------------------------------------------------------------------------

    private fun address(raw: String, what: String, granted: Set<String>): String {
        val url = raw.trim()
        if (url.length > AutomationParser.MAX_URL) throw BadRequest("$what is too long")
        val lower = url.lowercase()
        when {
            lower.startsWith("http://") || lower.startsWith("https://") -> checkHttp(url, what)
            isContentUri(url) -> {
                if (url.length <= "content://".length || url.any { it.isWhitespace() }) throw BadRequest("$what is not a content:// URI")
                if (url !in granted) {
                    throw BadRequest(
                        "$what: CastApp may not read this content:// URI. Put it into the Intent's ClipData and add FLAG_GRANT_READ_URI_PERMISSION",
                    )
                }
            }
            lower.startsWith("file:") -> throw BadRequest("$what: file:// is not supported (Android does not let CastApp read other apps' files); use a content:// URI that you grant")
            else -> throw BadRequest("$what must be an http://, https:// or content:// address")
        }
        return url
    }

    private fun checkHttp(url: String, what: String) {
        if (url.length > AutomationParser.MAX_URL) throw BadRequest("$what is too long")
        if (!httpPattern.matches(url)) throw BadRequest("$what must be an http:// or https:// address")
    }

    // ---- fields -------------------------------------------------------------------------------

    /** A field's value; JSON null counts as left out. */
    private fun JSONObject.value(key: String): Any? = opt(key).takeUnless { it == null || it == JSONObject.NULL }

    /** A string field (numbers too where [numbersToo]); null or "" counts as left out. */
    private fun text(o: JSONObject, key: String, what: String, numbersToo: Boolean = false): String? = when (val v = o.value(key)) {
        null -> null
        is String -> v.trim().ifEmpty { null }
        is Number -> if (numbersToo) plainNumber(v) else throw BadRequest("$what must be text")
        else -> throw BadRequest("$what must be text")
    }

    /** The caller's id: text, or a whole number (42 and "42" are the same id). */
    private fun id(o: JSONObject, what: String): String? {
        val id = when (val v = o.value("id")) {
            null -> return null
            is String -> v
            is Number -> plainNumber(v)
            else -> throw BadRequest("$what must be text or a number")
        }
        if (id.isEmpty()) return null
        checkLength(id, MAX_ID, what)
        if (id.any { it.isISOControl() }) throw BadRequest("$what must not contain control characters")
        return id
    }

    /** 42 rather than 42.0. */
    private fun plainNumber(n: Number): String {
        val d = n.toDouble()
        return if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else n.toString()
    }

    private fun whole(o: JSONObject, key: String, what: String): Long? = when (val v = o.value(key)) {
        null -> null
        is Number -> v.toDouble().let { d -> if (d.isNaN() || d.isInfinite() || d != Math.floor(d)) throw BadRequest("$what must be a whole number") else d.toLong() }
        is String -> v.trim().ifEmpty { null }?.let { it.toLongOrNull() ?: throw BadRequest("$what must be a whole number") }
        else -> throw BadRequest("$what must be a whole number")
    }

    private fun checkInt(v: Long, what: String): Int = if (v in 0..Int.MAX_VALUE) v.toInt() else throw BadRequest("$what must be 0 or more")

    fun checkStartPosition(ms: Long) {
        if (ms !in 0..MAX_START_POSITION_MS) throw BadRequest("\"${AutomationApi.EXTRA_START_POSITION}\" must be 0 to $MAX_START_POSITION_MS")
    }

    private fun checkLength(s: String, max: Int, what: String) {
        if (s.length > max) throw BadRequest("$what is too long (at most $max characters)")
    }
}

/**
 * How CastApp may tell an app's PLAYBACK broadcasts to the right app: the request names its own
 * package in `report_to`, and CastApp must be able to confirm that it really is the caller —
 * else anyone could send play-state events into another app. No Android code.
 */
data class CallerEvidence(
    /** getCallingActivity(): set when the caller used startActivityForResult. Android vouches for it. */
    val callingActivity: String? = null,
    /** Android 14+: getLaunchedFromPackage() / BroadcastReceiver.getSentFromPackage(), when the caller shares its identity. */
    val sharedIdentity: String? = null,
    /** The launching app as Android recorded it (Activity.getReferrer() without the caller's EXTRA_REFERRER). */
    val referrer: String? = null,
    /** Build.VERSION.SDK_INT of this phone. */
    val sdk: Int = 0,
) {
    /** The packages Android vouches for. The recorded referrer only from Android 11 on, which checks it against the caller's uid. */
    val vouchedPackages: Set<String> get() = setOfNotNull(callingActivity, sharedIdentity, referrer?.takeIf { sdk >= TRUSTED_REFERRER_SDK })

    companion object {
        const val TRUSTED_REFERRER_SDK = 30
        val NONE = CallerEvidence()
    }
}

object ReportRules {
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun isPackageName(name: String): Boolean = name.length <= 255 && packagePattern.matches(name)

    /**
     * Null when [reportTo] may get the broadcasts: Android confirms it is the caller, or the caller
     * sent the right secret (a secret is set in Settings; [secretVerified]). Else why not.
     */
    fun check(reportTo: String, caller: CallerEvidence, secretVerified: Boolean): AutomationResult? = when {
        !isPackageName(reportTo) -> AutomationResult.invalid("\"${AutomationApi.EXTRA_REPORT_TO}\" must be a package name")
        reportTo in caller.vouchedPackages || secretVerified -> null
        else -> AutomationResult.unauthorized(
            "CastApp cannot confirm that \"${AutomationApi.EXTRA_REPORT_TO}\" is the app that sent the request. " +
                "Start CastApp with startActivityForResult (or share your identity on Android 14+), or send the secret",
        )
    }
}

/** Turns queue items into CastApp's play list. No Android code: what needs a ContentResolver is passed in. */
object QueueMapping {
    private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")

    /**
     * [names] and [types]: display name and type of content:// addresses (from the ContentResolver),
     * missing where it gave none. The subtitles keep the caller's order: the first is shown.
     */
    fun toMediaItem(item: QueueItem, names: Map<String, String> = emptyMap(), types: Map<String, String> = emptyMap()): MediaItem {
        val name = names[item.url]
        val title = item.title ?: name ?: Sidecars.fileName(item.url).ifBlank { AutomationParser.defaultTitle(item.url) }
        val mime = item.mime
            ?: types[item.url]?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
            ?: if (item.isContent) MediaItem.mimeForName(name ?: "") else MediaItem.mimeForUrl(item.url)
        val subtitleNames = item.subtitles.mapNotNull { sub ->
            val fileName = names[sub.url] ?: Sidecars.fileName(sub.url)
            (subtitleName(sub, fileName) ?: names[sub.url])?.let { sub.url to it }
        }.toMap()
        return MediaItem(
            title = title,
            uri = item.url,
            mimeType = mime,
            subtitleUri = item.subtitles.firstOrNull()?.url,
            moreSubtitles = item.subtitles.drop(1).map { it.url },
            subtitleNames = subtitleNames,
        )
    }

    /**
     * A file name that makes CastApp's subtitle picker show the caller's language and label
     * ("subtitles.en [Signs].ass" → "English (Signs)"); null when the caller gave neither.
     */
    fun subtitleName(sub: QueueSubtitle, fileName: String): String? {
        val label = sub.label?.replace('.', ' ')?.trim()?.ifEmpty { null }
        if (sub.language == null && label == null) return null
        val ext = fileName.substringAfterLast('.', "").lowercase().takeIf { it in SUBTITLE_EXTENSIONS } ?: "srt"
        val tag = when {
            sub.language != null && label != null -> "${sub.language} [$label]"
            else -> sub.language ?: label!!
        }
        return "subtitles.$tag.$ext"
    }

    /** What the "Cast to TV" sheet shows: the request's title, else the first item's (and how many follow). */
    fun sheetTitle(queue: CastQueueRequest, items: List<MediaItem>, more: (first: String, others: Int) -> String): String =
        queue.title ?: items.getOrNull(queue.startIndex)?.title?.let { first ->
            if (items.size > 1) more(first, items.size - 1) else first
        } ?: ""
}
