package app.truefind.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Colours, icons and small parts of the native call screens — the same as the web's
 * components/DateCallScreen.jsx, IncomingCallWatcher.jsx, CallPulseIcon.jsx and CallFeedback.jsx
 * (sizes in dp = the web's CSS px, Calibri / Carlito text).
 */
object CallKit {
    val INK = Ui.c("#15101E"); val SUB = Ui.c("#4A4458"); val PURPLE = Ui.c("#6A1FD0"); val VIOLET = Ui.c("#7B2FF7")
    val LAV = Ui.c("#F1EAFE"); val LAV2 = Ui.c("#EDE2FF"); val LILAC = Ui.c("#B794FF"); val LILAC_TEXT = Ui.c("#E4D4FF")
    val PINK_A = Ui.c("#FF6FA8"); val PINK_B = Ui.c("#E1306C"); val PINK_TEXT = Ui.c("#C2185B"); val PINK_BG = Ui.c("#FFF0F6")
    val GREEN = Ui.c("#1B9E4B"); val RED = Ui.c("#FF2D3D"); val PANEL = Ui.c("#4B1396")
    val BLUE = Ui.c("#5664EF"); val BLUE_LIGHT = Ui.c("#7C86F6")

    const val HEART_PATH = "M12 21c-6.5-4.4-10-8-10-12a5 5 0 0 1 10-1.6A5 5 0 0 1 22 9c0 4-3.5 7.6-10 12z"
    const val PHONE_PATH = "M6.6 10.8a15.1 15.1 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25 11.4 11.4 0 0 0 3.6.57 1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.5a1 1 0 0 1 1 1c0 1.25.2 2.45.57 3.57a1 1 0 0 1-.25 1z"

    /** the pink "Date This" heart (linear gradient #FF6FA8 → #E1306C) */
    val PINK_HEART = "<svg viewBox=\"0 0 24 24\"><defs><linearGradient id=\"g\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\"><stop offset=\"0\" stop-color=\"#FF6FA8\"/><stop offset=\"1\" stop-color=\"#E1306C\"/></linearGradient></defs><path d=\"$HEART_PATH\" fill=\"url(#g)\"/></svg>"
    val PHONE = "<svg viewBox=\"0 0 24 24\"><path fill=\"currentColor\" d=\"$PHONE_PATH\"/></svg>"
    private fun stroke(w: Float, inner: String) =
        "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"$w\" stroke-linecap=\"round\" stroke-linejoin=\"round\">$inner</svg>"
    /** Icon.mic of the web: always drawn with its slash (the web passes off = true for both states) */
    val MIC = stroke(2.2f, "<rect x=\"9\" y=\"3\" width=\"6\" height=\"11\" rx=\"3\"/><path d=\"M5 11a7 7 0 0 0 14 0M12 18v3\"/><path d=\"M3 3l18 18\"/>")
    val SPEAKER = stroke(2.2f, "<path d=\"M11 5L6 9H3v6h3l5 4z\"/><path d=\"M15.5 8.5a5 5 0 0 1 0 7\"/>")
    val SPEAKER_ON = stroke(2.2f, "<path d=\"M11 5L6 9H3v6h3l5 4z\"/><path d=\"M15.5 8.5a5 5 0 0 1 0 7\"/><path d=\"M18.5 5.5a9 9 0 0 1 0 13\"/>")
    val CAMERA = stroke(2.2f, "<rect x=\"2\" y=\"6\" width=\"14\" height=\"12\" rx=\"2\"/><path d=\"M16 10l6-3v10l-6-3z\"/>")
    val CAMERA_OFF = stroke(2.2f, "<rect x=\"2\" y=\"6\" width=\"14\" height=\"12\" rx=\"2\"/><path d=\"M16 10l6-3v10l-6-3z\"/><path d=\"M3 3l18 18\"/>")
    val CHAT = stroke(2.2f, "<path d=\"M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z\"/>")
    val SHIELD = stroke(2.2f, "<path d=\"M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z\"/><path d=\"M8.5 12l2.5 2.5 4.5-5\"/>")
    val LOCK = stroke(2.4f, "<rect x=\"5\" y=\"11\" width=\"14\" height=\"10\" rx=\"2\"/><path d=\"M8 11V8a4 4 0 0 1 8 0v3\"/>")
    val PLUS = stroke(3.4f, "<path d=\"M12 5v14M5 12h14\"/>")
    val UP = stroke(2.6f, "<path d=\"M6 15l6-6 6 6\"/>")
    val SEND = "<svg viewBox=\"0 0 24 24\"><path fill=\"currentColor\" d=\"M3 20l18-8L3 4v6l12 2-12 2z\"/></svg>"
    // lucide-react 0.475 icons used by CallFeedback.jsx / CallScreen.jsx
    const val FLAG = "<path d=\"M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z\"/><line x1=\"4\" x2=\"4\" y1=\"22\" y2=\"15\"/>"
    const val SHIELD_ALERT = "<path d=\"M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z\"/><path d=\"M12 8v4\"/><path d=\"M12 16h.01\"/>"
    const val COINS = "<circle cx=\"8\" cy=\"8\" r=\"6\"/><path d=\"M18.09 10.37A6 6 0 1 1 10.34 18\"/><path d=\"M7 6h1v4\"/><path d=\"m16.71 13.88.7.71-2.82 2.82\"/>"
    // the incoming call's glyphs (IncomingCallWatcher.jsx)
    val VIDEO_GLYPH = "<svg viewBox=\"0 0 24 24\"><rect x=\"2\" y=\"6\" width=\"14\" height=\"12\" rx=\"3\" fill=\"currentColor\"/><path fill=\"currentColor\" d=\"M16 10l6-4v12l-6-4z\"/></svg>"
    // CallPulseIcon.jsx
    val PULSE_PHONE = "<svg viewBox=\"0 0 24 24\"><path fill=\"#fff\" d=\"M6.6 3.2c.5-.4 1.3-.3 1.7.2l2 2.6c.4.5.3 1.2-.1 1.6l-1.3 1.2c-.3.3-.4.8-.2 1.2.9 1.8 2.4 3.3 4.2 4.2.4.2.9.1 1.2-.2l1.2-1.3c.4-.4 1.1-.5 1.6-.1l2.6 2c.5.4.6 1.2.2 1.7l-1 1.3c-.8 1-2.1 1.5-3.4 1.1C9.6 17.4 5.6 13.4 4 7.6c-.4-1.3.1-2.6 1.1-3.4z\"/></svg>"
    val PULSE_VIDEO = "<svg viewBox=\"0 0 24 24\"><rect x=\"2.5\" y=\"6\" width=\"13\" height=\"12\" rx=\"3\" fill=\"#fff\"/><path fill=\"#fff\" d=\"M16.5 10.2l4.1-2.6c.6-.4 1.4.1 1.4.8v7.2c0 .7-.8 1.2-1.4.8l-4.1-2.6z\"/></svg>"
    /** IncomingCallWatcher's own doodles (132 tile, white strokes at 8 %) */
    const val INCOMING_DOODLE = "<svg viewBox=\"0 0 132 132\"><g fill=\"none\" stroke=\"#fff\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" opacity=\"0.08\"><path d=\"M22 30c-5-8-17-3-9 8l9 9 9-9c8-11-4-16-9-8z\"/><path d=\"M92 40l3 8 9 1-7 5 3 9-8-5-8 5 3-9-7-5 9-1z\"/><path d=\"M58 14q7-9 14 0t14 0\"/><circle cx=\"28\" cy=\"84\" r=\"6\"/><path d=\"M48 108v-12l9-3v12\"/><circle cx=\"45\" cy=\"109\" r=\"3\"/><circle cx=\"54\" cy=\"106\" r=\"3\"/><path d=\"M92 96v12M86 102h12\"/><path d=\"M116 104l-5 9h7l-5 9\"/></g></svg>"

    val EASE_OUT_BACK = PathInterpolator(.22f, 1f, .36f, 1f)        // cubic-bezier(.22,1,.36,1)
    val EASE_IN_OUT = PathInterpolator(.42f, 0f, .58f, 1f)
    val EASE_OUT = PathInterpolator(0f, 0f, .58f, 1f)

    fun svg(markup: String, sizeDp: Float, tint: Int = Color.WHITE): Drawable = SvgDrawable(Svg.of(markup), sizeDp, sizeDp, tint)
    fun lu(inner: String, sizeDp: Float, color: Int, stroke: Float = 2f): Drawable = Tf.lu(inner, color, sizeDp, stroke)
    fun heart(sizeDp: Float): Drawable = SvgDrawable(Svg.of(PINK_HEART), sizeDp, sizeDp)

    /** CSS font-weight → Carlito (it has regular and bold only): 600 and up are bold */
    fun t(ctx: Context, s: CharSequence, sizeDp: Float, color: Int, weight: Int = 700): TextView = Tf.t(ctx, s, sizeDp, color, weight >= 600)

    fun firstName(n: String?): String = Tf.firstName(n)
    fun fmt(s0: Long): String { val s = maxOf(0L, s0); return "%02d:%02d".format(s / 60, s % 60) }
    /** time left: 00:59 · 12:05 · 1:02:30 (an hour or more) */
    fun fmtLeft(s0: Long): String { val s = maxOf(0L, s0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else fmt(s) }

    /** the doodles of the call page (140 tile, -8°) and of its purple panel (120 tile) */
    fun doodles(ctx: Context): View = View(ctx).apply { background = PatternFill(MatchStage.DOODLE, 140f, -8f, "stageDoodle"); isClickable = false }
    fun panelDoodles(ctx: Context): View = FillView(ctx).apply { background = PatternFill(MatchStage.PANEL_DOODLE, 120f, -8f, "panelDoodle") }

    /** "100% Safe & Secure" dark badge (top right of every call page) */
    fun safeBadge(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = Tf.box(INK, 14f)
        setPadding(Ui.dpi(7f), Ui.dpi(5f), Ui.dpi(10f), Ui.dpi(5f))
        addView(ImageView(ctx).apply { setImageDrawable(svg(SHIELD, 18f, LILAC)) }, LinearLayout.LayoutParams(Ui.dpi(18f), Ui.dpi(18f)).apply { rightMargin = Ui.dpi(6f) })
        val st = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        st.addView(t(ctx, "100% Safe", 11.5f, Color.WHITE))
        st.addView(t(ctx, "& Secure", 10f, Ui.c("#D9C8FF"), 400))
        addView(st)
    }

    /** a round button with its word under it (RoundBtn of the web) */
    class RoundBtn(ctx: Context, label: String, sizeDp: Float, bg: Int, borderDp: Float, borderColor: Int, icon: Drawable?, iconRot: Float = 0f,
                   labelWeight: Int = 500, compact: Boolean = false, onClick: () -> Unit) : LinearLayout(ctx) {
        val circle = ImageView(ctx)
        val text: TextView
        init {
            orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            circle.scaleType = ImageView.ScaleType.CENTER
            circle.setImageDrawable(icon)
            circle.rotation = iconRot
            circle.background = Tf.circle(bg, borderDp, borderColor)
            addView(circle, LayoutParams(Ui.dpi(sizeDp), Ui.dpi(sizeDp)))
            text = t(ctx, label, if (compact) 11f else 12.5f, Color.WHITE, labelWeight).apply { gravity = Gravity.CENTER }
            addView(text, LayoutParams(-2, -2).apply { topMargin = Ui.dpi(if (compact) 3f else 5f) })
            minimumWidth = Ui.dpi(sizeDp + 16f)
            contentDescription = label
            setOnClickListener { onClick() }
            circle.isClickable = false
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> circle.animate().scaleX(.94f).scaleY(.94f).setDuration(110).start()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> circle.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }
                false
            }
        }
        fun set(label: String, bg: Int, borderDp: Float, borderColor: Int, icon: Drawable?) {
            text.text = label; contentDescription = label
            circle.background = Tf.circle(bg, borderDp, borderColor)
            circle.setImageDrawable(icon)
        }
    }

    /** the gold "TF" coin of the call panel (CoinTF) */
    class CoinTF(ctx: Context, private val sizeDp: Float = 22f) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Ui.bold; color = Ui.c("#6B4500") }
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Ui.dpi(sizeDp), Ui.dpi(sizeDp))
        override fun onDraw(c: Canvas) {
            val s = width.toFloat(); val r = s / 2f
            c.save()
            val clip = android.graphics.Path().apply { addCircle(r, r, r, android.graphics.Path.Direction.CW) }
            c.clipPath(clip)
            p.style = Paint.Style.FILL; p.color = Ui.c("#FFB800"); c.drawCircle(r, r, r, p)
            // inset 0 -2px 0 #B37800: a darker rim along the bottom
            p.color = Ui.c("#B37800"); c.drawCircle(r, r, r, p)
            p.color = Ui.c("#FFB800"); c.drawCircle(r, r - Ui.dp(2f), r, p)
            c.restore()
            p.style = Paint.Style.STROKE; p.strokeWidth = Ui.dp(2f); p.color = Ui.c("#FFE08A")
            c.drawCircle(r, r, r - Ui.dp(1f), p)
            tp.textSize = s * .36f
            val fm = tp.fontMetrics
            c.drawText("TF", r, r - (fm.ascent + fm.descent) / 2f, tp)
        }
    }

    /** the talk bars (dcsEq .8 s, each bar its own delay) */
    class Bars(ctx: Context, private val white: Boolean = false, private val count: Int = 7) : LoopView(ctx, 800) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (white) Color.WHITE else PURPLE }
        private val r = RectF()
        private val delays = floatArrayOf(0f, .12f, .28f, .05f, .4f, .18f, .33f, .08f, .24f)
        private val bw get() = Ui.dp(if (white) 3f else 4f)
        private val gap get() = Ui.dp(if (white) 2f else 3f)
        private val bh get() = Ui.dp(if (white) 16f else 26f)
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Math.round(bw * count + gap * (count - 1)), Math.round(bh))
        override fun onDraw(c: Canvas) {
            val cy = height / 2f
            for (i in 0 until count) {
                val ph = ((t - delays[i] / .8f) % 1f + 1f) % 1f
                val k = if (ph < .5f) ph / .5f else (1f - ph) / .5f
                val s = .25f + .75f * EASE_IN_OUT.getInterpolation(k)
                val x = i * (bw + gap)
                val hh = bh * s / 2f
                r.set(x, cy - hh, x + bw, cy + hh)
                c.drawRoundRect(r, Ui.dp(3f), Ui.dp(3f), p)
            }
        }
    }

    /** a dot blinking 1 → .35 → 1 (dcsBlink 1.2 s) */
    fun blinkDot(ctx: Context, color: Int) = LiveBlinkDot(ctx, color, 1200, .35f)
}

