package io.github.patschgoo.castapp.remote

import io.github.patschgoo.castapp.Txt
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import io.github.patschgoo.castapp.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.Executor

/**
 * The phone as a Bluetooth keyboard + media remote (HID Device profile, Android 9+) for
 * Fire TV, NVIDIA Shield, Android TV / Google TV boxes and anything that takes a Bluetooth
 * keyboard. The report layout lives in [HidReports].
 */
object BluetoothRemote : RemoteProvider {
    override val title: String get() = Txt.s(R.string.bt_remote_label)

    private const val PREFS = "bluetooth_remote"
    private const val KEY_HOST = "host"
    private const val KEY_UNSUPPORTED = "unsupported_on"

    private val list = MutableStateFlow<List<RemoteTarget>>(emptyList())
    @Volatile private var probing = false
    @Volatile private var probedOk = false

    override fun targets(context: Context): StateFlow<List<RemoteTarget>> {
        val app = context.applicationContext
        if (list.value.isEmpty() && mightWork(app)) list.value = listOf(Target(app))
        return list
    }

    /** Checks once in the background whether the HID Device profile is really there. */
    override fun startDiscovery(context: Context) {
        val app = context.applicationContext
        if (probing || probedOk || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || list.value.isEmpty()) return
        val adapter = adapter(app) ?: return
        if (!isEnabled(adapter)) return // profiles only run while Bluetooth is on
        probing = true
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                probing = false
                probedOk = true
                try { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy) } catch (_: Exception) {}
            }
            override fun onServiceDisconnected(profile: Int) {}
        }
        val asked = try { adapter.getProfileProxy(app, listener, BluetoothProfile.HID_DEVICE) } catch (_: Exception) { true }
        if (!asked) {
            markUnsupported(app)
            probing = false
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (probing && isEnabled(adapter)) markUnsupported(app)
            probing = false
        }, 8_000)
    }

    private fun mightWork(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) return false
        if (adapter(context) == null) return false
        return prefs(context).getString(KEY_UNSUPPORTED, null) != Build.FINGERPRINT
    }

    /** Hides the target until the next system update (some phones' stacks lack the HID Device profile). */
    private fun markUnsupported(context: Context) {
        prefs(context).edit { putString(KEY_UNSUPPORTED, Build.FINGERPRINT) }
        list.value = emptyList()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun adapter(context: Context): BluetoothAdapter? =
        try { context.getSystemService(BluetoothManager::class.java)?.adapter } catch (_: Exception) { null }

    private fun isEnabled(adapter: BluetoothAdapter) = try { adapter.isEnabled } catch (_: Exception) { false }

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyArray() // BLUETOOTH / BLUETOOTH_ADMIN are granted at install time
        }

    private class Target(app: Context) : RemoteTarget {
        override val id = "bluetooth:hid"
        override val name = app.getString(R.string.bt_remote_name)
        override val label = app.getString(R.string.bt_remote_label)
        override val keys: Set<RemoteKey> = HidReports.keyMap.keys
        override val supportsText = true

        override suspend fun connect(context: Context, ui: RemoteUi): RemoteSession {
            val app = context.applicationContext
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) throw IOException(app.getString(R.string.bt_remote_too_old))
            val adapter = adapter(app) ?: throw IOException(app.getString(R.string.bt_remote_no_bluetooth))

            val missing = neededPermissions().filter { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty() && !RemoteHelperActivity.requestPermissions(context, missing.toTypedArray())) {
                throw IOException(app.getString(R.string.bt_remote_need_permission))
            }
            if (!isEnabled(adapter)) {
                if (!RemoteHelperActivity.requestEnableBluetooth(context)) throw IOException(app.getString(R.string.bt_remote_need_bluetooth))
                withTimeoutOrNull(10_000) { while (!isEnabled(adapter)) delay(200) }
                    ?: throw IOException(app.getString(R.string.bt_remote_need_bluetooth))
            }

            ui.showMessage(app.getString(R.string.bt_remote_starting))
            val session = HidSession(app, adapter)
            try {
                session.start(context, ui)
                return session
            } catch (t: Throwable) {
                session.close()
                throw t
            }
        }
    }

    /** One registration of the HID app; kept until [close] so the TV stays connected. */
    @RequiresApi(Build.VERSION_CODES.P)
    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked/requested in Target.connect
    private class HidSession(private val app: Context, private val adapter: BluetoothAdapter) : RemoteSession {
        private val proxyReady = CompletableDeferred<BluetoothHidDevice?>()
        @Volatile private var hid: BluetoothHidDevice? = null
        private val registered = MutableStateFlow(false)
        @Volatile private var everRegistered = false
        @Volatile private var plugged: BluetoothDevice? = null
        private val host = MutableStateFlow<BluetoothDevice?>(null)
        @Volatile private var closed = false
        private val sendLock = Mutex()

        private val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (closed) {
                    try { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy) } catch (_: Exception) {}
                    return
                }
                hid = proxy as? BluetoothHidDevice
                proxyReady.complete(hid)
            }

            override fun onServiceDisconnected(profile: Int) {
                // Bluetooth was turned off or its service crashed.
                hid = null
                registered.value = false
                host.value = null
                proxyReady.complete(null)
            }
        }

        private val callback = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                if (registered) {
                    everRegistered = true
                    plugged = pluggedDevice
                }
                this@HidSession.registered.value = registered
                if (!registered) host.value = null
            }

            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        host.value = device
                        prefs(app).edit { putString(KEY_HOST, device.address) }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> if (host.value == device) host.value = null
                }
            }

            override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
                val size = when (id.toInt()) {
                    HidReports.ID_KEYBOARD -> HidReports.KEYBOARD_SIZE
                    HidReports.ID_CONSUMER -> HidReports.CONSUMER_SIZE
                    else -> -1
                }
                try {
                    if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && size > 0) {
                        hid?.replyReport(device, type, id, ByteArray(size))
                    } else {
                        hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                    }
                } catch (_: Exception) {
                }
            }

            override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
                // No output reports (keyboard LEDs); acknowledge so the host doesn't stall.
                try { hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS) } catch (_: Exception) {}
            }

            override fun onVirtualCableUnplug(device: BluetoothDevice) {
                if (prefs(app).getString(KEY_HOST, null) == device.address) prefs(app).edit { remove(KEY_HOST) }
                if (host.value == device) host.value = null
            }
        }

        suspend fun start(context: Context, ui: RemoteUi) {
            val asked = try { adapter.getProfileProxy(app, listener, BluetoothProfile.HID_DEVICE) } catch (_: Exception) { false }
            if (!asked) unsupported()
            val proxy = withTimeoutOrNull(8_000) { proxyReady.await() }
            if (proxy == null) {
                if (isEnabled(adapter)) unsupported()
                throw IOException(app.getString(R.string.bt_remote_need_bluetooth))
            }

            val sdp = BluetoothHidDeviceAppSdpSettings(
                "CastApp Remote",
                "Remote control and keyboard",
                "CastApp",
                // Keyboard + remote control (0x43)
                (BluetoothHidDevice.SUBCLASS1_KEYBOARD.toInt() or BluetoothHidDevice.SUBCLASS2_REMOTE_CONTROL.toInt()).toByte(),
                HidReports.descriptor,
            )
            val direct = Executor { it.run() }
            val ok = try { proxy.registerApp(sdp, null, null, direct, callback) } catch (_: Exception) { false }
            if (!ok || withTimeoutOrNull(6_000) { registered.first { it } } == null) {
                throw IOException(app.getString(R.string.bt_remote_busy))
            }

            // A TV that knows us may connect by itself right after registration.
            if (withTimeoutOrNull(1_500) { host.first { it != null } } != null) return connected(ui)

            for (device in knownHosts()) {
                ui.showMessage(app.getString(R.string.bt_remote_reconnecting, nameOf(device)))
                try { proxy.connect(device) } catch (_: Exception) {}
                if (withTimeoutOrNull(10_000) { host.first { it != null } } != null) return connected(ui)
            }

            // New TV: make the phone visible and let the TV pair with it.
            ui.showMessage(app.getString(R.string.bt_remote_make_visible))
            if (!RemoteHelperActivity.requestDiscoverable(context, 180)) {
                throw IOException(app.getString(R.string.bt_remote_not_visible))
            }
            val phoneName = try { adapter.name } catch (_: Exception) { null } ?: app.getString(R.string.bt_remote_this_phone)
            ui.showMessage(app.getString(R.string.bt_remote_pair_steps, phoneName))
            val deadline = System.currentTimeMillis() + 200_000
            while (System.currentTimeMillis() < deadline) {
                if (!registered.value) throw IOException(app.getString(R.string.bt_remote_lost))
                if (withTimeoutOrNull(12_000) { host.first { it != null } } != null) return connected(ui)
                // Also try TVs that were paired from the phone's Bluetooth settings meanwhile.
                for (device in knownHosts() + pairedTvs()) {
                    if (host.value != null) break
                    try { hid?.connect(device) } catch (_: Exception) {}
                }
            }
            throw IOException(app.getString(R.string.bt_remote_timeout))
        }

        private fun connected(ui: RemoteUi) {
            host.value?.let { ui.showMessage(app.getString(R.string.bt_remote_connected, nameOf(it))) }
        }

        private fun unsupported(): Nothing {
            markUnsupported(app)
            throw IOException(app.getString(R.string.bt_remote_unsupported))
        }

        private fun nameOf(device: BluetoothDevice): String =
            try { device.name } catch (_: Exception) { null } ?: app.getString(R.string.bt_remote_the_tv)

        /** The host the profile remembers, then the last one we were connected to (if still paired). */
        private fun knownHosts(): List<BluetoothDevice> {
            val bonded = try { adapter.bondedDevices.orEmpty() } catch (_: Exception) { emptySet() }
            val saved = prefs(app).getString(KEY_HOST, null)
            return listOfNotNull(plugged, bonded.firstOrNull { it.address == saved }).distinctBy { it.address }
        }

        /** Paired devices that call themselves a TV or set-top box. */
        private fun pairedTvs(): List<BluetoothDevice> {
            val bonded = try { adapter.bondedDevices.orEmpty() } catch (_: Exception) { emptySet() }
            val tvClasses = setOf(
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER,
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR,
                BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX,
            )
            return bonded.filter { d -> try { d.bluetoothClass?.deviceClass in tvClasses } catch (_: Exception) { false } }
        }

        /** The connected TV; tries to reconnect once if it dropped (e.g. the box went to sleep). */
        private suspend fun currentHost(): BluetoothDevice {
            host.value?.let { return it }
            val proxy = hid
            if (closed || proxy == null || !registered.value) throw IOException(app.getString(R.string.bt_remote_lost))
            knownHosts().firstOrNull()?.let { try { proxy.connect(it) } catch (_: Exception) {} }
            return withTimeoutOrNull(6_000) { host.first { it != null } } ?: throw IOException(app.getString(R.string.bt_remote_lost))
        }

        private suspend fun send(key: HidReports.Key) {
            val device = currentHost()
            val proxy = hid ?: throw IOException(app.getString(R.string.bt_remote_lost))
            val (down, up) = HidReports.pressReports(key)
            if (!proxy.sendReport(device, down.first, down.second)) throw IOException(app.getString(R.string.bt_remote_lost))
            delay(KEY_HOLD_MS)
            proxy.sendReport(device, up.first, up.second)
        }

        override suspend fun press(key: RemoteKey) {
            val hidKey = HidReports.keyMap[key] ?: return
            sendLock.withLock { send(hidKey) }
        }

        override suspend fun type(text: String) {
            sendLock.withLock {
                for (k in HidReports.keysFor(text)) {
                    send(k)
                    delay(KEY_GAP_MS)
                }
            }
        }

        override fun close() {
            closed = true
            val proxy = hid
            hid = null
            host.value = null
            try { if (everRegistered || registered.value) proxy?.unregisterApp() } catch (_: Exception) {}
            try { if (proxy != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy) } catch (_: Exception) {}
            proxyReady.complete(null)
        }

        companion object {
            /** Time between key-down and key-up, and between typed characters. */
            const val KEY_HOLD_MS = 25L
            const val KEY_GAP_MS = 15L
        }
    }
}
