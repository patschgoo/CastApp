package io.github.patschgoo.castapp.render

import io.github.patschgoo.castapp.discovery.CastDevice
import java.net.InetAddress

/** What a TV should play. */
data class RendererMedia(
    val url: String,
    val mimeType: String,
    val title: String,
    /** Shown under the title, e.g. the TV name. */
    val subtitle: String,
    /** A live/endless stream (converted video): no seeking on the TV side. */
    val live: Boolean = false,
    /** WebVTT subtitles (Cast) / SRT (DLNA) served by the phone, if any. */
    val subtitleUrl: String? = null,
    val subtitleSrtUrl: String? = null,
    val subtitlesOn: Boolean = true,
    val startMs: Long = 0,
    /** CastApp's screen stream to a CastApp TV receiver: play with minimal buffering. */
    val lowLatency: Boolean = false,
    /** How the subtitles should look, where the TV can be told (Google Cast, CastApp TV). */
    val subtitleStyle: io.github.patschgoo.castapp.subtitles.SubtitleStyle? = null,
    /**
     * Several screens: where a CastApp receiver reads the shared timeline to keep in sync
     * (only ever sent to CastApp TVs; other TVs never see it).
     */
    val syncUrl: String? = null,
)

enum class PlayState { PLAYING, PAUSED, BUFFERING, IDLE, FINISHED, ERROR, STOPPED }

data class RendererStatus(
    val state: PlayState,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
)

/**
 * A TV (or speaker) that plays media by URL: Google Cast via the Default Media
 * Receiver, or a DLNA renderer such as a Samsung/LG TV.
 */
interface MediaRenderer {
    interface Listener {
        fun onStatus(status: RendererStatus)
        fun onVolume(level: Double?, muted: Boolean) {}

        /** The connection to the device was lost (Wi-Fi drop, TV restart). */
        fun onLost(error: Throwable?)

        /** Playback was ended on the TV itself (remote control, another phone). */
        fun onClosedOnDevice()
    }

    val device: CastDevice
    var listener: Listener?

    /** Our address as the TV sees it; used to build URLs for content served by the phone. */
    val localAddress: InetAddress?

    suspend fun connect()

    /** Starts playing [media]. Throws if the TV refuses it. */
    suspend fun load(media: RendererMedia): RendererStatus

    suspend fun play()
    suspend fun pause()
    suspend fun seek(positionMs: Long)

    /** Asks the TV for its current state (also reported through [Listener.onStatus]). */
    suspend fun refresh(): RendererStatus?

    suspend fun setSubtitlesVisible(on: Boolean)
    suspend fun setVolume(level: Double)
    suspend fun setMuted(muted: Boolean)

    /** Stops playback and closes the player on the TV. */
    suspend fun stop()

    /** Closes the connection (without stopping playback). */
    fun close()

    companion object {
        fun create(device: CastDevice): MediaRenderer = when (device.kind) {
            io.github.patschgoo.castapp.discovery.DeviceKind.CAST -> CastRenderer(device)
            io.github.patschgoo.castapp.discovery.DeviceKind.DLNA -> DlnaRenderer(device)
            io.github.patschgoo.castapp.discovery.DeviceKind.AIRPLAY -> AirPlayRenderer(
                device,
                io.github.patschgoo.castapp.airplay.AndroidAirPlayStore,
                io.github.patschgoo.castapp.service.CastController::askPin,
            )
            io.github.patschgoo.castapp.discovery.DeviceKind.ROKU -> RokuRenderer(device)
        }
    }
}