/**
 * Round photos of the call screens: a photo (or the lavender default avatar) in a circle with a
 * coloured ring, a soft purple shadow, and room for badges around it (heart, muted mic).
 * Photos: an https address, a private file (signed first), a data: picture, or asset:path (test pictures).
 */
class CallFace(ctx: Context, private val sizeDp: Float, borderDp: Float, private val shadow: Boolean = true) : FrameLayout(ctx) {
    private val pic = Pic(ctx, borderDp)
    private var raw = ""
    var grey = false
        set(v) { field = v; pic.grey = v; pic.invalidate() }

    init {
        clipChildren = false; clipToPadding = false
        addView(pic, LayoutParams(Ui.dpi(sizeDp), Ui.dpi(sizeDp)))
        if (shadow) {
            pic.elevation = Ui.dp(8f)
            pic.outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) } }
            pic.outlineSpotShadowColorCompat(Color.argb(90, 40, 10, 80))
        }
    }

    fun border(color: Int, animate: Boolean = true) = pic.setBorder(color, animate)

    fun setPhoto(raw0: String?) {
        val r = raw0.orEmpty()
        if (r == raw && (pic.bmp != null || r.isEmpty())) return
        raw = r
        pic.bmp = null; pic.invalidate()
        if (r.isEmpty()) return
        CallPics.round(context, r, Ui.dpi(sizeDp)) { b -> if (raw == r) { pic.bmp = b; pic.invalidate() } }
    }

    fun setBitmap(b: Bitmap?) { raw = ""; pic.bmp = b; pic.invalidate() }

    /** a badge at (right, top) offsets in dp from the photo's own corner */
    fun badge(v: View, wDp: Float, hDp: Float, rightDp: Float? = null, topDp: Float? = null, bottomDp: Float? = null, leftDp: Float? = null) {
        val lp = LayoutParams(Ui.dpi(wDp), Ui.dpi(hDp))
        lp.gravity = (if (rightDp != null) Gravity.END else Gravity.START) or (if (bottomDp != null) Gravity.BOTTOM else Gravity.TOP)
        rightDp?.let { lp.rightMargin = Ui.dpi(it) }; leftDp?.let { lp.leftMargin = Ui.dpi(it) }
        topDp?.let { lp.topMargin = Ui.dpi(it) }; bottomDp?.let { lp.bottomMargin = Ui.dpi(it) }
        v.elevation = Ui.dp(10f)
        addView(v, lp)
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = Ui.dpi(sizeDp)
        super.onMeasure(MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY))
    }

    private class Pic(ctx: Context, private val borderDp: Float) : View(ctx) {
        var bmp: Bitmap? = null
        var grey = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var color = Color.WHITE
        private var anim: ValueAnimator? = null
        private val r = RectF()
        private val greyFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(.4f) })
        fun setBorder(c: Int, animate: Boolean) {
            if (c == color) return
            anim?.cancel()
            if (!animate || !isAttachedToWindow) { color = c; invalidate(); return }
            val from = color
            anim = ValueAnimator.ofArgb(from, c).apply { duration = 200; addUpdateListener { color = it.animatedValue as Int; invalidate() }; start() }
        }
        override fun onDraw(c: Canvas) {
            val s = minOf(width, height).toFloat()
            r.set(0f, 0f, s, s)
            p.colorFilter = if (grey) greyFilter else null
            p.shader = null; p.color = CallKit.LAV2; c.drawOval(r, p)
            val b = bmp
            if (b != null) {
                p.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    val k = maxOf(s / b.width, s / b.height)
                    setLocalMatrix(Matrix().apply { setScale(k, k); postTranslate((s - b.width * k) / 2f, (s - b.height * k) / 2f) })
                }
                c.drawOval(r, p); p.shader = null
            } else {
                if (grey) { c.saveLayer(r, Paint().apply { colorFilter = greyFilter }); DefaultAvatar.draw(c, s); c.restore() } else DefaultAvatar.draw(c, s)
            }
            if (borderDp > 0f) {
                val bw = Ui.dp(borderDp)
                ring.strokeWidth = bw; ring.color = color
                c.drawCircle(s / 2f, s / 2f, s / 2f - bw / 2f, ring)
            }
        }
    }
}

