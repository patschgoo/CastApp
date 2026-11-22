package io.github.patschgoo.castapp.iptv

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.network.NetworkSource
import io.github.patschgoo.castapp.network.RawHttp
import io.github.patschgoo.castapp.network.SmbConnection
import io.github.patschgoo.castapp.network.SourceKind
import io.github.patschgoo.castapp.network.WebDav
import io.github.patschgoo.castapp.service.MediaItem
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.EnumSet
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection

/**
 * A file being written. [stream] gets the recording; after it was closed [finish] settles the
 * destination (makes a gallery file visible, checks that the server accepted an upload, or removes
 * an empty file) and returns a problem the destination reported, or null.
 */
class RecordingOutput(
    val stream: OutputStream,
    /** What to store in [Recording.location]. */
    val location: String,
    val fileName: String,
    private val settle: (keep: Boolean) -> String?,
) {
    fun finish(keep: Boolean): String? = try {
        settle(keep)
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }
}

/** The places a recording can be written to: the phone, the shared Movies folder, a network folder. */
object RecordingSinks {
    /** The folder (inside the app's Movies folder, the gallery's Movies folder or a share) that holds recordings. */
    const val FOLDER = "CastApp"

    fun authority(context: Context) = "${context.packageName}.recordings"

    fun phoneDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "Movies")
        return File(base, FOLDER).apply { mkdirs() }
    }

    /** Whether the gallery option exists on this phone (it needs Android 10+). */
    val galleryAvailable: Boolean get() = Build.VERSION.SDK_INT >= 29

    fun open(context: Context, target: RecordingTarget, sourceId: String, fileName: String): RecordingOutput = when (target) {
        RecordingTarget.PHONE -> openPhone(context, fileName)
        RecordingTarget.GALLERY -> if (Build.VERSION.SDK_INT >= 29) openGallery(context, fileName) else openPhone(context, fileName)
        RecordingTarget.NETWORK -> {
            val source = CastApp.get(context).network.get(sourceId) ?: throw IOException("The network folder is not saved any more")
            openNetwork(source, fileName)
        }
    }

    private fun openPhone(context: Context, wanted: String): RecordingOutput {
        val dir = phoneDir(context)
        val name = RecordingNames.unique(wanted, dir.list()?.toSet().orEmpty())
        val file = File(dir, name)
        val out = BufferedOutputStream(file.outputStream(), 256 * 1024)
        return RecordingOutput(out, file.absolutePath, name) { keep ->
            if (!keep || file.length() == 0L) file.delete()
            null
        }
    }

    @RequiresApi(29)
    private fun openGallery(context: Context, name: String): RecordingOutput {
        val resolver = context.contentResolver
        val mime = RecordingNames.mimeFor(name.substringAfterLast('.', "ts"))
        val audio = mime.startsWith("audio/")
        val collection = if (audio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Can't create the file in the gallery")
        val raw = try {
            resolver.openOutputStream(uri) ?: throw IOException("Can't write to the gallery")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw if (e is IOException) e else IOException(e.message, e)
        }
        return RecordingOutput(BufferedOutputStream(raw, 256 * 1024), uri.toString(), name) { keep ->
            if (keep) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } else {
                resolver.delete(uri, null, null)
            }
            null
        }
    }

    private fun openNetwork(source: NetworkSource, name: String): RecordingOutput = when (source.kind) {
        SourceKind.SMB -> openSmb(source, name)
        SourceKind.WEBDAV -> openWebDav(source, name)
    }

    private fun openSmb(source: NetworkSource, name: String): RecordingOutput {
        val connection = SmbConnection(source.smbTarget)
        try {
            val share = connection.share()
            if (!share.folderExists(FOLDER)) share.mkdir(FOLDER)
            val path = "$FOLDER\\$name"
            val file = share.openFile(
                path,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null,
            )
            val out = BufferedOutputStream(file.outputStream, 512 * 1024)
            return RecordingOutput(out, "smb://${source.id}/$FOLDER/$name", name) { keep ->
                var problem: String? = null
                try {
                    file.close()
                    if (!keep) share.rm(path)
                } catch (e: Exception) {
                    problem = e.message
                } finally {
                    connection.close()
                }
                problem
            }
        } catch (e: Exception) {
            connection.close()
            throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun encode(name: String) = URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    private fun openWebDav(source: NetworkSource, name: String): RecordingOutput {
        val headers = WebDav.authHeader(source.user, source.password)
        val folder = source.address.trimEnd('/') + "/" + FOLDER
        // The folder is made once; "405" means it is there already.
        val made = RawHttp.request("MKCOL", "$folder/", headers, null, source.trustAll)
        when (made.status) {
            401, 403 -> throw IOException("Wrong user name or password")
        }
        val url = "$folder/${encode(name)}"
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            if (conn is HttpsURLConnection && source.trustAll) {
                conn.sslSocketFactory = RawHttp.trustAllFactory()
                conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.setChunkedStreamingMode(256 * 1024)
            conn.setRequestProperty("Content-Type", RecordingNames.mimeFor(name.substringAfterLast('.', "ts")))
            conn.setRequestProperty("User-Agent", "CastApp")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            val out = conn.outputStream
            return RecordingOutput(out, url, name) { keep ->
                var problem: String? = null
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) problem = "The server answered $code"
                } catch (e: IOException) {
                    problem = e.message ?: "The upload did not finish"
                } finally {
                    conn.disconnect()
                }
                if (!keep || problem != null) {
                    runCatching { RawHttp.request("DELETE", url, headers, null, source.trustAll) }
                }
                problem
            }
        } catch (e: Exception) {
            conn.disconnect()
            throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    // ---- afterwards ---------------------------------------------------------------------------------------

    fun delete(context: Context, r: Recording) {
        when (r.target) {
            RecordingTarget.PHONE -> File(r.location).delete()
            RecordingTarget.GALLERY -> context.contentResolver.delete(r.location.toUri(), null, null)
            RecordingTarget.NETWORK -> {
                val network = CastApp.get(context).network
                val source = network.get(r.sourceId) ?: return
                when (source.kind) {
                    SourceKind.SMB -> {
                        val path = r.location.removePrefix("smb://${source.id}/")
                        network.smb(source).share().rm(SmbConnection.smbPath(path))
                    }
                    SourceKind.WEBDAV -> RawHttp.request("DELETE", r.location, WebDav.authHeader(source.user, source.password), null, source.trustAll)
                }
            }
        }
    }

    /** An address that other apps (a video player) can open; null for network folders and missing files. */
    fun viewUri(context: Context, r: Recording): Uri? = when (r.target) {
        RecordingTarget.PHONE -> File(r.location).takeIf { it.exists() }?.let { FileProvider.getUriForFile(context, authority(context), it) }
        RecordingTarget.GALLERY -> r.location.toUri()
        RecordingTarget.NETWORK -> null
    }

    /** A recording as something to cast to a TV. */
    fun mediaItem(context: Context, r: Recording): MediaItem? {
        val mime = RecordingNames.mimeFor(r.fileName.substringAfterLast('.', "ts"))
        val title = if (r.title == r.channelName) r.title else "${r.channelName} · ${r.title}"
        return when (r.target) {
            RecordingTarget.PHONE, RecordingTarget.GALLERY -> viewUri(context, r)?.let { MediaItem(title = title, uri = it.toString(), mimeType = mime) }
            RecordingTarget.NETWORK -> {
                val source = CastApp.get(context).network.get(r.sourceId) ?: return null
                when (source.kind) {
                    SourceKind.SMB -> MediaItem(title = title, uri = r.location, mimeType = mime)
                    SourceKind.WEBDAV -> MediaItem(
                        title = title, uri = r.location, mimeType = mime,
                        headers = WebDav.authHeader(source.user, source.password), trustAll = source.trustAll,
                    )
                }
            }
        }
    }
}
