package io.github.patschgoo.castapp.service

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import io.github.patschgoo.castapp.network.NetworkSources
import io.github.patschgoo.castapp.stream.Subtitles
import io.github.patschgoo.castapp.subtitles.Sidecars
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

enum class MediaType { VIDEO, AUDIO, PHOTO }

/** One thing to play on the TV: a file on the phone or a video from a website. */
data class MediaItem(
    val title: String,
    /** content://… for files on the phone, http(s)://… for web videos. */
    val uri: String,
    val mimeType: String,
    /** content://…, smb://… or http(s)://… of an .srt / .vtt / .ass file: the one shown unless another is picked. */
    val subtitleUri: String? = null,
    /** Web videos: request headers the site expects (Referer, Cookie, User-Agent, Authorization). */
    val headers: Map<String, String> = emptyMap(),
    /** A live stream (TV channel): no seeking, no end. */
    val live: Boolean = false,
    /** Accept a self-signed HTTPS certificate (own NAS). */
    val trustAll: Boolean = false,
    /** Plex / Jellyfin: which server item this is ([io.github.patschgoo.castapp.mediaservers.ItemRef]), so progress can be reported. */
    val serverRef: String? = null,
    /**
     * Plex / Jellyfin: the server's file as it is, when [uri] is the server's converted stream.
     * CastApp TV receivers play far more than a Chromecast (MKV, HEVC 10-bit, ASS subtitles),
     * so they get this first and the converted stream only if it doesn't play.
     */
    val original: String? = null,
    val originalMime: String? = null,
    /** More subtitle files that came with the video (other languages), offered in the subtitle picker after [subtitleUri]. */
    val moreSubtitles: List<String> = emptyList(),
) {
    val type: MediaType
        get() = when {
            mimeType.startsWith("image/") -> MediaType.PHOTO
            mimeType.startsWith("audio/") -> MediaType.AUDIO
            else -> MediaType.VIDEO
        }

    val isWeb: Boolean get() = uri.startsWith("http://") || uri.startsWith("https://")
    /** A file on a Windows/Samba share: smb://<source id>/<path inside the share>. */
    val isSmb: Boolean get() = uri.startsWith("smb://")
    val isHls: Boolean get() = mimeType.contains("mpegurl", ignoreCase = true) || uri.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    /** All subtitle files that came with the video, [subtitleUri] first. */
    val subtitleFiles: List<String> get() = subtitleUri?.let { listOf(it) + moreSubtitles } ?: emptyList()

    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("uri", uri)
        .put("mime", mimeType)
        .put("sub", subtitleUri ?: "")
        .put("headers", JSONObject(headers as Map<*, *>))
        .put("live", live)
        .put("trustAll", trustAll)
        .put("ref", serverRef ?: "")
        .put("orig", original ?: "")
        .put("origMime", originalMime ?: "")
        .also { o -> if (moreSubtitles.isNotEmpty()) o.put("subs", JSONArray(moreSubtitles)) }

    companion object {
        fun fromJson(o: JSONObject): MediaItem {
            val h = o.optJSONObject("headers")
            val headers = HashMap<String, String>()
            h?.keys()?.forEach { k -> headers[k] = h.optString(k) }
            return MediaItem(
                title = o.optString("title"),
                uri = o.optString("uri"),
                mimeType = o.optString("mime"),
                subtitleUri = o.optString("sub").ifEmpty { null },
                headers = headers,
                live = o.optBoolean("live"),
                trustAll = o.optBoolean("trustAll"),
                serverRef = o.optString("ref").ifEmpty { null },
                original = o.optString("orig").ifEmpty { null },
                originalMime = o.optString("origMime").ifEmpty { null },
                moreSubtitles = o.optJSONArray("subs")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } } }.orEmpty(),
            )
        }

        fun listToJson(items: List<MediaItem>): String = JSONArray().also { a -> items.forEach { a.put(it.toJson()) } }.toString()

        fun listFromJson(json: String): List<MediaItem> {
            val a = JSONArray(json)
            return (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }

        /** Guesses the type of a web video address from its extension. */
        fun mimeForUrl(url: String): String = mimeForName(url.substringBefore('?').substringBefore('#'))

        /**
         * Guesses the type of a file from the extension of its [name] ("Show #9.mkv"): unlike in
         * an address, '?' and '#' are part of a name.
         */
        fun mimeForName(name: String): String {
            val path = name.lowercase()
            return when {
                path.endsWith(".m3u8") -> "application/x-mpegurl"
                path.endsWith(".mpd") -> "application/dash+xml"
                path.endsWith(".webm") -> "video/webm"
                path.endsWith(".mkv") -> "video/x-matroska"
                path.endsWith(".mov") -> "video/quicktime"
                path.endsWith(".mp3") -> "audio/mpeg"
                path.endsWith(".m4a") || path.endsWith(".aac") -> "audio/mp4"
                path.endsWith(".ogg") || path.endsWith(".opus") -> "audio/ogg"
                path.endsWith(".flac") -> "audio/flac"
                path.endsWith(".wav") -> "audio/wav"
                path.endsWith(".ts") -> "video/mp2t"
                path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".webp") -> "image/webp"
                path.endsWith(".heic") -> "image/heic"
                path.endsWith(".avi") || path.endsWith(".divx") -> "video/x-msvideo"
                path.endsWith(".srt") -> "application/x-subrip"
                path.endsWith(".vtt") -> "text/vtt"
                else -> "video/mp4"
            }
        }

        /**
         * Builds the play list from picked/shared files: videos, music and photos are
         * kept in order, and subtitle files are attached to the video they are named
         * after ([Sidecars]; or to the only video), one in a [languages] language first.
         */
        fun fromUris(resolver: ContentResolver, uris: List<Uri>, languages: List<String> = Sidecars.preferredLanguages()): List<MediaItem> {
            val named = uris.map { uri ->
                val name = displayName(resolver, uri) ?: uri.lastPathSegment ?: "File"
                Triple(uri, name, typeOf(resolver.getType(uri), name))
            }
            return fromNamed(named, languages)
        }

        /** [fromUris] for files already named and typed: (address, name, type). */
        internal fun <U> fromNamed(named: List<Triple<U, String, String>>, languages: List<String>): List<MediaItem> {
            val subtitleFiles = named.filter { (_, name, _) -> Subtitles.isSubtitleFile(name) }
            val media = named.filter { (_, name, mime) ->
                !Subtitles.isSubtitleFile(name) && (mime.startsWith("video/") || mime.startsWith("audio/") || mime.startsWith("image/"))
            }
            val videos = media.filter { it.third.startsWith("video/") }
            val owned = Sidecars.assign(videos.map { it.second }, subtitleFiles.map { it.second })
            return media.map { file ->
                val (uri, name, mime) = file
                val v = videos.indexOf(file)
                val candidates = when {
                    v < 0 -> emptyList()
                    owned[v].isEmpty() && videos.size == 1 -> subtitleFiles
                    else -> owned[v].map { subtitleFiles[it] }
                }
                val subs = Sidecars.ranked(name, candidates, languages) { it.second }.map { it.first.toString() }
                MediaItem(title = name, uri = uri.toString(), mimeType = mime, subtitleUri = subs.firstOrNull(), moreSubtitles = subs.drop(1))
            }
        }

        /** The type the phone gives a picked file, or one from its name when it gives none ("Film.divx"). */
        private fun typeOf(given: String?, name: String): String =
            given?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
                ?: mimeForName(name).takeIf { NetworkSources.isMediaName(name) }
                ?: given.orEmpty()

        fun displayName(resolver: ContentResolver, uri: Uri): String? = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }

        /**
         * Loads a photo, turns it upright and shrinks it to TV size as JPEG. Phone photos
         * are often 50+ megapixels or HEIC, which Cast devices can't show.
         */
        // android.media.ExifInterface is fine here: it's only used on Android 8.x (26-27), which supports streams.
        // The decoder lambdas only run on Android 9+ (checked in decodeForTv).
        @android.annotation.SuppressLint("NewApi")
        fun loadPhotoForTv(resolver: ContentResolver, uri: Uri, maxW: Int = 1920, maxH: Int = 1080): ByteArray? = decodeForTv(
            source = { ImageDecoder.createSource(resolver, uri) },
            open = { resolver.openInputStream(uri) },
            maxW = maxW,
            maxH = maxH,
        )

        /** Same for a photo already read into memory (e.g. from a network share). */
        @android.annotation.SuppressLint("NewApi")
        fun loadPhotoForTv(data: ByteArray, maxW: Int = 1920, maxH: Int = 1080): ByteArray? = decodeForTv(
            source = { ImageDecoder.createSource(java.nio.ByteBuffer.wrap(data)) },
            open = { java.io.ByteArrayInputStream(data) },
            maxW = maxW,
            maxH = maxH,
        )

        @android.annotation.SuppressLint("NewApi", "ExifInterface")
        private fun decodeForTv(source: () -> ImageDecoder.Source, open: () -> java.io.InputStream?, maxW: Int, maxH: Int): ByteArray? {
            val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(source()) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val scale = minOf(1f, maxW.toFloat() / maxOf(w, h).coerceAtLeast(1) * 1f, maxH.toFloat() / minOf(w, h).coerceAtLeast(1))
                    decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) sample *= 2
                val decoded = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
                val orientation = open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                val degrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (degrees == 0f) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
            }
            return ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                bitmap.recycle()
                out.toByteArray()
            }
        }
    }
}
