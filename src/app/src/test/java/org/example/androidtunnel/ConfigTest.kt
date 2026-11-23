package org.example.androidtunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConfigTest {
    private fun valid() = TunnelConfig("example.net", "A".repeat(40), 32876, "relay.example.net:11010", "2001:db8::1")

    @Test fun acceptsConstrainedConfiguration() { valid().validate() }
    @Test fun rejectsShellMetacharactersInDomain() {
        assertThrows(IllegalArgumentException::class.java) { valid().copy(domain = "example.net;id").validate() }
    }
    @Test fun rejectsPrivilegedPorts() {
        assertThrows(IllegalArgumentException::class.java) { valid().copy(port = 443).validate() }
    }
    @Test fun rejectsMalformedToken() {
        assertThrows(IllegalArgumentException::class.java) { valid().copy(token = "short").validate() }
    }
    @Test fun rejectsInvalidRelayCharacters() {
        assertThrows(IllegalArgumentException::class.java) { valid().copy(relay = "relay;id").validate() }
    }
    @Test fun rejectsOutOfRangeRelayPort() {
        assertThrows(IllegalArgumentException::class.java) { valid().copy(relay = "relay.example.net:99999").validate() }
    }
    @Test fun rejectsHostnameAsEdgeIp() {
        assertThrows(IllegalArgumentException::class.java) { valid().copy(edgeIp = "edge.example.net").validate() }
    }
    @Test fun derivesDdnsHostFromNormalizedDomain() {
        assertEquals("v6.example.net", TunnelConfig("Example.NET.", "A".repeat(40), 32876, "", "").ddnsName())
    }
}
