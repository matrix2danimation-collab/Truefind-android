package app.truefind.android

import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * "Never miss a call" (NotifyPrompt.jsx), drawn by the phone: a small card above the bottom bar
 * on Home that asks once (then again after 3 days if "Not now") to turn on notifications, so calls
 * and messages can reach the phone even when True Find is closed. It asks Android's own question
 * (Android 13 and newer); it only shows while that question hasn't been answered yet, and never
 * over another pop-up. It never blocks the page.
 *
 * Then (and for members who allowed notifications before) it walks through what the phone still
 * needs so a call rings while True Find is closed (BgSetup): background / battery, the phone
 * maker's Autostart (or Samsung's sleeping apps), Xiaomi's lock-screen / pop-up permissions, the
 * "Incoming calls" notification kind, full-screen calls on Android 14+ — one step at a time, each opening the
 * phone's own settings. "Not now" asks again after 3 days.
 */
class NotifyPrompt(private val act: ComponentActivity, private val root: FrameLayout) {
    companion object {
        private const val AGAIN_MS = 3L * 24 * 60 * 60 * 1000
        private const val PERM = "android.permission.POST_NOTIFICATIONS"
    }
    private val main = Handler(Looper.getMainLooper())
    private val prefs = act.getSharedPreferences("tf_status", Context.MODE_PRIVATE)
    private var show = false
    private var state = ""      // "" · busy · on · blocked · failed · bg (a background step) · bgdone
    private var step = ""       // the background step on the card: BgSetup.STEPS
    private var kind = ""       // its variant: battery → restricted · autostart → list / samsung / appinfo
    private val tried = mutableSetOf<String>()
    private var onHome = false
    private var checking = false
    private var tries = 0
    /** anything else on screen (a pop-up, a status screen) — the card waits */
    var busyElsewhere: () -> Boolean = { false }
    /** the bottom bar's height (the card sits 12 above it) */
    var navPx: () -> Int = { 0 }
    var preview = false

