package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import androidx.camera.video.Quality
import androidx.camera.view.PreviewView
import org.json.JSONObject
import java.io.File
import kotlin.math.min

/** Colours of the Verification and Edit-your-profile pages (pages/GenderVerification.jsx, ProfileBuilder.jsx N) */
private object VC {
    val head = Ui.c("#4C1D95"); val headBtn = Ui.c("#5E31AD"); val onHead = Ui.c("#DCCFF5"); val ground = Ui.c("#F6F3FB"); val line = Ui.c("#E7E0F2")
    val tint = Ui.c("#F1EAFB"); val wineLine = Ui.c("#DCCFF5"); val ink = Ui.c("#1E1433"); val sub = Ui.c("#5F5673")
    val green = Ui.c("#15803D"); val greenBg = Ui.c("#E8F7EE"); val amber = Ui.c("#B45309"); val amberBg = Ui.c("#FFF4E0"); val red = Ui.c("#B91C1C"); val redBg = Ui.c("#FDECEC")
}

/** The purple top that stays while the page scrolls: back button + title + "TrueFind". */
internal fun stickyTop(ctx: Context, title: String, onBack: () -> Unit): LinearLayout {
    val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(VC.head); setPadding(Ui.dpi(16f), Ui.dpi(52f), Ui.dpi(16f), Ui.dpi(14f)) }
    bar.addView(PUi.headBtn(ctx, "Back", PUi.lu(Lu.ChevronLeft, Color.WHITE, 22f, 2.2f)) { onBack() }, LinearLayout.LayoutParams(Ui.dpi(44f), Ui.dpi(44f)).apply { rightMargin = Ui.dpi(12f) })
    val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    t.addView(Tf.t(ctx, title, 17f, Color.WHITE).lineH(22f).apply { tag = "title" })
    t.addView(Tf.t(ctx, "TrueFind", 13f, VC.onHead, false).lineH(18f))
    bar.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
    return bar
}

/** fade + rise in (.22 s), like tfv-in / tfb-in */
internal fun riseIn(v: View, delay: Long = 0) {
    v.alpha = 0f; v.translationY = Ui.dp(8f)
    v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
}

/**
 * True Find · Verification (/register/verification, pages/GenderVerification.jsx), native:
 * women record a 6-second live video (a pose and 4 numbers to say) and add their UPI;
 * men confirm the age check. Same flat purple + white look, back button always at the top.
 */
