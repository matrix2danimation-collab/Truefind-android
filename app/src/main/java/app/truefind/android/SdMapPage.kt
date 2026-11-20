package app.truefind.android

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.min

/**
 * SECRET DATING · Online Map (/dating) — the web's map view of SecretApp.jsx, native:
 * the maroon top of Chat and My Activity (owner, 20 Nov night: same look, no credits here) with
 * "Online Map", the plan pill and "Verified Boys & Girls Across India", then on the white sheet the 3D Earth
 * (Near You / Online filters, full screen), "N People · M Online", the Stories (moved here from Home, right
 * under the map — owner, 20 Nov night), the gliding strip of people and the location promise.
 */
@SuppressLint("ViewConstructor")
class SdMapPage(private val h: SecretHome) : FrameLayout(h.context) {
    private val scroll = ScrollView(context)
    private val col = vbox(context)
    private val planBox = FrameLayout(context)
    private val noticeBox = sdText(context, "", 15f, Ui.c("#166534"), bold = false)
    private val mapBox = object : FrameLayout(context) {
        // the white 4px frame on top of the map (outside the full screen)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
        override fun dispatchDraw(c: Canvas) {
            super.dispatchDraw(c)
            if (!fullOn) { p.strokeWidth = Ui.dp(4f); val r = Ui.dp(26f); val i = Ui.dp(2f); c.drawRoundRect(i, i, width - i, height - i, r - i, r - i, p) }
        }
    }
    private val mapInner = FrameLayout(context)
    private var fullOn = false
    private val globe = GlobeMap(context, onOpen = { h.openProfile(it) },
        onGroup = { list, title -> allSheet(list, title) }, onFull = { setFull(!fullOn) }, say = { h.say(it) })
    private val spin = ProgressBar(context).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.c("#FF9BCB")) }
    private val empty = sdText(context, "", 14.5f, K.wine).apply { gravity = Gravity.CENTER; background = rounded(Color.argb(240, 255, 255, 255), 16f); setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f)) }
    private val filters = hbox(context, 8f)
    private val nearBtn = sdText(context, "Near You", 14.5f, K.wine)
    private val onlineBtn = sdText(context, "Online", 14.5f, K.wine)
    private val peopleBtn = LinearLayout(context)
    private val peopleText = sdText(context, "", 19f, Color.WHITE).oneLine()
    private val peopleDot = FrameLayout(context)
    private val strip = HorizontalScrollView(context)
    private val stripRow = LinearLayout(context)
    private var filter = "all"
    private var fullHost: FrameLayout? = null

    val top = SdTop(context, h, "Online Map", bell = true, extra = planBox, subtitle = "Verified Boys & Girls Across India")
    private var storiesV: View? = null

    init {
        clipChildren = false
        setBackgroundColor(Color.WHITE)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        scroll.isFillViewport = true
        addView(scroll, LayoutParams(-1, -1))
        // the maroon top, then the white sheet over its lower edge (the page scrolls as one)
        val outer = vbox(context)
        val headBox = InsetFrame(context, MAP_SHEET_TOP + 26f).apply { setBackgroundColor(SD.M) }
        headBox.addView(MaroonDeco(context), LayoutParams(-1, -1))
        headBox.addView(top, LayoutParams(-1, -2))
        outer.addView(headBox, lp(MATCH, WRAP))
        val sheet = FrameLayout(context).apply { background = SD.sheetBg() }
        sheet.addView(sdHandle(context))
        col.setPadding(dpi(16f), dpi(26f), dpi(16f), dpi(40f))
        sheet.addView(col, LayoutParams(-1, -2))
        outer.addView(sheet, lp(MATCH, WRAP, t = -26f))
        scroll.addView(outer, LayoutParams(-1, -2))
        noticeBox.background = rounded(Ui.c("#F0FDF4"), 14f, 1f, Color.argb(89, 22, 163, 74))
        noticeBox.setPadding(dpi(14f), dpi(11f), dpi(14f), dpi(11f)); noticeBox.gravity = Gravity.CENTER
        noticeBox.setOnClickListener { h.clearNotice() }
        col.addView(noticeBox, lp(MATCH, WRAP, b = 12f))

        // the map: rounded, white frame; full screen on the button
        mapBox.clipToOutline = true
        mapBox.outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { if (fullOn) o.setRect(0, 0, v.width, v.height) else o.setRoundRect(0, 0, v.width, v.height, Ui.dp(26f)) } }
        mapBox.elevation = Ui.dp(8f)
        mapBox.outlineSpotShadowColorCompat(Color.argb(153, 196, 16, 98))
        mapBox.setBackgroundColor(Ui.c("#06020f"))
        mapInner.addView(globe, LayoutParams(-1, -1))
        mapInner.addView(spin, LayoutParams(dpi(30f), dpi(30f), Gravity.CENTER))
        mapInner.addView(empty, LayoutParams(-1, -2, Gravity.TOP).apply { leftMargin = 0; rightMargin = 0 })
        // filters: Near You · Online (one at a time; tap again for everyone)
        styleFilter(nearBtn, Lucide.MapPin); styleFilter(onlineBtn, null)
        nearBtn.contentDescription = "Near You"; onlineBtn.contentDescription = "Online"
        nearBtn.press().onTap {
            if (filter != "near" && h.meNow == null) { h.say("Add your city in your profile to see people near you."); return@onTap }
            filter = if (filter == "near") "all" else "near"; render()
        }
        onlineBtn.press().onTap { filter = if (filter == "online") "all" else "online"; render() }
        filters.addView(nearBtn, lp(WRAP, dpi(42f))); filters.addView(onlineBtn, lp(WRAP, dpi(42f)))
        mapInner.addView(filters, LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dpi(12f), dpi(12f), 0, 0) })
        mapBox.addView(mapInner, LayoutParams(-1, -1))
        val vw = resources.displayMetrics.widthPixels / Ui.density
        col.addView(mapBox, lp(MATCH, dpi(min(vw * 1.18f, 560f))))
        mapInner.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeEmpty() }

        // everyone on the map: verified boys and girls, online first
        peopleBtn.orientation = LinearLayout.HORIZONTAL; peopleBtn.gravity = Gravity.CENTER_VERTICAL
        // green (owner, 20 Nov): the "N People · M Online" bar
        peopleBtn.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.c("#22C55E"), Ui.c("#15924A"))).apply { cornerRadius = Ui.dp(29f); setStroke(dpi(2f), Color.WHITE) }
        peopleBtn.elevation = Ui.dp(6f)
        peopleBtn.setPadding(dpi(22f), 0, dpi(18f), 0)
        peopleBtn.addView(peopleDot, lp(dpi(19f), dpi(19f), r = 12f))
        peopleText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dp((vw * .044f).coerceIn(15f, 19f)))
        peopleBtn.addView(peopleText, lp(0, WRAP, 1f))
        peopleBtn.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.ChevronRight, Color.WHITE, 24f)) }, lp(dpi(24f), dpi(24f), l = 12f))
        peopleBtn.press().onTap { allSheet(h.onlineNow ?: emptyList(), null) }
        col.addView(peopleBtn, lp(MATCH, dpi(58f), t = 14f))

        // Stories: right under the map (owner, 20 Nov night: "मैप के नीचे")
        storiesV = h.storiesView().also { col.addView(it, lp(MATCH, WRAP, t = 22f)) }

        // the first 40 (online first) glide by on their own
        strip.isHorizontalScrollBarEnabled = false; strip.overScrollMode = View.OVER_SCROLL_NEVER; strip.clipToPadding = false
        stripRow.orientation = LinearLayout.HORIZONTAL
        stripRow.setPadding(dpi(16f), dpi(4f), dpi(16f), dpi(12f))
        strip.addView(stripRow)
        strip.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) pauseStrip(); false }
        col.addView(strip, lp(MATCH, WRAP, l = -16f, r = -16f, t = 16f))

        // privacy promise
        val promise = hbox(context, 10f).apply { background = rounded(Color.WHITE, 16f, 1f, K.line); setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f)) }
        promise.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.ShieldCheck, Ui.c("#16a34a"), 22f)) }, lp(dpi(22f), dpi(22f)))
        val pt = android.text.SpannableStringBuilder()
        val b0 = pt.length; pt.append("Don't worry about your location."); pt.setSpan(android.text.style.ForegroundColorSpan(K.wine), b0, pt.length, 0)
        pt.setSpan(android.text.style.TypefaceSpan(Ui.bold), b0, pt.length, 0)
        pt.append(" Your exact location is never shared — the map only shows the city you chose in your profile.")
        promise.addView(sdText(context, pt, 13.5f, K.sub, bold = false).lineHeight(1.25f), lp(0, WRAP, 1f))
        col.addView(promise, lp(MATCH, WRAP, t = 10f))
    }

    private fun styleFilter(t: TextView, icon: String?) {
        t.gravity = Gravity.CENTER_VERTICAL
        t.setPadding(dpi(13f), 0, dpi(if (icon != null) 16f else 15f), 0)
        t.elevation = Ui.dp(4f)
    }
    private fun paintFilters() {
        for ((t, on) in listOf(nearBtn to (filter == "near"), onlineBtn to (filter == "online"))) {
            t.background = rounded(if (on) K.pill else Color.argb(240, 255, 255, 255), 21f)
            t.setTextColor(if (on) Color.WHITE else K.wine)
            t.compoundDrawablePadding = dpi(7f)
            if (t === nearBtn) t.setCompoundDrawablesWithIntrinsicBounds(Icon(Lucide.MapPin, if (on) Color.WHITE else K.rose, 17f), null, null, null)
            else t.setCompoundDrawablesWithIntrinsicBounds(DotDrawable(9f, if (on) 2f else 0f), null, null, null)
            (t as View).contentDescription = (if (t === nearBtn) "Near You" else "Online") + if (on) ", on" else ""
        }
    }

    /** a still green dot with an optional white ring (the filter's Dot) */
    private class DotDrawable(sizeDp: Float, private val ringDp: Float) : android.graphics.drawable.Drawable() {
        private val s = Ui.dpi(sizeDp + 2 * ringDp)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun getIntrinsicWidth() = s
        override fun getIntrinsicHeight() = s
        override fun draw(c: Canvas) {
            val b = bounds; val r = b.width() / 2f
            if (ringDp > 0) { p.color = Color.argb(230, 255, 255, 255); c.drawCircle(b.exactCenterX(), b.exactCenterY(), r, p) }
            p.color = K.green; c.drawCircle(b.exactCenterX(), b.exactCenterY(), r - Ui.dp(ringDp), p)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun placeEmpty() {
        // left 12%, right 12%, top 42%
        val w = mapInner.width; val hh = mapInner.height
        val lpE = empty.layoutParams as LayoutParams
        val side = (w * 0.12f).toInt(); val top = (hh * 0.42f).toInt()
        if (lpE.leftMargin != side || lpE.topMargin != top) { lpE.leftMargin = side; lpE.rightMargin = side; lpE.topMargin = top; empty.layoutParams = lpE }
    }

    // ═════════════ who shows on the map ═════════════
    private val mapPeople get() = h.onlineNow ?: emptyList()
    private val shownOnMap: List<JSONObject> get() {
        val all = mapPeople
        if (filter == "online") return all.filter { SdRules.liveOf(it) }
        val me = h.meNow
        if (filter == "near" && me != null) {
            val myGeo = SdRules.geoOf(SdRules.s(me, "city"), SdRules.s(me, "state"))
            val myXY = mapXY(myGeo[1], myGeo[0])
            val myState = SdRules.s(me, "state").lowercase()
            return all.filter { p ->
                (myState.isNotEmpty() && SdRules.s(p, "state").lowercase() == myState) || run {
                    val g = SdRules.geoOf(SdRules.s(p, "city"), SdRules.s(p, "state")); val xy = mapXY(g[1], g[0])
                    hypot(xy[0] - myXY[0], xy[1] - myXY[1]) < 66
                }
            }
        }
        return all
    }

    private var stripIds: List<String> = emptyList()
    fun render() {
        val shown = shownOnMap
        globe.me = h.meNow; globe.mine = h.mineNow
        globe.setPeople(shown)
        // plan line
        planBox.removeAllViews()
        h.planPill()?.let { planBox.addView(it, LayoutParams(-2, dpi(30f), Gravity.CENTER_VERTICAL)) }
        planBox.visibility = if (planBox.childCount > 0) VISIBLE else GONE
        top.update()
        noticeBox.text = h.noticeNow; noticeBox.visibility = if (h.noticeNow.isNotEmpty()) VISIBLE else GONE
        paintFilters()
        val loaded = h.onlineNow != null
        spin.visibility = if (loaded) GONE else VISIBLE
        empty.visibility = if (loaded && shown.isEmpty()) VISIBLE else GONE
        empty.text = when (filter) { "near" -> "No verified members near you yet."; "online" -> "No one's online right now — the map updates live."; else -> "No verified members on the map yet." }
        // N People · M Online
        val all = mapPeople; val onN = all.count { SdRules.liveOf(it) }
        peopleText.text = "${all.size} ${if (all.size == 1) "Person" else "People"} · $onN Online"
        peopleBtn.contentDescription = "${all.size} verified people across India, $onN online now. Show everyone"
        peopleDot.removeAllViews()
        if (onN > 0) peopleDot.addView(LiveDot(context, 13f, 2.5f, Color.argb(217, 255, 255, 255)), LayoutParams(-2, -2, Gravity.CENTER))
        else peopleDot.addView(View(context).apply { background = oval(Ui.c("#E9E4F2")) }, LayoutParams(dpi(13f), dpi(13f), Gravity.CENTER))
        // the strip
        strip.visibility = if (all.isNotEmpty()) VISIBLE else GONE
        val first = shown.take(40)
        val ids = first.map { it.optString("id") + SdRules.liveOf(it) }
        if (ids != stripIds) {
            stripIds = ids
            stripRow.removeAllViews()
            for ((i, p) in first.withIndex()) stripRow.addView(personCard(p), lp(cardW(), WRAP, l = if (i == 0) 0f else 12f))
        }
        restartStrip()
    }

    /** test shots only: the full-screen map, everyone, a filter, the end of the page */
    fun preview(what: String) {
        when (what) {
            "stories" -> storiesV?.let { v -> scroll.post { scroll.scrollTo(0, maxOf(0, (col.parent as View).top + col.top + v.top - dpi(140f))) } }
            "full" -> setFull(true)
            "all" -> allSheet(h.onlineNow ?: emptyList(), null)
            "near", "online" -> { filter = what; render() }
            "scroll" -> scroll.scrollTo(0, scroll.getChildAt(0).height)
            // closer in: state names and big cities, then towns, first names, highways and roads
            "zoom-north" -> globe.showAt(78.0, 27.6, 7.0)
            "zoom-haryana" -> globe.showAt(76.7, 29.1, 16.0)
            "zoom-delhi" -> globe.showAt(77.15, 28.62, 40.0)
            "zoom-south" -> globe.showAt(78.0, 14.5, 6.0)
        }
    }

    fun setActive(on: Boolean) { globe.active = on; if (!on) { setFull(false); stopStrip() } else restartStrip() }

    /** full screen: the map leaves the page and covers the whole phone (the bottom bar too) */
    fun setFull(on: Boolean) {
        if (on == fullOn) return
        fullOn = on
        globe.full = on
        (mapInner.parent as? ViewGroup)?.removeView(mapInner)
        if (on) {
            val f = FrameLayout(context).apply { setBackgroundColor(Ui.c("#06020f")); isClickable = true; elevation = Ui.dp(60f) }
            f.addView(mapInner, LayoutParams(-1, -1))
            h.hostRoot.addView(f, LayoutParams(-1, -1))
            fullHost = f
        } else {
            fullHost?.let { (it.parent as? ViewGroup)?.removeView(it) }
            fullHost = null
            mapBox.addView(mapInner, LayoutParams(-1, -1))
        }
        mapBox.invalidateOutline(); mapBox.invalidate()
    }
    val isFull get() = fullOn

    // ═════════════ person card (photo, "Tanvi J.", city, Online / last seen) ═════════════
    private fun cardW(): Int {
        val vw = min(resources.displayMetrics.widthPixels / Ui.density, 480f)
        return dpi(((vw - 68f) / 4f).coerceIn(82f, 112f))
    }
    private fun personCard(p: JSONObject): View {
        val live = SdRules.liveOf(p)
        val card = vbox(context).apply { background = rounded(Color.WHITE, 18f, 1f, K.line); setPadding(dpi(3f), dpi(8f), dpi(3f), dpi(3f)); elevation = Ui.dp(3f) }
        // a round photo with a soft pink ring (owner, 20 Nov: the photos are circles)
        val w = cardW() - dpi(6f)
        val d = (w - dpi(14f)).coerceAtLeast(dpi(40f))
        val photo = FrameLayout(context)
        val ringV = FrameLayout(context).apply { background = oval(Ui.c("#FFE1EF"), 2f, Ui.c("#F7A8CB")); setPadding(dpi(3f), dpi(3f), dpi(3f), dpi(3f)) }
        val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; outlineProvider = SD_CIRCLE_OUTLINE; setBackgroundColor(Ui.c("#F6D6E6")) }
        val raws = listOf(SdRules.s(p, "photo_thumb"), SdRules.s(p, "photo", "profile_picture")).filter { it.isNotBlank() }
        val demo = Demo.cover(p, d, d)
        if (demo != null) img.setImageBitmap(demo)
        else Photos.cachedCover(raws, d, d)?.let { img.setImageBitmap(it) } ?: Photos.loadCover(raws, d, d) { b -> if (b != null) img.setImageBitmap(b) }
        ringV.addView(img, LayoutParams(-1, -1))
        photo.addView(ringV, LayoutParams(d, d, Gravity.CENTER_HORIZONTAL))
        card.addView(photo, lp(MATCH, d))
        val dot = View(context).apply { background = oval(if (live) Ui.c("#34C759") else Ui.c("#A79FB8"), 2.5f, Color.WHITE); elevation = Ui.dp(3f); outlineProvider = null }
        val texts = vbox(context, 1f, Gravity.CENTER_HORIZONTAL).apply { setPadding(dpi(3f), dpi(7f), dpi(3f), dpi(6f)) }
        texts.addView(sdText(context, shortName(SdRules.s(p, "name", "full_name")), 14f, K.wine2).oneLine().apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
        val city = SdRules.cityOf(p)
        if (city.isNotEmpty()) texts.addView(sdText(context, city, 12f, K.sub, bold = false).oneLine().icon(Icon(Lucide.MapPin, K.rose, 11f), 3f), lp(WRAP, WRAP))
        texts.addView(sdText(context, if (live) "Online" else SdRules.seenText(p).removePrefix("Last seen "), 11.5f, if (live) Ui.c("#15803D") else K.soft).oneLine().apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
        card.addView(texts, lp(MATCH, WRAP))
        val wrap = FrameLayout(context).apply { clipChildren = false }
        wrap.addView(card, LayoutParams(-1, -2))
        // the status dot sits on the circle's lower right
        wrap.addView(dot, LayoutParams(dpi(14f), dpi(14f)).apply {
            val cx = dpi(3f) + w / 2 + (d * 0.354f).toInt(); val cy = dpi(8f) + d / 2 + (d * 0.354f).toInt()
            leftMargin = cx - dpi(7f); topMargin = cy - dpi(7f)
        })
        wrap.contentDescription = "${SdRules.s(p, "name")}, ${SdRules.s(p, "age")}, $city, ${SdRules.seenText(p)}"
        wrap.press().onTap { h.openProfile(p) }
        return wrap
    }
    private fun roundOutline(r: Float) = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, Ui.dp(r)) } }

    // glides right-to-left on its own; any touch pauses it, it resumes a few seconds later
    private val main = Handler(Looper.getMainLooper())
    private var stripPaused = false
    private val resume = Runnable { stripPaused = false }
    private val glide = object : Runnable {
        override fun run() {
            main.postDelayed(this, 2800)
            if (stripPaused || !strip.isShown || stripRow.width <= strip.width + dpi(4f)) return
            val step = cardW() + dpi(12f)
            if (strip.scrollX + strip.width >= stripRow.width - dpi(6f)) strip.smoothScrollTo(0, 0) else strip.smoothScrollBy(step, 0)
        }
    }
    private fun restartStrip() { main.removeCallbacks(glide); if (!GlobeMap.REDUCED) main.postDelayed(glide, 2800) }
    private fun stopStrip() { main.removeCallbacks(glide) }
    private fun pauseStrip() { stripPaused = true; main.removeCallbacks(resume); main.postDelayed(resume, 4500) }

    // ═════════════ everyone on the map (or one city's group): online first ═════════════
    fun allSheet(people: List<JSONObject>, title: String?) {
        val list = people.filter { SdRules.liveOf(it) } + people.filter { !SdRules.liveOf(it) }
        val liveN = list.count { SdRules.liveOf(it) }
        val panel = vbox(context).apply { background = K.sheet(); setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(22f)) }
        panel.addView(View(context).apply { background = rounded(Ui.c("#F2C3D8"), 3f) }, lp(dpi(44f), dpi(5f), b = 12f, gravity = Gravity.CENTER_HORIZONTAL))
        val head = hbox(context, 10f)
        if (liveN > 0) head.addView(LiveDot(context, 10f)) else head.addView(View(context).apply { background = oval(Ui.c("#A79FB8")) }, lp(dpi(10f), dpi(10f)))
        val t = vbox(context)
        t.addView(sdText(context, title ?: "${list.size} verified across India", 22f, K.wine, italic = true))
        t.addView(sdText(context, "$liveN online now · ${list.size - liveN} offline", 13.5f, K.sub, bold = false), lp(WRAP, WRAP, t = 2f))
        head.addView(t, lp(0, WRAP, 1f))
        var close: () -> Unit = {}
        head.addView(ImageView(context).apply { setImageDrawable(Icon(Lucide.X, K.wine, 24f)); scaleType = ImageView.ScaleType.CENTER; contentDescription = "Close" }.onTap { close() }, lp(dpi(44f), dpi(44f)))
        panel.addView(head, lp(MATCH, WRAP, b = 14f))
        if (list.isEmpty()) panel.addView(sdText(context, "No verified members here yet.", 15f, K.sub, bold = false).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP, t = 10f, b = 10f))
        val cols = 3
        val gap = dpi(10f)
        val tileW = (resources.displayMetrics.widthPixels - dpi(32f) - gap * (cols - 1)) / cols
        val tileH = (tileW / 0.78f).toInt()
        var row: LinearLayout? = null
        for ((i, p) in list.withIndex()) {
            if (i % cols == 0) { row = hbox(context, 10f, Gravity.TOP); panel.addView(row, lp(MATCH, WRAP, t = if (i == 0) 0f else 10f)) }
            row!!.addView(tile(p, tileW, tileH) { close(); h.openProfile(p) }, lp(tileW, tileH))
        }
        close = h.layer.sheet(panel, z = 85f, maxVh = 0.86f)
    }
    private fun tile(p: JSONObject, w: Int, hh: Int, onClick: () -> Unit): View {
        val live = SdRules.liveOf(p)
        val f = FrameLayout(context).apply {
            background = rounded(Ui.c("#F6D6E6"), 16f, 3f, Color.WHITE); clipToOutline = true; outlineProvider = roundOutline(16f); elevation = Ui.dp(3f)
            setPadding(dpi(3f), dpi(3f), dpi(3f), dpi(3f))
        }
        val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val raws = listOf(SdRules.s(p, "photo", "profile_picture")).filter { it.isNotBlank() }
        val demo = Demo.cover(p, w, hh)
        if (demo != null) img.setImageBitmap(demo) else Photos.cachedCover(raws, w, hh)?.let { img.setImageBitmap(it) } ?: Photos.loadCover(raws, w, hh) { b -> if (b != null) img.setImageBitmap(b) }
        f.addView(img, LayoutParams(-1, -1))
        f.addView(object : View(context) {
            val pp = Paint()
            override fun onSizeChanged(w2: Int, h2: Int, ow: Int, oh: Int) { pp.shader = LinearGradient(0f, 0f, 0f, h2.toFloat(), intArrayOf(Color.argb(0, 122, 0, 54), Color.argb(0, 122, 0, 54), Color.argb(230, 92, 0, 40)), floatArrayOf(0f, .42f, 1f), Shader.TileMode.CLAMP) }
            override fun onDraw(c: Canvas) { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pp) }
        }, LayoutParams(-1, -1))
        val tx = vbox(context).apply { setPadding(dpi(4f), 0, dpi(3f), dpi(3f)) }
        val nameRow = hbox(context, 5f)
        if (live) nameRow.addView(LiveDot(context, 7f)) else nameRow.addView(View(context).apply { background = oval(Ui.c("#CFC8DD")) }, lp(dpi(7f), dpi(7f)))
        nameRow.addView(sdText(context, SdRules.firstName(SdRules.s(p, "name", "full_name")) + SdRules.s(p, "age").let { if (it.isNotEmpty()) ", $it" else "" }, 15f, Color.WHITE).oneLine(), lp(0, WRAP, 1f))
        tx.addView(nameRow, lp(MATCH, WRAP))
        val city = SdRules.cityOf(p)
        if (city.isNotEmpty()) tx.addView(sdText(context, city, 12f, Color.argb(224, 255, 255, 255), bold = false).oneLine(), lp(MATCH, WRAP))
        tx.addView(sdText(context, SdRules.seenText(p), 11.5f, if (live) Ui.c("#86EFAC") else Color.argb(191, 255, 255, 255)).oneLine(), lp(MATCH, WRAP))
        f.addView(tx, LayoutParams(-1, -2, Gravity.BOTTOM))
        f.contentDescription = "${SdRules.s(p, "name")}, $city, ${SdRules.seenText(p)}"
        f.press().onTap { onClick() }
        return f
    }

    companion object {
        /** where the white sheet starts under the maroon top (dp below the status bar) */
        const val MAP_SHEET_TOP = 148f
        fun shortName(n: String): String {
            val w = n.ifBlank { "Member" }.trim().split(Regex("\\s+"))
            return if (w.size > 1) "${w[0]} ${w[w.size - 1][0]}." else w[0]
        }
    }
}

/** a round photo's outline (the map's people cards) */
private val SD_CIRCLE_OUTLINE = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) } }
