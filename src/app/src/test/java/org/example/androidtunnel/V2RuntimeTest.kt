package org.example.androidtunnel

import org.junit.Assert.*
import org.junit.Test

class V2RuntimeTest {
    @Test fun sixComponentsAreVerifiedIncludingAwgControlTool() {
        assertEquals(listOf("amneziawg-go","awg","sing-box","easytier-core","cloudflared","wstunnel"), ComponentAssets.names)
        val digest="83d457ce84a0120f1307f5cd8c725cbaa23b82569ca2037487fcf3a9095ac2e6"
        assertEquals(digest, ComponentAssets.parseManifest("$digest  awg")["awg"])
    }
    @Test fun detectsModifiedComponent() {
        val digest=ComponentAssets.sha256("asset".toByteArray())
        assertEquals(digest, ComponentAssets.parseManifest("$digest  amneziawg-go\ninvalid entry")["amneziawg-go"])
        assertNotEquals(digest,ComponentAssets.sha256("modified".toByteArray()))
    }
    @Test fun passiveAwgKeepsAliveWithoutDnsEndpoint() {
        val conf=ServiceConfigGenerator.amneziaWg(shared(),"C".repeat(43)+"=")
        assertTrue(conf.contains("ListenPort = 51820"))
        assertTrue(conf.contains("PersistentKeepalive = 25"))
        assertTrue(conf.contains("AllowedIPs = fd00:1::2/128, 10.23.0.2/32"))
        assertFalse(conf.contains("Endpoint ="))
    }
    @Test fun fallbackIsDataProxyAndNotSsh() {
        val config=ServiceConfigGenerator.singBox(shared(),"a".repeat(32))
        assertTrue(config.contains("\"type\":\"vless\"") && config.contains("\"type\":\"ws\""))
        assertTrue(config.contains("\"listen\":\"127.0.0.1\"") && config.contains("\"listen_port\":8443"))
        assertTrue(config.contains("\"outbound\":\"cf-probe\"") && config.contains("\"final\":\"direct\""))
        assertFalse(config.contains("wstunnel")); assertFalse(config.contains("hysteria2"))
    }
    @Test fun rootInjectionRejectedBeforeExecution() {
        assertThrows(IllegalArgumentException::class.java) { RootController.run("ip link add awg0; id") }
    }
    @Test fun directPreferredOverVerifiedFallback() {
        assertEquals(TunnelMode.DIRECT_IPV6,TunnelStateMachine.select(true,true,true))
    }
    @Test fun verifiedFallbackWhenDirectFails() {
        assertEquals(TunnelMode.FALLBACK,TunnelStateMachine.select(false,true,true))
    }
    @Test fun processRunningIsNotProofOfEgress() {
        assertEquals(TunnelMode.WAITING,TunnelStateMachine.select(false,false,true))
        assertEquals(TunnelMode.FAILED,TunnelStateMachine.select(false,false,false))
    }
    @Test fun handshakeMustBeFresh() {
        assertTrue(TunnelStateMachine.freshHandshake(900,1000))
        assertFalse(TunnelStateMachine.freshHandshake(800,1000))
        assertFalse(TunnelStateMachine.freshHandshake(0,1000))
        assertFalse(TunnelStateMachine.freshHandshake(1001,1000))
    }
    private fun update() = UpdateInfo("0.3.0",3,"https://example.net/app.apk",200,"a".repeat(64))
    @Test fun onlyHigherVersionCodePrompts() { assertTrue(update().validate().newerThan(2)); assertFalse(update().newerThan(3)); assertFalse(update().newerThan(4)) }
    @Test fun rejectsBadDigest() { assertThrows(IllegalArgumentException::class.java) { update().copy(sha256="abc").validate() } }
    @Test fun rejectsInvalidSize() { assertThrows(IllegalArgumentException::class.java) { update().copy(apkSize=0).validate() } }
    @Test fun rejectsInsecureUpdateUrl() { assertThrows(IllegalArgumentException::class.java) { update().copy(apkUrl="http://example.net/a.apk").validate() } }
}
