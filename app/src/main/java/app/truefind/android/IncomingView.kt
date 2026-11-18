package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Incoming call: the full-screen True Find screen of the web's IncomingCallWatcher.jsx — premium purple,
 * a soft copy of the caller's photo behind, the photo with two rings, "Incoming voice call", the name,
 * the city, "is calling you...", red Decline and the blue animated Accept (CallPulseIcon).
 */
@SuppressLint("ViewConstructor")
class IncomingView(ctx: Context, private val call: JSONObject, private val onAccept: () -> Unit, private val onDecline: () -> Unit) : FrameLayout(ctx) {
    private val video = Tf.s(call, "type") == "video"
    private val blur = CoverImage(ctx).apply { webBlur = true; alpha = .42f }
    private val face = CallFace(ctx, 156f, 4f)
    private val noteText = CallKit.t(ctx, "", 13.5f, Ui.c("#FDE68A"), 700)
    private val declineBox: LinearLayout
    private val acceptBox: LinearLayout
    private val acceptPulse = CallPulse(ctx, video)
    private val acceptBusy = FrameLayout(ctx)
    private val acceptLabel = CallKit.t(ctx, "Accept", 14f, Color.WHITE, 700)
    private val bottom: LinearLayout
    var busy = false
        set(v) { field = v; paintBusy() }

