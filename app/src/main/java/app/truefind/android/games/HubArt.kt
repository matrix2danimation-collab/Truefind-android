package app.truefind.android.games

/**
 * The Games page pictures — the same SVG as Games.jsx (TgLogo, PadArt, ClueArt, DressArt,
 * MoodArt, HeartArt, MiniPad, coming-soon icons). The web's drop shadows (feDropShadow) are
 * drawn by Svg.Shadow from the parts that carry them.
 */
object HubArt {
    const val HEART = "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z"
    const val PAD = "M8.2 1.5h13.6c3.5 0 6 2.5 6.6 6l1 6.4c.5 3-2.9 5.1-5.3 3.2l-3.4-2.7H9.3l-3.4 2.7C3.5 19-.1 16.9.4 13.9l1-6.4c.6-3.5 3.1-6 6.8-6z"
    private const val SPARK = "M0-1C.12-.12.12-.12 1 0 .12.12.12.12 0 1-.12.12-.12.12-1 0-.12-.12-.12-.12 0-1Z"
    private const val NS = "xmlns=\"http://www.w3.org/2000/svg\""
    private const val IC_G = "<linearGradient id=\"tgIcG\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#8a1fe6\"/><stop offset=\"1\" stop-color=\"#4c0fa8\"/></linearGradient>"

    private fun spark(x: Float, y: Float, r: Float, c: String, o: Float = 1f) =
        "<path d=\"$SPARK\" transform=\"translate($x $y) scale($r)\" fill=\"$c\" opacity=\"$o\"/>"

    // ── logo: two linked hearts ──
    val logo = """<svg $NS viewBox="3.2 3.2 34.6 21.2"><defs>
<linearGradient id="a" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#47048a"/><stop offset="1" stop-color="#6c04cb"/></linearGradient>
<linearGradient id="b" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#7a12dc"/><stop offset="1" stop-color="#a24cf5"/></linearGradient>
<clipPath id="c"><rect x="14.5" y="14.2" width="13" height="12"/></clipPath></defs>
<g fill="none" stroke-width="1.55" stroke-linejoin="round">
<path d="$HEART" transform="translate(13.6 1.1) scale(1.08)" stroke="url(#b)"/>
<path d="$HEART" transform="translate(2 1.1) scale(1.08)" stroke="url(#a)"/>
<path d="$HEART" transform="translate(13.6 1.1) scale(1.08)" stroke="url(#b)" clip-path="url(#c)"/>
</g></svg>"""

    /** small game pad (fill: gradient or a colour, dot colour) */
    fun miniPad(fill: String = "url(#tgIcG)", dot: String = "#fff") = """<svg $NS viewBox="0 0 30 20"><defs>$IC_G</defs>
<path d="$PAD" fill="$fill"/>
<path d="M8.7 6.3v6M5.7 9.3h6" stroke="$dot" stroke-width="2" stroke-linecap="round"/>
<circle cx="20.8" cy="6.9" r="1.45" fill="$dot"/><circle cx="23.9" cy="9.8" r="1.45" fill="$dot"/>
<circle cx="17.7" cy="9.8" r="1.45" fill="$dot"/><circle cx="20.8" cy="12.7" r="1.45" fill="$dot"/></svg>"""

    // ── big glossy pad (top card) ──
    private const val PAD_DEFS = """<defs>
<linearGradient id="pb" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffffff"/><stop offset="1" stop-color="#e4d2fe"/></linearGradient>
<linearGradient id="pe" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#b98ff5"/><stop offset="1" stop-color="#6d28d9"/></linearGradient></defs>"""
    private val padBody = "<path d=\"$PAD\" fill=\"url(#pe)\" transform=\"translate(0 1.2)\"/><path d=\"$PAD\" fill=\"url(#pb)\"/>"
    val pad = """<svg $NS viewBox="-7 -9 44 36">$PAD_DEFS<g>$padBody</g>
<path d="M4.6 4.1c.9-1.2 2.2-1.8 3.8-1.8h5.4" stroke="#fff" stroke-width=".9" stroke-linecap="round" fill="none"/>
<path d="M8.7 6.4v5.8M5.8 9.3h5.8" stroke="#5b16b0" stroke-width="2.2" stroke-linecap="round"/>
<circle cx="20.8" cy="6.8" r="1.5" fill="#ff5c9a"/><circle cx="23.9" cy="9.7" r="1.5" fill="#ffc44d"/>
<circle cx="17.7" cy="9.7" r="1.5" fill="#22c3ee"/><circle cx="20.8" cy="12.6" r="1.5" fill="#8b5cf6"/>
<rect x="12.6" y="5.4" width="2" height=".9" rx=".45" fill="#c9b4f1"/><rect x="15.4" y="5.4" width="2" height=".9" rx=".45" fill="#c9b4f1"/>
<path d="$HEART" transform="translate(26.4 -4.6) scale(.27) rotate(14 12 12)" fill="#ff7ab0"/>
${spark(-3.6f, -3.4f, 1.9f, "#ffd877")}${spark(33.4f, 17.6f, 1.4f, "#fff", 0.9f)}${spark(2.6f, 22.6f, 1f, "#f0abfc", 0.9f)}
</svg>"""
    val padShadow = listOf(Svg.Shadow("""<svg $NS viewBox="-7 -9 44 36">$PAD_DEFS<g>$padBody</g></svg>""", 0f, 2.2f, 1.8f, G.c("#16003d"), 0.45f))

