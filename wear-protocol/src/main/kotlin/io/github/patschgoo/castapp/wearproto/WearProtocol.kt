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

    /** Volume up or down: the only commands a watch may send with a repeat count (see [WearMessage.Command]). */
    val isVolumeStep: Boolean get() = this == VOLUME_UP || this == VOLUME_DOWN

    companion object {
        fun fromWire(value: String?): WearCommand? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One message from the watch to the phone on [WearPaths.COMMAND].
 *
 * Phones before [WearCodec.REVISION_VOLUME_LEVELS] only know plain [Command]s: they do one step for
 * a repeated volume command and ignore [SetVolume], so the watch sends those only to a phone whose
 * [NowPlaying.revision] says it understands them.
 */
sealed interface WearMessage {
    /**
     * A button. VOLUME_UP and VOLUME_DOWN may carry [repeat] > 1: that many steps at once, for a fast
     * turn of the bezel or crown. Other commands always have 1.
     */
    data class Command(val command: WearCommand, val repeat: Int = 1) : WearMessage

    /** Sets the TV volume to [percent] (0..100). */
    data class SetVolume(val percent: Int) : WearMessage
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
    /**
     * What the phone app understands ([WearCodec.REVISION]). A phone that does not send it (CastApp
     * 4.0 and older) is 1: only plain button commands.
     */
    val revision: Int = 1,
) {
    val hasQueue: Boolean get() = queueSize > 1

    companion object {
        val IDLE = NowPlaying()
    }
}

/** Encoding of the Data Layer messages: small JSON objects with a version number. Unknown input decodes to null. */
object WearCodec {
    /**
     * Format version: a message of another one is ignored. New fields are added without changing it
     * (old readers skip what they do not know); [REVISION] says which of them a phone understands.
     */
    const val VERSION = 1

    /** What this build understands; the phone sends it in [NowPlaying.revision]. */
    const val REVISION = 2

    /** First revision that understands [WearMessage.SetVolume] and repeated volume steps. */
    const val REVISION_VOLUME_LEVELS = 2

    /** Most volume steps in one [WearMessage.Command] (5 % each on the phone). */
    const val MAX_REPEAT = 10

    /** Wire name of [WearMessage.SetVolume]; not a [WearCommand], so older phones ignore it. */
    const val CMD_SET_VOLUME = "volume_set"

    /** Titles are cut here so a message stays far below the Data Layer limit. */
    const val MAX_TEXT = 200

    fun encodeCommand(command: WearCommand): ByteArray = encodeMessage(WearMessage.Command(command))

    /** Only the button: what a phone before [REVISION_VOLUME_LEVELS] reads from a message. */
    fun decodeCommand(bytes: ByteArray?): WearCommand? = (decodeMessage(bytes) as? WearMessage.Command)?.command

    fun encodeMessage(message: WearMessage): ByteArray {
        val o = JSONObject().put("v", VERSION)
        when (message) {
            is WearMessage.Command -> {
                o.put("cmd", message.command.wire)
                val n = message.repeat.coerceIn(1, MAX_REPEAT)
                if (message.command.isVolumeStep && n > 1) o.put("n", n)
            }
            is WearMessage.SetVolume -> o.put("cmd", CMD_SET_VOLUME).put("level", message.percent.coerceIn(0, 100))
        }
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeMessage(bytes: ByteArray?): WearMessage? {
        val o = parse(bytes) ?: return null
        if (o.optInt("v", 0) != VERSION) return null
        val cmd = o.opt("cmd") as? String ?: return null
        if (cmd == CMD_SET_VOLUME) {
            // A level that is missing or out of range is dropped rather than guessed: it sets a TV's volume.
            val level = o.opt("level") as? Number ?: return null
            val percent = level.toDouble()
            if (percent.isNaN() || percent < 0.0 || percent > 100.0) return null
            return WearMessage.SetVolume(Math.round(percent).toInt())
        }
        val command = WearCommand.fromWire(cmd) ?: return null
        val repeat = if (command.isVolumeStep) (o.opt("n") as? Number)?.toInt()?.coerceIn(1, MAX_REPEAT) ?: 1 else 1
        return WearMessage.Command(command, repeat)
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
        .put("rev", state.revision)
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
            revision = o.optInt("rev", 1).coerceAtLeast(1),
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
