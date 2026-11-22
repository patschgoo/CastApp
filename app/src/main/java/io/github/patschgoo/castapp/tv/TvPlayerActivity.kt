package io.github.patschgoo.castapp.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.net.toUri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.net.HttpURLConnection
import java.net.URL

/**
 * Full-screen player of the TV receiver mode: videos, music, photos and live
 * streams sent from a phone. Screen mirroring from CastApp is played with almost
 * no buffer and gently sped up when it falls behind, to keep the delay short.
 */
@OptIn(UnstableApi::class)
class TvPlayerActivity : ComponentActivity() {
    /** What this screen is showing now. */
    @Volatile var showing: TvMedia? = null
        private set

    private var exo: ExoPlayer? = null
    private var exoLowLatency = false
    private lateinit var playerView: PlayerView
    private lateinit var photoView: ImageView
    private lateinit var infoView: TextView
    private lateinit var swapButton: android.widget.Button
    private val handler = Handler(Looper.getMainLooper())
    private var photoState = TransportState.STOPPED
    private var photoLoader: Thread? = null
    private var failed = false
    private var closing = false
    private var speed = 1f

    private val ticker = object : Runnable {
        override fun run() {
            reportState()
            catchUp()
            swapButton.visibility = if (io.github.patschgoo.castapp.service.SwapDirection.partner != null) View.VISIBLE else View.GONE
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = true
            controllerAutoShow = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            keepScreenOn = true
        }
        photoView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        infoView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            visibility = View.GONE
        }
        root.addView(playerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(photoView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(infoView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        // After "swap" (a TV mirroring to this phone): go back to the phone mirroring to the TV.
        swapButton = android.widget.Button(this).apply {
            text = getString(io.github.patschgoo.castapp.R.string.swap_back)
            visibility = View.GONE
            setOnClickListener {
                closing = true
                TvReceiver.closedOnTv()
                exo?.stop()
                io.github.patschgoo.castapp.service.SwapDirection.backToTv(this@TvPlayerActivity)
                finish()
            }
        }
        val margin = (16 * resources.displayMetrics.density).toInt()
        root.addView(
            swapButton,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                .apply { setMargins(margin, margin * 2, margin, margin) },
        )
        setContentView(root)

        onBackPressedDispatcher.addCallback(this) {
            // Back ends the cast; the phone sees it stopped.
            closing = true
            TvReceiver.closedOnTv()
            exo?.stop()
            io.github.patschgoo.castapp.service.SwapDirection.ended(this@TvPlayerActivity)
            finish()
        }

        TvReceiver.init(this)
        TvReceiver.player = this
        TvReceiver.clearStartNotification()
        val media = TvReceiver.media
        if (media == null) {
            finish()
            return
        }
        load(media, autoPlay = true)
        handler.post(ticker)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val media = TvReceiver.media ?: return
        if (media != showing) load(media, autoPlay = true) else resume()
    }

    override fun onStop() {
        super.onStop()
        // Not on screen any more (Home button): don't play on in the background.
        if (!isFinishing) exo?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        photoLoader?.interrupt()
        exo?.release()
        exo = null
        if (TvReceiver.player === this) {
            TvReceiver.player = null
            if (!closing) TvReceiver.closedOnTv()
        }
        super.onDestroy()
    }

    // ---- commands from the phone ----------------------------------------------------------------

    fun load(media: TvMedia, autoPlay: Boolean) {
        showing = media
        failed = false
        infoView.visibility = View.GONE
        if (media.kind == TvMediaKind.PHOTO) {
            exo?.stop()
            playerView.visibility = View.GONE
            photoView.visibility = View.VISIBLE
            loadPhoto(media.uri)
            return
        }
        photoView.visibility = View.GONE
        photoView.setImageDrawable(null)
        playerView.visibility = View.VISIBLE
        val player = playerFor(media.lowLatency)
        val item = MediaItem.Builder().setUri(media.uri).setMediaId(media.uri)
        val path = media.uri.substringBefore('?').lowercase()
        when {
            media.mimeType?.contains("mpegurl", ignoreCase = true) == true || path.endsWith(".m3u8") -> item.setMimeType(MimeTypes.APPLICATION_M3U8)
            media.mimeType?.contains("dash", ignoreCase = true) == true || path.endsWith(".mpd") -> item.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        media.subtitleUri?.let { sub ->
            val srt = sub.substringBefore('?').lowercase().endsWith(".srt") || sub.contains("srt", ignoreCase = true)
            item.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(sub.toUri())
                        .setMimeType(if (srt) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }
        if (media.kind == TvMediaKind.AUDIO) {
            infoView.text = getString(io.github.patschgoo.castapp.R.string.tv_now_playing_audio, media.title)
            infoView.visibility = View.VISIBLE
        }
        speed = 1f
        player.playbackParameters = PlaybackParameters.DEFAULT
        player.setMediaItem(item.build())
        player.prepare()
        player.playWhenReady = autoPlay
        applyVolume()
    }

    fun resume() {
        if (showing?.kind == TvMediaKind.PHOTO) return
        val p = exo ?: return
        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
            showing?.let { load(it, autoPlay = true) }
        } else {
            p.play()
        }
    }

    fun pauseNow() {
        exo?.pause()
    }

    fun seekTo(positionMs: Long) {
        exo?.seekTo(positionMs)
    }

    fun applyVolume() {
        val s = TvReceiver.snapshot
        exo?.volume = if (s.muted) 0f else s.volume / 100f
    }

    fun stopAndClose() {
        closing = true
        exo?.stop()
        finish()
    }

    // ---- player ----------------------------------------------------------------------------------

    private fun playerFor(lowLatency: Boolean): ExoPlayer {
        exo?.let { if (exoLowLatency == lowLatency) return it }
        exo?.release()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("CastApp TV")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(if (lowLatency) 0 else 20_000)
        val loadControl = if (lowLatency) {
            // Start as soon as there's a frame and never hold more than a moment.
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(300, 1500, 0, 100)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        } else {
            DefaultLoadControl()
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failed = true
                infoView.text = getString(io.github.patschgoo.castapp.R.string.tv_cant_play, error.errorCodeName)
                infoView.visibility = View.VISIBLE
                reportState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) = reportState()
            override fun onIsPlayingChanged(isPlaying: Boolean) = reportState()
        })
        playerView.player = player
        exo = player
        exoLowLatency = lowLatency
        return player
    }

    private fun reportState() {
        val media = showing
        if (media == null) {
            TvReceiver.report(TransportState.NO_MEDIA_PRESENT, 0, 0)
            return
        }
        if (media.kind == TvMediaKind.PHOTO) {
            TvReceiver.report(photoState, 0, 0)
            return
        }
        val p = exo ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 && !media.live } ?: 0L
        val state = when {
            failed -> TransportState.STOPPED
            p.playbackState == Player.STATE_ENDED -> TransportState.STOPPED
            p.playbackState == Player.STATE_IDLE -> TransportState.STOPPED
            p.playbackState == Player.STATE_BUFFERING && p.playWhenReady -> TransportState.TRANSITIONING
            p.playWhenReady -> TransportState.PLAYING
            else -> TransportState.PAUSED
        }
        val position = if (p.playbackState == Player.STATE_ENDED && duration > 0) duration else p.currentPosition.coerceAtLeast(0)
        TvReceiver.report(state, position, duration)
    }

