package io.github.patschgoo.castapp.youtube

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.cast.CastClient
import io.github.patschgoo.castapp.cast.CastException
import io.github.patschgoo.castapp.cast.RunningApp
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceKind
import io.github.patschgoo.castapp.service.CastAppTvClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException

/**
 * "Play in YouTube on the TV": the TV's own YouTube app plays the video (full
 * quality, no phone needed afterwards). Chromecasts and Google TVs get it through
 * the YouTube Cast receiver and the lounge protocol; a CastApp TV (Fire TV /
 * Android TV) opens the link in its YouTube app.
 */
object YouTubeCast {
    private const val TAG = "YouTubeCast"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    /** Devices with a YouTube app we can start: Google Cast TVs (not speakers or groups) and CastApp TVs. */
    fun supports(device: CastDevice): Boolean =
        (device.kind == DeviceKind.CAST && device.video && !device.group) || device.isCastAppTv

    /** Starts [link] on [device]; progress and errors are shown as toasts. */
    fun play(context: Context, device: CastDevice, link: YouTubeLink) {
        val app = context.applicationContext
        if (!supports(device)) {
            toast(app, app.getString(R.string.yt_error_device, device.name))
            return
        }
        toast(app, app.getString(R.string.yt_starting, device.name))
        scope.launch {
            val message = try {
                if (device.isCastAppTv) openOnCastAppTv(app, device, link) else playOnCast(app, device, link)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "YouTube on ${device.name} failed", e)
                app.getString(R.string.yt_error_unreachable, device.name)
            }
            toast(app, message)
        }
    }

    private suspend fun playOnCast(context: Context, device: CastDevice, link: YouTubeLink): String {
        val c = CastClient(device.host, device.port)
        try {
            try {
                c.connect(6000)
            } catch (e: Exception) {
                return context.getString(R.string.yt_error_unreachable, device.name)
            }
            val app = try {
                c.launch(YouTubeLounge.YOUTUBE_APP_ID, timeoutMs = 30_000)
            } catch (e: Exception) {
                Log.w(TAG, "YouTube receiver didn't start", e)
                return context.getString(R.string.yt_error_no_app, device.name)
            }
            val screenId = screenId(c, app) ?: return context.getString(R.string.yt_error_no_screen, device.name)
            try {
                YouTubeLounge().play(screenId, link)
            } catch (e: YouTubeLounge.LoungeException) {
                Log.w(TAG, "Lounge request failed: ${e.message}")
                return if (e.httpCode > 0) context.getString(R.string.yt_error_refused, e.httpCode.toString())
                else context.getString(R.string.yt_error_refused, e.message ?: "?")
            } catch (e: IOException) {
                Log.w(TAG, "YouTube unreachable", e)
                return context.getString(R.string.yt_error_internet)
            }
            return context.getString(R.string.yt_started, device.name)
        } finally {
            // The YouTube app keeps playing; only our connection closes.
            try { c.close() } catch (_: Exception) {}
        }
    }

    /** Asks the YouTube receiver for its screen id (it may need a moment right after starting). */
    private suspend fun screenId(c: CastClient, app: RunningApp): String? {
        repeat(3) { attempt ->
            try {
                val reply = c.requestCustomMatching(
                    app, YouTubeLounge.NAMESPACE, JSONObject().put("type", YouTubeLounge.TYPE_GET_STATUS), 8000,
                ) { it.optString("type") == YouTubeLounge.TYPE_STATUS }
                YouTubeLounge.screenIdFrom(reply)?.let { return it }
            } catch (e: CastException) {
                Log.i(TAG, "No screen id yet (attempt ${attempt + 1}): ${e.message}")
            }
            delay(1500)
        }
        return null
    }

    private fun openOnCastAppTv(context: Context, device: CastDevice, link: YouTubeLink): String {
        val (code, text) = CastAppTvClient.openYouTube(device, link.canonicalUrl)
        return when (code) {
            200 -> context.getString(R.string.yt_tv_opened, device.name)
            404 -> context.getString(R.string.yt_error_old_tv, device.name)
            -1 -> context.getString(R.string.yt_error_unreachable, device.name)
            else -> text.ifBlank { context.getString(R.string.yt_error_unreachable, device.name) }
        }
    }

    private fun toast(context: Context, text: String) {
        main.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}
