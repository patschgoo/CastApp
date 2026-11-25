package io.github.patschgoo.castapp.tv

import java.util.Locale

/** The remote's buttons the TV player listens to. */
enum class TvKey { OK, LEFT, RIGHT, UP, DOWN, BACK, PLAY_PAUSE, PLAY, PAUSE, FORWARD, REWIND, STOP }

/** The buttons of the player's controls, in the order they are drawn. */
enum class PlayerButton { PLAY_PAUSE, SUBTITLES, SOUND, STOP }

/** The lists the Subtitles and Sound buttons open. */
enum class PlayerPanel { SUBTITLES, SOUND }

/** How much of the controls is on screen, and where the remote's arrows act. */
enum class ControlsLevel {
    /** Only the picture. */
    HIDDEN,

    /** The controls show; Left and Right move through the file. */
    BAR,

    /** The controls show; Left and Right move between the buttons. */
    BUTTONS,

    /** A list of subtitles or sound tracks is open. */
    PANEL,
}

/** What the media on screen allows. */
data class RemoteContext(
    /** A file with a length: not screen mirroring, not a live stream. */
    val canSeek: Boolean,
    /** False while mirroring a screen (pausing a live picture only adds delay). */
    val canPause: Boolean,
    /** Entries of the subtitle list, "Off" included; fewer than 2 means there is nothing to choose. */
    val subtitleChoices: Int = 0,
    /** Sound tracks; fewer than 2 means there is nothing to choose. */
    val soundChoices: Int = 0,
) {
    val buttons: List<PlayerButton>
        get() = buildList {
            if (canPause) add(PlayerButton.PLAY_PAUSE)
            if (subtitleChoices >= 2) add(PlayerButton.SUBTITLES)
            if (soundChoices >= 2) add(PlayerButton.SOUND)
            add(PlayerButton.STOP)
        }

    fun choices(panel: PlayerPanel): Int = if (panel == PlayerPanel.SUBTITLES) subtitleChoices else soundChoices
}

/** What the player should do after a key. */
sealed interface RemoteAction {
    data object TogglePlay : RemoteAction
    data object Play : RemoteAction
    data object Pause : RemoteAction
    data class SeekBy(val ms: Long) : RemoteAction

    /** End the cast and close the player. */
    data object Stop : RemoteAction
    data class PickSubtitle(val index: Int) : RemoteAction
    data class PickSound(val index: Int) : RemoteAction
}

data class RemoteState(
    val level: ControlsLevel = ControlsLevel.HIDDEN,
    val button: PlayerButton = PlayerButton.PLAY_PAUSE,
    val panel: PlayerPanel? = null,
    /** The highlighted entry of the open list. */
    val panelIndex: Int = 0,
    /** "Press Back again to stop casting" shows until then; a second Back before it stops. */
    val backHintUntil: Long = 0,
    /** The controls go away by themselves at this time (while it plays). */
    val hideAt: Long = 0,
) {
    fun backHint(now: Long): Boolean = now < backHintUntil
}

/**
 * What the remote's buttons do in CastApp TV's player. Plain logic without Android, so every key is
 * covered by tests: the player screen only draws the [RemoteState] and carries out the [RemoteAction]s.
 *
 * With the controls hidden: OK pauses or plays, Left and Right jump through the file, Up and Down
 * bring the buttons. Back closes a list, then the controls; with nothing open it asks once
 * ("Press Back again") before it ends the cast, so a slip of the thumb doesn't.
 */
object TvRemote {
    /** The controls stay this long after the last key. */
    const val SHOW_MS = 5_000L

    /** A second Back within this time ends the cast. */
    const val BACK_AGAIN_MS = 3_000L

    /** How far Left and Right jump: further the longer the button is held ([repeat] counts the repeats). */
    fun seekStep(repeat: Int): Long = when {
        repeat < 5 -> 10_000L
        repeat < 20 -> 30_000L
        else -> 60_000L
    }

