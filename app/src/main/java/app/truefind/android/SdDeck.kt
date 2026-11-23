package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Secret Dating Home's profile card (owner's pictures, 23 Nov): one big card at a time — the photo, Online and
 * Verified at its top, the name and city at its bottom, and two round buttons on it: ✕ (the next profile) and
 * ♥ (like). Swipe left / right or tap the arrows for the next / the one before; tap the photo to open the profile.
 * Flat ("2D"): a white edge, no gloss, no deep shadow.
 * Its size (owner, 23 Nov: the second picture's big card; of the two sizes shown he chose "B — पहली screen में पूरा"):
 * almost the screen's width, and as tall as the first screen allows — it ends just above the bottom bar, so ✕ ♥ show
 * at once — never taller than the picture's card (1.545 × its width). Everything on it is in that picture's proportions
 * (the round buttons a fifth of the card's width, and so on).
 */
@SuppressLint("ViewConstructor")
class SdDeck(
    ctx: Context,
    private val onOpen: (JSONObject) -> Unit,
    /** the heart was tapped (the heart's own view: the like's little burst starts from it) */
    private val onLike: (JSONObject, View) -> Unit,
    private val isLiked: (JSONObject) -> Boolean,
) : LinearLayout(ctx) {
    private var people: List<JSONObject> = emptyList()

    private val box = object : FrameLayout(ctx) {
        override fun onInterceptTouchEvent(e: MotionEvent) = true
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean = touch(e)
    }
    private val dots = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
    private val counter = sdText(ctx, "", 13f, Rs.ICON).apply { gravity = Gravity.CENTER }
    private val prevBtn = navBtn(Lucide.ChevronLeft) { go(idx - 1) }
    private val nextBtn = navBtn(Lucide.ChevronRight) { go(idx + 1) }
    private val cards = HashMap<Int, Card>()

    private var idx = 0
    private var center = 0f        // the card position the deck shows (animated)
    private var dx = 0f
    private var dragging = false
    private var anim: ValueAnimator? = null
    // the deck spans the whole screen width (margin -16 around the page's 16 padding); one card fills it but for its side gaps
    private var cw = ctx.resources.displayMetrics.widthPixels
    private var W = cardW(ctx, cw)
    private var H = cardH(W, 0)
    /** [fit]: the height from the card's top down to the bottom bar on the first screen (SecretHome measures it) */
    var room = 0
        set(v) {
            if (field == v) return
            field = v
            val h = cardH(W, v)
            if (h != H) { H = h; for (c in cards.values) box.removeView(c); cards.clear(); layoutCards(); requestLayout() }
        }

    init {
        orientation = VERTICAL
        clipChildren = false
        addView(box, LayoutParams(MATCH, 0))
        addView(dots, lp(MATCH, dpi(8f), t = 10f))
        addView(counter, lp(MATCH, WRAP, t = 8f))
        box.addView(prevBtn); box.addView(nextBtn)
    }

    /** [restart]: another list was chosen (Discover · Nearby · Favorite) — it starts at its first card */
    fun set(list: List<JSONObject>, restart: Boolean = false) {
        val changed = restart || list.map { it.optString("id") } != people.map { it.optString("id") }
        val frontId = if (n > 0 && !restart) people[mod(idx)].optString("id") else ""
        people = list
        if (changed) {
            // the same person stays in front when the list only grew, shrank or changed its order around them
            val at = list.indexOfFirst { it.optString("id") == frontId }
            idx = if (restart) 0 else if (at >= 0) at else mod(idx)
            center = idx.toFloat(); dx = 0f; dragging = false; anim?.cancel()
            for (c in cards.values) box.removeView(c); cards.clear()
        }
        layoutCards()
    }
    /** the next profile (✕, and after a like) */
    fun next() { if (n > 1) go(idx + 1) }
    /** a like changed: the hearts are painted again */
    fun refresh() { for (c in cards.values) c.paintHeart() }
    /** test shots: the card in front */
    val front: JSONObject? get() = if (n == 0) null else people[mod(idx)]
    private val n get() = people.size
    private fun mod(k: Int) = if (n == 0) 0 else ((k % n) + n) % n

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        (box.layoutParams as LayoutParams).height = H + dpi(4f)
        super.onMeasure(wSpec, hSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && w != cw) {
            cw = w
            W = cardW(context, cw); H = cardH(W, room)
            post { for (c in cards.values) box.removeView(c); cards.clear(); layoutCards(); requestLayout() }
        }
        if (Demo.on) android.util.Log.i("TFDBG", "deck size w=$w h=$h cw=$cw W=$W H=$H cards=${cards.size} n=$n")
    }

    // ── the cards: the one in front, and its two neighbours waiting just outside the screen ──
    private fun layoutCards() {
        if (W == 0 || n == 0) { for (c in cards.values) box.removeView(c); cards.clear(); refreshNav(); return }
        val want = HashSet<Int>()
        for (off in if (n >= 2) intArrayOf(-1, 0, 1) else intArrayOf(0)) {
            val k = idx + off
            want.add(k)
            val card = cards.getOrPut(k) { Card(context).also { c -> box.addView(c, 0, FrameLayout.LayoutParams(W, H)) } }
            card.bind(people[mod(k)])
        }
        for (k in cards.keys.toList()) if (k !in want) { box.removeView(cards[k]); cards.remove(k) }
        place()
        refreshNav()
    }

    private fun place() {
        val c0 = if (dragging) idx - dx / cw else center
        for ((k, card) in cards) {
            val pos = k - c0
            card.x = (cw - W) / 2f + pos * cw
            card.y = Ui.dp(2f)
            card.visibility = if (abs(pos) >= 1f) INVISIBLE else VISIBLE
        }
        prevBtn.translationZ = 30f; nextBtn.translationZ = 30f
    }

    private fun refreshNav() {
        val many = n > 1
        val left = (cw - W) / 2f
        // (the picture's arrows sit a little above the card's middle: 43 % down)
        val top = Ui.dp(2f) + H * 0.428f - Ui.dp(20f)
        prevBtn.visibility = if (many) VISIBLE else GONE; nextBtn.visibility = if (many) VISIBLE else GONE
        prevBtn.x = left + Ui.dp(8f); prevBtn.y = top
        nextBtn.x = cw - left - Ui.dp(8f) - Ui.dp(40f); nextBtn.y = top
        dots.removeAllViews()
        dots.visibility = if (many && n <= 8) VISIBLE else GONE
        counter.visibility = if (many && n > 8) VISIBLE else GONE
        val cur = mod(idx)
        if (many && n <= 8) for (i in 0 until n) {
            dots.addView(View(context).apply { background = oval(if (i == cur) Rs.M else Rs.c("#E9CBCB")) }, lp(dpi(8f), dpi(8f), l = if (i == 0) 0f else 7f))
        }
        counter.text = "${cur + 1} / $n"
    }

    // ── moving ──
    private val ease = PathInterpolator(0.22f, 0.8f, 0.24f, 1f)
    private fun go(k: Int) { moveTo(k) }
    private fun moveTo(k: Int) {
        if (n == 0) return
        val from = if (dragging) idx - dx / cw else center
        idx = k; dx = 0f; dragging = false
        layoutCards()
        anim?.cancel()
        center = from
        if (PageFx.off(context)) { center = idx.toFloat(); place(); return }
        anim = ValueAnimator.ofFloat(from, k.toFloat()).apply {
            duration = 380; interpolator = ease
            addUpdateListener { center = it.animatedValue as Float; place() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { center = idx.toFloat(); place() }
            })
            start()
        }
        if (abs(idx) >= n * 50) post { val m = mod(idx); if (anim?.isRunning != true) { idx = m; center = m.toFloat(); for (c in cards.values) box.removeView(c); cards.clear(); layoutCards() } }
    }

    private var downX = 0f; private var downY = 0f; private var axis = 0 // 0 = none, 1 = x, 2 = y
    private var lastX = 0f; private var lastT = 0L; private var v = 0f; private var downOn = false; private var downBtn: View? = null

    private fun touch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; axis = 0; v = 0f; lastX = e.x; lastT = System.nanoTime()
                downBtn = null
                if (hit(prevBtn, e)) { downOn = false; navDown = prevBtn; return true }
                if (hit(nextBtn, e)) { downOn = false; navDown = nextBtn; return true }
                navDown = null
                val card = cards[idx]
                downOn = card != null && card.visibility == VISIBLE && e.x >= card.x && e.x <= card.x + card.width && e.y >= card.y && e.y <= card.y + card.height
                if (downOn) downBtn = card!!.buttonAt(e.x - card.x, e.y - card.y)?.also { it.animate().scaleX(.92f).scaleY(.92f).setDuration(110).start() }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val mx = e.x - downX; val my = e.y - downY
                if (axis == 0 && (abs(mx) > Ui.dp(8f) || abs(my) > Ui.dp(8f))) {
                    axis = if (abs(mx) > abs(my)) 1 else 2
                    release()
                    if (axis == 2) { parent?.requestDisallowInterceptTouchEvent(false); return false } // a page scroll
                    anim?.cancel(); center = idx.toFloat(); dragging = true
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
                val btn = downBtn
                release()
                navDown?.let { b -> if (!cancel && hit(b, e)) b.performClick(); navDown = null; return true }
                if (axis == 0) {
                    dx = 0f
                    if (cancel || !downOn || n == 0) return true
                    val card = cards[idx] ?: return true
                    val p = people[mod(idx)]
                    when {
                        btn != null && btn === card.skipBtn -> next()
                        btn != null && btn === card.likeBtn -> onLike(p, card.likeIcon)
                        else -> onOpen(p)
                    }
                    return true
                }
                if (axis == 1) {
                    val x = dx
                    val vDp = v / Ui.density // px per ms → dp per ms
                    val flick = abs(vDp) > 0.45f && abs(x) > Ui.dp(18f) && (System.nanoTime() - lastT) / 1e6f < 90f
                    val far = cw * 0.22f
                    val delta = if (n > 1 && (x < -far || (flick && v < 0))) 1 else if (n > 1 && (x > far || (flick && v > 0))) -1 else 0
                    moveTo(idx + delta)
                }
            }
        }
        return true
    }
    private fun release() { downBtn?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start(); downBtn = null }
    private var navDown: View? = null
    private fun hit(v: View, e: MotionEvent) = v.visibility == VISIBLE && e.x >= v.x && e.x <= v.x + v.width && e.y >= v.y && e.y <= v.y + v.height

    override fun onDetachedFromWindow() { anim?.cancel(); center = idx.toFloat(); super.onDetachedFromWindow() }

    private fun navBtn(icon: String, onClick: () -> Unit): ImageView = ImageView(context).apply {
        setImageDrawable(Icon(icon, Rs.INK, 22f, 2.4f))
        scaleType = ImageView.ScaleType.CENTER
        background = oval(Color.WHITE, 1f, Rs.LINE)
        layoutParams = FrameLayout.LayoutParams(dpi(40f), dpi(40f))
        setOnClickListener { onClick() }
        contentDescription = if (icon == Lucide.ChevronLeft) "Previous profile" else "Next profile"
    }

    companion object {
        /** the second picture's card: 480 wide on a 512 screen (13 at each side on a phone), 743 tall → 1.545 × its width */
        const val RATIO = 1.545f
        /**
         * true (the owner's choice, 23 Nov): the card is as tall as the first screen allows — it ends just above the bottom
         * bar, so ✕ ♥ show without a scroll — and never taller than the picture's.
         * false: exactly the picture's size on every phone; on most its foot (✕ ♥) comes up only with a small scroll.
         */
        var fit = true
        fun cardW(ctx: Context, deckW: Int = ctx.resources.displayMetrics.widthPixels): Int = (deckW - Ui.dp(26f)).coerceIn(Ui.dp(220f), Ui.dp(460f)).roundToInt()
        /** [room]: the height free for the card on the first screen, see [fit] (0 = not measured yet) */
        fun cardH(w: Int, room: Int): Int {
            val full = (w * RATIO).roundToInt()
            // (little room — a short phone, or the "Stories are Premium Only" box above it: never squarer than the card was before, 1.05 × its width)
            return if (fit && room > 0) room.coerceIn((w * 1.05f).roundToInt(), full) else full
        }
    }

    /** One card: photo, Online / Verified, name, city, ✕ and ♥. */
    inner class Card(ctx: Context) : FrameLayout(ctx) {
        var id = ""
        private var person: JSONObject? = null
        val photo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val inner = FrameLayout(ctx)
        private val shade = object : View(ctx) {
            val p = Paint()
            override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
                p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                    intArrayOf(Color.argb(0, 40, 6, 14), Color.argb(0, 40, 6, 14), Color.argb(120, 40, 6, 14), Color.argb(215, 34, 4, 12)),
                    floatArrayOf(0f, .46f, .68f, 1f), Shader.TileMode.CLAMP)
            }
            override fun onDraw(c: Canvas) { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
        }
        private val info = vbox(ctx, 5f)
        private val nameRow = hbox(ctx, 8f)
        // the picture's measures, as parts of the card's width (dp)
        private val wDp = W / Ui.density
        private val btnDp = (wDp * .2f).coerceIn(58f, 80f)          // ✕ and ♥: a fifth of the card's width
        private val footDp = wDp * .075f                            // under the buttons
        private val name = Rs.title(ctx, "", (wDp * .082f).coerceIn(24f, 33f), Color.WHITE).oneLine().shadow(6f, 1f, Color.argb(110, 30, 0, 8))
        private val seal = ImageView(ctx).apply { setImageDrawable(Seal(23f, Color.WHITE, Rs.M)) }
        private val city = sdText(ctx, "", (wDp * .044f).coerceIn(15f, 17.5f), Color.WHITE, bold = false).oneLine().shadow(6f, 1f, Color.argb(120, 30, 0, 8))
        private val verified = sdText(ctx, "Verified", 13.5f, Rs.ICON).icon(Seal(18f, Rs.M, Color.WHITE), 5f)
        private val online = sdText(ctx, "Online", 13.5f, Rs.INK).icon(oval(K.green).apply { setSize(dpi(9f), dpi(9f)) }, 6f)
        val skipBtn = ImageView(ctx).apply {
            setImageDrawable(Icon(Lucide.X, Rs.M, btnDp * .75f, 1.3f)); scaleType = ImageView.ScaleType.CENTER   // the ✕ itself: 3/8 of the button, as in the picture
            background = oval(Color.WHITE); contentDescription = "Next profile"
        }
        val likeIcon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER }
        val likeBtn = FrameLayout(ctx).apply { addView(likeIcon, LayoutParams(-1, -1)) }
        private val buttons = hbox(ctx, 0f, Gravity.CENTER)
        private var boundId = ""
        private var heartKey = -1

        init {
            // the white edge around the photo
            background = rounded(Color.WHITE, 26f, 1f, Rs.LINE)
            inner.background = rounded(Rs.c("#F6D6E6"), 23f)
            inner.outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, Ui.dp(23f)) } }
            inner.clipToOutline = true
            addView(inner, LayoutParams(-1, -1).apply { setMargins(dpi(3.5f), dpi(3.5f), dpi(3.5f), dpi(3.5f)) })
            inner.addView(photo, LayoutParams(-1, -1))
            inner.addView(shade, LayoutParams(-1, -1))
            nameRow.addView(name, lp(0, WRAP, 1f).apply { weight = 0f; width = WRAP })
            nameRow.addView(seal, lp(dpi(23f), dpi(23f)))
            info.addView(nameRow, lp(WRAP, WRAP))
            city.icon(Icon(Lucide.MapPin, Color.WHITE, 16f, 2.2f), 5f)
            info.addView(city, lp(WRAP, WRAP))
            // the name starts a little in from the card's side, right over the two buttons (as in the picture)
            inner.addView(info, LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dpi(wDp * .135f), 0, dpi(14f), dpi(footDp + btnDp + wDp * .05f)) })
            online.background = rounded(Color.WHITE, 15f); online.setPadding(dpi(10f), 0, dpi(12f), 0); online.gravity = Gravity.CENTER_VERTICAL
            inner.addView(online, LayoutParams(-2, dpi(30f), Gravity.TOP or Gravity.START).apply { setMargins(dpi(13f), dpi(13f), 0, 0) })
            verified.background = rounded(Color.WHITE, 15f); verified.setPadding(dpi(7f), 0, dpi(12f), 0); verified.gravity = Gravity.CENTER_VERTICAL
            inner.addView(verified, LayoutParams(-2, dpi(30f), Gravity.TOP or Gravity.END).apply { setMargins(0, dpi(13f), dpi(13f), 0) })
            buttons.addView(skipBtn, lp(dpi(btnDp), dpi(btnDp)))
            buttons.addView(likeBtn, lp(dpi(btnDp), dpi(btnDp), l = wDp * .12f))
            inner.addView(buttons, LayoutParams(-1, dpi(btnDp), Gravity.BOTTOM).apply { bottomMargin = dpi(footDp) })
        }

        fun bind(p: JSONObject) {
            person = p
            val pid = p.optString("id")
            id = pid
            paintHeart()
            if (pid == boundId) return
            boundId = pid
            val age = SdRules.s(p, "age")
            name.text = SdRules.firstName(SdRules.s(p, "name", "full_name")) + if (age.isNotEmpty()) ", $age" else ""
            val c = SdRules.cityOf(p)
            val st = SdRules.s(p, "state")
            val where = c + if (st.isNotEmpty() && SdRules.s(p, "city").isNotEmpty() && st != SdRules.s(p, "city")) ", $st" else ""
            // Nearby: how far they are, after the city
            val km = p.optDouble("_km", Double.NaN)
            city.text = if (km.isNaN()) where else listOf(where, if (km < 5) "Your city" else "${Math.round(km)} km").filter { it.isNotEmpty() }.joinToString(" · ")
            city.visibility = if (city.text.isEmpty()) GONE else VISIBLE
            seal.visibility = if (p.opt("verified") != false) VISIBLE else GONE
            verified.visibility = seal.visibility
            online.visibility = if (SdRules.liveOf(p)) VISIBLE else GONE
            skipBtn.visibility = if (n > 1) VISIBLE else GONE
            photo.setImageBitmap(null)
            val raws = listOf(SdRules.s(p, "photo", "profile_picture"))
            val bw = W - dpi(7f); val bh = H - dpi(7f)
            val demo = Demo.cover(p, bw, bh)
            if (demo != null) photo.setImageBitmap(demo)
            else if (W > 0) {
                Photos.cachedCover(raws, bw, bh)?.let { photo.setImageBitmap(it) }
                    ?: Photos.loadCover(raws, bw, bh) { b -> if (boundId == pid && b != null) photo.setImageBitmap(b) }
            }
            contentDescription = "${name.text}${if (where.isNotEmpty()) ", $where" else ""}. Tap to open the profile"
        }

        /** ♥: maroon with a white heart; once liked, white with a maroon heart */
        fun paintHeart() {
            val p = person ?: return
            val on = isLiked(p)
            val key = if (on) 1 else 0
            if (key == heartKey) return
            heartKey = key
            likeBtn.background = if (on) oval(Color.WHITE, 2f, Rs.M) else oval(Rs.M, 2f, Color.WHITE)
            likeIcon.setImageDrawable(if (on) Icon(Lucide.Heart, Rs.M, btnDp * .56f, 2f, Rs.M) else Icon(Lucide.Heart, Color.WHITE, btnDp * .56f, 2f, Color.WHITE))
            likeBtn.contentDescription = if (on) "Liked. Tap to take back" else "Like"
        }

        /** which round button is under this point of the card (null: the photo) */
        fun buttonAt(x: Float, y: Float): View? {
            val bx = x - inner.left - buttons.left; val by = y - inner.top - buttons.top
            for (b in listOf<View>(skipBtn, likeBtn)) {
                if (b.visibility != VISIBLE) continue
                val pad = Ui.dp(6f)
                if (bx >= b.left - pad && bx <= b.right + pad && by >= b.top - pad && by <= b.bottom + pad) return b
            }
            return null
        }
    }
}
