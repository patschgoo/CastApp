package io.github.patschgoo.castapp.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Small pictures from the web (channel logos, album art), kept in memory. */
private object ImageCache {
    val images = LruCache<String, ImageBitmap>(300)
    val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val downloads = Semaphore(4)

    suspend fun load(url: String, sizePx: Int): ImageBitmap? = downloads.withPermit {
        images.get(url)?.let { return it }
        if (url in failed) return null
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            val bytes = try {
                if (conn.responseCode !in 200..299) null else conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (out.size() < 2 * 1024 * 1024) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
            if (bytes == null) {
                failed += url
                return null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()
            if (bitmap == null) failed += url else images.put(url, bitmap)
            bitmap
        } catch (_: Exception) {
            failed += url
            null
        }
    }
}

/** The same downloads and memory cache for code outside a composable ([io.github.patschgoo.castapp.artwork.Artwork]). */
internal object RemotePictures {
    suspend fun load(url: String, sizePx: Int): ImageBitmap? = withContext(Dispatchers.IO) { ImageCache.load(url, sizePx) }
}

/** Shows the picture at [url], or [fallback] while it loads or if there is none. */
@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier, sizePx: Int = 128, fallback: @Composable () -> Unit) {
    val image by produceState(url?.let { ImageCache.images.get(it) }, url) {
        if (url != null && value == null && (url.startsWith("http://") || url.startsWith("https://"))) {
            value = withContext(Dispatchers.IO) { ImageCache.load(url, sizePx) }
        }
    }
    val bitmap = image
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit) else fallback()
}
