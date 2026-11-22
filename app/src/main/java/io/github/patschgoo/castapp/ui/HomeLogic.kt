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
