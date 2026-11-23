package org.example.androidtunnel

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class StatusActivity : Activity() {
    private lateinit var view: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply {
            textSize = 18f
            setPadding(28, 36, 28, 28)
            gravity = Gravity.TOP
        }
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("tunnel_status", Context.MODE_PRIVATE)
        val mode = runCatching { TunnelMode.valueOf(prefs.getString("mode", TunnelMode.STOPPED.name)!!) }.getOrDefault(TunnelMode.STOPPED)
        val ip = prefs.getString("ipv6", null) ?: "未检测"
        val latency = prefs.getLong("latency", -1L).takeIf { it >= 0 }?.let { "$it ms" } ?: "未收到对端探测回应"
        val edge = org.example.androidtunnel.security.SecretStore(this).get("edge_ip").orEmpty().ifBlank { "未配置" }
        view.text = "运行状态\n\n连接方式：${mode.displayMode()}\n当前 IPv6：$ip\n对端延迟：$latency\n应急优选 IP：$edge\nCloudflare 实际边缘节点/实测延迟：未取得\n\n${prefs.getString("detail", "服务未启动") ?: "服务未启动"}"
    }
}
