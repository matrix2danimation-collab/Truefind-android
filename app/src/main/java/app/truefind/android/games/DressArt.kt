package app.truefind.android.games

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Dress Code — items, colours, rounds, score, and the doll drawn from the web's own art.
 * assets/games/dresscode.json is made by tools/dresscode-gen from Games.jsx: every item's SVG with its
 * colours left as placeholders; here the colours are swapped in and the doll is put together in the
 * same layer order as the web's DollSvg (checked to give byte-identical SVG).
 */
object DressArt {
    class Item(
        val id: String, val slot: String, val base: String, val pat: String, val name: String, val baseName: String,
        val tier: String, val price: Int, val tags: List<String>, val cols: IntArray, val t: Int, val hb: Int, val hf: Int, val ordinal: Int,
    )
    class Tab(val key: String, val label: String, val icon: String, val slots: List<String>)
    class Wear(val id: String, val c: Int)
    class Cols(val m: String, val d: String, val l: String, val x: String, val k: String)

    @Volatile var ready = false; private set
    private var loading = false
    private val waiters = ArrayList<() -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    lateinit var colors: List<Pair<String, String>> private set      // name, hex
    lateinit var items: List<Item> private set
    lateinit var byId: Map<String, Item> private set
    lateinit var tabs: List<Tab> private set
    lateinit var patterns: Map<String, Pair<String, String>> private set // pat → name, tier
    private lateinit var tpls: Array<String>
    private var defs = ""; private var baseTop = ""; private var baseBottom = ""
    private var bodyFront = ""; private var bodyBack = ""; private var bodyGhost = ""
    private var defaultHairB = ""; private var defaultHairF = ""; private var sareeBlouseT = ""
    private lateinit var baseHair: Cols
    private var thumbBox: Map<String, String> = emptyMap()
    private var thumbBody: Set<String> = emptySet()

    /** Reads the art once (on a background thread); [done] runs on the main thread when it is ready. */
    fun load(ctx: Context, done: () -> Unit) {
        if (ready) { done(); return }
        waiters.add(done)
        if (loading) return
        loading = true
        val app = ctx.applicationContext
        Thread {
            try {
                parse(app.assets.open("games/dresscode.json").use { it.readBytes().toString(Charsets.UTF_8) })
            } catch (e: Throwable) { }
            main.post {
                loading = false
                val list = ArrayList(waiters); waiters.clear()
                list.forEach { it() }
            }
        }.start()
    }

    /** Fills everything from dresscode.json (no Android needed: also used by the check on a computer). */
    fun parse(text: String) {
                val o = JSONObject(text)
                val cs = o.getJSONArray("colors")
                colors = List(cs.length()) { val a = cs.getJSONArray(it); a.getString(0) to a.getString(1) }
                val ts = o.getJSONArray("tpls")
                tpls = Array(ts.length()) { ts.getString(it) }
                val its = o.getJSONArray("items")
                items = List(its.length()) { i ->
                    val j = its.getJSONObject(i)
                    val tg = j.getJSONArray("tags"); val cl = j.getJSONArray("cols")
                    Item(j.getString("id"), j.getString("slot"), j.getString("base"), j.optString("pat"), j.getString("name"), j.getString("baseName"),
                        j.getString("tier"), j.getInt("price"), List(tg.length()) { tg.getString(it) }, IntArray(cl.length()) { cl.getInt(it) },
                        j.optInt("t", -1), j.optInt("hb", -1), j.optInt("hf", -1), i)
                }
                byId = items.associateBy { it.id }
                val tb = o.getJSONArray("tabs")
                tabs = List(tb.length()) { i -> val j = tb.getJSONObject(i); val sl = j.getJSONArray("slots"); Tab(j.getString("key"), j.getString("label"), j.getString("icon"), List(sl.length()) { sl.getString(it) }) }
                val pt = o.getJSONObject("patterns")
                patterns = pt.keys().asSequence().associateWith { k -> val a = pt.getJSONArray(k); a.getString(0) to a.getString(1) }
                defs = o.getString("defs"); baseTop = o.getString("baseTop"); baseBottom = o.getString("baseBottom")
                bodyFront = o.getString("bodyFront"); bodyBack = o.getString("bodyBack"); bodyGhost = o.getString("bodyGhost")
                defaultHairB = o.getString("defaultHairB"); defaultHairF = o.getString("defaultHairF"); sareeBlouseT = o.getString("sareeBlouse")
                val bh = o.getJSONObject("baseHair")
                baseHair = Cols(bh.getString("m"), bh.getString("d"), bh.getString("l"), bh.getString("x"), bh.getString("k"))
                val tbx = o.getJSONObject("thumbBox"); thumbBox = tbx.keys().asSequence().associateWith { tbx.getString(it) }
                val tbd = o.getJSONObject("thumbBody"); thumbBody = tbd.keys().asSequence().toSet()
                ready = true
    }