@SuppressLint("ViewConstructor")
class VerifyScreen(ctx: Context, private val pages: NativePages) : FrameLayout(ctx) {
    private var shown = false
    private var profile: JSONObject? = null
    private var loading = false
    private var loadFailed = false
    private var submitting = false
    private var error = ""
    private var resubmit = false
    private var seq = 0
    private val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true }
    private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(16f), Ui.dpi(18f), Ui.dpi(16f), Ui.dpi(28f)) }
    private var form: VideoUpiForm? = null

    init {
        setBackgroundColor(VC.ground)
        isClickable = true
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(stickyTop(ctx, "Verification") { goBack() }, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(body, LayoutParams(-1, -2))
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(col, LayoutParams(-1, -1))
    }

    fun setShown(on: Boolean) {
        val was = shown
        shown = on
        visibility = if (on) VISIBLE else GONE
        if (!on) { if (was && !pages.onVerify) reset(); return }
        if (!was && profile == null && !loading) load()
    }
    private fun reset() { seq++; form?.release(); form = null; profile = null; loading = false; loadFailed = false; submitting = false; error = ""; resubmit = false; body.removeAllViews() }
    fun onBack(): Boolean = false
    fun onAppVisible(v: Boolean) {}

    private fun load() {
        val my = ++seq
        loading = true; loadFailed = false; render()
        Api.me { r ->
            if (my != seq) return@me
            val id = PR.s(r.data, "id")
            if (!r.ok || id.isEmpty()) { failed(my, r.status); return@me }
            Api.filter("UserProfile", JSONObject().put("created_by_id", id), null, 1) { r2 ->
                if (my != seq) return@filter
                if (!r2.ok) { failed(my, r2.status); return@filter }
                val p = PR.list(r2).firstOrNull()
                loading = false
                // new members sign up on the Welcome screens
                if (p == null) { pages.go("/welcome", null, true); return@filter }
                profile = p
                render()
            }
        }
    }
    private fun failed(my: Int, status: Int) {
        if (my != seq) return
        loading = false
        // signed out: to the Welcome screens; a weak network says so and offers "Try again"
        if (status == 401 || status == 403) { pages.go("/welcome", null, true); return }
        loadFailed = true; render()
    }

    private val alreadyRegistered get() = profile != null

    private fun goNext() {
        Api.invoke("updateMyProfile", JSONObject().put("updates", JSONObject().put("registration_step", "complete").put("profile_complete", true))) {
            pages.go(if (alreadyRegistered) "/home" else "/register/complete-profile", null, alreadyRegistered)
        }
    }
    private fun goBack() {
        if (pages.preview.isNotEmpty()) { pages.activity.finish(); return }
        pages.js("(function(){return String(history.length)})()") { n ->
            if ((n?.toIntOrNull() ?: 1) > 1) pages.backBy(1) else pages.go(if (alreadyRegistered) "/home" else "/register/basic-profile", null, true)
        }
    }

    private fun submitAge() {
        submitting = true; error = ""; render()
        val p = profile
        // an age check the admin already passed must not go back to "pending"
        if (PR.s(p, "age_verification_status") == "verified") { submitting = false; pages.go(if (alreadyRegistered) "/home" else "/register/complete-profile", null, alreadyRegistered); render(); return }
        Api.invoke("updateMyProfile", JSONObject().put("updates", JSONObject().put("age_verification_status", "pending").put("registration_step", "complete"))) { r ->
            submitting = false
            if (r.ok) pages.go(if (alreadyRegistered) "/home" else "/register/complete-profile", null, alreadyRegistered)
            else error = PR.s(r.data, "error").ifEmpty { if (r.status > 0) "Request failed with status code ${r.status}" else "Network Error" }
            render()
        }
    }

    // ── pieces ──
    private fun card(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = Tf.box(Color.WHITE, 18f, 1f, VC.line); setPadding(Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f)) }
    internal fun cardHead(icon: String, title: String, sub: String?, right: View?): LinearLayout = vCardHead(context, icon, title, sub, right)
    private fun gap() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(14f) }

    private fun render() {
        val ctx = context
        val keepForm = form
        body.removeAllViews()
        if (loading) {
            val f = FrameLayout(ctx).apply { setPadding(0, Ui.dpi(60f), 0, Ui.dpi(60f)) }
            f.addView(TfLoader(ctx, 28f, VC.head), FrameLayout.LayoutParams(Ui.dpi(28f), Ui.dpi(28f), Gravity.CENTER))
            body.addView(f, LinearLayout.LayoutParams(-1, -2))
            return
        }
        val p = profile
        if (loadFailed && p == null) {
            val c = card()
            c.addView(cardHead(Lu.RefreshCw, "Could not load your verification", "Please check your internet and try again.", null))
            c.addView(vBtn(ctx, "Try again", "primary", Lu.RefreshCw, 16f) { load() }, LinearLayout.LayoutParams(-1, Ui.dpi(48f)))
            body.addView(c, LinearLayout.LayoutParams(-1, -2)); riseIn(c)
            return
        }
        if (p == null) return
        val isFemale = PR.s(p, "gender") == "female"
        val status = PR.creatorStatus(p)
        val showForm = isFemale && (status == "none" || resubmit)
        // title
        val hd = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dpi(2f), Ui.dpi(2f), Ui.dpi(2f), Ui.dpi(4f)) }
        val t = FrameLayout(ctx).apply { background = Tf.box(VC.head, 14f) }
        t.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.ShieldCheck, Color.WHITE, 25f, 2.1f)) }, LayoutParams(-2, -2, Gravity.CENTER))
        hd.addView(t, LinearLayout.LayoutParams(Ui.dpi(48f), Ui.dpi(48f)).apply { rightMargin = Ui.dpi(12f) })
        val tc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        tc.addView(Tf.t(ctx, if (isFemale) "Live video verification" else "Age verification", 19f, VC.ink).lineH(24f))
        tc.addView(PUi.para(ctx, if (isFemale) "Record a short live video and add your UPI details. This keeps True Find safe and unlocks earning." else "Male accounts complete an age verification check.", 13f, VC.sub, 18f),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(3f) })
        hd.addView(tc, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(hd, LinearLayout.LayoutParams(-1, -2))
        if (keepForm == null) riseIn(hd)
        if (error.isNotEmpty()) body.addView(vNote(ctx, error), gap())
        if (isFemale) {
            if (showForm) {
                val f = keepForm ?: VideoUpiForm(ctx, pages) { fields ->
                    val np = JSONObject(p.toString()); fields.keys().forEach { np.put(it, fields.get(it)) }
                    profile = np; resubmit = false; form?.release(); form = null
                    goNext()
                }.also { form = it }
                for (v in f.views) { (v.parent as? LinearLayout)?.removeView(v); body.addView(v, gap()) }
                return
            }
            form?.release(); form = null
            if (status == "rejected") {
                val c = card()
                c.addView(cardHead(Lu.XCircle, "Verification not approved", "Please record a new, clear live video.", badge(ctx, "rejected")))
                val reason = PR.s(p, "creator_reject_reason")
                if (reason.isNotEmpty()) c.addView(PUi.para(ctx, "Reason: $reason", 13f, Ui.c("#991B1B"), 18f).apply { background = Tf.box(VC.redBg, 12f); setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f)) },
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(14f) })
                c.addView(vBtn(ctx, "Try again", "primary", Lu.RefreshCw, 16f) { resubmit = true; render() }, LinearLayout.LayoutParams(-1, Ui.dpi(48f)))
                body.addView(c, gap()); riseIn(c)
            } else {
                val c = card()
                val ok = status == "approved"
                c.addView(cardHead(if (ok) Lu.CheckCircle2 else Lu.Clock, if (ok) "You are verified" else "Submitted for review",
                    if (ok) "Earning features are unlocked for you." else "Our team will check your video soon. You can use the app meanwhile.", badge(ctx, status)))
                c.addView(vBtn(ctx, "Continue") { goNext() }, LinearLayout.LayoutParams(-1, Ui.dpi(48f)))
                body.addView(c, gap()); riseIn(c)
            }
            return
        }
        // men: the age check
        val c = card()
        c.addView(cardHead(Lu.ShieldCheck, "Age verification", "Confirm you are 18 or older.", badge(ctx, PR.s(p, "age_verification_status"))))
        c.addView(PUi.para(ctx, "Your date of birth is already captured from your profile. We confirm your age to keep the community safe. A government ID may be requested during review.", 13.5f, VC.sub, 19f),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(14f) })
        val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = Tf.box(VC.ground, 14f, 1f, VC.line); setPadding(Ui.dpi(14f), Ui.dpi(4f), Ui.dpi(14f), Ui.dpi(4f)) }
        listOf("Registered age" to "${PR.s(p, "age")} years", "Date of birth" to PR.s(p, "date_of_birth")).forEachIndexed { i, (a, b) ->
            if (i > 0) info.addView(View(ctx).apply { setBackgroundColor(VC.line) }, LinearLayout.LayoutParams(-1, Ui.dpi(1f)))
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dpi(10f), 0, Ui.dpi(10f)) }
            r.addView(Tf.t(ctx, a, 13.5f, VC.sub, false), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Ui.dpi(12f) })
            r.addView(Tf.t(ctx, b, 14f, VC.ink))
            info.addView(r)
        }
        c.addView(info, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(16f) })
        val b = vBtn(ctx, if (submitting) "Submitting…" else "Confirm & continue", busySpin = submitting) { if (!submitting) submitAge() }
        b.alpha = if (submitting) 0.5f else 1f
        c.addView(b, LinearLayout.LayoutParams(-1, Ui.dpi(48f)))
        body.addView(c, gap()); riseIn(c)
    }

    private fun badge(ctx: Context, status: String): TextView {
        val (label, icon, bg, fg) = when (status) {
            "verified", "approved" -> Quad("Verified", Lu.CheckCircle2, VC.greenBg, VC.green)
            "rejected" -> Quad("Rejected", Lu.XCircle, VC.redBg, VC.red)
            else -> Quad("Pending review", Lu.Clock, VC.amberBg, VC.amber)
        }
        return Tf.t(ctx, label, 12f, fg).apply {
            gravity = Gravity.CENTER_VERTICAL; background = Tf.box(bg, 999f); setPadding(Ui.dpi(9f), 0, Ui.dpi(9f), 0); maxLines = 1
            compoundDrawablePadding = Ui.dpi(4f); setCompoundDrawablesWithIntrinsicBounds(PUi.lu(icon, fg, 13f), null, null, null)
            layoutParams = LinearLayout.LayoutParams(-2, Ui.dpi(24f))
        }
    }
    private data class Quad(val a: String, val b: String, val c: Int, val d: Int)

    fun previewOpen(what: String) {
        when (what) {
            "male" -> { profile = JSONObject(profile.toString()).put("gender", "male"); render() }
            "pending" -> { profile = JSONObject(profile.toString()).put("creator_submitted_at", "2026-11-17T10:00:00.000Z"); render() }
            "error" -> { error = "Could not submit. Please try again."; render() }
        }
    }
}