    // ── ClueMe ──
    private const val CLUE_DEFS = """<defs>
<radialGradient id="bg" cx=".5" cy=".5" r=".5"><stop offset="0" stop-color="#e6d7fd"/><stop offset=".7" stop-color="#efe5fe" stop-opacity=".6"/><stop offset="1" stop-color="#f3ecfe" stop-opacity="0"/></radialGradient>
<linearGradient id="cd" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#a04cf5"/><stop offset=".55" stop-color="#6c0fd6"/><stop offset="1" stop-color="#4c0fa8"/></linearGradient>
<linearGradient id="hd" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#ff86b4"/><stop offset="1" stop-color="#db2777"/></linearGradient></defs>"""
    private const val CLUE_CARD = """<g transform="rotate(-8 62 66)">
<rect x="34" y="28" width="56" height="76" rx="12" fill="url(#cd)"/>
<rect x="35" y="29" width="54" height="74" rx="11" fill="none" stroke="#fff" stroke-opacity=".22" stroke-width="1.2"/>
<path d="M53.6 56.4a8.6 8.6 0 1 1 12.4 7.7c-2.7 1.4-3.8 3.1-3.8 6v1.5" fill="none" stroke="#fff" stroke-width="5.4" stroke-linecap="round" stroke-linejoin="round"/>
<circle cx="62.2" cy="82.4" r="3.5" fill="#fff"/></g>"""
    private const val CLUE_LENS = """<g><path d="m111.5 97.5 15 15" stroke="url(#hd)" stroke-width="9.5" stroke-linecap="round"/>
<circle cx="99" cy="85" r="17" fill="#fff" fill-opacity=".72" stroke="#4c0fa8" stroke-width="5"/>
<path d="M89.6 80.6a11 11 0 0 1 7.6-6.4" stroke="#fff" stroke-width="3" stroke-linecap="round" fill="none"/></g>"""
    val clue = """<svg $NS viewBox="0 0 150 130">$CLUE_DEFS
<circle cx="78" cy="66" r="64" fill="url(#bg)"/>
<g transform="rotate(12 94 60)">
<rect x="68" y="24" width="52" height="70" rx="11" fill="#fff" stroke="#e4d6f9" stroke-width="1.5"/>
<rect x="78" y="38" width="32" height="5" rx="2.5" fill="#eee4fc"/><rect x="78" y="49" width="24" height="5" rx="2.5" fill="#eee4fc"/><rect x="78" y="60" width="28" height="5" rx="2.5" fill="#eee4fc"/></g>
$CLUE_CARD $CLUE_LENS
<g transform="translate(96 7)"><path d="M12 0h20a12 12 0 0 1 0 24H18l-8.5 6.5 2-6.6A12 12 0 0 1 12 0z" fill="#fff" stroke="#e6d9fa" stroke-width="1.4" stroke-linejoin="round"/>
<circle cx="13" cy="12" r="2.6" fill="#6c0fd6"/><circle cx="22" cy="12" r="2.6" fill="#6c0fd6" opacity=".7"/><circle cx="31" cy="12" r="2.6" fill="#6c0fd6" opacity=".4"/></g>
${spark(24f, 30f, 6f, "#f5b301")}${spark(140f, 58f, 4f, "#ff5c9a")}${spark(30f, 110f, 3.4f, "#a78bfa")}
</svg>"""
    val clueShadow = listOf(Svg.Shadow("""<svg $NS viewBox="0 0 150 130">$CLUE_DEFS$CLUE_CARD$CLUE_LENS</svg>""", 0f, 6f, 5f, G.c("#4c0fa8"), 0.28f))

