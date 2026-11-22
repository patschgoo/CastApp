package io.github.patschgoo.castapp.support

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.media.MediaCodecList
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.patschgoo.castapp.subtitles.VoskModels
import io.github.patschgoo.castapp.subtitles.WhisperModels
import io.github.patschgoo.castapp.tv.TvReceiverService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket

/** The things the "Test this phone" self-test looks at. */
enum class SelfCheck { H264, HEVC, AAC, SCREEN_CAPTURE, APP_AUDIO, NOTIFICATIONS, BATTERY, INFRARED, BLUETOOTH, NETWORK, MULTICAST, PORT, MODELS }

enum class SelfStatus { PASS, WARN, FAIL }

/**
 * One result. [why] names the explanation (the screen maps it to a text in the user's language),
 * [detail] is extra information such as the names of the encoders.
 */
data class SelfItem(val check: SelfCheck, val status: SelfStatus, val why: String, val detail: String? = null)

/** What was measured on the phone, with no interpretation yet. */
data class SelfTestInput(
    val h264Encoders: List<String> = emptyList(),
    val hevcEncoders: List<String> = emptyList(),
    val aacEncoders: List<String> = emptyList(),
    val screenCapture: Boolean = true,
    val sdk: Int = 34,
    val notificationsAllowed: Boolean = true,
    val batteryExempt: Boolean = true,
    val infraredEmitter: Boolean = false,
    val hasBluetooth: Boolean = true,
    /** null when it could not be checked (no permission, or the Bluetooth stack did not answer). */
    val hidDeviceSupported: Boolean? = true,
    val transport: Transport = Transport.WIFI,
    val vpnActive: Boolean = false,
    val multicastLockWorks: Boolean = true,
    val receiverPortFree: Boolean = true,
    /** The receiver of this app is running and holds the port itself. */
    val receiverRunning: Boolean = false,
    val installedModels: List<String> = emptyList(),
)

/** The "Test this phone" self-test: [evaluate] is pure, [collect] measures the real phone. */
object SelfTest {
    private const val TAG = "CastApp.SelfTest"
    const val RECEIVER_PORT = TvReceiverService.PORT

    private fun names(list: List<String>) = list.take(3).joinToString(", ").ifEmpty { null }

    fun evaluate(i: SelfTestInput): List<SelfItem> = listOf(
        if (i.h264Encoders.isNotEmpty()) SelfItem(SelfCheck.H264, SelfStatus.PASS, "h264_ok", names(i.h264Encoders))
        else SelfItem(SelfCheck.H264, SelfStatus.FAIL, "h264_missing"),

        if (i.hevcEncoders.isNotEmpty()) SelfItem(SelfCheck.HEVC, SelfStatus.PASS, "hevc_ok", names(i.hevcEncoders))
        else SelfItem(SelfCheck.HEVC, SelfStatus.WARN, "hevc_missing"),

        if (i.aacEncoders.isNotEmpty()) SelfItem(SelfCheck.AAC, SelfStatus.PASS, "aac_ok", names(i.aacEncoders))
        else SelfItem(SelfCheck.AAC, SelfStatus.FAIL, "aac_missing"),

        if (i.screenCapture) SelfItem(SelfCheck.SCREEN_CAPTURE, SelfStatus.PASS, "capture_ok")
        else SelfItem(SelfCheck.SCREEN_CAPTURE, SelfStatus.FAIL, "capture_missing"),

        if (i.sdk >= 29) SelfItem(SelfCheck.APP_AUDIO, SelfStatus.PASS, "appaudio_ok")
        else SelfItem(SelfCheck.APP_AUDIO, SelfStatus.WARN, "appaudio_old", "Android ${i.sdk}"),

        if (i.notificationsAllowed) SelfItem(SelfCheck.NOTIFICATIONS, SelfStatus.PASS, "notif_ok")
        else SelfItem(SelfCheck.NOTIFICATIONS, SelfStatus.WARN, "notif_off"),

        if (i.batteryExempt) SelfItem(SelfCheck.BATTERY, SelfStatus.PASS, "battery_ok")
        else SelfItem(SelfCheck.BATTERY, SelfStatus.WARN, "battery_limited"),

        if (i.infraredEmitter) SelfItem(SelfCheck.INFRARED, SelfStatus.PASS, "ir_ok")
        else SelfItem(SelfCheck.INFRARED, SelfStatus.WARN, "ir_none"),

        when {
            !i.hasBluetooth -> SelfItem(SelfCheck.BLUETOOTH, SelfStatus.WARN, "bt_none")
            i.sdk < 28 -> SelfItem(SelfCheck.BLUETOOTH, SelfStatus.WARN, "bt_no_hid", "Android ${i.sdk}")
            i.hidDeviceSupported == null -> SelfItem(SelfCheck.BLUETOOTH, SelfStatus.WARN, "bt_unknown")
            i.hidDeviceSupported -> SelfItem(SelfCheck.BLUETOOTH, SelfStatus.PASS, "bt_ok")
            else -> SelfItem(SelfCheck.BLUETOOTH, SelfStatus.WARN, "bt_no_hid")
        },

        when {
            i.transport == Transport.NONE -> SelfItem(SelfCheck.NETWORK, SelfStatus.FAIL, "net_none")
            i.transport == Transport.CELLULAR -> SelfItem(SelfCheck.NETWORK, SelfStatus.FAIL, "net_cellular")
            i.vpnActive -> SelfItem(SelfCheck.NETWORK, SelfStatus.WARN, "net_vpn")
            else -> SelfItem(SelfCheck.NETWORK, SelfStatus.PASS, "net_ok", if (i.transport == Transport.ETHERNET) "Ethernet" else "Wi-Fi")
        },

        if (i.multicastLockWorks) SelfItem(SelfCheck.MULTICAST, SelfStatus.PASS, "multicast_ok")
        else SelfItem(SelfCheck.MULTICAST, SelfStatus.WARN, "multicast_fail"),

        when {
            i.receiverPortFree -> SelfItem(SelfCheck.PORT, SelfStatus.PASS, "port_free", RECEIVER_PORT.toString())
            i.receiverRunning -> SelfItem(SelfCheck.PORT, SelfStatus.PASS, "port_ours", RECEIVER_PORT.toString())
            else -> SelfItem(SelfCheck.PORT, SelfStatus.WARN, "port_busy", RECEIVER_PORT.toString())
        },

        if (i.installedModels.isNotEmpty()) SelfItem(SelfCheck.MODELS, SelfStatus.PASS, "models_ok", i.installedModels.joinToString(", "))
        else SelfItem(SelfCheck.MODELS, SelfStatus.WARN, "models_none"),
    )

