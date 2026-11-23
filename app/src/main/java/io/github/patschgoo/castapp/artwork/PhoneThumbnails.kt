package io.github.patschgoo.castapp.artwork

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size

/**
 * Small pictures of the phone's own videos (and covers of its music), as Android's gallery has
 * them: ContentResolver.loadThumbnail from Android 10, the media library's thumbnails and the
 * file manager's below, else a frame of the file itself. Blocking: call it off the main thread.
 */
internal object PhoneThumbnails {
    fun load(context: Context, uri: Uri, widthPx: Int, heightPx: Int, audio: Boolean = false): Bitmap? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return resolver.loadThumbnail(uri, Size(widthPx, heightPx), null)
            } catch (_: Exception) {
                // Not every provider makes thumbnails: read the file below.
            }
        } else {
            mediaStoreVideoId(uri)?.let { id ->
                try {
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(resolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)?.let { return it }
                } catch (_: Exception) {
                }
            }
            if (isDocument(context, uri)) {
                try {
                    DocumentsContract.getDocumentThumbnail(resolver, uri, Point(widthPx, heightPx), null)?.let { return it }
                } catch (_: Exception) {
                }
            }
        }
        return fromFile(context, uri, maxOf(widthPx, heightPx), audio)
    }

    /** The id of a video in Android's media library (content://media/external/video/media/42). */
    private fun mediaStoreVideoId(uri: Uri): Long? {
        if (uri.authority != MediaStore.AUTHORITY || uri.pathSegments?.contains("video") != true) return null
        return try { ContentUris.parseId(uri).takeIf { it >= 0 } } catch (_: Exception) { null }
    }

    private fun isDocument(context: Context, uri: Uri): Boolean = try {
        DocumentsContract.isDocumentUri(context, uri)
    } catch (_: Exception) {
        false
    }

    /** A frame (or the cover of a song) read from the file. */
    private fun fromFile(context: Context, uri: Uri, maxPx: Int, audio: Boolean): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            if (audio) coverOf(retriever, maxPx) else frameOf(retriever, maxPx, timeUs = -1)
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /** The cover picture inside a music file, shrunk while decoding. */
    fun coverOf(retriever: MediaMetadataRetriever, maxPx: Int): Bitmap? {
        val bytes = retriever.embeddedPicture ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** One frame near [timeUs] (-1: one Android picks), at most [maxPx] on its longer side where Android can scale while decoding. */
    fun frameOf(retriever: MediaMetadataRetriever, maxPx: Int, timeUs: Long): Bitmap? =
        if (Build.VERSION.SDK_INT >= 27) {
            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxPx, maxPx)
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
}
