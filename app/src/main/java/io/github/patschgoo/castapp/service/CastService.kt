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
import io.github.patschgoo.castapp.settings.PictureMode
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
    private var orientationLock: OrientationLock? = null

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
                io.github.patschgoo.castapp.wear.WearSync.onState(applicationContext, state)
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
            ACTION_SET_VOLUME -> setVolume(intent.getDoubleExtra(EXTRA_LEVEL, -1.0))
            ACTION_PLAY_PAUSE -> (session as? MediaCastSession)?.togglePlay()
            ACTION_REWIND -> (session as? MediaCastSession)?.seekBy(-MediaCastSession.REWIND_MS)
            ACTION_FORWARD -> (session as? MediaCastSession)?.seekBy(MediaCastSession.FORWARD_MS)
            ACTION_SEEK -> (session as? MediaCastSession)?.seekTo(intent.getLongExtra(EXTRA_POSITION, 0))
            ACTION_NEXT -> (session as? MediaCastSession)?.next()
            ACTION_PREVIOUS -> (session as? MediaCastSession)?.previous()
            ACTION_SUBTITLES -> (session as? MediaCastSession)?.toggleSubtitles()
            ACTION_SWAP -> (session as? CastSession)?.device?.takeIf { it.isCastAppTv }?.let { SwapDirection.toPhone(this, it) }
            ACTION_CREATE_SUBTITLES -> (session as? MediaCastSession)?.createSubtitles()
            ACTION_PLAY_INDEX -> (session as? MediaCastSession)?.playAt(intent.getIntExtra(EXTRA_INDEX, 0))
            ACTION_QUALITY -> intent.getStringExtra(EXTRA_QUALITY)?.let { q ->
                when (val s = session) {
                    is MediaCastSession -> s.setQuality(q)
                    is CastSession -> s.setQuality(q)
                    else -> Unit
                }
            }
            ACTION_DARK_SCREEN -> setDarkScreen(intent.getBooleanExtra(EXTRA_ON, overlay == null))
            else -> if (session == null) stopSelfCompletely()
        }
        return START_NOT_STICKY
    }

    /** Sets the TV volume to [level] (0..1) by changing it by the difference to what the TV reported. */
    private fun setVolume(level: Double) {
        if (level !in 0.0..1.0) return
        val current = (CastController.state.value as? CastState.Active)?.volume?.toDouble() ?: 0.5
        session?.changeVolume(level - current)
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
        // With "Phone microphone" on, also the microphone type: Android 11+ silences the
        // microphone of a background app without it. If Android refuses that, mirror without.
        val projectionType = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        val withMic = Build.VERSION.SDK_INT >= 30 && CastApp.get(this).settings.current.microphone &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            try {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID,
                    buildNotification(CastState.Active(device ?: CastDevice("?", "TV", "0.0.0.0"), CastPhase.CONNECTING)),
                    projectionType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
                true
            } catch (e: Exception) {
                false
            }
        if (!withMic && !goForeground(device, projectionType)) return
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
            CastController.message(getString(R.string.svc_no_projection))
            stopIfIdle()
            return
        }

        replaceSession(device)
        val app = CastApp.get(this)
        val singleApp = intent.getBooleanExtra(EXTRA_SINGLE_APP, false)
        val audioOnly = intent.getBooleanExtra(EXTRA_AUDIO_ONLY, false)
        val newSession = CastSession(this, device, projection, app.settings.state, singleApp, audioOnly, ::onSessionEnded)
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
        io.github.patschgoo.castapp.support.DoNotDisturb.onMirroringStarted(this)
        if (!audioOnly && app.settings.current.picture == PictureMode.LANDSCAPE) lockLandscape(true)
    }

    // ---- file casting ------------------------------------------------------------------

    private fun handleStartFile(intent: Intent) {
        val device = deviceFrom(intent)
        val items = intent.getStringExtra(EXTRA_ITEMS_KEY)?.let { pendingItems.remove(it) }
            ?: intent.getStringExtra(EXTRA_ITEMS)?.let { runCatching { MediaItem.listFromJson(it) }.getOrNull() }.orEmpty()
        if (!goForeground(device, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)) return
        if (device == null || items.isEmpty()) {
            stopIfIdle()
            return
        }
        val app = CastApp.get(this)
        // Already playing files on this TV: switch over without connecting again.
        val running = session as? MediaCastSession
        if (running != null && running.device.id == device.id && running.replaceQueue(items, intent.getIntExtra(EXTRA_INDEX, 0))) {
            app.devices.markUsed(device)
            return
        }
        replaceSession(device)
        val newSession = MediaCastSession(this, device, items, intent.getIntExtra(EXTRA_INDEX, 0), app.settings.state, ::onSessionEnded)
        session = newSession
        app.devices.markUsed(device)
        Shortcuts.update(this, device)
        newSession.start()
    }

    // ---- turn apps sideways ---------------------------------------------------------------

    private fun lockLandscape(on: Boolean) {
        if (on) {
            if (orientationLock != null) return
            if (!Settings.canDrawOverlays(this)) {
                CastController.message(getString(R.string.svc_overlay_landscape))
                return
            }
            orientationLock = OrientationLock(this).also { it.show() }
        } else {
            orientationLock?.hide()
            orientationLock = null
        }
    }

    // ---- dark screen ---------------------------------------------------------------------

    private fun setDarkScreen(on: Boolean) {
        val s = session ?: return
        val state = CastController.state.value as? CastState.Active ?: return
        if (on && state.canDarkScreen) {
            if (overlay != null) return
            if (!Settings.canDrawOverlays(this)) {
                CastController.message(getString(R.string.svc_overlay_dark))
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
            CastController.message(getString(R.string.svc_not_allowed))
            stopIfIdle()
            false
        }
    }

    private fun replaceSession(newDevice: CastDevice) {
        overlay?.hide()
        overlay = null
        lockLandscape(false)
        session?.let { it.finish(null, stopReceiver = it.device.host != newDevice.host) }
    }

    private fun onSessionEnded(ended: ActiveCast) {
        if (session === ended) {
            session = null
            overlay?.hide()
            overlay = null
            lockLandscape(false)
            io.github.patschgoo.castapp.support.DoNotDisturb.onMirroringEnded(this)
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
        lockLandscape(false)
        session?.finish(null)
        session = null
        io.github.patschgoo.castapp.support.DoNotDisturb.onMirroringEnded(this)
        requestTileUpdate()
        io.github.patschgoo.castapp.wear.WearSync.onState(applicationContext, CastState.Idle)
        scope.cancel()
        super.onDestroy()
    }

    private fun requestTileUpdate() {
        try {
            TileService.requestListeningState(this, ComponentName(this, CastTileService::class.java))
        } catch (_: Exception) {
        }
        // The home-screen widget shows the same state (a no-op unless what it shows changed).
        io.github.patschgoo.castapp.widget.CastWidget.refresh(this)
    }

    // ---- notifications -------------------------------------------------------------------

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(state: CastState.Active): Notification {
        val file = session as? MediaCastSession
        if (state.kind == CastKind.FILE && file != null) return buildMediaNotification(state, file)

        val text = buildString {
            append(state.phase.label)
            if (state.compatibilityMode) append(getString(R.string.svc_compat_mode))
            if (state.lowLatencyPlayer) append(getString(R.string.svc_lowlat_player))
            state.volume?.let { append(getString(R.string.svc_volume, (it * 100).toInt())) }
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast_connected)
            .setContentTitle(getString(if (state.audioOnly) R.string.notification_title_sound else R.string.notification_title, state.device.name))
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
        } else if (state.device.isCastAppTv && !state.audioOnly && state.kind == CastKind.SCREEN) {
            // Mirroring to a CastApp TV: offer swapping the direction instead of Volume −.
            builder.addAction(R.drawable.ic_cast, getString(R.string.swap_action), action(ACTION_SWAP, 10))
        } else {
            builder.addAction(R.drawable.ic_volume_down, getString(R.string.action_volume_down), action(ACTION_VOLUME_DOWN, 1))
        }
        return builder
            .addAction(R.drawable.ic_volume_up, getString(R.string.action_volume_up), action(ACTION_VOLUME_UP, 2))
            .addAction(R.drawable.ic_stop, getString(R.string.action_stop), action(ACTION_STOP, 3))
            .build()
    }

    /** Media-style notification: shows on the lock screen with play/pause and seeking. */
    private fun buildMediaNotification(state: CastState.Active, file: MediaCastSession): Notification {
        fun platformAction(icon: Int, title: Int, pi: PendingIntent) =
            Notification.Action.Builder(Icon.createWithResource(this, icon), getString(title), pi).build()

        val playing = state.phase == CastPhase.LIVE && !state.paused
        val status = buildString {
            append(
                when {
                    state.phase != CastPhase.LIVE -> state.phase.label
                    state.paused -> getString(R.string.svc_paused_on, state.device.name)
                    else -> getString(R.string.svc_playing_on, state.device.name)
                },
            )
            if (state.queueSize > 1) append(" · ${state.queueIndex + 1}/${state.queueSize}")
        }
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        file.mediaSession?.sessionToken?.let { style.setMediaSession(it) }
        val builder = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(R.drawable.ic_cast_connected)
            .setContentTitle(state.title ?: getString(R.string.svc_video))
            .setContentText(status)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setStyle(style)
        val queue = state.queueSize > 1
        if (queue) builder.addAction(platformAction(R.drawable.ic_previous, R.string.action_previous, action(ACTION_PREVIOUS, 8)))
        else builder.addAction(platformAction(R.drawable.ic_rewind, R.string.action_rewind, action(ACTION_REWIND, 5)))
        builder.addAction(
            platformAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) R.string.action_pause else R.string.action_play,
                action(ACTION_PLAY_PAUSE, 6),
            ),
        )
        if (queue) builder.addAction(platformAction(R.drawable.ic_next, R.string.action_next, action(ACTION_NEXT, 9)))
        else builder.addAction(platformAction(R.drawable.ic_forward, R.string.action_forward, action(ACTION_FORWARD, 7)))
        builder.addAction(platformAction(R.drawable.ic_stop, R.string.action_stop, action(ACTION_STOP, 3)))
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
        /** Sets the volume to [EXTRA_LEVEL] (a Double, 0..1). Used by the automation API. */
        const val ACTION_SET_VOLUME = "io.github.patschgoo.castapp.action.SET_VOLUME"
        const val ACTION_PLAY_PAUSE = "io.github.patschgoo.castapp.action.PLAY_PAUSE"
        const val ACTION_REWIND = "io.github.patschgoo.castapp.action.REWIND"
        const val ACTION_FORWARD = "io.github.patschgoo.castapp.action.FORWARD"
        const val ACTION_SEEK = "io.github.patschgoo.castapp.action.SEEK"
        const val ACTION_DARK_SCREEN = "io.github.patschgoo.castapp.action.DARK_SCREEN"
        const val ACTION_NEXT = "io.github.patschgoo.castapp.action.NEXT"
        const val ACTION_PREVIOUS = "io.github.patschgoo.castapp.action.PREVIOUS"
        const val ACTION_SUBTITLES = "io.github.patschgoo.castapp.action.SUBTITLES"
        /** Mirroring to a CastApp TV: let the TV mirror to this phone instead. */
        const val ACTION_SWAP = "io.github.patschgoo.castapp.action.SWAP"
        const val ACTION_CREATE_SUBTITLES = "io.github.patschgoo.castapp.action.CREATE_SUBTITLES"
        const val ACTION_PLAY_INDEX = "io.github.patschgoo.castapp.action.PLAY_INDEX"
        const val ACTION_QUALITY = "io.github.patschgoo.castapp.action.QUALITY"
        private const val EXTRA_DEVICE = "device"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_SINGLE_APP = "single_app"
        private const val EXTRA_AUDIO_ONLY = "audio_only"
        private const val EXTRA_ITEMS = "items"
        private const val EXTRA_ITEMS_KEY = "items_key"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_LEVEL = "level"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_ON = "on"
        private const val CHANNEL_ID = "casting"
        private const val NOTIFICATION_ID = 1
        private const val VOLUME_STEP = 0.05

        fun start(context: Context, device: CastDevice, resultCode: Int, data: Intent, singleApp: Boolean, audioOnly: Boolean) {
            val intent = Intent(context, CastService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DEVICE, device.toJson().toString())
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_SINGLE_APP, singleApp)
                .putExtra(EXTRA_AUDIO_ONLY, audioOnly)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Casts a list of videos, music, photos or web videos. Read permission for every
         * file on the phone is handed on to the service through the ClipData.
         */
        fun startMedia(context: Context, device: CastDevice, items: List<MediaItem>, startIndex: Int = 0) {
            val intent = Intent(context, CastService::class.java)
                .setAction(ACTION_START_FILE)
                .putExtra(EXTRA_DEVICE, device.toJson().toString())
                .putExtra(EXTRA_INDEX, startIndex)
            val json = MediaItem.listToJson(items)
            if (json.length < 200_000) {
                intent.putExtra(EXTRA_ITEMS, json)
            } else {
                // Long channel lists don't fit into an Intent; the service runs in this process.
                val key = java.util.UUID.randomUUID().toString()
                pendingItems[key] = items
                intent.putExtra(EXTRA_ITEMS_KEY, key)
            }
            val localUris = items.flatMap { listOfNotNull(it.uri, it.subtitleUri) }.filter { it.startsWith("content:") }.map(Uri::parse)
            if (localUris.isNotEmpty()) {
                val clip = android.content.ClipData.newRawUri("media", localUris.first())
                localUris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
                intent.clipData = clip
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
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

        /** Changes the mirroring resolution or the picture quality of what's playing. */
        fun setQuality(context: Context, label: String) = send(context, ACTION_QUALITY) { it.putExtra(EXTRA_QUALITY, label) }

        fun playIndex(context: Context, index: Int) = send(context, ACTION_PLAY_INDEX) { it.putExtra(EXTRA_INDEX, index) }

        private val pendingItems = java.util.concurrent.ConcurrentHashMap<String, List<MediaItem>>()

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
            if (Build.VERSION.SDK_INT < 26) return // Android 7.x (older Fire TV sticks) has no channels
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
