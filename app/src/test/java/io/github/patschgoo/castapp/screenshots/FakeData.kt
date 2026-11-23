package io.github.patschgoo.castapp.screenshots

import android.content.Context
import android.os.SystemClock
import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceEntry
import io.github.patschgoo.castapp.discovery.DeviceKind
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastPhase
import io.github.patschgoo.castapp.service.CastState
import io.github.patschgoo.castapp.service.MediaItem
import io.github.patschgoo.castapp.service.WatchHistory
import io.github.patschgoo.castapp.ui.DevicesActions

/**
 * Made-up TVs, speakers and titles for the pictures, in English or German (the names people
 * would give their TVs). Nothing here is anyone's real data.
 */
class FakeData(private val de: Boolean) {
    private fun t(en: String, de: String) = if (this.de) de else en

    val livingRoom = CastDevice(
        id = "cast-living", name = t("Living Room TV", "Wohnzimmer-TV"), host = "192.168.1.20",
        model = "Chromecast with Google TV", status = t("Ambient Mode", "Ambientemodus"),
    )
    val bedroom = CastDevice(
        id = "dlna-bedroom", name = t("Bedroom Fire TV", "Schlafzimmer Fire TV"), host = "192.168.1.31", port = 8098,
        model = "CastApp TV (Fire TV Stick 4K)", kind = DeviceKind.DLNA,
    )
    val office = CastDevice(
        id = "dlna-office", name = t("Office Samsung", "Büro Samsung"), host = "192.168.1.44", port = 9197,
        model = "Samsung QN90 Series", kind = DeviceKind.DLNA,
    )
    val appleTv = CastDevice(id = "airplay-den", name = t("Den Apple TV", "Apple TV Arbeitszimmer"), host = "192.168.1.52", port = 7000, model = "AppleTV11,1", kind = DeviceKind.AIRPLAY)
    val roku = CastDevice(id = "roku-kids", name = t("Kids' Room Roku", "Kinderzimmer Roku"), host = "192.168.1.63", port = 8060, model = "Roku Express", kind = DeviceKind.ROKU)
    val kitchen = CastDevice(id = "cast-kitchen", name = t("Kitchen Speaker", "Küchenlautsprecher"), host = "192.168.1.71", model = "Nest Audio", video = false)
    val house = CastDevice(id = "cast-group", name = t("Whole House", "Ganzes Haus"), host = "192.168.1.71", port = 32187, model = "Google Cast Group", video = false, group = true)

    val devices: List<DeviceEntry> = listOf(
        DeviceEntry(livingRoom, saved = true, online = true),
        DeviceEntry(bedroom, saved = true, online = true),
        DeviceEntry(office, saved = false, online = true),
        DeviceEntry(appleTv, saved = false, online = true),
        DeviceEntry(roku, saved = true, online = false),
        DeviceEntry(kitchen, saved = false, online = true),
        DeviceEntry(house, saved = false, online = true),
    )

    val filmTitle = t("Mountain Trails — A Summer in the Alps", "Bergpfade – Ein Sommer in den Alpen")

    /** The phone's screen on the living room TV. */
    val mirroring = CastState.Active(
        device = livingRoom,
        phase = CastPhase.LIVE,
        kind = CastKind.SCREEN,
        volume = 0.4f,
        audio = true,
        resolution = "1080p",
        bitrateKbps = 8000,
        videoCodec = "H.264",
        qualityLabel = "1080p",
        qualityOptions = listOf("720p", "1080p", "1440p"),
    )

    /** A film from the phone, a third of the way in; [subtitles]: subtitles are being written. */
    fun playingFile(subtitles: Boolean = false) = CastState.Active(
        device = livingRoom,
        phase = CastPhase.LIVE,
        kind = CastKind.FILE,
        volume = 0.55f,
        audio = true,
        title = filmTitle,
        positionMs = 31 * 60_000L + 12_000L,
        durationMs = 94 * 60_000L,
        positionAt = SystemClock.elapsedRealtime(),
        queueIndex = 1,
        queueSize = 4,
        hasSubtitles = true,
        subtitlesOn = true,
        subtitleProgress = if (subtitles) 42 else -1,
        qualityLabel = t("Original", "Original"),
        qualityOptions = listOf("Original", "1080p", "720p"),
    )

    /** "Continue watching": three videos stopped part-way. */
    fun fillHistory(context: Context) {
        val history = WatchHistory.get(context)
        history.clear()
        val items = listOf(
            Triple(t("Saltwater Summer", "Salzwassersommer"), 38, 104),
            Triple(t("Kitchen Diaries – Episode 4", "Küchentagebuch – Folge 4"), 12, 41),
            Triple(t("Coast Road Trip 2026", "Küstenroadtrip 2026"), 21, 58),
        )
        items.forEachIndexed { i, (title, at, length) ->
            val item = MediaItem(title, "content://media/external/video/media/${900 + i}", "video/mp4")
            history.record(item, at * 60_000L, length * 60_000L, force = true)
        }
    }

    companion object {
        fun of(variant: Variant) = FakeData(variant.lang == "de")

        fun clearHistory(context: Context) = WatchHistory.get(context).clear()

        fun actions() = DevicesActions(
            onRefresh = {}, onScan = {}, onOpenSettings = {}, onMirror = {}, onCastApp = {},
            onCastFiles = { _, _ -> }, onCastSound = {}, onOpenBrowser = {}, onAudioMode = {},
            onForget = {}, onAddManual = { null }, onStop = {}, onVolumeDown = {}, onVolumeUp = {},
            onToggleMute = {}, onDarkScreen = {}, onPlayPause = {}, onSeek = {}, onRewind = {},
            onForward = {}, onNext = {}, onPrevious = {}, onToggleSubtitles = {},
        )
    }
}
