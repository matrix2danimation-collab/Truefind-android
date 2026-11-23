package app.truefind.android

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * What a phone needs so a call RINGS (and a message shows) while True Find is closed — on every
 * make of phone, not only on plain Android. Checked one by one (NotifyPrompt walks the member
 * through the ones still missing, each opening the phone's own settings page):
 *
 *  1. battery — Android's background question ("Allow / Deny"). A phone whose battery setting for
 *     True Find is RESTRICTED gets no Firebase messages at all (Android 9+), so that one opens the
 *     app's own page, where Battery → Unrestricted is chosen.
 *  2. autostart — Xiaomi / Redmi / POCO, Oppo / Realme / OnePlus, Vivo / iQOO, Tecno / Infinix / itel,
 *     Huawei / Honor, Asus stop a swiped-away app unless "Autostart" is on for it; Samsung puts apps
 *     to sleep unless they are "Never sleeping". Their own settings page opens. (There is no way to
 *     read these switches, so a step counts as done once its page was opened.)
 *  3. miui — Xiaomi only: "Show on Lock screen" and "Display pop-up windows while running in the
 *     background" (Other permissions). Without them a call only rings, its screen never shows.
 *  4. channel — the "Incoming calls" notification kind must pop up, show on the lock screen and
 *     ring: opened when the phone (or the member) turned any of that down, and once on the makes
 *     that switch pop-ups off for new apps.
 *  5. fullscreen — Android 14+: "full screen notifications" allowed, so a call fills the lock screen.
 *
 * Not fixable by any app: a phone without Google Play services (newer Huawei), an app the member
 * force-stopped, notifications switched off by the member.
 */
object BgSetup {
    private const val PREFS = "tf_status"
    const val CALL_CHANNEL = "tf_calls"
    val STEPS = listOf("battery", "autostart", "miui", "channel", "fullscreen")

    private val brand: String get() = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
    private fun brandIs(vararg names: String) = names.any { brand.contains(it) }
    val xiaomi get() = brandIs("xiaomi", "redmi", "poco")
    val samsung get() = brandIs("samsung")
    /** the makes whose own battery managers stop swiped-away apps (dontkillmyapp.com) */
    private val aggressive get() = xiaomi || brandIs("oppo", "realme", "oneplus", "vivo", "iqoo", "tecno", "infinix", "itel", "huawei", "honor", "asus", "meizu", "lenovo", "letv", "nokia")
    /** the makes that switch pop-up / lock-screen notifications off for new apps */
    private val quietByDefault get() = xiaomi || brandIs("vivo", "iqoo", "oppo", "realme", "oneplus", "tecno", "infinix", "itel")

    /** the phone makers' Autostart / sleeping-apps pages, most likely first; the first one this phone has is used */
    private val autostartPages = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.transsion.phonemaster" to "com.cyin.himgr.autostart.AutoStartActivity",
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.entry.FunctionActivity",
        // Samsung: Battery → Background usage limits (sleeping / never sleeping apps)
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        "com.samsung.android.sm" to "com.samsung.android.sm.ui.battery.BatteryActivity",
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun done(ctx: Context, step: String) = prefs(ctx).getBoolean("bg_done_$step", false)
    fun markDone(ctx: Context, step: String) { prefs(ctx).edit().putBoolean("bg_done_$step", true).apply() }

    // ═════════════════ what the phone says now ═════════════════
    fun batteryOk(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return try { ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true } catch (e: Throwable) { true }
    }

    /** Battery → Restricted (Android 9+): Firebase delivers NO messages to the app at all */
    fun restricted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        return try { ctx.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted ?: false } catch (e: Throwable) { false }
    }

    fun fullScreenOk(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return try { ctx.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true } catch (e: Throwable) { true }
    }

    /** the "Incoming calls" notification kind pops up, shows on the lock screen and makes a sound */
    fun callChannelOk(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        return try {
            val ch = ctx.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CALL_CHANNEL) ?: return true
            ch.importance >= NotificationManager.IMPORTANCE_HIGH &&
                ch.lockscreenVisibility != android.app.Notification.VISIBILITY_SECRET &&
                ch.sound != null
        } catch (e: Throwable) { true }
    }

    /** Google Play services is on this phone (Firebase can't reach a phone without it, e.g. newer Huawei) */
    fun playServices(ctx: Context): Boolean = try {
        ctx.packageManager.getApplicationInfo("com.google.android.gms", 0).enabled
    } catch (e: Throwable) { false }

    /** this phone's Autostart / sleeping-apps page, or null (plain Android, Pixel, Motorola … have none) */
    fun autostartIntent(ctx: Context): Intent? {
        val pm = ctx.packageManager
        for ((pkg, cls) in autostartPages) {
            val i = Intent().setComponent(ComponentName(pkg, cls))
            val ok = try { pm.resolveActivity(i, 0)?.activityInfo?.exported == true } catch (e: Throwable) { false }
            if (ok) return i
        }
        return null
    }

    /** Xiaomi's "Other permissions" page of True Find (Show on Lock screen, pop-up windows in the background) */
    private fun miuiPermIntent(ctx: Context): Intent? {
        if (!xiaomi) return null
        val list = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"),
            Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity"),
        ).map { it.putExtra("extra_pkgname", ctx.packageName).putExtra("extra_package_name", ctx.packageName).putExtra("extra_package_uid", android.os.Process.myUid()) }
        return list.firstOrNull { i -> try { ctx.packageManager.resolveActivity(i, 0)?.activityInfo?.exported == true } catch (e: Throwable) { false } }
    }

    // ═════════════════ the steps ═════════════════
    fun stepNeeded(ctx: Context, step: String): Boolean = when (step) {
        "battery" -> !batteryOk(ctx) || restricted(ctx)
        // the page this phone has; Oppo / Realme / OnePlus phones without one have the switch on the app's own page
        "autostart" -> !done(ctx, step) && (autostartIntent(ctx) != null || aggressive)
        "miui" -> !done(ctx, step) && miuiPermIntent(ctx) != null
        "channel" -> !callChannelOk(ctx) || (quietByDefault && !done(ctx, step))
        "fullscreen" -> !fullScreenOk(ctx)
        else -> false
    }

    /** the first step still missing (skipping the ones already tried this time): "" when all done */
    fun nextStep(ctx: Context, tried: Set<String> = emptySet()): String = STEPS.firstOrNull { it !in tried && stepNeeded(ctx, it) } ?: ""

    fun needed(ctx: Context): Boolean = nextStep(ctx).isNotEmpty()

    /**
     * The settings screens for one step, best first, ending with the app's own page. The phone
     * makers' pages are only listed when this phone has them; Android's own screens are tried in
     * order when opened (NotifyPrompt), since a phone maker may have left one out.
     */
    fun intentsFor(ctx: Context, step: String): List<Intent> {
        val pkg = Uri.parse("package:${ctx.packageName}")
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        val list: List<Intent> = when (step) {
            "battery" ->
                if (restricted(ctx)) emptyList() // Battery → Unrestricted is on the app's own page
                else listOf(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg), Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            "autostart" -> listOfNotNull(autostartIntent(ctx))
            "miui" -> listOfNotNull(miuiPermIntent(ctx))
            "channel" -> if (Build.VERSION.SDK_INT >= 26) listOf(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, CALL_CHANNEL),
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
            ) else emptyList()
            "fullscreen" -> if (Build.VERSION.SDK_INT >= 34) listOf(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)) else emptyList()
            else -> emptyList()
        }
        return list + details
    }

    /** what the Autostart step's page is (the card's words depend on it) */
    fun autostartKind(ctx: Context): String = when {
        samsung && autostartIntent(ctx) != null -> "samsung"
        autostartIntent(ctx) != null -> "list"
        else -> "appinfo"
    }

    /** test builds: every check in one line of the phone's log (ci/push-test.sh reads it) */
    fun report(ctx: Context): String = listOf(
        "sdk=${Build.VERSION.SDK_INT}", "brand=${Build.MANUFACTURER}",
        "play=${playServices(ctx)}", "battery=${batteryOk(ctx)}", "restricted=${restricted(ctx)}",
        "fullscreen=${fullScreenOk(ctx)}", "channel=${callChannelOk(ctx)}",
        "autostart=${autostartIntent(ctx)?.component?.className ?: "-"}", "next=${nextStep(ctx).ifEmpty { "-" }}",
    ).joinToString(" ")
}
