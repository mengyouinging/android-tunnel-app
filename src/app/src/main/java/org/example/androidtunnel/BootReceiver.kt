package org.example.androidtunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && context.getSharedPreferences("private_config", Context.MODE_PRIVATE).getBoolean("autostart", false)) {
            // Autostart remains opt-in and is intentionally not enabled by default.
            context.startForegroundService(Intent(context, TunnelService::class.java))
        }
    }
}
