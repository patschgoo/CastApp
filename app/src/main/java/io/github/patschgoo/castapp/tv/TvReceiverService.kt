package io.github.patschgoo.castapp.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.patschgoo.castapp.BuildConfig
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.discovery.NetworkScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the TV receiver reachable: runs the DLNA renderer server and answers
 * phones searching the network, also while another app is on screen.
 */
class TvReceiverService : Service() {
    @Volatile private var renderer: UpnpRenderer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * The TV wakes from sleep (screen on, screensaver ends): join the search group again and announce
     * ourselves, in case the network went quiet while it slept (no network callback comes then).
     */
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Thread({ refreshNetwork() }, "CastApp-tv-wake").start()
        }
    }
    private var wakeRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TvReceiver.init(this)
        createChannels(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        try {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            multicastLock = wifi?.createMulticastLock("CastApp-tv")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        val r = UpnpRenderer(TvReceiver.deviceName(this), TvReceiver.uuid(this), TvReceiver, BuildConfig.VERSION_NAME)
        r.commands = TvReceiver
        r.guard = TvReceiver.guard
        r.start(PORT, ssdp = true, interfaces = interfaces())
        renderer = r
        _port.value = r.port
        _address.value = NetworkScanner.localNetworks().firstOrNull()?.address?.hostAddress?.let { "$it:${r.port}" }
        _running.value = true
        watchNetwork()
        try {
            val filter = android.content.IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_DREAMING_STOPPED) }
            ContextCompat.registerReceiver(this, wakeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            wakeRegistered = true
        } catch (_: Exception) {
        }
        // Phones that cast can find this receiver as a CastApp device (to join, hand over...).
        io.github.patschgoo.castapp.multi.Peers.acquire(this, "receiver")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RENAME -> renderer?.friendlyName = TvReceiver.deviceName(this)
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // On a phone the receiver only runs when asked for; don't bring it back by itself.
        return if (TvReceiver.isTv(this)) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        if (wakeRegistered) try { unregisterReceiver(wakeReceiver) } catch (_: Exception) {}
        networkCallback?.let { cb ->
            try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        renderer?.stop()
        renderer = null
        try { multicastLock?.release() } catch (_: Exception) {}
        _running.value = false
        _port.value = 0
        io.github.patschgoo.castapp.multi.Peers.release("receiver")
        super.onDestroy()
    }

    private fun interfaces() = NetworkScanner.localNetworks().map { it.iface }.distinct()

    /** Off the main thread: it sends on the network. */
    private fun refreshNetwork() {
        val r = renderer ?: return
        r.networkChanged(interfaces())
        _address.value = NetworkScanner.localNetworks().firstOrNull()?.address?.hostAddress?.let { "$it:${r.port}" }
    }

    /** A new Wi-Fi connection (or address): rejoin the search group there and announce ourselves. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshNetwork()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refreshNetwork()
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (_: Exception) {
        }
    }

    private fun notification(): Notification {
        val tv = TvReceiver.isTv(this)
        val home = if (tv) TvHomeActivity::class.java else io.github.patschgoo.castapp.MainActivity::class.java
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, home).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_RECEIVER)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(getString(if (tv) R.string.tv_ready else R.string.receive_notification_title))
            .setContentText(getString(R.string.tv_ready_hint, TvReceiver.deviceName(this)))
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (!tv) {
            val stop = PendingIntent.getService(
                this, 2, Intent(this, TvReceiverService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(R.drawable.ic_stop, getString(R.string.receive_stop), stop)
        }
        return builder.build()
    }

    companion object {
        /** Fixed port, so a phone that remembers this TV finds it again quickly. */
        const val PORT = 49595
        const val CHANNEL_RECEIVER = "tv_receiver"
        const val CHANNEL_PLAY = "tv_play"
        private const val NOTIFICATION_ID = 40
        private const val ACTION_RENAME = "io.github.patschgoo.castapp.action.TV_RENAME"
        private const val ACTION_STOP = "io.github.patschgoo.castapp.action.RECEIVER_STOP"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _port = MutableStateFlow(0)
        /** The renderer's HTTP port while running, else 0. */
        val port: StateFlow<Int> = _port.asStateFlow()

        private val _address = MutableStateFlow<String?>(null)
        /** "192.168.1.23:49595" while running. */
        val address: StateFlow<String?> = _address.asStateFlow()

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TvReceiverService::class.java))
            } catch (_: Exception) {
                // Not allowed from the background on this Android version; the TV app starts it when opened.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TvReceiverService::class.java))
        }

        fun nameChanged(context: Context) {
            if (_running.value) {
                try {
                    context.startService(Intent(context, TvReceiverService::class.java).setAction(ACTION_RENAME))
                } catch (_: Exception) {
                }
            }
        }

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return // Android 7.x has no notification channels
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RECEIVER, context.getString(R.string.tv_channel_receiver), NotificationManager.IMPORTANCE_LOW),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PLAY, context.getString(R.string.tv_channel_play), NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }
}

/** Starts the receiver when the TV starts (if the user wants that). */
class TvBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (TvReceiver.isTv(context) && TvReceiver.startOnBoot(context)) TvReceiverService.start(context)
    }
}
