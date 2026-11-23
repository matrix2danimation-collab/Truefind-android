package app.truefind.android

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * What a phone needs so a call RINGS (and a message shows) while True Find is closed — on every
 * make of phone, not only on plain Android:
 *  1. battery: True Find may run in the background (Android's own "Allow / Deny" question)
 *  2. Autostart: Xiaomi / Redmi / POCO, Oppo / Realme, Vivo / iQOO, OnePlus, Huawei / Honor, Asus
 *     stop apps that are swiped away unless "Autostart" is on for them; their own settings page
 *     opens (there is no way to read that switch, so it counts as done once it was opened)
 *  3. Android 14 and newer: "full screen notifications" allowed, so a call fills the lock screen
 * NotifyPrompt walks the member through the steps that are still missing, one at a time.
 */
object BgSetup {
    private const val PREFS = "tf_status"

    /** the phone makers' Autostart pages, most likely first; the first one this phone has is used */
    private val autostartPages = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.entry.FunctionActivity",
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun batteryOk(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return try { ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true } catch (e: Throwable) { true }
    }

    fun fullScreenOk(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return try { ctx.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true } catch (e: Throwable) { true }
    }

    /** this phone's Autostart page, or null (plain Android, Samsung, Pixel … have none) */
    fun autostartIntent(ctx: Context): Intent? {
        val pm = ctx.packageManager
        for ((pkg, cls) in autostartPages) {
            val i = Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = try { pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.exported == true } catch (e: Throwable) { false }
            if (ok) return i
        }
        return null
    }

    fun autostartDone(ctx: Context): Boolean = prefs(ctx).getBoolean("autostart_opened", false)
    fun markAutostartDone(ctx: Context) { prefs(ctx).edit().putBoolean("autostart_opened", true).apply() }

    /** the next step still missing: "battery" · "autostart" · "fullscreen" · "" (all done) */
    fun nextStep(ctx: Context, skipBattery: Boolean = false): String = when {
        !skipBattery && !batteryOk(ctx) -> "battery"
        !autostartDone(ctx) && autostartIntent(ctx) != null -> "autostart"
        !fullScreenOk(ctx) -> "fullscreen"
        else -> ""
    }

    fun needed(ctx: Context): Boolean = nextStep(ctx).isNotEmpty()

    fun stepNeeded(ctx: Context, step: String): Boolean = when (step) {
        "battery" -> !batteryOk(ctx)
        "autostart" -> !autostartDone(ctx) && autostartIntent(ctx) != null
        "fullscreen" -> !fullScreenOk(ctx)
        else -> false
    }

    /** the settings screen for one step (null: this phone can't open it) */
    fun intentFor(ctx: Context, step: String): Intent? {
        val pkg = Uri.parse("package:${ctx.packageName}")
        val list = when (step) {
            "battery" -> listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            "autostart" -> listOfNotNull(autostartIntent(ctx))
            "fullscreen" -> if (Build.VERSION.SDK_INT >= 34) listOf(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)) else emptyList()
            else -> emptyList()
        } + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        return list.firstOrNull { i -> try { i.resolveActivity(ctx.packageManager) != null } catch (e: Throwable) { false } }
    }
}
