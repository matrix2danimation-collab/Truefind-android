package app.truefind.android

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.view.View

/**
 * Calls that only exist on newer Android, made safe for every phone the app installs on (Android 7.0 and up).
 * Without these, Android 7 / 7.1 phones crash on the sign-in screens (autofill is Android 8+), and
 * Android 7 – 8.1 phones crash when a chat or Secret Dating screen draws bold or regular words (TypefaceSpan(Typeface) is Android 9+).
 */
object Compat {
    /** autofill hints (Android 8+); older phones simply don't autofill */
    fun autofill(v: View, vararg hints: String) {
        if (Build.VERSION.SDK_INT >= 26) v.setAutofillHints(*hints)
    }

    /** "this field matters for autofill" (Android 8+) */
    fun autofillYes(v: View) {
        if (Build.VERSION.SDK_INT >= 26) v.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
    }

    /** a span that draws text in [tf]: Android's own on Android 9+, the same effect by hand before */
    fun typefaceSpan(tf: Typeface): Any =
        if (Build.VERSION.SDK_INT >= 28) android.text.style.TypefaceSpan(tf) else FontSpan(tf)

    private class FontSpan(private val tf: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) = apply(tp)
        override fun updateMeasureState(tp: TextPaint) = apply(tp)
        private fun apply(p: Paint) {
            val old = p.typeface
            val oldStyle = old?.style ?: Typeface.NORMAL
            val fake = oldStyle and tf.style.inv()
            if (fake and Typeface.BOLD != 0) p.isFakeBoldText = true
            if (fake and Typeface.ITALIC != 0) p.textSkewX = -0.25f
            p.typeface = tf
        }
    }
}
