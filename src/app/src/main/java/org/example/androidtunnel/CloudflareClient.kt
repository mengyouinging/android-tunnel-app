package org.example.androidtunnel

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

interface CloudflareTransport {
    fun request(token: String, method: String, path: String, body: JSONObject? = null): JSONObject
}

interface DnsApi {
    fun upsertAaaa(token: String, domain: String, name: String, address: String): CloudflareClient.DnsRecord
}

class HttpCloudflareTransport : CloudflareTransport {
    override fun request(token: String, method: String, path: String, body: JSONObject?): JSONObject {
        val connection = (URL("https://api.cloudflare.com/client/v4$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val json = stream?.bufferedReader()?.use { it.readText() }?.let(::JSONObject) ?: JSONObject()
            if (code !in 200..299 || !json.optBoolean("success")) {
                val errors = json.optJSONArray("errors") ?: JSONArray()
                val detail = (0 until errors.length()).joinToString("; ") {
                    val error = errors.optJSONObject(it) ?: JSONObject()
                    "${error.optString("code")}: ${error.optString("message")}".trim(':', ' ')
                }.ifBlank { "HTTP $code" }
                throw CloudflareApiException(detail)
            }
            json
        } finally { connection.disconnect() }
    }
}

class CloudflareApiException(message: String) : IllegalStateException(message)

class CloudflareClient(private val transport: CloudflareTransport = HttpCloudflareTransport()) : DnsApi {
    data class Zone(val id: String, val name: String)
    data class DnsRecord(val id: String, val content: String, val ttl: Int)

    fun verifyDnsEdit(token: String, domain: String): String {
        validateTokenFormat(token)
        val zoneName = TunnelConfig(domain, token, 1024, "", "").normalizedDomain().substringAfter('.', "")
        require(zoneName.isNotBlank()) { "域名格式无效" }
        try {
            findZone(token, zoneName)
        } catch (e: CloudflareApiException) {
            throw IllegalStateException("Token 无法读取 zone；请授予该 zone 的 Zone:Read 与 DNS:Edit。Cloudflare: ${e.message}", e)
        }
        return "Token 可访问 $zoneName；请确认权限包含 Zone:Read 与 DNS:Edit"
    }

    override fun upsertAaaa(token: String, domain: String, name: String, address: String): DnsRecord {
        validateTokenFormat(token)
        val config = TunnelConfig(domain, token, 1024, "", "")
        config.validate()
        require(name == "v6.${config.normalizedDomain()}" || name == config.normalizedDomain()) { "仅允许更新当前域名及 v6 子域名" }
        require(address.length <= 45 && address.contains(':') && runCatching { java.net.InetAddress.getByName(address) is java.net.Inet6Address }.getOrDefault(false)) { "公网 IPv6 地址无效" }
        val zoneName = config.normalizedDomain().substringAfter('.', "")
        val zone = try { findZone(token, zoneName) } catch (e: CloudflareApiException) {
            throw IllegalStateException("zone 查询失败；需要 $zoneName 的 Zone:Read 和 DNS:Edit 权限。${e.message}", e)
        }
        val q = URLEncoder.encode(name, Charsets.UTF_8.name())
        val records = transport.request(token, "GET", "/zones/${zone.id}/dns_records?type=AAAA&name=$q").optJSONArray("result") ?: JSONArray()
        val body = JSONObject().put("type", "AAAA").put("name", name).put("content", address).put("ttl", 60).put("proxied", false)
        if (records.length() > 0) {
            val existing = records.optJSONObject(0) ?: error("Cloudflare 返回的 DNS 记录格式无效")
            val result = transport.request(token, "PUT", "/zones/${zone.id}/dns_records/${existing.getString("id")}", body).getJSONObject("result")
            return DnsRecord(result.getString("id"), result.getString("content"), result.optInt("ttl", 60))
        }
        val result = transport.request(token, "POST", "/zones/${zone.id}/dns_records", body).getJSONObject("result")
        return DnsRecord(result.getString("id"), result.getString("content"), result.optInt("ttl", 60))
    }

    private fun findZone(token: String, zoneName: String): Zone {
        val q = URLEncoder.encode(zoneName, Charsets.UTF_8.name())
        val zones = transport.request(token, "GET", "/zones?name=$q&status=active").optJSONArray("result") ?: JSONArray()
        val zone = (0 until zones.length()).mapNotNull { zones.optJSONObject(it) }
            .firstOrNull { it.optString("name").equals(zoneName, ignoreCase = true) }
            ?: throw CloudflareApiException("未找到可访问的 zone: $zoneName")
        return Zone(zone.getString("id"), zone.getString("name"))
    }

    companion object {
        fun validateTokenFormat(token: String) {
            require(token == token.trim() && token.matches(Regex("[A-Za-z0-9_=-]{20,256}"))) { "Cloudflare Token 格式无效" }
        }
    }
}
