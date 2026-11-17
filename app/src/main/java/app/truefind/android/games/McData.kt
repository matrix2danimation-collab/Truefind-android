package app.truefind.android.games

/** Mood Changer — labels, faces and colours (same as mcData.js / mcUi.jsx). Questions live on the server. */
object McData {
    class Mood(val key: String, val label: String, val hi: String, val fam: String, val v: Double, val tint: String)

    val MOODS = listOf(
        Mood("happy", "Happy", "खुश", "positive", 2.0, "#FFD66E"),
        Mood("sad", "Sad", "उदास", "low", -2.0, "#A9C4FF"),
        Mood("stressed", "Stressed", "तनाव में", "tense", -1.5, "#FFB3A1"),
        Mood("lonely", "Lonely", "अकेलापन", "low", -1.5, "#BDB5F7"),
        Mood("bored", "Bored", "बोर", "flat", -0.5, "#DCD5C8"),
        Mood("anxious", "Anxious", "घबराहट", "tense", -1.5, "#F8CB7F"),
        Mood("angry", "Angry", "ग़ुस्से में", "tense", -1.5, "#FF9494"),
        Mood("tired", "Tired", "थकान", "flat", -0.5, "#CEC7EC"),
        Mood("confused", "Confused", "उलझन में", "flat", -0.5, "#F2D78F"),
        Mood("relaxed", "Relaxed", "सुकून में", "positive", 1.5, "#A6E6C8"),
        Mood("romantic", "Romantic", "रोमांटिक", "positive", 1.5, "#FFA8CB"),
        Mood("excited", "Excited", "उत्साहित", "positive", 2.0, "#FFBE6E"),
        Mood("disappointed", "Disappointed", "निराश", "low", -1.5, "#BAC6DC"),
        Mood("fearful", "Fearful", "डर", "tense", -1.5, "#CDBCF5"),
        Mood("unmotivated", "Unmotivated", "मन नहीं लग रहा", "low", -1.0, "#CFC9BF"),
        Mood("grateful", "Grateful", "आभारी", "positive", 2.0, "#FFCFA8"),
        Mood("curious", "Curious", "जिज्ञासु", "positive", 1.0, "#A6DBFF"),
        Mood("overwhelmed", "Overwhelmed", "बोझ तले", "tense", -1.5, "#F7B2CE"),
        Mood("neutral", "Neutral", "सामान्य", "flat", 0.0, "#DDD9E7"),
        Mood("unknown", "Not sure", "पता नहीं", "flat", 0.0, "#E7E3F0"),
    )
    private val BY = MOODS.associateBy { it.key }
    fun moodOf(k: String?): Mood = BY[k] ?: BY["neutral"]!!
    fun has(k: String?) = k != null && BY.containsKey(k)

    val INTENSITY = listOf("A little", "Mild", "Moderate", "Strong", "Very strong")
    val PREFS = listOf("listen" to "Just listen to me", "laugh" to "Make me laugh", "deep" to "Deep talk", "light" to "Light, casual chat", "ideas" to "Fresh perspective")
    val PREF = PREFS.toMap()

    class Level(val n: Int, val key: String, val title: String, val blurb: String, val depth: Int)
    val LEVELS = listOf(
        Level(1, "l1", "Check-in", "Start light: how are you, really?", 1),
        Level(2, "l2", "What Happened", "The story behind the mood", 2),
        Level(3, "l3", "How It Feels", "Put the feeling into words", 3),
        Level(4, "l4", "Going Deeper", "What it means and what you need", 4),
        Level(5, "l5", "Your Strength", "What helps you, who supports you", 3),
        Level(6, "l6", "Lighten Up", "Something hopeful and fun", 2),
        Level(7, "l7", "Warm Ending", "End on a kind note", 1),
    )
    val STAGE = listOf("Light start", "Getting deeper", "Getting deeper", "The deepest round", "Coming back up", "Coming back up", "Warm ending")
    val CHEERS = listOf("", "Nice start. You are both sharing.", "Good going. You are listening to each other.", "This takes trust. Thank you both.", "You made it through the deepest part.", "Lovely energy between you two.", "Last round. Let's end on a warm note.")

