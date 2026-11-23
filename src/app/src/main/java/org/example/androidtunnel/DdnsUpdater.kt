package org.example.androidtunnel

import java.net.Inet6Address
import java.net.NetworkInterface

fun interface Ipv6AddressProvider { fun currentGlobalIpv6(): String? }

class SystemIpv6AddressProvider : Ipv6AddressProvider {
    override fun currentGlobalIpv6(): String? = NetworkInterface.getNetworkInterfaces()?.asSequence()
        ?.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        ?.flatMap { it.inetAddresses.asSequence() }
        ?.filterIsInstance<Inet6Address>()
        ?.mapNotNull { it.hostAddress?.substringBefore('%') }
        ?.firstOrNull { address ->
            val first = address.substringBefore(':').toIntOrNull(16) ?: return@firstOrNull false
            first in 0x2000..0x3fff
        }
}

class DdnsUpdater(
    private val api: DnsApi,
    private val addresses: Ipv6AddressProvider,
    private val token: String,
    private val domain: String,
) {
    @Volatile private var lastPublished: String? = null

    fun refresh(force: Boolean = false): CloudflareClient.DnsRecord? {
        val address = addresses.currentGlobalIpv6() ?: return null
        if (!force && address == lastPublished) return null
        val config = TunnelConfig(domain, token, 1024, "", "")
        config.validate()
        val result = api.upsertAaaa(token, config.normalizedDomain(), config.ddnsName(), address)
        lastPublished = address
        return result
    }
}
