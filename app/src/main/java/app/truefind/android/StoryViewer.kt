package app.truefind.android

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import org.json.JSONObject

/**
 * Stories: full screen, 6 s each, bars at the top, tap left / right to go
 * back / on, hold to pause. Your own story: views (who saw it) and Delete.
 */
object StoryViewer {
    private const val DUR = 6000L

    @SuppressLint("ClickableViewAccessibility")
    fun open(h: SecretHome, list: List<JSONObject>, start: Int, mine: Boolean) {
        if (list.isEmpty()) return
        val ctx = h.context
        var i = start.coerceIn(0, list.size - 1)
        var paused = false
        var holding = false
        var confirm = false
        var close: () -> Unit = {}

        val root = FrameLayout(ctx)
        // the dark rose background with the photo in the middle (contain)
        root.addView(object : View(ctx) {
            val p = Paint()
            override fun onSizeChanged(w: Int, hh: Int, ow: Int, oh: Int) {
                p.shader = RadialGradient(w / 2f, hh * .4f, maxOf(w, hh) * .7f, Ui.c("#3a0820"), Ui.c("#12000a"), Shader.TileMode.CLAMP)
            }
            override fun onDraw(c: Canvas) { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
        }, FrameLayout.LayoutParams(-1, -1))
        val photo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(photo, FrameLayout.LayoutParams(-1, -1))
        val spin = ProgressBar(ctx).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.c("#FF9BCB")) }
        root.addView(spin, FrameLayout.LayoutParams(dpi(28f), dpi(28f), Gravity.CENTER))
        root.addView(shade(ctx, true), FrameLayout.LayoutParams(-1, dpi(150f), Gravity.TOP))
        // progress bars
        val bars = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val fills = ArrayList<View>()
        for (k in list.indices) {
            val track = FrameLayout(ctx).apply { background = rounded(Color.argb(89, 255, 255, 255), 2f) }
            val fill = View(ctx).apply { background = rounded(Color.WHITE, 2f); pivotX = 0f; scaleX = 0f }
            track.addView(fill, FrameLayout.LayoutParams(-1, -1)); fills.add(fill)
            bars.addView(track, lp(0, dpi(3f), 1f, l = if (k == 0) 0f else 4f))
        }
        root.addView(bars, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dpi(12f), dpi(12f), dpi(12f), 0) })
        // who
        val who = hbox(ctx, 10f)
        val face = RoundPhoto(ctx, 36f)
        val faceRing = FrameLayout(ctx).apply { background = oval(K.pill); setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f)) }
        val faceWhite = FrameLayout(ctx).apply { background = oval(Color.WHITE); setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f)) }
        faceWhite.addView(face, FrameLayout.LayoutParams(-1, -1)); faceRing.addView(faceWhite, FrameLayout.LayoutParams(-1, -1))
        who.addView(faceRing, lp(dpi(40f), dpi(40f)))
        val nameBox = vbox(ctx)
        val name = sdText(ctx, "", 17f, Color.WHITE).oneLine().icon(Seal(16f), 6f, end = true)
        val sub = sdText(ctx, "", 13f, Color.argb(199, 255, 255, 255), bold = false)
        nameBox.addView(name); nameBox.addView(sub)
        who.addView(nameBox, lp(0, WRAP, 1f))
        who.addView(ImageView(ctx).apply { setImageDrawable(Icon(Lucide.X, Color.WHITE, 24f)); scaleType = ImageView.ScaleType.CENTER; background = oval(Color.argb(64, 0, 0, 0)); contentDescription = "Close story" }
            .onTap { close() }, lp(dpi(44f), dpi(44f)))
        root.addView(who, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dpi(14f), dpi(24f), dpi(10f), 0) })
        // caption + owner tools
        val bottom = vbox(ctx, 12f, Gravity.CENTER_HORIZONTAL).apply {
            setPadding(dpi(18f), dpi(48f), dpi(18f), dpi(20f))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(0, 30, 0, 14), Color.argb(219, 30, 0, 14)))
        }
        val bottomWrap = bottom
        val caption = sdText(ctx, "", 19f, Color.WHITE).apply { gravity = Gravity.CENTER; lineHeight(1.2f); shadow(6f, 1f, Color.argb(153, 0, 0, 0)) }
        bottom.addView(caption, lp(MATCH, WRAP))
        val tools = hbox(ctx, 10f, Gravity.CENTER)
        val viewsBtn = sdText(ctx, "", 15f, Color.WHITE).apply { gravity = Gravity.CENTER; background = rounded(Color.argb(46, 255, 255, 255), 22f); setPadding(dpi(18f), 0, dpi(18f), 0) }
        val delBtn = sdText(ctx, "Delete", 15f, Ui.c("#ffd0d6"), bold = false).icon(Icon(Lucide.Trash2, Ui.c("#ffd0d6"), 16f), 7f).apply {
            gravity = Gravity.CENTER; background = rounded(Color.argb(51, 0, 0, 0), 22f, 1f, Color.argb(153, 255, 180, 190)); setPadding(dpi(16f), 0, dpi(16f), 0)
        }
        tools.addView(viewsBtn, lp(WRAP, dpi(44f))); tools.addView(delBtn, lp(WRAP, dpi(44f)))
        if (mine) bottom.addView(tools, lp(MATCH, WRAP))
        root.addView(bottomWrap, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // 6 s per story, counted on the clock (not with an animator): the same when the phone's animations are turned off
        var startedAt = 0L; var spent = 0L; var running = false
        var onEnd: () -> Unit = {}
        val tick = object : Runnable {
            override fun run() {
                if (!running) return
                val t = spent + (android.os.SystemClock.uptimeMillis() - startedAt)
                fills[i].scaleX = (t / DUR.toFloat()).coerceAtMost(1f)
                if (t >= DUR) { running = false; onEnd() } else root.postOnAnimation(this)
            }
        }
        fun pause() { if (!running) return; spent += android.os.SystemClock.uptimeMillis() - startedAt; running = false; root.removeCallbacks(tick) }
        fun resume() { if (running) return; running = true; startedAt = android.os.SystemClock.uptimeMillis(); root.postOnAnimation(tick) }
        fun stop() { running = false; root.removeCallbacks(tick) }
        fun show() {
            val s = list[i]
            stop(); spent = 0L
            for ((k, f) in fills.withIndex()) f.scaleX = if (k < i) 1f else 0f
            name.text = if (mine) "Your story" else "${SdRules.firstName(SdRules.s(s, "name"))}${SdRules.s(s, "age").let { if (it.isNotEmpty()) ", $it" else "" }}"
            sub.text = listOf(if (!mine) SdRules.s(s, "city") else "", SdRules.timeAgo(SdRules.s(s, "created_date"))).filter { it.isNotEmpty() }.joinToString(" · ")
            face.set(listOf(SdRules.s(s, "photo_thumb"), SdRules.s(s, "photo")))
            caption.text = SdRules.s(s, "caption"); caption.visibility = if (caption.text.isEmpty()) View.GONE else View.VISIBLE
            val views = s.optInt("views")
            viewsBtn.text = "$views ${if (views == 1) "view" else "views"}"; viewsBtn.icon(Icon(Lucide.Eye, Color.WHITE, 17f), 7f)
            photo.setImageBitmap(null); spin.visibility = View.VISIBLE
            val id = s.optString("id")
            Photos.loadFull(SdRules.s(s, "media_url", "media_uri"), 1600) { b -> if (list.getOrNull(i)?.optString("id") == id && b != null) { photo.setImageBitmap(b); spin.visibility = View.GONE } }
            if (!mine) h.seeStory(s)
            if (!paused && !confirm && !holding) resume()
        }
        onEnd = { if (i + 1 < list.size) { i++; show() } else close() }
        fun setPaused(p: Boolean) { paused = p; if (p) pause() else if (!confirm && !holding) resume() }

        // tap: left third = back, else on; press and hold = pause
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { holding = true; pause() }
                MotionEvent.ACTION_UP -> {
                    holding = false
                    val held = e.eventTime - e.downTime > 300
                    if (!held) { if (e.x < root.width * 0.33f) { if (i > 0) { i--; show() } else show() } else { if (i + 1 < list.size) { i++; show() } else close() } }
                    else if (!paused && !confirm) resume()
                }
                MotionEvent.ACTION_CANCEL -> { holding = false; if (!paused && !confirm) resume() }
            }
            true
        }
        delBtn.onTap {
            if (!confirm) {
                confirm = true; pause()
                delBtn.text = "Tap to delete"; delBtn.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
                delBtn.setTextColor(Color.WHITE); delBtn.typeface = Ui.bold; delBtn.background = rounded(Ui.c("#c0153d"), 22f)
            } else h.deleteStory { close() }
        }
        viewsBtn.press().onTap {
            setPaused(true)
            val panel = vbox(ctx).apply { background = K.sheet(); setPadding(dpi(18f), dpi(16f), dpi(18f), dpi(18f)) }
            panel.addView(sdText(ctx, "Seen by", 24f, K.wine, italic = true), lp(WRAP, WRAP, b = 10f))
            val wait = ProgressBar(ctx).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(K.rose) }
            panel.addView(wait, lp(dpi(22f), dpi(22f)))
            h.layer.sheet(panel, z = 40f, onClose = { setPaused(false) })
            h.viewersOfMine { vs ->
                panel.removeView(wait)
                if (vs.isEmpty()) panel.addView(sdText(ctx, "No views yet.", 15f, K.sub, bold = false))
                for (v in vs) {
                    val r = hbox(ctx, 12f).apply { setPadding(0, dpi(8f), 0, dpi(8f)) }
                    r.addView(RoundPhoto(ctx, 40f).also { it.set(listOf(SdRules.s(v, "photo"))) }, lp(dpi(40f), dpi(40f)))
                    r.addView(sdText(ctx, SdRules.s(v, "name").ifEmpty { "Member" }, 16f, K.wine2), lp(0, WRAP, 1f))
                    r.addView(sdText(ctx, SdRules.timeAgo(SdRules.s(v, "created_date")), 13f, K.soft, bold = false))
                    panel.addView(r, lp(MATCH, WRAP))
                    panel.addView(View(ctx).apply { setBackgroundColor(K.line2) }, lp(MATCH, dpi(1f)))
                }
            }
        }
        close = h.layer.fade(root, z = 36f, onClose = { stop() })
        show()
    }

    /** the dark fade at the top (down) or at the bottom (up) */
    private fun shade(ctx: android.content.Context, top: Boolean) = object : View(ctx) {
        val p = Paint()
        override fun onSizeChanged(w: Int, hh: Int, ow: Int, oh: Int) {
            val a = Color.argb(if (top) 168 else 0, 30, 0, 14); val b = Color.argb(if (top) 0 else 219, 30, 0, 14)
            p.shader = LinearGradient(0f, 0f, 0f, hh.toFloat(), a, b, Shader.TileMode.CLAMP)
        }
        override fun onDraw(c: Canvas) { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
    }
}