/** The small white circle with the pink heart on a photo (HeartBadge), popping in (dcsPop .45 s). */
class HeartBadge(ctx: Context, label: String) : FrameLayout(ctx) {
    init {
        background = Tf.circle(Color.WHITE)
        elevation = Ui.dp(5f)
        outlineSpotShadowColorCompat(Color.argb(90, 225, 48, 108))
        addView(ImageView(ctx).apply { setImageDrawable(CallKit.heart(20f)) }, LayoutParams(Ui.dpi(20f), Ui.dpi(20f), Gravity.CENTER))
        contentDescription = label
    }
    private val icon get() = getChildAt(0)
    /** the small size (on the video call's little photo and my camera tile): a smaller heart in the circle */
    fun scaleSmall() { (icon.layoutParams as LayoutParams).apply { width = Ui.dpi(14f); height = Ui.dpi(14f) }; icon.requestLayout() }
    fun pop() {
        scaleX = 0f; scaleY = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450; interpolator = CallKit.EASE_OUT
            addUpdateListener { val f = it.animatedValue as Float; val s = if (f < .6f) 1.35f * (f / .6f) else 1.35f - .35f * ((f - .6f) / .4f); scaleX = s; scaleY = s }
            start()
        }
    }
}

/** Two lilac circles growing and fading behind the stage photo (dcsPulse 1.5 s, the second .75 s later). */
class StagePulses(ctx: Context) : LoopView(ctx, 1500) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CallKit.LILAC }
    var on = true
        set(v) { field = v; invalidate() }
    override fun onDraw(c: Canvas) {
        if (!on) return
        val r = width / 2f
        for (d in floatArrayOf(0f, .5f)) {
            val f = (t + d) % 1f; val e = CallKit.EASE_OUT.getInterpolation(f)
            p.alpha = (.5f * (1f - e) * 255).toInt()
            c.drawCircle(r, r, r * (1f + .75f * e), p)
        }
    }
}

