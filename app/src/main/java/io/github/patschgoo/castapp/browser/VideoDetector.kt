package io.github.patschgoo.castapp.browser

import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.R
import androidx.annotation.StringRes
import io.github.patschgoo.castapp.service.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/** A video (or audio) found on a web page. */
data class FoundVideo(
    val url: String,
    val kind: Kind,
    val pageUrl: String,
    val pageTitle: String,
    /** The picture the page shows for it: the player's poster, else the page's own (og:image). */
    val poster: String? = null,
) {
    enum class Kind(@StringRes val labelRes: Int) {
        HLS(R.string.kind_stream_hls),
        DASH(R.string.kind_stream_dash),
        FILE(R.string.kind_video_file),
        AUDIO(R.string.kind_audio);

        val label: String get() = Txt.s(labelRes)
    }

    val host: String get() = try { URI(url).host ?: "" } catch (_: Exception) { "" }

    val mimeType: String
        get() = when (kind) {
            Kind.HLS -> "application/x-mpegurl"
            Kind.DASH -> "application/dash+xml"
            Kind.AUDIO -> when {
                path.endsWith(".mp3") -> "audio/mpeg"
                path.endsWith(".ogg") || path.endsWith(".opus") -> "audio/ogg"
                path.endsWith(".flac") -> "audio/flac"
                path.endsWith(".wav") -> "audio/wav"
                else -> "audio/mp4"
            }
            Kind.FILE -> when {
                path.endsWith(".webm") -> "video/webm"
                path.endsWith(".mkv") -> "video/x-matroska"
                path.endsWith(".mov") -> "video/quicktime"
                else -> "video/mp4"
            }
        }

    private val path: String get() = url.substringBefore('?').substringBefore('#').lowercase()
}

/**
 * Collects the media addresses a page loads (seen through the browser's network
 * requests and its <video>/<audio> elements). Stream segments, blobs and the like
 * are ignored; HLS master playlists are listed first.
 */
class VideoDetector {
    private val found = LinkedHashMap<String, FoundVideo>()
    private val _videos = MutableStateFlow<List<FoundVideo>>(emptyList())
    val videos: StateFlow<List<FoundVideo>> = _videos.asStateFlow()

    @Volatile var pageUrl: String = ""
        private set
    @Volatile var pageTitle: String = ""
    /** The page's own picture (og:image / twitter:image), for videos whose player has no poster. */
    @Volatile var pagePicture: String? = null
        private set

    @Synchronized
    fun newPage(url: String) {
        // Same page (e.g. a hash change): keep what was found.
        if (url.substringBefore('#') == pageUrl.substringBefore('#')) return
        pageUrl = url
        pageTitle = ""
        pagePicture = null
        found.clear()
        _videos.value = emptyList()
    }

    /**
     * A request the page made. [mimeHint] is a type from a <source> tag, if any; [poster] the
     * poster of the <video> it came from (web addresses only; data: pictures are left out).
     */
    @Synchronized
    fun onUrl(url: String, mimeHint: String? = null, poster: String? = null) {
        if (!url.startsWith("http")) return
        val own = MediaItem.pictureAddress(poster)
        found[url]?.let { known ->
            // Seen through the network first: the player's poster comes with the next scan.
            if (own != null && known.poster != own) {
                found[url] = known.copy(poster = own)
                publish()
            }
            return
        }
        val kind = classify(url, mimeHint) ?: return
        found[url] = FoundVideo(url, kind, pageUrl, pageTitle, own ?: pagePicture)
        publish()
    }

    /** The page's og:image: the picture of every video found on it without a poster of its own. */
    @Synchronized
    fun updatePagePicture(url: String?) {
        val picture = MediaItem.pictureAddress(url) ?: return
        if (picture == pagePicture) return
        val old = pagePicture
        pagePicture = picture
        for ((k, v) in found) if (v.poster == null || v.poster == old) found[k] = v.copy(poster = picture)
        publish()
    }

    @Synchronized
    fun updateTitle(title: String) {
        if (title.isBlank()) return
        pageTitle = title
        for ((k, v) in found) found[k] = v.copy(pageTitle = title)
        publish()
    }

    private fun publish() {
        // Streams first (the master playlist was usually requested first), then files, then audio.
        _videos.value = found.values.sortedBy {
            when (it.kind) {
                FoundVideo.Kind.HLS, FoundVideo.Kind.DASH -> 0
                FoundVideo.Kind.FILE -> 1
                FoundVideo.Kind.AUDIO -> 2
            }
        }
    }

    companion object {
        private val VIDEO_EXT = listOf(".mp4", ".m4v", ".webm", ".mov", ".mkv")
        private val AUDIO_EXT = listOf(".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wav")
        private val SEGMENT_HINTS = listOf("/seg", "segment", "/chunk", "chunk-", "frag", "/range/", "init.mp4", "-init.", "_init.")

        fun classify(url: String, mimeHint: String? = null): FoundVideo.Kind? {
            val hint = mimeHint?.lowercase().orEmpty()
            val lower = url.lowercase()
            val path = lower.substringBefore('?').substringBefore('#')
            if (path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".m4f") || path.endsWith(".vtt") || path.endsWith(".srt")) return null
            return when {
                hint.contains("mpegurl") || path.endsWith(".m3u8") -> FoundVideo.Kind.HLS
                hint.contains("dash") || path.endsWith(".mpd") -> FoundVideo.Kind.DASH
                hint.startsWith("audio/") -> FoundVideo.Kind.AUDIO
                hint.startsWith("video/") -> FoundVideo.Kind.FILE
                VIDEO_EXT.any { path.endsWith(it) } && SEGMENT_HINTS.none { path.contains(it) } && !lower.contains("range=") -> FoundVideo.Kind.FILE
                AUDIO_EXT.any { path.endsWith(it) } && SEGMENT_HINTS.none { path.contains(it) } -> FoundVideo.Kind.AUDIO
                else -> null
            }
        }

        /**
         * JavaScript that lists the page's <video>/<audio> sources (blob: URLs can't be cast) with
         * their posters, and the page's own picture.
         */
        const val SCAN_SCRIPT = """
            (function () {
              var out = [];
              document.querySelectorAll('video, audio').forEach(function (m) {
                var p = m.poster || '';
                var s = m.currentSrc || m.src;
                if (s && s.indexOf('blob:') !== 0) out.push({ src: s, type: '', poster: p });
                m.querySelectorAll('source').forEach(function (x) {
                  if (x.src && x.src.indexOf('blob:') !== 0) out.push({ src: x.src, type: x.type || '', poster: p });
                });
              });
              var meta = document.querySelector('meta[property="og:image"], meta[name="og:image"], meta[name="twitter:image"]');
              return JSON.stringify({ title: document.title, picture: meta ? meta.content : '', items: out });
            })()
        """

        const val PAUSE_SCRIPT = "document.querySelectorAll('video, audio').forEach(function (m) { try { m.pause(); } catch (e) {} });"
    }
}