    /** Numbers of passed, warning and failed checks, for the summary line. */
    fun counts(items: List<SelfItem>): Triple<Int, Int, Int> = Triple(
        items.count { it.status == SelfStatus.PASS },
        items.count { it.status == SelfStatus.WARN },
        items.count { it.status == SelfStatus.FAIL },
    )

    /** Plain-text lines for the debug log (always English, so a developer can read them). */
    fun logLines(items: List<SelfItem>): List<String> {
        val (p, w, f) = counts(items)
        return buildList {
            add("Self-test: $p ok, $w warnings, $f failed")
            items.forEach { add("[${it.status}] ${it.check.name} (${it.why})${it.detail?.let { d -> ": $d" } ?: ""}") }
        }
    }

    /** The newest result, added to the debug report. */
    @Volatile var last: List<String> = emptyList()
        private set

    /** Remembers [items] for the debug report and writes them to the app's log. */
    fun record(items: List<SelfItem>) {
        val lines = logLines(items)
        last = lines
        lines.forEach { Log.i(TAG, it) }
    }

    // ---- Measuring the real phone ----------------------------------------------------------

    suspend fun run(context: Context): List<SelfItem> {
        val items = evaluate(collect(context))
        record(items)
        return items
    }

    suspend fun collect(context: Context): SelfTestInput = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val encoders = try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { it.isEncoder }
        } catch (_: Exception) {
            emptyList()
        }
        fun encodersFor(mime: String) = encoders.filter { c -> c.supportedTypes.any { it.equals(mime, true) } }.map { it.name }

        val hasBt = app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        val (transport, vpn) = transportOf(app)
        val running = TvReceiverService.running.value
        VoskModels.refresh(app)
        WhisperModels.refresh(app)
        val models = VoskModels.ALL.filter { it.code in VoskModels.installed.value }.map { "Vosk ${it.code}" } +
            WhisperModels.ALL.filter { it.id in WhisperModels.installed.value }.map { "Whisper ${it.id}" }

        SelfTestInput(
            h264Encoders = encodersFor("video/avc"),
            hevcEncoders = encodersFor("video/hevc"),
            aacEncoders = encodersFor("audio/mp4a-latm"),
            screenCapture = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager != null,
            sdk = Build.VERSION.SDK_INT,
            notificationsAllowed = try { NotificationManagerCompat.from(app).areNotificationsEnabled() } catch (_: Exception) { true },
            batteryExempt = BatteryHelp.isIgnoring(app),
            infraredEmitter = try { (app.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager)?.hasIrEmitter() == true } catch (_: Exception) { false },
            hasBluetooth = hasBt,
            hidDeviceSupported = if (hasBt) hidSupported(app) else false,
            transport = transport,
            vpnActive = vpn,
            multicastLockWorks = multicastWorks(app),
            receiverPortFree = !running && portFree(RECEIVER_PORT),
            receiverRunning = running,
            installedModels = models,
        )
    }

    @Suppress("DEPRECATION")
    private fun transportOf(context: Context): Pair<Transport, Boolean> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return Transport.NONE to false
        var wifi = false
        var eth = false
        var cell = false
        var vpn = false
        try {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { vpn = true; continue }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) eth = true
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cell = true
            }
        } catch (_: Exception) {
        }
        val t = when {
            wifi -> Transport.WIFI
            eth -> Transport.ETHERNET
            cell -> Transport.CELLULAR
            else -> Transport.NONE
        }
        return t to vpn
    }

    private fun multicastWorks(context: Context): Boolean = try {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("castapp-selftest")
        if (lock == null) false else {
            lock.setReferenceCounted(false)
            lock.acquire()
            val held = lock.isHeld
            lock.release()
            held
        }
    } catch (_: Exception) {
        false
    }

    private fun portFree(port: Int): Boolean = try {
        ServerSocket(port).use { true }
    } catch (_: Exception) {
        false
    }

    /** Asks the Bluetooth stack for the HID Device profile; null if that is not possible right now. */
    private suspend fun hidSupported(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return null
        val adapter: BluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
        val answer = CompletableDeferred<BluetoothProfile?>()
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { answer.complete(proxy) }
            override fun onServiceDisconnected(profile: Int) {}
        }
        return try {
            val asked = adapter.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE)
            if (!asked) return false
            val proxy = withTimeoutOrNull(3_000) { answer.await() }
            if (proxy != null) try { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy) } catch (_: Exception) {}
            if (proxy is BluetoothHidDevice) true else null
        } catch (_: Exception) {
            null
        }
    }
}