/**
 * The incoming call's Accept button (CallPulseIcon.jsx): blue round button with a white phone (voice)
 * or camera (video). The icon shakes, a lighter circle grows inside, then a ring spreads out and fades.
 * Loops every 2 s. The ring is drawn outside the view (its parents don't clip).
 */
class CallPulse(ctx: Context, video: Boolean) : LoopView(ctx, 2000) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Svg.of(if (video) CallKit.PULSE_VIDEO else CallKit.PULSE_PHONE)
    private fun seg(t: Float, keys: FloatArray, vals: FloatArray): Float {
        if (t <= keys[0]) return vals[0]
        for (i in 1 until keys.size) if (t <= keys[i]) {
            val f = (t - keys[i - 1]) / (keys[i] - keys[i - 1])
            return vals[i - 1] + (vals[i] - vals[i - 1]) * CallKit.EASE_IN_OUT.getInterpolation(f)
        }
        return vals.last()
    }
    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat(); val r = s / 2f; val cx = width / 2f; val cy = height / 2f
        // ring: still until 58 %, then grows to 1.45 and fades (ease-out)
        val rs: Float; val ra: Float
        if (t <= .58f) { rs = 1f; ra = .7f } else { val e = CallKit.EASE_OUT.getInterpolation((t - .58f) / .42f); rs = 1f + .45f * e; ra = .7f * (1f - e) }
        p.color = CallKit.BLUE; p.alpha = (255 * ra).toInt(); c.drawCircle(cx, cy, r * rs, p)
        p.alpha = 255; c.drawCircle(cx, cy, r, p)
        // lighter circle inside (inset 8 %)
        val ins = seg(t, floatArrayOf(0f, .12f, .30f, .55f, .66f, 1f), floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f))
        if (ins > 0f) { p.color = CallKit.BLUE_LIGHT; c.drawCircle(cx, cy, r * .84f * ins, p) }
        // the icon wiggles
        val rot = seg(t, floatArrayOf(0f, .12f, .22f, .32f, .42f, .52f, .66f, 1f), floatArrayOf(0f, 0f, -14f, 10f, -8f, 0f, 0f, 0f))
        val sc = seg(t, floatArrayOf(0f, .12f, .22f, .32f, .42f, .52f, .66f, 1f), floatArrayOf(1f, 1f, 1.12f, 1.12f, 1.12f, 1.08f, 1f, 1f))
        val ico = Math.round(s * .44f).toFloat()
        c.save(); c.translate(cx, cy); c.rotate(rot); c.scale(sc, sc)
        icon.draw(c, -ico / 2f, -ico / 2f, ico, ico)
        c.restore()
    }
}

