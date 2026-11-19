package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.UUID

/**
 * Profile → Avatar / Templates (owner, 19 Nov evening): the rows scroll sideways, "See all" opens a page with
 * all of them, and tapping one opens a Wallet-like sheet "Buy for N coins" that really buys it with True Find
 * coins (the shop function). What is for sale and the prices come from the server's list.
 */
class ShopItemV(val id: String, val kind: String, val name: String, val art: String, val price: Int, var owned: Boolean)

/** The answers of the shop function, read without any screen (so they can be checked on their own). */
object ShopData {
    fun items(d: JSONObject?): List<ShopItemV> {
        val a = d?.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id"); val kind = o.optString("kind"); val price = o.optInt("price", 0)
            if (id.isEmpty() || (kind != "avatar" && kind != "template") || price < 1) null
            else ShopItemV(id, kind, o.optString("name").ifEmpty { if (kind == "avatar") "Avatar" else "Template" }, o.optString("art"), price, o.optBoolean("owned"))
        }
    }
    fun coins(d: JSONObject?): Long? = if (d != null && d.has("coins") && !d.isNull("coins")) d.optLong("coins") else null

    sealed class Buy {
        class Done(val coins: Long?) : Buy()
        class Short(val need: Int, val coins: Long?) : Buy()
        class Failed(val message: String) : Buy()
    }
    /** what a Buy answer means: bought (or already mine), not enough coins, or a failure to show */
    fun buy(status: Int, d: JSONObject): Buy = when {
        status in 200..299 && d.optBoolean("ok") -> Buy.Done(coins(d))
        status == 402 && d.optString("error") == "coins" -> Buy.Short(d.optInt("need", 0).coerceAtLeast(1), coins(d))
        status == 429 && d.optString("error") == "busy" -> Buy.Failed("The shop is busy right now. Please try again in a moment.")
        status == 0 -> Buy.Failed("No internet. Check your connection and try again — you were not charged twice.")
        else -> Buy.Failed(d.optString("error").takeIf { it.isNotEmpty() && it != "coins" && it.length < 120 } ?: "Could not buy it. Please try again.")
    }
    /** one id per Buy sheet: the same tap sent again (after a dropped answer) is charged once */
    fun newTap(): String = "t" + UUID.randomUUID().toString().replace("-", "").take(24)
}

object ShopUi {
    private val GOLD = Ui.c("#B45309")
    fun isAvatar(kind: String) = kind == "avatar"

    /** the picture: an avatar is round, a template is a rounded square */
    fun art(ctx: Context, item: ShopItemV?, kind: String): View {
        val round = isAvatar(kind)
        return PImg(ctx, if (round) 0f else 14f, round).apply { if (item != null && item.art.isNotEmpty()) set(listOf(item.art)) }
    }

