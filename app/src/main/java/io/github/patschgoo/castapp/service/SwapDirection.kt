package io.github.patschgoo.castapp.service

import android.content.Context
import android.content.Intent
import io.github.patschgoo.castapp.ProjectionActivity
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.tv.TvReceiverService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL

/**
 * Swaps the mirroring direction with a CastApp TV (Fire TV / Android TV with this
 * app): the phone becomes the receiver and the TV mirrors its screen to the phone,
 * and back again. Only CastApp TVs can do this; Chromecasts, Apple TVs and smart
 * TVs have no way to send their screen.
 */
object SwapDirection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The TV that is mirroring to this phone after a swap (the player offers "Swap back"). */
    @Volatile var partner: CastDevice? = null
        private set

    /** The receiver was started only for the swap: stop it again afterwards. */
    @Volatile private var startedReceiver = false

    /** Phone → TV mirroring becomes TV → phone. Call from a user action (the receiver service starts here). */
    fun toPhone(context: Context, tv: CastDevice) {
        val app = context.applicationContext
        val base = baseUrl(tv)
        if (!tv.isCastAppTv || base == null) {
            CastController.message(app.getString(R.string.swap_error_not_castapp_tv))
            return
        }
        val wasRunning = TvReceiverService.running.value
        TvReceiverService.start(app)
        scope.launch {
            // Stop mirroring to the TV first: its player would otherwise be in what it captures.
            CastService.stop(app)
            val port = withTimeoutOrNull(8000) { TvReceiverService.port.first { it > 0 } }
            if (port == null) {
                CastController.message(app.getString(R.string.swap_error_receiver))
                return@launch
            }
            delay(1200)
            val local = localAddressFor(tv.host)
            if (local == null) {
                CastController.message(app.getString(R.string.swap_error_unreachable))
                return@launch
            }
            partner = tv
            startedReceiver = !wasRunning
            val body = JSONObject().put("description", descriptionUrl(local, port)).toString()
            val (code, text) = post("$base/castapp/mirror", body)
            when (code) {
                200 -> CastController.message(app.getString(R.string.swap_started, tv.name))
                404 -> fail(app, app.getString(R.string.swap_error_old_tv, tv.name))
                -1 -> fail(app, app.getString(R.string.swap_error_unreachable))
                else -> fail(app, text.ifBlank { app.getString(R.string.swap_error_unreachable) })
            }
        }
    }

    /** TV → phone mirroring becomes phone → TV again (Android asks for the phone's capture permission). */
    fun backToTv(context: Context) {
        val app = context.applicationContext
        val tv = partner ?: return
        partner = null
        scope.launch {
            baseUrl(tv)?.let { post("$it/castapp/stop-mirror", "{}") }
            if (startedReceiver) {
                startedReceiver = false
                delay(800)
                TvReceiverService.stop(app)
            }
        }
        app.startActivity(ProjectionActivity.intent(app, tv, ProjectionActivity.MODE_SCREEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The viewer closed the player: the swap is over (the TV notices playback stopped). */
    fun ended(context: Context) {
        val tv = partner ?: return
        partner = null
        val app = context.applicationContext
        scope.launch {
            baseUrl(tv)?.let { post("$it/castapp/stop-mirror", "{}") }
            if (startedReceiver) {
                startedReceiver = false
                TvReceiverService.stop(app)
            }
        }
    }

    private fun fail(context: Context, message: String) {
        partner = null
        CastController.message(message)
        if (startedReceiver) {
            startedReceiver = false
            TvReceiverService.stop(context)
        }
    }

    /** "http://host:port" of a CastApp TV's renderer (from its control URLs). */
    fun baseUrl(tv: CastDevice): String? {
        val url = tv.avTransportUrl ?: return null
        return try {
            val u = URI(url)
            if (u.host == null) null else "${u.scheme}://${u.host}:${if (u.port > 0) u.port else 80}"
        } catch (_: Exception) {
            null
        }
    }

    fun descriptionUrl(local: InetAddress, port: Int) = "http://${local.hostAddress}:$port/description.xml"

    /** Our address on the network the TV is on. */
    private fun localAddressFor(host: String): InetAddress? = try {
        DatagramSocket().use { s ->
            s.connect(InetAddress.getByName(host), 9)
            s.localAddress.takeIf { !it.isAnyLocalAddress }
        }
    } catch (_: Exception) {
        null
    }

    /** POSTs [json]; returns the status code (-1 if unreachable) and the answer text. */
    private fun post(url: String, json: String): Pair<Int, String> = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val bytes = json.toByteArray()
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            code to text
        } finally {
            conn.disconnect()
        }
    } catch (_: IOException) {
        -1 to ""
    }
}