    /** key, English, Hindi, icon (Lucide name) */
    class Reason(val key: String, val en: String, val hi: String, val icon: String)
    private val NEGATIVE = listOf(
        Reason("fight", "A fight or argument", "झगड़ा या बहस", "Swords"),
        Reason("hurtful", "Someone said something hurtful", "किसी की कोई बात चुभ गई", "MessageCircleWarning"),
        Reason("treated_badly", "Someone treated me badly", "किसी ने मेरे साथ बुरा किया", "ShieldAlert"),
        Reason("family", "Family problems", "घर-परिवार की परेशानी", "House"),
        Reason("breakup", "Breakup or relationship trouble", "ब्रेकअप या रिश्ते की परेशानी", "HeartCrack"),
        Reason("alone", "No one to talk to", "बात करने वाला कोई नहीं", "UserX"),
        Reason("pressure", "Work or study pressure", "काम या पढ़ाई का दबाव", "Briefcase"),
        Reason("unwell", "Not feeling well", "तबीयत ठीक नहीं", "Thermometer"),
        Reason("just", "Just not feeling good", "बस अच्छा नहीं लग रहा", "Cloud"),
    )
    val REASONS = mapOf(
        "low" to NEGATIVE, "tense" to NEGATIVE,
        "positive" to listOf(
            Reason("goodnews", "Good news", "कोई अच्छी खबर", "Megaphone"),
            Reason("people", "Time with people I love", "अपनों के साथ अच्छा वक़्त", "Users"),
            Reason("achievement", "I achieved something", "कुछ हासिल किया", "Trophy"),
            Reason("someone", "Met someone special", "किसी ख़ास से मुलाक़ात", "Heart"),
            Reason("rest", "A relaxing day", "आराम का दिन", "Sun"),
            Reason("lookforward", "Something to look forward to", "किसी चीज़ का इंतज़ार", "CalendarHeart"),
            Reason("just", "No special reason", "कोई ख़ास वजह नहीं", "Smile"),
        ),
        "flat" to listOf(
            Reason("routine", "Same routine every day", "रोज़ का एक-सा रूटीन", "Repeat"),
            Reason("pressure", "Too much work or study", "काम या पढ़ाई बहुत ज़्यादा", "Briefcase"),
            Reason("rest_low", "Not enough sleep or rest", "नींद या आराम कम", "Moon"),
            Reason("nothing", "Nothing exciting happening", "कुछ ख़ास नहीं हो रहा", "Meh"),
            Reason("busy_mind", "Too much on my mind", "मन में बहुत कुछ चल रहा", "Brain"),
            Reason("just", "Just like that", "बस ऐसे ही", "Cloud"),
        ),
    )
    val TOPICS = listOf("daily" to "Daily life", "interests" to "Interests", "hobbies" to "Hobbies", "memories" to "Memories",
        "travel" to "Travel", "food" to "Food", "music" to "Music", "personality" to "Personality",
        "dreams" to "Dreams", "relaxation" to "Relaxation", "appreciation" to "Appreciation", "support" to "Emotional support")
    val REPORT_REASONS = listOf("Rude or abusive language", "Sexual or inappropriate talk", "Harassment or threats", "Hate speech",
        "Asked for personal details", "Seems under 18", "Fake profile", "Something else")
    /** icon, title, text */
    val RULES = listOf(
        Triple("Headphones", "Live voice call", "The game runs on a voice call. There is no text chat, and you can mute anytime."),
        Triple("Heart", "Mood sharing", "Your mood is shown only to your partner, and only during this game. The reason behind it always stays private."),
        Triple("Lock", "Keep it private", "Don’t share your partner’s mood or personal details outside the game. No phone numbers, addresses or social handles."),
        Triple("HandHeart", "Be kind", "No rude, sexual or disrespectful talk. Report and block are one tap away."),
        Triple("Shuffle", "You’re in control", "Skip a question, change the topic or leave whenever you want."),
        Triple("LifeBuoy", "Not medical advice", "This is friendly support between players, not medical or professional advice."),
    )

    fun fam(k: String?) = BY[k]?.fam ?: "flat"
    fun isHeavyFam(f: String) = f == "low" || f == "tense"

    /** history items: mood, intensity, final_mood, final_intensity */
    class Hist(val mood: String, val intensity: Int, val finalMood: String?, val finalIntensity: Int?)

