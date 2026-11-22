package io.github.patschgoo.castapp.automation

import io.github.patschgoo.castapp.discovery.CastDevice

sealed interface DeviceMatch {
    data class Found(val device: CastDevice) : DeviceMatch
    data class NotFound(val message: String) : DeviceMatch
}

/** Finds the TV a caller meant among the known devices. No Android code. */
object DeviceMatcher {
    /**
     * By id first, then by name: an exact name (any case), or else a name that contains the text
     * when only one does. Two TVs that match are an error, never a guess. Nothing given: [lastUsed].
     */
    fun resolve(devices: List<CastDevice>, ref: DeviceRef, lastUsed: CastDevice?): DeviceMatch {
        if (ref.isLast) {
            return lastUsed?.let { DeviceMatch.Found(it) }
                ?: DeviceMatch.NotFound("No TV given and none used yet: pass \"${AutomationApi.EXTRA_DEVICE}\"")
        }
        ref.id?.let { id ->
            return devices.firstOrNull { it.id == id }?.let { DeviceMatch.Found(it) } ?: DeviceMatch.NotFound("No TV with id \"$id\"")
        }
        val query = ref.name ?: ref.either!!
        if (ref.name == null) devices.firstOrNull { it.id == query }?.let { return DeviceMatch.Found(it) }
        devices.filter { it.name.equals(query, ignoreCase = true) }.let { exact ->
            if (exact.size == 1) return DeviceMatch.Found(exact.single())
            if (exact.size > 1) return ambiguous(query, exact)
        }
        val partial = devices.filter { it.name.contains(query, ignoreCase = true) }
        return when (partial.size) {
            0 -> DeviceMatch.NotFound("No TV named \"$query\"")
            1 -> DeviceMatch.Found(partial.single())
            else -> ambiguous(query, partial)
        }
    }

    private fun ambiguous(query: String, matches: List<CastDevice>) =
        DeviceMatch.NotFound("More than one TV matches \"$query\": ${matches.joinToString(", ") { it.name }}. Use \"${AutomationApi.EXTRA_DEVICE_ID}\"")
}
