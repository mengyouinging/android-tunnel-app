package org.example.androidtunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!StartupPolicy.shouldStartOnBoot(intent.action)) return
        // Boot startup is always enabled. An old stored false must not disable it.
        // The normal foreground-service bootstrap verifies root before doing any work.
        runCatching { TunnelService.start(context, fromBoot = true) }.onFailure {
            context.getSharedPreferences("tunnel_status", Context.MODE_PRIVATE).edit()
                .putBoolean("running", false)
                .putString("mode", TunnelMode.FAILED.name)
                .putString("failure_code", "boot_blocked")
                .putString("detail", "系统阻止开机自启动，请在系统设置中允许清连后台自启动")
                .apply()
        }
    }
}
