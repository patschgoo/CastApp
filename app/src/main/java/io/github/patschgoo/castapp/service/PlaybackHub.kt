package io.github.patschgoo.castapp.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One line of the queue sheet. */
data class QueueEntryView(val id: Long, val title: String, val type: MediaType, val live: Boolean)

data class QueueView(
    val entries: List<QueueEntryView>,
    val currentId: Long,
    val repeat: RepeatMode,
    val shuffled: Boolean,
    /** TV channels can only be jumped between, not edited. */
    val editable: Boolean,
)

/** One choice of the subtitle picker. */
data class SubtitleOptionView(val key: String, val label: String, val detail: String?, val selected: Boolean)

data class AudioOptionView(val ordinal: Int, val label: String, val selected: Boolean)

/** Subtitle and sound choices for the file playing now. */
data class TracksView(
    val subtitleOptions: List<SubtitleOptionView> = emptyList(),
    val audioOptions: List<AudioOptionView> = emptyList(),
    /** Subtitles are going to the TV: show the timing buttons. */
    val subtitlesActive: Boolean = false,
    val offsetMs: Long = 0,
    /** Reading a subtitle track out of the file: 0..100, or -1. */
    val extractProgress: Int = -1,
    /** Still looking into the file. */
    val probing: Boolean = false,
    /** Picture-based subtitle tracks that can't be shown. */
    val pictureSubtitles: Int = 0,
    val canPickSubtitles: Boolean = false,
)

/** "Resumed at 12:34 · Start over" */
data class ResumeNotice(val uri: String, val positionMs: Long)

/**
 * What the casting card shows besides [CastState]: the editable queue, track
 * choices and the resume notice of the file cast that runs now, and that cast
 * itself so the screen can talk to it directly (everything runs in one process).
 */
object PlaybackHub {
    // Only while the cast runs: the session clears it when it ends.
    @android.annotation.SuppressLint("StaticFieldLeak")
    @Volatile var session: MediaCastSession? = null
        internal set

    internal val queueFlow = MutableStateFlow<QueueView?>(null)
    val queue: StateFlow<QueueView?> = queueFlow.asStateFlow()

    internal val tracksFlow = MutableStateFlow(TracksView())
    val tracks: StateFlow<TracksView> = tracksFlow.asStateFlow()

    internal val resumeFlow = MutableStateFlow<ResumeNotice?>(null)
    val resumed: StateFlow<ResumeNotice?> = resumeFlow.asStateFlow()

    internal fun attach(s: MediaCastSession) {
        session = s
    }

    internal fun detach(s: MediaCastSession) {
        if (session !== s) return
        session = null
        queueFlow.value = null
        tracksFlow.value = TracksView()
        resumeFlow.value = null
    }
}
