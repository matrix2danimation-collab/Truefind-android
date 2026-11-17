package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
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
 * "Finding someone…" / "Calling Riya…" — the native copy of the web's
 * CallStageScreen (white page, round photo with lilac pulses, purple panel
 * at the bottom with the red Cancel button while searching).
 *
 * [webLook] (Random Match): also the web's doodle pattern on the white page and
 * on the purple panel, the panel sliding up (dcsPanel) and the blinking green
 * dot (dcsBlink). Explore keeps its own look (webLook = false).
 */
@SuppressLint("ViewConstructor")
class CallStageView(ctx: Context, private val webLook: Boolean = false) : FrameLayout(ctx) {
    private val INK = Ui.c("#15101E"); private val SUB = Ui.c("#4A4458"); private val PURPLE = Ui.c("#6A1FD0")
    private val LILAC = Ui.c("#B794FF"); private val LILAC_TEXT = Ui.c("#E4D4FF"); private val GREEN = Ui.c("#1B9E4B")
    private val RED = Ui.c("#FF2D3D"); private val PANEL = Ui.c("#4B1396"); private val LAV2 = Ui.c("#EDE2FF")

    private val kind: TextView
    private val photo: ImageView
    private val line: TextView
    private val sub: TextView
    private val status: TextView
    private val cancelBox: LinearLayout
    private val spacer: View
    private val pulses: PulseView
    private val panel: LinearLayout
    private val dot: GradientDrawable
    private val blink: ValueAnimator?
    var onCancel: (() -> Unit)? = null

