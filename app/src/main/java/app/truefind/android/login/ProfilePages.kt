package app.truefind.android.login

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.truefind.android.Api
import app.truefind.android.PUp
import app.truefind.android.TfLoader
import app.truefind.android.Ui
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** The old sign-up pages (NeonAuthShell): /register/basic-profile and /register/complete-profile (signed in). */
internal object ProfKit {
    /** FIELD: h-12 rounded-2xl bg-white/10 border-white/15 text-white placeholder:text-white/40 */
    fun field(ctx: Context, hint: String, icon: String? = null, hDp: Float = 48f, radius: Float = 16f): AField =
        AField(ctx, hint, icon, hDp, radius, AK.w(0.10f), AK.w(0.15f), Color.WHITE, AK.w(0.4f), AK.w(0.5f))

    /** the error box (bg-destructive/20 text-red-200) */
    fun errorBox(ctx: Context): TextView = AK.small(ctx, "", LK.c("#FECACA")).apply {
        background = CssBox(LK.a(AK.destructive, 0.2f), 12f); setPadding(Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f), Ui.dpi(12f)); visibility = View.GONE
    }

    /** two choice buttons (h-12 rounded-2xl border-2): picked → primary border, primary/20 and primary text */
    fun choice(ctx: Context, label: String, size: Float = 16f): TextView = LK.t(ctx, label, size, AK.w(0.6f)).apply { gravity = Gravity.CENTER; isClickable = true; LK.press(this, 0.97f) }
    fun pick(v: TextView, on: Boolean) {
        v.background = if (on) CssBox(AK.p(0.2f), 16f, 2f, AK.primary) else CssBox(AK.w(0.05f), 16f, 2f, AK.w(0.15f))
        v.setTextColor(if (on) AK.primary else AK.w(0.6f))
    }

    /** the big button (h-14 rounded-2xl text-base font-semibold, glowing) */
    fun bigBtn(ctx: Context, label: String): ABtn = ABtn(ctx, label, "primary", 56f, 16f, 16f, true, glow = Sh(0f, 0f, 24f, 0f, AK.p(0.45f))).apply {
        busyLabel = "Saving…"; spinnerSize(20f, 16f)
    }

    /** a label with the text-sm leading-none look, white (the shell's text colour) */
    fun label(ctx: Context, s: CharSequence) = LK.t(ctx, s, 14f, Color.WHITE, false, 1f)

    /** uploadProfilePhotoWithThumb: shrink (1440 px / .82) + the 360 px copy, both to private storage → (uri, thumb) */
    fun privateWithThumb(bytes: ByteArray, mime: String, done: (String, String) -> Unit) {
        if (bytes.size > 15 * 1024 * 1024 || !mime.startsWith("image/")) { done("", ""); return }
        Api.async({
            val pic = PUp.decode(bytes, 1440)
            val full = if (pic != null) PUp.jpeg(pic, 1440, 0.82f) else null
            val small = if (pic != null) PUp.jpeg(pic, PUp.THUMB_PX, 0.74f) else null
            pic?.bmp?.recycle()
            if (full == null) "" to "" else {
                var thumb = ""
                val t = if (small != null) Thread { thumb = try { val r = PUp.send(true, small, "thumb.jpg", "image/jpeg"); if (r.ok) r.data.optString("file_uri") else "" } catch (e: Exception) { "" } }.apply { start() } else null
                val r = PUp.send(true, full, "photo.jpg", "image/jpeg")
                try { t?.join(60000) } catch (e: InterruptedException) { }
                val uri = if (r.ok) r.data.optString("file_uri") else ""
                uri to (if (uri.isEmpty()) "" else thumb)
            }
        }) { (u, t) -> done(u, t) }
    }

    /** the browser's date box: its look (dd/mm/yyyy in the phone's order) */
    private fun pattern(): String {
        val p = (DateFormat.getDateInstance(DateFormat.SHORT, Locale.getDefault()) as? SimpleDateFormat)?.toPattern() ?: "dd/MM/yyyy"
        return p.replace(Regex("y+"), "yyyy").replace(Regex("M+"), "MM").replace(Regex("d+"), "dd")
    }
    fun dateHint(): String = pattern().lowercase()
    fun dateText(iso: String): String = try {
        val c = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!
        SimpleDateFormat(pattern(), Locale.getDefault()).format(c)
    } catch (e: Exception) { "" }
}