    /**
     * [selected] tells which entry of a list is the one in use now, so the list opens on it.
     */
    fun press(
        state: RemoteState,
        key: TvKey,
        now: Long,
        context: RemoteContext,
        repeat: Int = 0,
        selected: (PlayerPanel) -> Int = { 0 },
    ): Pair<RemoteState, List<RemoteAction>> {
        val buttons = context.buttons
        val s = normalize(state, context)
        val shown = s.copy(hideAt = now + SHOW_MS, backHintUntil = 0)
        fun show(level: ControlsLevel) = shown.copy(level = if (level == ControlsLevel.BAR && !context.canSeek) ControlsLevel.BUTTONS else level)
        fun seek(direction: Int): List<RemoteAction> = if (context.canSeek) listOf(RemoteAction.SeekBy(direction * seekStep(repeat))) else emptyList()
        fun toggle(): List<RemoteAction> = if (context.canPause) listOf(RemoteAction.TogglePlay) else emptyList()

        // The remote's own play, pause and wind buttons work wherever the focus is.
        when (key) {
            TvKey.PLAY_PAUSE -> return keepOrBar(s, shown, context) to toggle()
            TvKey.PLAY -> return keepOrBar(s, shown, context) to if (context.canPause) listOf(RemoteAction.Play) else emptyList()
            TvKey.PAUSE -> return keepOrBar(s, shown, context) to if (context.canPause) listOf(RemoteAction.Pause) else emptyList()
            TvKey.FORWARD -> return keepOrBar(s, shown, context) to seek(1)
            TvKey.REWIND -> return keepOrBar(s, shown, context) to seek(-1)
            TvKey.STOP -> return s to listOf(RemoteAction.Stop)
            else -> Unit
        }

        return when (s.level) {
            ControlsLevel.HIDDEN -> when (key) {
                TvKey.BACK ->
                    if (s.backHint(now)) s to listOf(RemoteAction.Stop)
                    else s.copy(backHintUntil = now + BACK_AGAIN_MS) to emptyList()
                TvKey.OK -> show(ControlsLevel.BAR) to toggle()
                TvKey.LEFT -> show(ControlsLevel.BAR) to seek(-1)
                TvKey.RIGHT -> show(ControlsLevel.BAR) to seek(1)
                else -> show(ControlsLevel.BUTTONS) to emptyList()
            }
            ControlsLevel.BAR -> when (key) {
                TvKey.BACK -> s.copy(level = ControlsLevel.HIDDEN) to emptyList()
                TvKey.OK -> shown to toggle()
                TvKey.LEFT -> shown to seek(-1)
                TvKey.RIGHT -> shown to seek(1)
                else -> show(ControlsLevel.BUTTONS) to emptyList()
            }
            ControlsLevel.BUTTONS -> {
                val at = buttons.indexOf(s.button)
                when (key) {
                    TvKey.BACK -> s.copy(level = ControlsLevel.HIDDEN) to emptyList()
                    TvKey.LEFT -> shown.copy(button = buttons[(at - 1).coerceAtLeast(0)]) to emptyList()
                    TvKey.RIGHT -> shown.copy(button = buttons[(at + 1).coerceAtMost(buttons.lastIndex)]) to emptyList()
                    TvKey.UP -> show(ControlsLevel.BAR) to emptyList()
                    TvKey.OK -> when (s.button) {
                        PlayerButton.PLAY_PAUSE -> shown to toggle()
                        PlayerButton.SUBTITLES -> open(shown, PlayerPanel.SUBTITLES, context, selected) to emptyList()
                        PlayerButton.SOUND -> open(shown, PlayerPanel.SOUND, context, selected) to emptyList()
                        PlayerButton.STOP -> s to listOf(RemoteAction.Stop)
                    }
                    else -> shown to emptyList()
                }
            }
            ControlsLevel.PANEL -> {
                val panel = s.panel ?: PlayerPanel.SUBTITLES
                val last = (context.choices(panel) - 1).coerceAtLeast(0)
                val closed = shown.copy(level = ControlsLevel.BUTTONS, panel = null)
                when (key) {
                    TvKey.BACK, TvKey.LEFT -> closed to emptyList()
                    TvKey.UP -> shown.copy(panelIndex = (s.panelIndex - 1).coerceAtLeast(0)) to emptyList()
                    TvKey.DOWN -> shown.copy(panelIndex = (s.panelIndex + 1).coerceAtMost(last)) to emptyList()
                    TvKey.OK -> closed to listOf(
                        if (panel == PlayerPanel.SUBTITLES) RemoteAction.PickSubtitle(s.panelIndex) else RemoteAction.PickSound(s.panelIndex),
                    )
                    else -> shown to emptyList()
                }
            }
        }
    }