/** The incoming call's two border rings around the photo (tficWave 2.6 s, the second 1.3 s later). */
class WaveRings(ctx: Context) : LoopView(ctx, 2600) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(191, 196, 181, 253) }
    override fun onDraw(c: Canvas) {
        val r = width / 2f
        p.strokeWidth = Ui.dp(2f)
        for (d in floatArrayOf(0f, .5f)) {
            val f = (t + d) % 1f; val e = CallKit.EASE_OUT.getInterpolation(f)
            p.alpha = (.42f * (1f - e) * 255).toInt()
            c.drawCircle(r, r, (r - Ui.dp(1f)) * (1f + .62f * e), p)
        }
    }
}

/** Photos for the call screens: round copies, cover copies (full screen), and blurred backgrounds. */
object CallPics {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun decode(ctx: Context, raw: String): Bitmap? = try {
        when {
            raw.startsWith("data:") -> { val b = Base64.decode(raw.substringAfter(","), Base64.DEFAULT); BitmapFactory.decodeByteArray(b, 0, b.size) }
            raw.startsWith("asset:") -> ctx.assets.open(raw.removePrefix("asset:")).use { BitmapFactory.decodeStream(it) }
            else -> null
        }
    } catch (e: Throwable) { null }

    private fun local(raw: String) = raw.startsWith("data:") || raw.startsWith("asset:")

