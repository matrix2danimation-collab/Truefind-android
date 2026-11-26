package app.truefind.android

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** My own Reconnect profile (the photo at the top-left): view + edit — MyProfile in SecretApp.jsx. */
object SdMyProfile {
    private val NAME_OK = Regex("^[\\p{L}\\p{M}][\\p{L}\\p{M} .'-]*$")

    fun open(h: SecretHome, startEdit: Boolean) {
        val ctx = h.context
        val root = FrameLayout(ctx)
        root.addView(PinkBackdrop(ctx), FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        var editing = startEdit
        var saving = false
        var savedNote = ""
        var close: () -> Unit = {}
        lateinit var draw: () -> Unit

        // the edit form's values
        var fName = ""; var fBio = ""; var fState = ""; var fCity = ""; var fDob = ""
        var err = ""
        // the form starts with what the profile has (also when the page opens straight into editing)
        fun fill() {
            val p = h.myProfileNow
            fName = SdRules.s(p, "full_name", "name").trim(); fBio = SdRules.s(p, "bio", "about"); fState = SdRules.s(p, "state").trim(); fCity = SdRules.s(p, "city").trim(); fDob = ""
        }
        fill()
        fun startEditing() {
            fill()
            err = ""; savedNote = ""; editing = true; draw()
        }

        fun save() {
            val p = h.myProfileNow
            val age = SdRules.ageOf(p)
            val name = SdRules.s(p, "full_name", "name").trim()
            val bio = SdRules.s(p, "bio", "about")
            val nm = fName.trim().replace(Regex("\\s+"), " ")
            if (nm.length < 2) { err = "Please enter your name."; draw(); return }
            if (nm.length > 40 || !NAME_OK.matches(nm)) { err = "Please use letters only in your name (max 40)."; draw(); return }
            var dobIso = ""
            if (age == null) {
                dobIso = dobToIso(fDob)
                if (dobIso.isEmpty()) { err = "Please enter your date of birth as DD/MM/YYYY."; draw(); return }
                if ((SdRules.ageOf(JSONObject().put("date_of_birth", dobIso)) ?: 0) < 18) { err = "You must be 18 or older."; draw(); return }
            }
            if (fState.isEmpty()) { err = "Please select your state."; draw(); return }
            if (fCity.isEmpty()) { err = "Please select your city."; draw(); return }
            val updates = JSONObject()
            if (nm != name) updates.put("full_name", nm)
            if (fState != SdRules.s(p, "state").trim()) updates.put("state", fState)
            if (fCity != SdRules.s(p, "city").trim()) updates.put("city", fCity)
            val b = fBio.trim()
            if (b != bio.trim()) updates.put("bio", b)
            if (dobIso.isNotEmpty()) updates.put("date_of_birth", dobIso)
            if (updates.length() == 0) { editing = false; draw(); return }
            if (SdRules.findContact(b).isNotEmpty()) { err = "Please don't add numbers, links or social IDs in About Me."; draw(); return }
            saving = true; err = ""; draw()
            Api.invoke("updateMyProfile", JSONObject().put("updates", updates)) { r ->
                saving = false
                val d = r.data
                if (!r.ok || d.opt("ok") == false || (d.optString("error").isNotEmpty() && d.optJSONObject("profile") == null)) {
                    err = d.optString("message").ifEmpty { d.optString("error").ifEmpty { r.error ?: "Could not save your profile." } }; draw(); return@invoke
                }
                val next = d.optJSONObject("profile") ?: JSONObject(p.toString()).also { m -> updates.keys().forEach { k -> m.put(k, updates.get(k)) } }
                h.profileSaved(next)
                editing = false; savedNote = "Profile saved ✓"; draw()
            }
        }

        draw = {
            val p = h.myProfileNow
            val age = SdRules.ageOf(p)
            val name = SdRules.s(p, "full_name", "name").trim()
            val bio = SdRules.s(p, "bio", "about")
            val state0 = SdRules.s(p, "state").trim(); val city0 = SdRules.s(p, "city").trim()
            val missing = SdRules.missingOf(p)
            val female = h.gender == "f"
            val cStatus = SdRules.creatorStatusOf(p)
            val photo = SdRules.photoOf(p)
            val (premium, premiumKnown, premiumUntil) = h.premiumInfo
            val until = SdRules.whenText(premiumUntil)
            val credits = h.creditsShown
            val white = Color.WHITE
            // the page is blush now (23 Nov): what stands straight on it is black / maroon, with no glow behind the words
            val ink = Rs.INK
            val shadowC = Color.TRANSPARENT

            val col = vbox(ctx)
            // top bar: back · title · edit
            val top = FrameLayout(ctx).apply { setPadding(dpi(6f), dpi(18f), dpi(8f), 0) }
            top.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.ArrowLeft, ink, 26f, 2.3f)); scaleType = ImageView.ScaleType.CENTER; contentDescription = "Back" }
                .press().onTap { if (editing) { editing = false; err = ""; draw() } else close() }, FrameLayout.LayoutParams(dpi(44f), dpi(44f), Gravity.START or Gravity.CENTER_VERTICAL))
            top.addView(sdText(ctx, if (editing) "Edit Profile" else "My Profile", 24f, Rs.M_INK, serif = true), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            if (!editing) {
                // Settings: True Find's own Settings (the same screens as on True Find's Profile — owner, 23 Nov night);
                // its back arrow comes back here
                top.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.Settings, ink, 23f, 2.1f)); scaleType = ImageView.ScaleType.CENTER; contentDescription = "Settings" }
                    .press().onTap { h.openSettings() }, FrameLayout.LayoutParams(dpi(44f), dpi(44f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dpi(44f) })
                top.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.Pencil, ink, 22f, 2.3f)); scaleType = ImageView.ScaleType.CENTER; contentDescription = "Edit profile" }
                    .press().onTap { startEditing() }, FrameLayout.LayoutParams(dpi(44f), dpi(44f), Gravity.END or Gravity.CENTER_VERTICAL))
            }
            col.addView(top, lp(MATCH, WRAP))

            // round photo + change photo
            val size = if (editing) 132f else 184f
            val pb = FrameLayout(ctx).apply { clipChildren = false }
            val ring = FrameLayout(ctx).apply {
                background = RingDrawable(intArrayOf(Rs.c("#FF7DBB"), Rs.c("#E3086C"), Rs.c("#8E1243"), Rs.c("#FF9CCB"), Rs.c("#FF7DBB")), floatArrayOf(0f, .35f, .65f, .85f, 1f))
                setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f)); elevation = Ui.dp(8f)
            }
            val gap = FrameLayout(ctx).apply { background = oval(white); setPadding(dpi(5f), dpi(5f), dpi(5f), dpi(5f)) }
            gap.addView(RoundPhoto(ctx, size).also { it.set(listOf(photo)) }, FrameLayout.LayoutParams(-1, -1))
            ring.addView(gap, FrameLayout.LayoutParams(-1, -1))
            pb.addView(ring, FrameLayout.LayoutParams(-1, -1))
            pb.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.ImagePlus, white, 21f, 2.2f)); scaleType = ImageView.ScaleType.CENTER; background = oval(K.pill, 3f, white); elevation = Ui.dp(10f); contentDescription = "Change photo" }
                .press().onTap { h.changePhoto() }, FrameLayout.LayoutParams(dpi(46f), dpi(46f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = dpi(2f); bottomMargin = dpi(6f) })
            // Premium: the crown shows here too, as on the small photo at the top of Reconnect (owner, 20 Nov)
            if (premium) pb.addView(ImageView(ctx).apply {
                setImageDrawable(Icon(Lucide.Crown, white, if (editing) 17f else 21f, 2.2f, white)); scaleType = ImageView.ScaleType.CENTER
                background = oval(K.hot, 3f, white); elevation = Ui.dp(10f); contentDescription = "Premium"
            }, FrameLayout.LayoutParams(dpi(if (editing) 36f else 44f), dpi(if (editing) 36f else 44f), Gravity.END or Gravity.TOP).apply { rightMargin = dpi(4f); topMargin = dpi(6f) })
            col.addView(pb, lp(dpi(size), dpi(size), t = 6f, gravity = Gravity.CENTER_HORIZONTAL))

            if (editing) {
                val f = vbox(ctx).apply { setPadding(dpi(22f), dpi(6f), dpi(22f), dpi(34f)) }
                f.addView(sdText(ctx, "Name, age, state and city are compulsory. Premium features open only when they are filled.", 14f, K.ink2, bold = false).apply {
                    gravity = Gravity.CENTER; lineHeight(1.15f); background = rounded(white, 14f, 1f, K.line2); setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f))
                }, lp(MATCH, WRAP, t = 14f))
                fun label(t: String) = sdText(ctx, t, 15f, Rs.c("#A1144A"))
                fun field(): GradientDrawable = rounded(white, 16f, 1f, K.line)
                f.addView(label("Name *"), lp(WRAP, WRAP, l = 2f, t = 16f, b = 7f))
                val nameIn = EditText(ctx).apply {
                    setText(fName); hint = "Your name"; setHintTextColor(K.soft); setTextColor(K.ink); typeface = Ui.regular; textSize = 16f; isSingleLine = true
                    filters = arrayOf(InputFilter.LengthFilter(40)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                    background = field(); setPadding(dpi(14f), 0, dpi(14f), 0)
                    addTextChangedListener(watch { fName = it })
                }
                f.addView(nameIn, lp(MATCH, dpi(52f)))
                f.addView(label("Age *"), lp(WRAP, WRAP, l = 2f, t = 16f, b = 7f))
                if (age != null) {
                    val r = hbox(ctx).apply { background = rounded(Rs.c("#FFF6FA"), 16f, 1f, K.line); setPadding(dpi(14f), 0, dpi(14f), 0) }
                    r.addView(sdText(ctx, "$age years", 16f, K.ink), lp(0, WRAP, 1f))
                    r.addView(sdText(ctx, "from your date of birth", 13f, K.sub, bold = false))
                    f.addView(r, lp(MATCH, dpi(52f)))
                } else {
                    val dob = EditText(ctx).apply {
                        setText(fDob); hint = "Date of birth (DD/MM/YYYY)"; setHintTextColor(K.soft); setTextColor(K.ink); typeface = Ui.regular; textSize = 16f; isSingleLine = true
                        inputType = InputType.TYPE_CLASS_NUMBER; background = field(); setPadding(dpi(14f), 0, dpi(14f), 0)
                    }
                    dob.addTextChangedListener(object : TextWatcher {
                        var busy = false
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            if (busy) return
                            val m = dobMask(s.toString()); fDob = m
                            if (m != s.toString()) { busy = true; dob.setText(m); dob.setSelection(m.length); busy = false }
                        }
                    })
                    f.addView(dob, lp(MATCH, dpi(52f)))
                }
                fun select(value: String, empty: String, enabled: Boolean, options: List<String>, onPick: (String) -> Unit): View {
                    val r = FrameLayout(ctx).apply { background = field(); alpha = if (enabled) 1f else 0.6f }
                    r.addView(sdText(ctx, value.ifEmpty { empty }, 16f, if (value.isEmpty()) K.soft else K.ink, bold = false).oneLine(), FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply { setMargins(dpi(14f), 0, dpi(40f), 0) })
                    r.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.ChevronRight, K.wine, 20f)); rotation = 90f }, FrameLayout.LayoutParams(dpi(20f), dpi(20f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dpi(14f) })
                    if (enabled) r.setOnClickListener {
                        val items = options.toTypedArray()
                        AlertDialog.Builder(ctx).setTitle(empty).setSingleChoiceItems(items, options.indexOf(value)) { d, i -> d.dismiss(); onPick(items[i]) }.setNegativeButton("Cancel", null).show()
                    }
                    return r
                }
                val states = SdRules.states.let { if (fState.isNotEmpty() && fState !in it) listOf(fState) + it else it }
                f.addView(label("State *"), lp(WRAP, WRAP, l = 2f, t = 16f, b = 7f))
                f.addView(select(fState, "Select state", true, states) { v -> if (v != fState) fCity = ""; fState = v; draw() }, lp(MATCH, dpi(52f)))
                val cities = if (fState.isEmpty()) emptyList() else SdRules.districts(fState).let { if (fCity.isNotEmpty() && fCity !in it) listOf(fCity) + it else it }
                f.addView(label("City *"), lp(WRAP, WRAP, l = 2f, t = 16f, b = 7f))
                f.addView(select(fCity, if (fState.isNotEmpty()) "Select city" else "Select state first", fState.isNotEmpty(), cities) { v -> fCity = v; draw() }, lp(MATCH, dpi(52f)))
                f.addView(label("About Me"), lp(WRAP, WRAP, l = 2f, t = 16f, b = 7f))
                val bioCount = sdText(ctx, "${fBio.length}/300", 12.5f, K.soft, bold = false).apply { gravity = Gravity.END }
                val bioIn = EditText(ctx).apply {
                    setText(fBio); hint = "A few lines about you and who you are looking for"; setHintTextColor(K.soft); setTextColor(K.ink); typeface = Ui.regular; textSize = 16f
                    filters = arrayOf(InputFilter.LengthFilter(300)); minLines = 4; gravity = Gravity.TOP; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    background = field(); setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f)); setLineSpacing(0f, 1.2f)
                    addTextChangedListener(watch { fBio = it; bioCount.text = "${it.length}/300" })
                }
                f.addView(bioIn, lp(MATCH, WRAP).apply { height = WRAP }); bioIn.minHeight = dpi(112f)
                f.addView(bioCount, lp(MATCH, WRAP, t = 4f))
                if (err.isNotEmpty()) f.addView(sdText(ctx, err, 14.5f, Rs.c("#B4183C"), bold = false).apply { lineHeight(1.15f); background = rounded(Rs.c("#FFF0F3"), 14f, 1f, Rs.c("#FBD3DC")); setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f)) }, lp(MATCH, WRAP, t = 10f))
                f.addView(SdSheets.bigButton(h, if (saving) "Saving…" else "Save Profile", if (saving) null else Icon(Lucide.Check, white, 22f, 3f)) { if (!saving) save() }.apply { alpha = if (saving) 0.85f else 1f }, lp(MATCH, dpi(58f), t = 18f))
                f.addView(SdSheets.textButton(h, "Cancel") { if (!saving) { editing = false; err = ""; draw() } }, lp(MATCH, dpi(46f), t = 6f))
                col.addView(f, lp(MATCH, WRAP))
            } else {
                val head = vbox(ctx, 0f, Gravity.CENTER_HORIZONTAL).apply { setPadding(dpi(24f), 0, dpi(24f), 0) }
                val nameRow = hbox(ctx, 8f, Gravity.CENTER)
                nameRow.addView(Rs.title(ctx, (if (name.isNotEmpty()) SdRules.firstName(name) else "Your name") + (if (age != null) ", $age" else ""), 28f, ink).oneLine())
                if (female && cStatus == "approved") nameRow.addView(ImageView(ctx).apply { setImageDrawable(Seal(26f)) }, lp(dpi(26f), dpi(26f)))
                head.addView(nameRow)
                head.addView(sdText(ctx, listOf(city0, state0).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { "Add your city" }, 17f, ink).oneLine().icon(Icon(Lucide.MapPin, Rs.ICON, 19f), 6f), lp(WRAP, WRAP, t = 6f))
                if (savedNote.isNotEmpty()) head.addView(sdText(ctx, savedNote, 14.5f, Rs.c("#17803A")), lp(WRAP, WRAP, t = 8f))
                col.addView(head, lp(MATCH, WRAP, t = 16f))
                // plan + credits
                val two = hbox(ctx, 10f, Gravity.TOP).apply { setPadding(dpi(20f), 0, dpi(20f), 0) }
                fun card(bg: android.graphics.drawable.Drawable, onClick: () -> Unit, build: (LinearLayout) -> Unit): View {
                    val c = vbox(ctx, 4f).apply { background = bg; setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(11f)) }
                    build(c); c.press().onTap(onClick); return c
                }
                val dl = h.days
                two.addView(card(if (premium) grad(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Rs.c("#FFF0F7"), white), 18f, 1f, K.line) else rounded(white, 18f, 1f, K.line), { if (premium) h.showWallet() else h.unlock() }) { c ->
                    c.addView(sdText(ctx, if (premium) "Premium" else "Free plan", 16f, K.wine).icon(Icon(Lucide.Crown, K.hot, 18f, 2.2f, if (premium) K.hot else null), 6f))
                    c.addView(sdText(ctx, if (!premiumKnown) "Checking…" else if (premium) (if (dl != null && dl != "Renew") dl else "Active") else "Get Premium", 13.5f, if (premium) K.ink2 else K.hot, bold = !premium))
                    if (premium && until.isNotEmpty()) c.addView(sdText(ctx, "till $until", 12.5f, K.sub, bold = false))
                }, lp(0, MATCH, 1f))
                two.addView(card(rounded(white, 18f, 1f, K.line), { h.showWallet() }) { c ->
                    c.addView(sdText(ctx, "${credits?.let { SdRules.inr(it) } ?: "–"} ${if (credits == 1) "Credit" else "Credits"}", 16f, K.wine).icon(Coins(20f), 6f))
                    c.addView(sdText(ctx, if (premium && until.isNotEmpty()) "End with your plan" else "Come with a Premium plan", 13.5f, K.ink2, bold = false))
                    if (premium && until.isNotEmpty()) c.addView(sdText(ctx, until, 12.5f, K.sub, bold = false))
                }, lp(0, MATCH, 1f))
                col.addView(two, lp(MATCH, WRAP, t = 16f))
                if (missing.isNotEmpty()) {
                    val m = vbox(ctx).apply { background = grad(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Rs.c("#FFE7F2"), Rs.c("#FFF6FA")), 20f, 1.5f, Rs.c("#F3A7C6")); setPadding(dpi(14f), dpi(14f), dpi(14f), dpi(12f)) }
                    m.addView(sdText(ctx, "Complete your profile", 17f, Rs.c("#A91C56")))
                    m.addView(sdText(ctx, "Add ${missing.joinToString(", ") { SdRules.NEED_LABEL[it] ?: it }} — Premium features stay locked until then.", 14f, K.ink2, bold = false).lineHeight(1.15f), lp(WRAP, WRAP, t = 3f))
                    m.addView(sdText(ctx, "Complete Now", 15.5f, white).apply { gravity = Gravity.CENTER; background = rounded(K.pill, 22f); setPadding(dpi(18f), 0, dpi(18f), 0) }.press().onTap { startEditing() }, lp(WRAP, dpi(44f), t = 10f))
                    col.addView(m, lp(MATCH, WRAP, l = 20f, r = 20f, t = 14f))
                }
                // about
                val ab = vbox(ctx).apply { setPadding(dpi(24f), 0, dpi(24f), 0) }
                ab.addView(sdText(ctx, "About Me", 21f, Rs.c("#A1144A")))
                if (bio.trim().isNotEmpty()) ab.addView(sdText(ctx, bio, 17f, Rs.c("#161216"), bold = false).lineHeight(1.2f), lp(MATCH, WRAP, t = 8f))
                else ab.addView(sdText(ctx, "+ Add a few lines about you", 15.5f, Rs.c("#A1144A")).apply { gravity = Gravity.CENTER_VERTICAL }.onTap { startEditing() }, lp(WRAP, dpi(44f), t = 6f))
                val chips = SdProfileView.Flow(ctx, 6f)
                listOfNotNull(age?.let { Lucide.Calendar to "$it Years" }, h.gender.takeIf { it.isNotEmpty() }?.let { Lucide.User to (if (female) "Female" else "Male") },
                    (city0.ifEmpty { state0 }).takeIf { it.isNotEmpty() }?.let { Lucide.MapPin to it }).forEach { (ic, t) ->
                    chips.addView(sdText(ctx, t, 14f, K.ink, bold = false).icon(Icon(ic, Rs.c("#97294B"), 16f), 5f).apply { gravity = Gravity.CENTER_VERTICAL; background = rounded(white, 999f, 1f, Rs.c("#EED6E2")); setPadding(dpi(9f), 0, dpi(11f), 0); height = dpi(36f) })
                }
                ab.addView(chips, lp(MATCH, WRAP, t = 12f))
                col.addView(ab, lp(MATCH, WRAP, t = 20f))
                // verification
                val ver = vbox(ctx).apply { setPadding(dpi(24f), 0, dpi(24f), 0) }
                ver.addView(sdText(ctx, "Verification", 21f, Rs.c("#A1144A")))
                val vb = vbox(ctx).apply { background = grad(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Rs.c("#FDE5F1"), Rs.c("#FFF6FA")), 18f, 1f, Rs.c("#F5C7DC")); setPadding(dpi(14f), dpi(13f), dpi(14f), dpi(13f)) }
                if (female) {
                    val r = hbox(ctx, 12f)
                    r.addView(ImageView(ctx).apply { setImageDrawable(when (cStatus) { "approved" -> Seal(38f); "pending" -> Icon(Lucide.Hourglass, K.rose, 34f, 1.8f); else -> Icon(Lucide.ShieldCheck, K.rose, 38f, 1.8f) }) }, lp(dpi(38f), dpi(38f)))
                    val t = vbox(ctx)
                    t.addView(sdText(ctx, when (cStatus) { "approved" -> "Verified Creator"; "pending" -> "Under review"; "rejected" -> "Not approved"; else -> "Not verified yet" }, 17f, Rs.c("#A91C56")))
                    t.addView(sdText(ctx, when (cStatus) { "approved" -> "Your profile shows the verified badge."; "pending" -> "Our team is checking your video. This usually takes a short while."; else -> "Verify with a short live video, the same as Become a Creator." }, 14f, K.ink2, bold = false).lineHeight(1.1f), lp(WRAP, WRAP, t = 2f))
                    r.addView(t, lp(0, WRAP, 1f))
                    vb.addView(r, lp(MATCH, WRAP))
                    if (cStatus == "none" || cStatus == "rejected") vb.addView(sdText(ctx, if (cStatus == "rejected") "Verify Again" else "Verify Now", 16.5f, white).apply { gravity = Gravity.CENTER; background = rounded(K.pill, 24f) }.press().onTap { h.verify() }, lp(MATCH, dpi(48f), t = 12f))
                } else {
                    val ok = photo.isNotEmpty() && name.isNotEmpty() && age != null && state0.isNotEmpty() && city0.isNotEmpty()
                    if (ok) vb.addView(sdText(ctx, "Verified · you show on the map", 16.5f, Rs.c("#A91C56")).icon(Seal(24f), 8f), lp(WRAP, WRAP, b = 8f))
                    else vb.addView(sdText(ctx, "Add your photo, name, age, state and city to get verified and show on the map.", 15f, Rs.c("#A91C56")).lineHeight(1.1f), lp(MATCH, WRAP, b = 8f))
                    vb.addView(sdText(ctx, "A full profile — photo, name, age and a few lines about you — helps you get accepted.", 15f, K.ink2, bold = false).lineHeight(1.15f))
                    val grid = vbox(ctx, 6f)
                    for (pair in listOf("Photo" to photo.isNotEmpty(), "Name" to name.isNotEmpty(), "Age" to (age != null), "About Me" to bio.trim().isNotEmpty(), "State" to state0.isNotEmpty(), "City" to city0.isNotEmpty()).chunked(2)) {
                        val r = hbox(ctx, 6f)
                        for ((t, okk) in pair) {
                            val cell = hbox(ctx, 7f)
                            val mark: View = if (okk) ImageView(ctx).apply { setImageDrawable(Icon(Lucide.Check, Rs.c("#16A34A"), 12f, 3.4f)); scaleType = ImageView.ScaleType.CENTER; background = oval(Rs.c("#E7F8EA")) }
                            else FrameLayout(ctx).apply { background = oval(Rs.c("#FFE4EF")); addView(View(ctx).apply { background = oval(K.hot) }, FrameLayout.LayoutParams(dpi(7f), dpi(7f), Gravity.CENTER)) }
                            cell.addView(mark, lp(dpi(20f), dpi(20f)))
                            cell.addView(sdText(ctx, t, 15f, if (okk) K.ink2 else K.hot))
                            r.addView(cell, lp(0, WRAP, 1f))
                        }
                        grid.addView(r, lp(MATCH, WRAP))
                    }
                    vb.addView(grid, lp(MATCH, WRAP, t = 10f))
                }
                ver.addView(vb, lp(MATCH, WRAP, t = 10f))
                col.addView(ver, lp(MATCH, WRAP, t = 22f))
                // wallet · back to True Find
                val rows = vbox(ctx).apply { setPadding(dpi(20f), dpi(12f), dpi(20f), dpi(30f)) }
                fun row(ic: String, title: String, sub: String, blue: Boolean = false, onClick: () -> Unit) {
                    // "Back to True Find" is a blue box (owner, 20 Nov): True Find's blue, white words
                    val r = hbox(ctx, 12f).apply {
                        background = if (blue) grad(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Rs.c("#4F5FEA"), Rs.c("#3346D3")), 18f) else rounded(white, 18f, 1f, K.line2)
                        if (blue) elevation = Ui.dp(4f)
                        setPadding(dpi(12f), dpi(8f), dpi(12f), dpi(8f)); minimumHeight = dpi(60f)
                    }
                    r.addView(ImageView(ctx).apply { setImageDrawable(Icon(ic, if (blue) white else K.rose, 21f)); scaleType = ImageView.ScaleType.CENTER; background = rounded(if (blue) Rs.argb(46, 255, 255, 255) else K.tint, 13f) }, lp(dpi(42f), dpi(42f)))
                    val t = vbox(ctx); t.addView(sdText(ctx, title, 16.5f, if (blue) white else K.ink2)); t.addView(sdText(ctx, sub, 13.5f, if (blue) Rs.argb(220, 255, 255, 255) else K.sub, bold = false).lineHeight(1.1f), lp(WRAP, WRAP, t = 1f))
                    r.addView(t, lp(0, WRAP, 1f))
                    r.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.ChevronRight, if (blue) white else K.wine, 20f)) }, lp(dpi(20f), dpi(20f)))
                    r.press().onTap(onClick)
                    rows.addView(r, lp(MATCH, WRAP, t = 8f))
                }
                row(Lucide.Wallet, "Reconnect Wallet", if (premium) "Plan, credits and history" else "Get Premium · credits come with the plan") { h.showWallet() }
                row(Lucide.Home, "Back to True Find", "Leave Reconnect", blue = true) { h.exit() }
                col.addView(rows, lp(MATCH, WRAP))
            }
            scroll.removeAllViews()
            scroll.addView(col, FrameLayout.LayoutParams(-1, -2))
        }
        draw()
        close = h.layer.page(root, z = 31f, back = { if (editing && !saving) { editing = false; err = ""; draw(); true } else false })
    }

    private fun watch(f: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { f(s.toString()) }
    }
    private fun dobMask(v: String): String {
        val d = v.filter { it.isDigit() }.take(8)
        return if (d.length <= 2) d else if (d.length <= 4) "${d.take(2)}/${d.drop(2)}" else "${d.take(2)}/${d.substring(2, 4)}/${d.drop(4)}"
    }
    private fun dobToIso(text: String): String {
        val m = Regex("^(\\d{2})/(\\d{2})/(\\d{4})$").find(text) ?: return ""
        val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val y = m.groupValues[3].toInt()
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { isLenient = false; clear(); set(y, mo - 1, d) }
        return try { c.timeInMillis; String.format(java.util.Locale.US, "%04d-%02d-%02d", y, mo, d) } catch (e: Exception) { "" }
    }
}

@Suppress("unused") private val keepTv3: Class<TextView> = TextView::class.java
