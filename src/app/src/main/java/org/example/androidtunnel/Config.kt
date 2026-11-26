package org.example.androidtunnel

import java.net.URI
import java.net.InetAddress
import java.net.Inet6Address
import java.util.UUID
import java.util.Base64

object V2 {
    const val RELAY = "https://tunnel-relay.cnnb.dpdns.org"
    const val CONFIG = "$RELAY/config"
    const val CF_HOST = "cf.cnnb.dpdns.org"
    const val UPDATE = "https://ppt-auto-bakeup.cnnb.dpdns.org/app/version.json"
    const val PORT = 51820
    const val ORIGIN_PORT = 8443
    const val WS_PATH = "/tunnel"
    const val METRICS_PORT = 20241
    const val PROBE_PORT = 10843
    const val PEER_PORT = 18445
    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    fun httpsUrl(value: String): URI = URI(value.trim()).also {
        require(it.scheme == "https" && it.host != null && it.rawUserInfo == null &&
            it.rawQuery == null && it.rawFragment == null) { "仅支持无口令、查询参数或片段的 HTTPS 地址" }
    }
    fun password(value: String): String = value.trim().also {
        require(it.length in 1..256 && it.none(Char::isISOControl)) { "请输入中转站口令" }
    }
    fun key(value: String): Boolean = runCatching {
        Base64.getDecoder().decode(value).size == 32 && value.matches(Regex("[A-Za-z0-9+/]{43}="))
    }.getOrDefault(false)

    fun cidr(value: String): String {
        val parts = value.trim().split('/')
        require(parts.size == 2 && parts[0].matches(Regex("[0-9A-Fa-f:.]+")) &&
            (parts[0].contains(':') || parts[0].matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")))) { "地址分配格式无效" }
        val ip = InetAddress.getByName(parts[0])
        val bits = parts[1].toIntOrNull() ?: throw IllegalArgumentException("CIDR 前缀无效")
        require(bits in 1..(ip.address.size * 8)) { "禁止默认路由或非法 CIDR" }
        return value.trim()
    }
    fun globalIpv6(value: String): Boolean = runCatching {
        require(value.contains(':') && value.matches(Regex("[0-9A-Fa-f:.]+")))
        val ip = InetAddress.getByName(value)
        ip is Inet6Address && value.contains(':') && !value.contains('%') &&
            (ip.address[0].toInt() and 0xe0) == 0x20 && !ip.isMulticastAddress &&
            !value.lowercase().startsWith("2001:db8:")
    }.getOrDefault(false)
    fun uuid(value: String): String = UUID.fromString(value).toString().also {
        require(it.equals(value, ignoreCase = true)) { "共享 UUID 无效" }
    }
}

data class RelaySettings(val password: String, val relay: String = V2.RELAY, val config: String = V2.CONFIG) {
    fun validate(): RelaySettings {
        V2.password(password)
        V2.httpsUrl(relay)
        V2.httpsUrl(config)
        return this
    }
}

data class SharedConfig(
    val awgPort: Int, val peerPublicKey: String, val phonePublicKey: String,
    val allowedIps: List<String>, val interfaceAddresses: List<String>, val vlessUuid: String,
    val tunnelToken: String, val cfHost: String, val realityPublicKey: String = "",
    val realityShortId: String = "", val hysteriaPassword: String = "",
    val easyTierNetwork: String = "", val easyTierSecret: String = "",
    val relay: String = "", val wstunnelRemote: String = "", val domain: String = "",
    val v6Host: String = ""
) {
    fun validate(): SharedConfig {
        require(awgPort == V2.PORT) { "中转站端口必须为 51820" }
        require(V2.key(peerPublicKey)) { "控制端公钥无效" }
        require(phonePublicKey.isBlank() || V2.key(phonePublicKey)) { "手机公钥无效" }
        require(allowedIps.isNotEmpty() && interfaceAddresses.isNotEmpty()) { "共享地址分配缺失" }
        (allowedIps + interfaceAddresses).forEach { V2.cidr(it) }
        V2.uuid(vlessUuid)
        require(tunnelToken.isNotBlank() && tunnelToken.length <= 8192 && tunnelToken.none(Char::isWhitespace)) { "中转站未提供有效 Tunnel Token" }
        require(cfHost.matches(Regex("[A-Za-z0-9.-]{1,253}")) && cfHost.contains('.')) { "Cloudflare 保底主机名无效" }
        return this
    }
}

object ServiceConfigGenerator {
    fun amneziaWg(config: SharedConfig, privateKey: String): String {
        config.validate()
        require(V2.key(privateKey)) { "本机私钥无效" }
        // Passive server: moyu initiates to the registered address. No DNS Endpoint.
        return """[Interface]
PrivateKey = $privateKey
ListenPort = ${V2.PORT}
Jc = 3
Jmin = 10
Jmax = 50

[Peer]
PublicKey = ${config.peerPublicKey}
AllowedIPs = ${config.allowedIps.joinToString(", ")}
PersistentKeepalive = 25
"""
    }
    fun singBox(config: SharedConfig, probePassword: String): String {
        config.validate()
        val uuid = V2.uuid(config.vlessUuid)
        require(probePassword.matches(Regex("[a-f0-9]{32}")))
        // Cloudflare terminates TLS. Local HTTP/WebSocket origin with UUID authentication.
        return """{"log":{"level":"warn"},"inbounds":[{"type":"vless","tag":"cf-data","listen":"127.0.0.1","listen_port":${V2.ORIGIN_PORT},"users":[{"uuid":"$uuid"}],"transport":{"type":"ws","path":"${V2.WS_PATH}"}},{"type":"socks","tag":"self-probe","listen":"127.0.0.1","listen_port":${V2.PROBE_PORT},"users":[{"username":"probe","password":"$probePassword"}]}],"outbounds":[{"type":"direct","tag":"direct"},{"type":"vless","tag":"cf-probe","server":"${config.cfHost}","server_port":443,"uuid":"$uuid","tls":{"enabled":true,"server_name":"${config.cfHost}"},"transport":{"type":"ws","path":"${V2.WS_PATH}","headers":{"Host":"${config.cfHost}"}}}],"route":{"rules":[{"inbound":["self-probe"],"action":"route","outbound":"cf-probe"}],"final":"direct"}}"""
    }
}

data class UpdateInfo(val versionName: String, val versionCode: Long, val apkUrl: String, val apkSize: Long, val sha256: String) {
    fun validate(): UpdateInfo {
        require(versionName.isNotBlank() && versionName.length <= 80 && versionName.none(Char::isISOControl))
        require(versionCode in 1..Int.MAX_VALUE.toLong())
        val uri = URI(apkUrl)
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null)
        require(apkSize in 1..536870912L)
        require(sha256.matches(Regex("[A-Fa-f0-9]{64}")))
        return this
    }
    fun newerThan(installed: Long) = versionCode > installed
}
