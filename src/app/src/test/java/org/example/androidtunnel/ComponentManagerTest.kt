package org.example.androidtunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentManagerTest {
    private fun peer() = PeerConfig(
        awgPrivateKey = "A".repeat(43) + "=", peerPublicKey = "B".repeat(43) + "=", peerEndpoint = "peer.example:51820",
        allowedIps = "10.0.0.2/32", interfaceAddress = "fd00::2/64", awgPort = 51820, jc = 3, jmin = 10, jmax = 50,
        vlessUuid = "123e4567-e89b-12d3-a456-426614174000", realityPrivateKey = "C".repeat(43) + "=",
        realityShortId = "a1b2c3d4", realityServerName = "relay.example.net", hysteriaPassword = "hysteria-placeholder",
        hysteriaCertificatePath = java.io.File.createTempFile("tunnel-cert", ".crt").absolutePath,
        hysteriaKeyPath = java.io.File.createTempFile("tunnel-key", ".key").absolutePath,
        easyTierNetwork = "network-placeholder", easyTierSecret = "network-secret-placeholder", relay = "relay.example.net:11010",
        probeHost = "peer.example", probePort = 443, cloudflaredToken = "tunnel-token-placeholder", wstunnelRemote = "wss://relay.example.net"
    )

    @Test fun sixComponentsAreVerifiedIncludingAwgControlTool() {
        assertEquals(listOf("amneziawg-go", "awg", "sing-box", "easytier-core", "cloudflared", "wstunnel"), ComponentAssets.names)
        val manifest = ComponentAssets.parseManifest("83d457ce84a0120f1307f5cd8c725cbaa23b82569ca2037487fcf3a9095ac2e6  awg")
        assertEquals("83d457ce84a0120f1307f5cd8c725cbaa23b82569ca2037487fcf3a9095ac2e6", manifest["awg"])
    }

    @Test fun parsesAllReviewedDigestsAndDetectsModifiedContent() {
        val digest = ComponentAssets.sha256("asset".toByteArray())
        val manifest = ComponentAssets.parseManifest("$digest  amneziawg-go\ninvalid entry")
        assertEquals(digest, manifest["amneziawg-go"])
        assertFalse(ComponentAssets.sha256("modified".toByteArray()).equals(manifest["amneziawg-go"], true))
    }

    @Test fun generatesPeerDrivenConfigsWithoutSubstitutingCredentials() {
        val config = peer()
        val awg = ServiceConfigGenerator.amneziaWg(config)
        val singBox = ServiceConfigGenerator.singBox(config)
        val easyTier = ServiceConfigGenerator.easyTier(config)
        assertTrue(awg.contains("Jc = 3") && awg.contains("Endpoint = peer.example:51820"))
        assertTrue(singBox.contains("\"listen_port\":443") && singBox.contains(config.vlessUuid))
        assertTrue(singBox.contains(config.hysteriaCertificatePath))
        assertTrue(easyTier.contains("--need-p2p") && easyTier.contains("--latency-first"))
    }

    @Test fun missingPeerParametersBlockRuntimeConfig() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { peer().copy(peerPublicKey = "").validate() }
    }

    @Test fun rootCommandOutsideAllowlistIsRejectedBeforeExecution() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { RootController.run("ip link add awg0; id") }
    }

    @Test fun emergencyStartsOnlyAfterFallbackPathAndRollsBackPartialStart() {
        val calls = mutableListOf<String>()
        val launcher = object : ComponentLauncher {
            override fun start(name: String): Boolean { calls += "start:$name"; return name != "wstunnel" }
            override fun stop(name: String) { calls += "stop:$name" }
        }
        val orchestrator = TunnelOrchestrator(launcher)
        assertTrue(orchestrator.startDirect())
        assertTrue(orchestrator.startFallback())
        assertFalse(orchestrator.startEmergency())
        assertEquals(listOf("start:amneziawg-go", "start:sing-box", "start:easytier-core", "start:cloudflared", "start:wstunnel", "stop:cloudflared"), calls)
    }
}
