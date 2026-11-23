package org.example.androidtunnel

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class ComponentAsset(val name: String, val sha256: String)

object ComponentAssets {
    val names = listOf("amneziawg-go", "awg", "sing-box", "easytier-core", "cloudflared", "wstunnel")

    fun parseManifest(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line ->
            val match = Regex("^([a-fA-F0-9]{64})\\s+([A-Za-z0-9._-]+)$").matchEntire(line.trim()) ?: return@mapNotNull null
            match.groupValues[2] to match.groupValues[1].lowercase()
        }.toMap()

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(file: File): String = FileInputStream(file).use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class ComponentInstallResult(val files: Map<String, File>, val missing: List<String>) {
    val ready: Boolean get() = missing.isEmpty() && files.size == ComponentAssets.names.size
}

class ComponentManager(private val context: Context) {
    fun prepare(): ComponentInstallResult {
        val out = File(context.filesDir, "components").apply { mkdirs() }
        val manifest = runCatching {
            context.assets.open("arm64/SHA256SUMS").bufferedReader().use { ComponentAssets.parseManifest(it.readText()) }
        }.getOrDefault(emptyMap())
        val files = linkedMapOf<String, File>()
        val missing = mutableListOf<String>()
        for (name in ComponentAssets.names) {
            val target = File(out, name)
            val valid = runCatching {
                val expected = manifest[name] ?: error("digest missing")
                val actual = context.assets.open("arm64/$name").use { stream ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                if (!actual.equals(expected, ignoreCase = true)) error("asset digest mismatch")
                if (!target.isFile || !ComponentAssets.sha256(target).equals(expected, true)) {
                    context.assets.open("arm64/$name").use { input -> target.outputStream().use(input::copyTo) }
                }
                target.setReadable(true, true)
                target.setExecutable(true, true)
                target.isFile && target.canExecute() && ComponentAssets.sha256(target).equals(expected, true)
            }.getOrDefault(false)
            if (valid) files[name] = target else missing += name
        }
        return ComponentInstallResult(files, missing)
    }
}

data class PeerConfig(
    val awgPrivateKey: String,
    val peerPublicKey: String,
    val peerEndpoint: String,
    val allowedIps: String,
    val interfaceAddress: String,
    val awgPort: Int,
    val jc: Int,
    val jmin: Int,
    val jmax: Int,
    val vlessUuid: String,
    val realityPrivateKey: String,
    val realityShortId: String,
    val realityServerName: String,
    val hysteriaPassword: String,
    val hysteriaCertificatePath: String,
    val hysteriaKeyPath: String,
    val easyTierNetwork: String,
    val easyTierSecret: String,
    val relay: String,
    val probeHost: String,
    val probePort: Int,
    val cloudflaredToken: String,
    val wstunnelRemote: String
) {
    fun validate() {
        require(awgPrivateKey.matches(Regex("[A-Za-z0-9+/]{43}=")) && peerPublicKey.matches(Regex("[A-Za-z0-9+/]{43}="))) { "AmneziaWG 私钥和 peer 公钥格式无效或待配置" }
        require(peerEndpoint.matches(Regex("[A-Za-z0-9.\\[\\]:-]+:[0-9]{1,5}"))) { "peer Endpoint 格式无效" }
        require(peerEndpoint.substringAfterLast(':').toIntOrNull()?.let { it in 1..65535 } == true) { "peer Endpoint 端口无效" }
        require(allowedIps.split(',').all { range ->
            val parts = range.trim().split('/')
            parts.size == 2 && parts[0].matches(Regex("[0-9A-Fa-f:.]+")) && parts[1].toIntOrNull()?.let { it in 0..128 } == true
        }) { "AllowedIPs 格式无效" }
        require(interfaceAddress.matches(Regex("[0-9A-Fa-f:]+/[0-9]{1,3}"))) { "隧道接口 IPv6 地址待配置" }
        require(awgPort in 1024..65535 && probePort in 1..65535) { "端口范围无效" }
        require(jc in 0..128 && jmin in 0..128 && jmax in 0..128 && (jc == 0 || jmin <= jmax)) { "Jc/Jmin/Jmax 范围无效" }
        require(vlessUuid.matches(Regex("[A-Fa-f0-9-]{36}")) && realityPrivateKey.matches(Regex("[A-Za-z0-9_+/-]{43}=?"))) { "VLESS UUID / Reality 私钥格式无效或待配置" }
        require(realityShortId.matches(Regex("[A-Fa-f0-9]{1,16}")) && realityServerName.matches(Regex("[A-Za-z0-9.-]{1,253}"))) { "Reality Short ID / ServerName 格式无效" }
        require(hysteriaPassword.isNotBlank() && hysteriaCertificatePath.startsWith('/') && hysteriaKeyPath.startsWith('/') &&
            File(hysteriaCertificatePath).isFile && File(hysteriaKeyPath).isFile) { "Hysteria2 密码/证书文件路径无效或待配置" }
        require(easyTierNetwork.matches(Regex("[A-Za-z0-9_.-]{1,64}")) && easyTierSecret.matches(Regex("[A-Za-z0-9_./+=-]{1,128}"))) { "EasyTier 网络名/密钥格式无效" }
        require(relay.isNotBlank() && relay.matches(Regex("[A-Za-z0-9.\\[\\]:-]+:[0-9]{1,5}"))) { "自建中继 VPS 地址待配置" }
        require(probeHost.matches(Regex("[A-Za-z0-9.\\[\\]:-]+"))) { "探测对端地址待配置" }
        require(cloudflaredToken.isNotBlank() && wstunnelRemote.matches(Regex("wss://[A-Za-z0-9.:-]{1,253}(:[0-9]{1,5})?(/[A-Za-z0-9_./-]*)?"))) { "应急 Tunnel 凭据/地址待配置" }
    }
}

object ServiceConfigGenerator {
    fun amneziaWg(config: PeerConfig): String {
        config.validate()
        return """[Interface]
PrivateKey = ${config.awgPrivateKey}
ListenPort = ${config.awgPort}
Jc = ${config.jc}
Jmin = ${config.jmin}
Jmax = ${config.jmax}

[Peer]
PublicKey = ${config.peerPublicKey}
AllowedIPs = ${config.allowedIps}
Endpoint = ${config.peerEndpoint}
PersistentKeepalive = 25
"""
    }

