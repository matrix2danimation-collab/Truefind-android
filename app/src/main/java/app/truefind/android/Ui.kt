package app.truefind.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/** Small helpers shared by the native screens (sizes in dp = the web's px). */
object Ui {
    var density = 1f
    lateinit var regular: Typeface
    lateinit var bold: Typeface
    lateinit var boldItalic: Typeface
    private var ready = false

    fun init(ctx: Context) {
        density = ctx.resources.displayMetrics.density
        if (ready) return
        ready = true
        // Test branch only: app font sample #143 (russo-one) for the owner, 26 Nov, drawn to the old letter width;
        // bold = Inter SemiBold. (assets/fonts/TrueFindSans-*, based on Inter, OFL.) If a file can't be read the phone's font is used.
        regular = load(ctx, "fonts/TrueFindSans-Regular.ttf") ?: Typeface.SANS_SERIF
        bold = load(ctx, "fonts/TrueFindSans-Bold.ttf") ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        boldItalic = load(ctx, "fonts/TrueFindSans-BoldItalic.ttf") ?: Typeface.create(bold, Typeface.BOLD_ITALIC)
    }

    private fun load(ctx: Context, path: String): Typeface? = try {
        Typeface.createFromAsset(ctx.assets, path)
    } catch (e: Exception) {
        null
    }

    fun dp(v: Float): Float = v * density
    fun dpi(v: Float): Int = (v * density + 0.5f).toInt()

    fun c(hex: String): Int = Color.parseColor(hex)

    /** "#RRGGBB" + alpha 0..1 */
    fun ca(hex: String, a: Float): Int {
        val base = Color.parseColor(hex)
        return Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(base), Color.green(base), Color.blue(base))
    }

    fun text(ctx: Context, s: CharSequence, sizeDp: Float, color: Int, isBold: Boolean = true): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(sizeDp))
            setTextColor(color)
            typeface = if (isBold) bold else regular
            includeFontPadding = false
        }

    fun round(color: Int, radiusDp: Float, strokeColor: Int? = null, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp)
            if (strokeColor != null) setStroke(dpi(strokeDp), strokeColor)
        }

    fun vgrad(top: Int, bottom: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = dp(radiusDp)
            if (strokeColor != null) setStroke(dpi(1f), strokeColor)
        }

    /** Press effect like the web's :active { transform: scale(.96) } */
    fun pressable(v: View, scale: Float = 0.96f) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
            false
        }
    }
}
