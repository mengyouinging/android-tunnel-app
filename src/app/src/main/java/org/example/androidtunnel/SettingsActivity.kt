package org.example.androidtunnel

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import org.example.androidtunnel.security.SecretStore
import java.security.SecureRandom

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val store = SecretStore(this)
        val domain = findViewById<EditText>(R.id.domain)
        val token = findViewById<EditText>(R.id.token)
        val port = findViewById<EditText>(R.id.port)
        val relay = findViewById<EditText>(R.id.relay)
        val edge = findViewById<EditText>(R.id.edge)
        val awgPrivate = findViewById<EditText>(R.id.awg_private)
        val peerPublic = findViewById<EditText>(R.id.peer_public)
        val peerEndpoint = findViewById<EditText>(R.id.peer_endpoint)
        val allowedIps = findViewById<EditText>(R.id.allowed_ips)
        val interfaceAddress = findViewById<EditText>(R.id.interface_address)
        val jitter = findViewById<EditText>(R.id.jitter).apply { setText("3,10,50") }
        val uuid = findViewById<EditText>(R.id.uuid)
        val realityPrivate = findViewById<EditText>(R.id.reality_private)
        val realityId = findViewById<EditText>(R.id.reality_id)
        val realityName = findViewById<EditText>(R.id.reality_name)
        val hysteriaPassword = findViewById<EditText>(R.id.hysteria_password)
        val hysteriaCert = findViewById<EditText>(R.id.hysteria_cert)
        val hysteriaKey = findViewById<EditText>(R.id.hysteria_key)
        val easyTierNetwork = findViewById<EditText>(R.id.easy_tier_network)
        val easyTierSecret = findViewById<EditText>(R.id.easy_tier_secret)
        val probeHost = findViewById<EditText>(R.id.probe_host)
        val probePort = findViewById<EditText>(R.id.probe_port)
        val cfTunnelToken = findViewById<EditText>(R.id.cf_tunnel_token)
        val wsRemote = findViewById<EditText>(R.id.ws_remote)
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
        val autoStart = findViewById<CheckBox>(R.id.auto_start).apply {
            isChecked = getSharedPreferences("private_config", MODE_PRIVATE).getBoolean("autostart", false)
        }
        val result = findViewById<TextView>(R.id.result)
        findViewById<Button>(R.id.test_token).apply {
            setOnClickListener {
                val value = token.text.toString()
                val host = domain.text.toString()
                Thread {
                    val message = try { CloudflareClient().verifyDnsEdit(value, host) } catch (e: Exception) { "测试失败：${e.message ?: "网络错误"}" }
                    runOnUiThread { result.text = message }
                }.start()
            }
        }
        findViewById<Button>(R.id.save_config).apply {
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
        }
    }
}