/** a page in NeonAuthShell: the neon backdrop and a top column (px-6 py-8, max 448) */
internal open class ShellPage(ctx: Context, layer: LoginLayer) : DarkPage(ctx, layer) {
    protected fun shell(vararg views: View) {
        val col = LK.col(context)
        for (v in views) col.addView(v, (v.layoutParams as? LinearLayout.LayoutParams) ?: LK.lp(-1, -2))
        put(col, 24f, 32f, center = false)
    }
    /** the title block (mt-8 mb-6): optional glowing tile with an icon, title, line */
    protected fun header(icon: String?, title: String, sub: String): View = LK.col(context, Gravity.CENTER_HORIZONTAL).apply {
        if (icon != null) {
            val tile = FrameLayout(context).apply { background = CssBox(AK.primary, 16f, shadows = listOf(Sh(0f, 0f, 28f, 0f, AK.p(0.7f)))) }
            tile.addView(ImageView(context).apply { setImageDrawable(LK.lu(icon, Color.WHITE, 32f)) }, FrameLayout.LayoutParams(Ui.dpi(32f), Ui.dpi(32f), Gravity.CENTER))
            addView(tile, LK.lp(Ui.dpi(64f), Ui.dpi(64f), b = 16f))
        }
        addView(LK.t(context, title, 24f, Color.WHITE, true, 32f / 24f).apply { gravity = Gravity.CENTER }, LK.lp(-1, -2))
        addView(LK.t(context, sub, 14f, AK.w(0.7f), false, 20f / 14f).apply { gravity = Gravity.CENTER }, LK.lp(-1, -2, t = 8f))
        layoutParams = LK.lp(-1, -2, t = 32f, b = 24f)
    }
    /** framer-motion's opacity 0 → 1, y 10 → 0 */
    protected fun rise(v: View): View { v.alpha = 0f; v.translationY = Ui.dp(10f); v.animate().alpha(1f).translationY(0f).setDuration(450).setInterpolator(PathInterpolator(0.25f, 0.1f, 0.25f, 1f)).start(); return v }
}

// ═══════════════════════════════════ /register/basic-profile ═══════════════════════════════════
/** BasicProfile.jsx: name, date of birth, gender (+ account type for women), state, city → createMyProfile → verification */
internal class BasicProfilePage(ctx: Context, layer: LoginLayer) : ShellPage(ctx, layer) {
    private var dob = ""      // yyyy-mm-dd
    private var gender = ""
    private var accountType = "user"
    private var state = ""; private var city = ""
    private var loading = false
    private var built = false

