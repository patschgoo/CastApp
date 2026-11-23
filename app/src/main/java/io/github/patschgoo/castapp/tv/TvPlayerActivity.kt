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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
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
    /** The tracks of the media now loaded were judged ([checkTracks]). */
    private var tracksChecked = false
    /** The subtitle track of the media now loaded was picked ([pickTextTrack]). */
    private var textPicked = false
    /** Sound without any picture (a DivX 3 AVI): given up on once it has stood a moment ([checkPicture]). */
    private val pictureWatch = TrackSupport.PictureWatch()
    private lateinit var noticeView: TextView
    private var closing = false
    private var speed = 1f
    /** Synced playback: follows the host's shared timeline (several screens, joined viewers). */
    private var follower: io.github.patschgoo.castapp.multi.SyncFollower? = null

    private val ticker = object : Runnable {
        override fun run() {
            checkPicture()
            reportState()
            catchUp()
            followTimeline()
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
        noticeView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setBackgroundColor(0xB0000000.toInt())
            setPadding(32, 16, 32, 16)
            visibility = View.GONE
        }
        root.addView(playerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(photoView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(infoView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(
            noticeView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = (32 * resources.displayMetrics.density).toInt() },
        )
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
        follower?.stop()
        follower = null
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
        tracksChecked = false
        textPicked = false
        pictureWatch.reset()
        startFollowing(media)
        infoView.visibility = View.GONE
        handler.removeCallbacks(hideNotice)
        noticeView.visibility = View.GONE
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
                        .setId(SIDECAR_ID)
                        .setMimeType(if (srt) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }
        player.trackSelectionParameters = textParameters(player.trackSelectionParameters, media)
        applySubtitleStyle(io.github.patschgoo.castapp.subtitles.SubtitleStyle.decode(media.subtitleStyle))
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

    /** The subtitle look chosen on the phone (size, colour, background); the TV's own when none came. */
    private fun applySubtitleStyle(style: io.github.patschgoo.castapp.subtitles.SubtitleStyle?) {
        val view = playerView.subtitleView ?: return
        if (style == null) {
            view.setUserDefaultStyle()
            view.setUserDefaultTextSize()
            return
        }
        val edge = if (style.background == io.github.patschgoo.castapp.subtitles.SubtitleBackground.SHADOW) {
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        } else {
            CaptionStyleCompat.EDGE_TYPE_NONE
        }
        view.setStyle(CaptionStyleCompat(style.foregroundArgb, style.backgroundArgb, Color.TRANSPARENT, edge, Color.BLACK, null))
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.size.scale)
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

    /** Stops playing but keeps the screen: the phone usually sends the next address right after Stop. */
    fun stopPlayback() {
        exo?.stop()
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
                showing?.let { TvReceiver.failed(it, PlaybackFailure.of(error, it)) }
                reportState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Played to its end by itself: the item a sender queued (SetNextAVTransportURI) starts here.
                if (playbackState == Player.STATE_ENDED && !failed) showing?.let { TvReceiver.playedToEnd(it) }
                reportState()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) = reportState()
            override fun onTracksChanged(tracks: Tracks) {
                checkTracks(tracks)
                pickTextTrack(tracks)
                checkPicture()
            }
        })
        playerView.player = player
        exo = player
        exoLowLatency = lowLatency
        return player
    }

    /**
     * A video whose picture has no decoder on this box fails like a player error (the phone moves on to
     * its next way, e.g. converting); sound without a decoder is only pointed out, the picture plays on.
     */
    private fun checkTracks(tracks: Tracks) {
        val media = showing ?: return
        if (failed || tracksChecked || tracks.isEmpty) return
        when (val verdict = TrackSupport.verdict(TrackSupport.groupsOf(tracks), media.kind, media.lowLatency, media.joinedFrom != null)) {
            is TrackSupport.Verdict.CantPlay -> {
                tracksChecked = true
                cantPlay(media, verdict.codec, PlaybackFailure.noVideoDecoder(verdict.codec))
            }
            // Music without a sound decoder has nothing left to play: it fails like a picture without one.
            is TrackSupport.Verdict.NoSound -> if (media.kind == TvMediaKind.AUDIO) {
                tracksChecked = true
                cantPlay(media, verdict.codec, PlaybackFailure.noAudioDecoder(verdict.codec))
            } else {
                tracksChecked = true
                showNotice(getString(io.github.patschgoo.castapp.R.string.tv_no_sound_codec, verdict.codec))
                TvReceiver.warn(media, PlaybackFailure.playsWithoutSound(verdict.codec))
            }
            TrackSupport.Verdict.Fine -> Unit
        }
    }

    /** Gives up on [media]: says why on the screen ([what]) and to the sender ([error]), and stops. */
    private fun cantPlay(media: TvMedia, what: String, error: TvError) {
        failed = true
        infoView.text = getString(io.github.patschgoo.castapp.R.string.tv_cant_play, what)
        infoView.visibility = View.VISIBLE
        TvReceiver.failed(media, error)
        exo?.stop()
        reportState()
    }

    /**
     * The subtitle settings for [media], from scratch (the player stays between files): the phone's
     * hint ([TextTrackHint]) switches the file's subtitles off or prefers its languages; without one
     * the player picks as it always does (the file's default or forced track, the TV's caption settings).
     */
    private fun textParameters(current: TrackSelectionParameters, media: TvMedia): TrackSelectionParameters {
        val hint = TextTrackHint.decode(media.subtitleTrack).takeIf { media.subtitleUri == null }
        val languages = hint?.preferred ?: TrackSelectionParameters.getDefaults(this).preferredTextLanguages
        return current.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, hint?.off == true)
            .setPreferredTextLanguages(*languages.toTypedArray())
            .build()
    }

    /**
     * Once the file's tracks are known: the subtitle file the phone sent wins over the file's own
     * default track; else the track inside the file the phone asked for ([TextTrackHint.pick]).
     */
    private fun pickTextTrack(tracks: Tracks) {
        val media = showing ?: return
        val player = exo ?: return
        if (textPicked || tracks.isEmpty) return
        val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
        fun isSidecar(g: Tracks.Group) = g.getTrackFormat(0).id?.endsWith(SIDECAR_ID) == true
        val chosen = if (media.subtitleUri != null) {
            text.firstOrNull(::isSidecar) ?: return // not prepared yet
        } else {
            val hint = TextTrackHint.decode(media.subtitleTrack)
            val inFile = text.filterNot(::isSidecar)
            hint?.pick(inFile.map { TextTrackHint.Found(it.getTrackFormat(0).language, it.getTrackFormat(0).label) })?.let { inFile[it] }
        }
        textPicked = true
        if (chosen == null || !chosen.isSupported) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(chosen.mediaTrackGroup, 0))
            .build()
    }

    /**
     * A video whose tracks are sound only, with no picture group at all (Media3 drops a DivX 3 stream):
     * after a moment with the player ready ([TrackSupport.PictureWatch]: tracks may still come in steps),
     * it fails like a picture without decoder, so the phone moves on to its next way.
     */
    private fun checkPicture() {
        val media = showing ?: return
        val p = exo ?: return
        if (failed || media.kind != TvMediaKind.VIDEO) return
        val soundOnly = TrackSupport.soundWithoutPicture(
            TrackSupport.groupsOf(p.currentTracks), media.kind, media.lowLatency, media.joinedFrom != null, media.live,
        )
        val ready = p.playbackState == Player.STATE_READY
        if (!pictureWatch.check(android.os.SystemClock.elapsedRealtime(), soundOnly, ready)) return
        cantPlay(
            media,
            getString(io.github.patschgoo.castapp.R.string.tv_no_picture),
            PlaybackFailure.noPicture(PlaybackFailure.containerName(media.uri, media.mimeType)),
        )
    }

    /** A short line at the top that goes away by itself. */
    private fun showNotice(text: String) {
        handler.removeCallbacks(hideNotice)
        noticeView.text = text
        noticeView.visibility = View.VISIBLE
        handler.postDelayed(hideNotice, NOTICE_MS)
    }

    private val hideNotice = Runnable { noticeView.visibility = View.GONE }

    private fun reportState() {
        // Let go of by TvReceiver.stop(): until it's gone this screen mustn't speak for a new one starting.
        if (TvReceiver.player !== this) return
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

    // ---- synced playback ---------------------------------------------------------------------------

    private fun startFollowing(media: TvMedia) {
        val url = media.syncUrl
        if (follower?.syncUrl == url) return
        follower?.stop()
        follower = null
        if (url == null || media.kind == TvMediaKind.PHOTO) return
        val f = io.github.patschgoo.castapp.multi.SyncFollower(url, android.os.SystemClock::elapsedRealtime)
        val joinedFrom = media.joinedFrom
        if (joinedFrom != null) {
            f.onHostChanged = { itemChanged, stopped ->
                handler.post {
                    if (follower !== f) return@post
                    when {
                        stopped -> {
                            infoView.text = getString(io.github.patschgoo.castapp.R.string.multi_host_stopped)
                            infoView.visibility = View.VISIBLE
                            exo?.stop()
                        }
                        // The host moved on to the next item: get its stream.
                        itemChanged -> io.github.patschgoo.castapp.multi.Peers.rejoin(joinedFrom)
                    }
                }
            }
        }
        follower = f
        f.start()
    }

    /** Keeps this screen with the host's timeline: small differences by speed, big ones with a jump. */
    private fun followTimeline() {
        val f = follower ?: return
        val p = exo ?: return
        if (exoLowLatency || failed || p.playbackState != Player.STATE_READY) return
        val c = f.correction(p.currentPosition, p.isPlaying, speed) ?: return
        c.seekToMs?.let { p.seekTo(it) }
        if (c.rate != speed) {
            speed = c.rate
            p.playbackParameters = PlaybackParameters(c.rate)
        }
        when (c.play) {
            true -> if (!p.playWhenReady) p.play()
            false -> if (p.playWhenReady) p.pause()
            null -> Unit
        }
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
            var error: TvError? = null
            val bitmap = try {
                downloadPhoto(url, metrics.widthPixels, metrics.heightPixels).also {
                    // Loaded, but not a picture Android can read.
                    if (it == null) error = PlaybackFailure.unsupportedFormat(PlaybackFailure.containerName(url, showing?.mimeType))
                }
            } catch (e: PhotoHttpException) {
                error = PlaybackFailure.network(httpStatus = e.status)
                null
            } catch (_: Exception) {
                error = PlaybackFailure.network()
                null
            }
            runOnUiThread {
                val media = showing ?: return@runOnUiThread
                if (media.uri != url) return@runOnUiThread
                if (bitmap != null) {
                    photoView.setImageBitmap(bitmap)
                    photoState = TransportState.PLAYING
                } else {
                    infoView.text = getString(io.github.patschgoo.castapp.R.string.tv_cant_show_photo)
                    infoView.visibility = View.VISIBLE
                    photoState = TransportState.STOPPED
                    error?.let { TvReceiver.failed(media, it) }
                }
                reportState()
            }
        }, "tv-photo").apply { isDaemon = true; start() }
    }

    private class PhotoHttpException(val status: Int) : java.io.IOException("HTTP $status")

    private fun downloadPhoto(url: String, maxW: Int, maxH: Int): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        val bytes = try {
            if (conn.responseCode !in 200..299) throw PhotoHttpException(conn.responseCode)
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

    private companion object {
        /** How long a notice ("No sound: …") stays on screen. */
        const val NOTICE_MS = 6000L

        /** The id of the subtitle file the phone sends (Media3 puts "n:" in front of it). */
        const val SIDECAR_ID = "castapp-sidecar"
    }
}