internal fun vCardHead(ctx: Context, icon: String, title: String, sub: String?, right: View?): LinearLayout {
    val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val t = FrameLayout(ctx).apply { background = Tf.box(VC.tint, 11f) }
    t.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(icon, VC.head, 20f, 2.1f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    r.addView(t, LinearLayout.LayoutParams(Ui.dpi(40f), Ui.dpi(40f)).apply { rightMargin = Ui.dpi(12f) })
    val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    c.addView(Tf.t(ctx, title, 15f, VC.ink).lineH(20f))
    if (!sub.isNullOrEmpty()) c.addView(PUi.para(ctx, sub, 12.5f, VC.sub, 17f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(2f) })
    r.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
    if (right != null) r.addView(right, (right.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(-2, -2))
    (right?.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = Ui.dpi(12f)
    return LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(14f) }) }
}

/** Btn of the verification page: primary purple, light white, record red; 48 high, round 14, bold 15 */
internal fun vBtn(ctx: Context, label: String, kind: String = "primary", icon: String? = null, iconSize: Float = 17f, busySpin: Boolean = false, dot: Boolean = false, onClick: () -> Unit): LinearLayout {
    val (bg, fg, stroke) = when (kind) {
        "light" -> Triple(Color.WHITE, VC.head, VC.line)
        "record" -> Triple(VC.red, Color.WHITE, 0)
        else -> Triple(VC.head, Color.WHITE, 0)
    }
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        background = if (stroke != 0) Tf.box(bg, 14f, 1.5f, stroke) else Tf.box(bg, 14f)
        if (busySpin) addView(TfLoader(ctx, 18f, fg), LinearLayout.LayoutParams(Ui.dpi(18f), Ui.dpi(18f)).apply { rightMargin = Ui.dpi(8f) })
        if (dot) addView(View(ctx).apply { background = Tf.circle(Color.WHITE) }, LinearLayout.LayoutParams(Ui.dpi(12f), Ui.dpi(12f)).apply { rightMargin = Ui.dpi(8f) })
        if (icon != null) addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(icon, fg, iconSize)) }, LinearLayout.LayoutParams(Ui.dpi(iconSize), Ui.dpi(iconSize)).apply { rightMargin = Ui.dpi(8f) })
        addView(Tf.t(ctx, label, 15f, fg))
        setOnClickListener { onClick() }
        Ui.pressable(this, .97f)
    }
}

