package io.github.patschgoo.castapp.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
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
 * Foreground service that keeps a cast running: screen mirroring (a
 * "mediaProjection" service, as Android requires for screen capture) or a file
 * served to the TV (a "mediaPlayback" service that keeps working with the
 * phone's screen off). Its notification has the controls.
 */
class CastService : Service() {
    private var session: ActiveCast? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: DarkScreenOverlay? = null

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
            ACTION_START -> handleStartScreen(intent)
            ACTION_START_FILE -> handleStartFile(intent)
            ACTION_STOP -> {
                val s = session
                if (s != null) s.finish(null) else stopSelfCompletely()
            }
            ACTION_VOLUME_UP -> session?.changeVolume(+VOLUME_STEP)
            ACTION_VOLUME_DOWN -> session?.changeVolume(-VOLUME_STEP)
            ACTION_TOGGLE_MUTE -> session?.toggleMute()
            ACTION_PLAY_PAUSE -> (session as? FileCastSession)?.togglePlay()
            ACTION_REWIND -> (session as? FileCastSession)?.seekBy(-FileCastSession.REWIND_MS)
            ACTION_FORWARD -> (session as? FileCastSession)?.seekBy(FileCastSession.FORWARD_MS)
            ACTION_SEEK -> (session as? FileCastSession)?.seekTo(intent.getLongExtra(EXTRA_POSITION, 0))
            ACTION_DARK_SCREEN -> setDarkScreen(intent.getBooleanExtra(EXTRA_ON, overlay == null))
            else -> if (session == null) stopSelfCompletely()
        }
        return START_NOT_STICKY
    }

    // ---- screen mirroring ----------------------------------------------------------

    private fun handleStartScreen(intent: Intent) {
        val device = deviceFrom(intent)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        // Must be in the foreground (with the mediaProjection type) before touching the projection.
        if (!goForeground(device, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)) return
        if (device == null || data == null || resultCode != Activity.RESULT_OK) {
            stopIfIdle()
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
            stopIfIdle()
            return
        }

        replaceSession(device)
        val app = CastApp.get(this)
        val singleApp = intent.getBooleanExtra(EXTRA_SINGLE_APP, false)
        val newSession = CastSession(this, device, projection, app.settings.current, singleApp, ::onSessionEnded)
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

    // ---- file casting ------------------------------------------------------------------

    private fun handleStartFile(intent: Intent) {
        val device = deviceFrom(intent)
        val uri: Uri? = intent.data
        if (!goForeground(device, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)) return
        if (device == null || uri == null) {
            stopIfIdle()
            return
        }
        val mime = intent.type ?: contentResolver.getType(uri) ?: "video/mp4"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: FileCastSession.describe(contentResolver, uri).first ?: "Video"

        replaceSession(device)
        val app = CastApp.get(this)
        val newSession = FileCastSession(this, device, uri, normalizeMime(mime), title, app.settings.current.port, ::onSessionEnded)
        session = newSession
        app.devices.markUsed(device)
        Shortcuts.update(this, device)
        newSession.start()
    }

    // ---- dark screen ---------------------------------------------------------------------

    private fun setDarkScreen(on: Boolean) {
        val s = session ?: return
        val state = CastController.state.value as? CastState.Active ?: return
        if (on && state.canDarkScreen) {
            if (overlay != null) return
            if (!Settings.canDrawOverlays(this)) {
                CastController.message("Allow CastApp to \"display over other apps\" to use Dark screen")
                return
            }
            val o = DarkScreenOverlay(this, object : DarkScreenOverlay.Callbacks {
                override fun onVolume(delta: Double) { session?.changeVolume(delta) }
                override fun onStopCasting() { session?.finish(null) }
                override fun onWake() = setDarkScreen(false)
            })
            if (o.show()) {
                overlay = o
                s.setDarkScreen(true)
            }
        } else if (!on) {
            overlay?.hide()
            overlay = null
            s.setDarkScreen(false)
        }
    }

    // ---- shared plumbing -----------------------------------------------------------------

    private fun deviceFrom(intent: Intent): CastDevice? =
        intent.getStringExtra(EXTRA_DEVICE)?.let { runCatching { CastDevice.fromJson(JSONObject(it)) }.getOrNull() }

    private fun goForeground(device: CastDevice?, type: Int): Boolean {
        val placeholder = CastState.Active(device ?: CastDevice("?", "TV", "0.0.0.0"), CastPhase.CONNECTING)
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(placeholder), type)
            true
        } catch (e: Exception) {
            CastController.message("Android did not allow casting to start")
            stopIfIdle()
            false
        }
    }

    private fun replaceSession(newDevice: CastDevice) {
        overlay?.hide()
        overlay = null
        session?.let { it.finish(null, stopReceiver = it.device.host != newDevice.host) }
    }

    private fun onSessionEnded(ended: ActiveCast) {
        if (session === ended) {
            session = null
            overlay?.hide()
            overlay = null
            stopSelfCompletely()
        }
    }

    private fun stopIfIdle() {
        if (session == null) stopSelfCompletely()
    }

    private fun stopSelfCompletely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        requestTileUpdate()
    }

    override fun onDestroy() {
        overlay?.hide()
        overlay = null
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

    // ---- notifications -------------------------------------------------------------------

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(state: CastState.Active): Notification {
        val file = session as? FileCastSession
        if (state.kind == CastKind.FILE && file != null) return buildMediaNotification(state, file)

        val text = buildString {
            append(state.phase.label)
            if (state.compatibilityMode) append(" · compatibility mode")
            state.volume?.let { append(" · volume ").append((it * 100).toInt()).append('%') }
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast_connected)
            .setContentTitle(getString(R.string.notification_title, state.device.name))
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val darkAvailable = state.canDarkScreen && Settings.canDrawOverlays(this)
        if (darkAvailable) {
            // Single-app casting: offer Dark screen instead of Volume − (volume stays in the app).
            builder.addAction(
                R.drawable.ic_dark_screen,
                getString(if (state.darkScreen) R.string.action_wake_screen else R.string.action_dark_screen),
                action(ACTION_DARK_SCREEN, 4) { it.putExtra(EXTRA_ON, !state.darkScreen) },
            )
        } else {
            builder.addAction(R.drawable.ic_volume_down, getString(R.string.action_volume_down), action(ACTION_VOLUME_DOWN, 1))
        }
        return builder
            .addAction(R.drawable.ic_volume_up, getString(R.string.action_volume_up), action(ACTION_VOLUME_UP, 2))
            .addAction(R.drawable.ic_stop, getString(R.string.action_stop), action(ACTION_STOP, 3))
            .build()
    }

    /** Media-style notification: shows on the lock screen with play/pause and seeking. */
    private fun buildMediaNotification(state: CastState.Active, file: FileCastSession): Notification {
        fun platformAction(icon: Int, title: Int, pi: PendingIntent) =
            Notification.Action.Builder(Icon.createWithResource(this, icon), getString(title), pi).build()

        val playing = state.phase == CastPhase.LIVE && !state.paused
        val status = when {
            state.phase != CastPhase.LIVE -> state.phase.label
            state.paused -> "Paused on ${state.device.name}"
            else -> "Playing on ${state.device.name}"
        }
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        file.mediaSession?.sessionToken?.let { style.setMediaSession(it) }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast_connected)
            .setContentTitle(state.title ?: "Video")
            .setContentText(status)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setStyle(style)
            .addAction(platformAction(R.drawable.ic_rewind, R.string.action_rewind, action(ACTION_REWIND, 5)))
            .addAction(
                platformAction(
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (playing) R.string.action_pause else R.string.action_play,
                    action(ACTION_PLAY_PAUSE, 6),
                ),
            )
            .addAction(platformAction(R.drawable.ic_forward, R.string.action_forward, action(ACTION_FORWARD, 7)))
            .addAction(platformAction(R.drawable.ic_stop, R.string.action_stop, action(ACTION_STOP, 3)))
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    private fun action(action: String, requestCode: Int, extras: (Intent) -> Unit = {}): PendingIntent = PendingIntent.getService(
        this, requestCode,
        Intent(this, CastService::class.java).setAction(action).also(extras),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_START = "io.github.patschgoo.castapp.action.START"
        const val ACTION_START_FILE = "io.github.patschgoo.castapp.action.START_FILE"
        const val ACTION_STOP = "io.github.patschgoo.castapp.action.STOP"
        const val ACTION_VOLUME_UP = "io.github.patschgoo.castapp.action.VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "io.github.patschgoo.castapp.action.VOLUME_DOWN"
        const val ACTION_TOGGLE_MUTE = "io.github.patschgoo.castapp.action.TOGGLE_MUTE"
        const val ACTION_PLAY_PAUSE = "io.github.patschgoo.castapp.action.PLAY_PAUSE"
        const val ACTION_REWIND = "io.github.patschgoo.castapp.action.REWIND"
        const val ACTION_FORWARD = "io.github.patschgoo.castapp.action.FORWARD"
        const val ACTION_SEEK = "io.github.patschgoo.castapp.action.SEEK"
        const val ACTION_DARK_SCREEN = "io.github.patschgoo.castapp.action.DARK_SCREEN"
        private const val EXTRA_DEVICE = "device"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_SINGLE_APP = "single_app"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_ON = "on"
        private const val CHANNEL_ID = "casting"
        private const val NOTIFICATION_ID = 1
        private const val VOLUME_STEP = 0.05

        fun start(context: Context, device: CastDevice, resultCode: Int, data: Intent, singleApp: Boolean) {
            val intent = Intent(context, CastService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DEVICE, device.toJson().toString())
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_SINGLE_APP, singleApp)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Casts a video/audio file. The read permission for [uri] is handed on to the service. */
        fun startFile(context: Context, device: CastDevice, uri: Uri, mimeType: String?, title: String?) {
            val intent = Intent(context, CastService::class.java)
                .setAction(ACTION_START_FILE)
                .setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(EXTRA_DEVICE, device.toJson().toString())
                .apply { if (title != null) putExtra(EXTRA_TITLE, title) }
            ContextCompat.startForegroundService(context, intent)
        }

        fun send(context: Context, action: String, extras: (Intent) -> Unit = {}) {
            if (!CastController.isActive && action != ACTION_STOP) return
            try {
                context.startService(Intent(context, CastService::class.java).setAction(action).also(extras))
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) = send(context, ACTION_STOP)

        fun seek(context: Context, positionMs: Long) = send(context, ACTION_SEEK) { it.putExtra(EXTRA_POSITION, positionMs) }

        fun darkScreen(context: Context, on: Boolean) = send(context, ACTION_DARK_SCREEN) { it.putExtra(EXTRA_ON, on) }

        /** The Default Media Receiver decides how to play by MIME type; keep it to types it knows. */
        fun normalizeMime(mime: String): String = when {
            mime == "video/x-matroska" || mime == "video/mkv" -> "video/webm"
            mime == "video/quicktime" || mime == "video/3gpp" -> "video/mp4"
            mime.startsWith("video/") || mime.startsWith("audio/") -> mime
            else -> "video/mp4"
        }

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_casting), NotificationManager.IMPORTANCE_LOW)
            channel.description = context.getString(R.string.channel_casting_description)
            channel.setShowBadge(false)
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(channel)
        }
    }
}
