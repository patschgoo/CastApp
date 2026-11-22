package io.github.patschgoo.castapp.support

enum class Transport { WIFI, ETHERNET, OTHER_LOCAL, CELLULAR, NONE }

enum class Severity { PROBLEM, WARNING, INFO, OK }

/** What a finding is about; the screen turns each code into an explanation in plain words. */
enum class FindingCode {
    NO_NETWORK,
    MOBILE_DATA,
    NO_LOCAL_ADDRESS,
    VPN_ACTIVE,
    ROUTER_SILENT,
    NOTHING_ANSWERED,
    MDNS_SILENT,
    SSDP_SILENT,
    DEVICES_FOUND,
    SAVED_UNREACHABLE,
    SAVED_OTHER_SUBNET,
    SAVED_REACHABLE_NOT_FOUND,
    DATA_SAVER,
    BATTERY_SAVER,
    ALL_GOOD,
}

/** One result of the network check. [detail] is extra text such as TV names or an address. */
data class Finding(val code: FindingCode, val severity: Severity, val detail: String? = null)

/** A remembered TV and whether its port answered. */
data class SavedTvCheck(val name: String, val host: String, val reachable: Boolean)

/** Raw measurements of the network check, with no interpretation yet. */
data class NetworkMeasurements(
    val transport: Transport,
    val vpnActive: Boolean = false,
    /** The phone's IPv4 address on the local network, e.g. "192.168.1.23". */
    val phoneAddress: String? = null,
    val prefixLength: Int = 24,
    /** null when there is no router address to ask. */
    val routerAnswered: Boolean? = null,
    val mdnsReplies: Int = 0,
    val ssdpReplies: Int = 0,
    /** Names of the devices that answered or were found while checking. */
    val foundDevices: List<String> = emptyList(),
    val savedTvs: List<SavedTvCheck> = emptyList(),
    val dataSaver: Boolean = false,
    val batterySaver: Boolean = false,
)

/** Turns measurements into findings. Pure, so it can be tested without a phone. */
object NetworkCheck {
    fun analyze(m: NetworkMeasurements): List<Finding> {
        val out = ArrayList<Finding>()

        when (m.transport) {
            Transport.NONE -> {
                out += Finding(FindingCode.NO_NETWORK, Severity.PROBLEM)
                return out
            }
            Transport.CELLULAR -> {
                out += Finding(FindingCode.MOBILE_DATA, Severity.PROBLEM)
                return out
            }
            else -> Unit
        }
        if (m.phoneAddress == null) {
            out += Finding(FindingCode.NO_LOCAL_ADDRESS, Severity.PROBLEM)
            if (m.vpnActive) out += Finding(FindingCode.VPN_ACTIVE, Severity.WARNING)
            return out
        }
        if (m.vpnActive) out += Finding(FindingCode.VPN_ACTIVE, Severity.WARNING)
        if (m.routerAnswered == false) out += Finding(FindingCode.ROUTER_SILENT, Severity.WARNING, m.phoneAddress)

        val anyReply = m.mdnsReplies > 0 || m.ssdpReplies > 0
        if (m.foundDevices.isNotEmpty()) {
            out += Finding(FindingCode.DEVICES_FOUND, Severity.OK, m.foundDevices.joinToString(", "))
        } else if (!anyReply) {
            out += Finding(FindingCode.NOTHING_ANSWERED, Severity.PROBLEM)
        }
        if (anyReply || m.foundDevices.isNotEmpty()) {
            if (m.mdnsReplies == 0) out += Finding(FindingCode.MDNS_SILENT, Severity.INFO)
            if (m.ssdpReplies == 0) out += Finding(FindingCode.SSDP_SILENT, Severity.INFO)
        }

        val foundNames = m.foundDevices.toSet()
        val unreachable = m.savedTvs.filter { !it.reachable }
        val otherSubnet = unreachable.filter { !sameSubnet(it.host, m.phoneAddress, m.prefixLength) }
        val offline = unreachable - otherSubnet.toSet()
        if (otherSubnet.isNotEmpty()) {
            out += Finding(FindingCode.SAVED_OTHER_SUBNET, Severity.WARNING, otherSubnet.joinToString(", ") { it.name })
        }
        if (offline.isNotEmpty()) {
            out += Finding(FindingCode.SAVED_UNREACHABLE, Severity.WARNING, offline.joinToString(", ") { it.name })
        }
        val silentButThere = m.savedTvs.filter { it.reachable && it.name !in foundNames }
        if (silentButThere.isNotEmpty() && !anyReply) {
            out += Finding(FindingCode.SAVED_REACHABLE_NOT_FOUND, Severity.WARNING, silentButThere.joinToString(", ") { it.name })
        }

        if (m.dataSaver) out += Finding(FindingCode.DATA_SAVER, Severity.INFO)
        if (m.batterySaver) out += Finding(FindingCode.BATTERY_SAVER, Severity.INFO)

        if (out.none { it.severity == Severity.PROBLEM || it.severity == Severity.WARNING } && m.foundDevices.isNotEmpty()) {
            out += Finding(FindingCode.ALL_GOOD, Severity.OK)
        }
        return out.sortedBy { it.severity.ordinal }
    }

    /** True when [host] and [phone] (IPv4 text) are in the same subnet of [prefixLength] bits. */
    fun sameSubnet(host: String, phone: String, prefixLength: Int): Boolean {
        val a = ipv4(host) ?: return true // a name, not an address: can't tell, don't complain
        val b = ipv4(phone) ?: return true
        val prefix = prefixLength.coerceIn(8, 30)
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (a and mask) == (b and mask)
    }

    private fun ipv4(text: String): Long? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }
}
