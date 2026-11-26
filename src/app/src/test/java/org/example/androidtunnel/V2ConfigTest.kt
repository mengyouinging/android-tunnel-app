package org.example.androidtunnel

import org.junit.Assert.*
import org.junit.Test

internal fun shared() = SharedConfig(51820, "A".repeat(43)+"=", "B".repeat(43)+"=",
    listOf("fd00:1::2/128","10.23.0.2/32"), listOf("fd00:1::1/64","10.23.0.1/24"),
    "123e4567-e89b-12d3-a456-426614174000", "test-token-not-real", "cf.example.net")

class V2ConfigTest {
    @Test fun acceptsDualStackConfig() { assertEquals(51820, shared().validate().awgPort) }
    @Test fun rejectsWrongPort() { assertThrows(IllegalArgumentException::class.java) { shared().copy(awgPort=12345).validate() } }
    @Test fun rejectsPeerKeyMissing() { assertThrows(IllegalArgumentException::class.java) { shared().copy(peerPublicKey="").validate() } }
    @Test fun rejectsShellInCidr() { assertThrows(IllegalArgumentException::class.java) { V2.cidr("fd00::2/128;id") } }
    @Test fun rejectsIpv4PrefixTooLong() { assertThrows(IllegalArgumentException::class.java) { V2.cidr("10.0.0.2/128") } }
    @Test fun rejectsDefaultRoute() { assertThrows(IllegalArgumentException::class.java) { V2.cidr("::/0") } }
    @Test fun rejectsInvalidUuid() { assertThrows(IllegalArgumentException::class.java) { shared().copy(vlessUuid="bad").validate() } }
    @Test fun rejectsMissingToken() { assertThrows(IllegalArgumentException::class.java) { shared().copy(tunnelToken="").validate() } }
    @Test fun rejectsCredentialInUrl() { assertThrows(IllegalArgumentException::class.java) { V2.httpsUrl("https://user:pass@example.com") } }
    @Test fun rejectsNonHttpsRelay() { assertThrows(IllegalArgumentException::class.java) { RelaySettings("secret","http://example.com").validate() } }
    @Test fun rejectsEmptyPassword() { assertThrows(IllegalArgumentException::class.java) { V2.password("") } }
    @Test fun recognizesGlobalIpv6Only() { assertTrue(V2.globalIpv6("2606:4700::1111")); assertFalse(V2.globalIpv6("fd00::2")); assertFalse(V2.globalIpv6("fe80::1")); assertFalse(V2.globalIpv6("127.0.0.1")) }
    @Test fun stationsAreSeparate() { assertNotEquals(V2.httpsUrl(V2.CONFIG).host, V2.httpsUrl(V2.UPDATE).host) }
}
