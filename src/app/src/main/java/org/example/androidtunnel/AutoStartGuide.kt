package org.example.androidtunnel

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import java.util.Locale

/**
 * OEM pages are best-effort, non-public interfaces. There is no portable permission
 * that can grant or query OEM autostart access. Never change OEM settings by shell.
 */
object AutoStartGuide {
    private const val PREF = "startup_guide"
    private const val SHOWN = "autostart_page_attempted_v1"

    private fun component(packageName: String, className: String): Intent =
        Intent().setComponent(ComponentName(packageName, className))

    internal fun candidates(manufacturer: String, brand: String): List<Intent> {
        val words = "$manufacturer $brand".lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+"))
        fun matches(vararg names: String) = names.any { it in words }
        return when {
            matches("xiaomi", "redmi", "poco", "blackshark") -> listOf(
                component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                Intent("miui.intent.action.OP_AUTO_START").setPackage("com.miui.securitycenter")
            )
            matches("huawei", "honor") -> listOf(
                component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                Intent("huawei.intent.action.HSM_BOOTAPP_MANAGER").setPackage("com.huawei.systemmanager")
            )
            matches("oppo", "realme", "oneplus") -> listOf(
                component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
            )
            matches("vivo", "iqoo") -> listOf(
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
            )
            matches("meizu") -> listOf(
                component("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")
            )
            matches("samsung") -> listOf(
                component("com.samsung.android.sm", "com.samsung.android.sm.ui.ram.AutoRunActivity"),
                component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
            )
            matches("asus") -> listOf(
                component("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")
            )
            else -> emptyList()
        }
    }

    /** Call from a resumed Activity, only after the first root check has succeeded. */
    fun openOnce(activity: Activity): Boolean {
        val prefs = activity.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getBoolean(SHOWN, false)) return false
        val intents = candidates(Build.MANUFACTURER, Build.BRAND)
        if (intents.isEmpty()) {
            // AOSP/Pixel and unknown manufacturers: don't send users to unrelated settings.
            prefs.edit().putBoolean(SHOWN, true).apply()
            return false
        }
        // Explicit launch avoids needing QUERY_ALL_PACKAGES; missing/non-exported pages are caught.
        prefs.edit().putBoolean(SHOWN, true).apply()
        for (intent in intents) {
            if (runCatching { activity.startActivity(intent) }.isSuccess) {
                Toast.makeText(activity, "请允许清连开机自启动及后台运行", Toast.LENGTH_LONG).show()
                return true
            }
        }
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
        if (runCatching { activity.startActivity(fallback) }.isSuccess) {
            Toast.makeText(activity, "请在系统应用设置中允许自启动及后台运行", Toast.LENGTH_LONG).show()
            return true
        }
        Toast.makeText(activity, "请手动在系统设置中允许清连自启动", Toast.LENGTH_LONG).show()
        return false
    }
}
