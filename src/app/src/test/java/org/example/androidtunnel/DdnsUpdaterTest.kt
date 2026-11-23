package org.example.androidtunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DdnsUpdaterTest {
    @Test fun createsOrUpdatesOnlyWhenIpv6Changes() {
        var address: String? = "2001:db8::1"
        val calls = mutableListOf<String>()
        val api = object : DnsApi {
            override fun upsertAaaa(token: String, domain: String, name: String, address: String): CloudflareClient.DnsRecord {
                calls += "$name=$address"
                return CloudflareClient.DnsRecord("record", address, 60)
            }
        }
        val updater = DdnsUpdater(api, Ipv6AddressProvider { address }, "A".repeat(40), "example.net")
        assertEquals("2001:db8::1", updater.refresh()?.content)
        assertNull(updater.refresh())
        address = "2001:db8::2"
        assertEquals("2001:db8::2", updater.refresh()?.content)
        assertEquals(listOf("v6.example.net=2001:db8::1", "v6.example.net=2001:db8::2"), calls)
    }

    @Test fun forceRefreshStillRequiresAddress() {
        val updater = DdnsUpdater(object : DnsApi {
            override fun upsertAaaa(token: String, domain: String, name: String, address: String) = error("must not call API")
        }, Ipv6AddressProvider { null }, "A".repeat(40), "example.net")
        assertNull(updater.refresh(force = true))
    }
}
