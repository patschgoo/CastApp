package io.github.patschgoo.castapp.remote

import android.content.Context
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceKind
import io.github.patschgoo.castapp.discovery.NetworkScanner
import io.github.patschgoo.castapp.dlna.SsdpBrowser
import io.github.patschgoo.castapp.remote.net.AndroidTvTarget
import io.github.patschgoo.castapp.remote.net.LgProtocol
import io.github.patschgoo.castapp.remote.net.LgTarget
import io.github.patschgoo.castapp.remote.net.MdnsServiceBrowser
import io.github.patschgoo.castapp.remote.net.NetTarget
import io.github.patschgoo.castapp.remote.net.NetworkRemoteStore
import io.github.patschgoo.castapp.remote.net.RokuRemoteClient
import io.github.patschgoo.castapp.remote.net.RokuTarget
import io.github.patschgoo.castapp.remote.net.SamsungProtocol
import io.github.patschgoo.castapp.remote.net.SamsungTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * TVs and TV boxes the phone can steer over Wi-Fi: Android TV / Google TV (Android TV Remote
 * protocol v2), Roku (ECP), Samsung Tizen (WebSocket remote) and LG webOS (SSAP).
 * Found by mDNS and SSDP and by checking the TVs the app's device list already knows.
 * TVs used once are remembered so they're listed (and reconnect) right away.
 */
object NetworkRemotes : RemoteProvider {
    override val title = "TVs on the network"

    private const val ATV_SERVICE = "_androidtvremote2._tcp.local"
    private const val SAMSUNG_SSDP = "urn:samsung.com:device:RemoteControlReceiver:1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val found = ConcurrentHashMap<String, NetTarget>()
    private val saved = ConcurrentHashMap<String, NetTarget>()
    private val list = MutableStateFlow<List<RemoteTarget>>(emptyList())
    private val probed = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var loaded = false

    private var users = 0
    private var devicesJob: Job? = null
    private var mdns: MdnsServiceBrowser? = null
    private val ssdp = ArrayList<SsdpBrowser>()
    private var appContext: Context? = null

    override fun targets(context: Context): StateFlow<List<RemoteTarget>> {
        load(context)
        return list.asStateFlow()
    }

    private fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            for (o in NetworkRemoteStore.get(context).savedTargets()) NetTarget.fromJson(o)?.let { saved[it.id] = it }
            loaded = true
        }
        publish()
    }

    @Synchronized
    override fun startDiscovery(context: Context) {
        load(context)
        users++
        if (users > 1) return
        val app = context.applicationContext
        appContext = app
        probed.clear()
        val repository = CastApp.get(app).devices
        repository.startDiscovery()
        devicesJob = scope.launch {
            repository.devices.collect { entries -> entries.filter { it.online }.forEach { check(it.device) } }
        }
        val ifaces = NetworkScanner.localNetworks().map { it.iface }.distinct()
        mdns = MdnsServiceBrowser(ATV_SERVICE) { s ->
            val host = s.host.hostAddress ?: return@MdnsServiceBrowser
            add(AndroidTvTarget(host, s.name.ifBlank { "Android TV" }, if (s.port > 0) s.port else 6466))
        }.also { it.start(ifaces) }
        ssdp += SsdpBrowser(LgProtocol.SSDP_TARGET) { location, d ->
            hostOf(location)?.let { add(LgTarget(it, d.friendlyName)) }
        }
        ssdp += SsdpBrowser(SAMSUNG_SSDP) { location, d ->
            hostOf(location)?.let { probeSamsung(it, d.friendlyName) }
        }
        ssdp += SsdpBrowser(RokuRemoteClient.SSDP_TARGET) { location, _ ->
            val uri = try { URI(location) } catch (_: Exception) { return@SsdpBrowser }
            uri.host?.let { probeRoku(it, if (uri.port > 0) uri.port else 8060) }
        }
        ssdp.forEach { it.start(ifaces) }
    }

    @Synchronized
    override fun stopDiscovery() {
        if (users == 0) return
        users--
        if (users > 0) return
        devicesJob?.cancel(); devicesJob = null
        mdns?.stop(); mdns = null
        ssdp.forEach { it.stop() }
        ssdp.clear()
        appContext?.let { CastApp.get(it).devices.stopDiscovery() }
    }

    /** A TV from the app's device list: see whether it also takes remote control. */
    private fun check(device: CastDevice) {
        val host = device.host
        val model = device.model.orEmpty()
        when {
            device.kind == DeviceKind.ROKU -> probeRoku(host, device.port)
            device.kind == DeviceKind.CAST && device.video && !device.group -> once("atv:$host") {
                if (open(host, 6466)) add(AndroidTvTarget(host, device.name))
            }
            device.kind == DeviceKind.DLNA && model.contains("Samsung", ignoreCase = true) -> probeSamsung(host, device.name)
            device.kind == DeviceKind.DLNA && (model.startsWith("LG", ignoreCase = true) || model.contains("webOS", ignoreCase = true)) ->
                once("lg:$host") { if (open(host, LgProtocol.PLAIN_PORT) || open(host, LgProtocol.SECURE_PORT)) add(LgTarget(host, device.name)) }
        }
    }

    private fun probeRoku(host: String, port: Int) = once("roku:$host") {
        val info = RokuRemoteClient(host, port).info() ?: return@once
        add(RokuTarget(host, info.name, port, info.isTv))
    }

    private fun probeSamsung(host: String, fallbackName: String) = once("samsung:$host") {
        val info = SamsungProtocol.fetchInfo(host) ?: return@once
        add(SamsungTarget(host, info.name.removePrefix("[TV] ").ifBlank { fallbackName }, secure = info.tokenAuth))
    }

    private fun once(key: String, block: suspend () -> Unit) {
        if (!probed.add(key)) return
        scope.launch {
            try { block() } catch (_: Exception) { probed.remove(key) }
        }
    }

    private fun open(host: String, port: Int): Boolean = try {
        NetworkScanner.isPortOpen(InetAddress.getByName(host), port, 1500)
    } catch (_: Exception) {
        false
    }

    private fun hostOf(location: String): String? = try { URI(location).host } catch (_: Exception) { null }

    private fun add(target: NetTarget) {
        found[target.id] = target
        publish()
    }

    /** Called after a successful connection: keep the TV in the list for next time. */
    internal fun remember(context: Context, target: NetTarget) {
        NetworkRemoteStore.get(context).remember(target.toJson())
        saved[target.id] = target
        publish()
    }

    private fun publish() {
        val all = LinkedHashMap<String, NetTarget>()
        found.values.sortedBy { it.name.lowercase() }.forEach { all[it.id] = it }
        saved.values.sortedBy { it.name.lowercase() }.forEach { all.putIfAbsent(it.id, it) }
        list.value = all.values.toList()
    }
}
