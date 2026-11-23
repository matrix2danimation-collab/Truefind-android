package app.truefind.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Stories, as on Instagram (owner, 23 Nov, from his screen recording): tap a ring and that person's story opens full
 * screen; the stories then run on by themselves, person after person — tap the right side for the next story, the left
 * side for the one before, hold to pause. Each person has their own bars at the top (one for each of their stories);
 * from one person to the next the page turns like a cube, and swiping left / right turns it by hand. Swipe down to
 * close (or the phone's Back) — there is no ✕ (owner, 23 Nov night: "उसकी जरूरत नहीं है"). 6 s a story. Your own story:
 * who saw it, and Delete.
 */
object StoryViewer {
    private const val DUR = 6000L

    private fun keyOf(s: JSONObject) = SdRules.s(s, "profile_id").ifEmpty { SdRules.s(s, "user_id") }.ifEmpty { SdRules.s(s, "name") + "|" + SdRules.s(s, "photo") }

    /** the stories by person, in the row's order — a person's stories together */
    fun groups(list: List<JSONObject>): List<List<JSONObject>> {
        val out = ArrayList<ArrayList<JSONObject>>()
        val at = HashMap<String, Int>()
        for (s in list) { val k = keyOf(s); val g = at[k]; if (g != null) out[g].add(s) else { at[k] = out.size; out.add(arrayListOf(s)) } }
        return out
    }

    @SuppressLint("ClickableViewAccessibility")
    fun open(h: SecretHome, list: List<JSONObject>, start: Int, mine: Boolean) {
        if (list.isEmpty()) return
        val ctx = h.context
        val gs = groups(list)
        val first = list[start.coerceIn(0, list.size - 1)]
        var gi = gs.indexOfFirst { g -> g.any { it === first } }.coerceAtLeast(0)
        // the story each person is at: the tapped one; for the others their first not yet seen
        val at = IntArray(gs.size) { k -> gs[k].indexOfFirst { !it.optBoolean("seen") }.coerceAtLeast(0) }
        at[gi] = gs[gi].indexOfFirst { it === first }.coerceAtLeast(0)

        var paused = false; var holding = false; var confirm = false; var moving = false
        var close: () -> Unit = {}

        val root = FrameLayout(ctx).apply { setBackgroundColor(Rs.c("#0B0206")) }
        val pages = HashMap<Int, Page>()
        var pos = gi.toFloat()
        var anim: ValueAnimator? = null

        fun place() {
            val w = root.width.toFloat().takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels.toFloat()
            for ((k, p) in pages) {
                val q = k - pos
                if (abs(q) >= 1f) { p.visibility = View.INVISIBLE; continue }
                p.visibility = View.VISIBLE
                // the cube (CubeOut): each page turns about the edge it shares with its neighbour
                p.translationX = q * w
                p.pivotX = if (q < 0) w else 0f
                p.pivotY = p.height / 2f
                p.rotationY = 90f * q
                p.dim.alpha = min(1f, abs(q) * 1.3f) * .55f
            }
        }
        fun page(k: Int): Page = pages.getOrPut(k) {
            Page(ctx, gs[k], mine).also { p ->
                p.cameraDistance = 9000f * Ui.density
                root.addView(p, FrameLayout.LayoutParams(-1, -1))
                p.show(at[k])
            }
        }
        fun trim() { for (k in pages.keys.toList()) if (abs(k - gi) > 1) { root.removeView(pages[k]); pages.remove(k) } }

        // 6 s per story, counted on the clock (the same when the phone's animations are turned off)
        var startedAt = 0L; var spent = 0L; var running = false
        var onEnd: () -> Unit = {}
        val tick = object : Runnable {
            override fun run() {
                if (!running) return
                val t = spent + (android.os.SystemClock.uptimeMillis() - startedAt)
                pages[gi]?.fill(at[gi], t / DUR.toFloat())
                if (t >= DUR) { running = false; onEnd() } else root.postOnAnimation(this)
            }
        }
        fun canRun() = !paused && !confirm && !holding && !moving
        fun pause() { if (!running) return; spent += android.os.SystemClock.uptimeMillis() - startedAt; running = false; root.removeCallbacks(tick) }
        fun resume() { if (running || !canRun()) return; running = true; startedAt = android.os.SystemClock.uptimeMillis(); root.postOnAnimation(tick) }
        fun stop() { running = false; root.removeCallbacks(tick) }
        fun show() {
            stop(); spent = 0L
            val p = page(gi)
            p.show(at[gi]); p.fill(at[gi], 0f)
            if (!mine) h.seeStory(gs[gi][at[gi]])
            resume()
        }
        /** turn the cube to person [to] (from where it stands now), then their story runs */
        fun turnTo(to: Int) {
            if (to !in gs.indices) { close(); return }
            stop(); moving = true
            val target = page(to); target.show(at[to]); target.fill(at[to], 0f)
            val from = pos
            anim?.cancel()
            val done = { moving = false; pos = to.toFloat(); place(); val same = to == gi; gi = to; trim(); if (same) resume() else show() }
            if (PageFx.off(ctx) || from == to.toFloat()) { done(); return }
            anim = ValueAnimator.ofFloat(from, to.toFloat()).apply {
                duration = (420 * abs(to - from)).toLong().coerceIn(140, 420)
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { pos = it.animatedValue as Float; place() }
                addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { done() } })
                start()
            }
        }
        fun next() { if (at[gi] + 1 < gs[gi].size) { at[gi]++; show() } else if (gi + 1 < gs.size) turnTo(gi + 1) else close() }
        fun prev() { if (at[gi] > 0) { at[gi]--; show() } else if (gi > 0) turnTo(gi - 1) else show() }
        onEnd = { next() }

        // tap: left third = back, else on · hold = pause · swipe left / right = the next / the person before · swipe down = close
        var downX = 0f; var downY = 0f; var axis = 0; var lastX = 0f; var lastT = 0L; var vx = 0f
        val slop = Ui.dp(10f)
        root.setOnTouchListener { _, e ->
            val w = max(1, root.width).toFloat()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (moving) return@setOnTouchListener true
                    downX = e.rawX; downY = e.rawY; axis = 0; lastX = e.rawX; lastT = e.eventTime; vx = 0f
                    holding = true; pause()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (moving && axis == 0) return@setOnTouchListener true
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (axis == 0 && (abs(dx) > slop || abs(dy) > slop)) axis = if (abs(dx) > abs(dy)) 1 else if (dy > 0) 2 else 3
                    if (axis == 1) {
                        val dt = max(1L, e.eventTime - lastT).toFloat()
                        vx = vx * .4f + ((e.rawX - lastX) / dt) * .6f; lastX = e.rawX; lastT = e.eventTime
                        var d = dx
                        if ((d > 0 && gi == 0) || (d < 0 && gi == gs.size - 1)) d *= .35f      // nobody on that side: it gives a little
                        if (gi > 0) page(gi - 1); if (gi + 1 < gs.size) page(gi + 1)
                        pos = gi - d / w; place()
                    }
                    if (axis == 2) {
                        val d = max(0f, dy)
                        root.translationY = d
                        val s = 1f - min(1f, d / max(1, root.height)) * .14f
                        root.scaleX = s; root.scaleY = s
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holding = false
                    val up = e.actionMasked == MotionEvent.ACTION_UP
                    when (axis) {
                        0 -> {
                            if (moving) return@setOnTouchListener true
                            val held = e.eventTime - e.downTime > 300
                            if (up && !held) { if (e.x < w * .33f) prev() else next() } else resume()
                        }
                        1 -> {
                            val d = pos - gi
                            val flick = abs(vx) > .5f * Ui.density
                            val to = when {
                                d > .22f || (flick && vx < 0 && d > .04f) -> gi + 1
                                d < -.22f || (flick && vx > 0 && d < -.04f) -> gi - 1
                                else -> gi
                            }
                            when {
                                to >= gs.size -> close()                 // past the last person: the stories close
                                to < 0 -> turnTo(gi)
                                else -> turnTo(to)
                            }
                        }
                        2 -> {
                            if (e.rawY - downY > Ui.dp(110f)) close()
                            else root.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(200).withEndAction { resume() }.start()
                        }
                        else -> resume()
                    }
                    axis = 0
                }
            }
            true
        }

        // your own story: Delete, and who saw it
        if (mine) {
            val p = page(gi)
            p.delBtn.onTap {
                if (!confirm) {
                    confirm = true; pause()
                    p.delBtn.text = "Tap to delete"; p.delBtn.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
                    p.delBtn.setTextColor(Color.WHITE); p.delBtn.typeface = Ui.bold; p.delBtn.background = rounded(Rs.c("#c0153d"), 22f)
                } else h.deleteStory { close() }
            }
            p.viewsBtn.press().onTap {
                paused = true; pause()
                val panel = vbox(ctx).apply { background = K.sheet(); setPadding(dpi(18f), dpi(16f), dpi(18f), dpi(18f)) }
                panel.addView(sdText(ctx, "Seen by", 24f, K.wine, serif = true), lp(WRAP, WRAP, b = 10f))
                val wait = ProgressBar(ctx).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(K.rose) }
                panel.addView(wait, lp(dpi(22f), dpi(22f)))
                h.layer.sheet(panel, z = 40f, onClose = { paused = false; resume() })
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
        }
        close = h.layer.fade(root, z = 36f, onClose = { stop(); anim?.cancel(); h.storiesClosed() })
        root.post { place() }
        place()
        show()
    }

    /** one person's stories: the photo, their bars, who, the caption (and, on your own, Delete and the views) */
    @SuppressLint("ViewConstructor")
    private class Page(ctx: Context, val stories: List<JSONObject>, val mine: Boolean) : FrameLayout(ctx) {
        val photo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val spin = ProgressBar(ctx).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Rs.c("#FF9BCB")) }
        private val fills = ArrayList<View>()
        private val face = RoundPhoto(ctx, 32f)
        private val name = sdText(ctx, "", 16f, Color.WHITE).oneLine().icon(Seal(15f), 5f, end = true).shadow(4f, 1f, Rs.argb(120, 0, 0, 0))
        private val sub = sdText(ctx, "", 13f, Rs.argb(210, 255, 255, 255), bold = false).oneLine().shadow(4f, 1f, Rs.argb(120, 0, 0, 0))
        private val caption = sdText(ctx, "", 19f, Color.WHITE).apply { gravity = Gravity.CENTER; lineHeight(1.2f); shadow(6f, 1f, Rs.argb(153, 0, 0, 0)) }
        val viewsBtn = sdText(ctx, "", 15f, Color.WHITE).apply { gravity = Gravity.CENTER; background = rounded(Rs.argb(46, 255, 255, 255), 22f); setPadding(dpi(18f), 0, dpi(18f), 0) }
        val delBtn = sdText(ctx, "Delete", 15f, Rs.c("#ffd0d6"), bold = false).icon(Icon(Lucide.Trash2, Rs.c("#ffd0d6"), 16f), 7f).apply {
            gravity = Gravity.CENTER; background = rounded(Rs.argb(51, 0, 0, 0), 22f, 1f, Rs.argb(153, 255, 180, 190)); setPadding(dpi(16f), 0, dpi(16f), 0)
        }
        /** the cube's shade, darker the further the page has turned */
        val dim = View(ctx).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        private var shownId = ""

        init {
            // the page: rounded, the photo over a dark rose ground (a photo not as tall as the screen sits in its middle)
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, Ui.dp(14f)) } }
            clipToOutline = true
            addView(object : View(ctx) {
                val p = Paint()
                override fun onSizeChanged(w: Int, hh: Int, ow: Int, oh: Int) {
                    p.shader = RadialGradient(w / 2f, hh * .4f, maxOf(w, hh) * .7f, Rs.c("#3a0820"), Rs.c("#12000a"), Shader.TileMode.CLAMP)
                }
                override fun onDraw(c: Canvas) { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
            }, LayoutParams(-1, -1))
            addView(photo, LayoutParams(-1, -1))
            addView(spin, LayoutParams(dpi(28f), dpi(28f), Gravity.CENTER))
            addView(shade(ctx, true), LayoutParams(-1, dpi(140f), Gravity.TOP))
            // the bars: one for each of this person's stories
            val bars = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in stories.indices) {
                val track = FrameLayout(ctx).apply { background = rounded(Rs.argb(89, 255, 255, 255), 2f) }
                val fill = View(ctx).apply { background = rounded(Color.WHITE, 2f); pivotX = 0f; scaleX = 0f }
                track.addView(fill, LayoutParams(-1, -1)); fills.add(fill)
                bars.addView(track, lp(0, dpi(2.5f), 1f, l = if (k == 0) 0f else 4f))
            }
            addView(bars, LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dpi(10f), dpi(10f), dpi(10f), 0) })
            // who: their photo, name, city · how long ago
            val who = hbox(ctx, 9f)
            val ring = FrameLayout(ctx).apply { background = oval(Color.WHITE); setPadding(dpi(1.5f), dpi(1.5f), dpi(1.5f), dpi(1.5f)) }
            ring.addView(face, LayoutParams(-1, -1))
            who.addView(ring, lp(dpi(35f), dpi(35f)))
            val nameBox = vbox(ctx)
            nameBox.addView(name, lp(WRAP, WRAP)); nameBox.addView(sub, lp(WRAP, WRAP))
            who.addView(nameBox, lp(0, WRAP, 1f))
            addView(who, LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dpi(12f), dpi(22f), dpi(12f), 0) })
            // the caption (and your own story's tools) at the foot
            val bottom = vbox(ctx, 12f, Gravity.CENTER_HORIZONTAL).apply {
                setPadding(dpi(18f), dpi(48f), dpi(18f), dpi(26f))
                background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Rs.argb(0, 30, 0, 14), Rs.argb(200, 30, 0, 14)))
            }
            bottom.addView(caption, lp(MATCH, WRAP))
            if (mine) {
                val tools = hbox(ctx, 10f, Gravity.CENTER)
                tools.addView(viewsBtn, lp(WRAP, dpi(44f))); tools.addView(delBtn, lp(WRAP, dpi(44f)))
                bottom.addView(tools, lp(MATCH, WRAP))
            }
            addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM))
            addView(dim, LayoutParams(-1, -1))
        }

        /** the bars: done before story [k], [f] of [k] */
        fun fill(k: Int, f: Float) { for ((j, v) in fills.withIndex()) v.scaleX = if (j < k) 1f else if (j == k) f.coerceIn(0f, 1f) else 0f }

        fun show(k: Int) {
            val s = stories.getOrNull(k) ?: return
            fill(k, 0f)
            name.text = if (mine) "Your story" else "${SdRules.firstName(SdRules.s(s, "name"))}${SdRules.s(s, "age").let { if (it.isNotEmpty()) ", $it" else "" }}"
            sub.text = listOf(if (!mine) SdRules.s(s, "city") else "", SdRules.timeAgo(SdRules.s(s, "created_date"))).filter { it.isNotEmpty() }.joinToString(" · ")
            face.set(listOf(SdRules.s(s, "photo_thumb"), SdRules.s(s, "photo")))
            caption.text = SdRules.s(s, "caption"); caption.visibility = if (caption.text.isEmpty()) View.GONE else View.VISIBLE
            val views = s.optInt("views")
            viewsBtn.text = "$views ${if (views == 1) "view" else "views"}"; viewsBtn.icon(Icon(Lucide.Eye, Color.WHITE, 17f), 7f)
            val id = s.optString("id")
            if (id == shownId) return
            shownId = id
            photo.setImageBitmap(null); spin.visibility = View.VISIBLE
            Photos.loadFull(SdRules.s(s, "media_url", "media_uri"), 1600) { b ->
                if (shownId == id && b != null) {
                    // a tall photo fills the page, as on Instagram; a wider one is shown whole
                    photo.scaleType = if (b.height >= b.width * 1.45f) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                    photo.setImageBitmap(b); spin.visibility = View.GONE
                }
            }
        }
    }

    /** the dark fade at the top (down) or at the bottom (up) */
    private fun shade(ctx: Context, top: Boolean) = object : View(ctx) {
        val p = Paint()
        override fun onSizeChanged(w: Int, hh: Int, ow: Int, oh: Int) {
            val a = Rs.argb(if (top) 150 else 0, 30, 0, 14); val b = Rs.argb(if (top) 0 else 219, 30, 0, 14)
            p.shader = LinearGradient(0f, 0f, 0f, hh.toFloat(), a, b, Shader.TileMode.CLAMP)
        }
        override fun onDraw(c: Canvas) { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p) }
    }
}
