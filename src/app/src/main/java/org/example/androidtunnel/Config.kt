package org.example.androidtunnel

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Inet4Address

data class TunnelConfig(val domain: String, val token: String, val port: Int, val relay: String, val edgeIp: String) {
    fun validate() {
        val cleanDomain = domain.trim().trimEnd('.')
        require(cleanDomain.isNotEmpty()) { "域名不能为空" }
        val normalized = try { IDN.toASCII(cleanDomain, IDN.USE_STD3_ASCII_RULES) } catch (_: IllegalArgumentException) { "" }
        require(normalized.length in 4..253 && normalized.contains('.') && normalized.split('.').all {
            it.length in 1..63 && it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
        }) { "域名格式无效" }
        require(token == token.trim() && token.matches(Regex("[A-Za-z0-9_=-]{20,256}"))) { "Cloudflare Token 格式无效" }
        require(port in 1024..65535) { "端口必须为 1024–65535" }
        require(relay.isBlank() || validRelay(relay)) { "中继地址格式无效" }
        require(edgeIp.isBlank() || validIpLiteral(edgeIp)) { "优选 IP 格式无效" }
    }

    fun normalizedDomain(): String = IDN.toASCII(domain.trim().trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase()
    fun ddnsName(): String = "v6.${normalizedDomain()}"

    private fun validRelay(value: String): Boolean {
        if (value.length > 255 || value.any { it.isWhitespace() || it == ';' || it == '$' || it == '`' }) return false
        val host: String
        val portPart: String?
        if (value.startsWith('[')) {
            val end = value.indexOf(']')
            if (end < 0) return false
            host = value.substring(1, end)
            portPart = value.substring(end + 1).removePrefix(":").takeIf { value.length > end + 1 }
            if (!validIpLiteral(host) || (value.length > end + 1 && !value.substring(end + 1).startsWith(':'))) return false
        } else {
            val split = value.lastIndexOf(':')
            host = if (split > 0 && value.substring(split + 1).all(Char::isDigit)) value.substring(0, split) else value
            portPart = if (host != value) value.substring(split + 1) else null
            if (!host.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?"))) return false
        }
        return portPart == null || portPart.toIntOrNull()?.let { it in 1..65535 } == true
    }

    private fun validIpLiteral(value: String): Boolean = runCatching {
        val address = InetAddress.getByName(value)
        address is Inet6Address && value.contains(':') || address is Inet4Address && value.matches(Regex("[0-9.]+"))
    }.getOrDefault(false)
}