    private val ask = act.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> answered(granted) }
    /** back from the phone's settings for one background step */
    private val settings = act.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { nextBgStep() }

    // ── the card ──
    private val card = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = Tf.box(Color.WHITE, 20f, 1f, Ui.c("#EEE6FB"))
        setPadding(Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(14f), Ui.dpi(12f))
        elevation = Ui.dp(14f)
        if (Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = Color.argb(150, 59, 7, 100); outlineAmbientShadowColor = Color.argb(60, 59, 7, 100) }
        isClickable = true
        visibility = View.GONE
        contentDescription = "Turn on notifications"
    }
    private val iconBox = FrameLayout(act)
    private val bell = ImageView(act).apply {
        setImageDrawable(Tf.svg("<svg viewBox=\"0 0 24 24\"><path d=\"M12 3a6 6 0 0 0-6 6v3.6L4.4 15.5a1 1 0 0 0 .9 1.5h13.4a1 1 0 0 0 .9-1.5L18 12.6V9a6 6 0 0 0-6-6z\" fill=\"#6C2BD9\"/><path d=\"M9.8 19a2.3 2.3 0 0 0 4.4 0\" fill=\"none\" stroke=\"#6C2BD9\" stroke-width=\"2\" stroke-linecap=\"round\"/></svg>", 24f))
    }
    private val tick = ImageView(act).apply {
        setImageDrawable(Tf.svg("<svg viewBox=\"0 0 24 24\"><path d=\"M5 12.5l4.5 4.5L19 7.5\" fill=\"none\" stroke=\"#16A34A\" stroke-width=\"2.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>", 24f))
        visibility = View.GONE
    }
    private val title = Tf.t(act, "Never miss a call", 16.5f, Ui.c("#1F1235")).apply { setLineSpacing(0f, 1.2f) }
    private val sub = Tf.t(act, "Get calls and messages even when True Find is closed.", 13f, Ui.c("#6B6478"), false).apply { setLineSpacing(0f, 1.35f) }
    private val buttons = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    private val later = Tf.t(act, "Not now", 14.5f, Ui.c("#5B21B6")).apply {
        gravity = Gravity.CENTER; background = Tf.box(Color.WHITE, 13f, 1f, Ui.c("#E9E0FB"))
    }
    private val turnOn = Tf.t(act, "Turn on", 14.5f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#7A0AD7"), Ui.c("#5806A3"))).apply { cornerRadius = Ui.dp(13f) }
        elevation = Ui.dp(3f)
    }
    private var ring: ValueAnimator? = null

    init {
        iconBox.addView(bell, FrameLayout.LayoutParams(Ui.dpi(24f), Ui.dpi(24f), Gravity.CENTER))
        iconBox.addView(tick, FrameLayout.LayoutParams(Ui.dpi(24f), Ui.dpi(24f), Gravity.CENTER))
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(iconBox, LinearLayout.LayoutParams(Ui.dpi(46f), Ui.dpi(46f)))
        val words = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        words.addView(title)
        words.addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(2f) })
        top.addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Ui.dpi(12f) })
        card.addView(top)
        buttons.addView(later, LinearLayout.LayoutParams(0, Ui.dpi(42f), 1f))
        buttons.addView(turnOn, LinearLayout.LayoutParams(0, Ui.dpi(42f), 1.4f).apply { leftMargin = Ui.dpi(8f) })
        card.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(12f) })
        Ui.pressable(later, 0.97f); Ui.pressable(turnOn, 0.97f)
        later.setOnClickListener { notNow() }
        turnOn.setOnClickListener { turnOnNow() }
        root.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        card.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        draw()
    }

    // ═════════════════ the phone's answer so far ═════════════════
    /** "granted" · "default" (not answered yet) · "denied" (like Notification.permission) */
    private fun permission(): String {
        if (Build.VERSION.SDK_INT < 33) return if (NotificationManagerCompat.from(act).areNotificationsEnabled()) "granted" else "denied"
        if (ContextCompat.checkSelfPermission(act, PERM) == PackageManager.PERMISSION_GRANTED) return "granted"
        return if (prefs.getBoolean("push_denied", false)) "denied" else "default"
    }
    private fun askedRecently(): Boolean = System.currentTimeMillis() - prefs.getLong("push_ask_at", 0L) < AGAIN_MS
    private fun markAsked() { prefs.edit().putLong("push_ask_at", System.currentTimeMillis()).apply() }
    private fun bgAskedRecently(): Boolean = System.currentTimeMillis() - prefs.getLong("bg_ask_at", 0L) < AGAIN_MS
    /** notifications are on, but the phone may still stop True Find in the background */
    private fun bgWanted(): Boolean = permission() == "granted" && !bgAskedRecently() && BgSetup.needed(act)
    private fun firstBgStep(): String = BgSetup.nextStep(act, tried).also { kindOf(it) }
    private fun kindOf(s: String) {
        kind = when (s) {
            "battery" -> if (BgSetup.restricted(act)) "restricted" else ""
            "autostart" -> BgSetup.autostartKind(act)
            else -> ""
        }
    }

    // ═════════════════ when it shows ═════════════════
    /** MainActivity: the page now (Home or not), signed in */
    fun update(home: Boolean, signedIn: Boolean) {
        if (preview) return
        val was = onHome
        onHome = home && signedIn
        // leaving Home hides it (it comes back next time if it was not answered)
        if (!onHome && (state == "" || state == "bg")) { if (show) hide() }
        if (onHome && !was) scheduleCheck()
        place()
    }

    private fun scheduleCheck() {
        val askPush = permission() == "default" && !askedRecently()
        if (show || checking || !(askPush || bgWanted())) return
        checking = true; tries = 0
        main.postDelayed(check, 2500)
    }
    private val check = object : Runnable {
        override fun run() {
            checking = false
            val askPush = permission() == "default" && !askedRecently()
            if (!onHome || !(askPush || bgWanted())) return
            if (busyElsewhere() && tries++ < 20) { checking = true; main.postDelayed(this, 3000); return }
            if (askPush) state = "" else { tried.clear(); step = firstBgStep(); if (step.isEmpty()) return; state = "bg" }
            show = true; draw(); appear()
        }
    }

    private fun appear() {
        card.visibility = View.VISIBLE
        place()
        card.alpha = 0f; card.translationY = Ui.dp(24f)
        card.animate().alpha(1f).translationY(0f).setDuration(400).setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f)).start()
        startRing()
    }
    private fun hide() {
        show = false
        stopRing()
        card.animate().alpha(0f).translationY(Ui.dp(16f)).setDuration(200).withEndAction { card.visibility = View.GONE; card.translationY = 0f; card.alpha = 1f }.start()
    }

    /** above the bottom bar, at most 456 wide, 12 from the sides */
    private fun place() {
        val lp = card.layoutParams as FrameLayout.LayoutParams
        val w = root.width - root.paddingLeft - root.paddingRight
        val want = if (w > 0) minOf(w - Ui.dpi(24f), Ui.dpi(456f)) else -1
        val bottom = navPx() + Ui.dpi(12f)
        if (lp.width != want || lp.bottomMargin != bottom) { lp.width = want; lp.bottomMargin = bottom; card.layoutParams = lp }
    }

    // ═════════════════ the buttons ═════════════════
    private fun notNow() {
        if (state == "bg") prefs.edit().putLong("bg_ask_at", System.currentTimeMillis()).apply() else markAsked()
        hide()
    }

    private fun turnOnNow() {
        if (state == "bg") { openBgStep(); return }
        if (state == "busy") return
        if (preview) { state = "on"; draw(); return }
        state = "busy"; draw()
        if (Build.VERSION.SDK_INT < 33) { answered(NotificationManagerCompat.from(act).areNotificationsEnabled()); return }
        try { ask.launch(PERM) } catch (e: Exception) { result("failed") }
    }

    private fun answered(granted: Boolean) {
        markAsked()
        if (granted) { result("granted"); return }
        // "Don't allow" → Android lets the app ask once more later, but like the web the card
        // doesn't come back by itself; closed without an answer → asked again another day
        val explicit = Build.VERSION.SDK_INT >= 33 && act.shouldShowRequestPermissionRationale(PERM)
        val askedBefore = prefs.getInt("push_asks", 0) > 0
        prefs.edit().putInt("push_asks", prefs.getInt("push_asks", 0) + 1).apply()
        if (explicit || askedBefore) { prefs.edit().putBoolean("push_denied", true).apply(); result("denied") } else result("default")
    }

    private fun result(r: String) {
        when (r) {
            "granted" -> {
                state = "on"; draw(); testPush()
                // straight on to what the phone needs so calls ring while True Find is closed
                main.postDelayed({
                    tried.clear(); step = firstBgStep()
                    if (step.isEmpty()) hide() else { state = "bg"; draw(); startRing() }
                }, 2200)
            }
            "denied" -> { state = "blocked"; draw(); main.postDelayed({ hide() }, 6000) }
            "default" -> { state = ""; hide() }
            else -> { state = "failed"; draw(); main.postDelayed({ hide() }, 5000) }
        }
    }

    // ═════════════════ background steps (BgSetup) ═════════════════
    private fun openBgStep() {
        if (preview) { nextBgStep(); return }
        val s = step
        tried.add(s)
        // the switches of these pages can't be read back: once opened, the step counts as done
        if (s == "autostart" || s == "miui" || s == "channel") BgSetup.markDone(act, s)
        // the best page first; a phone that lacks one gets the next (in the end the app's own page)
        for (i in BgSetup.intentsFor(act, s)) {
            try { settings.launch(i); return } catch (e: Throwable) { }
        }
        nextBgStep()
    }

    /** the next step still missing, or "all set" */
    private fun nextBgStep() {
        if (preview) tried.add(step)
        step = if (preview) BgSetup.STEPS.firstOrNull { it !in tried }.orEmpty().also { kind = "" } else firstBgStep()
        if (step.isNotEmpty()) { state = "bg"; draw(); return }
        state = "bgdone"; draw()
        main.postDelayed({ if (state == "bgdone") hide() }, 2600)
    }

    /** the web's sendTestPush(): only when this app can receive pushes (its push token), else nothing reaches this phone */
    private fun testPush() {
        if (Push.token(act).isNotEmpty()) Api.invoke("pushSubscribe", org.json.JSONObject().put("action", "test")) { }
    }

    private fun draw() {
        val done = state == "on" || state == "bgdone"; val blocked = state == "blocked"; val failed = state == "failed"
        val bg = state == "bg"
        iconBox.background = Tf.box(if (done) Ui.c("#DCFCE7") else Ui.c("#F1EAFE"), 14f)
        bell.visibility = if (done) View.GONE else View.VISIBLE
        tick.visibility = if (done) View.VISIBLE else View.GONE
        if (done) stopRing()
        title.text = when {
            state == "bgdone" -> "All set"
            bg && step == "battery" && kind == "restricted" -> "True Find is restricted"
            bg && step == "battery" -> "Let calls ring when closed"
            bg && step == "autostart" && kind == "samsung" -> "Keep True Find awake"
            bg && step == "autostart" -> "Turn on Autostart"
            bg && step == "miui" -> "Show calls on the lock screen"
            bg && step == "channel" -> "Let calls pop up"
            bg && step == "fullscreen" -> "Full screen calls"
            done -> "Notifications are on"
            blocked -> "Notifications are blocked"
            failed -> "Couldn't turn them on"
            else -> "Never miss a call"
        }
        sub.text = when {
            state == "bgdone" -> "Calls and messages will reach you even when True Find is closed."
            bg && step == "battery" && kind == "restricted" -> "Open Battery and choose Unrestricted. Until then calls and messages can't reach you."
            bg && step == "battery" -> "Allow True Find to run in the background, so calls and messages always come through."
            bg && step == "autostart" && kind == "samsung" -> "Background usage limits → Never sleeping apps: add True Find, so calls ring while it's closed."
            bg && step == "autostart" && kind == "appinfo" -> "Turn on Auto launch and Allow background activity for True Find."
            bg && step == "autostart" -> "Your phone stops apps in the background. Find True Find in the list and switch it on."
            bg && step == "miui" -> "Turn on Show on Lock screen and Display pop-up windows while running in the background."
            bg && step == "channel" -> "For Incoming calls, turn on pop-up (floating) notifications, Lock screen and Sound."
            bg && step == "fullscreen" -> "Turn on full screen notifications for True Find, so a call fills the screen like a phone call."
            done -> "Calls and messages will reach you even when True Find is closed."
            blocked -> "Allow them for True Find in your phone's settings → Notifications."
            failed -> "This browser can't show True Find notifications right now. Please try again later."
            else -> "Get calls and messages even when True Find is closed."
        }
        buttons.visibility = if (done || blocked || failed) View.GONE else View.VISIBLE
        turnOn.text = when { state == "busy" -> "Turning on…"; bg && step == "battery" -> "Allow"; bg -> "Open settings"; else -> "Turn on" }
        turnOn.alpha = if (state == "busy") 0.7f else 1f
    }

    // the bell rings now and then (tfnpRing: 2.6 s; nothing when the phone's animations are off)
    private fun startRing() {
        if (ring != null || state == "on") return
        val off = try { Settings.Global.getFloat(act.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (e: Exception) { false }
        if (off) return
        bell.post { bell.pivotX = bell.width * 0.5f; bell.pivotY = bell.height * 0.15f }
        val keys = floatArrayOf(0f, 0.70f, 0.75f, 0.80f, 0.85f, 0.90f, 1f)
        val deg = floatArrayOf(0f, 0f, 14f, -12f, 8f, -5f, 0f)
        ring = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                var i = 0
                while (i < keys.size - 2 && f > keys[i + 1]) i++
                val span = keys[i + 1] - keys[i]
                val k = if (span <= 0f) 0f else ((f - keys[i]) / span).coerceIn(0f, 1f)
                val e = k * k * (3 - 2 * k)
                bell.rotation = deg[i] + (deg[i + 1] - deg[i]) * e
            }
            start()
        }
    }
    private fun stopRing() { ring?.cancel(); ring = null; bell.rotation = 0f }

    /** the bell rings only while the card can be seen */
    fun onAppVisible(v: Boolean) { if (!v) stopRing() else if (show && (state == "" || state == "bg")) startRing() }

    // ═════════════════ test pictures ═════════════════
    fun startPreview(what: String) {
        preview = true
        onHome = true
        tried.clear()
        // notify-bg-<step>[-<kind>]: e.g. notify-bg-battery-restricted, notify-bg-autostart-samsung
        val parts = what.removePrefix("notify-bg-").split("-")
        step = if (what.startsWith("notify-bg-") && parts[0] in BgSetup.STEPS) parts[0] else ""
        kind = if (step.isNotEmpty()) parts.getOrNull(1).orEmpty() else ""
        state = when (what) { "notify-on" -> "on"; "notify-blocked" -> "blocked"; "notify-bg-done" -> "bgdone"; else -> if (step.isNotEmpty()) "bg" else "" }
        show = true
        draw()
        appear()
    }
}