    /** Mirroring: if frames pile up (Wi-Fi hiccup), play a little faster until we're live again. */
    private fun catchUp() {
        val p = exo ?: return
        if (!exoLowLatency || !p.isPlaying) return
        val ahead = p.bufferedPosition - p.currentPosition
        val target = when {
            ahead > 2000 -> 1.5f
            ahead > 700 -> 1.15f
            ahead > 300 -> 1.05f
            else -> 1f
        }
        if (target != speed) {
            speed = target
            p.playbackParameters = PlaybackParameters(target)
        }
    }

    private fun loadPhoto(url: String) {
        photoLoader?.interrupt()
        photoState = TransportState.TRANSITIONING
        val metrics = resources.displayMetrics
        photoLoader = Thread({
            val bitmap = try { downloadPhoto(url, metrics.widthPixels, metrics.heightPixels) } catch (_: Exception) { null }
            runOnUiThread {
                if (showing?.uri != url) return@runOnUiThread
                if (bitmap != null) {
                    photoView.setImageBitmap(bitmap)
                    photoState = TransportState.PLAYING
                } else {
                    infoView.text = getString(io.github.patschgoo.castapp.R.string.tv_cant_show_photo)
                    infoView.visibility = View.VISIBLE
                    photoState = TransportState.STOPPED
                }
                reportState()
            }
        }, "tv-photo").apply { isDaemon = true; start() }
    }

    private fun downloadPhoto(url: String, maxW: Int, maxH: Int): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        val bytes = try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
