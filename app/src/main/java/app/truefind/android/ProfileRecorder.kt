package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import kotlin.math.min

/**
 * The phone's camera for the live selfie videos: preview on a [PreviewView], record to a file
 * (front camera, with sound), a still from the picture, camera off. Used by Become a Host /
 * Creator and by the verification page.
 */
class CamCore(private val ctx: Context, private val owner: LifecycleOwner, private val preview: PreviewView, private val quality: Quality, private val bitRate: Int) {
    private var provider: ProcessCameraProvider? = null
    private var capture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    var alive = true

    fun start(ready: () -> Unit, fail: () -> Unit) {
        try {
            val f = ProcessCameraProvider.getInstance(ctx)
            f.addListener({
                if (!alive) return@addListener
                try {
                    val p = f.get()
                    provider = p
                    val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                    val rec = Recorder.Builder().setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                        .setTargetVideoEncodingBitRate(bitRate).build()
                    val vc = VideoCapture.withOutput(rec)
                    val cam = if (p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    p.unbindAll()
                    p.bindToLifecycle(owner, cam, pv, vc)
                    capture = vc
                    ready()
                } catch (e: Throwable) { fail() }
            }, ContextCompat.getMainExecutor(ctx))
        } catch (e: Throwable) { fail() }
    }

    /** starts recording into [file]; [done] gets true when there is a usable clip. false = could not start. */
    @SuppressLint("MissingPermission")
    fun record(file: File, done: (Boolean) -> Unit): Boolean {
        val vc = capture ?: return false
        val r = try {
            vc.output.prepareRecording(ctx, FileOutputOptions.Builder(file).build()).withAudioEnabled()
                .start(ContextCompat.getMainExecutor(ctx)) { ev ->
                    if (ev is VideoRecordEvent.Finalize) {
                        recording = null
                        val ok = alive && file.exists() && file.length() > 0 && (!ev.hasError() || ev.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED)
                        if (!ok) file.delete()
                        if (alive) done(ok)
                    }
                }
        } catch (e: Throwable) { null } ?: return false
        recording = r
        return true
    }
    fun stopRecording() { try { recording?.stop() } catch (e: Throwable) { } }

    /** the picture now, the right way round (not the mirror of the preview), as a JPEG */
    fun still(quality: Int = 85): ByteArray? = try {
        val b = preview.bitmap
        if (b == null) null else {
            val m = android.graphics.Matrix().apply { postScale(-1f, 1f) }
            val turned = android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            val out = java.io.ByteArrayOutputStream()
            turned.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
            if (turned !== b) turned.recycle()
            out.toByteArray()
        }
    } catch (e: Throwable) { null }

    fun stop() { try { provider?.unbindAll() } catch (e: Throwable) { }; capture = null }
    fun release() { alive = false; stopRecording(); recording = null; stop() }
}

/**
 * The live selfie video of Become a Host / Become a Creator (VideoRecorder of Profile.jsx):
 * front camera only (no gallery), "Say: …" on the picture, a [seconds]-second recording that
 * stops by itself, then the clip plays back with "Record again". [onChange] gets the clip (or null).
 */
@SuppressLint("ViewConstructor")
class LiveRecorder(ctx: Context, private val owner: LifecycleOwner, private val seconds: Int, private val line: String,
                   private val askPermission: ((Boolean) -> Unit) -> Unit, private val onChange: (File?) -> Unit) : LinearLayout(ctx) {
    private var state = "idle"     // idle | starting | ready | recording | done | error
    private var left = seconds
    private var err = ""
    private var clip: File? = null
    private var alive = true

    private val main = Handler(Looper.getMainLooper())
    private val box = object : FrameLayout(ctx) {
        // aspect 3 : 4, never taller than 340
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            val height = min((width * 4f / 3f).toInt(), Ui.dpi(340f))
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }
    private val preview = PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER }
    private val player = VideoView(ctx)
    private val playIcon = ImageView(ctx)
    private val idle = LinearLayout(ctx)
    private val idleText: TextView
    private val spin = TfLoader(ctx, 26f, Color.WHITE)
    private val badge: TextView
    private val say: TextView
    private val actions = FrameLayout(ctx)

    private val cam = CamCore(ctx, owner, preview, Quality.HD, 2_500_000)