    init {
        isClickable = true
        clipChildren = false
        val name = CallKit.firstName(Tf.s(call, "caller_name")).ifEmpty { "Someone" }
        contentDescription = "Incoming ${if (video) "video" else "voice"} call from $name"
        addView(GradientView(ctx, intArrayOf(Ui.c("#3B0F7A"), Ui.c("#24074F"), Ui.c("#120428")), floatArrayOf(0f, .46f, 1f), 165f), LayoutParams(-1, -1))
        addView(blur, LayoutParams(-1, -1))
        addView(View(ctx).apply { background = PatternFill(CallKit.INCOMING_DOODLE, 132f, -8f, "incomingDoodle") }, LayoutParams(-1, -1))
        addView(GradientView(ctx, intArrayOf(Color.argb(89, 18, 4, 40), Color.argb(0, 18, 4, 40), Color.argb(191, 12, 3, 28)), floatArrayOf(0f, .35f, 1f)), LayoutParams(-1, -1))

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        addView(col, LayoutParams(-1, -1))
        // top: brand + safety
        val top = Tf.row(ctx).apply { setPadding(Ui.dpi(18f), Ui.dpi(16f), Ui.dpi(18f), 0) }
        val brand = SpannableString("True Find").apply { setSpan(ForegroundColorSpan(Ui.c("#F9A8D4")), 5, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        top.addView(CallKit.t(ctx, brand, 22f, Color.WHITE, 800).apply { letterSpacing = -.2f / 22f }, Tf.ll(0, Tf.W, 1f))
        val safe = Tf.row(ctx).apply {
            background = Tf.box(Color.argb(31, 255, 255, 255), 14f, 1f, Color.argb(51, 255, 255, 255))
            setPadding(Ui.dpi(8f), Ui.dpi(5f), Ui.dpi(11f), Ui.dpi(5f))
            addView(ImageView(ctx).apply { setImageDrawable(CallKit.svg(CallKit.SHIELD, 15f, Ui.c("#C4B5FD"))) }, Tf.ll(Ui.dpi(15f), Ui.dpi(15f), r = 6f))
            addView(CallKit.t(ctx, "100% Safe & Secure", 11.5f, Color.WHITE, 700))
        }
        top.addView(safe)
        col.addView(top)
        // the caller
        val mid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; clipChildren = false; setPadding(Ui.dpi(24f), 0, Ui.dpi(24f), 0) }
        val chip = Tf.row(ctx).apply {
            background = Tf.box(Color.argb(31, 255, 255, 255), 999f, 1f, Color.argb(51, 255, 255, 255))
            setPadding(Ui.dpi(14f), Ui.dpi(6f), Ui.dpi(14f), Ui.dpi(6f))
            addView(CallKit.blinkDot(ctx, Ui.c("#22C55E")), Tf.ll(Ui.dpi(7f), Ui.dpi(7f), r = 7f))
            addView(CallKit.t(ctx, "Incoming ${if (video) "video" else "voice"} call", 13f, Color.WHITE, 700).apply { letterSpacing = .3f / 13f })
        }
        mid.addView(chip, Tf.ll())
        val ph = FrameLayout(ctx).apply { clipChildren = false }
        ph.addView(WaveRings(ctx), LayoutParams(Ui.dpi(156f), Ui.dpi(156f)))
        face.elevation = Ui.dp(18f)
        face.outlineSpotShadowColorCompat(Color.argb(160, 0, 0, 0))
        ph.addView(face, LayoutParams(Ui.dpi(156f), Ui.dpi(156f)))
        val badge = FrameLayout(ctx).apply {
            background = Tf.circle(if (video) Ui.c("#7B2FF7") else Ui.c("#16A34A"), 3f, Color.WHITE)
            elevation = Ui.dp(20f)
            outlineSpotShadowColorCompat(Color.argb(77, 0, 0, 0))
            addView(ImageView(ctx).apply { setImageDrawable(if (video) CallKit.svg(CallKit.VIDEO_GLYPH, 19f) else CallKit.svg(CallKit.PHONE, 18f)) },
                LayoutParams(Ui.dpi(if (video) 19f else 18f), Ui.dpi(if (video) 19f else 18f), Gravity.CENTER))
        }
        ph.addView(badge, LayoutParams(Ui.dpi(40f), Ui.dpi(40f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = Ui.dpi(4f); bottomMargin = Ui.dpi(6f) })
        mid.addView(ph, Tf.ll(Ui.dpi(156f), Ui.dpi(156f), t = 14f + 14f, b = 6f))
        mid.addView(CallKit.t(ctx, name, 32f, Color.WHITE, 800).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(Ui.dp(12f), 0f, Ui.dp(2f), Color.argb(77, 0, 0, 0))
        }, Tf.ll(t = 14f))
        val city = Tf.s(call, "caller_city")
        if (city.isNotEmpty()) mid.addView(CallKit.t(ctx, city, 14.5f, CallKit.LILAC_TEXT, 600), Tf.ll(t = 8f))
        mid.addView(CallingDots(ctx), Tf.ll(t = 14f))
        col.addView(mid, LinearLayout.LayoutParams(-1, 0, 1f))

        // Accept / Decline
        bottom = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; setPadding(Ui.dpi(34f), 0, Ui.dpi(34f), Ui.dpi(38f)) }
        noteText.apply {
            gravity = Gravity.CENTER
            background = Tf.box(Color.argb(36, 255, 255, 255), 14f, 1f, Color.argb(56, 255, 255, 255))
            setPadding(Ui.dpi(14f), Ui.dpi(9f), Ui.dpi(14f), Ui.dpi(9f))
            maxWidth = Ui.dpi(320f)
            visibility = GONE
        }
        bottom.addView(noteText, Tf.ll(g = Gravity.CENTER_HORIZONTAL, b = 16f))
        val row = Tf.row(ctx, Gravity.TOP).apply { clipChildren = false }
        declineBox = Tf.col(ctx, Gravity.CENTER_HORIZONTAL)
        val dec = ImageView(ctx).apply {
            setImageDrawable(CallKit.svg(CallKit.PHONE, 32f)); rotation = 135f; scaleType = ImageView.ScaleType.CENTER
            background = Tf.circle(CallKit.RED)
            elevation = Ui.dp(10f); outlineSpotShadowColorCompat(Color.argb(178, 255, 45, 61))
        }
        declineBox.addView(dec, Tf.ll(Ui.dpi(74f), Ui.dpi(74f)))
        declineBox.addView(CallKit.t(ctx, "Decline", 14f, Color.WHITE, 700), Tf.ll(t = 9f))
        declineBox.contentDescription = "Decline call"
        declineBox.setOnClickListener { if (!busy) onDecline() }
        press(declineBox)
        row.addView(declineBox)
        row.addView(View(ctx), Tf.ll(0, 1, 1f))
        acceptBox = Tf.col(ctx, Gravity.CENTER_HORIZONTAL).apply { clipChildren = false }
        val ab = FrameLayout(ctx).apply { clipChildren = false }
        ab.addView(acceptPulse, LayoutParams(Ui.dpi(74f), Ui.dpi(74f)))
        acceptBusy.background = Tf.circle(CallKit.BLUE)
        acceptBusy.addView(TfLoader(ctx, 30f, Color.WHITE), LayoutParams(Ui.dpi(30f), Ui.dpi(30f), Gravity.CENTER))
        acceptBusy.visibility = GONE
        ab.addView(acceptBusy, LayoutParams(Ui.dpi(74f), Ui.dpi(74f)))
        acceptBox.addView(ab, Tf.ll(Ui.dpi(74f), Ui.dpi(74f)))
        acceptBox.addView(acceptLabel, Tf.ll(t = 9f))
        acceptBox.contentDescription = "Accept call"
        acceptBox.setOnClickListener { if (!busy) onAccept() }
        press(acceptBox)
        row.addView(acceptBox)
        bottom.addView(row, Tf.ll(Tf.M, Tf.W))
        col.addView(bottom)

        // tficIn: fade in from 1.03; tficUp: the buttons rise 26 px
        alpha = 0f; scaleX = 1.03f; scaleY = 1.03f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(350).setInterpolator(CallKit.EASE_OUT).start()
        bottom.alpha = 0f; bottom.translationY = Ui.dp(26f)
        bottom.animate().alpha(1f).translationY(0f).setStartDelay(80).setDuration(500).setInterpolator(CallKit.EASE_OUT_BACK).start()
    }

