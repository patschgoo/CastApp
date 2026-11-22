package io.github.patschgoo.castapp.settings

import android.app.UiModeManager
import android.content.Context
import androidx.core.content.edit
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Resolution(val width: Int, val height: Int, val label: String, val short: String) {
    P480(848, 480, "480p (weak Wi-Fi)", "480p"),
    P540(960, 540, "540p (saves data)", "540p"),
    P720(1280, 720, "720p HD", "720p"),
    P1080(1920, 1080, "1080p Full HD", "1080p"),
    P1440(2560, 1440, "1440p (needs a 4K TV device)", "1440p"),
    P2160(3840, 2160, "4K (Shield, Chromecast/Google TV 4K)", "4K"),
}

enum class StreamMode(val label: String, val description: String) {
    LOW_LATENCY("Low latency", "Fastest response. Falls back to Compatible automatically if the TV can't play it."),
    COMPATIBLE("Compatible (HLS)", "Works with every Cast receiver, but lags a few seconds behind."),
}

enum class ThemeMode(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark"),
}

enum class PictureMode(val label: String, val description: String) {
    FIT("Fit", "Whole picture, black bars where the shapes differ"),
    FILL("Fill the TV", "Zooms in to remove black bars; edges are cut off"),
    LANDSCAPE("Turn apps sideways", "Rotates the phone to landscape while casting, so apps fill the TV"),
}

enum class ConvertMode(val label: String, val description: String) {
    AUTO("Automatic", "Only when the TV can't play a file"),
    ALWAYS("Always", "Every video is converted on the phone (uses more battery)"),
    OFF("Never", "Show an error if the TV can't play a file"),
}

/** Video codec for screen mirroring. */
enum class CodecPreference {
    /** HEVC only where it is known to work, H.264 everywhere else. */
    AUTO,
    H264,
    /** HEVC wherever the phone can encode it (DLNA TVs always get H.264). */
    HEVC,
}

data class CastSettings(
    val resolution: Resolution = Resolution.P720,
    val fps: Int = 30,
    /** 0 = automatic. */
    val bitrateMbps: Int = 0,
    val audio: Boolean = Build.VERSION.SDK_INT >= 29,
    val streamMode: StreamMode = StreamMode.LOW_LATENCY,
    val port: Int = 8090,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val picture: PictureMode = PictureMode.FIT,
    /** Lower the quality automatically when the Wi-Fi can't keep up. */
    val adaptiveQuality: Boolean = true,
    /** Positive: sound plays later; negative: earlier. */
    val audioDelayMs: Int = 0,
    /** App ID of the optional low-latency TV player (empty = Google's standard player). */
    val receiverAppId: String = "",
    val convert: ConvertMode = ConvertMode.AUTO,
    val slideshowSeconds: Int = 6,
    /** Language code of the speech model used to create subtitles ("" = none chosen). */
    val subtitleLanguage: String = "",
    /** Create subtitles automatically for videos that have none. */
    val autoSubtitles: Boolean = false,
    /** Video codec for mirroring. */
    val videoCodec: CodecPreference = CodecPreference.AUTO,
    /** Mix the phone's microphone into the mirrored sound. */
    val microphone: Boolean = false,
    /** Microphone level in percent (100 = as recorded). */
    val micLevel: Int = 100,
) {
    /**
     * Bitrate for [hevc] streams: automatic bitrates are about a third lower for
     * HEVC (the same picture quality); a bitrate the user picked is kept.
     */
    fun bitrateFor(hevc: Boolean): Int =
        if (hevc && bitrateMbps == 0) (effectiveBitrate * 0.65 / 100_000).toInt() * 100_000 else effectiveBitrate

    val effectiveBitrate: Int
        get() {
            if (bitrateMbps > 0) return bitrateMbps * 1_000_000
            val base = when (resolution) {
                Resolution.P480 -> 1_800_000
                Resolution.P540 -> 2_500_000
                Resolution.P720 -> 4_500_000
                Resolution.P1080 -> 8_000_000
                Resolution.P1440 -> 12_000_000
                Resolution.P2160 -> 20_000_000
            }
            return if (fps > 30) base * 3 / 2 else base
        }
}

