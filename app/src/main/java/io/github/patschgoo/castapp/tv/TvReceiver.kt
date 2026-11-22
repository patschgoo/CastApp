package io.github.patschgoo.castapp.tv

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.patschgoo.castapp.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * The TV ("receiver") side of CastApp: bridges the UPnP renderer, which runs on
 * network threads, and the player screen, which runs on the main thread.
 */
object TvReceiver : RendererBackend, CastAppCommands {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context

    /** The player screen while it is open (weakly held, so a closed screen can't leak). */
    @Volatile private var playerRef: java.lang.ref.WeakReference<TvPlayerActivity>? = null
    var player: TvPlayerActivity?
        get() = playerRef?.get()
        set(value) {
            playerRef = value?.let { java.lang.ref.WeakReference(it) }
        }

    @Volatile var media: TvMedia? = null
        private set

    private val _state = MutableStateFlow(TvSnapshot())
    val state: StateFlow<TvSnapshot> = _state.asStateFlow()
    override val snapshot: TvSnapshot get() = _state.value

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    /**
     * Who may cast here. TVs default to "anyone on the network" (as before), phones that
     * "receive on this phone" to "ask first". Call [init] before.
     */
    val guard: ReceiverGuard by lazy {
        ReceiverGuard(
            PrefsGuardStore(appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)),
            if (isTv(appContext)) ProtectionMode.ANYONE else ProtectionMode.ASK,
        ).also { g -> g.onAttention = { ReceiverPrompt.attention(appContext, g) } }
    }

    /** True on Fire TV, Android TV and Google TV devices. */
    fun isTv(context: Context): Boolean {
        val pm = context.packageManager
        val ui = context.getSystemService(android.app.UiModeManager::class.java)
        return ui?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("amazon.hardware.fire_tv")
    }

    /** The name phones see: the TV's own name (e.g. "Living Room Fire TV") or its model. */
    fun deviceName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val system = try {
            Settings.Global.getString(context.contentResolver, "device_name")
        } catch (_: Exception) {
            null
        }
        return system?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    fun setDeviceName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_NAME, name.trim()) }
    }

    fun uuid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_UUID, null) ?: UUID.randomUUID().toString().also { prefs.edit { putString(KEY_UUID, it) } }
    }

    fun startOnBoot(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BOOT, true)

    fun setStartOnBoot(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_BOOT, on) }
    }

    // ---- RendererBackend (network threads) -------------------------------------------------------

    override fun setMedia(media: TvMedia) {
        this.media = media
        _state.value = _state.value.copy(state = TransportState.STOPPED, positionMs = 0, durationMs = 0)
        main.post { player?.load(media, autoPlay = false) }
    }

    override fun play() {
        val m = media ?: return
        if (_state.value.state != TransportState.PAUSED) _state.value = _state.value.copy(state = TransportState.TRANSITIONING)
        main.post {
            val p = player
            if (p != null && p.showing == m) p.resume() else openPlayer(m)
        }
    }

    override fun pause() {
        main.post { player?.pauseNow() }
    }

    override fun stop() {
        _state.value = _state.value.copy(state = TransportState.STOPPED)
        main.post {
            // Let go of the closing screen at once: a phone trying its next way sends Stop, SetAVTransportURI
            // and Play in a row, and those must open a new player, not go to the one that is finishing.
            val p = player
            player = null
            p?.stopAndClose()
        }
    }

    override fun seek(positionMs: Long) {
        main.post { player?.seekTo(positionMs) }
    }

    override fun setVolume(percent: Int) {
        _state.value = _state.value.copy(volume = percent, muted = false)
        main.post { player?.applyVolume() }
    }

    override fun setMuted(muted: Boolean) {
        _state.value = _state.value.copy(muted = muted)
        main.post { player?.applyVolume() }
    }

    // ---- reported by the player screen (main thread) --------------------------------------------

    fun report(state: TransportState, positionMs: Long, durationMs: Long) {
        _state.value = _state.value.copy(state = state, positionMs = positionMs, durationMs = durationMs)
    }

    /** The viewer left the player with Back: tell the phone playback stopped. */
    fun closedOnTv() {
        _state.value = _state.value.copy(state = TransportState.STOPPED)
    }

    private fun openPlayer(media: TvMedia) {
        val intent = Intent(appContext, TvPlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            appContext.startActivity(intent)
        } catch (_: Exception) {
        }
        // Android 10+ may not let a background app open a screen; a full-screen notification can.
        main.postDelayed({
            if (player == null && this.media == media) showStartNotification(intent)
        }, 1500)
    }

    // ---- CastAppCommands: a phone asks this device to mirror its screen back ("swap") -------------

    override fun mirrorTo(descriptionUrl: String): String? {
        val xml = io.github.patschgoo.castapp.dlna.Dlna.fetch(descriptionUrl, 4000)
            ?: return appContext.getString(R.string.swap_error_unreachable)
        val description = io.github.patschgoo.castapp.dlna.Dlna.parseDescription(xml, descriptionUrl)
        val target = io.github.patschgoo.castapp.dlna.SsdpBrowser.rendererDevice(descriptionUrl, description)
            ?: return appContext.getString(R.string.swap_error_not_receiver)
        // Whatever plays here now would be captured; close it first.
        stop()
        val intent = io.github.patschgoo.castapp.ProjectionActivity.intent(
            appContext, target, io.github.patschgoo.castapp.ProjectionActivity.MODE_SCREEN,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        main.postDelayed({
            try {
                appContext.startActivity(intent)
            } catch (_: Exception) {
            }
            // Android 10+ may block that from the background; the notification lets the viewer start it.
            if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(appContext)) {
                showStartNotification(
                    intent,
                    appContext.getString(R.string.swap_tv_notification_title, target.name),
                    appContext.getString(R.string.swap_tv_notification_text),
                )
            }
        }, 600)
        return null
    }

    override fun stopMirror() {
        io.github.patschgoo.castapp.service.CastService.stop(appContext)
    }

    // ---- CastAppCommands: what this TV can do, and YouTube links ------------------------------------

    /** Phones read this before mirroring: HEVC only goes to TVs that decode it. */
    override fun capabilities(): Map<String, Any> = mapOf(
        "hevc" to io.github.patschgoo.castapp.capture.VideoCodecs.hasHevcDecoder(),
        "youtube" to true,
    )

    /** Opens a (checked, canonical) YouTube link in the TV's YouTube app, or whatever handles such links. */
    override fun openYouTube(url: String): String? {
        val uri = url.toUri()
        val pm = appContext.packageManager
        val base = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // YouTube for Android TV / Google TV, YouTube for Fire TV, then the phone app (on a phone receiver).
        val intent = YOUTUBE_PACKAGES.asSequence()
            .map { pkg -> Intent(base).setPackage(pkg) }
            .firstOrNull { it.resolveActivity(pm) != null }
            ?: base.takeIf { it.resolveActivity(pm) != null }
            ?: return appContext.getString(R.string.yt_tv_no_app)
        // What plays here now would keep playing underneath; close it first.
        stop()
        main.post {
            try {
                appContext.startActivity(intent)
            } catch (_: Exception) {
            }
            // Android 10+ may block that from the background; the notification lets the viewer open it.
            if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(appContext)) {
                showStartNotification(
                    intent,
                    appContext.getString(R.string.yt_tv_notification_title),
                    appContext.getString(R.string.yt_tv_notification_text),
                )
            }
        }
        return null
    }

    private val YOUTUBE_PACKAGES = listOf(
        "com.google.android.youtube.tv",
        "com.google.android.youtube.googletv",
        "com.amazon.firetv.youtube",
        "com.google.android.youtube",
    )

    private fun showStartNotification(
        intent: Intent,
        title: String = appContext.getString(R.string.tv_incoming, media?.title ?: ""),
        text: String = appContext.getString(R.string.tv_incoming_hint),
    ) {
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
        TvReceiverService.createChannels(appContext)
        val pending = PendingIntent.getActivity(appContext, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(appContext, TvReceiverService.CHANNEL_PLAY)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        try { nm.notify(NOTIFICATION_INCOMING, n) } catch (_: SecurityException) {}
    }

    fun clearStartNotification() {
        appContext.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_INCOMING)
    }

    private const val PREFS = "tv_receiver"
    private const val KEY_NAME = "name"
    private const val KEY_UUID = "uuid"
    private const val KEY_BOOT = "start_on_boot"
    private const val NOTIFICATION_INCOMING = 41
}
