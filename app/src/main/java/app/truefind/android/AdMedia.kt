package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PrivateVideo of ui.jsx: the live verification / host video, full width, at most 420 high,
 * with its first picture, play / pause and a seek bar (the web's video controls).
 * The video is only downloaded when Play is tapped; the player is let go when the
 * card leaves the screen.
 */
@SuppressLint("ViewConstructor")
class AdVideo(ctx: Context, private val src: AdSrc) : FrameLayout(ctx) {
    companion object {
        /** the first picture and size of each video, and its signed address (kept while the app runs) */
        private val meta = HashMap<String, Triple<Bitmap?, Int, Int>>()
        private val urls = HashMap<String, Pair<String, Long>>()
        private val reader = java.util.concurrent.Executors.newSingleThreadExecutor()
    }
    private val main = Handler(Looper.getMainLooper())
    private var url = ""
    private var state = "wait"           // wait · failed · ready · loading · playing · paused
    private var vw = 0
    private var vh = 0
    private var seq = 0
    private val poster = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private var tex: TextureView? = null
    private var surface: Surface? = null
    private var mp: MediaPlayer? = null
    private val play = FrameLayout(ctx).apply {
        background = Tf.circle(Color.argb(150, 0, 0, 0))
        addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(AdIc.Play, Color.WHITE, 26f, 2f, "#fff")) }, LayoutParams(Ui.dpi(26f), Ui.dpi(26f), Gravity.CENTER).apply { leftMargin = Ui.dpi(3f) })
    }
    private val spin = TfLoader(ctx, 28f, Color.WHITE)
    private val bar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.argb(110, 0, 0, 0)); setPadding(Ui.dpi(10f), 0, Ui.dpi(6f), 0)
    }
    private val time = AU.t(ctx, "0:00", 11.5f, Color.WHITE, true)
    private val seek = SeekBar(ctx).apply { max = 1000 }
    private val note = AU.t(ctx, "Can't load video", 12f, AK.faint).apply { gravity = Gravity.CENTER }
    private val tick = object : Runnable {
        override fun run() {
            val p = mp ?: return
            try {
                val d = p.duration; val c = p.currentPosition
                if (d > 0 && !dragging) seek.progress = (c * 1000L / d).toInt()
                time.text = "${clock(c)} / ${clock(max(0, d))}"
            } catch (e: Exception) { }
            if (state == "playing") main.postDelayed(this, 250)
        }
    }
    private var dragging = false

    init {
        background = Tf.box(AK.tint, 12f)
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; clipToOutline = true
        addView(poster, LayoutParams(-1, -1))
        addView(play, LayoutParams(Ui.dpi(60f), Ui.dpi(60f), Gravity.CENTER))
        addView(spin, LayoutParams(Ui.dpi(28f), Ui.dpi(28f), Gravity.CENTER))
        bar.addView(time, LinearLayout.LayoutParams(-2, -2))
        bar.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        addView(bar, LayoutParams(-1, Ui.dpi(34f), Gravity.BOTTOM))
        addView(note, LayoutParams(-1, -1))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { if (fromUser) mp?.let { p -> try { time.text = "${clock((p.duration.toLong() * v / 1000).toInt())} / ${clock(p.duration)}" } catch (e: Exception) { } } }
            override fun onStartTrackingTouch(s: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar?) { dragging = false; mp?.let { p -> try { p.seekTo((p.duration.toLong() * (s?.progress ?: 0) / 1000).toInt()) } catch (e: Exception) { } } }
        })
        setOnClickListener { tap() }
        show()
    }

    fun set(raw: String) {
        release()
        val my = ++seq
        state = "wait"; url = ""; vw = 0; vh = 0; poster.setImageDrawable(null); show()
        if (raw.isBlank()) { state = "failed"; show(); return }
        val got = { u: String ->
            if (my == seq) {
                if (u.isBlank()) { state = "failed"; show() }
                else {
                    url = u; state = "ready"
                    meta[raw]?.let { (b, w, h) -> vw = w; vh = h; if (b != null) poster.setImageBitmap(b) }
                    show()
                    if (u.startsWith("http") && !meta.containsKey(raw)) {
                        reader.execute {
                            val r = firstPicture(u)
                            main.post {
                                meta[raw] = Triple(r?.first, r?.second?.first ?: 0, r?.second?.second ?: 0)
                                if (my != seq || r == null) return@post
                                vw = r.second.first; vh = r.second.second
                                if (r.first != null && mp == null) poster.setImageBitmap(r.first)
                                requestLayout()
                            }
                        }
                    }
                }
            }
        }
        val cached = urls[raw]
        if (cached != null && System.currentTimeMillis() - cached.second < 50 * 60_000L) { got(cached.first); return }
        src.signed(raw) { u -> if (u.isNotBlank()) urls[raw] = u to System.currentTimeMillis(); got(u) }
    }

    /** the video's size (turned the right way) and its first picture, like preload="metadata" */
    private fun firstPicture(u: String): Pair<Bitmap?, Pair<Int, Int>>? = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(u, HashMap())
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val f = try { r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } catch (e: Throwable) { null }
            val small = if (f != null && max(f.width, f.height) > 900) Bitmap.createScaledBitmap(f, (f.width * 900f / max(f.width, f.height)).roundToInt(), (f.height * 900f / max(f.width, f.height)).roundToInt(), true) else f
            small to (w to h)
        } finally { try { r.release() } catch (e: Throwable) { } }
    } catch (e: Throwable) { null }

    override fun onMeasure(w: Int, h: Int) {
        val ww = MeasureSpec.getSize(w)
        val hh = when (state) {
            "wait" -> Ui.dpi(200f)
            "failed" -> Ui.dpi(120f)
            else -> if (vw > 0 && vh > 0) min(Ui.dpi(420f), (ww.toLong() * vh / vw).toInt()) else min(Ui.dpi(420f), ww * 9 / 16)
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(ww, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(hh, MeasureSpec.EXACTLY))
    }

    private fun show() {
        val ready = state != "wait" && state != "failed"
        background = Tf.box(if (ready) Color.BLACK else AK.tint, 12f)
        poster.visibility = if (ready && state != "playing" && state != "paused") View.VISIBLE else if (ready && mp == null) View.VISIBLE else View.GONE
        play.visibility = if (state == "ready" || state == "paused") View.VISIBLE else View.GONE
        spin.visibility = if (state == "loading") View.VISIBLE else View.GONE
        bar.visibility = if (state == "playing" || state == "paused") View.VISIBLE else View.GONE
        note.visibility = if (state == "failed") View.VISIBLE else View.GONE
        requestLayout()
    }

    private fun tap() {
        when (state) {
            "ready" -> start()
            "playing" -> { try { mp?.pause() } catch (e: Exception) { }; state = "paused"; show() }
            "paused" -> { try { mp?.start() } catch (e: Exception) { }; state = "playing"; show(); main.post(tick) }
        }
    }

    private fun start() {
        if (!url.startsWith("http")) return // test data: nothing to play
        state = "loading"; show()
        val tv = TextureView(context)
        tex = tv
        addView(tv, 0, LayoutParams(-1, -1))
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { prepare(Surface(st)) }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun prepare(s: Surface) {
        surface = s
        val p = MediaPlayer()
        mp = p
        try {
            p.setDataSource(url)
            p.setSurface(s)
            p.setOnPreparedListener { pl ->
                if (mp !== pl) return@setOnPreparedListener
                if (pl.videoWidth > 0 && pl.videoHeight > 0 && (vw == 0 || vh == 0)) { vw = pl.videoWidth; vh = pl.videoHeight }
                pl.start(); state = "playing"; poster.visibility = View.GONE; show(); main.post(tick)
            }
            p.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) { vw = w; vh = h; requestLayout() } }
            p.setOnCompletionListener { state = "paused"; show() }
            p.setOnErrorListener { _, _, _ -> release(); state = "failed"; show(); true }
            p.prepareAsync()
        } catch (e: Exception) { release(); state = "failed"; show() }
    }

    /** lets the player go (leaving the screen, or a new video) */
    fun release() {
        main.removeCallbacks(tick)
        mp?.let { p -> mp = null; try { p.stop() } catch (e: Exception) { }; try { p.release() } catch (e: Exception) { } }
        surface?.let { s -> surface = null; try { s.release() } catch (e: Exception) { } }
        tex?.let { t -> tex = null; removeView(t) }
        if (state == "playing" || state == "paused" || state == "loading") { state = "ready"; show() }
    }

    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); if (v != View.VISIBLE && state == "playing") tap() }

    private fun clock(ms: Int): String { val s = max(0, ms / 1000); return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
}

