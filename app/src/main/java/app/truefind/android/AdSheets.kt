package app.truefind.android

import android.graphics.Color
import android.view.Gravity

/**
 * AskSheet of ui.jsx: asks for a short reason / note (with ready-made answers to tap), then
 * runs [onConfirm] with the trimmed text. [onConfirm] calls back done(true) to close the sheet
 * (a failed save keeps it open, the web's way).
 */
class AdAsk(
    private val a: AdminScreen, private val title: String, private val text: String?, private val presets: List<String>,
    private val label: String = "Reason", private val confirmLabel: String = "Confirm", private val danger: Boolean = false,
    private val min: Int = 3, private val placeholder: String = "", private val onConfirm: (String, (Boolean) -> Unit) -> Unit,
) {
    fun open(): AdSheet {
        val ctx = a.context
        val sh = AdSheet(a.layer, title)
        val b = sh.body
        if (!text.isNullOrEmpty()) b.addView(AU.t(ctx, text, 13.5f, AK.sub, false, 19f), AU.lp(-1, -2, b = 12f))
        val area = AU.area(ctx, placeholder, 3)
        val go = AU.btn(ctx, confirmLabel, if (danger) "danger" else "dark")
        val chips = ArrayList<android.widget.TextView>()
        fun paintChips() {
            val v = area.value
            presets.forEachIndexed { i, p ->
                val on = v == p
                chips[i].background = Tf.box(if (on) AK.tint else Color.WHITE, 999f, 1.5f, if (on) AK.purple else AK.line)
                chips[i].setTextColor(if (on) AK.deep else AK.sub)
            }
        }
        if (presets.isNotEmpty()) {
            val flow = FlowRow(ctx, Ui.dpi(6f), Ui.dpi(6f))
            for (p in presets) {
                val c = AU.t(ctx, p, 12.5f, AK.sub, true).apply {
                    gravity = Gravity.CENTER; setPadding(Ui.dpi(10f), 0, Ui.dpi(10f), 0); height = Ui.dpi(30f)
                    setOnClickListener { area.setText(p); area.setSelection(area.value.length) }
                }
                chips.add(c); flow.addView(c)
            }
            b.addView(flow, AU.lp(-1, -2, b = 10f))
            paintChips()
        }
        b.addView(AU.label(ctx, label))
        b.addView(area, AU.lp(-1, -2))
        go.off = area.value.trim().length < min
        area.onText = { go.off = it.trim().length < min; if (presets.isNotEmpty()) paintChips() }
        val cancel = AU.btn(ctx, "Cancel", "light") { sh.close() }
        go.setOnClickListener {
            if (go.busy || go.off) return@setOnClickListener
            go.busy = true
            onConfirm(area.value.trim()) { ok -> go.busy = false; if (ok) sh.close() }
        }
        b.addView(AU.btnRow(ctx, 10f, true, cancel, go), AU.lp(-1, -2, t = 12f))
        return sh.show()
    }
}

/** Confirm of ui.jsx: a question with Cancel and the action (busy while it runs). */
class AdConfirm(
    private val a: AdminScreen, private val title: String, private val text: String, private val confirmLabel: String = "Yes",
    private val danger: Boolean = false, private val onConfirm: ((Boolean) -> Unit) -> Unit,
) {
    fun open(): AdSheet {
        val ctx = a.context
        val sh = AdSheet(a.layer, title)
        sh.body.addView(AU.t(ctx, text, 14f, AK.sub, false, 20f), AU.lp(-1, -2, b = 16f))
        val go = AU.btn(ctx, confirmLabel, if (danger) "danger" else "dark")
        go.setOnClickListener {
            if (go.busy) return@setOnClickListener
            go.busy = true
            onConfirm { ok -> go.busy = false; if (ok) sh.close() }
        }
        sh.body.addView(AU.btnRow(ctx, 10f, true, AU.btn(ctx, "Cancel", "light") { sh.close() }, go))
        return sh.show()
    }
}
