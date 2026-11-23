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
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Secret Dating Home's profile card (owner's pictures, 23 Nov): one big card in front — the photo, Online and
 * Verified at its top, the name and city at its bottom, and two round buttons on it: ✕ (the next profile) and
 * ♥ (like). Swipe left / right for the next / the one before; tap the photo to open the profile.
 * The cards before and after it peek out at both sides, a little smaller and shaded, so it shows that there are more
 * (owner, 23 Nov evening: "साइड में और भी हैं … ताकि यूजर खुद ही उसको चेंज कर ले" — the arrows went; a tap on a
 * peeking card brings it to the front). Flat ("2D"): a white edge, no gloss, no deep shadow.
 * Its height (owner, 23 Nov: "इतना ही बड़ा कर दो" … "नीचे तक लंबी खींच दो" … "थोड़ा और लंबा करो", twice; that night
 * "थोड़ा सा छोटा कर दो"): 1.8 × the
 * second picture's card width, on every phone; its foot (the name, ✕ ♥) comes up with a small scroll. The round
 * buttons are a little smaller than the picture's (that evening: "थोड़ा छोटा छोटा कर दो"). (He first tried the shorter
 * size that ends above the bottom bar, [fit], and sent it back the same day.)
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
        // (no "4 / 40" and no dots under the card any more: owner, 23 Nov night)
        box.clipChildren = false
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
    /** test shots: where the front card's ✕ and ♥ are on the screen (their middles, px) */
    fun logButtons() {
        val c = cards[idx] ?: return
        val a = IntArray(2)
        for ((nm, b) in listOf("skip" to c.skipBtn, "like" to c.likeBtn)) { b.getLocationOnScreen(a); android.util.Log.i("TFDBG", "deck btn $nm ${a[0] + b.width / 2} ${a[1] + b.height / 2}") }
        c.getLocationOnScreen(a); android.util.Log.i("TFDBG", "deck card ${a[0]} ${a[1]} ${c.width} ${c.height}")
    }
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

    // ── the cards: the one in front, the two peeking out at its sides, and the next two waiting outside the screen ──
    private val step get() = W + Ui.dp(GAP)
    private fun layoutCards() {
        if (W == 0 || n == 0) { for (c in cards.values) box.removeView(c); cards.clear(); refreshNav(); return }
        val want = HashSet<Int>()
        for (off in if (n >= 2) intArrayOf(-2, -1, 0, 1, 2) else intArrayOf(0)) {
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
        val c0 = if (dragging) idx - dx / step else center
        for ((k, card) in cards) {
            val pos = k - c0
            val a = min(1f, abs(pos))
            card.x = (cw - W) / 2f + pos * step
            card.y = Ui.dp(2f)
            // the ones at the sides: a little smaller, shaded, under the one in front
            // (they shrink towards the front card's side, so as much of them peeks out as [PEEK])
            card.pivotX = if (pos > 0) 0f else W.toFloat(); card.pivotY = H / 2f
            card.scaleX = 1f - .07f * a; card.scaleY = 1f - .07f * a
            card.shadeTo(a)
            card.translationZ = (1f - a) * 4f
            card.visibility = if (abs(pos) >= 1.9f) INVISIBLE else VISIBLE
        }
    }

    private fun refreshNav() { }

    // ── moving ──
    private val ease = PathInterpolator(0.22f, 0.8f, 0.24f, 1f)
    private fun go(k: Int) { moveTo(k) }
    private fun moveTo(k: Int) {
        if (n == 0) return
        val from = if (dragging) idx - dx / step else center
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
                if (Demo.on) android.util.Log.i("TFDBG", "deck up cancel=$cancel axis=$axis dx=$dx x=${e.x} downOn=$downOn idx=$idx n=$n cw=$cw W=$W")
                val btn = downBtn
                release()
                if (axis == 0) {
                    dx = 0f
                    if (cancel || n == 0) return true
                    // a tap on a card peeking out at a side: that one comes to the front
                    if (!downOn) { if (n > 1) { if (e.x < (cw - W) / 2f) go(idx - 1) else if (e.x > (cw + W) / 2f) go(idx + 1) }; return true }
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
                    val far = step * 0.22f
                    val delta = if (n > 1 && (x < -far || (flick && v < 0))) 1 else if (n > 1 && (x > far || (flick && v > 0))) -1 else 0
                    moveTo(idx + delta)
                }
            }
        }
        return true
    }
    private fun release() { downBtn?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start(); downBtn = null }

    override fun onDetachedFromWindow() { anim?.cancel(); center = idx.toFloat(); super.onDetachedFromWindow() }

    companion object {
        private val demoCovers = object : android.util.LruCache<String, android.graphics.Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
        }
        /** a small (demo) face: round with a white ring, high in a card of [w] × [h] washed in the face's own colours */
        fun faceCard(face: android.graphics.Bitmap, w: Int, h: Int): android.graphics.Bitmap {
            val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            // the wash: the face shrunk to a few pixels and spread over the card (soft, no edges), a little lighter
            val tiny = android.graphics.Bitmap.createScaledBitmap(face, 6, 10, true)
            c.drawBitmap(tiny, null, android.graphics.Rect(0, 0, w, h), p)
            c.drawColor(Color.argb(60, 255, 255, 255))
            val d = (w * .74f).toInt(); val cx = w / 2f; val cy = h * .36f
            p.color = Color.WHITE; c.drawCircle(cx, cy, d / 2f + Ui.dp(5f), p)
            val sq = minOf(face.width, face.height)
            val src = android.graphics.Rect((face.width - sq) / 2, (face.height - sq) / 2, (face.width + sq) / 2, (face.height + sq) / 2)
            c.save()
            c.clipPath(android.graphics.Path().apply { addCircle(cx, cy, d / 2f, android.graphics.Path.Direction.CW) })
            c.drawBitmap(face, src, android.graphics.RectF(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f), p)
            c.restore()
            return out
        }
        /**
         * the second picture's card is 480 wide on a 512 screen (13 at each side on a phone) and 743 tall, 1.545 × its
         * width; the owner then asked for it longer twice (23 Nov evening: 1.8 ×, then 1.95 ×), and that night a little
         * shorter again: 1.8 × that width (the card is narrower, the neighbours peeking out beside it, but keeps this height)
         */
        // (24 Nov night, owner: "थोड़ी सी और छोटी कर दो" — 1.62 ×, a tenth shorter than 1.8)
        const val RATIO = 1.62f
        /** how far the cards before and after peek out at each side, and the gap between the cards (dp) */
        const val PEEK = 22f
        const val GAP = 10f
        /**
         * false (the owner's word, 23 Nov): the long card ([RATIO]) on every phone; its foot (✕ ♥) comes up only with a
         * small scroll.
         * true: the card is as tall as the first screen allows — it ends just above the bottom bar, so ✕ ♥ show without a
         * scroll — and never taller than [RATIO]. (In the app for an hour on 23 Nov; too short for him.)
         */
        var fit = false
        fun cardW(ctx: Context, deckW: Int = ctx.resources.displayMetrics.widthPixels): Int = (deckW - Ui.dp(2 * (PEEK + GAP))).coerceIn(Ui.dp(200f), Ui.dp(420f)).roundToInt()
        /** [w]: the card's width ([cardW]); [room]: the height free for the card on the first screen, see [fit] (0 = not measured yet) */
        fun cardH(w: Int, room: Int): Int {
            // the picture's width (13 at each side of the screen), from which the height is counted
            val base = (w + Ui.dp(2 * (PEEK + GAP)) - Ui.dp(26f)).coerceIn(Ui.dp(220f), Ui.dp(460f))
            val full = (base * RATIO).roundToInt()
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
        private val btnDp = (wDp * .165f).coerceIn(48f, 64f)        // ✕ and ♥: a little smaller than the picture's (a fifth of the card's width)
        private val footDp = wDp * .075f                            // under the buttons
        private val name = Rs.title(ctx, "", (wDp * .082f).coerceIn(24f, 33f), Color.WHITE).oneLine().shadow(6f, 1f, Color.argb(110, 30, 0, 8))
        private val seal = ImageView(ctx).apply { setImageDrawable(Seal(23f, Color.WHITE, Rs.M)) }
        private val city = sdText(ctx, "", (wDp * .044f).coerceIn(15f, 17.5f), Color.WHITE, bold = false).oneLine().shadow(6f, 1f, Color.argb(120, 30, 0, 8))
        private val verified = sdText(ctx, "Verified", 13.5f, Rs.ICON).icon(Seal(18f, Rs.M, Color.WHITE), 5f)
        /** ★ on a profile in Favorite (one I liked): owner, 23 Nov night — so it shows at a glance that these are the favourites */
        private val favStar = ImageView(ctx).apply {
            setImageDrawable(Icon(Lucide.Star, Color.WHITE, 19f, 1.6f, Ui.c("#F5B301"))); scaleType = ImageView.ScaleType.CENTER
            background = oval(Rs.M, 2f, Color.WHITE); contentDescription = "Favorite"; visibility = GONE
        }
        private val online = sdText(ctx, "Online", 13.5f, Rs.INK).icon(oval(K.green).apply { setSize(dpi(9f), dpi(9f)) }, 6f)
        val skipBtn = ImageView(ctx).apply {
            setImageDrawable(Icon(Lucide.X, Rs.M, btnDp * .75f, 1.3f)); scaleType = ImageView.ScaleType.CENTER   // the ✕ itself: 3/8 of the button, as in the picture
            background = oval(Color.WHITE); contentDescription = "Next profile"
        }
        val likeIcon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER }
        val likeBtn = FrameLayout(ctx).apply { addView(likeIcon, LayoutParams(-1, -1)) }
        private val buttons = hbox(ctx, 0f, Gravity.CENTER)
        /** the shade over a card peeking out at a side */
        private val sideShade = View(ctx).apply { background = rounded(Rs.c("#5A1428"), 26f); alpha = 0f }
        private var boundId = ""
        private var heartKey = -1

        init {
            // the white edge around the photo (flat: no shadow, also while the cards are stacked by their height)
            background = rounded(Color.WHITE, 26f, 1f, Rs.LINE)
            outlineProvider = null
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
            inner.addView(favStar, LayoutParams(dpi(36f), dpi(36f), Gravity.TOP or Gravity.END).apply { setMargins(0, dpi(52f), dpi(13f), 0) })
            buttons.addView(skipBtn, lp(dpi(btnDp), dpi(btnDp)))
            buttons.addView(likeBtn, lp(dpi(btnDp), dpi(btnDp), l = wDp * .12f))
            inner.addView(buttons, LayoutParams(-1, dpi(btnDp), Gravity.BOTTOM).apply { bottomMargin = dpi(footDp) })
            addView(sideShade, LayoutParams(-1, -1))
        }
        /** [a]: 0 in front … 1 at a side */
        fun shadeTo(a: Float) { sideShade.alpha = a * .32f; buttons.alpha = 1f - a; info.alpha = 1f - a * .6f }

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
            else if (SdDemo.isDemo(p) && W > 0) {
                // a demo face is a small picture (128 px): stretched over the whole card it was a blur of big pixels (owner's
                // screenshots, 23 Nov night) — it stands round in the card's upper half on a soft wash of its own colours
                demoCovers[pid]?.let { photo.setImageBitmap(it) } ?: Photos.loadFull(raws[0], 512) { b ->
                    if (b != null) { val c = faceCard(b, bw, bh); demoCovers.put(pid, c); if (boundId == pid) photo.setImageBitmap(c) }
                }
            }
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
            favStar.visibility = if (on) VISIBLE else GONE
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
