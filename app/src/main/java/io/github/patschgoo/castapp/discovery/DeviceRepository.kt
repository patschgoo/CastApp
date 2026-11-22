package io.github.patschgoo.castapp.discovery

import android.content.Context
import androidx.core.content.edit
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/** A device as shown in the list. */
data class DeviceEntry(
    val device: CastDevice,
    val saved: Boolean,
    val online: Boolean,
)

/**
 * Combines every discovery method (Android NSD, our own mDNS browser, a full
 * subnet scan and manually added IPs) with the list of remembered devices,
 * so a TV you used once stays one tap away even if discovery fails later.
 */
class DeviceRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("devices", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val discovered = ConcurrentHashMap<String, CastDevice>()
    private val reachable = ConcurrentHashMap.newKeySet<String>()
    private val saved = LinkedHashMap<String, CastDevice>()

    private val _devices = MutableStateFlow<List<DeviceEntry>>(emptyList())
    val devices: StateFlow<List<DeviceEntry>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    /** True while a full network scan is running. */
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _audioMode = MutableStateFlow(prefs.getBoolean(KEY_AUDIO_MODE, false))
    /** Audio mode lists speakers and speaker groups too and casts sound only. */
    val audioMode: StateFlow<Boolean> = _audioMode.asStateFlow()

    fun setAudioMode(on: Boolean) {
        _audioMode.value = on
        prefs.edit { putBoolean(KEY_AUDIO_MODE, on) }
    }

    private val _searching = MutableStateFlow(false)
    /** True while discovery is active (the device list is on screen). */
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var mdns: MdnsBrowser? = null
    private var nsd: NsdBrowser? = null
    private var ssdp: io.github.patschgoo.castapp.dlna.SsdpBrowser? = null
    private var roku: io.github.patschgoo.castapp.dlna.SsdpBrowser? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var autoScanJob: Job? = null
    private var scanJob: Job? = null
    private var discoveryUsers = 0

    init {
        loadSaved()
        publish()
    }

    // ---- discovery lifecycle ------------------------------------------------

    @Synchronized
    fun startDiscovery() {
        discoveryUsers++
        if (discoveryUsers > 1) return
        _searching.value = true
        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("CastApp-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        val onDevice: (CastDevice) -> Unit = { found(it) }
        val ifaces = NetworkScanner.localNetworks().map { it.iface }.distinct()
        mdns = MdnsBrowser(onDevice).also { it.start(ifaces) }
        nsd = NsdBrowser(appContext, onDevice).also { it.start() }
        // When this phone receives casts itself, don't list it as a TV.
        val ownUdn = "uuid:" + io.github.patschgoo.castapp.tv.TvReceiver.uuid(appContext)
        ssdp = io.github.patschgoo.castapp.dlna.SsdpBrowser { location, d ->
            if (d.udn == ownUdn) return@SsdpBrowser
            io.github.patschgoo.castapp.dlna.SsdpBrowser.rendererDevice(location, d)?.let(onDevice)
        }.also { it.start(ifaces) }
        roku = io.github.patschgoo.castapp.dlna.SsdpBrowser(io.github.patschgoo.castapp.render.RokuRenderer.SSDP_TARGET) { location, _ ->
            io.github.patschgoo.castapp.render.RokuRenderer.deviceFrom(location)?.let(onDevice)
        }.also { it.start(ifaces) }
        checkSavedDevices()
        // If the normal discovery finds nothing quickly, scan the network automatically.
        autoScanJob = scope.launch {
            delay(4000)
            if (discovered.isEmpty() && reachable.isEmpty()) scanNetwork()
        }
    }

    @Synchronized
    fun stopDiscovery() {
        if (discoveryUsers == 0) return
        discoveryUsers--
        if (discoveryUsers > 0) return
        _searching.value = false
        autoScanJob?.cancel()
        mdns?.stop(); mdns = null
        nsd?.stop(); nsd = null
        ssdp?.stop(); ssdp = null
        roku?.stop(); roku = null
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    /** Re-queries the network and re-checks remembered devices. */
    fun refresh() {
        mdns?.queryNow()
        ssdp?.searchAgain()
        roku?.searchAgain()
        checkSavedDevices()
        scanNetwork()
    }

    /** Probes every address on the local network for Cast devices. */
    fun scanNetwork() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            _scanning.value = true
            try {
                for (net in NetworkScanner.localNetworks().distinctBy { it.address }) {
                    NetworkScanner.scan(net) { found(it) }
                }
            } finally {
                _scanning.value = false
            }
        }
    }

    private fun checkSavedDevices() {
        val list = synchronized(saved) { saved.values.toList() }
        for (device in list) {
            scope.launch {
                val ok = try {
                    NetworkScanner.isPortOpen(InetAddress.getByName(device.host), device.port, 1200)
                } catch (_: Exception) { false }
                if (ok) reachable.add(device.id) else reachable.remove(device.id)
                publish()
            }
        }
    }

    /** Adds a device by IP address (or host name). Returns null if no Cast device answers there. */
    suspend fun addManual(address: String): CastDevice? {
        val host = address.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
        val ip = host.substringBefore(':')
        val port = host.substringAfter(':', "").toIntOrNull() ?: 8009
        val resolved = try { InetAddress.getByName(ip).hostAddress } catch (_: Exception) { null } ?: return null
        val device = NetworkScanner.identify(resolved, port) ?: return null
        found(device)
        save(device)
        return device
    }

    private fun sameEndpoint(a: CastDevice, b: CastDevice) = a.host == b.host && a.port == b.port && a.kind == b.kind

    fun found(device: CastDevice) {
        // A device found by IP only may already be known under its real id.
        val existing = discovered.values.firstOrNull { sameEndpoint(it, device) && it.id != device.id }
        if (existing != null && device.id.startsWith("host:")) {
            discovered[existing.id] = existing.copy(status = device.status ?: existing.status)
        } else {
            if (existing != null && existing.id.startsWith("host:")) discovered.remove(existing.id)
            discovered[device.id] = device
        }
        reachable.add(device.id)
        // Keep remembered devices up to date (e.g. the TV got a new IP address).
        synchronized(saved) {
            val match = saved[device.id] ?: saved.values.firstOrNull { sameEndpoint(it, device) && it.id.startsWith("host:") }
            if (match != null && (match.host != device.host || match.port != device.port || match.name != device.name || match.id != device.id)) {
                saved.remove(match.id)
                saved[device.id] = device.copy(status = null, mac = device.mac ?: match.mac)
                persistSaved()
                if (prefs.getString(KEY_LAST, null) == match.id) prefs.edit { putString(KEY_LAST, device.id) }
            }
        }
        publish()
    }

    // ---- remembered devices -------------------------------------------------

    fun save(device: CastDevice) {
        synchronized(saved) {
            val mac = device.mac ?: saved[device.id]?.mac
            saved.remove(device.id)
            saved.values.removeAll { sameEndpoint(it, device) && it.id.startsWith("host:") }
            saved[device.id] = device.copy(status = null, mac = mac)
            persistSaved()
        }
        publish()
    }

    /** Sets (or clears, with null) the MAC address used to wake [device]; remembers the device unless [onlyIfSaved]. */
    fun setMac(device: CastDevice, mac: String?, onlyIfSaved: Boolean = false) {
        synchronized(saved) {
            val base = saved[device.id] ?: if (onlyIfSaved) return else device
            saved[device.id] = base.copy(status = null, mac = mac)
            persistSaved()
        }
        publish()
    }

    fun forget(id: String) {
        synchronized(saved) {
            saved.remove(id)
            persistSaved()
        }
        if (prefs.getString(KEY_LAST, null) == id) prefs.edit { remove(KEY_LAST) }
        publish()
    }

    /** Remembers [device] as the one to use for quick casting (tile, shortcut). */
    fun markUsed(device: CastDevice) {
        save(device)
        prefs.edit { putString(KEY_LAST, device.id) }
    }

    fun lastUsed(): CastDevice? {
        val id = prefs.getString(KEY_LAST, null) ?: return null
        return find(id)
    }

    fun find(id: String): CastDevice? = discovered[id] ?: synchronized(saved) { saved[id] }

    private fun loadSaved() {
        val raw = prefs.getString(KEY_SAVED, null) ?: return
        try {
            val arr = JSONArray(raw)
            synchronized(saved) {
                for (i in 0 until arr.length()) {
                    CastDevice.fromJson(arr.getJSONObject(i))?.let { saved[it.id] = it }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun persistSaved() {
        val arr = JSONArray()
        saved.values.forEach { arr.put(it.toJson()) }
        prefs.edit { putString(KEY_SAVED, arr.toString()) }
    }

    private fun publish() {
        val savedCopy = synchronized(saved) { saved.toMap() }
        val entries = LinkedHashMap<String, DeviceEntry>()
        for ((id, d) in discovered) entries[id] = DeviceEntry(d.copy(mac = d.mac ?: savedCopy[id]?.mac), savedCopy.containsKey(id), true)
        for ((id, d) in savedCopy) if (id !in entries) entries[id] = DeviceEntry(d, true, id in reachable)
        // Many TVs answer in several ways (Chromecast built in, DLNA, AirPlay, Roku); show each once,
        // the best way first: Cast, DLNA, Roku, AirPlay. CastApp TV receivers are always listed.
        val rank = mapOf(DeviceKind.CAST to 0, DeviceKind.DLNA to 1, DeviceKind.ROKU to 2, DeviceKind.AIRPLAY to 3)
        val best = entries.values.filter { it.device.video && !it.device.isCastAppTv }
            .groupBy { it.device.host }
            .mapValues { (_, list) -> list.minOf { rank.getValue(it.device.kind) } }
        entries.values.removeAll { e ->
            e.device.video && !e.device.isCastAppTv && (best[e.device.host] ?: Int.MAX_VALUE) < rank.getValue(e.device.kind)
        }
        val lastId = prefs.getString(KEY_LAST, null)
        _devices.value = entries.values.sortedWith(
            compareByDescending<DeviceEntry> { it.online }
                .thenByDescending { it.device.id == lastId }
                .thenByDescending { it.saved }
                .thenBy { it.device.name.lowercase() },
        )
    }

    companion object {
        private const val KEY_SAVED = "saved_devices"
        private const val KEY_LAST = "last_device"
        private const val KEY_AUDIO_MODE = "audio_mode"
    }
}
