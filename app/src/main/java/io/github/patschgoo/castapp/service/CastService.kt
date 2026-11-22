package io.github.patschgoo.castapp.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import android.content.ComponentName
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.MainActivity
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.Shortcuts
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.tile.CastTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Foreground service that owns the screen capture while casting. Android
 * requires screen capture to run in a "mediaProjection" foreground service with
 * a visible notification; ours also has Volume and Stop buttons.
 */
class CastService : Service() {
    private var session: CastSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        scope.launch {
            CastController.state.collect { state ->
                if (state is CastState.Active && session != null) {
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(state))
                }
                requestTileUpdate()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                val s = session
                if (s != null) s.finish(null) else stopSelfCompletely()
            }
            ACTION_VOLUME_UP -> session?.changeVolume(+VOLUME_STEP)
            ACTION_VOLUME_DOWN -> session?.changeVolume(-VOLUME_STEP)
            ACTION_TOGGLE_MUTE -> session?.toggleMute()
            else -> if (session == null) stopSelfCompletely()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val device = intent.getStringExtra(EXTRA_DEVICE)?.let { runCatching { CastDevice.fromJson(JSONObject(it)) }.getOrNull() }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        // Must be in the foreground (with the mediaProjection type) before touching the projection.
        val placeholder = CastState.Active(
            device ?: CastDevice("?", "TV", "0.0.0.0"),
            CastPhase.CONNECTING,
        )
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(placeholder),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
            )
        } catch (e: Exception) {
            CastController.message("Android did not allow screen casting to start")
            stopSelfCompletely()
            return
        }

        if (device == null || data == null || resultCode != Activity.RESULT_OK) {
            stopSelfCompletely()
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            null
        }
        if (projection == null) {
            CastController.message("Screen capture permission was not granted")
            stopSelfCompletely()
            return
        }

        session?.let { it.finish(null, stopReceiver = it.device.host != device.host) }
        val app = CastApp.get(this)
        val newSession = CastSession(this, device, projection, app.settings.current) { ended ->
            if (session === ended) {
                session = null
                stopSelfCompletely()
            }
        }
        // Android 14+ requires the callback to be registered before capture starts.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                newSession.finish(null)
            }
        }, mainHandler)
        session = newSession
        app.devices.markUsed(device)
        Shortcuts.update(this, device)
        newSession.start()
    }

    private fun stopSelfCompletely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        requestTileUpdate()
    }

    override fun onDestroy() {
        session?.finish(null)
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun requestTileUpdate() {
        try {
            TileService.requestListeningState(this, ComponentName(this, CastTileService::class.java))
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(state: CastState.Active): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = buildString {
            append(state.phase.label)
            if (state.compatibilityMode) append(" · compatibility mode")
            state.volume?.let { append(" · volume ").append((it * 100).toInt()).append('%') }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast_connected)
            .setContentTitle(getString(R.string.notification_title, state.device.name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_volume_down, getString(R.string.action_volume_down), action(ACTION_VOLUME_DOWN, 1))
            .addAction(R.drawable.ic_volume_up, getString(R.string.action_volume_up), action(ACTION_VOLUME_UP, 2))
            .addAction(R.drawable.ic_stop, getString(R.string.action_stop), action(ACTION_STOP, 3))
            .build()
    }

    private fun action(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode,
        Intent(this, CastService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_START = "io.github.patschgoo.castapp.action.START"
        const val ACTION_STOP = "io.github.patschgoo.castapp.action.STOP"
        const val ACTION_VOLUME_UP = "io.github.patschgoo.castapp.action.VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "io.github.patschgoo.castapp.action.VOLUME_DOWN"
        const val ACTION_TOGGLE_MUTE = "io.github.patschgoo.castapp.action.TOGGLE_MUTE"
        private const val EXTRA_DEVICE = "device"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "casting"
        private const val NOTIFICATION_ID = 1
        private const val VOLUME_STEP = 0.05

        fun start(context: Context, device: CastDevice, resultCode: Int, data: Intent) {
            val intent = Intent(context, CastService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DEVICE, device.toJson().toString())
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun send(context: Context, action: String) {
            if (!CastController.isActive && action != ACTION_STOP) return
            try {
                context.startService(Intent(context, CastService::class.java).setAction(action))
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) = send(context, ACTION_STOP)

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_casting), NotificationManager.IMPORTANCE_LOW)
            channel.description = context.getString(R.string.channel_casting_description)
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
    }
}