internal fun vNote(ctx: Context, text: String): TextView = PUi.para(ctx, text, 13f, Ui.c("#991B1B"), 18f, bold = true).apply {
    gravity = Gravity.CENTER; background = Tf.box(VC.redBg, 12f); setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f))
}

/**
 * Step 1 · the 6-second live video (a pose and 4 numbers to say, a still from 2.5 s) and
 * Step 2 · UPI details, then "Submit for verification".
 */
class VideoUpiForm(private val ctx: Context, private val pages: NativePages, private val onSubmitted: (JSONObject) -> Unit) {
    companion object {
        const val REC_SECONDS = 6
        // poses avoid "left / right" so the mirrored camera picture never confuses anyone
        val POSES = listOf("Raise one hand above your head ✋", "Show 3 fingers to the camera", "Give a thumbs up 👍", "Make a V (peace) sign with your fingers ✌️",
            "Touch your nose with one finger", "Put one hand on your cheek", "Hold your open palm next to your face")
        val UPI_RE = Regex("^[a-zA-Z0-9._-]{2,256}@[a-zA-Z][a-zA-Z0-9]{1,63}$")
    }
    private val main = Handler(Looper.getMainLooper())
    private val pose = POSES[(Math.random() * POSES.size).toInt().coerceAtMost(POSES.size - 1)]
    private val code = (1..4).map { (Math.random() * 9).toInt().coerceAtMost(8) + 1 }.joinToString(" ")
    private var cam = "idle"      // idle | starting | ready | recording | recorded | denied | error
    private var left = REC_SECONDS
    private var clip: File? = null
    private var still: ByteArray? = null
    private var upiName = ""
    private var upiId = ""
    private var submitting = false
    private var error = ""
    private var alive = true

