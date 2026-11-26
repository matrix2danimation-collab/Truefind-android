package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * True Find · Edit your profile (/profile/edit, pages/ProfileBuilder.jsx), native: photos
 * (up to 5, first is the main one, min 2), name, date of birth, gender (fixed), "interested
 * in" and bio. Opened from the Home Verification bar and from games that need a finished profile.
 */
@SuppressLint("ViewConstructor")
class BuilderScreen(ctx: Context, private val pages: NativePages) : FrameLayout(ctx) {
    private val MAX_IMAGES = 5
    private val head = Ui.c("#4C1D95"); private val ground = Ui.c("#F6F3FB"); private val line = Ui.c("#E7E0F2"); private val tint = Ui.c("#F1EAFB")
    private val dash = Ui.c("#B9A3E6"); private val ink = Ui.c("#1E1433"); private val sub = Ui.c("#5F5673"); private val redBg = Ui.c("#FDECEC")

    private var shown = false
    private var profile: JSONObject? = null
    private var loading = false
    private var saving = false
    private var uploadIndex = -1
    private var error = ""
    private var seq = 0
    // the form
    private var fullName = ""; private var bio = ""; private var birthDate = ""; private var gender = ""; private var preferred = ""
    private val images = ArrayList<Any>()          // stored photo items (as saved)
    private val thumbs = HashMap<String, String>() // photo uri → its small list copy (photos added here)
    private var sheetClose: (() -> Unit)? = null

