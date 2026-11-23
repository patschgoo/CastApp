package io.github.patschgoo.castapp.discovery

/**
 * Mirroring delay by player: Google Cast's standard player keeps 1 to 3 seconds of picture in
 * reserve, CastApp TV about 0.3 seconds. The same Android TV box can show up as both.
 */
object MirrorLatency {
    /** CastApp TV on the same box as Google Cast device [current], if it answers; null otherwise. */
    fun fasterTwin(current: CastDevice, online: List<CastDevice>): CastDevice? {
        if (current.kind != DeviceKind.CAST || !current.video) return null
        return online.firstOrNull { it.isCastAppTv && it.host == current.host }
    }

    /**
     * Google Cast device [device] is an Android TV / Google TV box, which can run CastApp TV
     * (an NVIDIA Shield, a Google TV Streamer, a TV with Android TV built in). Plain Chromecasts
     * announce themselves as "Chromecast", the same as the Chromecast with Google TV, so they don't count.
     */
    fun couldRunCastAppTv(device: CastDevice): Boolean {
        if (device.kind != DeviceKind.CAST || !device.video) return false
        val model = device.model ?: return false
        return ANDROID_TV_MODELS.any { model.contains(it, ignoreCase = true) }
    }

    private val ANDROID_TV_MODELS = listOf("SHIELD", "Android TV", "Google TV", "BRAVIA", "MiBOX", "Mi Box")
}