    private fun press(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(.93f).scaleY(.93f).setDuration(150).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
            false
        }
    }

    fun setPhoto(raw: String) {
        face.setPhoto(raw)
        if (raw.isNotEmpty()) {
            val dm = resources.displayMetrics
            CallPics.blurred(context, raw, dm.widthPixels, dm.heightPixels) { b -> blur.bitmap = b }
        }
    }

    fun note(text: String) {
        noteText.text = text
        noteText.visibility = if (text.isEmpty()) GONE else VISIBLE
    }

    private fun paintBusy() {
        declineBox.alpha = if (busy) .55f else 1f
        acceptBox.alpha = if (busy) .75f else 1f
        acceptPulse.visibility = if (busy) GONE else VISIBLE
        acceptBusy.visibility = if (busy) VISIBLE else GONE
        acceptLabel.text = if (busy) "Connecting…" else "Accept"
    }

    /** "is calling you" and three dots fading in turn (tficDots 1.4 s, .2 s apart) */
    private class CallingDots(ctx: Context) : LoopView(ctx, 1400) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ui.regular; color = CallKit.LILAC_TEXT; textSize = Ui.dp(15f) }
        private val text = "is calling you"
        override fun onMeasure(w: Int, h: Int) {
            val fm = p.fontMetrics
            setMeasuredDimension(Math.ceil((p.measureText(text) + p.measureText("...")).toDouble()).toInt() + 2, Math.ceil((fm.descent - fm.ascent).toDouble()).toInt())
        }
        override fun onDraw(c: Canvas) {
            val y = -p.fontMetrics.ascent
            p.alpha = 255
            c.drawText(text, 0f, y, p)
            var x = p.measureText(text)
            for (i in 0 until 3) {
                val f = ((t - i * .2f / 1.4f) % 1f + 1f) % 1f
                val a = when { f <= .2f -> .25f; f <= .5f -> .25f + .75f * CallKit.EASE_IN_OUT.getInterpolation((f - .2f) / .3f); f <= .8f -> 1f - .75f * CallKit.EASE_IN_OUT.getInterpolation((f - .5f) / .3f); else -> .25f }
                p.alpha = (255 * a).toInt()
                c.drawText(".", x, y, p)
                x += p.measureText(".")
            }
        }
    }

    @Suppress("unused") private val keepTv: Class<*> = TextView::class.java
}

/**
 * The incoming call's ring: the phone's own ringtone (the choice for True Find's calls), looping, and
 * the web's buzz pattern (400 ms, 220 ms pause, 400 ms, every 2.2 s). A phone on silent stays silent,
 * a phone on vibrate only buzzes.
 */
class Ringer(private val ctx: Context) {
    private var player: MediaPlayer? = null
    private var vib: Vibrator? = null
    private var on = false

    fun start() {
        if (on) return
        on = true
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val mode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            try {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                if (uri != null) {
                    val mp = MediaPlayer()
                    mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    mp.setDataSource(ctx, uri)
                    mp.isLooping = true
                    mp.setOnPreparedListener { if (on && player === it) try { it.start() } catch (e: Throwable) { } }
                    mp.setOnErrorListener { _, _, _ -> true }
                    player = mp
                    mp.prepareAsync()
                }
            } catch (e: Throwable) { player?.let { try { it.release() } catch (x: Throwable) { } }; player = null }
        }
        if (mode != AudioManager.RINGER_MODE_SILENT) {
            try {
                val v = if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
                    else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                vib = v
                val pattern = longArrayOf(0, 400, 220, 400, 1180)
                if (v != null && v.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= 26) {
                        @Suppress("DEPRECATION")
                        v.vibrate(VibrationEffect.createWaveform(pattern, 0), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
                    } else @Suppress("DEPRECATION") v.vibrate(pattern, 0)
                }
            } catch (e: Throwable) { }
        }
    }

    fun stop() {
        on = false
        player?.let { p -> try { if (p.isPlaying) p.stop() } catch (e: Throwable) { }; try { p.release() } catch (e: Throwable) { } }
        player = null
        try { vib?.cancel() } catch (e: Throwable) { }
        vib = null
    }
}
