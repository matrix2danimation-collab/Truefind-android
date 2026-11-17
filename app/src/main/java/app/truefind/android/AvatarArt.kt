package app.truefind.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.PathParser

/**
 * The drawn cartoon faces the web Explore shows for people without a photo
 * (and for the demo profiles): the same 30 looks per gender, the same colours,
 * drawn from the same shapes (port of avatarSvg in Explore.jsx).
 */
object AvatarArt {
    private val SK = listOf("#ffe0c8" to "#f2bf9a", "#f6cda8" to "#e0a57c", "#e3ac80" to "#c9895c", "#c98e62" to "#a86d45", "#a96f48" to "#8a5434", "#f9d6bb" to "#e9b38f")
    private val HR = listOf("#2b1a14" to "#120a07", "#3d2418" to "#1c0f09", "#5a321c" to "#2e170b", "#1a1a1f" to "#050507", "#6e3b1f" to "#3b1d0d", "#46281a" to "#20110a")
    private val BG = listOf("#fde68a" to "#fbbf24", "#bbf7d0" to "#4ade80", "#bfdbfe" to "#60a5fa", "#fbcfe8" to "#f472b6", "#ddd6fe" to "#a78bfa", "#fed7aa" to "#fb923c", "#a5f3fc" to "#22d3ee", "#fecdd3" to "#fb7185")
    private val CL = listOf("#8b5cf6" to "#6d28d9", "#ec4899" to "#be185d", "#0ea5e9" to "#0369a1", "#22c55e" to "#15803d", "#f59e0b" to "#b45309", "#f43f5e" to "#be123c", "#14b8a6" to "#0f766e", "#a855f7" to "#7e22ce")
    private val EYE = listOf("#3b2314", "#1f2937", "#4b5320", "#5b3a1e")

    private val GIRL_BACK = listOf(
        "M22 50c0-22 12-34 28-34s28 12 28 34l3 38H19z",
        "M24 48c0-20 11-31 26-31s26 11 26 31c0 14 4 24 8 32H16c4-8 8-18 8-32z",
        "M28 46c0-18 10-27 22-27s22 9 22 27v6H28z",
        "M23 46c0-20 12-30 27-30s27 10 27 30c0 18-3 30-6 40H29c-3-10-6-22-6-40z",
        "M26 48c0-19 10-29 24-29s24 10 24 29v20c0 6-4 10-8 12H34c-4-2-8-6-8-12z",
    )
    private val GIRL_FRONT = listOf(
        "M31 46c1-15 9-23 19-23 11 0 19 8 19 23-5-8-11-12-19-12-4 0-7 3-10 6-3 2-6 4-9 6z",
        "M31 47c0-15 8-24 19-24s19 9 19 24c-3-7-8-11-14-12l-5 5-4-5c-7 1-12 5-15 12z",
        "M31 45c1-14 9-21 19-21s18 7 19 21c-5-6-11-9-19-9s-14 3-19 9z",
        "M31 48c0-16 9-25 19-25 11 0 19 9 19 25-6-10-13-14-24-13-6 1-10 6-14 13z",
        "M31 46c0-14 9-22 19-22s19 8 19 22c-4-5-9-8-15-8 2 3 2 5 2 7-5-5-12-6-25 1z",
    )
    private const val BOY_FRONT = "M32 46c0-15 8-22 18-22s18 7 18 22c-2-6-6-9-10-10-3 2-10 3-14 1-6 1-10 4-12 9z"
    private const val BEARD = "M35 56c1 12 8 17 15 17s14-5 15-17c-2 4-6 7-9 7-2-2-4-3-6-3s-4 1-6 3c-3 0-7-3-9-7z"