    // ── Dress Code ──
    private const val DRESS_DEFS = """<defs>
<radialGradient id="bg" cx=".5" cy=".5" r=".5"><stop offset="0" stop-color="#f6dcf6"/><stop offset=".7" stop-color="#f6e8fb" stop-opacity=".6"/><stop offset="1" stop-color="#f7effc" stop-opacity="0"/></radialGradient>
<linearGradient id="tp" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ef7cf3"/><stop offset="1" stop-color="#b01ec0"/></linearGradient>
<linearGradient id="sk" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#d02ad6"/><stop offset=".55" stop-color="#7a0ad7"/><stop offset="1" stop-color="#4f06a0"/></linearGradient>
<linearGradient id="gd" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffe28f"/><stop offset="1" stop-color="#d99a1e"/></linearGradient></defs>"""
    private val dressBody: String = run {
        val dots = listOf(52 to 100, 62 to 104, 74 to 106, 86 to 106, 98 to 104, 108 to 100, 70 to 86, 90 to 86, 80 to 96)
            .joinToString("") { (x, y) -> "<circle cx=\"$x\" cy=\"$y\" r=\"1.25\" fill=\"#fff\" opacity=\".7\"/>" }
        """<g><path d="M67.5 38.5h6.2c1.7 6.4 10.9 6.4 12.6 0h6.2l3.4 22h-31.8z" fill="url(#tp)"/>
<path d="M64.6 64h30.8c10.4 13.4 18.4 29 22.6 45.4-13 6.6-63 6.6-76 0 4.2-16.4 12.2-32 22.6-45.4z" fill="url(#sk)"/>
<path d="M74.5 66 62 111.5M85.5 66 98 111.5M80 66v48" stroke="#fff" stroke-opacity=".16" stroke-width="2.2" stroke-linecap="round"/>
<path d="M42.6 108.6c12.4 5.4 62.4 5.4 74.8 0" fill="none" stroke="url(#gd)" stroke-width="2.6" stroke-linecap="round"/>
<rect x="63.2" y="58.2" width="33.6" height="7.4" rx="3.7" fill="url(#gd)"/>
<circle cx="80" cy="61.9" r="3.2" fill="#fff" stroke="#d99a1e" stroke-width="1.2"/>$dots
<circle cx="74" cy="47" r="1.1" fill="#fff" opacity=".75"/><circle cx="86" cy="47" r="1.1" fill="#fff" opacity=".75"/><circle cx="80" cy="53" r="1.1" fill="#fff" opacity=".75"/></g>"""
    }
    val dress = """<svg $NS viewBox="0 0 150 130">$DRESS_DEFS
<circle cx="80" cy="68" r="62" fill="url(#bg)"/>
<path d="M80 21.5c0-4.6 6.4-5.2 6.8-1.2.3 3-2.8 3.7-6.8 6.4" fill="none" stroke="url(#gd)" stroke-width="2.8" stroke-linecap="round"/>
<path d="M80 26.5 53.5 40.5h53z" fill="none" stroke="url(#gd)" stroke-width="3.2" stroke-linejoin="round"/>
$dressBody
<g transform="translate(18 26) rotate(-14 13 9)">
<path d="M1.6 5.6 7.4 10 13 2.2 18.6 10l5.8-4.4-2 10.6H3.6z" fill="url(#gd)" stroke="#c98a14" stroke-width=".8" stroke-linejoin="round"/>
<circle cx="1.6" cy="4.8" r="1.9" fill="url(#gd)"/><circle cx="13" cy="1.6" r="2" fill="url(#gd)"/><circle cx="24.4" cy="4.8" r="1.9" fill="url(#gd)"/>
<rect x="3.4" y="17.4" width="19.2" height="2.6" rx="1.3" fill="url(#gd)"/></g>
${spark(128f, 36f, 6f, "#f5b301")}${spark(138f, 92f, 3.6f, "#ff5c9a")}${spark(26f, 86f, 3.2f, "#a78bfa")}
</svg>"""
    val dressShadow = listOf(Svg.Shadow("""<svg $NS viewBox="0 0 150 130">$DRESS_DEFS$dressBody</svg>""", 0f, 7f, 5f, G.c("#7a0a9c"), 0.3f))