    private val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true }
    private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dpi(16f), Ui.dpi(18f), Ui.dpi(16f), Ui.dpi(28f)) }
    private val topBar: LinearLayout
    private var built = false
    // pieces kept while typing
    private val errBox = FrameLayout(ctx)
    private val grid = LinearLayout(ctx)
    private val countPill: TextView
    private val dobText: TextView
    private val prefText: TextView
    private val genderText: TextView
    private val saveBox = FrameLayout(ctx)
    private val nameIn: TfInput
    private val bioIn: TfInput
    private val titleView: TextView
    private val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    init {
        setBackgroundColor(ground)
        isClickable = true
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        topBar = stickyTop(ctx, "Edit your profile") { goBack() }
        col.addView(topBar, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(body, LayoutParams(-1, -2))
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(col, LayoutParams(-1, -1))
        // intro
        val intro = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dpi(2f), Ui.dpi(2f), Ui.dpi(2f), Ui.dpi(4f)) }
        val ht = FrameLayout(ctx).apply { background = Tf.box(head, 14f) }
        ht.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.Heart, Color.WHITE, 23f, 2f, "#fff")) }, LayoutParams(-2, -2, Gravity.CENTER))
        intro.addView(ht, LinearLayout.LayoutParams(Ui.dpi(48f), Ui.dpi(48f)).apply { rightMargin = Ui.dpi(12f) })
        val ic = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titleView = Tf.t(ctx, "", 19f, ink).lineH(24f)
        ic.addView(titleView)
        ic.addView(PUi.para(ctx, "Tell people who you are — name, story and photos.", 13f, sub, 18f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(3f) })
        intro.addView(ic, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(intro)
        content.addView(errBox, gap())
        // photos
        val pc = card()
        val ph = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ph.addView(Tf.t(ctx, "Photos", 15f, ink).lineH(20f), LinearLayout.LayoutParams(0, -2, 1f))
        countPill = Tf.t(ctx, "", 12f, head).apply { gravity = Gravity.CENTER; background = Tf.box(tint, 8f); setPadding(Ui.dpi(9f), 0, Ui.dpi(9f), 0) }
        ph.addView(countPill, LinearLayout.LayoutParams(-2, Ui.dpi(24f)))
        pc.addView(ph, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(12f) })
        grid.orientation = LinearLayout.VERTICAL
        pc.addView(grid, LinearLayout.LayoutParams(-1, -2))
        pc.addView(Tf.t(ctx, "JPG or PNG, up to 5MB each.", 12.5f, sub, false).lineH(17f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(10f); leftMargin = Ui.dpi(2f); rightMargin = Ui.dpi(2f) })
        content.addView(pc, gap())
        // details
        val dc = card()
        dc.addView(label("Full name"))
        nameIn = TfInput(ctx, "Your name").apply { contentDescription = "Full name"; onText = { fullName = it } }
        dc.addView(nameIn, LinearLayout.LayoutParams(-1, Ui.dpi(48f)))
        dc.addView(label("Date of birth"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(14f) })
        dobText = selectBox(dc, "Date of birth", Lu.CalendarDays) { pickDate() }
        val two = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val g1 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        g1.addView(label("Gender"))
        genderText = Tf.t(ctx, "", 16f, sub, false).ell().apply { gravity = Gravity.CENTER_VERTICAL; background = Tf.box(ground, 12f, 1.5f, line); setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0) }
        g1.addView(genderText, LinearLayout.LayoutParams(-1, Ui.dpi(48f)))
        two.addView(g1, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Ui.dpi(10f) })
        val g2 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        g2.addView(label("Interested in"))
        prefText = selectBox(g2, "Select", Lu.ChevronDown) { pickPreferred() }
        two.addView(g2, LinearLayout.LayoutParams(0, -2, 1f))
        dc.addView(two, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(14f) })
        dc.addView(Tf.t(ctx, "Gender is set at registration and can't be changed.", 12f, sub, false).lineH(16f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(8f); leftMargin = Ui.dpi(2f); rightMargin = Ui.dpi(2f) })
        dc.addView(label("Bio"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(14f) })
        bioIn = TfInput(ctx, "A short intro about you…", multiLine = true, lines = 3).apply { contentDescription = "Bio"; minHeight = Ui.dpi(96f); onText = { bio = it } }
        dc.addView(bioIn, LinearLayout.LayoutParams(-1, -2))
        content.addView(dc, gap())
        content.addView(saveBox, LinearLayout.LayoutParams(-1, Ui.dpi(50f)).apply { topMargin = Ui.dpi(14f) })
    }

    private fun gap() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(14f) }
    private fun card(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = Tf.box(Color.WHITE, 18f, 1f, line); setPadding(Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f)) }
    private fun label(t: String): TextView = Tf.t(context, t, 13f, sub).lineH(18f).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(6f) } }
    private fun selectBox(into: LinearLayout, desc: String, icon: String, onTap: () -> Unit): TextView {
        val f = FrameLayout(context).apply { background = Tf.box(Color.WHITE, 12f, 1.5f, line); setPadding(Ui.dpi(14f), 0, Ui.dpi(12f), 0); contentDescription = desc; setOnClickListener { onTap() } }
        val t = Tf.t(context, "", 16f, ink, false).ell()
        f.addView(t, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply { rightMargin = Ui.dpi(24f) })
        f.addView(ImageView(context).apply { setImageDrawable(PUi.lu(icon, sub, 17f)) }, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END))
        into.addView(f, LinearLayout.LayoutParams(-1, Ui.dpi(48f)))
        return t
    }

    fun setShown(on: Boolean) {
        val was = shown
        shown = on
        visibility = if (on) VISIBLE else GONE
        if (!on) { if (was && !pages.onBuilder) reset(); return }
        if (!was && profile == null && !loading) load()
    }
    private fun reset() { seq++; sheetClose?.invoke(); profile = null; loading = false; saving = false; uploadIndex = -1; error = ""; images.clear(); thumbs.clear(); body.removeAllViews(); built = false }
    fun onBack(): Boolean = false

    private fun load() {
        val my = ++seq
        loading = true; render()
        Api.me { r ->
            if (my != seq) return@me
            val id = PR.s(r.data, "id")
            if (!r.ok || id.isEmpty()) { failed(my); return@me }
            // only my own profile (an admin can read everyone's)
            Api.filter("UserProfile", JSONObject().put("created_by_id", id), null, 1) { r2 ->
                if (my != seq) return@filter
                if (!r2.ok) { failed(my); return@filter }
                val p = PR.list(r2).firstOrNull()
                loading = false
                // new members sign up on the Welcome screens
                if (p == null) { pages.go("/welcome", null, true); return@filter }
                profile = p
                fullName = PR.s(p, "full_name"); bio = PR.s(p, "bio"); birthDate = PR.s(p, "date_of_birth"); gender = PR.s(p, "gender"); preferred = PR.s(p, "preferred_gender")
                images.clear()
                p.optJSONArray("images")?.let { a -> for (i in 0 until a.length()) a.opt(i)?.let { v -> if (v != JSONObject.NULL) images.add(v) } }
                nameIn.setText(fullName); bioIn.setText(bio)
                render()
            }
        }
    }
    private fun failed(my: Int) { if (my != seq) return; loading = false; error = "Could not load your profile. Please check your internet and try again."; render() }

    private fun goBack() {
        if (pages.preview.isNotEmpty()) { pages.activity.finish(); return }
        if (pages.canGoBack) pages.backBy(1) else pages.go("/profile", null, true)
    }

    private fun render() {
        val ctx = context
        titleView.text = if (profile != null) "Edit your profile" else "Create your profile"
        (topBar.findViewWithTag<TextView>("title"))?.text = if (profile != null) "Edit your profile" else "Create your profile"
        if (loading) {
            body.removeAllViews(); built = false
            val f = FrameLayout(ctx).apply { setPadding(0, Ui.dpi(60f), 0, Ui.dpi(60f)) }
            f.addView(TfLoader(ctx, 28f, head), LayoutParams(Ui.dpi(28f), Ui.dpi(28f), Gravity.CENTER))
            body.addView(f, LinearLayout.LayoutParams(-1, -2))
            return
        }
        if (!built) {
            body.removeAllViews()
            (content.parent as? ViewGroup)?.removeView(content)
            body.addView(content, LinearLayout.LayoutParams(-1, -2))
            riseIn(content)
            built = true
        }
        // error
        errBox.removeAllViews()
        if (error.isNotEmpty()) errBox.addView(PUi.para(ctx, error, 13f, Ui.c("#991B1B"), 18f, bold = true).apply { gravity = Gravity.CENTER; background = Tf.box(redBg, 12f); setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f)) }, LayoutParams(-1, -2))
        errBox.visibility = if (error.isNotEmpty()) VISIBLE else GONE
        // photos
        countPill.text = "${images.size}/$MAX_IMAGES · min 2"
        renderGrid()
        // details
        dobText.text = if (birthDate.isNotEmpty()) showDate(birthDate) else "dd/mm/yyyy"
        dobText.setTextColor(if (birthDate.isNotEmpty()) ink else Ui.c("#9A91AD"))
        genderText.text = when (gender) { "female" -> "Woman"; "male" -> "Man"; else -> "Not set" }
        prefText.text = when (preferred) { "female" -> "Women"; "male" -> "Men"; "both" -> "Everyone"; else -> "Select" }
        prefText.setTextColor(if (preferred.isNotEmpty()) ink else Ui.c("#9A91AD"))
        // save
        saveBox.removeAllViews()
        val b = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; background = Tf.box(head, 14f)
            if (saving) { addView(TfLoader(ctx, 18f, Color.WHITE), LinearLayout.LayoutParams(Ui.dpi(18f), Ui.dpi(18f)).apply { rightMargin = Ui.dpi(8f) }); alpha = 0.55f }
            addView(Tf.t(ctx, if (saving) "Saving…" else if (profile != null) "Save changes" else "Create profile", 15f, Color.WHITE))
            setOnClickListener { if (!saving) save() }
            Ui.pressable(this, .97f)
        }
        saveBox.addView(b, LayoutParams(-1, -1))
    }

    private fun showDate(v: String): String {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(v) ?: return v
        return "${m.groupValues[3]}/${m.groupValues[2]}/${m.groupValues[1]}"
    }

    private fun renderGrid() {
        val ctx = context
        grid.removeAllViews()
        val cells = ArrayList<View>()
        images.forEachIndexed { i, raw ->
            val c = FrameLayout(ctx).apply { background = Tf.box(tint, 14f, 1f, line); clipToOutline = true }
            val img = PImg(ctx, 14f).apply { empty = "none" }
            val cam = ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.Camera, dash, 20f)) }
            c.addView(cam, LayoutParams(-2, -2, Gravity.CENTER))
            c.addView(img, LayoutParams(-1, -1))
            img.onLoaded = { ok -> cam.visibility = if (ok) GONE else VISIBLE }
            img.set(listOf(PR.srcOf(raw)))
            img.contentDescription = "Photo ${i + 1}"
            if (i == 0) c.addView(Tf.t(ctx, "Main", 11f, Color.WHITE).apply { gravity = Gravity.CENTER; background = Tf.box(head, 6f); setPadding(Ui.dpi(7f), 0, Ui.dpi(7f), 0) },
                LayoutParams(-2, Ui.dpi(20f), Gravity.START or Gravity.TOP).apply { leftMargin = Ui.dpi(6f); topMargin = Ui.dpi(6f) })
            val x = FrameLayout(ctx).apply {
                background = Tf.circle(Color.argb(184, 30, 20, 51)); contentDescription = "Remove photo"
                addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.X, Color.WHITE, 14f, 2.6f)) }, LayoutParams(-2, -2, Gravity.CENTER))
                setOnClickListener { images.removeAt(i); render() }
                Ui.pressable(this, .97f)
            }
            c.addView(x, LayoutParams(Ui.dpi(26f), Ui.dpi(26f), Gravity.END or Gravity.TOP).apply { rightMargin = Ui.dpi(6f); topMargin = Ui.dpi(6f) })
            cells.add(c)
        }
        if (images.size < MAX_IMAGES) {
            if (uploadIndex == images.size) cells.add(Shimmer(ctx, tint).apply { contentDescription = "Uploading photo"; background = Tf.box(Color.TRANSPARENT, 14f, 1f, line); clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND })
            else {
                val add = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = Tf.dashedBox(ground, 14f, 1.5f, dash); contentDescription = "Add photo"
                    setOnClickListener { pick() }; Ui.pressable(this, .97f)
                }
                add.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.Plus, head, 20f, 2.4f)) })
                add.addView(Tf.t(ctx, "Add photo", 11.5f, sub), LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dpi(3f) })
                cells.add(add)
            }
        }
        // 3 square cells a row, gap 10
        var row: LinearLayout? = null
        cells.forEachIndexed { i, v ->
            if (i % 3 == 0) { row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }; grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = Ui.dpi(10f) }) }
            row!!.addView(SquareBox(ctx, v), LinearLayout.LayoutParams(0, -2, 1f).apply { if (i % 3 > 0) leftMargin = Ui.dpi(10f) })
        }
        row?.let { r -> while (r.childCount < 3) r.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = Ui.dpi(10f) }) }
    }

    private fun pick() {
        if (uploadIndex >= 0) return
        val p = SdSheets.pickImage ?: return
        p { bytes, type ->
            if (bytes == null) return@p
            error = ""
            if (images.size >= MAX_IMAGES) { error = "You can add up to $MAX_IMAGES photos."; render(); return@p }
            uploadIndex = images.size; render()
            if (bytes.size > 15 * 1024 * 1024 || !type.startsWith("image/")) { uploadFailed(); return@p }
            Api.async({
                // the photo (1440 px / 0.82) and its 360 px list copy, both private
                val pic = PUp.decode(bytes, 1440)
                val full = if (pic != null) PUp.jpeg(pic, 1440, 0.82f) else null
                val small = if (pic != null) PUp.jpeg(pic, PUp.THUMB_PX, 0.74f) else null
                pic?.bmp?.recycle()
                if (full == null) null else {
                    var thumb = ""
                    val t = if (small != null) Thread { val r = PUp.send(true, small, "photo-thumb.jpg", "image/jpeg"); if (r.ok) thumb = PR.s(r.data, "file_uri") }.apply { start() } else null
                    val r = PUp.send(true, full, "photo.jpg", "image/jpeg")
                    try { t?.join(60000) } catch (e: InterruptedException) { }
                    val uri = if (r.ok) PR.s(r.data, "file_uri") else ""
                    if (uri.isEmpty()) null else uri to thumb
                }
            }) { res ->
                if (res == null) { uploadFailed(); return@async }
                if (res.second.isNotEmpty()) thumbs[res.first] = res.second
                images.add(res.first)
                uploadIndex = -1
                render()
            }
        }
    }
    private fun uploadFailed() {
        uploadIndex = -1
        error = "Upload failed. Please upload a clear photo under 5MB."
        render()
        pages.toast.show("Upload failed. Please upload a clear photo under 5MB.", bad = true, top = true, style = "profile")
    }

    private fun pickDate() {
        val c = Calendar.getInstance()
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(birthDate)
        if (m != null) c.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt())
        try {
            DatePickerDialog(context, { _, y, mo, d -> birthDate = String.format(java.util.Locale.US, "%04d-%02d-%02d", y, mo + 1, d); render() },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        } catch (e: Exception) { }
    }

    private fun pickPreferred() {
        val ctx = context
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for ((v, l) in listOf("" to "Select", "female" to "Women", "male" to "Men", "both" to "Everyone")) {
            val on = v == preferred
            col.addView(Tf.t(ctx, l, 15f, if (on) head else ink, on).apply {
                gravity = Gravity.CENTER_VERTICAL; minHeight = Ui.dpi(46f); setPadding(Ui.dpi(6f), 0, Ui.dpi(6f), 0)
                if (on) { compoundDrawablePadding = Ui.dpi(8f); setCompoundDrawablesWithIntrinsicBounds(null, null, PUi.lu(Lu.Check, head, 16f, 2.6f), null) }
                setOnClickListener { preferred = v; render(); sheetClose?.invoke() }
            }, LinearLayout.LayoutParams(-1, -2))
            col.addView(View(ctx).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(-1, Ui.dpi(1f)))
        }
        sheetClose = PUi.sheet(pages.layer, "Interested in", null, col) { sheetClose = null }
    }

    private fun save() {
        error = ""
        val p = profile
        // no profile (it could not be loaded): sign-up lives on the Welcome screens
        if (p == null) { pages.go("/welcome"); return }
        error = when {
            fullName.trim().isEmpty() -> "Please enter your full name."
            birthDate.isEmpty() -> "Please add your date of birth."
            images.size < 2 -> "Please upload at least 2 profile pictures."
            else -> ""
        }
        if (error.isNotEmpty()) { render(); return }
        saving = true; render()
        val arr = JSONArray(); images.forEach { arr.put(it) }
        val payload = JSONObject().put("full_name", fullName.trim()).put("bio", bio.trim()).put("date_of_birth", birthDate).put("preferred_gender", preferred)
            .put("images", arr).put("profile_picture", images[0]).put("profile_complete", true)
        // small list copies of the photos added here
        val th = JSONArray()
        images.forEach { u -> val k = PR.srcOf(u); thumbs[k]?.let { t -> th.put(JSONObject().put("uri", k).put("thumb", t)) } }
        if (th.length() > 0) payload.put("photo_thumbs", th)
        Api.invoke("updateMyProfile", JSONObject().put("updates", payload)) { r ->
            saving = false
            if (r.ok) { render(); pages.go("/home"); return@invoke }
            error = if (r.data.opt("error") == "privacy_blocked") "For your safety, sharing external social media handles, links or contact IDs is strictly restricted in your profile bio."
                else PR.s(r.data, "error").ifEmpty { if (r.status > 0) "Request failed with status code ${r.status}" else "Network Error" }
            render()
        }
    }

    fun previewOpen(what: String) { when (what) { "error" -> { error = "Please upload at least 2 profile pictures."; render() }; "uploading" -> { uploadIndex = images.size; render() }; "preferred" -> pickPreferred() } }

    /** a square cell (aspect-ratio 1 / 1) */
    private class SquareBox(ctx: Context, child: View) : FrameLayout(ctx) {
        init { addView(child, LayoutParams(-1, -1)) }
        override fun onMeasure(w: Int, h: Int) { val s = MeasureSpec.getSize(w); super.onMeasure(MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY)) }
    }

    /** the uploading skeleton: a soft light sliding across (tfb-sk, .65 s) */
    private class Shimmer(ctx: Context, private val base: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var t = 0f
        private val a = ValueAnimator.ofFloat(0f, 1f).apply { duration = 650; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); addUpdateListener { t = it.animatedValue as Float; invalidate() } }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); a.start() }
        override fun onDetachedFromWindow() { a.cancel(); super.onDetachedFromWindow() }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.shader = null; p.color = base
            c.drawRect(0f, 0f, w, h, p)
            val x = -w + 2 * w * t
            p.shader = LinearGradient(x, 0f, x + w, 0f, intArrayOf(base, Ui.c("#FBF8FF"), base), floatArrayOf(.25f, .5f, .75f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
        }
    }
}
