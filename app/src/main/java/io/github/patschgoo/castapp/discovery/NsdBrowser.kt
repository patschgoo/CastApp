package io.github.patschgoo.castapp.discovery

import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.R
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Discovery through Android's own DNS-SD stack (NsdManager). */
class NsdBrowser(context: Context, private val onDevice: (CastDevice) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nsd").apply { isDaemon = true } }
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val lock = Any()

    fun start() {
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        discoveryListener = listener
        try {
            nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            discoveryListener = null
        }
    }

    fun stop() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
        synchronized(lock) { pending.clear() }
    }

    private fun enqueue(info: NsdServiceInfo) {
        synchronized(lock) {
            pending.addLast(info)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    private fun resolveNext() {
        val next = synchronized(lock) {
            val n = pending.removeFirstOrNull()
            if (n == null) resolving = false
            n
        } ?: return
        try {
            if (Build.VERSION.SDK_INT >= 34) resolveModern(next) else resolveLegacy(next)
        } catch (_: Exception) {
            resolveNext()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacy(info: NsdServiceInfo) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = resolveNext()
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                serviceInfo.host?.let { report(serviceInfo, it) }
                resolveNext()
            }
        })
    }

    @RequiresApi(34)
    private fun resolveModern(info: NsdServiceInfo) {
        val callback = object : NsdManager.ServiceInfoCallback {
            private var done = false
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = finish()
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.hostAddresses.firstOrNull { it is Inet4Address } ?: serviceInfo.hostAddresses.firstOrNull()
                if (host != null) {
                    report(serviceInfo, host)
                    finish()
                }
            }
            override fun onServiceLost() = finish()
            override fun onServiceInfoCallbackUnregistered() {}
            fun finish() {
                if (done) return
                done = true
                try { nsd.unregisterServiceInfoCallback(this) } catch (_: Exception) {}
                resolveNext()
            }
        }
        nsd.registerServiceInfoCallback(info, executor, callback)
        // Do not let one silent device block the queue. Runs on the same executor as the callbacks.
        executor.schedule({ callback.finish() }, 4, TimeUnit.SECONDS)
    }

    private fun report(info: NsdServiceInfo, host: InetAddress) {
        val txt = HashMap<String, String>()
        try {
            for ((k, v) in info.attributes) txt[k.lowercase()] = v?.toString(Charsets.UTF_8) ?: ""
        } catch (_: Exception) {
        }
        MdnsCastInfo(info.serviceName ?: Txt.s(R.string.dev_cast_device), host, info.port, txt).toDevice()?.let(onDevice)
    }
}
