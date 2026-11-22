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
import io.github.patschgoo.castapp.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * The TV ("receiver") side of CastApp: bridges the UPnP renderer, which runs on
 * network threads, and the player screen, which runs on the main thread.
 */
object TvReceiver : RendererBackend {
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
        main.post { player?.stopAndClose() }
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

    private fun showStartNotification(intent: Intent) {
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
        TvReceiverService.createChannels(appContext)
        val pending = PendingIntent.getActivity(appContext, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(appContext, TvReceiverService.CHANNEL_PLAY)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(appContext.getString(R.string.tv_incoming, media?.title ?: ""))
            .setContentText(appContext.getString(R.string.tv_incoming_hint))
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
