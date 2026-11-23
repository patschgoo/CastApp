package io.github.patschgoo.castapp.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.net.toUri
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.network.SourceKind
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.ui.RemotePictures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pictures of "Continue watching": posters and logos from the web ([MediaItem.artUri]),
 * Android's thumbnails of the phone's videos, and one frame of videos on network shares.
 * Kept small ([ArtworkRules.MAX_PX]) in memory and on disk (the app's cache folder, by item),
 * so the main screen shows them at once the next time. Everything runs off the main thread;
 * a picture that is being made is made once, however many cards ask for it, and finishes
 * even when its card scrolls away.
 */
object Artwork {
    private val memory = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val making = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    private val phone = Semaphore(2)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val shares = Dispatchers.IO.limitedParallelism(1)
    private val written = AtomicInteger()

    /** The memory key of [item]'s picture. */
    fun keyOf(item: MediaItem): String = ArtworkRules.cacheKey(item.uri, item.artUri)

    /** [item]'s picture if it's in memory already (no waiting, fine on the main thread). */
    fun inMemory(item: MediaItem): Bitmap? = memory.get(keyOf(item))

    /** [item]'s picture, or null when it has none (yet). [durationMs]: its length, if known. */
    suspend fun load(context: Context, item: MediaItem, durationMs: Long = 0): Bitmap? {
        val key = keyOf(item)
        memory.get(key)?.let { return it }
        val app = context.applicationContext
        val job = making[key] ?: scope.async(start = CoroutineStart.LAZY) { make(app, key, item, durationMs) }.let { fresh ->
            val running = making.putIfAbsent(key, fresh) ?: fresh
            if (running === fresh) fresh.invokeOnCompletion { making.remove(key, fresh) } else fresh.cancel()
            running.start()
            running
        }
        return try {
            job.await()
        } catch (e: CancellationException) {
            throw e // the card went away; the picture is still made and kept
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun make(app: Context, key: String, item: MediaItem, durationMs: Long): Bitmap? {
        val disk = DiskCache(File(app.cacheDir, "artwork"))
        disk.read(key)?.let { memory.put(key, it); return it }
        if (disk.saysNone(key)) return null
        val webDav = try {
            CastApp.get(app).network.sources.value.filter { it.kind == SourceKind.WEBDAV }.map { it.address }
        } catch (_: Exception) {
            emptyList()
        }
        val made: Bitmap? = when (val source = ArtworkRules.sourceFor(item, webDav)) {
            null -> null
            is ArtSource.Picture -> RemotePictures.load(source.url, ArtworkRules.MAX_PX)?.asAndroidBitmap()
            is ArtSource.PhoneFile -> phone.withPermit {
                try {
                    PhoneThumbnails.load(app, source.uri.toUri(), ArtworkRules.MAX_PX, ArtworkRules.MAX_PX, audio = item.mimeType.startsWith("audio/"))
                } catch (_: Exception) {
                    null
                }
            }
            is ArtSource.ShareFile -> withContext(shares) {
                when (val r = ShareFrames.grab(app, item, source, durationMs)) {
                    is ShareFrames.Result.Picture -> r.bitmap
                    ShareFrames.Result.NoPicture -> { disk.writeNone(key); null }
                    ShareFrames.Result.Later -> null
                }
            }
        }
        val small = made?.let(::shrink) ?: return null
        memory.put(key, small)
        // Tidy the folder with the first picture made and then every 20th.
        if (disk.write(key, small) && written.getAndIncrement() % 20 == 0) disk.prune()
        return small
    }

    /** A software bitmap of at most [ArtworkRules.MAX_PX] (thumbnails can be big, or in graphics memory). */
    private fun shrink(bitmap: Bitmap): Bitmap? = try {
        val soft = if (Build.VERSION.SDK_INT >= 26 && bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val (w, h) = ArtworkRules.fitInside(soft.width, soft.height)
        if (w == soft.width && h == soft.height) soft else Bitmap.createScaledBitmap(soft, w, h, true)
    } catch (_: Exception) {
        null
    }

    /**
     * The cached pictures: <key>.img (JPEG, or PNG when it has see-through parts such as a channel
     * logo), and <key>.none for a file that gave none (tried again after a while).
     */
    private class DiskCache(private val dir: File) {
        fun read(key: String): Bitmap? {
            val file = File(dir, "$key.img")
            if (!file.isFile) return null
            return try {
                BitmapFactory.decodeFile(file.path)?.also {
                    val now = System.currentTimeMillis()
                    // Least recently used goes first when the folder is full; once a day is enough.
                    if (now - file.lastModified() > 24 * 3600_000L) file.setLastModified(now)
                }
            } catch (_: Exception) {
                null
            }
        }

        fun saysNone(key: String): Boolean {
            val file = File(dir, "$key.none")
            if (!file.isFile) return false
            if (ArtworkRules.noPictureStillHolds(file.lastModified(), System.currentTimeMillis())) return true
            file.delete()
            return false
        }

        fun writeNone(key: String) {
            try {
                dir.mkdirs()
                File(dir, "$key.none").writeText("")
            } catch (_: Exception) {
            }
        }

        fun write(key: String, bitmap: Bitmap): Boolean =
            try {
                dir.mkdirs()
                val tmp = File(dir, "$key.tmp")
                tmp.outputStream().use {
                    if (bitmap.hasAlpha()) bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    else bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it)
                }
                val file = File(dir, "$key.img")
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
                File(dir, "$key.none").delete()
                true
            } catch (_: Exception) {
                false
            }

        fun prune() {
            try {
                val files = dir.listFiles()?.filter { it.isFile } ?: return
                val listed = files.map { ArtworkRules.CachedFile(it.name, it.length(), it.lastModified()) }
                val byName = files.associateBy { it.name }
                for (f in ArtworkRules.toPrune(listed)) byName[f.name]?.delete()
            } catch (_: Exception) {
            }
        }
    }
}
