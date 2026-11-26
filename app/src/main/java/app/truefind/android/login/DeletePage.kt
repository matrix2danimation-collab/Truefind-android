package app.truefind.android.login

import android.content.Context
import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import app.truefind.android.TfLoader
import app.truefind.android.Ui
import org.json.JSONObject

/**
 * /delete-account (public, no sign-in): how to delete a True Find account, and a form for people who can't
 * open the app any more (DeleteAccount.jsx; Google Play needs this page). Requests go to the "support"
 * function (action deletion_request), like the web.
 */
internal class DeletePage(ctx: Context, layer: LoginLayer) : LPage(ctx, layer) {
    private val ink = LK.c("#17121F"); private val sub = LK.c("#5E586B"); private val soft = LK.c("#8A829B"); private val line = LK.c("#E9E6EE")
    private val purple = LK.c("#4C1D95"); private val tint = LK.c("#F3EEFB"); private val red = LK.c("#B91C1C")
    private var built = false
    private var busy = false
    private var sent = false
    private lateinit var form: FrameLayout

    init { setBackgroundColor(Color.WHITE) }

    override fun route(search: String) {
        if (built) return
        built = true
        val ctx = context
        val col = LK.col(ctx).apply { setPadding(Ui.dpi(16f), Ui.dpi(26f), Ui.dpi(16f), Ui.dpi(40f)) }
        col.addView(LK.t(ctx, "True Find", 22f, purple, true), LK.lp(t = 6f, b = 18f))
        val tile = FrameLayout(ctx).apply { background = CssBox(tint, 14f) }
        tile.addView(ImageView(ctx).apply { setImageDrawable(LK.lu(LK.I.Trash2, purple, 22f)) }, FrameLayout.LayoutParams(Ui.dpi(22f), Ui.dpi(22f), Gravity.CENTER))
        col.addView(tile, LK.lp(Ui.dpi(48f), Ui.dpi(48f)))
        col.addView(LK.t(ctx, "Delete your account", 22f, ink, true), LK.lp(t = 12f, b = 4f))
        col.addView(LK.t(ctx, "You can delete your True Find account and its data at any time.", 14f, sub, false, 20f / 14f), LK.lp(-1, -2))

        // In the app (fastest)
        val steps = LK.col(ctx)
        val items = listOf(
            listOf("Open True Find and log in with your mobile number." to false),
            listOf("Go to " to false, "Profile → Settings" to true, " (the gear button)." to false),
            listOf("Tap " to false, "Delete account" to true, " and type DELETE to confirm." to false),
        )
        for ((i, parts) in items.withIndex()) {
            val row = LK.row(ctx, Gravity.TOP)
            row.addView(LK.t(ctx, "${i + 1}.", 13.5f, sub, false, 19f / 13.5f).apply { gravity = Gravity.END }, LK.lp(Ui.dpi(16f), -2, r = 4f))
            val sb = SpannableStringBuilder()
            for ((t, b) in parts) {
                val st = sb.length; sb.append(t)
                if (b) { sb.setSpan(ForegroundColorSpan(ink), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(BoldSpan(), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
            row.addView(LK.t(ctx, sb, 13.5f, sub, false, 19f / 13.5f), LK.lp(0, -2, weight = 1f))
            steps.addView(row, LK.lp(-1, -2, b = 6f))
        }
        val open = LK.t(ctx, "Open True Find", 15f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = CssBox(ink, 12f) }
        LK.click(open, 0.97f) { layer.go("/welcome") }
        steps.addView(open, LK.lp(-1, Ui.dpi(44f), t = 2f))
        col.addView(section("In the app (fastest)", steps))

        // What is deleted
        val what = LK.col(ctx)
        what.addView(LK.t(ctx, "Your profile, photos, chats, coins, followers and Reconnect data.", 13.5f, sub, false, 19f / 13.5f), LK.lp(-1, -2, b = 6f))
        what.addView(LK.t(ctx, "We keep only what the law or safety needs: payment and withdrawal records (for tax), call records (without your name), and records of reports and bans, for as long as required. If you are a Creator or Host, please withdraw your earnings first.", 13.5f, sub, false, 19f / 13.5f), LK.lp(-1, -2))
        col.addView(section("What is deleted", what))

        // Can't open the app?
        form = FrameLayout(ctx)
        col.addView(section("Can't open the app?", form))
        col.addView(LK.t(ctx, "© True Find · Made with love in India", 12f, soft).apply { gravity = Gravity.CENTER }, LK.lp(-1, -2, t = 18f))
        drawForm()

        val sc = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true }
        val wrap = object : FrameLayout(ctx) {
            override fun onMeasure(w: Int, h: Int) { super.onMeasure(MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(w), Ui.dpi(520f)), MeasureSpec.EXACTLY), h) }
        }
        wrap.addView(col, LayoutParams(-1, -2))
        val center = FrameLayout(ctx).apply { addView(wrap, LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL)) }
        sc.addView(center, LayoutParams(-1, -2))
        addView(sc, LayoutParams(-1, -1))
    }

