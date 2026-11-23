package org.example.androidtunnel

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ScrollView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.example.androidtunnel.security.SecretStore
import java.security.SecureRandom

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SecretStore(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        box.addView(TextView(this).apply { text = "设置"; textSize = 22f })
        val domain = field("域名，例如 example.com")
        val token = field("Cloudflare API Token").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val port = field("监听端口（1024–65535）").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val relay = field("EasyTier 自建中继 VPS host:port（待实机联调）")
        val edge = field("Tunnel 优选边缘 IP（可选，待实机联调）")
        val awgPrivate = secretField("AmneziaWG 本机私钥")
        val peerPublic = field("AmneziaWG peer 公钥")
        val peerEndpoint = field("AmneziaWG peer Endpoint host:port")
        val allowedIps = field("AllowedIPs，例如 10.0.0.2/32")
        val interfaceAddress = field("本机隧道 IPv6/CIDR（待与对端分配）")
        val jitter = field("Jc,Jmin,Jmax（逗号分隔）").apply { setText("3,10,50") }
        val uuid = secretField("VLESS UUID")
        val realityPrivate = secretField("Reality 私钥")
        val realityId = field("Reality Short ID")
        val realityName = field("Reality ServerName")
        val hysteriaPassword = secretField("Hysteria2 密码")
        val hysteriaCert = field("Hysteria2 TLS 证书文件绝对路径")
        val hysteriaKey = secretField("Hysteria2 TLS 私钥文件绝对路径")
        val easyTierNetwork = field("EasyTier 网络名")
        val easyTierSecret = secretField("EasyTier 网络密钥")
        val probeHost = field("对端探测地址")
        val probePort = field("对端探测端口").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val cfTunnelToken = secretField("cloudflared Tunnel Token")
        val wsRemote = field("wstunnel 管理通道 wss:// 地址")
        listOf(domain, token, port, relay, edge, awgPrivate, peerPublic, peerEndpoint, allowedIps, interfaceAddress, jitter,
            uuid, realityPrivate, realityId, realityName, hysteriaPassword, hysteriaCert, hysteriaKey, easyTierNetwork, easyTierSecret,
            probeHost, probePort, cfTunnelToken, wsRemote).forEach(box::addView)
        domain.setText(store.get("domain") ?: "")
        token.setText(store.get("token") ?: "")
        port.setText(store.get("port") ?: (20000 + SecureRandom().nextInt(40001)).toString())
        relay.setText(store.get("relay") ?: "")
        edge.setText(store.get("edge_ip") ?: "")
        awgPrivate.setText(store.get("awg_private") ?: "")
        peerPublic.setText(store.get("peer_public") ?: "")
        peerEndpoint.setText(store.get("peer_endpoint") ?: "")
        allowedIps.setText(store.get("allowed_ips") ?: "")
        interfaceAddress.setText(store.get("interface_address") ?: "")
        uuid.setText(store.get("vless_uuid") ?: "")
        realityPrivate.setText(store.get("reality_private") ?: "")
        realityId.setText(store.get("reality_short_id") ?: "")
        realityName.setText(store.get("reality_server_name") ?: "")
        hysteriaPassword.setText(store.get("hysteria_password") ?: "")
        hysteriaCert.setText(store.get("hysteria_cert_path") ?: "")
        hysteriaKey.setText(store.get("hysteria_key_path") ?: "")
        easyTierNetwork.setText(store.get("easytier_network") ?: "")
        easyTierSecret.setText(store.get("easytier_secret") ?: "")
        probeHost.setText(store.get("probe_host") ?: "")
        probePort.setText(store.get("probe_port") ?: "")
        cfTunnelToken.setText(store.get("cloudflared_token") ?: "")
        wsRemote.setText(store.get("wstunnel_remote") ?: "")
        jitter.setText(getSharedPreferences("private_config", MODE_PRIVATE).getString("jitter", "3,10,50"))
        val autoStart = CheckBox(this).apply {
            text = "开机后自动启动服务"
            isChecked = getSharedPreferences("private_config", MODE_PRIVATE).getBoolean("autostart", false)
        }
        box.addView(autoStart)
        val result = TextView(this).apply { setPadding(0, 12, 0, 8) }
        box.addView(Button(this).apply {
            text = "测试 Token"
            setOnClickListener {
                val value = token.text.toString()
                val host = domain.text.toString()
                Thread {
                    val message = try { CloudflareClient().verifyDnsEdit(value, host) } catch (e: Exception) { "测试失败：${e.message ?: "网络错误"}" }
                    runOnUiThread { result.text = message }
                }.start()
            }
        })
        box.addView(Button(this).apply {
            text = "保存配置"
            setOnClickListener {
                try {
                    val config = TunnelConfig(domain.text.toString(), token.text.toString(), port.text.toString().toInt(), relay.text.toString(), edge.text.toString())
                    config.validate()
                    val values = jitter.text.toString().split(',').map { it.trim().toInt() }
                    require(values.size == 3) { "Jc,Jmin,Jmax 需要三个整数" }
                    val runtime = PeerConfig(
                        awgPrivate.text.toString(), peerPublic.text.toString(), peerEndpoint.text.toString(), allowedIps.text.toString(),
                        interfaceAddress.text.toString(), config.port, values[0], values[1], values[2], uuid.text.toString(), realityPrivate.text.toString(),
                        realityId.text.toString(), realityName.text.toString(), hysteriaPassword.text.toString(), hysteriaCert.text.toString(), hysteriaKey.text.toString(),
                        easyTierNetwork.text.toString(), easyTierSecret.text.toString(), config.relay,
                        probeHost.text.toString(), probePort.text.toString().toInt(), cfTunnelToken.text.toString(), wsRemote.text.toString()
                    )
                    runtime.validate()
                    store.put("domain", config.domain.trim().trimEnd('.'))
                    store.put("token", config.token)
                    store.put("port", config.port.toString())
                    store.put("awg_private", awgPrivate.text.toString())
                    store.put("peer_public", peerPublic.text.toString())
                    store.put("vless_uuid", uuid.text.toString())
                    store.put("reality_private", realityPrivate.text.toString())
                    store.put("hysteria_password", hysteriaPassword.text.toString())
                    store.put("hysteria_cert_path", hysteriaCert.text.toString())
                    store.put("hysteria_key_path", hysteriaKey.text.toString())
                    store.put("easytier_secret", easyTierSecret.text.toString())
                    store.put("cloudflared_token", cfTunnelToken.text.toString())
                    store.put("relay", config.relay)
                    store.put("edge_ip", config.edgeIp)
                    store.put("peer_endpoint", peerEndpoint.text.toString())
                    store.put("allowed_ips", allowedIps.text.toString())
                    store.put("interface_address", interfaceAddress.text.toString())
                    store.put("reality_short_id", realityId.text.toString())
                    store.put("reality_server_name", realityName.text.toString())
                    store.put("easytier_network", easyTierNetwork.text.toString())
                    store.put("probe_host", probeHost.text.toString())
                    store.put("probe_port", probePort.text.toString())
                    store.put("wstunnel_remote", wsRemote.text.toString())
                    getSharedPreferences("private_config", MODE_PRIVATE).edit().putBoolean("autostart", autoStart.isChecked).putString("jitter", values.joinToString(",")).apply()
                    result.text = "已加密保存"
                } catch (e: Exception) { result.text = e.message ?: "配置无效" }
            }
        })
        box.addView(result)
        box.addView(TextView(this).apply {
            text = "待实机联调：实际 Tunnel 边缘节点由 cloudflared 选择；当前版本不会将优选 IP 伪称为已使用节点或实测延迟。"
            setPadding(0, 12, 0, 0)
        })
        box.gravity = Gravity.TOP
        setContentView(ScrollView(this).apply { addView(box) })
    }
    private fun field(hintText: String) = EditText(this).apply { hint = hintText; maxLines = 1; setSingleLine(true) }
    private fun secretField(hintText: String) = field(hintText).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
}