    /** price "🪙 30" or "Owned" */
    fun priceLine(ctx: Context, item: ShopItemV): View {
        val r = Tf.row(ctx, Gravity.CENTER)
        if (item.owned) {
            r.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.Check, PC.green, 13f, 3f)) }, Tf.ll(Ui.dpi(13f), Ui.dpi(13f), r = 3f))
            r.addView(Tf.t(ctx, "Owned", 12f, PC.green))
        } else {
            r.addView(WCoins.flat(ctx, 14f), Tf.ll(Ui.dpi(14f), Ui.dpi(14f), r = 3f))
            r.addView(Tf.t(ctx, "${item.price}", 12.5f, GOLD))
        }
        return r
    }

    /** one tile: picture + name + price (or Owned) */
    fun tile(ctx: Context, item: ShopItemV, sizeDp: Float, onTap: (ShopItemV) -> Unit): View {
        val c = Tf.col(ctx, Gravity.CENTER_HORIZONTAL).apply {
            contentDescription = "${item.name}, ${if (item.owned) "owned" else "${item.price} coins"}"
            setOnClickListener { onTap(item) }
        }
        val round = isAvatar(item.kind)
        val frame = FrameLayout(ctx).apply {
            background = if (round) Tf.circle(PC.faint, 2f, if (item.owned) PC.green else PC.line) else Tf.box(PC.faint, 16f, 2f, if (item.owned) PC.green else PC.line)
            setPadding(Ui.dpi(3f), Ui.dpi(3f), Ui.dpi(3f), Ui.dpi(3f))
        }
        frame.addView(art(ctx, item, item.kind), FrameLayout.LayoutParams(-1, -1))
        c.addView(frame, Tf.ll(Ui.dpi(sizeDp), Ui.dpi(sizeDp)))
        c.addView(Tf.t(ctx, item.name, 12.5f, PC.ink).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; maxWidth = Ui.dpi(sizeDp + 12f) }, Tf.ll(t = 6f))
        c.addView(priceLine(ctx, item), Tf.ll(t = 2f))
        Ui.pressable(c, 0.96f)
        return c
    }

    /** the row in the box: sideways scroll; nothing for sale yet → the empty places (no words) */
    fun row(ctx: Context, kind: String, items: List<ShopItemV>, onTap: (ShopItemV) -> Unit): View {
        val round = isAvatar(kind)
        if (items.isEmpty()) {
            val row = Tf.row(ctx)
            val n = if (round) 5 else 4
            for (i in 0 until n) {
                val cell = FrameLayout(ctx).apply {
                    background = if (round) Tf.dashedBox(Ui.c("#FBF9FE"), 999f, 1.5f, PC.dashSoft) else Tf.dashedBox(Ui.c("#FBF9FE"), 12f, 1.5f, PC.dashSoft)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                cell.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(if (round) Lu.User else Lu.Crown, PC.dash, 18f, 2f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                row.addView(cell, LinearLayout.LayoutParams(0, Ui.dpi(56f), 1f).apply { if (i > 0) leftMargin = Ui.dpi(8f) })
            }
            return row
        }
        val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false }
        val line = Tf.row(ctx, Gravity.TOP)
        val size = if (round) 64f else 72f
        items.forEachIndexed { i, it -> line.addView(tile(ctx, it, size, onTap), Tf.ll(Ui.dpi(size + 12f), Tf.W).apply { if (i > 0) leftMargin = Ui.dpi(6f) }) }
        hs.addView(line, FrameLayout.LayoutParams(-2, -2))
        return hs
    }

    /** "See all ›" */
    fun seeAll(ctx: Context, onTap: () -> Unit): TextView = Tf.t(ctx, "See all", 14f, PC.head).apply {
        gravity = Gravity.CENTER_VERTICAL; compoundDrawablePadding = Ui.dpi(2f)
        setCompoundDrawablesWithIntrinsicBounds(null, null, PUi.lu(Lu.ChevronRight, PC.head, 16f, 2.2f), null)
        setOnClickListener { onTap() }; Ui.pressable(this, .97f)
        layoutParams = LinearLayout.LayoutParams(-2, Ui.dpi(44f))
    }
}