    private class BoldSpan : android.text.style.MetricAffectingSpan() {
        override fun updateDrawState(tp: android.text.TextPaint) { tp.typeface = Ui.bold }
        override fun updateMeasureState(tp: android.text.TextPaint) { tp.typeface = Ui.bold }
    }

    private fun section(title: String, body: View): View = LK.col(context).apply {
        background = CssBox(Color.WHITE, 16f, 1f, line)
        setPadding(Ui.dpi(17f), Ui.dpi(15f), Ui.dpi(17f), Ui.dpi(15f))
        addView(LK.t(context, title, 15f, ink, true), LK.lp(b = 8f))
        addView(body, LK.lp(-1, -2))
        layoutParams = LK.lp(-1, -2, t = 12f)
    }

    private fun drawForm() {
        val ctx = context
        form.removeAllViews()
        if (sent) {
            val row = LK.row(ctx, Gravity.TOP)
            row.addView(ImageView(ctx).apply { setImageDrawable(LK.lu("""<circle cx="12" cy="12" r="10"/><path d="m9 12 2 2 4-4"/>""", purple, 22f)) }, LK.lp(Ui.dpi(22f), Ui.dpi(22f), r = 10f))
            row.addView(LK.t(ctx, "Request received. Our team will contact you on this number or email to confirm it is your account, and then delete it.", 13.5f, sub, false, 19f / 13.5f), LK.lp(0, -2, weight = 1f))
            form.addView(row, FrameLayout.LayoutParams(-1, -2))
            return
        }
        val col = LK.col(ctx)
        col.addView(LK.t(ctx, "Send us the mobile number (or email) of your account and we will delete it for you.", 13.5f, sub, false, 19f / 13.5f), LK.lp(-1, -2, b = 10f))
        col.addView(LK.t(ctx, "Mobile number or email", 12.5f, ink, true), LK.lp(b = 6f))
        val contact = WInput(ctx, 15f, "98765 43210", ink, LK.c("#757575")).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            filters = arrayOf(InputFilter.LengthFilter(80))
            setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0)
            background = CssBox(Color.WHITE, 12f, 1.5f, line)
        }
        col.addView(contact, LK.lp(-1, Ui.dpi(44f)))
        col.addView(LK.t(ctx, "Anything else? (optional)", 12.5f, ink, true), LK.lp(t = 12f, b = 6f))
        val note = WInput(ctx, 14f, "", ink).apply {
            isSingleLine = false; maxLines = 3; minLines = 3; gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(500))
            setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f))
            background = CssBox(Color.WHITE, 12f, 1.5f, line)
        }
        col.addView(note, LK.lp(-1, -2))
        val err = LK.t(ctx, "", 13f, red).apply { visibility = View.GONE }
        col.addView(err, LK.lp(-1, -2, t = 8f))
        val btn = FrameLayout(ctx).apply { background = CssBox(purple, 12f) }
        val label = LK.t(ctx, "Request deletion", 15f, Color.WHITE, true)
        val spin = TfLoader(ctx, 18f, Color.WHITE).apply { visibility = View.GONE }
        btn.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)); btn.addView(spin, FrameLayout.LayoutParams(Ui.dpi(18f), Ui.dpi(18f), Gravity.CENTER))
        fun look() {
            val off = busy || contact.value.trim().length < 5
            btn.alpha = if (off) 0.55f else 1f; btn.isEnabled = !off
            label.visibility = if (busy) View.INVISIBLE else View.VISIBLE; spin.visibility = if (busy) View.VISIBLE else View.GONE
        }
        contact.onText = { look() }
        LK.click(btn, 0.97f) {
            if (busy || contact.value.trim().length < 5) return@click
            err.visibility = View.GONE; busy = true; look()
            LoginApi.fn("support", JSONObject().put("action", "deletion_request").put("contact", contact.value.trim()).put("message", note.value.trim())) { r ->
                busy = false
                if (r.ok) { sent = true; drawForm(); return@fn }
                err.text = LoginApi.fnErr(r, "Could not send right now. Please try again."); err.visibility = View.VISIBLE; look()
            }
        }
        col.addView(btn, LK.lp(-1, Ui.dpi(44f), t = 12f))
        look()
        form.addView(col, FrameLayout.LayoutParams(-1, -2))
    }

    override fun preview(variant: String, open: String) { if (variant.contains("sent")) { sent = true; drawForm() } }
}