/** Simple settings stored in SharedPreferences and exposed as a StateFlow for the UI. */
class AppSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<CastSettings> = _state.asStateFlow()
    val current: CastSettings get() = _state.value

    private fun load(): CastSettings {
        val d = CastSettings()
        return CastSettings(
            resolution = enumOr(prefs.getString("resolution", null), d.resolution),
            fps = prefs.getInt("fps", d.fps),
            bitrateMbps = prefs.getInt("bitrate_mbps", d.bitrateMbps),
            audio = prefs.getBoolean("audio", d.audio) && Build.VERSION.SDK_INT >= 29,
            streamMode = enumOr(prefs.getString("stream_mode", null), d.streamMode),
            port = prefs.getInt("port", d.port),
            theme = enumOr(prefs.getString("theme", null), d.theme),
            picture = enumOr(prefs.getString("picture", null), d.picture),
            adaptiveQuality = prefs.getBoolean("adaptive", d.adaptiveQuality),
            audioDelayMs = prefs.getInt("audio_delay", d.audioDelayMs),
            receiverAppId = prefs.getString("receiver_app_id", d.receiverAppId) ?: "",
            convert = enumOr(prefs.getString("convert", null), d.convert),
            slideshowSeconds = prefs.getInt("slideshow_seconds", d.slideshowSeconds),
            subtitleLanguage = prefs.getString("subtitle_language", d.subtitleLanguage) ?: "",
            autoSubtitles = prefs.getBoolean("auto_subtitles", d.autoSubtitles),
            videoCodec = enumOr(prefs.getString("video_codec", null), d.videoCodec),
            microphone = prefs.getBoolean("microphone", d.microphone),
            micLevel = prefs.getInt("mic_level", d.micLevel).coerceIn(0, 200),
        )
    }

    fun update(transform: (CastSettings) -> CastSettings) {
        val s = transform(_state.value)
        prefs.edit {
            putString("resolution", s.resolution.name)
            putInt("fps", s.fps)
            putInt("bitrate_mbps", s.bitrateMbps)
            putBoolean("audio", s.audio)
            putString("stream_mode", s.streamMode.name)
            putInt("port", s.port)
            putString("theme", s.theme.name)
            putString("picture", s.picture.name)
            putBoolean("adaptive", s.adaptiveQuality)
            putInt("audio_delay", s.audioDelayMs)
            putString("receiver_app_id", s.receiverAppId.trim())
            putString("convert", s.convert.name)
            putInt("slideshow_seconds", s.slideshowSeconds)
            putString("subtitle_language", s.subtitleLanguage)
            putBoolean("auto_subtitles", s.autoSubtitles)
            putString("video_codec", s.videoCodec.name)
            putBoolean("microphone", s.microphone)
            putInt("mic_level", s.micLevel)
        }
        _state.value = s
        applyTheme()
    }

    /** Resets the casting options; the chosen theme is kept. */
    fun reset() = update {
        CastSettings(theme = it.theme, receiverAppId = it.receiverAppId, subtitleLanguage = it.subtitleLanguage, autoSubtitles = it.autoSubtitles)
    }

    /**
     * On Android 12+ the app's light/dark choice is handed to the system, so every
     * window (including dialogs and the splash screen) follows it.
     */
    fun applyTheme() {
        if (Build.VERSION.SDK_INT < 31) return
        val mode = when (current.theme) {
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        }
        try {
            val ui = appContext.getSystemService(UiModeManager::class.java)
            ui.setApplicationNightMode(mode)
        } catch (_: Exception) {
        }
    }

    /** Whether we already asked for the optional permissions once (we never nag). */
    var askedForPermissions: Boolean
        get() = prefs.getBoolean("asked_permissions", false)
        set(value) { prefs.edit { putBoolean("asked_permissions", value) } }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
}
