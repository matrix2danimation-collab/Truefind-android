package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * True Find · Daily Free Coins pop-up (components/home/DailyClaimSheet.jsx), native:
 * slides up from the bottom with the warm gold top, soft light rays, the slowly
 * turning burst, twinkles and the floating gold TF coin; CLAIM NOW claims (the coin
 * spins, confetti falls from the top, coins fly to the wallet, "Congratulations!");
 * GREAT! closes it.
 */
@SuppressLint("ViewConstructor")
class DailyClaimSheet private constructor(ctx: Context, private val home: HomeScreen, private val pages: NativePages, private val daily: HomeScreen.Daily,
                                          private val name: String, private val firstTime: Boolean, private val onClosed: () -> Unit) : FrameLayout(ctx) {

    companion object {
        fun open(home: HomeScreen, pages: NativePages, daily: HomeScreen.Daily, name: String, firstTime: Boolean, onClosed: () -> Unit) {
            val s = DailyClaimSheet(home.context, home, pages, daily, name, firstTime, onClosed)
            s.elevation = Ui.dp(400f)
            pages.rootView().addView(s, LayoutParams(-1, -1))
            s.enter()
            pages.dailySheet = s
        }
        private val COLORS = listOf("#6C2BD9", "#F5B921", "#E11D48", "#22C55E", "#3B82F6", "#FF8A3D", "#C084FC", "#FFFFFF").map { Ui.c(it) }
    }

    private var phase = "ready"     // ready · busy · done · out
    private var gotAmount = 0; private var gotDay = 0
    private var err = ""
    private val amount = daily.amount
    private val day = daily.streak?.day ?: 1

    private val dim = View(ctx).apply { setBackgroundColor(Color.argb(115, 20, 10, 40)) }
    private val sheet = FrameLayout(ctx)
    private val title: TextView
    private val cong: TextView
    private val coinWrap = FrameLayout(ctx)
    private val coin = ImageView(ctx)
    private val pill: TextView
    private val amt: TextView
    private val msg: TextView
    private val btn: TextView
    private val x = FrameLayout(ctx)
    private val fx = Confetti(ctx)
    private val dm = ctx.resources.displayMetrics
    private val sheetH: Int

    init {
        isClickable = true
        addView(dim, LayoutParams(-1, -1))
        dim.setOnClickListener { if (phase != "busy") close() }
        val hostH = (pages.rootView().height - pages.rootView().paddingTop - pages.rootView().paddingBottom).takeIf { it > 0 } ?: dm.heightPixels
        sheetH = min(Ui.dpi(560f), (hostH * 0.86f).toInt())
        sheet.background = Tf.topRound(Color.WHITE, 30f)
        sheet.clipToOutline = true
        sheet.elevation = Ui.dp(12f)
        addView(sheet, LayoutParams(min(Ui.dpi(480f), dm.widthPixels), sheetH, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        // warm top with three soft rays
        sheet.addView(Top(ctx), LayoutParams(-1, (sheetH * 0.58f).toInt()))
        sheet.addView(Burst(ctx), LayoutParams(-1, -1))
        // twinkles
        for ((xf, yf, s, d) in listOf(Quad(.26f, .21f, 20f, 0L), Quad(.70f, .18f, 20f, 800L), Quad(.20f, .36f, 14f, 1400L), Quad(.78f, .38f, 14f, 400L))) {
            val tw = Twinkle(ctx, s, d)
            sheet.addView(tw, LayoutParams(Ui.dpi(s * 1.4f), Ui.dpi(s * 1.4f)).apply { leftMargin = 0; topMargin = 0 })
            tw.post { tw.x = sheet.width * xf; tw.y = sheetH * yf }
        }
        title = Tf.t(ctx, "DAILY FREE COINS", 24f, Ui.c("#3D2A10")).apply { gravity = Gravity.CENTER; letterSpacing = 3f / 24f }
        sheet.addView(title, LayoutParams(-1, -2).apply { topMargin = Ui.dpi(40f) })
        cong = Tf.t(ctx, "🎉 Congratulations!", 29f, Ui.c("#24123F")).apply { gravity = Gravity.CENTER; alpha = 0f; scaleX = .6f; scaleY = .6f }
        sheet.addView(cong, LayoutParams(-1, -2).apply { topMargin = Ui.dpi(34f) })
        coin.setImageBitmap(Tf.pic(ctx, "home/tf_coin.png")); coin.scaleType = ImageView.ScaleType.FIT_CENTER
        coinWrap.addView(coin, LayoutParams(-1, -1))
        sheet.addView(coinWrap, LayoutParams(Ui.dpi(150f), Ui.dpi(150f), Gravity.CENTER_HORIZONTAL).apply { topMargin = (sheetH * 0.20f).toInt() })
        pill = Tf.t(ctx, "", 15f, Ui.c("#3A2E50")).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Tf.box(Ui.c("#F3EFF8"), 16f); setPadding(Ui.dpi(6f), 0, Ui.dpi(14f), 0)
            compoundDrawablePadding = Ui.dpi(7f)
            setCompoundDrawablesWithIntrinsicBounds(StarChip(), null, null, null)
        }
        sheet.addView(pill, LayoutParams(-2, Ui.dpi(32f), Gravity.CENTER_HORIZONTAL).apply { topMargin = (sheetH * 0.55f).toInt() })
        amt = Tf.t(ctx, "", 52f, Ui.c("#C99A34")).apply { gravity = Gravity.CENTER; letterSpacing = -0.5f / 52f }
        sheet.addView(amt, LayoutParams(-1, -2).apply { topMargin = (sheetH * 0.61f).toInt() })
        msg = Tf.t(ctx, "", 16f, Ui.c("#6B4A20"), false).apply { gravity = Gravity.CENTER; setLineSpacing(Ui.dp(16f) * 0.42f, 1f) }
        sheet.addView(msg, LayoutParams(-1, -2).apply { topMargin = (sheetH * 0.735f).toInt(); leftMargin = Ui.dpi(28f); rightMargin = Ui.dpi(28f) })
        btn = ClaimButton(ctx).apply { setOnClickListener { if (phase == "done") close() else claim() } }
        sheet.addView(btn, LayoutParams(Ui.dpi(228f), Ui.dpi(52f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Ui.dpi(26f) })
        x.background = Tf.circle(Color.argb(179, 255, 255, 255))
        x.addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(Lu.X, Ui.c("#6B4A20"), 16f, 2.4f)) }, LayoutParams(Ui.dpi(16f), Ui.dpi(16f), Gravity.CENTER))
        x.contentDescription = "Close"
        x.setOnClickListener { close() }
        sheet.addView(x, LayoutParams(Ui.dpi(36f), Ui.dpi(36f), Gravity.END).apply { topMargin = Ui.dpi(14f); rightMargin = Ui.dpi(14f) })
        addView(fx, LayoutParams(-1, -1))
        render()
    }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Long)

    private fun render() {
        val done = phase == "done" || (phase == "out" && gotAmount > 0)
        val gd = if (gotDay > 0) gotDay else day
        val first = Tf.firstName(name)
        msg.text = if (err.isNotEmpty()) err else if (done) (if (gd >= 7) "Streak bonus added to your wallet! A new week starts tomorrow." else "Added to your wallet. Come back tomorrow for Day ${gd + 1}!")
            else if (firstTime) "Welcome to True Find${if (first.isNotEmpty()) ", $first" else ""}! Your first free coins are ready."
            else "Welcome back${if (first.isNotEmpty()) ", $first" else ""}! Your free coins for today are ready."
        msg.setTextColor(if (err.isNotEmpty()) Ui.c("#C81E4F") else Ui.c("#6B4A20"))
        pill.text = if (done) (if (gd >= 7) "7-day streak complete ✓" else "Day $gd claimed ✓") else if (firstTime) "Day 1 of 7 · New member" else if (day >= 7) "Day 7 of 7 · Streak bonus" else "Day $day of 7"
        amt.text = if (done) "+${if (gotAmount > 0) gotAmount else amount} coins" else "$amount coins"
        btn.text = if (phase == "busy") "CLAIMING…" else if (done) "GREAT!" else "CLAIM NOW"
        btn.isEnabled = phase != "busy" && phase != "out"
        btn.alpha = if (btn.isEnabled) 1f else .7f
        x.visibility = if (!done && phase != "busy") VISIBLE else GONE
    }

    private fun enter() {
        dim.alpha = 0f; dim.animate().alpha(1f).setDuration(300).start()
        sheet.translationY = sheetH.toFloat() + Ui.dp(40f)
        sheet.animate().translationY(0f).setStartDelay(50).setDuration(500).setInterpolator(PathInterpolator(.2f, .85f, .25f, 1f)).start()
        // the coin pops in (.7 s after .35 s), then floats up and down (3 s)
        coin.scaleX = 0f; coin.scaleY = 0f; coin.rotation = -30f
        coin.animate().scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(350).setDuration(700).setInterpolator(PathInterpolator(.2f, 1.4f, .3f, 1f)).start()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { val f = it.animatedValue as Float; coinWrap.translationY = -Ui.dp(8f) * ((1f - cos(2 * PI * f).toFloat()) / 2f) }
            start()
            this@DailyClaimSheet.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) { cancel() }
            })
        }
    }

    fun back(): Boolean { if (phase != "busy") close(); return true }

    fun close() {
        if (phase == "out") return
        phase = "out"
        render()
        dim.animate().alpha(0f).setDuration(300).start()
        sheet.animate().translationY(sheetH.toFloat() + Ui.dp(40f)).setStartDelay(0).setDuration(340).setInterpolator(PathInterpolator(.42f, 0f, 1f, 1f)).start()
        postDelayed({ (parent as? FrameLayout)?.removeView(this); if (pages.dailySheet === this) pages.dailySheet = null; onClosed() }, 340)
    }

    private fun claim() {
        if (phase != "ready") return
        err = ""; phase = "busy"; render()
        home.claimDaily(true) { ok, already, a, d, e ->
            if (phase == "out") return@claimDaily
            when {
                ok -> {
                    gotAmount = if (a > 0) a else amount; gotDay = if (d > 0) d else day
                    phase = "done"; render()
                    // title out, congratulations in; the coin spins twice
                    title.animate().alpha(0f).setDuration(250).start()
                    cong.animate().alpha(1f).setDuration(250).start()
                    cong.animate().scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(PathInterpolator(.2f, 1.5f, .3f, 1f)).start()
                    coin.animate().rotationY(720f).setStartDelay(0).setDuration(900).setInterpolator(PathInterpolator(.42f, 0f, .58f, 1f)).start()
                    fx.burst(); postDelayed({ fx.burst() }, 500)
                    flyCoins()
                }
                already -> close()
                else -> { err = e.ifEmpty { "Could not claim the coins. Please try again." }; phase = "ready"; render() }
            }
        }
    }

    /** 8 coins fly from the big coin to the wallet at the top right */
    private fun flyCoins() {
        val loc = IntArray(2); coin.getLocationInWindow(loc)
        val me = IntArray(2); getLocationInWindow(me)
        val sx = loc[0] - me[0] + coin.width / 2f - Ui.dp(13f); val sy = loc[1] - me[1] + coin.height / 2f - Ui.dp(13f)
        val w = width.toFloat()
        val tx = min(w, Ui.dp(480f) + (w - Ui.dp(480f)) / 2f) - Ui.dp(70f); val ty = Ui.dp(24f)
        for (i in 0 until 8) {
            val c = FlyCoin(context)
            addView(c, LayoutParams(Ui.dpi(26f), Ui.dpi(26f)))
            c.x = sx; c.y = sy
            val midX = (tx - sx) * .5f + ((Math.random() - .5) * Ui.dp(120f)).toFloat(); val midY = (ty - sy) * .45f - Ui.dp(80f)
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900; startDelay = 120L + i * 70L; interpolator = PathInterpolator(.4f, 0f, .6f, 1f)
                addUpdateListener {
                    val f = it.animatedValue as Float
                    val (dx, dy, s, a) = if (f < .5f) { val k = f / .5f; listOf(midX * k, midY * k, 1f + .1f * k, 1f) }
                    else { val k = (f - .5f) / .5f; listOf(midX + (tx - sx - midX) * k, midY + (ty - sy - midY) * k, 1.1f - .6f * k, 1f - .8f * k) }
                    c.x = sx + dx; c.y = sy + dy; c.scaleX = s; c.scaleY = s; c.alpha = a
                }
                addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { removeView(c) } })
                start()
            }
        }
    }

    // ── pieces ──
    private class Top(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Ui.c("#FFE3B0"), Ui.c("#FFF1D6"), Ui.c("#FFFFFF")), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            for ((lf, rw) in listOf(.14f to 90f, .46f to 46f, .76f to 90f)) {
                val rx = w * lf; val rwPx = Ui.dp(rw)
                c.save()
                c.translate(rx + rwPx / 2f, h / 2f); c.rotate(32f); c.translate(-rwPx / 2f, -h * 0.8f)
                p.shader = LinearGradient(0f, 0f, rwPx, 0f, intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(140, 255, 255, 255), Color.argb(0, 255, 255, 255)), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, rwPx, h * 1.6f, p)
                c.restore()
            }
            p.shader = null
        }
    }

    /** repeating-conic-gradient burst, fading out from the middle, turning once every 18 s */
    private class Burst(ctx: Context) : LoopView(ctx, 18000) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val mask = Paint(Paint.ANTI_ALIAS_FLAG)
        private val wedge = Path()
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height * .38f; val r = Ui.dp(210f)
            val save = c.saveLayer(cx - r, cy - r, cx + r, cy + r, null)
            c.rotate(360f * t, cx, cy)
            p.color = Color.argb(71, 255, 214, 120)
            var a = 0f
            while (a < 360f) {
                wedge.reset(); wedge.moveTo(cx, cy)
                wedge.arcTo(RectF(cx - r, cy - r, cx + r, cy + r), a - 90f, 9f); wedge.close()
                c.drawPath(wedge, p); a += 22f
            }
            mask.shader = RadialGradient(cx, cy, r, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, .3f, .7f), Shader.TileMode.CLAMP)
            mask.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            c.drawCircle(cx, cy, r, mask)
            c.restoreToCount(save)
        }
    }

    private class Twinkle(ctx: Context, private val sizeDp: Float, delay: Long) : LoopView(ctx, 2400) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Ui.bold; textAlign = Paint.Align.CENTER }
        private val d = delay
        override fun onDraw(c: Canvas) {
            val f = phase(d); val k = if (f < .5f) f / .5f else (1f - f) / .5f
            val e = PathInterpolator(.42f, 0f, .58f, 1f).getInterpolation(k)
            p.alpha = (255 * (.2f + .8f * e)).toInt()
            p.textSize = Ui.dp(sizeDp) * (.6f + .4f * e)
            c.drawText("✦", width / 2f, height / 2f - (p.fontMetrics.ascent + p.fontMetrics.descent) / 2f, p)
        }
    }

    private class StarChip : android.graphics.drawable.Drawable() {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.c("#6C2BD9") }
        private val star = Svg.of("<svg viewBox=\"0 0 24 24\"><path d=\"M12 2l2.6 6.6L21 9.3l-5 4.4 1.5 6.8L12 17l-5.5 3.5L8 13.7 3 9.3l6.4-.7z\" fill=\"#FFD24A\"/></svg>")
        override fun getIntrinsicWidth() = Ui.dpi(22f)
        override fun getIntrinsicHeight() = Ui.dpi(22f)
        override fun draw(c: Canvas) {
            val b = bounds; c.drawCircle(b.exactCenterX(), b.exactCenterY(), b.width() / 2f, bg)
            val s = Ui.dp(12f); star.draw(c, b.exactCenterX() - s / 2f, b.exactCenterY() - s / 2f, s, s)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** dark button with soft white waves and a shine sweeping across (2.6 s, from 1.2 s) */
    private class ClaimButton(ctx: Context) : TextView(ctx) {
        private val waves = Svg.of("<svg viewBox=\"0 0 228 52\"><g fill=\"none\" stroke=\"#FFFFFF\" stroke-opacity=\".09\" stroke-width=\"7\" stroke-linecap=\"round\"><path d=\"M-10 40C30 10 50 70 92 26\"/><path d=\"M60 62C90 20 120 64 150 18\"/><path d=\"M130 60C160 28 190 70 238 14\"/></g></svg>")
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private var t = 0f
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); startDelay = 1200
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
        }
        init {
            gravity = Gravity.CENTER
            setTextColor(Ui.c("#FFD983")); typeface = Ui.bold; textSize = 18f
            letterSpacing = 1f / 18f
            includeFontPadding = false
            background = Tf.box(Ui.c("#24123F"), 26f)
            elevation = Ui.dp(8f); outlineSpotShadowColorCompat(Ui.c("#24123F"))
            Ui.pressable(this, .97f)
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            clip.reset(); clip.addRoundRect(RectF(0f, 0f, w, h), h / 2f, h / 2f, Path.Direction.CW)
            c.save(); c.clipPath(clip)
            waves.draw(c, 0f, 0f, w, h, meet = false)
            // shine: 40 % wide, from -60 % to +130 % in the first 60 % of the loop
            val k = (t / .6f).coerceAtMost(1f)
            val sx = -w * .6f + w * 1.9f * k
            c.save(); c.translate(sx, 0f); c.skew(-0.364f, 0f)
            p.shader = LinearGradient(0f, 0f, w * .4f, 0f, intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(71, 255, 255, 255), Color.argb(0, 255, 255, 255)), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w * .4f, h, p); p.shader = null
            c.restore(); c.restore()
            super.onDraw(c)
        }
    }

    private class FlyCoin(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = width / 2f
            p.shader = RadialGradient(width * .35f, height * .30f, r * 1.3f, intArrayOf(Ui.c("#FFE9A6"), Ui.c("#F5B921"), Ui.c("#B97A00")), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(r, r, r, p)
        }
    }

    /** Confetti falling from the top; stops by itself when every piece has fallen. */
    private class Confetti(ctx: Context) : View(ctx) {
        private class P(var x: Float, var y: Float, val vx: Float, var vy: Float, val w: Float, val h: Float, var r: Float, val vr: Float, var sw: Float, val c: Int, val k: Char)
        private val parts = ArrayList<P>()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var running = false
        init { isClickable = false; isFocusable = false }
        fun burst() {
            val w = width / Ui.density; val h = height / Ui.density
            for (i in 0 until 150) {
                val k = if (Math.random() < .18) 'c' else if (Math.random() < .5) 's' else 'r'
                parts.add(P((Math.random() * w).toFloat(), (-20 - Math.random() * h * .6).toFloat(), ((Math.random() - .5) * 1.4).toFloat(), (2 + Math.random() * 2.6).toFloat(),
                    (6 + Math.random() * 6).toFloat(), (9 + Math.random() * 8).toFloat(), (Math.random() * 6.28).toFloat(), ((Math.random() - .5) * .25).toFloat(), (Math.random() * 6.28).toFloat(), COLORS[i % COLORS.size], k))
            }
            if (!running) { running = true; lastAt = 0L; postInvalidateOnAnimation() }
        }
        private var lastAt = 0L
        override fun onDraw(c: Canvas) {
            if (!running) return
            val d = Ui.density; val h = height / d
            // The pieces' steps were made for 60 pictures a second (one step each 16.7 ms): the step is sized by the
            // time since the last picture, so they fall just as fast on a screen that shows 90 or 120.
            val now = android.os.SystemClock.uptimeMillis()
            val k = if (lastAt == 0L) 1f else ((now - lastAt) / 16.667f).coerceIn(0.25f, 3f)
            lastAt = now
            for (q in parts) {
                q.sw += .08f * k; q.x += (q.vx + sin(q.sw.toDouble()).toFloat() * .8f) * k; q.y += q.vy * k; q.r += q.vr * k; q.vy = min(q.vy + .025f * k, 4.4f)
                c.save(); c.translate(q.x * d, q.y * d); c.rotate(Math.toDegrees(q.r.toDouble()).toFloat()); c.scale(1f, cos(q.sw * 1.3).toFloat())
                p.color = q.c
                when (q.k) {
                    'c' -> c.drawCircle(0f, 0f, q.w * .45f * d, p)
                    's' -> c.drawRect(-q.w * .25f * d, -q.h * .7f * d, q.w * .25f * d, q.h * .7f * d, p)
                    else -> c.drawRect(-q.w / 2f * d, -q.h / 2f * d, q.w / 2f * d, q.h / 2f * d, p)
                }
                c.restore()
            }
            parts.removeAll { it.y >= h + 30 }
            if (parts.isNotEmpty()) postInvalidateOnAnimation() else running = false
        }
    }

    @Suppress("unused") private fun keep(): Shader = SweepGradient(0f, 0f, 0, 0)
    @Suppress("unused") private val keepM = Matrix()
    @Suppress("unused") private fun keepMax(a: Int, b: Int) = max(a, b)
}