    fun singBox(config: PeerConfig): String {
        config.validate()
        val name = json(config.realityServerName)
        val uuid = json(config.vlessUuid)
        val privateKey = json(config.realityPrivateKey)
        val shortId = json(config.realityShortId)
        val password = json(config.hysteriaPassword)
        val certificate = json(config.hysteriaCertificatePath)
        val key = json(config.hysteriaKeyPath)
        return """{"log":{"level":"warn"},"inbounds":[{"type":"vless","tag":"vless-reality","listen":"::","listen_port":443,"users":[{"uuid":"$uuid","flow":"xtls-rprx-vision"}],"tls":{"enabled":true,"server_name":"$name","reality":{"enabled":true,"handshake":{"server":"$name","server_port":443},"private_key":"$privateKey","short_id":["$shortId"]}}},{"type":"hysteria2","tag":"hysteria2-fallback","listen":"::","listen_port":443,"users":[{"password":"$password"}],"tls":{"enabled":true,"certificate_path":"$certificate","key_path":"$key"}}],"outbounds":[{"type":"direct","tag":"direct"}]}"""
    }

    fun easyTier(config: PeerConfig): String {
        config.validate()
        return "--network-name ${config.easyTierNetwork}\n--network-secret ${config.easyTierSecret}\n--relay ${config.relay}\n--need-p2p\n--latency-first"
    }

    private fun json(value: String): String = buildString {
        value.forEach { c -> when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
        } }
    }
}

data class ProbeResult(val answered: Boolean, val latencyMs: Long?)

fun interface PeerProbe { fun probe(host: String, port: Int, timeoutMs: Int): ProbeResult }

class TcpPeerProbe : PeerProbe {
    override fun probe(host: String, port: Int, timeoutMs: Int): ProbeResult {
        val start = System.nanoTime()
        val answered = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), timeoutMs) } }.isSuccess
        return ProbeResult(answered, if (answered) (System.nanoTime() - start) / 1_000_000 else null)
    }
}

interface ComponentLauncher {
    fun start(name: String): Boolean
    fun stop(name: String)
}

class TunnelOrchestrator(private val launcher: ComponentLauncher) {
    fun startDirect(): Boolean {
        val started = mutableListOf<String>()
        for (name in listOf("amneziawg-go", "sing-box")) {
            if (!launcher.start(name)) {
                started.asReversed().forEach(launcher::stop)
                return false
            }
            started += name
        }
        return true
    }

    fun startFallback(): Boolean = launcher.start("easytier-core")

    fun startEmergency(): Boolean {
        if (!launcher.start("cloudflared")) return false
        if (!launcher.start("wstunnel")) {
            launcher.stop("cloudflared")
            return false
        }
        return true
    }

    fun stopDirect() = listOf("sing-box", "amneziawg-go").forEach(launcher::stop)
    fun stopFallback() = launcher.stop("easytier-core")
    fun stopEmergency() = listOf("wstunnel", "cloudflared").forEach(launcher::stop)

    fun stopAll() = listOf("wstunnel", "cloudflared", "easytier-core", "sing-box", "amneziawg-go").forEach(launcher::stop)
}
