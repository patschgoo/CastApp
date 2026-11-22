package io.github.patschgoo.castapp.ui

import io.github.patschgoo.castapp.discovery.CastDevice
import io.github.patschgoo.castapp.discovery.DeviceEntry

/** What a "play from this phone" tile lets the user choose in the system file picker. */
enum class FilePick { ANY, VIDEOS, PHOTOS, MUSIC }

/** The decisions of the main screen that don't need Android: which TV it casts to, which files a tile offers. */
object HomeLogic {

    /** The devices the picker lists: TVs, or in audio mode the speakers and speaker groups too. */
    fun listed(devices: List<DeviceEntry>, audioMode: Boolean): List<DeviceEntry> =
        if (audioMode) devices else devices.filter { it.device.video }

    /**
     * The device "Cast to" shows: the one used or picked last when the list still has it
     * (also while it isn't found right now, so a TV that is slow to answer stays one tap
     * away), otherwise the first one that is there. Null while nothing was found yet.
     */
    fun target(devices: List<DeviceEntry>, lastUsedId: String?, audioMode: Boolean): DeviceEntry? {
        val listed = listed(devices, audioMode)
        return listed.firstOrNull { it.device.id == lastUsedId } ?: listed.firstOrNull { it.online }
    }

    /** Same device: same id, or same address and port (a speaker group shares its IP with a speaker). */
    fun sameDevice(a: CastDevice, b: CastDevice): Boolean =
        a.id == b.id || (a.host == b.host && a.port == b.port)

    /** A tile that needs a picture can't play on a speaker. */
    fun needsPicture(pick: FilePick): Boolean = pick == FilePick.VIDEOS || pick == FilePick.PHOTOS

    /**
     * The file types the system picker offers. [picture] is false for speakers (and in audio
     * mode), which only take sound. Subtitle files may be picked together with videos.
     */
    fun mimeTypes(pick: FilePick, picture: Boolean): Array<String> = when {
        !picture -> arrayOf("audio/*")
        pick == FilePick.VIDEOS -> arrayOf("video/*", "application/x-subrip", "text/vtt", "application/octet-stream")
        pick == FilePick.PHOTOS -> arrayOf("image/*")
        pick == FilePick.MUSIC -> arrayOf("audio/*")
        else -> arrayOf("video/*", "audio/*", "image/*", "application/x-subrip", "text/vtt", "application/octet-stream")
    }
}

/**
 * "Recent on this phone": which permission lists the phone's videos on which Android
 * version. The row is optional; without the permission it only offers to turn itself on.
 */
object RecentRules {
    const val READ_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    const val READ_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val READ_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    /** What to ask for. On Android 14+ the answer may be "only these videos", which is fine too. */
    fun permissions(sdk: Int): Array<String> = when {
        sdk >= 34 -> arrayOf(READ_VIDEO, READ_SELECTED)
        sdk >= 33 -> arrayOf(READ_VIDEO)
        else -> arrayOf(READ_STORAGE)
    }

    /** Whether [granted] lets the app list videos: all of them, or the ones the user selected. */
    fun hasAccess(sdk: Int, granted: Set<String>): Boolean = when {
        sdk >= 34 -> READ_VIDEO in granted || READ_SELECTED in granted
        sdk >= 33 -> READ_VIDEO in granted
        else -> READ_STORAGE in granted
    }
}
