package app.truefind.android

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONObject

/**
 * The rules reminder every 3 months (RulesReminder.jsx, IT Rules as amended in 2026): on Home, once the
 * member's last reminder (or sign-up) is 90 days old, a card tells them what happens when the rules are
 * broken. "I understand" records it on the server (functions/rulesReminder), so it comes back 3 months later.
 * The server is asked at most once a day per phone, and the card never shows over another pop-up.
 */
class RulesReminder(private val ctx: Context, private val root: FrameLayout, private val openPath: (String) -> Unit) {
    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        val POINTS = listOf(
            "Content that breaks our rules is removed.",
            "Accounts that break the rules can be suspended or closed for good.",
            "Breaking the law can lead to penalties under the Information Technology Act, 2000 and other laws.",
            "Serious offences (for example anything involving a child, or fake or nude pictures of someone) are reported to the police.",
        )
    }
    private val main = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("tf_status", Context.MODE_PRIVATE)
    private var due = false
    private var shown = false
    private var onHome = false
    private var checking = false
    private var busy = false
    /** anything else on screen (a pop-up, a status screen) — the card waits */
    var busyElsewhere: () -> Boolean = { false }
    var preview = false

    private val dim = FrameLayout(ctx).apply {
        setBackgroundColor(Color.argb(115, 23, 18, 31))
        isClickable = true // nothing under it is tapped by mistake
        visibility = View.GONE
        elevation = Ui.dp(30f)
    }
    private val okBtn = Tf.t(ctx, "I understand", 15.5f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.c("#7A0AD7"), Ui.c("#5806A3"))).apply { cornerRadius = Ui.dp(23f) }
    }

    init {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Tf.box(Color.WHITE, 22f)
            setPadding(Ui.dpi(18f), Ui.dpi(20f), Ui.dpi(18f), Ui.dpi(16f))
            elevation = Ui.dp(12f)
            contentDescription = "A quick reminder of our rules"
        }
        val icon = FrameLayout(ctx).apply { background = Tf.box(Ui.c("#F3EDFD"), 14f) }
        icon.addView(ImageView(ctx).apply {
            setImageDrawable(Tf.svg("<svg viewBox=\"0 0 24 24\"><path d=\"M12 2.5l7.5 3v6c0 4.6-3.2 8.6-7.5 10-4.3-1.4-7.5-5.4-7.5-10v-6z\" fill=\"none\" stroke=\"#6C2BD9\" stroke-width=\"2\" stroke-linejoin=\"round\"/><path d=\"M8.5 12.2l2.4 2.4 4.6-4.8\" fill=\"none\" stroke=\"#6C2BD9\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>", 22f))
        }, FrameLayout.LayoutParams(Ui.dpi(22f), Ui.dpi(22f), Gravity.CENTER))
        card.addView(icon, LinearLayout.LayoutParams(Ui.dpi(44f), Ui.dpi(44f)))
        card.addView(Tf.t(ctx, "A quick reminder of our rules", 19f, Ui.c("#17121F")), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(12f) })
        card.addView(Tf.t(ctx, "To keep True Find safe, everyone follows our Community Guidelines, Terms and Privacy Policy. If they are broken:", 14.5f, Ui.c("#5E586B"), false).apply { setLineSpacing(0f, 1.3f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(6f) })
        for (p in POINTS) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Tf.t(ctx, "•", 14f, Ui.c("#6C2BD9")), LinearLayout.LayoutParams(Ui.dpi(14f), -2))
            row.addView(Tf.t(ctx, p, 14f, Ui.c("#17121F"), false).apply { setLineSpacing(0f, 1.3f) }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(6f) })
        }
        val links = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, path) in listOf("Guidelines" to "/community-guidelines", "Terms" to "/terms", "Privacy" to "/privacy")) {
            links.addView(Tf.t(ctx, label, 13.5f, Ui.c("#6C2BD9")).apply {
                setPadding(0, Ui.dpi(6f), Ui.dpi(16f), Ui.dpi(6f))
                setOnClickListener { openPath(path) }
            })
        }
        card.addView(links, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(6f) })
        card.addView(okBtn, LinearLayout.LayoutParams(-1, Ui.dpi(46f)).apply { topMargin = Ui.dpi(10f) })
        Ui.pressable(okBtn, 0.97f)
        okBtn.setOnClickListener { understood() }
        dim.addView(card, FrameLayout.LayoutParams(Ui.dpi(340f), -2, Gravity.CENTER))
        root.addView(dim, FrameLayout.LayoutParams(-1, -1))
    }

    private fun checkedToday() = System.currentTimeMillis() - prefs.getLong("rules_check_at", 0L) < DAY_MS

    /** MainActivity: the page now (Home or not), signed in */
    fun update(home: Boolean, signedIn: Boolean) {
        if (preview) return
        onHome = home && signedIn
        if (!onHome) { if (shown) hide(); return }
        if (due) { tryShow(); return }
        if (checking || checkedToday()) return
        checking = true
        main.postDelayed({
            Api.invoke("rulesReminder", JSONObject().put("action", "status")) { r ->
                checking = false
                if (!r.ok) return@invoke // asked again on the next visit to Home
                prefs.edit().putLong("rules_check_at", System.currentTimeMillis()).apply()
                if (r.data.optBoolean("due")) { due = true; tryShow() }
            }
        }, 2500L)
    }

    private fun tryShow() {
        if (shown || !due || !onHome) return
        if (busyElsewhere()) { main.postDelayed({ tryShow() }, 1000L); return }
        shown = true
        dim.visibility = View.VISIBLE
        dim.alpha = 0f
        dim.animate().alpha(1f).setDuration(260).setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f)).start()
    }

    private fun hide() {
        shown = false
        dim.animate().alpha(0f).setDuration(180).withEndAction { dim.visibility = View.GONE; dim.alpha = 1f }.start()
    }

    private fun understood() {
        if (busy) return
        if (preview) { due = false; hide(); return }
        busy = true; okBtn.alpha = .6f
        Api.invoke("rulesReminder", JSONObject().put("action", "ack")) { _ ->
            busy = false; okBtn.alpha = 1f
            due = false
            hide()
        }
    }

    /** the card is on screen (Back closes nothing: the member taps "I understand") */
    val open: Boolean get() = shown

    // ═════════════════ test pictures ═════════════════
    fun startPreview() {
        preview = true; due = true; onHome = true
        shown = true
        dim.visibility = View.VISIBLE
    }
}