    init {
        orientation = VERTICAL
        box.background = Tf.box(Ui.c("#1B1024"), 18f)
        box.clipToOutline = true
        box.addView(preview, FrameLayout.LayoutParams(-1, -1))
        box.addView(player, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        playIcon.setImageDrawable(Tf.svg("<svg viewBox=\"0 0 64 64\"><circle cx=\"32\" cy=\"32\" r=\"30\" fill=\"#000\" fill-opacity=\".45\"/><path d=\"M26 20v24l19-12z\" fill=\"#fff\"/></svg>", 56f))
        box.addView(playIcon, FrameLayout.LayoutParams(Ui.dpi(56f), Ui.dpi(56f), Gravity.CENTER))
        idle.orientation = VERTICAL; idle.gravity = Gravity.CENTER
        idle.setPadding(Ui.dpi(20f), Ui.dpi(20f), Ui.dpi(20f), Ui.dpi(20f))
        idle.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.Video, Color.WHITE, 34f)) }, LayoutParams(Ui.dpi(34f), Ui.dpi(34f)).apply { bottomMargin = Ui.dpi(10f) })
        idleText = PUi.para(ctx, "", 13.5f, Color.WHITE, 19f).apply { gravity = Gravity.CENTER; alpha = 0.85f }
        idle.addView(idleText)
        box.addView(idle, FrameLayout.LayoutParams(-1, -1))
        box.addView(spin, FrameLayout.LayoutParams(Ui.dpi(26f), Ui.dpi(26f), Gravity.CENTER))
        badge = Tf.t(ctx, "", 13f, Color.WHITE).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Tf.box(PC.red, 999f)
            setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0)
            compoundDrawablePadding = Ui.dpi(6f)
            setCompoundDrawablesWithIntrinsicBounds(DotDrawable(Color.WHITE, 8f), null, null, null)
        }
        box.addView(badge, FrameLayout.LayoutParams(-2, Ui.dpi(28f)).apply { leftMargin = Ui.dpi(12f); topMargin = Ui.dpi(12f) })
        say = PUi.para(ctx, "Say: “$line”", 13.5f, Color.WHITE, 19f, bold = true).apply {
            gravity = Gravity.CENTER
            background = Tf.box(Color.argb(140, 0, 0, 0), 12f)
            setPadding(Ui.dpi(12f), Ui.dpi(10f), Ui.dpi(12f), Ui.dpi(10f))
        }
        box.addView(say, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = Ui.dpi(12f); rightMargin = Ui.dpi(12f); bottomMargin = Ui.dpi(12f) })
        addView(box, LayoutParams(-1, -2))
        addView(actions, LayoutParams(-1, -2).apply { topMargin = Ui.dpi(10f) })
        player.setOnPreparedListener { mp -> mp.isLooping = false; mp.seekTo(1) }
        player.setOnCompletionListener { playIcon.visibility = VISIBLE }
        player.setOnErrorListener { _, _, _ -> true }
        box.setOnClickListener {
            if (state != "done") return@setOnClickListener
            if (player.isPlaying) { player.pause(); playIcon.visibility = VISIBLE } else { player.start(); playIcon.visibility = GONE }
        }
        render()
    }

    private fun render() {
        val live = state == "ready" || state == "recording" || state == "starting"
        preview.visibility = if (live) VISIBLE else GONE
        val showClip = state == "done" && clip != null
        player.visibility = if (showClip) VISIBLE else GONE
        playIcon.visibility = if (showClip && !player.isPlaying) VISIBLE else GONE
        idle.visibility = if (state == "idle" || state == "error") VISIBLE else GONE
        idleText.text = err.ifEmpty { "Record a $seconds-second live selfie video. Gallery videos are not allowed." }
        spin.visibility = if (state == "starting") VISIBLE else GONE
        badge.visibility = if (state == "recording") VISIBLE else GONE
        badge.text = "${left}s"
        say.visibility = if ((state == "ready" || state == "recording") && line.isNotEmpty()) VISIBLE else GONE
        actions.removeAllViews()
        val b: View? = when (state) {
            "idle", "error" -> PUi.btn(context, "Open camera", "primary", Lu.Camera, 18f) { start() }
            "ready" -> PUi.btn(context, "Start $seconds-second recording", "primary", Lu.Video, 18f) { record() }
            "done" -> PUi.btn(context, "Record again", "light", Lu.RefreshCw, 17f) { retake() }
            else -> null
        }
        if (b != null) actions.addView(b, FrameLayout.LayoutParams(-1, Ui.dpi(48f)))
    }

    private fun fail(text: String) { err = text; state = "error"; stopCamera(); render() }

    private fun start() {
        err = ""; state = "starting"; render()
        askPermission { ok ->
            if (!alive) return@askPermission
            if (!ok) { fail("Camera is not available. Please allow camera and microphone, then try again."); return@askPermission }
            cam.start({ state = "ready"; render() }) { fail("Camera is not available. Please allow camera and microphone, then try again.") }
        }
    }

    private fun record() {
        val file = File(context.cacheDir, "tf-video-${System.currentTimeMillis()}.mp4")
        val started = cam.record(file) { ok ->
            main.removeCallbacks(tick)
            if (!alive) { file.delete(); return@record }
            if (!ok) { fail("Video recording is not supported on this phone."); return@record }
            clip = file
            cam.stop()
            state = "done"; render()
            player.setVideoPath(file.absolutePath)
            onChange(file)
        }
        if (!started) { fail("Video recording is not supported on this phone."); return }
        state = "recording"; left = seconds; render()
        main.postDelayed(tick, 1000)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (state != "recording") return
            left -= 1
            render()
            if (left <= 0) cam.stopRecording() else main.postDelayed(this, 1000)
        }
    }

    private fun retake() {
        try { player.stopPlayback() } catch (e: Throwable) { }
        clip?.delete(); clip = null
        onChange(null)
        start()
    }

    private fun stopCamera() = cam.stop()

    /** the panel closed: camera off, recording dropped */
    fun release() {
        alive = false
        main.removeCallbacks(tick)
        cam.release()
        try { player.stopPlayback() } catch (e: Throwable) { }
    }

    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }

    @Suppress("unused") private fun keep(mp: MediaPlayer?) = mp
}