    fun repeatMood(history: List<Hist>, mood: String): Boolean {
        val last = history.takeLast(3)
        return last.size >= 3 && last.all { it.mood == mood } && isHeavyFam(fam(mood))
    }
    fun changeOf(sMood: String, sInt: Int, nMood: String, nInt: Int): String {
        val sH = isHeavyFam(fam(sMood)); val nH = isHeavyFam(fam(nMood))
        if (sH && !nH) return "lighter"
        if (!sH && nH) return "heavier"
        if (sH && nH) return if (nInt < sInt) "lighter" else if (nInt > sInt) "heavier" else "same"
        val dv = moodOf(nMood).v - moodOf(sMood).v
        return if (dv >= 1) "lighter" else if (fam(nMood) == "positive") "good" else "same"
    }
    fun needsSupport(history: List<Hist>, nMood: String, nInt: Int, change: String): Boolean {
        val heavyNow = isHeavyFam(fam(nMood)) && nInt >= 4
        // web: (h.final_intensity || h.intensity) >= 3
        val recentHeavy = history.takeLast(4).count { isHeavyFam(fam(it.finalMood ?: it.mood)) && ((it.finalIntensity?.takeIf { v -> v != 0 }) ?: it.intensity) >= 3 } >= 3
        return heavyNow || change == "heavier" || (recentHeavy && isHeavyFam(fam(nMood)))
    }
    fun fmtClock(s: Int) = String.format("%02d:%02d", s / 60, s % 60)
    fun fmtShort(s: Int) = String.format("%d:%02d", s / 60, s % 60)

    // ───────── mood faces (the same flat set as the web, drawn from the same shapes) ─────────
    private const val INK = "#2A1F3D"
    private class Face(val eyes: String, val brows: String? = null, val mouth: String, val x: List<String> = emptyList())
    private val FACES = mapOf(
        "happy" to Face("dot", null, "grin", listOf("blush")),
        "sad" to Face("dot", "sad", "frown", listOf("tear")),
        "stressed" to Face("dot", "sad", "wavy", listOf("sweat")),
        "lonely" to Face("down", null, "frownSm"),
        "bored" to Face("half", null, "flat"),
        "anxious" to Face("wide", "sad", "tight", listOf("sweat")),
        "angry" to Face("dot", "angry", "frownTight"),
        "tired" to Face("closed", null, "yawn", listOf("zzz")),
        "confused" to Face("dot", "raise", "side", listOf("q")),
        "relaxed" to Face("content", null, "smile"),
        "romantic" to Face("heart", null, "smile", listOf("blush")),
        "excited" to Face("happy", null, "grin", listOf("sparkle")),
        "disappointed" to Face("down", "flatSad", "frown"),
        "fearful" to Face("wide", "high", "o"),
        "unmotivated" to Face("half", null, "frownSm"),
        "grateful" to Face("content", null, "smile", listOf("heart")),
        "curious" to Face("up", "raise", "smallO"),
        "overwhelmed" to Face("squeeze", null, "wavy", listOf("sweat")),
        "neutral" to Face("dot", null, "flat"),
        "unknown" to Face("dot", null, "side", listOf("q")),
    )
    private const val S = "stroke=\"$INK\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" fill=\"none\""
    private fun f(v: Double): String { val r = Math.round(v * 1000.0) / 1000.0; return if (r == Math.floor(r)) r.toLong().toString() else r.toString() }
    private fun heartAt(x: Double, y: Double, s: Double = 1.0) =
        "M${f(x)} ${f(y + 1.9 * s)}c${f(-2.9 * s)} ${f(-1.9 * s)} ${f(-2.9 * s)} ${f(-4.4 * s)} ${f(-1 * s)} ${f(-4.5 * s)}c${f(0.6 * s)} 0 ${f(1 * s)} ${f(0.4 * s)} ${f(1 * s)} ${f(0.9 * s)}c0 ${f(-0.5 * s)} ${f(0.4 * s)} ${f(-0.9 * s)} ${f(1 * s)} ${f(-0.9 * s)}c${f(1.9 * s)} ${f(0.1 * s)} ${f(1.9 * s)} ${f(2.6 * s)} ${f(-1 * s)} ${f(4.5 * s)}z"