    // ───────── colour helpers (same maths as the web) ─────────
    private fun clamp255(n: Double) = max(0, min(255, Math.round(n).toInt()))
    fun shade(hex: String, p: Int): String {
        val n = hex.substring(1).toInt(16)
        val r = (n shr 16).toDouble(); val g = ((n shr 8) and 255).toDouble(); val b = (n and 255).toDouble()
        val t = if (p < 0) 0.0 else 255.0; val k = abs(p) / 100.0
        val ri = clamp255(r + (t - r) * k); val gi = clamp255(g + (t - g) * k); val bi = clamp255(b + (t - b) * k)
        return "#" + ((1 shl 24) + (ri shl 16) + (gi shl 8) + bi).toString(16).substring(1)
    }
    fun isLight(hex: String): Boolean { val n = hex.substring(1).toInt(16); return ((n shr 16) * 0.299 + ((n shr 8) and 255) * 0.587 + (n and 255) * 0.114) > 170 }
    fun mk(hex: String) = Cols(hex, shade(hex, -24), shade(hex, 30), shade(hex, -48), if (isLight(hex)) shade(hex, -40) else shade(hex, 55))
    private fun colIx(id: String, c: Int): Int { val item = byId[id] ?: return 0; return item.cols.getOrElse(c) { item.cols[0] } }
    fun colorHex(id: String, c: Int) = colors[colIx(id, c)].second
    fun colorName(id: String, c: Int) = colors[colIx(id, c)].first
    fun colorsFor(id: String, c: Int) = mk(colorHex(id, c))

    private fun fill(t: String, c: Cols): String = t.replace("§m", c.m.substring(1)).replace("§d", c.d.substring(1)).replace("§l", c.l.substring(1))
        .replace("§x", c.x.substring(1)).replace("§k", c.k.substring(1))

    private fun piece(w: Wear?): String {
        if (w == null) return ""
        val it = byId[w.id] ?: return ""
        if (it.t < 0) return ""
        return fill(tpls[it.t], colorsFor(w.id, w.c))
    }
    private fun sareeBlouse(w: Wear) = fill(sareeBlouseT, mk(colorsFor(w.id, w.c).x))

    /** how layers hide each other: kurta covers top & blouse, top covers blouse, saree covers bottom */
    fun visible(look: Map<String, Wear>): Map<String, Wear> {
        val l = LinkedHashMap(look)
        if (l.containsKey("kurta")) { l.remove("top"); l.remove("blouse") }
        if (l.containsKey("top")) l.remove("blouse")
        if (l.containsKey("saree")) l.remove("bottom")
        return l
    }

