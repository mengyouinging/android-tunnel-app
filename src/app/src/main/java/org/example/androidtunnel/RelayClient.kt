package org.example.androidtunnel

import android.content.Context
import org.example.androidtunnel.security.SecretStore
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URI
import java.io.InputStream

class RelayException(message: String): Exception(message)

object HttpJson {
    fun get(url: String, body: JSONObject? = null): JSONObject {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", V2.UA)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-store")
            useCaches = false
        }
        try {
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw RelayException(
                if (status == 401 || status == 403) "认证失败或请求被拦截（HTTP $status）" else "请求失败（HTTP $status）")
            val text = connection.inputStream.use { readLimited(it) }
            val obj = JSONObject(text)
            if (obj.has("ok") && !obj.optBoolean("ok")) throw RelayException("服务端拒绝请求")
            if (obj.has("error") && !obj.isNull("error")) throw RelayException("服务端返回错误")
            return obj
        } catch (e: RelayException) { throw e }
        catch (_: Exception) { throw RelayException("网络或响应格式错误，请检查网络和 HTTPS 地址") }
        finally { connection.disconnect() }
    }
    private fun readLimited(input: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val count = input.read(buf)
            if (count < 0) break
            require(out.size() + count <= 262144) { "响应过大" }
            out.write(buf, 0, count)
        }
        return out.toString("UTF-8")
    }
}

class RelayClient(private val settings: RelaySettings) {
    init { settings.validate() }
    private fun auth(url: String) = "$url?p=${URLEncoder.encode(settings.password, "UTF-8")}"
    private fun endpoint(path: String) = settings.relay.trimEnd('/') + path
    fun fetch(): SharedConfig {
        val obj = HttpJson.get(auth(settings.config))
        fun s(k: String) = if (obj.isNull(k)) "" else obj.optString(k, "")
        fun addresses(k: String): List<String> {
            val value = obj.opt(k)
            return (if (value is JSONArray) (0 until value.length()).map { value.getString(it) }
                else s(k).split(',')).map(String::trim).filter(String::isNotBlank)
        }
        val token = s("tunnel_token").ifBlank { HttpJson.get(auth(endpoint("/token"))).optString("tunnel_token") }
        return try {
            SharedConfig(obj.optInt("awg_port", -1), s("peer_public_key"), s("phone_public_key"),
                addresses("allowed_ips"), addresses("interface_address"), s("vless_uuid"), token, s("cf_host").ifBlank { V2.CF_HOST },
                s("reality_public_key"), s("reality_short_id"), s("hysteria_password"), s("easytier_network"),
                s("easytier_secret"), s("relay"), s("wstunnel_remote"), s("domain"), s("v6_host")).validate()
        } catch (_: Exception) { throw RelayException("共享配置缺失或无效（端口、公钥、地址、UUID、保底主机名或凭据）") }
    }
    fun register(ipv6: String, publicKey: String) {
        require(ipv6.isEmpty() || V2.globalIpv6(ipv6))
        require(V2.key(publicKey))
        HttpJson.get(endpoint("/register"), JSONObject().put("password", settings.password)
            .put("ipv6", ipv6).put("port", V2.PORT).put("pubkey", publicKey))
    }
}

object RelayRepository {
    private var startup: Pair<RelaySettings, SharedConfig>? = null
    @Synchronized fun prepareStartup(context: Context, selected: RelaySettings) {
        startup = null
        startup = selected to refresh(context, selected)
    }
    @Synchronized fun initial(context: Context, selected: RelaySettings): SharedConfig {
        val cached = startup
        startup = null
        return if (cached != null && cached.first == selected) cached.second else refresh(context, selected)
    }
    fun settings(context: Context): RelaySettings {
        val store = SecretStore(context)
        return RelaySettings(store.get("relay_password").orEmpty())
    }
    @Synchronized fun save(context: Context, settings: RelaySettings) {
        settings.validate()
        startup = null
        val store = SecretStore(context)
        store.put("relay_password", settings.password)
        purgeLegacy(context)
    }
    /** Authenticate first; do not overwrite a working password on an invalid candidate. */
    @Synchronized fun changePassword(context: Context, candidate: String) {
        val selected = RelaySettings(V2.password(candidate)).validate()
        val fresh = refresh(context, selected)
        save(context, selected)
        startup = selected to fresh
    }
    fun purgeLegacy(context: Context) {
        context.getSharedPreferences("private_config", Context.MODE_PRIVATE).edit()
            .remove("token").remove("cloudflared_token").remove("edge_ip").remove("peer_endpoint")
            .remove("relay_url").remove("config_url").remove("autostart").apply()
    }
    @Synchronized fun refresh(context: Context, settings: RelaySettings = settings(context)): SharedConfig {
        try {
            val result = RelayClient(settings).fetch()
            context.getSharedPreferences("tunnel_status", Context.MODE_PRIVATE).edit()
                .putLong("config_time", System.currentTimeMillis()).putString("config_status", "共享配置拉取成功").apply()
            return result
        } catch (e: Exception) {
            context.getSharedPreferences("tunnel_status", Context.MODE_PRIVATE).edit()
                .putString("config_status", "配置拉取失败：${if (e is RelayException) e.message else "配置或网络无效"}").apply()
            throw e
        }
    }
}