    override fun route(search: String) {
        if (built) return
        built = true
        val ctx = context
        val err = ProfKit.errorBox(ctx).apply { layoutParams = LK.lp(-1, -2, b = 16f) }
        val name = ProfKit.field(ctx, "Your full name")
        name.edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        val dobLabel = ProfKit.label(ctx, "Date of birth")
        val dobBox = ProfKit.field(ctx, ProfKit.dateHint(), LK.I.Calendar)
        dobBox.edit.isFocusable = false; dobBox.edit.isCursorVisible = false; dobBox.edit.keyListener = null
        val male = ProfKit.choice(ctx, "Male"); val female = ProfKit.choice(ctx, "Female")
        val genders = LK.row(ctx).apply { addView(male, LK.lp(0, Ui.dpi(48f), weight = 1f, r = 6f)); addView(female, LK.lp(0, Ui.dpi(48f), weight = 1f, l = 6f)) }
        val user = ProfKit.choice(ctx, "User", 14f); val creator = ProfKit.choice(ctx, "Become a Creator", 14f)
        val types = LK.row(ctx).apply { addView(user, LK.lp(0, Ui.dpi(48f), weight = 1f, r = 6f)); addView(creator, LK.lp(0, Ui.dpi(48f), weight = 1f, l = 6f)) }
        val typeBox = stack(ctx, 8f, ProfKit.label(ctx, "Account type"), types, AK.small(ctx, "Creators can earn from interactions after full verification.", AK.w(0.5f), center = false)).apply { visibility = View.GONE }
        val stateSel = ASelect(ctx, layer, "Select your state", "Select your state").apply { options = Places.LIB_NAMES.map { it to it } }
        val citySel = ASelect(ctx, layer, "Select your city", "Select state first", leading = LK.I.MapPin)
        val btn = ProfKit.bigBtn(ctx, "Continue")
        fun age(): Int? {
            if (dob.isEmpty()) return null
            val p = dob.split("-").map { it.toIntOrNull() ?: return null }
            val now = Calendar.getInstance()
            var a = now.get(Calendar.YEAR) - p[0]
            val m = now.get(Calendar.MONTH) + 1 - p[1]
            if (m < 0 || (m == 0 && now.get(Calendar.DAY_OF_MONTH) < p[2])) a--
            return a
        }
        fun draw() {
            val a = age()
            dobLabel.text = if (a != null) android.text.SpannableStringBuilder("Date of birth ").append(" · $a years", android.text.style.ForegroundColorSpan(AK.w(0.5f)), 0) else "Date of birth"
            dobBox.put(if (dob.isEmpty()) "" else ProfKit.dateText(dob))
            ProfKit.pick(male, gender == "male"); ProfKit.pick(female, gender == "female")
            ProfKit.pick(user, accountType == "user"); ProfKit.pick(creator, accountType == "creator")
            if ((gender == "female") != (typeBox.visibility == View.VISIBLE)) {
                typeBox.visibility = if (gender == "female") View.VISIBLE else View.GONE
                if (gender == "female") { typeBox.alpha = 0f; typeBox.animate().alpha(1f).setDuration(300).start() }
            }
            stateSel.set(state)
            citySel.options = (Places.LIB[state] ?: emptyList()).map { it to it }
            citySel.setPlaceholder(if (state.isNotEmpty()) "Select your city" else "Select state first")
            citySel.set(city); citySel.isEnabled = state.isNotEmpty()
            btn.busy = loading
        }
        dobBox.setOnClickListener { pickDate { dob = it; draw() } }
        dobBox.edit.setOnClickListener { pickDate { dob = it; draw() } }
        LK.click(male) { gender = "male"; draw() }; LK.click(female) { gender = "female"; draw() }
        LK.click(user) { accountType = "user"; draw() }; LK.click(creator) { accountType = "creator"; draw() }
        stateSel.onPick = { state = it; city = ""; draw() }
        citySel.onPick = { city = it; draw() }
        btn.setOnClickListener {
            AK.setText(err, "")
            val a = age()
            val msg = when {
                name.value.trim().isEmpty() -> "Please enter your full name."
                dob.isEmpty() -> "Please enter your date of birth."
                a == null || a < 18 -> "You must be at least 18 years old to use TRUE FIND."
                gender.isEmpty() -> "Please select your gender."
                state.isEmpty() -> "Please select your state."
                city.isEmpty() -> "Please select your city."
                else -> ""
            }
            if (msg.isNotEmpty()) { AK.setText(err, msg); scroll.smoothScrollTo(0, 0); return@setOnClickListener }
            loading = true; draw()
            val profile = JSONObject().put("full_name", name.value.trim()).put("date_of_birth", dob).put("age", a).put("gender", gender).put("state", state).put("city", city)
                .put("account_type", if (gender == "female") accountType else "user")
            val fail = { r: LoginApi.R -> loading = false; draw(); AK.setText(err, LoginApi.fnMsg(r).ifEmpty { "Could not save your details. Please try again." }); scroll.smoothScrollTo(0, 0) }
            LoginApi.fn("createMyProfile", JSONObject().put("profile", profile)) { r ->
                if (!r.ok) { fail(r); return@fn }
                val updates = if (r.data.optBoolean("existing"))
                    // the profile exists: only the fields the server allows (gender and account type are locked)
                    JSONObject().put("full_name", name.value.trim()).put("date_of_birth", dob).put("age", a).put("state", state).put("city", city).put("profile_complete", true)
                else JSONObject().put("profile_complete", true) // Home, Explore and Random Match only list complete profiles
                LoginApi.fn("updateMyProfile", JSONObject().put("updates", updates)) { u ->
                    if (!u.ok) { fail(u); return@fn }
                    if (layer.preview.isNotEmpty()) { layer.toast("→ /register/verification"); loading = false; draw(); return@fn }
                    layer.handOff("home") { }
                    layer.host.js("location.href='/register/verification'")
                }
            }
        }
        val form = stack(ctx, 16f,
            stack(ctx, 8f, ProfKit.label(ctx, "Full name"), name),
            stack(ctx, 8f, dobLabel, dobBox),
            stack(ctx, 8f, ProfKit.label(ctx, "Gender"), genders),
            typeBox,
            stack(ctx, 8f, ProfKit.label(ctx, "State"), stateSel),
            stack(ctx, 8f, ProfKit.label(ctx, "City"), citySel),
            btn.apply { layoutParams = LK.lp(-1, -2, t = 8f) })
        draw()
        shell(stepDots(ctx, 2, 4), header(LK.I.User, "Tell us about you", "These details help us find your matches."), err, rise(form))
        postDelayed({ LK.autoFocus(name.edit) }, 200)
    }

