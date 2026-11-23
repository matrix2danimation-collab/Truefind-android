package app.truefind.android

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** The bottom sheets of Secret Dating: Go Premium, Verify, Send Request, Complete Profile, Add Story. */
object SdSheets {
    /** set by MainActivity: opens the phone's photo picker, answers with the photo (bytes, type) or null */
    var pickImage: (((ByteArray?, String) -> Unit) -> Unit)? = null

    private val PREMIUM_WHY = mapOf(
        "story" to "Posting stories is for Premium members.", "watch" to "Watching stories is for Premium members.",
        "chat" to "Chat is for Premium members.", "request" to "Sending requests is for Premium members.",
        "like" to "Likes are for Premium members.", "activity" to "See who liked you, messaged you and viewed your profile.",
    )
    private val PLANS = listOf(Triple("Daily", 12, 1 to 3), Triple("Starter", 39, 7 to 5), Triple("Pro", 79, 7 to 8), Triple("Elite", 169, 30 to 10))
    private fun daysText(d: Int) = if (d == 1) "1 day" else if (d == 30) "1 month" else "$d days"

    // ── shared pieces ──
    fun panel(h: SecretHome, padSide: Float = 22f): LinearLayout = vbox(h.context).apply {
        background = K.sheet()
        setPadding(dpi(padSide), dpi(10f), dpi(padSide), dpi(22f))
        elevation = Ui.dp(8f)
        addView(View(h.context).apply { background = rounded(Rs.c("#F2C3D8"), 3f) }, lp(dpi(44f), dpi(5f), b = 16f, gravity = Gravity.CENTER_HORIZONTAL))
    }
    fun head(h: SecretHome, icon: android.graphics.drawable.Drawable, title: String, sub: String, titleSize: Float = 28f): View {
        val row = hbox(h.context, 12f)
        row.addView(ImageView(h.context).apply { setImageDrawable(icon); scaleType = ImageView.ScaleType.CENTER; background = K.badge(); elevation = Ui.dp(3f) }, lp(dpi(52f), dpi(52f)))
        val t = vbox(h.context)
        t.addView(sdText(h.context, title, titleSize, K.wine, serif = true))
        t.addView(sdText(h.context, sub, 15f, K.sub, bold = false).lineHeight(1.15f), lp(WRAP, WRAP, t = 2f))
        row.addView(t, lp(0, WRAP, 1f))
        return row
    }
    fun perks(h: SecretHome, list: List<String>): View {
        val box = vbox(h.context, 10f)
        for (t in list) {
            val r = hbox(h.context, 10f)
            r.addView(ImageView(h.context).apply { setImageDrawable(Icon(Lucide.Check, K.hot, 14f, 3.2f)); scaleType = ImageView.ScaleType.CENTER; background = oval(K.tint) }, lp(dpi(24f), dpi(24f)))
            r.addView(sdText(h.context, t, 16f, K.ink2), lp(0, WRAP, 1f))
            box.addView(r, lp(MATCH, WRAP))
        }
        return box
    }
    fun bigButton(h: SecretHome, text: String, icon: android.graphics.drawable.Drawable? = null, size: Float = 21f, onClick: () -> Unit): FrameLayout {
        val tv = sdText(h.context, text, size, Color.WHITE).apply { gravity = Gravity.CENTER; if (icon != null) icon(icon, 10f) }
        return FrameLayout(h.context).apply {
            background = K.btn(29f)   // flat ("2D", 23 Nov): no shadow under the button
            addView(tv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            press().onTap(onClick)
        }
    }
    fun textButton(h: SecretHome, text: String, onClick: () -> Unit): TextView =
        sdText(h.context, text, 16f, K.wine).apply { gravity = Gravity.CENTER; setOnClickListener { onClick() } }

    // ── Go Premium (Free members tapping a Premium feature) ──
    fun premium(h: SecretHome, what: String, girl: Boolean, intro: JSONObject?, onGet: () -> Unit) {
        val p = panel(h)
        var close: () -> Unit = {}
        p.addView(head(h, Icon(Lucide.Gem, Color.WHITE, 26f, 2f), if (girl) "Get a Plan" else "Go Premium",
            if (girl) "Requests need credits, and credits come with a plan." else PREMIUM_WHY[what] ?: "Free members can browse and view profiles."))
        p.addView(perks(h, if (girl) listOf("Request credits to send requests (1 credit each)", "Chat stays free for you", "Credits end when the plan ends")
            else listOf("Unlimited chat", "Send requests to verified singles (1 credit each)", "Post and watch stories near you")), lp(MATCH, WRAP, t = 16f))
        if (intro?.optBoolean("available") == true) {
            val r = hbox(h.context, 10f).apply { background = grad(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Rs.c("#FFF4D6"), Rs.c("#FFFBEF")), 16f, 1f, Rs.c("#F4D27A")); setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f)) }
            r.addView(ImageView(h.context).apply { setImageDrawable(Icon(Lucide.Sparkles, Rs.c("#B45309"), 22f)) }, lp(dpi(22f), dpi(22f)))
            val t = android.text.SpannableStringBuilder("First time?").apply { setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0); append(" 1 day + 3 credits for just 1 True Find coin.") }
            r.addView(sdText(h.context, t, 14.5f, Rs.c("#7C3A06"), bold = false).lineHeight(1.1f), lp(0, WRAP, 1f))
            p.addView(r, lp(MATCH, WRAP, t = 14f))
        }
        val plans = vbox(h.context).apply { background = rounded(Color.WHITE, 18f, 1f, K.line2); setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(10f)) }
        for (row in PLANS.chunked(2)) {
            val r = hbox(h.context, 8f)
            for ((name, price, dc) in row) {
                val cell = hbox(h.context, 8f).apply { background = rounded(K.tint2, 14f, 1f, K.line2); setPadding(dpi(10f), dpi(9f), dpi(10f), dpi(9f)) }
                cell.addView(ImageView(h.context).apply { setImageDrawable(Coins(24f)) }, lp(dpi(24f), dpi(24f)))
                val t = vbox(h.context)
                t.addView(sdText(h.context, "$name · ₹$price", 15.5f, K.wine).oneLine())
                t.addView(sdText(h.context, "${daysText(dc.first)} · ${dc.second} credits", 13f, K.ink2, bold = false).oneLine())
                cell.addView(t, lp(0, WRAP, 1f))
                r.addView(cell, lp(0, WRAP, 1f))
            }
            plans.addView(r, lp(MATCH, WRAP, t = if (plans.childCount == 0) 0f else 8f))
        }
        plans.addView(sdText(h.context, "Credits come with your plan. Unused credits end when the plan ends.", 13.5f, K.sub, bold = false).apply { gravity = Gravity.CENTER; lineHeight(1.1f) }, lp(MATCH, WRAP, t = 8f))
        p.addView(plans, lp(MATCH, WRAP, t = 14f))
        p.addView(bigButton(h, if (girl) "See Plans" else "Get Premium") { close(); onGet() }, lp(MATCH, dpi(58f), t = 20f))
        p.addView(textButton(h, "Not now") { close() }, lp(MATCH, dpi(46f), t = 6f))
        close = h.layer.sheet(p)
    }

    // ── Verify first (girls): Secret Dating is free for verified girls ──
    fun verify(h: SecretHome, status: String, onVerify: () -> Unit) {
        val pending = status == "pending"
        val p = panel(h)
        var close: () -> Unit = {}
        p.addView(head(h, if (pending) Icon(Lucide.Hourglass, Color.WHITE, 25f, 2f) else Icon(Lucide.ShieldCheck, Color.WHITE, 26f, 2f), if (pending) "Under review" else "Verify your profile",
            when { pending -> "Our team is checking your video. Secret Dating opens for you as soon as it's approved."
                status == "rejected" -> "Your last video wasn't approved. Verify again with a short live video."
                else -> "Secret Dating is free for verified girls. Verify with a short live video, the same as Become a Creator." }))
        p.addView(perks(h, listOf("Chat free — no plan needed", "Be seen on the map as verified", "First plan: 1 day + 3 credits for 1 True Find coin")), lp(MATCH, WRAP, t = 16f))
        if (!pending) p.addView(bigButton(h, if (status == "rejected") "Verify Again" else "Verify Now") { close(); onVerify() }, lp(MATCH, dpi(58f), t = 20f))
        p.addView(textButton(h, if (pending) "OK" else "Not now") { close() }, lp(MATCH, dpi(46f), t = if (pending) 18f else 6f))
        close = h.layer.sheet(p)
    }

    // ── Complete your profile ──
    fun need(h: SecretHome, missing: List<String>, onFill: () -> Unit) {
        val p = panel(h)
        var close: () -> Unit = {}
        p.addView(head(h, Icon(Lucide.User, Color.WHITE, 26f, 2.2f), "Complete Your Profile", "Premium features open only after your profile is complete.", 26f))
        val grid = vbox(h.context, 8f)
        for (row in listOf("name", "age", "state", "city").chunked(2)) {
            val r = hbox(h.context, 8f)
            for (k in row) {
                val miss = k in missing
                val cell = hbox(h.context, 8f).apply { background = rounded(Color.WHITE, 14f, 1f, if (miss) Rs.c("#F3A7C6") else K.line2); setPadding(dpi(12f), 0, dpi(12f), 0) }
                val mark: View = if (miss) FrameLayout(h.context).apply { background = oval(Rs.c("#FFE4EF")); addView(View(h.context).apply { background = oval(K.hot) }, FrameLayout.LayoutParams(dpi(8f), dpi(8f), Gravity.CENTER)) }
                else ImageView(h.context).apply { setImageDrawable(Icon(Lucide.Check, Rs.c("#16A34A"), 13f, 3.4f)); scaleType = ImageView.ScaleType.CENTER; background = oval(Rs.c("#E7F8EA")) }
                cell.addView(mark, lp(dpi(22f), dpi(22f)))
                cell.addView(sdText(h.context, SdRules.NEED_LABEL[k] ?: k, 16f, if (miss) K.wine else K.ink2), lp(0, WRAP, 1f))
                cell.addView(sdText(h.context, if (miss) "Needed" else "Done", 12.5f, if (miss) K.hot else Rs.c("#16A34A"), bold = false))
                r.addView(cell, lp(0, dpi(46f), 1f))
            }
            grid.addView(r, lp(MATCH, WRAP))
        }
        p.addView(grid, lp(MATCH, WRAP, t = 16f))
        p.addView(bigButton(h, "Complete Profile") { close(); onFill() }, lp(MATCH, dpi(58f), t = 18f))
        p.addView(textButton(h, "Not now") { close() }, lp(MATCH, dpi(46f), t = 6f))
        close = h.layer.sheet(p)
    }

    // ── Send Request (asks first, then uses 1 credit — nothing is spent on a single tap) ──
    fun request(h: SecretHome, person: JSONObject) {
        val p = panel(h)
        var close: () -> Unit = {}
        val ctx = h.context
        val credits = h.creditsShown
        val none = credits == 0
        val name = SdRules.firstName(SdRules.s(person, "name")).ifEmpty { "they" }
        val where = SdRules.cityOf(person)
        val row = hbox(ctx, 14f)
        val ring = FrameLayout(ctx).apply { background = grad(GradientDrawable.Orientation.TL_BR, intArrayOf(K.rose, Rs.c("#FF84BD"))).apply { shape = GradientDrawable.OVAL }; setPadding(dpi(2.5f), dpi(2.5f), dpi(2.5f), dpi(2.5f)) }
        val wb = FrameLayout(ctx).apply { background = oval(Color.WHITE); setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f)) }
        wb.addView(RoundPhoto(ctx, 62f).also { it.set(SdRules.thumbs(person), Demo.round(person, dpi(62f))) }, FrameLayout.LayoutParams(-1, -1))
        ring.addView(wb, FrameLayout.LayoutParams(-1, -1))
        row.addView(ring, lp(dpi(62f), dpi(62f)))
        val t = vbox(ctx)
        t.addView(sdText(ctx, "Send Request", 28f, K.wine, serif = true))
        t.addView(sdText(ctx, "to ${SdRules.firstName(SdRules.s(person, "name"))}${SdRules.s(person, "age").let { if (it.isNotEmpty()) ", $it" else "" }}${if (where.isNotEmpty()) " · $where" else ""}", 16.5f, K.ink2, bold = false).oneLine(), lp(WRAP, WRAP, t = 3f))
        row.addView(t, lp(0, WRAP, 1f))
        p.addView(row, lp(MATCH, WRAP))
        val box = hbox(ctx, 12f).apply { background = rounded(Color.WHITE, 18f, 1f, K.line2); setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f)) }
        box.addView(ImageView(ctx).apply { setImageDrawable(Coins(30f)) }, lp(dpi(30f), dpi(30f)))
        val bt = vbox(ctx)
        bt.addView(sdText(ctx, "1 credit per request", 17f, K.ink2))
        val until = h.until
        bt.addView(sdText(ctx, when { credits == null -> "Credits come with your Premium plan."; none -> "You have no credits left in this plan."
            else -> "You have ${SdRules.inr(credits)} ${if (credits == 1) "credit" else "credits"}${if (until.isNotEmpty()) " · they end $until" else ""}." },
            15f, if (none) Rs.c("#B4183C") else K.sub, bold = false), lp(WRAP, WRAP, t = 1f))
        box.addView(bt, lp(0, WRAP, 1f))
        p.addView(box, lp(MATCH, WRAP, t = 16f))
        p.addView(sdText(ctx, "When $name accepts, your private chat opens in Chat, and then you can chat without limits.", 15f, K.sub, bold = false).lineHeight(1.15f), lp(MATCH, WRAP, l = 2f, r = 2f, t = 12f))
        var sending = false
        if (none) p.addView(bigButton(h, "Get More Credits", Coins(24f)) { close(); h.showWallet() }, lp(MATCH, dpi(58f), t = 16f))
        else {
            lateinit var btn: FrameLayout
            btn = bigButton(h, "Send Request · 1 Credit", Icon(Lucide.Send, Color.WHITE, 23f, 2.2f)) {
                if (sending) return@bigButton
                sending = true
                ((btn.getChildAt(0)) as TextView).apply { text = "Sending…"; setCompoundDrawablesWithIntrinsicBounds(null, null, null, null) }
                btn.alpha = 0.85f
                h.sendRequest(person, sheetDone = { close() }, refresh = { })
            }
            p.addView(btn, lp(MATCH, dpi(58f), t = 16f))
        }
        p.addView(textButton(h, "Cancel") { if (!sending) close() }, lp(MATCH, dpi(46f), t = 6f))
        close = h.layer.sheet(p, locked = { sending })
    }

    // ── Add to your story ──
    fun addStory(h: SecretHome, onPosted: (JSONObject) -> Unit) {
        val ctx = h.context
        val p = panel(h, 20f)
        var close: () -> Unit = {}
        var bytes: ByteArray? = null
        var mime = "image/jpeg"
        var busy = false
        val top = hbox(ctx)
        top.addView(sdText(ctx, "Add to your story", 26f, K.wine, serif = true), lp(0, WRAP, 1f))
        top.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.X, K.wine, 24f)); scaleType = ImageView.ScaleType.CENTER }.press().onTap { if (!busy) close() }, lp(dpi(44f), dpi(44f)))
        p.addView(top, lp(MATCH, WRAP))
        // the photo box (9 : 14), dashed until a photo is chosen
        val w = (ctx.resources.displayMetrics.widthPixels * 0.6f).toInt()
        val pic = FrameLayout(ctx).apply { background = DashedBox(); clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND }
        val preview = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        val empty = vbox(ctx, 8f, Gravity.CENTER_HORIZONTAL)
        empty.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.ImagePlus, K.rose, 34f, 1.6f)) }, lp(dpi(34f), dpi(34f)))
        empty.addView(sdText(ctx, "Choose a photo", 17f, K.ink2), lp(WRAP, WRAP))
        val change = sdText(ctx, "Change", 13f, K.wine).apply { background = rounded(Rs.argb(230, 255, 255, 255), 12f); setPadding(dpi(10f), dpi(4f), dpi(10f), dpi(4f)); visibility = View.GONE }
        pic.addView(preview, FrameLayout.LayoutParams(-1, -1))
        pic.addView(empty, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        pic.addView(change, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dpi(8f), dpi(8f)) })
        p.addView(pic, lp(w, (w * 14f / 9f).toInt(), t = 10f, gravity = Gravity.CENTER_HORIZONTAL))
        // caption
        val capBox = FrameLayout(ctx)
        val cap = EditText(ctx).apply {
            hint = "Say something (optional)"; setHintTextColor(K.soft); setTextColor(K.ink); typeface = Ui.regular; textSize = 16f
            filters = arrayOf(InputFilter.LengthFilter(140)); maxLines = 1; isSingleLine = true
            background = rounded(Color.WHITE, 25f, 1f, Rs.c("#F2C3D8")); setPadding(dpi(18f), 0, dpi(60f), 0)
        }
        val count = sdText(ctx, "0/140", 13f, K.soft, bold = false)
        capBox.addView(cap, FrameLayout.LayoutParams(-1, dpi(50f)))
        capBox.addView(count, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dpi(18f) })
        p.addView(capBox, lp(MATCH, WRAP, t = 14f))
        val err = sdText(ctx, "", 14f, Rs.c("#c0153d"), bold = false).lineHeight(1.2f).apply { visibility = View.GONE }
        p.addView(err, lp(MATCH, WRAP, l = 2f, r = 2f, t = 10f))
        val rule = hbox(ctx, 10f, Gravity.TOP).apply { background = rounded(Rs.c("#FFF6FA"), 16f, 1f, K.line2); setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f)) }
        rule.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.ShieldCheck, K.rose, 18f)) }, lp(dpi(18f), dpi(18f), t = 1f))
        rule.addView(sdText(ctx, "No phone numbers, links or social IDs — in the text or written on the photo. Stories with them are removed automatically. Your story disappears after 24 hours.", 13.5f, K.sub, bold = false).lineHeight(1.15f), lp(0, WRAP, 1f))
        p.addView(rule, lp(MATCH, WRAP, t = 14f))
        lateinit var post: FrameLayout
        fun refresh() {
            val capErr = SdRules.findContact(cap.text.toString())
            count.text = "${cap.text.length}/140"
            cap.background = rounded(Color.WHITE, 25f, 1f, if (capErr.isNotEmpty()) Rs.c("#f87171") else Rs.c("#F2C3D8"))
            val e = err.tag as? String ?: ""
            val shownErr = e.ifEmpty { if (capErr.isNotEmpty()) "$capErr Please remove it." else "" }
            err.text = shownErr; err.visibility = if (shownErr.isEmpty()) View.GONE else View.VISIBLE
            post.alpha = if (bytes == null || capErr.isNotEmpty()) 0.5f else 1f
        }
        cap.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { err.tag = ""; refresh() }
        })
        pic.press().onTap {
            if (busy) return@onTap
            val pick = pickImage ?: return@onTap
            pick(chosen@{ b, type ->
                if (b == null) return@chosen
                if (!type.startsWith("image/")) { err.tag = "Please choose a photo."; refresh(); return@chosen }
                if (b.size > 10 * 1024 * 1024) { err.tag = "This photo is too big. Please choose one under 10 MB."; refresh(); return@chosen }
                bytes = b; mime = type; err.tag = ""
                val o = BitmapFactory.Options().apply { inSampleSize = 4 }
                preview.setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size, o))
                preview.visibility = View.VISIBLE; empty.visibility = View.GONE; change.visibility = View.VISIBLE
                pic.background = rounded(Color.WHITE, 20f, 2f, K.line)
                refresh()
            })
        }
        post = bigButton(h, "Post story", Icon(Lucide.Send, Color.WHITE, 18f), 20f) {
            val b = bytes ?: return@bigButton
            if (busy) return@bigButton
            val bad = SdRules.findContact(cap.text.toString())
            if (bad.isNotEmpty()) { err.tag = "$bad Please remove it to post."; refresh(); return@bigButton }
            busy = true; err.tag = ""; refresh()
            val label = (post.getChildAt(0) as TextView)
            label.text = "Checking & posting…"; label.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
            Api.uploadPrivate(b, "story.jpg", mime) { up ->
                val uri = up.data.optString("file_uri")
                if (!up.ok || uri.isEmpty()) { busy = false; label.text = "Post story"; err.tag = "Upload failed. Please try again."; refresh(); return@uploadPrivate }
                Api.invoke("createStory", JSONObject().put("file_uri", uri).put("kind", "photo").put("caption", cap.text.toString().trim().take(140))) { r ->
                    busy = false
                    if (r.ok) { close(); onPosted(r.data); return@invoke }
                    label.text = "Post story"; label.icon(Icon(Lucide.Send, Color.WHITE, 18f), 9f)
                    val d = r.data; val e = d.optString("error")
                    err.tag = when {
                        e == "blocked" -> "Story removed: ${d.optString("reason").ifEmpty { "it has a phone number, link or ID." }}"
                        e == "premium_required" -> "Stories are for Premium members."
                        e == "verify_first" -> "Verify your profile first — stories are free for verified girls."
                        e == "profile_incomplete" -> "Add your name, age, state and city first."
                        r.status == 503 -> "Could not check the photo. Please try again."
                        r.status == 404 -> "Stories are not switched on yet. Please try again later."
                        else -> d.optString("message").ifEmpty { e.ifEmpty { r.error ?: "Could not post your story. Please try again." } }
                    }
                    refresh()
                }
            }
        }
        p.addView(post, lp(MATCH, dpi(56f), t = 16f))
        refresh()
        close = h.layer.sheet(p, z = 35f, locked = { busy })
    }

    /** the dashed pink box of "Choose a photo" (2px dashed #EE6AA9, radius 20, K.tile inside) */
    private class DashedBox : android.graphics.drawable.Drawable() {
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = Ui.dp(2f); color = Rs.c("#EE6AA9")
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(Ui.dp(6f), Ui.dp(4f)), 0f)
        }
        override fun draw(c: android.graphics.Canvas) {
            val b = android.graphics.RectF(bounds); val r = Ui.dp(20f)
            fill.shader = android.graphics.LinearGradient(0f, b.top, 0f, b.bottom, Rs.c("#FDE5F1"), Color.WHITE, android.graphics.Shader.TileMode.CLAMP)
            c.drawRoundRect(b, r, r, fill)
            b.inset(Ui.dp(1f), Ui.dp(1f)); c.drawRoundRect(b, r, r, line)
        }
        override fun getOutline(o: android.graphics.Outline) { o.setRoundRect(bounds, Ui.dp(20f)) }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}

@Suppress("unused") private val keepLL: Class<LinearLayout> = LinearLayout::class.java