/** Zoom of ui.jsx: a photo over the whole screen on black; a tap anywhere or ✕ closes it. */
object AdZoom {
    fun open(layer: TfLayer, src: AdSrc, raw: String) {
        if (raw.isBlank()) return
        val ctx = layer.ctx
        val box = FrameLayout(ctx).apply { setBackgroundColor(Color.argb(230, 0, 0, 0)) }
        val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        box.addView(img, FrameLayout.LayoutParams(-1, -1).apply { val m = Ui.dpi(16f); setMargins(m, m, m, m) })
        val spin = TfLoader(ctx, 28f, Color.WHITE)
        box.addView(spin, FrameLayout.LayoutParams(Ui.dpi(28f), Ui.dpi(28f), Gravity.CENTER))
        val x = FrameLayout(ctx).apply {
            background = Tf.box(Color.argb(46, 255, 255, 255), 11f)
            addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(AdIc.X, Color.WHITE, 18f)) }, FrameLayout.LayoutParams(Ui.dpi(18f), Ui.dpi(18f), Gravity.CENTER))
            contentDescription = "Close"
        }
        box.addView(x, FrameLayout.LayoutParams(Ui.dpi(38f), Ui.dpi(38f), Gravity.TOP or Gravity.END).apply { topMargin = Ui.dpi(14f); rightMargin = Ui.dpi(14f) })
        val close = layer.fade(box, 80f)
        box.setOnClickListener { close() }
        x.setOnClickListener { close() }
        src.full(raw, 2048) { b -> spin.visibility = View.GONE; if (b != null) img.setImageBitmap(b) }
    }
}
