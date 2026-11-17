package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Random Match's full screen (the web's CallStageScreen in components/DateCallScreen.jsx):
 * white page with the violet / black doodles, the round photo with lilac pulses,
 * "Finding someone for you…" / "Calling Riya…", the purple panel with its own
 * doodles and the red Cancel while searching.
 */
@SuppressLint("ViewConstructor")
class MatchStage(ctx: Context) : FrameLayout(ctx) {
    private val INK = Ui.c("#15101E"); private val SUB = Ui.c("#4A4458"); private val PURPLE = Ui.c("#6A1FD0")
    private val LILAC = Ui.c("#B794FF"); private val LILAC_TEXT = Ui.c("#E4D4FF"); private val GREEN = Ui.c("#1B9E4B")
    private val RED = Ui.c("#FF2D3D"); private val PANEL = Ui.c("#4B1396"); private val LAV2 = Ui.c("#EDE2FF")
    var stageName = ""

    private val kind: TextView
    private val photo = FacePic(ctx)
    private val icon = ImageView(ctx)
    private val line: TextView
    private val sub: TextView
    private val status: TextView
    private val cancelBox = LinearLayout(ctx)
    private val spacer = View(ctx)
    private val pulses = Pulses(ctx)
    private var onCancel: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.WHITE)
        isClickable = true
        // doodles: 140 tiles turned -8°
        addView(View(ctx).apply { background = PatternFill(DOODLE, 140f, -8f, "stageDoodle") }, LayoutParams(-1, -1))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        addView(col, LayoutParams(-1, -1))
        val top = FrameLayout(ctx).apply { setPadding(Ui.dpi(16f), Ui.dpi(14f), Ui.dpi(16f), 0); minimumHeight = Ui.dpi(48f) }
        val mid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        mid.addView(Tf.t(ctx, "True Find", 23f, PURPLE))
        kind = Tf.t(ctx, "", 11.5f, SUB).apply { setPadding(0, Ui.dpi(2f), 0, 0) }
        mid.addView(kind)
        top.addView(mid, LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
        val safe = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = Tf.box(INK, 14f); setPadding(Ui.dpi(7f), Ui.dpi(5f), Ui.dpi(10f), Ui.dpi(5f)) }
        safe.addView(ImageView(ctx).apply { setImageDrawable(PathIcon(PathIcon.SHIELD, Ui.c("#D9C8FF"), 16f)) }, LinearLayout.LayoutParams(Ui.dpi(16f), Ui.dpi(16f)).apply { rightMargin = Ui.dpi(6f) })
        val st = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        st.addView(Tf.t(ctx, "100% Safe", 11.5f, Color.WHITE)); st.addView(Tf.t(ctx, "& Secure", 10f, Ui.c("#D9C8FF"), false))
        safe.addView(st)
        top.addView(safe, LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        col.addView(top)
        val center = LinearLayout(ctx).apply { clipChildren = false; orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(Ui.dpi(24f), 0, Ui.dpi(24f), 0) }
        val ph = FrameLayout(ctx).apply { clipChildren = false }
        ph.addView(pulses, LayoutParams(Ui.dpi(140f), Ui.dpi(140f)))
        val circle = FrameLayout(ctx).apply { background = Tf.circle(LAV2, 4f, Color.WHITE); setPadding(Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f)); elevation = Ui.dp(8f); outlineSpotShadowColorCompat(Ui.c("#280a50")) }
        circle.addView(photo, LayoutParams(-1, -1)); circle.addView(icon, LayoutParams(Ui.dpi(54f), Ui.dpi(54f), Gravity.CENTER))
        ph.addView(circle, LayoutParams(Ui.dpi(140f), Ui.dpi(140f)))
        center.addView(ph, LinearLayout.LayoutParams(Ui.dpi(140f), Ui.dpi(140f)).apply { bottomMargin = Ui.dpi(18f) })
        line = Tf.t(ctx, "", 24f, INK).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }
        center.addView(line)
        sub = Tf.t(ctx, "", 14f, SUB, false).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dpi(6f), 0, 0) }
        center.addView(sub)
        col.addView(center, LinearLayout.LayoutParams(-1, 0, 1f))
        val panel = FrameLayout(ctx).apply { background = Tf.topRound(PANEL, 34f); clipToOutline = true }
        panel.addView(View(ctx).apply { background = PatternFill(PANEL_DOODLE, 120f, -8f, "panelDoodle") }, LayoutParams(-1, -1))
        val pc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(Ui.dpi(22f), Ui.dpi(22f), Ui.dpi(22f), Ui.dpi(26f)) }
        status = Tf.t(ctx, "", 12.5f, LILAC_TEXT, false).apply { gravity = Gravity.CENTER_VERTICAL; compoundDrawablePadding = Ui.dpi(7f) }
        val srow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        srow.addView(LiveBlinkDot(ctx, GREEN, 1200, .35f), LinearLayout.LayoutParams(Ui.dpi(7f), Ui.dpi(7f)).apply { rightMargin = Ui.dpi(7f) })
        srow.addView(status)
        pc.addView(srow)
        cancelBox.orientation = LinearLayout.VERTICAL; cancelBox.gravity = Gravity.CENTER_HORIZONTAL
        val btn = ImageView(ctx).apply {
            setImageDrawable(PathIcon(PathIcon.PHONE, Color.WHITE, 30f)); rotation = 135f; scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(RED); setStroke(Ui.dpi(3f), Color.WHITE) }
            contentDescription = "Cancel"
            setOnClickListener { onCancel?.invoke() }
        }
        Ui.pressable(btn, .94f)
        cancelBox.addView(btn, LinearLayout.LayoutParams(Ui.dpi(72f), Ui.dpi(72f)))
        cancelBox.addView(Tf.t(ctx, "Cancel", 12.5f, Color.WHITE).apply { setPadding(0, Ui.dpi(5f), 0, 0) })
        pc.addView(cancelBox, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dpi(14f) })
        pc.addView(spacer, LinearLayout.LayoutParams(1, Ui.dpi(96f)).apply { topMargin = Ui.dpi(14f) })
        panel.addView(pc, LayoutParams(-1, -2))
        col.addView(panel, LinearLayout.LayoutParams(-1, -2))
        alpha = 0f; scaleX = .97f; scaleY = .97f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(400).start()
        panel.translationY = Ui.dp(400f)
        panel.animate().translationY(0f).setStartDelay(50).setDuration(500).setInterpolator(android.view.animation.PathInterpolator(.22f, 1f, .36f, 1f)).start()
    }

    /** stage: "search" or "calling"; title replaces the first line (search) */
    fun show(stage: String, type: String, rate: Int, photoRaws: List<String>, name: String, title: String?, cancel: (() -> Unit)?) {
        val video = type == "video"
        kind.text = "${if (video) "Video" else "Voice"} call${if (rate > 0) " · $rate coins/min" else ""}"
        val who = Tf.firstName(name)
        line.text = title ?: if (stage == "search") (if (video) "Finding a video partner…" else "Finding someone to talk…") else if (who.isNotEmpty()) "Calling $who…" else "Calling…"
        sub.text = if (stage == "search") "Only people who are online and free" else "Please wait a moment"
        status.text = if (stage == "search") "Searching" else "Connecting"
        onCancel = cancel
        cancelBox.visibility = if (cancel != null) VISIBLE else GONE
        spacer.visibility = if (cancel != null) GONE else VISIBLE
        if (photoRaws.isNotEmpty()) { photo.visibility = VISIBLE; icon.visibility = GONE; photo.setPhoto(photoRaws) }
        else {
            photo.visibility = GONE; icon.visibility = VISIBLE
            icon.setImageDrawable(if (stage == "search") (if (video) Svg.lucide("<rect x=\"2\" y=\"6\" width=\"14\" height=\"12\" rx=\"2\"/><path d=\"M16 10l6-3v10l-6-3z\"/>", 2f).let { SvgDrawable(it, 54f, 54f, PURPLE) }
                else PathIcon(PathIcon.PHONE, PURPLE, 50f)) else SvgDrawable(Svg.of("<svg viewBox=\"0 0 124 124\"><circle cx=\"62\" cy=\"48\" r=\"21\" fill=\"#FFFFFF\"/></svg>"), 54f))
        }
    }

    private class Pulses(ctx: Context) : LoopView(ctx, 1500) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.c("#B794FF") }
        override fun onDraw(c: Canvas) {
            val r = width / 2f
            for (d in floatArrayOf(0f, .5f)) {
                val f = (t + d) % 1f; val e = 1f - (1f - f) * (1f - f)
                p.alpha = (.5f * (1f - e) * 255).toInt()
                c.drawCircle(r, r, r * (1f + .75f * e), p)
            }
        }
    }

    companion object {
        const val DOODLE = "<svg viewBox=\"0 0 140 140\"><g fill=\"none\" stroke-width=\"2.4\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><g stroke=\"#7B2FF7\" opacity=\"0.28\"><path d=\"M22 34c-6-9-20-3-11 9l11 11 11-11c9-12-5-18-11-9z\"/><path d=\"M98 52l4 9 10 1-8 6 3 10-9-6-9 6 3-10-8-6 10-1z\"/><path d=\"M50 118v-14l10-3v14\"/><circle cx=\"47\" cy=\"119\" r=\"3\"/><circle cx=\"57\" cy=\"116\" r=\"3\"/><path d=\"M118 112l-6 10h8l-6 10\"/></g><g stroke=\"#15101E\" opacity=\"0.16\"><path d=\"M66 18q8-10 16 0t16 0t16 0\"/><circle cx=\"28\" cy=\"88\" r=\"7\"/><path d=\"M66 74a6 6 0 1 1 6 6a10 10 0 1 1-10-10\"/><path d=\"M92 104v14M85 111h14\"/><path d=\"M10 128c6-4 12 4 18 0\"/><path d=\"M124 22l6 6M130 22l-6 6\"/></g></g></svg>"
        const val PANEL_DOODLE = "<svg viewBox=\"0 0 120 120\"><g fill=\"none\" stroke=\"#fff\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" opacity=\"0.13\"><path d=\"M20 30c-5-8-17-3-9 8l9 9 9-9c8-11-4-16-9-8z\"/><path d=\"M84 44l3 8 9 1-7 5 3 9-8-5-8 5 3-9-7-5 9-1z\"/><path d=\"M56 16q7-9 14 0t14 0\"/><circle cx=\"26\" cy=\"78\" r=\"6\"/><path d=\"M44 102v-12l9-3v12\"/><circle cx=\"41\" cy=\"103\" r=\"3\"/><circle cx=\"50\" cy=\"100\" r=\"3\"/><path d=\"M82 92v12M76 98h12\"/><path d=\"M104 100l-5 9h7l-5 9\"/></g></svg>"
    }
}

/** A rotated repeating pattern (an SVG <pattern> with patternTransform rotate). */
class PatternFill(private val markup: String, private val tileDp: Float, private val rotate: Float, private val key: String) : android.graphics.drawable.Drawable() {
    private val p by lazy { SvgPattern.paint(Svg.of(markup), tileDp, rotate, key) }
    override fun draw(c: Canvas) { c.drawRect(bounds, p) }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

@Suppress("unused") private val keepVA: Class<*> = ValueAnimator::class.java
@Suppress("unused") private val keepLI: Class<*> = LinearInterpolator::class.java
