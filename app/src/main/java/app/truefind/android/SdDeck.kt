package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Secret Dating Home cards (Deck): big card in the middle, the next ones
 * peek out a little behind it, turned slightly in 3D. Swipe left / right,
 * tap the middle card to open the profile, tap a side card to bring it to
 * the middle. The first time (once per app visit) the cards move by
 * themselves right-to-left, then back.
 */
@SuppressLint("ViewConstructor")
class SdDeck(
    ctx: Context,
    private val onOpen: (JSONObject) -> Unit,
    private val onChat: (JSONObject) -> Unit,
) : LinearLayout(ctx) {
    private var people: List<JSONObject> = emptyList()
    private var premium = false
    private var sent: Map<String, String> = emptyMap()

    private val box = object : FrameLayout(ctx) {
        override fun onInterceptTouchEvent(e: MotionEvent) = true
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean = touch(e)
    }
    private val dots = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
    private val counter = sdText(ctx, "", 13f, K.wine).apply { gravity = Gravity.CENTER }
    private val prevBtn = navBtn(Lucide.ChevronLeft) { go(idx - 1) }
    private val nextBtn = navBtn(Lucide.ChevronRight) { go(idx + 1) }
    private val cards = HashMap<Int, Card>()

    private var idx = 0
    private var center = 0f        // the card position the deck shows (animated)
    private var dx = 0f
    private var dragging = false
    private var anim: ValueAnimator? = null
    private val S get() = Ui.dp(100f)
    private val D get() = Ui.dp(260f)
    // the deck always spans the whole screen width (margin -16 around a 16 padding)
    private var cw = ctx.resources.displayMetrics.widthPixels
    private var W = max(Ui.dp(220f), min(cw - Ui.dp(74f), Ui.dp(360f))).roundToInt()
    private var H = (W * 1.48f).roundToInt()

    init {
        orientation = VERTICAL
        clipChildren = false
        box.clipChildren = false
        addView(box, LayoutParams(MATCH, 0))
        addView(dots, lp(MATCH, dpi(12f), t = -8f))
        addView(counter, lp(MATCH, WRAP, t = -6f))
        box.addView(prevBtn); box.addView(nextBtn)
    }

    fun set(list: List<JSONObject>, premium: Boolean, sent: Map<String, String>) {
        val changed = list.map { it.optString("id") } != people.map { it.optString("id") }
        people = list; this.premium = premium; this.sent = sent
        if (abs(idx) >= max(1, list.size) * 50) { idx = mod(idx); center = idx.toFloat() }
        if (changed) { for (c in cards.values) box.removeView(c); cards.clear() }
        layoutCards(); startAuto()
    }
    private val n get() = people.size
    private fun mod(k: Int) = if (n == 0) 0 else ((k % n) + n) % n

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        (box.layoutParams as LayoutParams).height = H + dpi(30f)
        super.onMeasure(wSpec, hSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && w != cw) {
            cw = w
            W = max(Ui.dp(220f), min(cw - Ui.dp(74f), Ui.dp(360f))).roundToInt(); H = (W * 1.48f).roundToInt()
            post { for (c in cards.values) box.removeView(c); cards.clear(); layoutCards(); requestLayout() }
        }
        if (Demo.on) android.util.Log.i("TFDBG", "deck size w=$w h=$h cw=$cw W=$W H=$H cards=${cards.size} n=$n")
    }

    // ── the cards ──
    private fun offs(): IntArray = when { n >= 3 -> intArrayOf(-2, -1, 0, 1, 2); n == 2 -> intArrayOf(-1, 0, 1); n == 1 -> intArrayOf(0); else -> intArrayOf() }

    private fun layoutCards() {
        if (W == 0 || n == 0) { for (c in cards.values) box.removeView(c); cards.clear(); refreshNav(); return }
        val want = HashSet<Int>()
        for (off in offs()) {
            val k = idx + off
            if (n == 2 && ((off == -1 && dx <= 0) || (off == 1 && dx > 0))) continue
            want.add(k)
            val card = cards.getOrPut(k) { Card(context).also { c -> box.addView(c, 0, FrameLayout.LayoutParams(W, H)); c.bind(people[mod(k)]) } }
            card.bind(people[mod(k)])
        }
        for (k in cards.keys.toList()) if (k !in want) { box.removeView(cards[k]); cards.remove(k) }
        place()
        refreshNav()
    }

    private fun place() {
        val c0 = if (dragging) idx - dx / D else center
        for ((k, card) in cards) {
            val pos = k - c0
            val a = abs(pos)
            val c = min(a, 1f)
            val op = if (a <= 1) 1f else max(0f, 1f - (a - 1) / 0.6f)
            val info = max(0f, 1f - a * 1.6f)
            card.x = (cw - W) / 2f + pos * S
            card.y = Ui.dp(8f)
            card.rotationY = (if (pos < 0) 1 else -1) * c * 14f
            val sc = 1f - 0.12f * min(a, 1.5f)
            card.scaleX = sc; card.scaleY = sc
            card.alpha = op
            card.translationZ = (100 - (a * 10).roundToInt()).toFloat()
            card.visibility = if (op < 0.05f) INVISIBLE else VISIBLE
            card.style(c, info, a < 0.5f, k == idx && a < 0.3f, sent[card.id] == "sent", premium)
        }
        prevBtn.translationZ = 300f; nextBtn.translationZ = 300f
    }

    private fun refreshNav() {
        val many = n > 1
        val sideX = max(Ui.dp(4f), (cw - W) / 2f - Ui.dp(22f))
        val top = Ui.dp(8f) + H / 2f - Ui.dp(21f)
        prevBtn.visibility = if (many) VISIBLE else GONE; nextBtn.visibility = if (many) VISIBLE else GONE
        prevBtn.x = sideX; prevBtn.y = top
        nextBtn.x = cw - sideX - Ui.dp(42f); nextBtn.y = top
        dots.removeAllViews()
        dots.visibility = if (many && n <= 8) VISIBLE else GONE
        counter.visibility = if (many && n > 8) VISIBLE else GONE
        val cur = mod(idx)
        if (many && n <= 8) for (i in 0 until n) {
            dots.addView(View(context).apply { background = rounded(if (i == cur) K.hot else Ui.c("#F2C3D8"), 4f) },
                lp(dpi(if (i == cur) 22f else 8f), dpi(8f), l = if (i == 0) 0f else 7f))
        }
        counter.text = "${cur + 1} / $n"
    }

    // ── moving ──
    private val ease = PathInterpolator(0.22f, 0.8f, 0.24f, 1f)
    private fun go(k: Int) { stopAuto(); moveTo(k) }
    private fun moveTo(k: Int) {
        if (n == 0) return
        val from = if (dragging) idx - dx / D else center
        idx = k; dx = 0f; dragging = false
        layoutCards()
        anim?.cancel()
        center = from
        anim = ValueAnimator.ofFloat(from, k.toFloat()).apply {
            duration = 550; interpolator = ease
            addUpdateListener { center = it.animatedValue as Float; place() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { center = idx.toFloat(); place(); blurSides(true) }
                override fun onAnimationStart(a: android.animation.Animator) { blurSides(false) }
            })
            start()
        }
        if (abs(idx) >= n * 50) post { val m = mod(idx); if (anim?.isRunning != true) { idx = m; center = m.toFloat(); for (c in cards.values) box.removeView(c); cards.clear(); layoutCards() } }
    }

    /** side cards get a light blur once they stand still (the web does the same; never while moving) */
    private fun blurSides(on: Boolean) {
        if (Build.VERSION.SDK_INT < 31 || Demo.noBlur) return
        for ((k, card) in cards) {
            val c = min(abs(k - center), 1f)
            card.photo.setRenderEffect(if (on && c > 0.02f) RenderEffect.createBlurEffect(Ui.dp(c * 2.5f), Ui.dp(c * 2.5f), Shader.TileMode.CLAMP) else null)
        }
    }

    private var downX = 0f; private var downY = 0f; private var axis = 0 // 0 = none, 1 = x, 2 = y
    private var lastX = 0f; private var lastT = 0L; private var v = 0f; private var downK: Int? = null; private var downCta = false

    private fun touch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopAuto()
                downX = e.x; downY = e.y; axis = 0; v = 0f; lastX = e.x; lastT = System.nanoTime()
                downCta = false
                // which card / the nav buttons
                if (hit(prevBtn, e)) { downK = null; navDown = prevBtn; return true }
                if (hit(nextBtn, e)) { downK = null; navDown = nextBtn; return true }
                navDown = null
                downK = cards.entries.filter { it.value.visibility == VISIBLE && hitCard(it.value, e) }.maxByOrNull { it.value.translationZ }?.key
                downK?.let { k -> downCta = cards[k]?.hitButton(e.x - cards[k]!!.x, e.y - cards[k]!!.y) == true }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val mx = e.x - downX; val my = e.y - downY
                if (axis == 0 && (abs(mx) > Ui.dp(8f) || abs(my) > Ui.dp(8f))) {
                    axis = if (abs(mx) > abs(my)) 1 else 2
                    if (axis == 2) { parent?.requestDisallowInterceptTouchEvent(false); return false } // a page scroll
                    anim?.cancel(); center = idx.toFloat(); dragging = true; blurSides(false)
                }
                if (axis == 1 && n > 1) {
                    val now = System.nanoTime(); val dt = max(1f, (now - lastT) / 1e6f)
                    v = v * 0.4f + ((e.x - lastX) / dt) * 0.6f
                    lastX = e.x; lastT = now
                    dx = mx; layoutCards()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancel = e.actionMasked == MotionEvent.ACTION_CANCEL
                navDown?.let { b -> if (!cancel && hit(b, e)) b.performClick(); navDown = null; return true }
                if (axis == 0) {
                    if (cancel) { dx = 0f; return true }
                    val k = downK ?: return true
                    val off = k - idx
                    if (downCta && off == 0) { onChat(people[mod(k)]); return true }
                    if (off == 0) onOpen(people[mod(idx)]) else if (n > 1) moveTo(idx + off)
                    dx = 0f; return true
                }
                if (axis == 1) {
                    val x = dx
                    val flickPx = Ui.dp(18f)
                    val vDp = v / Ui.density // px per ms → dp per ms
                    val flick = abs(vDp) > 0.45f && abs(x) > flickPx && (System.nanoTime() - lastT) / 1e6f < 90f
                    val fifty = Ui.dp(50f)
                    val delta = if (n > 1 && (x < -fifty || (flick && v < 0))) 1 else if (n > 1 && (x > fifty || (flick && v > 0))) -1 else 0
                    moveTo(idx + delta)
                }
            }
        }
        return true
    }
    private var navDown: View? = null
    private fun hit(v: View, e: MotionEvent) = v.visibility == VISIBLE && e.x >= v.x && e.x <= v.x + v.width && e.y >= v.y && e.y <= v.y + v.height
    private fun hitCard(c: Card, e: MotionEvent): Boolean {
        // the card's box after its scale (around its middle)
        val sw = c.width * c.scaleX; val sh = c.height * c.scaleY
        val l = c.x + (c.width - sw) / 2; val t = c.y + (c.height - sh) / 2
        return e.x >= l && e.x <= l + sw && e.y >= t && e.y <= t + sh
    }

    // ── first time: the cards move by themselves (right-to-left, then back) ──
    private val h = Handler(Looper.getMainLooper())
    private var autoSteps: List<Int> = emptyList()
    private var autoK = 0
    private val autoTick = object : Runnable {
        override fun run() {
            if (autoK >= autoSteps.size) { stopAuto(); return }
            moveTo(autoSteps[autoK]); autoK++
            if (autoK < autoSteps.size) h.postDelayed(this, 2600) else stopAuto()
        }
    }
    private fun startAuto() {
        if (n < 2 || autoSeen) return
        autoSeen = true
        val steps = min(n - 1, 3)
        autoSteps = (1..steps).toList() + (steps - 1 downTo 0).toList()
        autoK = 0
        h.postDelayed(autoTick, 1600)
    }
    private fun stopAuto() { h.removeCallbacks(autoTick); autoSteps = emptyList() }
    override fun onDetachedFromWindow() { stopAuto(); anim?.cancel(); super.onDetachedFromWindow() }

    private fun navBtn(icon: String, onClick: () -> Unit): ImageView = ImageView(context).apply {
        setImageDrawable(Icon(icon, Ui.c("#C2185B"), 24f, 2.8f))
        scaleType = ImageView.ScaleType.CENTER
        background = oval(Color.WHITE, 1f, Color.argb(36, 214, 40, 120))
        elevation = Ui.dp(4f)
        layoutParams = FrameLayout.LayoutParams(dpi(42f), dpi(42f))
        setOnClickListener { onClick() }
        contentDescription = if (icon == Lucide.ChevronLeft) "Previous profile" else "Next profile"
    }

    companion object { var autoSeen = false }

    /** One card: photo, name, city, Verified / Online, Send Request. */
    inner class Card(ctx: Context) : FrameLayout(ctx) {
        var id = ""
        val photo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val inner = FrameLayout(ctx)
        private val shade = object : View(ctx) {
            val p = Paint()
            override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
                p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                    intArrayOf(Color.argb(0, 122, 0, 54), Color.argb(0, 122, 0, 54), Color.argb(107, 122, 0, 54), Color.argb(235, 92, 0, 40)),
                    floatArrayOf(0f, .4f, .58f, 1f), Shader.TileMode.CLAMP)
            }
            override fun onDraw(c: Canvas) { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
        }
        private val veil = View(ctx)
        private val info = vbox(ctx, 7f, Gravity.CENTER_HORIZONTAL)
        private val nameRow = hbox(ctx, 10f, Gravity.CENTER)
        private val name = sdText(ctx, "", nameSize(), Color.WHITE).oneLine().shadow(10f, 2f, Color.argb(115, 60, 0, 25))
        private val dot = LiveDot(ctx, 12f, 2.5f, Color.argb(230, 255, 255, 255))
        private val city = sdText(ctx, "", 17f, Color.WHITE, bold = false).oneLine().shadow(8f, 1f, Color.argb(128, 60, 0, 25))
        private val verified = sdText(ctx, "Verified", 15f, Color.WHITE).icon(Seal(22f, Color.WHITE, K.hot), 5f)
        private val online = sdText(ctx, "Online", 15f, Color.WHITE).icon(oval(Ui.c("#D8FFC9")).apply { setSize(dpi(9f), dpi(9f)) }, 6f)
        private val cta = sdText(ctx, "Send Request", 22f, Color.WHITE).apply { gravity = Gravity.CENTER }
        private var boundId = ""
        private var front = true
        private var ctaKey = -1

        init {
            background = rounded(Color.WHITE, 30f)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = false
            elevation = Ui.dp(12f)
            outlineSpotShadowColorCompat(Color.argb(140, 196, 16, 98))
            inner.background = rounded(Ui.c("#F6D6E6"), 26f)
            inner.outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, Ui.dp(26f)) } }
            inner.clipToOutline = true
            addView(inner, LayoutParams(-1, -1).apply { setMargins(dpi(4f), dpi(4f), dpi(4f), dpi(4f)) })
            inner.addView(photo, LayoutParams(-1, -1))
            inner.addView(shade, LayoutParams(-1, -1))
            nameRow.addView(name, lp(WRAP, WRAP)); nameRow.addView(dot)
            info.addView(nameRow, lp(WRAP, WRAP))
            city.icon(Icon(Lucide.MapPin, Color.WHITE, 19f, 2.2f), 6f)
            info.addView(city, lp(WRAP, WRAP))
            inner.addView(info, LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dpi(12f), 0, dpi(12f), dpi(90f)) })
            inner.addView(veil, LayoutParams(-1, -1))
            verified.background = rounded(K.hot, 16f); verified.setPadding(dpi(6f), 0, dpi(13f), 0); verified.gravity = Gravity.CENTER_VERTICAL
            addView(verified, LayoutParams(-2, dpi(32f), Gravity.TOP or Gravity.START).apply { setMargins(dpi(16f), dpi(16f), 0, 0) })
            online.background = rounded(Ui.c("#16B339"), 16f); online.setPadding(dpi(11f), 0, dpi(13f), 0); online.gravity = Gravity.CENTER_VERTICAL
            addView(online, LayoutParams(-2, dpi(32f), Gravity.TOP or Gravity.END).apply { setMargins(0, dpi(16f), dpi(16f), 0) })
            cta.elevation = Ui.dp(6f)
            addView(cta, LayoutParams(-1, dpi(58f), Gravity.BOTTOM).apply { setMargins(dpi(16f), 0, dpi(16f), dpi(16f)) })
            cameraDistance = 8000 * Ui.density
        }

        private fun nameSize(): Float {
            val vw = resources.displayMetrics.widthPixels / Ui.density
            return (vw * 0.078f).coerceIn(25f, 31f)
        }

        fun bind(p: JSONObject) {
            val pid = p.optString("id")
            id = pid
            if (pid == boundId) return
            boundId = pid
            val age = SdRules.s(p, "age")
            name.text = SdRules.firstName(SdRules.s(p, "name", "full_name")) + if (age.isNotEmpty()) ", $age" else ""
            val c = SdRules.cityOf(p)
            val st = SdRules.s(p, "state")
            city.text = c + if (st.isNotEmpty() && SdRules.s(p, "city").isNotEmpty() && st != SdRules.s(p, "city")) ", $st" else ""
            city.visibility = if (c.isEmpty()) GONE else VISIBLE
            online.visibility = if (SdRules.liveOf(p)) VISIBLE else GONE
            photo.setImageBitmap(null)
            val raws = listOf(SdRules.s(p, "photo", "profile_picture"))
            val demo = Demo.face(p)
            if (demo != null) photo.setImageBitmap(demo)
            else if (W > 0) {
                val bw = W - dpi(8f); val bh = H - dpi(8f)
                Photos.cachedCover(raws, bw, bh)?.let { photo.setImageBitmap(it) }
                    ?: Photos.loadCover(raws, bw, bh) { b -> if (boundId == pid && b != null) photo.setImageBitmap(b) }
            }
        }

        /** c: 0 (middle) … 1 (side); info: how much name / buttons show */
        fun style(c: Float, infoA: Float, isFront: Boolean, ctaLive: Boolean, done: Boolean, premium: Boolean) {
            info.alpha = infoA; verified.alpha = infoA; online.alpha = infoA; cta.alpha = infoA
            veil.setBackgroundColor(Color.argb((c * 0.55f * 255).toInt(), 253, 236, 245))
            if (isFront != front) { front = isFront; elevation = Ui.dp(if (isFront) 14f else 6f) }
            val key = (if (done) 1 else 0) + (if (premium) 2 else 0)
            if (key != ctaKey) {
                ctaKey = key
                cta.text = if (done) "Request Sent" else "Send Request"
                cta.setTextColor(if (done) K.wine else Color.WHITE)
                cta.background = rounded(if (done) Color.WHITE else K.hot, 29f, 2f, Color.argb(235, 255, 255, 255))
                val ic = if (done) Lucide.Check else if (premium) Lucide.Send else Lucide.Lock
                cta.icon(Icon(ic, if (done) K.wine else Color.WHITE, if (ic == Lucide.Lock) 23f else 24f, if (done) 3f else if (premium) 2.2f else 2.3f), 10f)
                cta.contentDescription = if (done) "Request sent" else if (premium) "Send request, 1 credit" else "Send request, Premium only"
            }
            cta.tag = ctaLive
        }

        fun hitButton(x: Float, y: Float): Boolean {
            if (cta.tag != true) return false
            // the card is drawn scaled around its middle: undo it
            val lx = (x - width / 2f) / scaleX + width / 2f; val ly = (y - height / 2f) / scaleY + height / 2f
            return lx >= cta.left && lx <= cta.right && ly >= cta.top && ly <= cta.bottom
        }
    }
}

@Suppress("unused") private val keepBmp: Bitmap? = null
@Suppress("unused") private val keepTv: Class<TextView> = TextView::class.java