    /** Called now and then: the controls go away by themselves while it plays; an open list stays. */
    fun tick(state: RemoteState, now: Long, playing: Boolean): RemoteState =
        if ((state.level == ControlsLevel.BAR || state.level == ControlsLevel.BUTTONS) && playing && now >= state.hideAt) {
            state.copy(level = ControlsLevel.HIDDEN)
        } else {
            state
        }

    /** New media: the controls of the last one don't fit any more. */
    fun reset(): RemoteState = RemoteState()

    private fun keepOrBar(s: RemoteState, shown: RemoteState, context: RemoteContext): RemoteState =
        if (s.level == ControlsLevel.HIDDEN) shown.copy(level = if (context.canSeek) ControlsLevel.BAR else ControlsLevel.BUTTONS) else shown

    private fun open(shown: RemoteState, panel: PlayerPanel, context: RemoteContext, selected: (PlayerPanel) -> Int): RemoteState {
        val last = (context.choices(panel) - 1).coerceAtLeast(0)
        return shown.copy(level = ControlsLevel.PANEL, panel = panel, panelIndex = selected(panel).coerceIn(0, last))
    }

    /** The media can change under the controls (tracks appear, a file follows a mirror): keep the state possible. */
    private fun normalize(state: RemoteState, context: RemoteContext): RemoteState {
        val buttons = context.buttons
        var s = if (state.button in buttons) state else state.copy(button = buttons.first())
        if (s.level == ControlsLevel.BAR && !context.canSeek) s = s.copy(level = ControlsLevel.BUTTONS)
        val panel = s.panel
        if (s.level == ControlsLevel.PANEL && (panel == null || context.choices(panel) < 2)) s = s.copy(level = ControlsLevel.BUTTONS, panel = null)
        return s
    }
}

/** The names of subtitle and sound tracks in the player's lists. */
object TrackNames {
    /**
     * "German", "Japanese · Commentary", a bare [label], or [fallback] ("Track 2") when the file says
     * nothing. [language] is what the player reports ("de", "ja", "und").
     */
    fun name(language: String?, label: String?, fallback: String, locale: Locale = Locale.getDefault()): String {
        val code = language?.trim()?.takeUnless { it.isEmpty() || it.equals("und", ignoreCase = true) }
        val spoken = code?.let {
            val shown = Locale.forLanguageTag(it).getDisplayLanguage(locale)
            if (shown.isBlank() || shown.equals(it, ignoreCase = true)) it.uppercase(Locale.ROOT) else shown.replaceFirstChar { c -> c.titlecase(locale) }
        }
        val extra = label?.trim()?.takeUnless { it.isEmpty() || it.equals(spoken, ignoreCase = true) || it.equals(code, ignoreCase = true) }
        return when {
            spoken != null && extra != null -> "$spoken · $extra"
            spoken != null -> spoken
            extra != null -> extra
            else -> fallback
        }
    }
}

/** A position in the file as the controls show it: "4:07", "1:02:45". */
fun playerClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}