    private val paths = HashMap<String, Path>()
    private fun p(d: String): Path = paths.getOrPut(d) { PathParser.createPathFromPathData(d) }
    private fun col(h: String) = Color.parseColor(h)
    private fun cola(h: String, a: Float): Int { val c = col(h); return Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c)) }

    private val cache = HashMap<String, Bitmap>()

    /** Same look the web picks for this person (key = gender + hash % 30). */
    fun forPerson(person: Person, size: Int): Bitmap = forKey(person.id.ifEmpty { person.name }, person.gender != "male", size)

    fun forKey(key: String, girl: Boolean, size: Int, round: Boolean = true): Bitmap {
        val i = Person.hashStr(key) % 30
        val k = "${if (girl) "g" else "b"}$i|$size|$round"
        return cache.getOrPut(k) { val b = draw(i, girl, size); if (round) Photos.circle(b) else b }
    }

    private fun draw(i: Int, girl: Boolean, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(size / 100f, size / 100f)
        val s = SK[i % 6]; val h = HR[(i * 5) % 6]; val b = BG[(i * 3) % 8]; val cl = CL[(i * 7 + 1) % 8]
        val st = i % 5; val eye = EYE[i % 4]
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

        fun solid(color: Int): Paint { fill.shader = null; fill.color = color; return fill }
        val hairShader = LinearGradient(0f, 16f, 0f, 90f, col(h.first), col(h.second), Shader.TileMode.CLAMP)
        fun hair(): Paint { fill.color = Color.BLACK; fill.shader = hairShader; return fill }

        // background
        fill.shader = RadialGradient(50f, 30f, 80f, col(b.first), col(b.second), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 100f, 100f, fill)
        c.drawCircle(22f, 20f, 14f, solid(Color.argb(46, 255, 255, 255)))
        if (girl) c.drawPath(p(GIRL_BACK[st]), hair())
        // clothes
        fill.color = Color.BLACK
        fill.shader = LinearGradient(0f, 74f, 0f, 100f, col(cl.first), col(cl.second), Shader.TileMode.CLAMP)
        c.drawPath(p("M16 100c2-17 16-26 34-26s32 9 34 26z"), fill)
        stroke.color = Color.argb(89, 255, 255, 255); stroke.strokeWidth = 1.6f
        c.drawPath(p("M40 75l10 9 10-9"), stroke)
        // neck, face, ears
        c.drawPath(p("M43 63h14v12q-7 5-14 0z"), solid(col(s.second)))
        fill.color = Color.BLACK
        fill.shader = RadialGradient(48.2f, 46f, 27f, col(s.first), col(s.second), Shader.TileMode.CLAMP)
        c.drawOval(RectF(32f, 30f, 68f, 72f), fill)
        solid(col(s.second))
        c.drawOval(RectF(29.3f, 48.6f, 35.7f, 57.4f), fill)
        c.drawOval(RectF(64.3f, 48.6f, 70.7f, 57.4f), fill)
        // front hair
        c.drawPath(p(if (girl) GIRL_FRONT[st] else BOY_FRONT), hair())
        if (girl) {
            stroke.color = Color.argb(56, 255, 255, 255); stroke.strokeWidth = 2.5f
            c.drawPath(p("M40 27q8-4 17 0"), stroke)
        }
        // eyes
        solid(Color.WHITE)
        c.drawOval(RectF(39.8f, 48f, 46.2f, 54f), fill)
        c.drawOval(RectF(53.8f, 48f, 60.2f, 54f), fill)
        solid(col(eye))
        c.drawCircle(43.4f, 51.2f, 2.1f, fill); c.drawCircle(57.4f, 51.2f, 2.1f, fill)
        solid(Color.WHITE)
        c.drawCircle(44.2f, 50.4f, 0.75f, fill); c.drawCircle(58.2f, 50.4f, 0.75f, fill)
        if (girl) {
            stroke.color = col("#1a1a1a"); stroke.strokeWidth = 1.1f; stroke.strokeCap = Paint.Cap.BUTT
            c.drawPath(p("M39.5 49q3.5-2.6 7 0M53.5 49q3.5-2.6 7 0"), stroke)
            stroke.strokeCap = Paint.Cap.ROUND
        }
        // brows, nose, cheeks
        stroke.color = col(h.second); stroke.strokeWidth = if (girl) 1.4f else 2f
        c.drawPath(p("M39 45.5q4-2.2 7.5-.6M53.5 44.9q3.5-1.6 7.5.6"), stroke)
        stroke.color = col(s.second); stroke.strokeWidth = 1.4f
        c.drawPath(p("M50 53v4.5q-1.5 1-2.5.5"), stroke)
        solid(cola("#fb7185", 0.28f))
        c.drawOval(RectF(34.4f, 57f, 41.6f, 61f), fill)
        c.drawOval(RectF(58.4f, 57f, 65.6f, 61f), fill)
        // extras
        if (girl) {
            c.drawCircle(50f, 40.5f, 1.5f, solid(col("#e11d48")))
            if (st % 2 == 0) {
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.6f; color = col("#d97706") }
                solid(col("#fcd34d"))
                c.drawCircle(31.5f, 58f, 2.6f, fill); c.drawCircle(31.5f, 58f, 2.6f, ring)
                c.drawCircle(68.5f, 58f, 2.6f, fill); c.drawCircle(68.5f, 58f, 2.6f, ring)
            }
            if (st == 3) {
                val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.6f; color = col("#f472b6") }
                c.drawPath(p("M31 30q19-10 38 0"), band)
            }
            c.drawPath(p("M44.5 61.5q5.5 3.5 11 0q-5.5 1-11 0z"), solid(cola("#e11d48", 0.75f)))
        } else {
            if (st % 2 == 1) {
                hair(); fill.alpha = 230
                c.drawPath(p(BEARD), fill)
                fill.alpha = 255
            }
            if (st == 2) {
                val glass = Paint(Paint.ANTI_ALIAS_FLAG)
                glass.color = Color.argb(38, 255, 255, 255)
                c.drawRoundRect(RectF(36f, 46f, 47f, 54f), 3.5f, 3.5f, glass)
                c.drawRoundRect(RectF(53f, 46f, 64f, 54f), 3.5f, 3.5f, glass)
                glass.style = Paint.Style.STROKE; glass.color = col("#111111"); glass.strokeWidth = 1.6f
                c.drawRoundRect(RectF(36f, 46f, 47f, 54f), 3.5f, 3.5f, glass)
                c.drawRoundRect(RectF(53f, 46f, 64f, 54f), 3.5f, 3.5f, glass)
                glass.strokeWidth = 1.4f
                c.drawLine(47f, 49.5f, 53f, 49.5f, glass)
            }
            stroke.color = col("#7c2d12"); stroke.strokeWidth = 1.8f
            c.drawPath(p("M45 62q5 3 10 0"), stroke)
        }
        return bmp
    }
}