    // ── Mood Changer ──
    private const val MOOD_DEFS = """<defs>
<radialGradient id="bg" cx=".5" cy=".5" r=".5"><stop offset="0" stop-color="#e4d8fd"/><stop offset=".7" stop-color="#eee6fe" stop-opacity=".6"/><stop offset="1" stop-color="#f3ecfe" stop-opacity="0"/></radialGradient>
<linearGradient id="cd" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#a04cf5"/><stop offset=".55" stop-color="#6c0fd6"/><stop offset="1" stop-color="#4c0fa8"/></linearGradient>
<linearGradient id="ar" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#a78bfa"/><stop offset="1" stop-color="#db2777"/></linearGradient></defs>"""
    private const val MOOD_LOW = """<g><path d="M18 52h40a12 12 0 0 1 12 12v18a12 12 0 0 1-12 12H34l-9 7 1.6-7.4A12 12 0 0 1 18 82V64a12 12 0 0 1 0-12z" fill="#fff" stroke="#e4d6f9" stroke-width="1.5"/>
<circle cx="44" cy="73" r="14" fill="#A9C4FF"/>
<circle cx="39" cy="70" r="1.8" fill="#2A1F3D"/><circle cx="49" cy="70" r="1.8" fill="#2A1F3D"/>
<path d="M38.6 80.4Q44 76.2 49.4 80.4" fill="none" stroke="#2A1F3D" stroke-width="2" stroke-linecap="round"/>
<path d="M51.6 72.6c0 0-2 2.5-2 3.8a2 2 0 0 0 4 0c0-1.3-2-3.8-2-3.8z" fill="#5B9BFF"/></g>"""
    private const val MOOD_HIGH = """<g><path d="M92 50h40a12 12 0 0 1 12 12v20a12 12 0 0 1-12 12h-6.6l1.6 7.4-9-7.4H92a12 12 0 0 1-12-12V62a12 12 0 0 1 12-12z" fill="url(#cd)"/>
<circle cx="112" cy="72" r="14.5" fill="#FFD66E"/>
<path d="M105.6 69.4q2.4-2.6 4.8 0M113.6 69.4q2.4-2.6 4.8 0" fill="none" stroke="#2A1F3D" stroke-width="2" stroke-linecap="round"/>
<path d="M105.2 75.4Q112 83.4 118.8 75.4Z" fill="#2A1F3D"/>
<ellipse cx="103.4" cy="76.4" rx="2.4" ry="1.5" fill="#FF6F96" opacity=".4"/><ellipse cx="120.6" cy="76.4" rx="2.4" ry="1.5" fill="#FF6F96" opacity=".4"/></g>"""
    val mood: String = run {
        val bars = listOf(Triple(66, 108, 6), Triple(72, 104, 14), Triple(78, 101, 20), Triple(84, 104, 14), Triple(90, 108, 6))
            .joinToString("") { (x, y, h) -> "<rect x=\"${x - 2}\" y=\"${y - h / 2f}\" width=\"4\" height=\"$h\" rx=\"2\" fill=\"#7c10c6\" opacity=\".75\"/>" }
        """<svg $NS viewBox="0 0 150 130">$MOOD_DEFS
<circle cx="78" cy="68" r="62" fill="url(#bg)"/>
<path d="M44 40C58 16 96 14 112 36" fill="none" stroke="url(#ar)" stroke-width="3.4" stroke-linecap="round" stroke-dasharray="2 7"/>
<path d="M105.5 30.5 113 37.2l-9.6 2.6" fill="none" stroke="#db2777" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round"/>
$MOOD_LOW $MOOD_HIGH $bars
${spark(22f, 30f, 5.4f, "#f5b301")}${spark(140f, 104f, 3.8f, "#ff5c9a")}${spark(30f, 114f, 3.2f, "#a78bfa")}
</svg>"""
    }
    val moodShadow = listOf(Svg.Shadow("""<svg $NS viewBox="0 0 150 130">$MOOD_DEFS$MOOD_LOW$MOOD_HIGH</svg>""", 0f, 6f, 5f, G.c("#4c0fa8"), 0.26f))