/** See all: every avatar (or template) on a page of its own, three across; tap → the Buy sheet. */
@SuppressLint("ViewConstructor")
class ShopAllPage(ctx: Context, private val kind: String, private val items: () -> List<ShopItemV>, private val coins: () -> Long?,
                  private val onTap: (ShopItemV) -> Unit, private val close: () -> Unit) : FrameLayout(ctx) {
    private val grid = Tf.col(ctx)
    private val coinText = Tf.t(ctx, "", 14f, Ui.c("#B45309"))
    init {
        setBackgroundColor(Color.WHITE)
        isClickable = true
        val page = Tf.col(ctx)
        val head = Tf.row(ctx).apply { setPadding(Ui.dpi(8f), Ui.dpi(8f), Ui.dpi(16f), Ui.dpi(8f)); background = Tf.box(WC.head, 0f) }
        head.addView(FrameLayout(ctx).apply {
            background = Tf.circle(Color.argb(41, 255, 255, 255)); contentDescription = "Back"
            addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(Lu.ChevronLeft, Color.WHITE, 22f, 2.4f)) }, LayoutParams(-2, -2, Gravity.CENTER))
            setOnClickListener { close() }; Ui.pressable(this, .95f)
        }, Tf.ll(Ui.dpi(40f), Ui.dpi(40f), r = 10f))
        head.addView(Tf.t(ctx, if (ShopUi.isAvatar(kind)) "Avatars" else "Templates", 18f, Color.WHITE), Tf.ll(0, Tf.W, 1f))
        val pill = Tf.row(ctx).apply { background = Tf.box(Color.WHITE, 99f); setPadding(Ui.dpi(10f), Ui.dpi(4f), Ui.dpi(12f), Ui.dpi(4f)) }
        pill.addView(WCoins.flat(ctx, 16f), Tf.ll(Ui.dpi(16f), Ui.dpi(16f), r = 5f))
        pill.addView(coinText)
        head.addView(pill)
        page.addView(head, Tf.ll(Tf.M, Tf.W))
        val sv = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        grid.setPadding(Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(28f))
        sv.addView(grid, LayoutParams(-1, -2))
        page.addView(sv, Tf.ll(Tf.M, 0, 1f))
        addView(page, LayoutParams(-1, -1))
        refresh()
    }
    fun refresh() {
        val ctx = context
        coinText.text = coins()?.toString() ?: "—"
        grid.removeAllViews()
        val list = items().filter { it.kind == kind }
        if (list.isEmpty()) {
            grid.addView(Tf.t(ctx, "Nothing here right now.", 14f, PC.sub, false).apply { gravity = Gravity.CENTER }, Tf.ll(Tf.M, Tf.W, t = 40f))
            return
        }
        val size = if (ShopUi.isAvatar(kind)) 84f else 92f
        var row: LinearLayout? = null
        list.forEachIndexed { i, it ->
            if (i % 3 == 0) { row = Tf.row(ctx, Gravity.TOP); grid.addView(row, Tf.ll(Tf.M, Tf.W).apply { if (i > 0) topMargin = Ui.dpi(18f) }) }
            row?.addView(ShopUi.tile(ctx, it, size, onTap), Tf.ll(0, Tf.W, 1f))
        }
        // the last line keeps three places, so the tiles never stretch
        val left = (3 - list.size % 3) % 3
        repeat(left) { row?.addView(View(ctx), Tf.ll(0, 1, 1f)) }
    }
}

/**
 * The Buy sheet, made like the Wallet's: purple top, a white card over it, the green bar at the bottom —
 * "Buy for N coins". Not enough coins → "Add coins" (the Wallet). Already owned → "You own this".
 */
