package io.github.patschgoo.castapp.settings

import android.content.Context
import androidx.core.content.edit
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Resolution(val width: Int, val height: Int, val label: String) {
    P540(960, 540, "540p (saves data)"),
    P720(1280, 720, "720p HD"),
    P1080(1920, 1080, "1080p Full HD"),
}

enum class StreamMode(val label: String, val description: String) {
    LOW_LATENCY("Low latency", "Fastest response. Falls back to Compatible automatically if the TV can't play it."),
    COMPATIBLE("Compatible (HLS)", "Works with every Cast receiver, but lags a few seconds behind."),
}

data class CastSettings(
    val resolution: Resolution = Resolution.P720,
    val fps: Int = 30,
    /** 0 = automatic. */
    val bitrateMbps: Int = 0,
    val audio: Boolean = Build.VERSION.SDK_INT >= 29,
    val streamMode: StreamMode = StreamMode.LOW_LATENCY,
    val port: Int = 8090,
) {
    val effectiveBitrate: Int
        get() {
            if (bitrateMbps > 0) return bitrateMbps * 1_000_000
            val base = when (resolution) {
                Resolution.P540 -> 2_500_000
                Resolution.P720 -> 4_500_000
                Resolution.P1080 -> 8_000_000
            }
            return if (fps > 30) base * 3 / 2 else base
        }
}

/** Simple settings stored in SharedPreferences and exposed as a StateFlow for the UI. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
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
        }
        _state.value = s
    }

    fun reset() = update { CastSettings() }

    /** Whether we already asked for the optional permissions once (we never nag). */
    var askedForPermissions: Boolean
        get() = prefs.getBoolean("asked_permissions", false)
        set(value) { prefs.edit { putBoolean("asked_permissions", value) } }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
}