    // ── Heart Chase Boxes ──
    private const val HC_DEFS = """<defs>
<radialGradient id="bg" cx=".5" cy=".5" r=".5"><stop offset="0" stop-color="#f6d9ec"/><stop offset=".7" stop-color="#f6e6fb" stop-opacity=".6"/><stop offset="1" stop-color="#f7eefe" stop-opacity="0"/></radialGradient>
<linearGradient id="op" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#ff86c2"/><stop offset=".5" stop-color="#d43bb8"/><stop offset="1" stop-color="#7a22e0"/></linearGradient></defs>"""
    private val hcBoxes: String = run {
        val sb = StringBuilder("<g>")
        for (r in 0 until 3) for (c in 0 until 3) {
            if (r == 1 && c == 1) continue
            val x = 30 + c * 32; val y = 30 + r * 32; val blue = (r + c) % 2 == 0
            sb.append("<rect x=\"$x\" y=\"$y\" width=\"26\" height=\"26\" rx=\"8\" fill=\"#fff\" stroke=\"${if (blue) "#bcd8ff" else "#ffcbe4"}\" stroke-width=\"1.6\"/>")
            sb.append("<path d=\"$HEART\" transform=\"translate(${x + 6.4f} ${y + 7}) scale(.44)\" fill=\"${if (blue) "#9cc4ff" else "#ffb3d6"}\"/>")
        }
        sb.append("<rect x=\"58\" y=\"58\" width=\"30\" height=\"30\" rx=\"9\" fill=\"url(#op)\"/>")
        sb.append("<path d=\"$HEART\" transform=\"translate(64.4 65.4) scale(.6)\" fill=\"#fff\"/></g>")
        sb.toString()
    }
    val heart = """<svg $NS viewBox="0 0 150 130">$HC_DEFS
<circle cx="76" cy="66" r="62" fill="url(#bg)"/>$hcBoxes
${spark(26f, 26f, 5f, "#ff5c9a")}${spark(126f, 40f, 3.6f, "#a78bfa")}${spark(120f, 104f, 4.4f, "#f5b301")}
</svg>"""
    val heartShadow = listOf(Svg.Shadow("""<svg $NS viewBox="0 0 150 130">$HC_DEFS$hcBoxes</svg>""", 0f, 5f, 4.5f, G.c("#5b0f9e"), 0.24f))

    // ── white magnifier with "?" (ClueMe sheet) ──
    const val clueIcon = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-linecap="round" stroke-linejoin="round">
<circle cx="10.4" cy="10.4" r="7.1" stroke-width="2.3"/><path d="m15.7 15.7 5.4 5.4" stroke-width="2.9"/>
<path d="M8.3 8.5a2.2 2.2 0 1 1 3.1 2c-.7.3-1 .8-1 1.5" stroke-width="1.9"/><circle cx="10.4" cy="14.5" r=".45" fill="#fff" stroke-width="1.3"/></svg>"""

    // ── coming soon icons ──
    val soonCrush = """<svg $NS viewBox="0 0 34 30"><defs>$IC_G</defs>
<path d="$HEART" transform="translate(.6 2.4) scale(1.24)" fill="url(#tgIcG)"/>
<path d="M11 10.2c1.2-1.6 3.4-2 5-1" stroke="#fff" stroke-opacity=".55" stroke-width="1.8" stroke-linecap="round" fill="none"/>
${spark(30.4f, 4.6f, 3.4f, "#ff5c9a")}</svg>"""
    val soonFlirty = """<svg $NS viewBox="0 0 26 32"><defs>$IC_G</defs>
<path d="M13.4 1.2c1.1 4.5 4.4 6.9 6.8 10.4 2.7 3.9 3.3 8.5.9 12.4C18.9 27.7 16.1 30.4 12.8 30.4 7.9 30.4 3.4 26.5 3.4 20.8c0-3.4 1.6-6.1 3.7-8.1.2 2.4 1.2 4.1 2.9 4.9-.7-5.7 1.4-11.4 3.4-16.4z" fill="url(#tgIcG)"/>
<path d="M13.1 17.2c2.7 2.2 4.5 4.3 4.5 7 0 2.6-2.1 4.5-4.7 4.5s-4.7-1.9-4.7-4.5c0-2.4 1.7-4.4 4.9-7z" fill="#fff" opacity=".5"/></svg>"""
    val soonDeep = """<svg $NS viewBox="0 0 32 32"><defs>$IC_G</defs>
<path d="M19.6 2.6A13.4 13.4 0 1 0 29.6 22.4 11 11 0 0 1 19.6 2.6z" fill="url(#tgIcG)"/>
${spark(26.4f, 6.4f, 3.2f, "#ff5c9a")}${spark(21.6f, 14.4f, 1.9f, "#a78bfa")}</svg>"""
}