    /** a square, centre-cropped copy of [src] of [px] pixels */
    fun square(src: Bitmap, px: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val sx = (src.width - side) / 2; val sy = (src.height - side) / 2
        Canvas(out).drawBitmap(src, Rect(sx, sy, sx + side, sy + side), Rect(0, 0, px, px), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** a w × h copy that fills the box (object-fit: cover) */
    fun cover(src: Bitmap, w: Int, h: Int): Bitmap {
        val k = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val cw = (w / k).toInt().coerceIn(1, src.width); val ch = (h / k).toInt().coerceIn(1, src.height)
        val sx = (src.width - cw) / 2; val sy = (src.height - ch) / 2
        val out = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(sx, sy, sx + cw, sy + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** a square photo of [px] (CallFace makes it round) */
    fun round(ctx: Context, raw: String, px: Int, done: (Bitmap?) -> Unit) {
        if (raw.isBlank()) { done(null); return }
        if (local(raw)) { Api.async({ decode(ctx, raw)?.let { square(it, px) } }, done); return }
        Photos.loadRaw(listOf(raw), px, done)
    }

    /** a photo that fills w × h (the video poster, the Go to Chat card) */
    fun fill(ctx: Context, raw: String, w: Int, h: Int, done: (Bitmap?) -> Unit) {
        if (raw.isBlank() || w <= 0 || h <= 0) { done(null); return }
        if (local(raw)) { Api.async({ decode(ctx, raw)?.let { cover(it, w, h) } }, done); return }
        Photos.loadCover(listOf(raw), w, h, done)
    }

    /**
     * The soft picture behind the incoming call / a camera that is off: the web draws the photo at 10 %
     * size and stretches it (a blur that costs nothing). Here: a tiny cover copy, stretched with filtering.
     */
    fun blurred(ctx: Context, raw: String, w: Int, h: Int, done: (Bitmap?) -> Unit) {
        val sw = (w / 10).coerceAtLeast(8); val sh = (h / 10).coerceAtLeast(8)
        fill(ctx, raw, sw * 4, sh * 4) { b -> if (b == null) done(null) else Api.async({ soften(Bitmap.createScaledBitmap(b, sw, sh, true)) }, done) }
    }

    /** blur(1.6px) on the small copy: two passes of a 3 × 3 box blur */
    private fun soften(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        var a = IntArray(w * h); src.getPixels(a, 0, w, 0, 0, w, h)
        var b = IntArray(w * h)
        repeat(2) {
            for (y in 0 until h) for (x in 0 until w) {
                var rr = 0; var gg = 0; var bb = 0; var n = 0
                for (dy in -1..1) { val yy = y + dy; if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) { val xx = x + dx; if (xx < 0 || xx >= w) continue
                        val c = a[yy * w + xx]; rr += (c shr 16) and 255; gg += (c shr 8) and 255; bb += c and 255; n++ } }
                b[y * w + x] = Color.rgb(rr / n, gg / n, bb / n)
            }
            val t = a; a = b; b = t
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }

    @Suppress("unused") private fun keep() = main
}

/** A bitmap drawn to fill the view, optionally stretched from a tiny copy (soft blur) and scaled like the web (10.6 × from the top-left). */
class CoverImage(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    /** the web's blur: the 10 % copy is drawn 10.6 × from the top-left corner */
    var webBlur = false
    private val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    override fun onDraw(c: Canvas) {
        val b = bitmap ?: return
        if (webBlur) dst.set(0f, 0f, width * 1.06f, height * 1.06f) else dst.set(0f, 0f, width.toFloat(), height.toFloat())
        if (webBlur) c.drawBitmap(b, null, dst, p)
        else {
            val k = maxOf(width.toFloat() / b.width, height.toFloat() / b.height)
            val dw = b.width * k; val dh = b.height * k
            dst.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
            c.drawBitmap(b, null, dst, p)
        }
    }
}

/** A plain vertical gradient over the whole view (CSS linear-gradient(to bottom, …)). */
class GradientView(ctx: Context, private val colors: IntArray, private val stops: FloatArray?, private val angleDeg: Float = 180f) : View(ctx) {
    private val p = Paint()
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // CSS angle: 180deg = to bottom; 165deg leans a little to the right
        val a = Math.toRadians(angleDeg.toDouble())
        val dx = Math.sin(a).toFloat(); val dy = -Math.cos(a).toFloat()
        val half = (Math.abs(w * dx) + Math.abs(h * dy)) / 2f
        val cx = w / 2f; val cy = h / 2f
        p.shader = android.graphics.LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half, colors, stops, Shader.TileMode.CLAMP)
    }
    override fun onDraw(c: Canvas) { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
}
