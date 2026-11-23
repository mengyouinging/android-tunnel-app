package org.example.androidtunnel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var serviceSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(28, 32, 28, 32) }
        root.addView(TextView(this).apply { text = "ANDROID TUNNEL"; textSize = 14f })
        status = TextView(this).apply { textSize = 18f; setPadding(0, 28, 0, 24) }
        root.addView(status)
        serviceSwitch = Switch(this).apply {
            text = "隧道服务"
            setOnCheckedChangeListener { _, enabled ->
                if (enabled) {
                    if (!RootController.hasRoot()) {
                        status.text = "需要 Magisk root 授权；当前未取得 root 权限"
                        isChecked = false
                    } else {
                        TunnelService.start(this@MainActivity)
                    }
                } else if (getSharedPreferences("tunnel_status", Context.MODE_PRIVATE).getBoolean("running", false)) {
                    TunnelService.stop(this@MainActivity)
                }
            }
        }
        root.addView(serviceSwitch)
        root.addView(Button(this).apply { text = "电池优化设置"; setOnClickListener { requestBatteryExemption() } })
        root.addView(Button(this).apply { text = "运行状态"; setOnClickListener { startActivity(Intent(this@MainActivity, StatusActivity::class.java)) } })
        root.addView(Button(this).apply { text = "设置"; setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) } })
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 21)
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("tunnel_status", Context.MODE_PRIVATE)
        serviceSwitch.isChecked = prefs.getBoolean("running", false)
        val mode = runCatching { TunnelMode.valueOf(prefs.getString("mode", TunnelMode.STOPPED.name)!!) }.getOrDefault(TunnelMode.STOPPED)
        status.text = "${mode.displayMode()}\n${prefs.getString("detail", "服務未啟動") ?: "服務未啟動"}"
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        } else status.text = "已在电池优化白名单中"
    }
}