    private val preview = PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER; setBackgroundColor(Color.BLACK) }
    private val core = CamCore(ctx, pages.activity, preview, Quality.SD, 800_000)
    private val player = VideoView(ctx)
    private val playIcon = ImageView(ctx)
    private val box = object : FrameLayout(ctx) {
        override fun onMeasure(w: Int, h: Int) { val width = MeasureSpec.getSize(w); super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((width * 4f / 3f).toInt(), MeasureSpec.EXACTLY)) }
    }
    private val overlay = FrameLayout(ctx)
    private val rec: TextView
    private val recDot = LiveBlinkDot(ctx, Ui.c("#EF4444"), 1000, .35f)
    private val btnBox = FrameLayout(ctx)
    private val note = FrameLayout(ctx)
    private val head1 = FrameLayout(ctx)
    private val upiHint: TextView
    private val submitBox = FrameLayout(ctx)
    val views: List<View>

    init {
        // step 1
        val c1 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = Tf.box(Color.WHITE, 18f, 1f, VC.line); setPadding(Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f)) }
        c1.addView(head1, LinearLayout.LayoutParams(-1, -2))
        val poseBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = Tf.box(VC.amberBg, 14f, 1f, Ui.c("#FED7AA")); setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f)) }
        poseBox.addView(Tf.t(ctx, "In your video, do this pose", 12f, Ui.c("#9A3412"), false).lineH(16f).apply { gravity = Gravity.CENTER })
        poseBox.addView(Tf.t(ctx, pose, 15f, Ui.c("#7C2D12")).lineH(20f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dpi(3f) })
        poseBox.addView(Tf.t(ctx, "and say these numbers out loud", 12f, Ui.c("#9A3412"), false).lineH(16f).apply {
            gravity = Gravity.CENTER; compoundDrawablePadding = Ui.dpi(4f); setCompoundDrawablesWithIntrinsicBounds(PUi.lu(Lu.Mic, Ui.c("#9A3412"), 13f), null, null, null)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dpi(8f) })
        poseBox.addView(Tf.t(ctx, code, 24f, VC.ink).lineH(30f).apply { gravity = Gravity.CENTER; letterSpacing = 0.3f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dpi(2f) })
        c1.addView(poseBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(12f) })
        box.background = Tf.box(VC.tint, 14f, 1f, VC.wineLine)
        box.clipToOutline = true
        box.addView(preview, FrameLayout.LayoutParams(-1, -1))
        player.setBackgroundColor(Color.TRANSPARENT)
        box.addView(player, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        playIcon.setImageDrawable(Tf.svg("<svg viewBox=\"0 0 64 64\"><circle cx=\"32\" cy=\"32\" r=\"30\" fill=\"#000\" fill-opacity=\".45\"/><path d=\"M26 20v24l19-12z\" fill=\"#fff\"/></svg>", 56f))
        box.addView(playIcon, FrameLayout.LayoutParams(Ui.dpi(56f), Ui.dpi(56f), Gravity.CENTER))
        box.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        val recRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = Tf.box(Color.argb(184, 30, 20, 51), 999f); setPadding(Ui.dpi(12f), Ui.dpi(5f), Ui.dpi(12f), Ui.dpi(5f)) }
        recRow.addView(recDot, LinearLayout.LayoutParams(Ui.dpi(10f), Ui.dpi(10f)).apply { rightMargin = Ui.dpi(7f) })
        rec = Tf.t(ctx, "", 13f, Color.WHITE)
        recRow.addView(rec)
        box.addView(recRow, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dpi(12f); topMargin = Ui.dpi(12f) })
        recRow.tag = "rec"
        c1.addView(box, LinearLayout.LayoutParams(-1, -2))
        c1.addView(btnBox, LinearLayout.LayoutParams(-1, Ui.dpi(48f)).apply { topMargin = Ui.dpi(14f) })
        player.setOnPreparedListener { mp -> mp.isLooping = false; mp.seekTo(1) }
        player.setOnCompletionListener { playIcon.visibility = View.VISIBLE }
        player.setOnErrorListener { _, _, _ -> true }
        box.setOnClickListener {
            if (cam != "recorded") return@setOnClickListener
            if (player.isPlaying) { player.pause(); playIcon.visibility = View.VISIBLE } else { player.start(); playIcon.visibility = View.GONE }
        }
        // step 2
        val c2 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = Tf.box(Color.WHITE, 18f, 1f, VC.line); setPadding(Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f)) }
        c2.addView(vCardHead(ctx, Lu.Wallet, "Step 2 · UPI details", "Your earnings will be paid to this UPI.", null))
        c2.addView(label("Full name (exactly as on your UPI / bank account)"))
        val nameIn = TfInput(ctx, "e.g. Priya Sharma").apply { maxLen(60); contentDescription = "Full name on your UPI" }
        nameIn.onText = { upiName = it; paintSubmit() }
        c2.addView(nameIn, LinearLayout.LayoutParams(-1, Ui.dpi(48f)).apply { bottomMargin = Ui.dpi(14f) })
        c2.addView(label("UPI ID"))
        val idIn = TfInput(ctx, "e.g. 9876543210@ybl").apply {
            maxLen(80); contentDescription = "UPI ID"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        idIn.onText = { t -> val c = t.replace(Regex("\\s"), ""); if (c != t) { idIn.setText(c); idIn.setSelection(c.length) }; upiId = c; paintSubmit() }
        c2.addView(idIn, LinearLayout.LayoutParams(-1, Ui.dpi(48f)))
        upiHint = Tf.t(ctx, "Enter a valid UPI ID like name@bank", 12.5f, VC.red).apply { visibility = View.GONE }
        c2.addView(upiHint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(6f); leftMargin = Ui.dpi(2f); rightMargin = Ui.dpi(2f) })
        val foot = Tf.t(ctx, "Your video is private. Only the True Find team can see it.", 12.5f, VC.sub, false).lineH(17f).apply {
            gravity = Gravity.CENTER; compoundDrawablePadding = Ui.dpi(6f); setCompoundDrawablesWithIntrinsicBounds(PUi.lu(Lu.Lock, VC.sub, 13f), null, null, null)
        }
        val footBox = FrameLayout(ctx).apply { addView(foot, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL)) }
        views = listOf(note, c1, c2, submitBox, footBox)
        riseIn(c1); riseIn(c2, 60)
        render()
    }

    private fun label(t: String): TextView = Tf.t(ctx, t, 13f, VC.sub).lineH(18f).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(6f) } }

    fun release() {
        alive = false
        main.removeCallbacksAndMessages(null)
        core.release()
        try { player.stopPlayback() } catch (e: Throwable) { }
        clip?.delete()
    }

    private fun render() {
        // error note
        note.removeAllViews()
        if (error.isNotEmpty()) note.addView(vNote(ctx, error), FrameLayout.LayoutParams(-1, -2))
        note.visibility = if (error.isNotEmpty()) View.VISIBLE else View.GONE
        // step 1 head (✓ when there is a clip)
        head1.removeAllViews()
        head1.addView(vCardHead(ctx, Lu.Video, "Step 1 · Live video ($REC_SECONDS sec)", "Camera only. Gallery videos are not allowed.",
            if (clip != null) PUi.icon(ctx, PUi.lu(Lu.CheckCircle2, VC.green, 24f), 24f).apply { layoutParams = LinearLayout.LayoutParams(Ui.dpi(24f), Ui.dpi(24f)) } else null))
        // the picture
        val live = cam == "ready" || cam == "recording"
        preview.visibility = if (live) View.VISIBLE else View.GONE
        val showClip = cam == "recorded" && clip != null
        player.visibility = if (showClip) View.VISIBLE else View.GONE
        playIcon.visibility = if (showClip && !player.isPlaying) View.VISIBLE else View.GONE
        box.findViewWithTag<View>("rec")?.visibility = if (cam == "recording") View.VISIBLE else View.GONE
        rec.text = "REC ${left}s"
        overlay.removeAllViews()
        when (cam) {
            "starting" -> overlay.addView(TfLoader(ctx, 30f, VC.head), FrameLayout.LayoutParams(Ui.dpi(30f), Ui.dpi(30f), Gravity.CENTER))
            "idle" -> {
                val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(Ui.dpi(24f), 0, Ui.dpi(24f), 0) }
                val ib = FrameLayout(ctx).apply { background = Tf.box(Color.WHITE, 16f) }
                ib.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.Video, VC.head, 28f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                c.addView(ib, LinearLayout.LayoutParams(Ui.dpi(56f), Ui.dpi(56f)).apply { bottomMargin = Ui.dpi(10f) })
                c.addView(PUi.para(ctx, "Good light, face clearly visible, no filters. Speak clearly.", 13.5f, VC.sub, 19f).apply { gravity = Gravity.CENTER })
                overlay.addView(c, FrameLayout.LayoutParams(-1, -1))
            }
            "denied", "error" -> {
                val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(Ui.dpi(24f), 0, Ui.dpi(24f), 0) }
                c.addView(PUi.icon(ctx, PUi.lu(Lu.XCircle, VC.red, 34f), 34f).apply { layoutParams = LinearLayout.LayoutParams(Ui.dpi(34f), Ui.dpi(34f)).apply { bottomMargin = Ui.dpi(8f) } })
                c.addView(PUi.para(ctx, if (cam == "denied") "Camera or microphone is blocked" else "Could not start the camera", 14f, VC.ink, 19f, bold = true).apply { gravity = Gravity.CENTER })
                c.addView(PUi.para(ctx, "Allow camera and microphone for this app in your phone or browser settings, then try again.", 12.5f, VC.sub, 17f).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(4f) })
                overlay.addView(c, FrameLayout.LayoutParams(-1, -1))
            }
        }
        // the button under it
        btnBox.removeAllViews()
        val b: View = when (cam) {
            "ready" -> vBtn(ctx, "Start recording", "record", dot = true) { startRecording() }
            "recording" -> vBtn(ctx, "Recording… ${left}s") { }.apply { alpha = 0.5f; isEnabled = false }
            "recorded" -> vBtn(ctx, "Record again", "light", Lu.RefreshCw, 16f) { if (!submitting) retake() }.apply { if (submitting) { alpha = 0.5f; isEnabled = false } }
            else -> vBtn(ctx, "Open camera", "primary", Lu.Video, 17f) { if (cam != "starting") startCamera() }.apply { if (cam == "starting") { alpha = 0.5f; isEnabled = false } }
        }
        btnBox.addView(b, FrameLayout.LayoutParams(-1, -1))
        paintSubmit()
    }

    private fun paintSubmit() {
        val upiOk = UPI_RE.matches(upiId.trim())
        upiHint.visibility = if (upiId.isNotEmpty() && !upiOk) View.VISIBLE else View.GONE
        val can = clip != null && upiName.trim().length >= 3 && upiOk && !submitting
        submitBox.removeAllViews()
        val b = vBtn(ctx, if (submitting) "Uploading…" else "Submit for verification", busySpin = submitting) { if (clip != null && upiName.trim().length >= 3 && UPI_RE.matches(upiId.trim()) && !submitting) submit() }
        b.alpha = if (can) 1f else 0.5f
        submitBox.addView(b, FrameLayout.LayoutParams(-1, Ui.dpi(50f)))
    }

    private fun startCamera() {
        error = ""
        cam = "starting"; render()
        pages.askCamera { ok ->
            if (!alive) return@askCamera
            if (!ok) { cam = "denied"; render(); return@askCamera }
            core.start({ if (alive) { cam = "ready"; render() } }) { if (alive) { cam = "error"; render() } }
        }
    }

    private fun startRecording() {
        error = ""
        still = null
        val file = File(ctx.cacheDir, "verify-${System.currentTimeMillis()}.mp4")
        val ok = core.record(file) { good ->
            main.removeCallbacks(tick)
            if (!alive) { file.delete(); return@record }
            core.stop()
            if (!good) { error = "Recording failed. Please try again."; cam = "idle"; render(); return@record }
            clip?.delete(); clip = file
            cam = "recorded"; render()
            player.setVideoPath(file.absolutePath)
        }
        if (!ok) { cam = "error"; render(); return }
        cam = "recording"; left = REC_SECONDS; render()
        // a still from the middle of the video, to compare with the profile photo
        main.postDelayed({ if (alive && cam == "recording") still = core.still(85) }, 2500)
        main.postDelayed(tick, 1000)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (cam != "recording") return
            left -= 1; render()
            if (left <= 0) core.stopRecording() else main.postDelayed(this, 1000)
        }
    }

    private fun retake() {
        try { player.stopPlayback() } catch (e: Throwable) { }
        clip?.delete(); clip = null
        startCamera()
    }

    private fun submit() {
        val f = clip ?: return
        submitting = true; error = ""; render()
        val shot = still
        val name = upiName.trim(); val id = upiId.trim().lowercase()
        Api.async({
            val bytes = try { f.readBytes() } catch (e: Throwable) { null }
            val up = if (bytes != null) PUp.send(true, bytes, "verify-${System.currentTimeMillis()}.mp4", "video/mp4") else null
            val uri = if (up != null && up.ok) PR.s(up.data, "file_uri") else ""
            var selfie = ""
            if (uri.isNotEmpty() && shot != null) {
                // the video is what matters; the still is a bonus
                val pic = PUp.decode(shot, 1440)
                val jpg = if (pic != null) PUp.jpeg(pic, 1440, 0.82f) else null
                pic?.bmp?.recycle()
                if (jpg != null) { val r = PUp.send(true, jpg, "verify-${System.currentTimeMillis()}.jpg", "image/jpeg"); if (r.ok) selfie = PR.s(r.data, "file_uri") }
            }
            Triple(uri, selfie, up)
        }) { (uri, selfie, up) ->
            if (!alive) return@async
            if (uri.isEmpty()) {
                error = if (up != null && !up.ok) PR.s(up.data, "error").ifEmpty { "Video upload failed. Please try again." } else "Video upload failed. Please try again."
                submitting = false; render(); return@async
            }
            // the server checks the application and saves it (the app cannot write verification fields itself)
            val apply = JSONObject().put("kind", "creator").put("video_uri", uri).put("selfie_uri", selfie).put("pose", pose).put("code", code).put("upi_name", name).put("upi_id", id)
            Api.invoke("updateMyProfile", JSONObject().put("apply", apply), 60000) { r ->
                if (!alive) return@invoke
                val saved = r.data.optJSONObject("profile")
                if (!r.ok || saved == null) {
                    error = PR.s(r.data, "error").ifEmpty { if (!r.ok && r.status > 0) "Request failed with status code ${r.status}" else if (r.status == 0) "Network Error" else "Could not submit. Please try again." }
                    submitting = false; render(); return@invoke
                }
                val fields = JSONObject()
                for (k in listOf("creator_video_uri", "creator_selfie_uri", "creator_pose", "creator_code", "creator_upi_name", "creator_upi_id", "creator_submitted_at", "creator_status"))
                    fields.put(k, saved.opt(k) ?: JSONObject.NULL)
                onSubmitted(fields)
            }
        }
    }

    @Suppress("unused") private fun keep() = min(1, 2)
}