    private fun eyes(t: String): String {
        val L = 14.2; val R = 25.8; val Y = 18.6
        return when (t) {
            "wide" -> "<circle cx=\"${f(L)}\" cy=\"${f(Y)}\" r=\"2.6\" fill=\"$INK\"/><circle cx=\"${f(R)}\" cy=\"${f(Y)}\" r=\"2.6\" fill=\"$INK\"/><circle cx=\"${f(L + 0.8)}\" cy=\"${f(Y - 0.8)}\" r=\"0.75\" fill=\"#fff\"/><circle cx=\"${f(R + 0.8)}\" cy=\"${f(Y - 0.8)}\" r=\"0.75\" fill=\"#fff\"/>"
            "down" -> "<circle cx=\"${f(L)}\" cy=\"${f(Y + 1.4)}\" r=\"1.8\" fill=\"$INK\"/><circle cx=\"${f(R)}\" cy=\"${f(Y + 1.4)}\" r=\"1.8\" fill=\"$INK\"/>"
            "up" -> "<circle cx=\"${f(L + 0.9)}\" cy=\"${f(Y - 1)}\" r=\"1.9\" fill=\"$INK\"/><circle cx=\"${f(R + 0.9)}\" cy=\"${f(Y - 1)}\" r=\"1.9\" fill=\"$INK\"/>"
            "half" -> "<path d=\"M${f(L - 2.8)} ${f(Y - 0.6)}h5.6M${f(R - 2.8)} ${f(Y - 0.6)}h5.6\" $S/><circle cx=\"${f(L)}\" cy=\"${f(Y + 0.9)}\" r=\"1.35\" fill=\"$INK\"/><circle cx=\"${f(R)}\" cy=\"${f(Y + 0.9)}\" r=\"1.35\" fill=\"$INK\"/>"
            "closed" -> "<path d=\"M${f(L - 2.7)} ${f(Y + 0.6)}h5.4M${f(R - 2.7)} ${f(Y + 0.6)}h5.4\" $S/>"
            "content" -> "<path d=\"M${f(L - 2.7)} ${f(Y - 0.6)}q2.7 2.9 5.4 0M${f(R - 2.7)} ${f(Y - 0.6)}q2.7 2.9 5.4 0\" $S/>"
            "happy" -> "<path d=\"M${f(L - 2.7)} ${f(Y + 1)}q2.7 -3.2 5.4 0M${f(R - 2.7)} ${f(Y + 1)}q2.7 -3.2 5.4 0\" $S/>"
            "heart" -> "<g fill=\"#E8436E\"><path d=\"${heartAt(L, Y - 0.2, 1.05)}\"/><path d=\"${heartAt(R, Y - 0.2, 1.05)}\"/></g>"
            "squeeze" -> "<path d=\"M${f(L - 2.4)} ${f(Y - 2.2)}l4.4 2.2l-4.4 2.2M${f(R + 2.4)} ${f(Y - 2.2)}l-4.4 2.2l4.4 2.2\" $S/>"
            else -> "<circle cx=\"${f(L)}\" cy=\"${f(Y)}\" r=\"1.95\" fill=\"$INK\"/><circle cx=\"${f(R)}\" cy=\"${f(Y)}\" r=\"1.95\" fill=\"$INK\"/>"
        }
    }
    private fun brows(t: String?): String {
        val d = when (t) {
            "sad" -> "M11.2 15.2L16.6 13.4M23.4 13.4L28.8 15.2"
            "angry" -> "M11.2 13.2L16.6 15.6M23.4 15.6L28.8 13.2"
            "raise" -> "M11.6 14.6L16.4 14.1M23.6 13.4q2.4 -2.1 4.8 -0.3"
            "high" -> "M11.4 13.6q2.6 -2.2 5.2 -0.5M23.4 13.1q2.6 -1.7 5.2 0.5"
            "flatSad" -> "M11.6 15L16.4 14.1M23.6 14.1L28.4 15"
            else -> return ""
        }
        return "<path d=\"$d\" stroke=\"$INK\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" fill=\"none\"/>"
    }
    private fun mouth(t: String): String = when (t) {
        "grin" -> "<path d=\"M13.2 24.4Q20 32.6 26.8 24.4Z\" fill=\"$INK\" stroke=\"$INK\" stroke-width=\"1.4\" stroke-linejoin=\"round\"/>"
        "smile" -> "<path d=\"M14.4 25.2Q20 30 25.6 25.2\" $S/>"
        "frown" -> "<path d=\"M14.8 29Q20 24.4 25.2 29\" $S/>"
        "frownSm" -> "<path d=\"M16.2 28.4Q20 25.9 23.8 28.4\" $S/>"
        "frownTight" -> "<path d=\"M15.2 28.8Q20 25.2 24.8 28.8\" stroke=\"$INK\" stroke-width=\"2.2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" fill=\"none\"/>"
        "flat" -> "<path d=\"M15.6 27.2H24.4\" $S/>"
        "wavy" -> "<path d=\"M14.2 27.6q1.9 -1.9 3.8 0t3.8 0t3.8 0\" $S/>"
        "tight" -> "<path d=\"M15.8 27.8Q20 26.2 24.2 27.8\" $S/>"
        "side" -> "<path d=\"M15.8 27.9Q20.6 28.7 24.6 25.9\" $S/>"
        "o" -> "<ellipse cx=\"20\" cy=\"27.6\" rx=\"2.5\" ry=\"3\" fill=\"$INK\"/>"
        "smallO" -> "<ellipse cx=\"20.4\" cy=\"27.2\" rx=\"1.7\" ry=\"2\" fill=\"$INK\"/>"
        "yawn" -> "<ellipse cx=\"20\" cy=\"27.8\" rx=\"3.1\" ry=\"3.5\" fill=\"$INK\"/>"
        else -> ""
    }
    private fun extra(t: String): String = when (t) {
        "blush" -> "<g fill=\"#FF6F96\" opacity=\".32\"><ellipse cx=\"10.8\" cy=\"24\" rx=\"2.7\" ry=\"1.7\"/><ellipse cx=\"29.2\" cy=\"24\" rx=\"2.7\" ry=\"1.7\"/></g>"
        "tear" -> "<path d=\"M27.4 21.2c0 0-2.3 2.9-2.3 4.4a2.3 2.3 0 0 0 4.6 0c0-1.5-2.3-4.4-2.3-4.4z\" fill=\"#5B9BFF\"/>"
        "sweat" -> "<path d=\"M32.2 5.6c0 0-2.5 3.1-2.5 4.8a2.5 2.5 0 0 0 5 0c0-1.7-2.5-4.8-2.5-4.8z\" fill=\"#5FA4FF\"/>"
        "zzz" -> "<g fill=\"$INK\" font-weight=\"700\" font-family=\"sans-serif\"><text x=\"29\" y=\"11\" font-size=\"7.5\">z</text><text x=\"33.4\" y=\"6.8\" font-size=\"5.6\">z</text></g>"
        "q" -> "<text x=\"30.2\" y=\"11.4\" font-size=\"10.5\" font-weight=\"700\" fill=\"#7F00FF\" font-family=\"sans-serif\">?</text>"
        "sparkle" -> "<path d=\"M32.6 2.8l1.2 3.3 3.3 1.2-3.3 1.2-1.2 3.3-1.2-3.3-3.3-1.2 3.3-1.2z\" fill=\"#7F00FF\"/>"
        "heart" -> "<path d=\"${heartAt(32.4, 6.4, 1.35)}\" fill=\"#F2477A\"/>"
        else -> ""
    }
    private val faceCache = HashMap<String, String>()
    /** The mood face as SVG (viewBox 0 0 40 40). */
    fun face(mood: String?): String {
        val k = if (FACES.containsKey(mood)) mood!! else "neutral"
        return faceCache.getOrPut(k) {
            val fc = FACES[k]!!
            val sb = StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 40 40\">")
            sb.append("<circle cx=\"20\" cy=\"21\" r=\"17\" fill=\"${moodOf(k).tint}\"/>")
            sb.append("<circle cx=\"20\" cy=\"21\" r=\"16.5\" fill=\"none\" stroke=\"$INK\" stroke-opacity=\".07\"/>")
            sb.append(brows(fc.brows)).append(eyes(fc.eyes)).append(mouth(fc.mouth))
            for (x in fc.x) sb.append(extra(x))
            sb.append("</svg>").toString()
        }
    }
}
