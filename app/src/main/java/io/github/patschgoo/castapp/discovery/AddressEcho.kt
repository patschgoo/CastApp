package io.github.patschgoo.castapp.discovery

/**
 * Some Chromecasts answer the network search a second time over IPv6 with only their instance
 * name and a link-local address (fe80::…%wlan0), without the id that ties it to the IPv4 answer.
 * That listed the same TV twice ("Chromecast" at 192.168.x.y and at fe80::…). The IPv6 copy is the
 * worse one to cast to: Google's player on the TV can't open a stream address with a zone like
 * "%wlan0". This finds such copies so the list shows each TV once.
 */
object AddressEcho {
    /** Ids of devices that are only an IPv6 echo of a device with the same name and kind on IPv4. */
    fun echoes(devices: Collection<CastDevice>): Set<String> {
        val ipv4 = devices.filter { !isIpv6(it.host) }.map { it.kind to it.name.trim().lowercase() }.toSet()
        return devices.filter { d ->
            isIpv6(d.host) && d.id.startsWith("host:") && (d.kind to d.name.trim().lowercase()) in ipv4
        }.map { it.id }.toSet()
    }

    fun isIpv6(host: String) = host.contains(':')
}