object ShopBuySheet {
    fun open(layer: TfLayer, item: ShopItemV, coins: Long?, buy: (tap: String, done: (ShopData.Buy) -> Unit) -> Unit,
             addCoins: () -> Unit, bought: (ShopItemV, Long?) -> Unit): () -> Unit {
        val ctx = layer.ctx
        val tap = ShopData.newTap()
        var close: () -> Unit = {}
        var busy = false
        var have = coins
        var shortBy: Int? = coins?.let { if (it < item.price) (item.price - it).toInt() else null }

        val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = Tf.topRound(Color.WHITE, 26f); isClickable = true }
        val head = Tf.row(ctx).apply { background = Tf.topRound(WC.head, 26f); setPadding(Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(16f), Ui.dpi(40f)) }
        head.addView(FrameLayout(ctx).apply {
            background = Tf.circle(Color.argb(41, 255, 255, 255)); contentDescription = "Close"
            addView(ImageView(ctx).apply { setImageDrawable(Tf.lu(Lu.X, Color.WHITE, 18f, 2.4f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            setOnClickListener { if (!busy) close() }
        }, Tf.ll(Ui.dpi(36f), Ui.dpi(36f)))
        head.addView(Tf.t(ctx, if (ShopUi.isAvatar(item.kind)) "Buy avatar" else "Buy template", 18f, Color.WHITE), Tf.ll(l = 10f))
        panel.addView(head, Tf.ll(Tf.M, Tf.W))

        val card = Tf.col(ctx, Gravity.CENTER_HORIZONTAL).apply {
            background = Tf.box(Color.WHITE, 20f, 1f, WC.line); elevation = Ui.dp(3f)
            setPadding(Ui.dpi(16f), Ui.dpi(20f), Ui.dpi(16f), Ui.dpi(18f))
        }
        val round = ShopUi.isAvatar(item.kind)
        val frame = FrameLayout(ctx).apply {
            background = if (round) Tf.circle(PC.faint, 3f, Ui.c("#C9B3F5")) else Tf.box(PC.faint, 20f, 3f, Ui.c("#C9B3F5"))
            setPadding(Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f))
        }
        frame.addView(ShopUi.art(ctx, item, item.kind), FrameLayout.LayoutParams(-1, -1))
        card.addView(frame, Tf.ll(Ui.dpi(if (round) 112f else 124f), Ui.dpi(if (round) 112f else 124f)))
        card.addView(Tf.t(ctx, item.name, 18f, WC.ink).apply { gravity = Gravity.CENTER }, Tf.ll(Tf.M, Tf.W, t = 12f))
        val priceRow = Tf.row(ctx, Gravity.CENTER)
        priceRow.addView(WCoins.flat(ctx, 18f), Tf.ll(Ui.dpi(18f), Ui.dpi(18f), r = 5f))
        priceRow.addView(Tf.t(ctx, "${item.price} coins", 16f, Ui.c("#B45309")))
        card.addView(priceRow, Tf.ll(Tf.M, Tf.W, t = 4f))
        val note = Tf.t(ctx, "", 13.5f, WC.sub, false).apply { gravity = Gravity.CENTER }
        card.addView(note, Tf.ll(Tf.M, Tf.W, t = 10f))
        val cardWrap = FrameLayout(ctx).apply { setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0) }
        cardWrap.addView(card, FrameLayout.LayoutParams(-1, -2))
        panel.addView(cardWrap, Tf.ll(Tf.M, Tf.W).apply { topMargin = -Ui.dpi(26f) })

        val bar = FrameLayout(ctx).apply {
            background = PayGreenDrawable(16f); elevation = Ui.dp(10f)
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, Ui.dp(16f)) } }
        }
        val barText = Tf.t(ctx, "", 17f, Ui.c("#05301A")).apply { gravity = Gravity.CENTER }
        val spin = WSpinner(ctx, 20f, Ui.c("#05301A")).apply { visibility = View.GONE }
        bar.addView(barText, FrameLayout.LayoutParams(-1, -1))
        bar.addView(spin, FrameLayout.LayoutParams(Ui.dpi(20f), Ui.dpi(20f), Gravity.CENTER))
        panel.addView(bar, Tf.ll(Tf.M, Ui.dpi(56f), l = 16f, t = 16f, r = 16f, b = 22f))

        fun paint() {
            when {
                item.owned -> { note.text = "You own this."; note.setTextColor(PC.green); barText.text = "OK"; bar.contentDescription = "OK" }
                shortBy != null -> { note.text = "You have ${have ?: 0} coins — ${shortBy} more needed."; note.setTextColor(PC.red); barText.text = "Add coins"; bar.contentDescription = "Add coins" }
                else -> { note.text = if (have != null) "You have $have coins." else ""; note.setTextColor(WC.sub); barText.text = "Buy for ${item.price} coins"; bar.contentDescription = "Buy for ${item.price} coins" }
            }
            barText.visibility = if (busy) View.INVISIBLE else View.VISIBLE
            spin.visibility = if (busy) View.VISIBLE else View.GONE
        }
        bar.setOnClickListener {
            if (busy) return@setOnClickListener
            when {
                item.owned -> close()
                shortBy != null -> { close(); addCoins() }
                else -> {
                    busy = true; paint()
                    buy(tap) { res ->
                        busy = false
                        when (res) {
                            is ShopData.Buy.Done -> { item.owned = true; have = res.coins ?: have; paint(); close(); bought(item, res.coins) }
                            is ShopData.Buy.Short -> { have = res.coins ?: have; shortBy = res.need; paint() }
                            is ShopData.Buy.Failed -> { paint(); note.text = res.message; note.setTextColor(PC.red) }
                        }
                    }
                }
            }
        }
        Ui.pressable(bar, 0.98f)
        paint()
        close = layer.sheet(panel, Color.argb(128, 28, 6, 58), 0.92f, 22f, { busy })
        return close
    }
}
