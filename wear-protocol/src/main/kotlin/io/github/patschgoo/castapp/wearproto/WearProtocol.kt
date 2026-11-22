package io.github.patschgoo.castapp.wearproto

import org.json.JSONException
import org.json.JSONObject

/** Wear Data Layer paths and capabilities shared by the phone app and the watch app. */
object WearPaths {
    /** Watch to phone: one [WearCommand]. */
    const val COMMAND = "/castapp/command"

    /** Watch to phone: "send me what is playing" (empty payload). */
    const val STATE_REQUEST = "/castapp/state/request"

    /** Phone to watch: a [NowPlaying]. */
    const val STATE = "/castapp/state"

    /** Declared by the phone app (res/values/wear.xml); the watch looks for phones with it. */
    const val CAPABILITY_PHONE = "castapp_phone"

    /** Declared by the watch app; the phone only sends state to watches that have it. */
    const val CAPABILITY_WATCH = "castapp_watch"
}

/** What the watch can ask the phone to do. [wire] is what goes over the air; never rename it. */
enum class WearCommand(val wire: String) {
    PLAY_PAUSE("play_pause"),
    PREVIOUS("previous"),
    NEXT("next"),
    VOLUME_UP("volume_up"),
    VOLUME_DOWN("volume_down"),
    STOP("stop"),

    /** Asks the phone to show its screen-capture prompt for the TV used last (Android needs a tap on the phone). */
    MIRROR_LAST("mirror_last"),
    ;

    companion object {
        fun fromWire(value: String?): WearCommand? = entries.firstOrNull { it.wire == value }
    }
}

/** What the watch shows. Sent by the phone whenever it changes (not for every playback tick). */
data class NowPlaying(
    /** A cast is running. */
    val active: Boolean = false,
    /** Name of the video / song, or empty (screen mirroring has none). */
    val title: String = "",
    /** Name of the TV that plays it. */
    val device: String = "",
    val paused: Boolean = false,
    /** Screen mirroring: play/pause, previous and next do nothing. */
    val mirroring: Boolean = false,
    /** Position in the play list, 0-based; [queueSize] is 1 for a single item. */
    val queueIndex: Int = 0,
    val queueSize: Int = 1,
    /** TV volume 0..100, or -1 when unknown. */
    val volumePercent: Int = -1,
    /** Name of the TV used last, for the "Mirror to ..." button; empty if none. */
    val lastTv: String = "",
) {
    val hasQueue: Boolean get() = queueSize > 1

    companion object {
        val IDLE = NowPlaying()
    }
}

/** Encoding of the Data Layer messages: small JSON objects with a version number. Unknown input decodes to null. */
object WearCodec {
    const val VERSION = 1

    /** Titles are cut here so a message stays far below the Data Layer limit. */
    const val MAX_TEXT = 200

    fun encodeCommand(command: WearCommand): ByteArray =
        JSONObject().put("v", VERSION).put("cmd", command.wire).toString().toByteArray(Charsets.UTF_8)

    fun decodeCommand(bytes: ByteArray?): WearCommand? {
        val o = parse(bytes) ?: return null
        if (o.optInt("v", 0) != VERSION) return null
        return WearCommand.fromWire(o.optString("cmd", ""))
    }

    fun encodeState(state: NowPlaying): ByteArray = JSONObject()
        .put("v", VERSION)
        .put("active", state.active)
        .put("title", state.title.take(MAX_TEXT))
        .put("device", state.device.take(MAX_TEXT))
        .put("paused", state.paused)
        .put("mirroring", state.mirroring)
        .put("qi", state.queueIndex)
        .put("qs", state.queueSize)
        .put("vol", state.volumePercent)
        .put("last", state.lastTv.take(MAX_TEXT))
        .toString()
        .toByteArray(Charsets.UTF_8)

    fun decodeState(bytes: ByteArray?): NowPlaying? {
        val o = parse(bytes) ?: return null
        if (o.optInt("v", 0) != VERSION || !o.has("active")) return null
        return NowPlaying(
            active = o.optBoolean("active"),
            title = o.optString("title", "").take(MAX_TEXT),
            device = o.optString("device", "").take(MAX_TEXT),
            paused = o.optBoolean("paused"),
            mirroring = o.optBoolean("mirroring"),
            queueIndex = o.optInt("qi", 0).coerceAtLeast(0),
            queueSize = o.optInt("qs", 1).coerceAtLeast(1),
            volumePercent = o.optInt("vol", -1).let { if (it < 0) -1 else it.coerceAtMost(100) },
            lastTv = o.optString("last", "").take(MAX_TEXT),
        )
    }

    private fun parse(bytes: ByteArray?): JSONObject? {
        if (bytes == null || bytes.isEmpty() || bytes.size > 16 * 1024) return null
        return try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: JSONException) {
            null
        }
    }
}