    init {
        if (webLook) background = Doodles.Page() else setBackgroundColor(Color.WHITE)
        isClickable = true // nothing under it can be tapped
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // top: title + kind, "100% Safe & Secure" on the right
        val top = FrameLayout(ctx).apply { setPadding(Ui.dpi(16f), Ui.dpi(14f), Ui.dpi(16f), 0); minimumHeight = Ui.dpi(48f) }
        val mid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        mid.addView(Ui.text(ctx, "True Find", 23f, PURPLE))
        kind = Ui.text(ctx, "", 11.5f, SUB).apply { setPadding(0, Ui.dpi(2f), 0, 0) }
        mid.addView(kind)
        top.addView(mid, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        val safe = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(INK, 14f)
            setPadding(Ui.dpi(7f), Ui.dpi(5f), Ui.dpi(10f), Ui.dpi(5f))
        }
        safe.addView(ImageView(ctx).apply { setImageDrawable(PathIcon(PathIcon.SHIELD, Ui.c("#D9C8FF"), 16f)) },
            LinearLayout.LayoutParams(Ui.dpi(16f), Ui.dpi(16f)).apply { rightMargin = Ui.dpi(6f) })
        val st = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        st.addView(Ui.text(ctx, "100% Safe", 11.5f, Color.WHITE))
        st.addView(Ui.text(ctx, "& Secure", 10f, Ui.c("#D9C8FF"), false))
        safe.addView(st)
        top.addView(safe, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        col.addView(top)

        // middle: photo with pulses, text
        val center = LinearLayout(ctx).apply { clipChildren = false; orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(Ui.dpi(24f), 0, Ui.dpi(24f), 0) }
        val ph = FrameLayout(ctx).apply { clipChildren = false }
        pulses = PulseView(ctx, LILAC)
        ph.addView(pulses, LayoutParams(Ui.dpi(140f), Ui.dpi(140f)))
        photo = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(LAV2); setStroke(Ui.dpi(4f), Color.WHITE) }
            setPadding(Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f))
            elevation = Ui.dp(8f)
        }
        ph.addView(photo, LayoutParams(Ui.dpi(140f), Ui.dpi(140f)))
        center.addView(ph, LinearLayout.LayoutParams(Ui.dpi(140f), Ui.dpi(140f)).apply { bottomMargin = Ui.dpi(18f) })
        line = Ui.text(ctx, "", 24f, INK).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }
        center.addView(line)
        sub = Ui.text(ctx, "", 14f, SUB, false).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dpi(6f), 0, 0) }
        center.addView(sub)
        col.addView(center, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // bottom panel
        panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = if (webLook) Doodles.Panel(PANEL, Ui.dp(34f)) else GradientDrawable().apply {
                setColor(PANEL)
                cornerRadii = floatArrayOf(Ui.dp(34f), Ui.dp(34f), Ui.dp(34f), Ui.dp(34f), 0f, 0f, 0f, 0f)
            }
            setPadding(Ui.dpi(22f), Ui.dpi(22f), Ui.dpi(22f), Ui.dpi(26f))
        }
        dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GREEN); setSize(Ui.dpi(7f), Ui.dpi(7f)) }
        status = Ui.text(ctx, "", 12.5f, LILAC_TEXT, false).apply {
            compoundDrawablePadding = Ui.dpi(7f)
            setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null)
        }
        // dcsBlink 1.2 s: the green dot fades to 35 % and back
        blink = if (!webLook) null else ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200; repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                val k = 1f - 0.65f * (if (f < 0.5f) f * 2f else (1f - f) * 2f)
                dot.alpha = (k * 255).toInt()
                status.invalidate()
            }
        }
        panel.addView(status)
        cancelBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val btn = ImageView(ctx).apply {
            setImageDrawable(PathIcon(PathIcon.PHONE, Color.WHITE, 30f))
            rotation = 135f
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(RED); setStroke(Ui.dpi(3f), Color.WHITE) }
            setOnClickListener { onCancel?.invoke() }
            contentDescription = "Cancel"
        }
        Ui.pressable(btn, 0.94f)
        cancelBox.addView(btn, LinearLayout.LayoutParams(Ui.dpi(72f), Ui.dpi(72f)))
        cancelBox.addView(Ui.text(ctx, "Cancel", 13f, Color.WHITE).apply { setPadding(0, Ui.dpi(8f), 0, 0) })
        panel.addView(cancelBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dpi(14f) })
        spacer = View(ctx)
        panel.addView(spacer, LinearLayout.LayoutParams(1, Ui.dpi(96f)).apply { topMargin = Ui.dpi(14f) })
        col.addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private var curStage = "search"
    private var curType = "voice"

    /** stage: "search" or "calling". [title] replaces the first line (Random Match: "Finding someone for you…"). */
    fun show(stage: String, type: String, rate: Int, face: Bitmap?, name: String, title: String? = null) {
        val video = type == "video"
        curStage = stage; curType = type
        kind.text = "${if (video) "Video" else "Voice"} call · $rate coins/min"
        val who = name.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        if (stage == "search") {
            line.text = title ?: if (video) "Finding a video partner…" else "Finding someone to talk…"
            sub.text = "Only people who are online and free"
            status.text = "Searching"
            cancelBox.visibility = View.VISIBLE; spacer.visibility = View.GONE
        } else {
            line.text = title ?: if (who.isNotEmpty()) "Calling $who…" else "Calling…"
            sub.text = "Please wait a moment"
            status.text = "Connecting"
            cancelBox.visibility = View.GONE; spacer.visibility = View.VISIBLE
        }
        setFace(face)
        pulses.start()
        blink?.let { if (!it.isStarted) it.start() }
        if (visibility != View.VISIBLE) {
            visibility = View.VISIBLE
            alpha = 0f; scaleX = 0.97f; scaleY = 0.97f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(400).start()
            if (webLook) {
                // dcsPanel: the purple panel slides up (.5 s, 50 ms after the page)
                panel.translationY = Ui.dp(400f)
                panel.post {
                    panel.translationY = panel.height.toFloat()
                    panel.animate().translationY(0f).setStartDelay(50).setDuration(500)
                        .setInterpolator(android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)).start()
                }
            }
        }
    }

    /** The round picture: the photo, else (searching) the call icon, else the plain default avatar. */
    fun setFace(face: Bitmap?) {
        if (face != null) {
            photo.scaleType = ImageView.ScaleType.CENTER_CROP
            photo.setImageBitmap(face)
        } else if (curStage != "search") {
            photo.scaleType = ImageView.ScaleType.CENTER_CROP
            photo.setImageBitmap(Doodles.defaultAvatar(Ui.dpi(140f)))
        } else {
            photo.scaleType = ImageView.ScaleType.CENTER
            photo.setImageDrawable(PathIcon(if (curType == "video") PathIcon.VIDEO else PathIcon.PHONE, PURPLE, 52f))
        }
    }

    fun hide() {
        pulses.stop()
        blink?.cancel()
        dot.alpha = 255
        panel.animate().cancel()
        panel.translationY = 0f
        visibility = View.GONE
    }

    /** Two lilac circles growing and fading (dcsPulse 1.5 s, second one .75 s later). */
    private class PulseView(ctx: Context, private val color: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private var t = 0f
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
        }
        fun start() { if (!anim.isStarted) anim.start() }
        fun stop() { anim.cancel() }
        override fun onDraw(c: Canvas) {
            val r = width / 2f
            for (d in floatArrayOf(0f, 0.5f)) {
                val f = (t + d) % 1f
                val e = 1f - (1f - f) * (1f - f)   // ease-out
                p.color = color
                p.alpha = (0.5f * (1f - e) * 255).toInt()
                c.drawCircle(r, r, r * (1f + 0.75f * e), p)
            }
        }
    }
}
