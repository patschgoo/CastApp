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
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
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
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.settings.ThemeMode
import io.github.patschgoo.castapp.ui.theme.CastAppTheme
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
    /** The sound track of the media now loaded was picked ([pickAudioTrack]). */
    private var audioPicked = false
    /** The subtitle files of the media now loaded and which one to show ([SubtitleFiles.plan]). */
    private var subtitlePlan = SubtitleFiles.Plan()
    /** Sound without any picture (a DivX 3 AVI): given up on once it has stood a moment ([checkPicture]). */
    private val pictureWatch = TrackSupport.PictureWatch()
    private lateinit var noticeView: TextView
    private var closing = false
    private var speed = 1f
    /** Synced playback: follows the host's shared timeline (several screens, joined viewers). */
    private var follower: io.github.patschgoo.castapp.multi.SyncFollower? = null

    /** On a TV the remote steers the player ([TvRemote]); a phone that receives keeps the touch controls. */
    private val tv by lazy { TvReceiver.isTv(this) }
    private var remote = RemoteState()
    private val ui = mutableStateOf(TvPlayerUi())
    /** The entries of the list that is open, and the tracks behind them (none behind "Off"). */
    private var listed: List<TrackChoice?> = emptyList()
    private var lastWindAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            checkPicture()
            reportState()
            catchUp()
            followTimeline()
            if (tv) {
                // The controls stay while it is paused on purpose; with nothing to pause (a photo, a mirror) they go.
                val paused = remoteContext().canPause && exo?.playWhenReady == false
                remote = TvRemote.tick(remote, android.os.SystemClock.elapsedRealtime(), playing = !paused)
                publishUi()
            }
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
            // On a TV the controls are CastApp's own, made for the remote ([TvPlayerOverlay]).
            useController = !tv
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
        if (tv) {
            root.addView(
                ComposeView(this).apply {
                    isFocusable = false
                    setContent { CastAppTheme(ThemeMode.DARK) { TvPlayerOverlay(ui.value) } }
                },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
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
            // On a TV, Back first closes the controls and asks once before it ends the cast (a slip of the
            // thumb shouldn't); after "can't play" there is nothing to lose.
            if (tv && !failed) onRemote(TvKey.BACK, 0) else endCast()
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

    /** Ends the cast from this side; the phone sees it stopped. */
    private fun endCast() {
        closing = true
        TvReceiver.closedOnTv()
        exo?.stop()
        io.github.patschgoo.castapp.service.SwapDirection.ended(this)
        finish()
    }

    // ---- the remote (TV) -------------------------------------------------------------------------

    // Activity.dispatchKeyEvent is public Android API; lint mistakes it for androidx.core's
    // restricted ComponentActivity override.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // "Swap back" is a real button: while it shows, the arrows and OK belong to it.
        if (!tv || swapButton.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        val key = keyFor(event.keyCode) ?: return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val winding = key == TvKey.LEFT || key == TvKey.RIGHT || key == TvKey.FORWARD || key == TvKey.REWIND
        if (event.repeatCount > 0) {
            // A held button repeats many times a second: only the arrows repeat, a few times a second.
            val now = android.os.SystemClock.elapsedRealtime()
            val moving = winding || key == TvKey.UP || key == TvKey.DOWN
            if (!moving || now - lastWindAt < WIND_EVERY_MS) return true
            lastWindAt = now
        }
        onRemote(key, event.repeatCount)
        return true
    }

    private fun onRemote(key: TvKey, repeat: Int) {
        val before = remote
        val (next, actions) = TvRemote.press(before, key, android.os.SystemClock.elapsedRealtime(), remoteContext(), repeat) { selectedIn(it) }
        remote = next
        if (next.level == ControlsLevel.PANEL && next.panel != null && before.level != ControlsLevel.PANEL) {
            listed = choicesOf(next.panel)
        }
        for (action in actions) {
            when (action) {
                RemoteAction.TogglePlay -> if (exo?.playWhenReady == true) pauseNow() else resume()
                RemoteAction.Play -> resume()
                RemoteAction.Pause -> pauseNow()
                is RemoteAction.SeekBy -> exo?.let { p ->
                    val end = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
                    p.seekTo((p.currentPosition + action.ms).coerceIn(0, end))
                }
                RemoteAction.Stop -> {
                    endCast()
                    return
                }
                is RemoteAction.PickSubtitle -> pickText(listed.getOrNull(action.index), off = action.index == 0)
                is RemoteAction.PickSound -> listed.getOrNull(action.index)?.let { pickSound(it) }
            }
        }
        publishUi()
    }

    /** A subtitle or sound track of the file on screen, with the name the list shows. */
    private class TrackChoice(val group: Tracks.Group, val track: Int, val name: String)

    private fun tracksOf(type: Int): List<TrackChoice> {
        val groups = exo?.currentTracks?.groups ?: return emptyList()
        val found = ArrayList<TrackChoice>()
        for (g in groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val format = g.getTrackFormat(i)
                val fallback = getString(io.github.patschgoo.castapp.R.string.tv_track_n, found.size + 1)
                found += TrackChoice(g, i, TrackNames.name(format.language, format.label, fallback))
            }
        }
        return found
    }

    /** The list a button opens: subtitles start with "Off" (null). */
    private fun choicesOf(panel: PlayerPanel): List<TrackChoice?> =
        if (panel == PlayerPanel.SUBTITLES) listOf<TrackChoice?>(null) + tracksOf(C.TRACK_TYPE_TEXT) else tracksOf(C.TRACK_TYPE_AUDIO)

    /** Which entry of [choicesOf] is in use now. */
    private fun selectedIn(panel: PlayerPanel): Int {
        val at = choicesOf(panel).indexOfFirst { it != null && it.group.isTrackSelected(it.track) }
        return at.coerceAtLeast(0)
    }

    private fun pickText(choice: TrackChoice?, off: Boolean) {
        val p = exo ?: return
        if (choice == null && !off) return
        textPicked = true
        val params = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        p.trackSelectionParameters = if (choice == null) {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        } else {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.track))
                .build()
        }
    }

    private fun pickSound(choice: TrackChoice) {
        val p = exo ?: return
        audioPicked = true
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.track))
            .build()
    }

    /** What the remote can do with the media on screen. */
    private fun remoteContext(): RemoteContext {
        val media = showing
        val p = exo
        if (media == null || p == null || media.kind == TvMediaKind.PHOTO || failed) return RemoteContext(canSeek = false, canPause = false)
        // Synced screens follow their host's timeline: pausing or winding here would only fight it.
        val free = follower == null && !media.lowLatency
        val length = p.duration
        return RemoteContext(
            canSeek = free && !media.live && length != C.TIME_UNSET && length > 0 && p.isCurrentMediaItemSeekable,
            canPause = free,
            subtitleChoices = if (media.kind == TvMediaKind.VIDEO) tracksOf(C.TRACK_TYPE_TEXT).size.let { if (it > 0) it + 1 else 0 } else 0,
            soundChoices = tracksOf(C.TRACK_TYPE_AUDIO).size,
        )
    }

    /** Hands the controls' state to the screen ([TvPlayerOverlay]). */
    private fun publishUi() {
        if (!tv) return
        val media = showing
        val p = exo
        val context = remoteContext()
        val shown = remote.level != ControlsLevel.HIDDEN
        // The names of the tracks in use are only looked up while the controls show.
        val detail = if (shown && media != null && media.kind == TvMediaKind.VIDEO) {
            val text = tracksOf(C.TRACK_TYPE_TEXT)
            val sound = tracksOf(C.TRACK_TYPE_AUDIO)
            listOfNotNull(
                if (text.isNotEmpty()) getString(R.string.tv_detail_subtitles, text.firstOrNull { it.group.isTrackSelected(it.track) }?.name ?: getString(R.string.tv_sub_off)) else null,
                if (sound.size >= 2) sound.firstOrNull { it.group.isTrackSelected(it.track) }?.let { getString(R.string.tv_detail_sound, it.name) } else null,
            ).joinToString(" · ")
        } else {
            ""
        }
        val panel = remote.panel
        ui.value = TvPlayerUi(
            remote = remote,
            buttons = context.buttons,
            canSeek = context.canSeek,
            title = media?.title.orEmpty(),
            detail = detail,
            positionMs = p?.currentPosition?.coerceAtLeast(0) ?: 0,
            durationMs = p?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
            playing = p?.playWhenReady == true,
            backHint = remote.backHint(android.os.SystemClock.elapsedRealtime()),
            entries = if (remote.level == ControlsLevel.PANEL) listed.map { it?.name ?: getString(R.string.tv_sub_off) } else emptyList(),
            current = if (remote.level == ControlsLevel.PANEL && panel != null) selectedIn(panel) else 0,
            music = media?.kind == TvMediaKind.AUDIO && !failed,
        )
    }

    /** A message in the middle of the screen; on a TV it also says how to get away from it. */
    private fun showInfo(text: String) {
        infoView.text = if (tv) text + "\n\n" + getString(io.github.patschgoo.castapp.R.string.tv_back_home) else text
        infoView.visibility = View.VISIBLE
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
        remote = TvRemote.reset()
        listed = emptyList()
        tracksChecked = false
        textPicked = false
        audioPicked = false
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
        val subtitles = SubtitleFiles.plan(media)
        subtitlePlan = subtitles
        if (subtitles.files.isNotEmpty()) {
            item.setSubtitleConfigurations(
                subtitles.files.mapIndexed { i, file ->
                    MediaItem.SubtitleConfiguration.Builder(file.url.toUri())
                        .setId(file.id)
                        .setMimeType(file.mime)
                        .setLanguage(file.language)
                        .setLabel(file.label)
                        .setSelectionFlags(if (i == subtitles.shown) C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                },
            )
        }
        player.trackSelectionParameters = trackParameters(player.trackSelectionParameters, subtitles, TrackHint.decode(media.audioTrack))
        applySubtitleStyle(io.github.patschgoo.castapp.subtitles.SubtitleStyle.decode(media.subtitleStyle))
        // On a TV the title of a song is part of the controls' screen (it moves, for OLED screens).
        if (media.kind == TvMediaKind.AUDIO && !tv) {
            infoView.text = getString(io.github.patschgoo.castapp.R.string.tv_now_playing_audio, media.title)
            infoView.visibility = View.VISIBLE
        }
        speed = 1f
        player.playbackParameters = PlaybackParameters.DEFAULT
        player.setMediaItem(item.build())
        player.prepare()
        player.playWhenReady = autoPlay
        applyVolume()
        publishUi()
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
                showInfo(getString(io.github.patschgoo.castapp.R.string.tv_cant_play, error.errorCodeName))
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
                pickAudioTrack(tracks)
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
        showInfo(getString(io.github.patschgoo.castapp.R.string.tv_cant_play, what))
        TvReceiver.failed(media, error)
        exo?.stop()
        reportState()
    }

    /**
     * The subtitle and sound settings for the media now loaded, from scratch (the player stays between
     * files). Subtitles ([SubtitleFiles.plan]): the sender's hint switches them off or prefers its
     * languages. Sound: the sender's hint ([TrackHint], `castapp:audiotrack:`) prefers its languages.
     * Without hints the player picks as it always does (the file's default or forced track, the TV's
     * caption settings).
     */
    private fun trackParameters(current: TrackSelectionParameters, subtitles: SubtitleFiles.Plan, audio: TrackHint?): TrackSelectionParameters {
        val defaults = TrackSelectionParameters.getDefaults(this)
        val sound = audio?.takeUnless { it.off }
        return current.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitles.off)
            .setPreferredTextLanguages(*(subtitles.languages ?: defaults.preferredTextLanguages).toTypedArray())
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setPreferredAudioLanguages(*(sound?.preferred ?: defaults.preferredAudioLanguages).toTypedArray())
            .build()
    }

    /**
     * Once the file's tracks are known: the subtitle file the sender sent wins over the file's own
     * default track; else the track inside the file it asked for ([SubtitleFiles.pickText]).
     */
    private fun pickTextTrack(tracks: Tracks) {
        val player = exo ?: return
        if (textPicked || tracks.isEmpty) return
        val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
        val found = text.map { g -> g.getTrackFormat(0).let { SubtitleFiles.FoundText(it.id, it.language, it.label) } }
        val chosen = when (val pick = SubtitleFiles.pickText(subtitlePlan, found)) {
            SubtitleFiles.TextPick.NotYet -> return // not prepared yet
            SubtitleFiles.TextPick.Player -> null
            is SubtitleFiles.TextPick.Show -> text[pick.index]
        }
        textPicked = true
        if (chosen == null || !chosen.isSupported) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(chosen.mediaTrackGroup, 0))
            .build()
    }

    /**
     * Once the file's tracks are known: the sound track the sender asked for ([TrackHint.pick], each
     * track of each sound group in order); without a hint, or none like it, the player's choice.
     */
    private fun pickAudioTrack(tracks: Tracks) {
        val player = exo ?: return
        if (audioPicked || tracks.isEmpty) return
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { g -> (0 until g.length).map { g to it } }
        if (audio.isEmpty()) return
        audioPicked = true
        val hint = TrackHint.decode(showing?.audioTrack)?.takeUnless { it.off } ?: return
        val at = hint.pick(audio.map { (g, i) -> g.getTrackFormat(i).let { TrackHint.Found(it.language, it.label) } }) ?: return
        val (group, track) = audio[at]
        if (!group.isTrackSupported(track)) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
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
                            showInfo(getString(io.github.patschgoo.castapp.R.string.multi_host_stopped))
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
                    showInfo(getString(io.github.patschgoo.castapp.R.string.tv_cant_show_photo))
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

        /** A held arrow winds this often. */
        const val WIND_EVERY_MS = 250L

        /** The remote's buttons the player handles itself; volume and the rest stay Android's. */
        fun keyFor(keyCode: Int): TvKey? = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TvKey.OK
            KeyEvent.KEYCODE_DPAD_LEFT -> TvKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> TvKey.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> TvKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> TvKey.DOWN
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> TvKey.PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY -> TvKey.PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> TvKey.PAUSE
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> TvKey.FORWARD
            KeyEvent.KEYCODE_MEDIA_REWIND -> TvKey.REWIND
            KeyEvent.KEYCODE_MEDIA_STOP -> TvKey.STOP
            else -> null
        }
    }
}
