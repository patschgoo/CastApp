package io.github.patschgoo.castapp.support

import android.content.Context
import android.media.AudioManager
import android.os.Build

/**
 * What the phone is playing right now, as far as Android tells other apps: for the cast diary and the
 * debug report. Android doesn't say which app plays, only what kind of sound it is and whether it may
 * be recorded. An app that opts out in its manifest (or targets Android 9 or older) still shows
 * "may be recorded" here: then the capture simply stays silent.
 */
object SoundState {
    fun describe(context: Context): String = if (Build.VERSION.SDK_INT < 26) "unknown (Android 7)" else try {
        val am = context.getSystemService(AudioManager::class.java) ?: return "no audio service"
        val players = am.activePlaybackConfigurations.map { cfg ->
            val attrs = cfg.audioAttributes
            val policy = if (Build.VERSION.SDK_INT >= 29) attrs.allowedCapturePolicy else 0
            Player(attrs.usage, attrs.contentType, policy)
        }
        summary(am.isMusicActive, players, am.mode)
    } catch (e: Exception) {
        "unknown (${e.javaClass.simpleName})"
    }

    data class Player(val usage: Int, val contentType: Int, val capturePolicy: Int)

    /** Plain text, e.g. "music active; 1 player: media/movie (may be recorded)". */
    fun summary(musicActive: Boolean, players: List<Player>, mode: Int = 0): String = buildString {
        append(if (musicActive) "music active" else "no music playing")
        if (mode == 2 || mode == 3) append("; in a call") // MODE_IN_CALL, MODE_IN_COMMUNICATION
        append("; ${players.size} player")
        if (players.size != 1) append('s')
        if (players.isNotEmpty()) {
            append(": ")
            append(players.joinToString(", ") { p -> "${usageName(p.usage)}/${contentName(p.contentType)} (${policyName(p)})" })
        }
    }

    /** Only these usages can be captured by other apps at all (AudioPlaybackCapture). */
    fun capturableUsage(usage: Int) = usage == 0 || usage == 1 || usage == 14

    fun usageName(u: Int) = when (u) {
        0 -> "unknown"
        1 -> "media"
        2 -> "call"
        3 -> "call-signal"
        4 -> "alarm"
        5 -> "notification"
        6 -> "ringtone"
        11 -> "accessibility"
        12 -> "navigation"
        13 -> "system-sound"
        14 -> "game"
        16 -> "assistant"
        else -> "usage-$u"
    }

    private fun contentName(c: Int) = when (c) {
        1 -> "speech"
        2 -> "music"
        3 -> "movie"
        4 -> "sonification"
        else -> "unknown"
    }

    private fun policyName(p: Player) = when {
        !capturableUsage(p.usage) -> "never recordable"
        p.capturePolicy == 2 -> "app forbids recording"
        p.capturePolicy == 3 -> "app forbids recording"
        else -> "may be recorded"
    }
}