    /** the date picker (max: today) */
    private fun pickDate(done: (String) -> Unit) {
        LK.hideKeyboard(this)
        val c = Calendar.getInstance()
        if (dob.isNotEmpty()) dob.split("-").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 }?.let { c.set(it[0], it[1] - 1, it[2]) } else c.add(Calendar.YEAR, -18)
        val d = DatePickerDialog(context, { _, y, m, day -> done("%04d-%02d-%02d".format(y, m + 1, day)) }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        d.datePicker.maxDate = System.currentTimeMillis()
        d.show()
    }
}

// ═══════════════════════════════════ /register/complete-profile ═══════════════════════════════════
/** CompleteProfile.jsx: photo (to the review queue), bio, hobbies, job, height, weight and the preferences → Home */
internal class CompleteProfilePage(ctx: Context, layer: LoginLayer) : ShellPage(ctx, layer) {
    private var profile: JSONObject? = null
    private var built = false
    private var uploading = false
    private var saving = false
    private var preview: Bitmap? = null
    private val f = HashMap<String, String>()

    override fun route(search: String) {
        if (built) return
        built = true
        // loading
        put(FrameLayout(context).apply { minimumHeight = resources.displayMetrics.heightPixels / 2; addView(darkSpinner(context, 32f), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) }, 24f, 32f)
        LoginApi.me { u, _ ->
            if (u == null) { layer.go("/welcome", true); return@me }
            // only my own profile (an admin can read everyone's)
            LoginApi.profiles(JSONObject().put("created_by_id", u.optString("id")), 1) { r ->
                val p = if (r.ok) r.list.firstOrNull() else null
                if (p == null) { layer.go("/welcome", true); return@profiles }
                profile = p
                f["bio"] = p.optString("bio"); f["occupation"] = p.optString("occupation"); f["height"] = p.optString("height"); f["weight"] = p.optString("weight")
                f["hobbies"] = p.optJSONArray("hobbies")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } } ?: ""
                f["min"] = p.optInt("preferred_age_min", 0).takeIf { it != 0 }?.toString() ?: "18"
                f["max"] = p.optInt("preferred_age_max", 0).takeIf { it != 0 }?.toString() ?: "35"
                f["gender"] = p.optString("preferred_gender").ifEmpty { "both" }
                f["pstate"] = p.optString("preferred_state"); f["pcity"] = p.optString("preferred_city")
                f["rel"] = p.optString("relationship_preference").ifEmpty { "both" }
                build()
            }
        }
    }

    private fun hasPhoto(): Boolean {
        val p = profile ?: return false
        if (p.optString("profile_picture").isNotEmpty()) return true
        val q = p.optJSONArray("photo_queue") ?: return false
        return (0 until q.length()).any { q.optJSONObject(it)?.optString("status") != "rejected" }
    }

    private lateinit var err: TextView
    private lateinit var photo: FrameLayout
    private lateinit var btn: ABtn

    private fun build() {
        val ctx = context
        err = ProfKit.errorBox(ctx).apply { layoutParams = LK.lp(-1, -2, b = 16f) }
        // the round photo spot
        photo = FrameLayout(ctx).apply {
            background = CssBox(AK.w(0.10f), 56f, 2f, AK.p(0.6f), shadows = listOf(Sh(0f, 0f, 24f, 0f, AK.p(0.4f))))
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) } }
            isClickable = true
            setOnClickListener { pick() }
        }
        drawPhoto()
        val photoCol = LK.col(ctx, Gravity.CENTER_HORIZONTAL).apply {
            addView(photo, LK.lp(Ui.dpi(112f), Ui.dpi(112f)))
            addView(LK.t(ctx, "Tap to add a real photo of you", 14f, AK.w(0.6f), false, 20f / 14f), LK.lp(t = 8f))
            addView(LK.t(ctx, "Our team checks every photo. AI-made or fake photos are rejected.", 12f, AK.w(0.45f), false, 16f / 12f).apply { gravity = Gravity.CENTER; setPadding(Ui.dpi(24f), 0, Ui.dpi(24f), 0) }, LK.lp(-1, -2, t = 4f))
            layoutParams = LK.lp(-1, -2, b = 24f)
        }
        // the fields
        fun input(key: String, hint: String, h: Float = 48f, r: Float = 16f, number: Boolean = false): AField = ProfKit.field(ctx, hint, null, h, r).apply {
            if (number) number(); put(f[key] ?: ""); onText = { f[key] = it }
        }
        val bio = ProfKit.field(ctx, "A short intro about you…", null, 80f, 16f).apply {
            edit.isSingleLine = false; edit.maxLines = 20; edit.minLines = 3; edit.gravity = Gravity.TOP or Gravity.START
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            edit.setPadding(Ui.dpi(12f), Ui.dpi(8f), Ui.dpi(12f), Ui.dpi(8f))
            put(f["bio"] ?: ""); onText = { f["bio"] = it }
        }
        val hobbies = input("hobbies", "Travel, music, cooking…")
        val occupation = input("occupation", "Your job")
        val height = input("height", "5'6\"")
        val weight = input("weight", "60 kg")
        val ageLabel = ProfKit.label(ctx, "")
        val min = input("min", "", 44f, 12f, true); val max = input("max", "", 44f, 12f, true)
        fun ageText() { ageLabel.text = "Preferred age range: ${f["min"]} – ${f["max"]}" }
        min.onText = { f["min"] = it; ageText() }; max.onText = { f["max"] = it; ageText() }
        ageText()
        val pg = ASelect(ctx, layer, "Preferred gender", "Select an option", 44f, 12f).apply { options = listOf("female" to "Female", "male" to "Male", "both" to "Both"); set(f["gender"] ?: "both"); onPick = { f["gender"] = it } }
        val rel = ASelect(ctx, layer, "Looking for", "Select an option", 44f, 12f).apply { options = listOf("dating" to "Dating", "friendship" to "Friendship", "both" to "Both"); set(f["rel"] ?: "both"); onPick = { f["rel"] = it } }
        val ps = ASelect(ctx, layer, "Preferred state", "Any", 44f, 12f).apply { options = Places.LIB_NAMES.map { it to it }; set(f["pstate"] ?: "") }
        val pc = ASelect(ctx, layer, "Preferred city", "Select state", 44f, 12f)
        fun cities() {
            val st = f["pstate"] ?: ""
            pc.options = (Places.LIB[st] ?: emptyList()).map { it to it }
            pc.setPlaceholder(if (st.isNotEmpty()) "Any" else "Select state"); pc.set(f["pcity"] ?: ""); pc.isEnabled = st.isNotEmpty()
        }
        ps.onPick = { f["pstate"] = it; f["pcity"] = ""; cities() }
        pc.onPick = { f["pcity"] = it }
        cities()
        fun two(a: View, b: View) = LK.row(ctx, Gravity.TOP).apply { addView(a, LK.lp(0, -2, weight = 1f, r = 6f)); addView(b, LK.lp(0, -2, weight = 1f, l = 6f)) }
        val prefs = LK.col(ctx).apply {
            background = CssBox(LK.a(LK.bg, 0.7f), 16f, 1f, AK.p(0.4f)); setPadding(Ui.dpi(17f), Ui.dpi(17f), Ui.dpi(17f), Ui.dpi(17f))
            addView(LK.t(ctx, "Your preferences", 14f, Color.WHITE, true, 20f / 14f), LK.lp(b = 12f))
            addView(stack(ctx, 16f,
                stack(ctx, 8f, ageLabel, two(min, max)),
                two(stack(ctx, 8f, ProfKit.label(ctx, "Preferred gender"), pg), stack(ctx, 8f, ProfKit.label(ctx, "Looking for"), rel)),
                two(stack(ctx, 8f, ProfKit.label(ctx, "Preferred state"), ps), stack(ctx, 8f, ProfKit.label(ctx, "Preferred city"), pc))), LK.lp(-1, -2))
        }
        btn = ProfKit.bigBtn(ctx, "Finish & enter TRUE FIND")
        btn.setOnClickListener { save() }
        val form = stack(ctx, 16f,
            stack(ctx, 8f, ProfKit.label(ctx, "Bio"), bio),
            stack(ctx, 8f, ProfKit.label(ctx, "Hobbies & interests"), hobbies, AK.small(ctx, "Separate with commas.", AK.w(0.5f), center = false)),
            two(stack(ctx, 8f, ProfKit.label(ctx, "Occupation"), occupation), stack(ctx, 8f, ProfKit.label(ctx, "Height"), height)),
            stack(ctx, 8f, ProfKit.label(ctx, "Weight"), weight),
            prefs, btn)
        shell(stepDots(ctx, 4, 4), header(null, "Complete your profile", "The more you add, the better your matches."), err, photoCol, rise(form))
    }

    private fun drawPhoto() {
        val ctx = context
        photo.removeAllViews()
        when {
            uploading -> photo.addView(TfLoader(ctx, 28f, Color.WHITE), FrameLayout.LayoutParams(Ui.dpi(28f), Ui.dpi(28f), Gravity.CENTER))
            preview != null -> photo.addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(preview) }, FrameLayout.LayoutParams(-1, -1))
            hasPhoto() -> photo.addView(ImageView(ctx).apply { setImageDrawable(LK.lu("""<circle cx="12" cy="12" r="10"/><path d="m9 12 2 2 4-4"/>""", LK.c("#34D399"), 32f)) }, FrameLayout.LayoutParams(Ui.dpi(32f), Ui.dpi(32f), Gravity.CENTER))
            else -> photo.addView(ImageView(ctx).apply { setImageDrawable(LK.lu(LK.I.Camera, AK.primary, 32f)) }, FrameLayout.LayoutParams(Ui.dpi(32f), Ui.dpi(32f), Gravity.CENTER))
        }
        photo.clipToOutline = preview != null && !uploading
    }

    private fun pick() {
        if (uploading) return
        if (layer.preview.isNotEmpty()) { layer.toast("→ photo picker"); return }
        layer.host.pickPhoto { bytes, mime ->
            if (bytes == null) return@pickPhoto
            uploading = true; AK.setText(err, "")
            preview = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 }) } catch (e: Throwable) { null }
            drawPhoto()
            val failed = {
                uploading = false; preview = null; drawPhoto() // the photo was not saved: don't keep showing it
                redToast(layer, "Upload failed. Please upload a clear photo under 5MB.")
                AK.setText(err, "Upload failed. Please upload a clear photo under 5MB.")
            }
            ProfKit.privateWithThumb(bytes, mime.ifEmpty { "image/jpeg" }) { uri, thumb ->
                if (uri.isEmpty()) { failed(); return@privateWithThumb }
                // the server adds it to the admin review queue (real photo check)
                val add = JSONObject().put("uri", uri).put("main", true).apply { if (thumb.isNotEmpty()) put("thumb", thumb) }
                LoginApi.fn("updateMyProfile", JSONObject().put("photo_queue_add", add)) { r ->
                    val q = r.data.optJSONObject("profile")?.optJSONArray("photo_queue")
                    if (!r.ok || q == null) { failed(); return@fn }
                    profile?.put("photo_queue", q)
                    uploading = false; drawPhoto()
                }
            }
        }
    }

    private fun save() {
        AK.setText(err, "")
        if (!hasPhoto()) { AK.setText(err, "Please add a profile picture to continue."); scroll.smoothScrollTo(0, 0); return }
        // same rule as the server (18–100, min not above max)
        val mn = (f["min"] ?: "").trim().toIntOrNull(); val mx = (f["max"] ?: "").trim().toIntOrNull()
        if (mn == null || mx == null || mn < 18 || mx > 100 || mn > mx) { AK.setText(err, "Please enter a preferred age range between 18 and 100."); scroll.smoothScrollTo(0, 0); return }
        saving = true; btn.busy = true
        val hobbies = JSONArray().apply { (f["hobbies"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(it) } }
        val updates = JSONObject().put("bio", (f["bio"] ?: "").trim()).put("hobbies", hobbies).put("occupation", (f["occupation"] ?: "").trim())
            .put("height", (f["height"] ?: "").trim()).put("weight", (f["weight"] ?: "").trim()).put("preferred_age_min", mn).put("preferred_age_max", mx)
            .put("preferred_gender", f["gender"] ?: "both").put("preferred_state", f["pstate"] ?: "").put("preferred_city", f["pcity"] ?: "")
            .put("relationship_preference", f["rel"] ?: "both").put("profile_complete", true).put("registration_step", "done")
        LoginApi.fn("updateMyProfile", JSONObject().put("updates", updates)) { r ->
            saving = false; btn.busy = false
            if (r.ok) { layer.go("/home"); return@fn }
            if (r.data.optString("error") == "privacy_blocked") {
                val m = "For your safety, sharing external social media handles, links or contact IDs is strictly restricted in your profile bio."
                redToast(layer, m); AK.setText(err, m)
            } else AK.setText(err, LoginApi.fnMsg(r).ifEmpty { "Could not save your profile." })
            scroll.smoothScrollTo(0, 0)
        }
    }
}
