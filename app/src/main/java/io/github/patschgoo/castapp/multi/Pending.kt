package io.github.patschgoo.castapp.multi

import io.github.patschgoo.castapp.discovery.CastDevice
import java.util.concurrent.ConcurrentHashMap

/** Where a file taken over from another device carries on (read once when it starts playing). */
object PendingStart {
    private val positions = ConcurrentHashMap<String, Long>()

    fun set(uri: String, positionMs: Long) {
        positions.clear()
        if (positionMs > 0) positions[uri] = positionMs
    }

    fun take(uri: String): Long? = positions.remove(uri)
}

/** More screens for the cast that is about to start (a hand-over of a cast on several screens). */
object PendingScreens {
    @Volatile private var screens: List<CastDevice> = emptyList()

    fun set(devices: List<CastDevice>) {
        screens = devices
    }

    fun take(): List<CastDevice> = screens.also { screens = emptyList() }
}