    private val dollCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 24
    }
    /** The whole doll as SVG (viewBox 0 0 300 545), front or back — web DollSvg. */
    fun doll(look: Map<String, Wear>, back: Boolean): String {
        val key = (if (back) "b|" else "f|") + look.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value.id}.${it.value.c}" }
        synchronized(dollCache) { dollCache[key]?.let { return it } }
        val l = visible(look)
        fun p(s: String) = piece(l[s])
        val hw = l["hair"]?.takeIf { (byId[it.id]?.hb ?: -1) >= 0 }
        val hc = if (hw != null) colorsFor(hw.id, hw.c) else baseHair
        val hairB = if (hw != null) fill(tpls[byId[hw.id]!!.hb], hc) else defaultHairB
        val hairF = if (hw != null) fill(tpls[byId[hw.id]!!.hf], hc) else defaultHairF
        val sb = StringBuilder(16000)
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 300 545\">").append(defs)
        if (!back) sb.append(hairB)
        sb.append(if (back) bodyBack.replace("§h", hc.m.substring(1)) else bodyFront)
        val saree = l["saree"]
        val lower = if (saree != null) "" else p("bottom").ifEmpty { baseBottom }
        val upper = when {
            l.containsKey("kurta") -> p("kurta")
            l.containsKey("top") -> p("top")
            l.containsKey("blouse") -> p("blouse")
            saree != null -> ""
            else -> baseTop
        }
        sb.append(lower)
        if (saree != null && !l.containsKey("kurta") && !l.containsKey("top")) sb.append(if (l.containsKey("blouse")) p("blouse") else sareeBlouse(saree))
        if (saree != null && (l.containsKey("kurta") || l.containsKey("top"))) sb.append(upper)
        sb.append(if (saree != null) p("saree") else upper)
        sb.append(p("shoes"))
        if (!back) sb.append(p("necklace"))
        sb.append(p("earrings")).append(p("watch")).append(p("bangles"))
        if (!back) sb.append(p("ring"))
        sb.append(p("dupatta")).append(p("bag"))
        if (!back) sb.append(p("glasses"))
        sb.append(if (back) hairB else hairF)
        if (!back) sb.append(p("tikka"))
        sb.append(p("hat")).append(p("hand"))
        sb.append("</svg>")
        return sb.toString().also { synchronized(dollCache) { dollCache[key] = it } }
    }

    /** One item on a soft mannequin (web ThumbSvg); also returns its viewBox width and height. */
    fun thumb(item: Item, c: Int): Triple<String, Float, Float> {
        val box = thumbBox[item.slot] ?: "0 0 300 545"
        val nums = box.split(" ").map { it.toFloat() }
        val isHair = item.slot == "hair" && item.hb >= 0
        val hc = if (isHair) colorsFor(item.id, c) else null
        val sb = StringBuilder(8000)
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"").append(box).append("\">").append(defs)
        if (isHair) sb.append(fill(tpls[item.hb], hc!!))
        if (thumbBody.contains(item.slot)) sb.append(bodyGhost)
        if (item.slot == "saree") sb.append(sareeBlouse(Wear(item.id, c)))
        sb.append(if (isHair) fill(tpls[item.hf], hc!!) else piece(Wear(item.id, c)))
        sb.append("</svg>")
        return Triple(sb.toString(), nums[2], nums[3])
    }

    fun lookOf(o: JSONObject?): Map<String, Wear> {
        val m = LinkedHashMap<String, Wear>()
        if (o == null) return m
        for (k in o.keys()) { val w = o.optJSONObject(k) ?: continue; val id = w.optString("id"); if (id.isNotEmpty()) m[k] = Wear(id, w.optInt("c", 0)) }
        return m
    }

    // ───────── rounds: theme + 3 missions (web THEMES) ─────────
    class Wish(val t: String, val e: String, val tags: List<String>, val ok: (Map<String, Wear>) -> Boolean)
    class Theme(val key: String, val title: String, val icon: String, val ask: String, val wishes: List<Wish>)
    private fun has(l: Map<String, Wear>, tag: String) = l.values.any { byId[it.id]?.tags?.contains(tag) == true }
    val THEMES = listOf(
        Theme("shaadi", "Shaadi Night", "💍", "Aaj shaadi hai! Saree ya lehenga pehnao 💍", listOf(
            Wish("Saree ya Lehenga", "🥻", listOf("saree", "lehenga")) { has(it, "saree") || has(it, "lehenga") },
            Wish("Jhumke", "💎", listOf("jhumka")) { has(it, "jhumka") },
            Wish("Kuch Super Premium", "👑", emptyList()) { l -> l.values.any { byId[it.id]?.tier == "s" } })),
        Theme("college", "College Date", "☕", "College date hai, kuch cool pehnao! ☕", listOf(
            Wish("Top + Jeans/Skirt", "👚", listOf("jeans", "skirt")) { it.containsKey("top") && (has(it, "jeans") || has(it, "skirt")) },
            Wish("Sneakers", "👟", listOf("sneakers")) { has(it, "sneakers") },
            Wish("Sling bag ya phone", "📱", listOf("sling", "phone")) { has(it, "sling") || has(it, "phone") })),
        Theme("gala", "Red Carpet", "🌟", "Red carpet! Mujhe star bana do ✨", listOf(
            Wish("Party top ya Saree", "✨", listOf("party", "saree")) { has(it, "party") || has(it, "saree") },
            Wish("Stilettos", "👠", listOf("heels")) { has(it, "heels") },
            Wish("Necklace", "📿", listOf("necklace")) { it.containsKey("necklace") })),
        Theme("beach", "Beach Day", "🏖️", "Beach chalein? Shades aur hat 😎", listOf(
            Wish("Shorts ya Mini", "🩳", listOf("short")) { has(it, "short") },
            Wish("Shades", "🕶️", listOf("glasses")) { it.containsKey("glasses") },
            Wish("Hat", "👒", listOf("hat")) { it.containsKey("hat") })),
        Theme("diwali", "Diwali Puja", "🪔", "Diwali puja ke liye suit pehnao 🪔", listOf(
            Wish("Kurta + Dupatta", "👘", listOf("kurta", "dupatta")) { it.containsKey("kurta") && it.containsKey("dupatta") },
            Wish("Chudiyan", "✨", listOf("bangles")) { it.containsKey("bangles") },
            Wish("Juttis", "🥿", listOf("jutti")) { has(it, "jutti") })),
        Theme("office", "Office Party", "💼", "Office party hai, smart look chahiye 💼", listOf(
            Wish("Blazer ya Shirt", "🧥", listOf("formal")) { it.containsKey("top") && has(it, "formal") },
            Wish("Trousers", "👖", listOf("trousers")) { has(it, "trousers") },
            Wish("Watch", "⌚", listOf("watch")) { it.containsKey("watch") })),
        Theme("rain", "Baarish Date", "☔", "Baarish ho rahi hai ☔ umbrella mat bhoolna!", listOf(
            Wish("Umbrella", "☂️", listOf("umbrella")) { has(it, "umbrella") },
            Wish("Ankle Boots", "🥾", listOf("boots")) { has(it, "boots") },
            Wish("Palazzo ya Skirt", "💃", listOf("palazzo", "skirt")) { has(it, "palazzo") || has(it, "skirt") })),
    )
    fun themeFor(round: Int) = THEMES[(((if (round > 0) round else 1) - 1) % THEMES.size)]

    private val SLOT_PTS = mapOf("saree" to 26, "blouse" to 6, "dupatta" to 8, "kurta" to 14, "top" to 12, "bottom" to 12, "shoes" to 10, "hair" to 8, "bag" to 6,
        "hand" to 5, "necklace" to 6, "earrings" to 6, "tikka" to 4, "bangles" to 4, "ring" to 3, "watch" to 4, "glasses" to 4, "hat" to 4)
    class Score(val total: Int, val done: List<Boolean>, val combo: Boolean, val grade: String)
    fun styleScore(look: Map<String, Wear>, theme: Theme): Score {
        val l = visible(look)
        var base = 0; var bonus = 0
        val counts = HashMap<String, Int>()
        for ((slot, w) in l) {
            val it = byId[w.id] ?: continue
            base += SLOT_PTS[slot] ?: 0
            bonus += if (it.tier == "s") 5 else if (it.tier == "p") 2 else 0
            val n = colorName(w.id, w.c)
            counts[n] = (counts[n] ?: 0) + 1
        }
        val done = theme.wishes.map { it.ok(l) }
        val combo = max(0, counts.values.maxOrNull() ?: 0) >= 3
        val total = min(100, Math.round(base * 0.5 + bonus + done.count { it } * 15 + (if (combo) 8 else 0)).toInt())
        val grade = if (total >= 90) "S" else if (total >= 75) "A" else if (total >= 55) "B" else if (total >= 35) "C" else "D"
        return Score(total, done, combo, grade)
    }

    const val GIRL_PERCENT = 67
    const val LOOK_SECONDS = 180
    val DC_RULES = listOf(
        "Ek ladka aur ek ladki (18+). Ladka stylist hai, ladki ki gudiya ko round ki theme ke hisaab se ready karta hai.",
        "Har cheez alag part hai: saree, blouse, dupatta, kurta, top, bottom, shoes, gehne. Ek-ek karke pehnao.",
        "Normal = 1 coin, Premium = 2 coin, Super Premium = 3-5 coin. Coin sirf \"Confirm\" dabane par katenge.",
        "Li hui cheez ka rang badalna free hai. Dobara pehnana aur utarna bhi free.",
        "Har round ki theme, 3 missions aur 3 minute ka timer. Mission poore karo, Style Score badhao, S rank lao!",
        "Har kharid ka $GIRL_PERCENT% ladki ko coins me milta hai.",
        "Doll ko zoom karo (do ungli ya + / −) aur swipe karke chaaron taraf se dekho.",
        "Pehle 2 minute chat free, phir har nayi kharid par 3 minute. Number, link ya social handle mana hai.",
    )
    val PARTNER_REPLIES = listOf("Wow, ye toh bahut accha lag raha hai 😍", "Ye rang mast hai!", "Hmm, theme ke hisaab se kuch aur? 😄", "Perfect! 💖", "Ab gehne bhi pehnao 💍", "Premium wala try karo na 👑")
    val SHOWCASE = linkedMapOf("saree" to Wear("banarasi-zari", 0), "blouse" to Wear("bshort-zari", 0), "shoes" to Wear("jutti-jewel", 0), "hair" to Wear("bun", 0),
        "earrings" to Wear("chandbali", 0), "necklace" to Wear("kundan", 0), "bangles" to Wear("kundanbangles", 0), "tikka" to Wear("kundantikka", 0))

    fun money(n: Double) = Math.round(n * 100) / 100.0
    fun coinFmt(n: Double): String {
        val v = money(n)
        if (v == Math.floor(v) && !v.isInfinite()) return v.toLong().toString()
        return String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    }
    fun mmss(ms: Long): String { val s = max(0L, Math.ceil(ms / 1000.0).toLong()); return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
}

/** For the Games page card (web DC_DESIGNS): "370+ designs". Known before the art is read: 373 items. */
object DressData {
    private const val ITEMS_IN_ART = 373 // tools/dresscode-gen prints the count; the art file is checked against it
    val designsLabel: String get() {
        val n = if (DressArt.ready) DressArt.items.size else ITEMS_IN_ART
        return "${maxOf(10, (n / 10) * 10)}+ designs"
    }
}
