package app.truefind.android

import java.text.Normalizer

/**
 * Chat safety — the web's messageProblem() in src/pages/ChatRoom.jsx, rule for rule:
 * no numbers (digits / words / Hindi / Roman), emails, links, IDs, social apps or abuse.
 * A message with any of these is NOT sent: it stays only on your phone with a red ✕.
 * Returns the reason ("" = fine to send).
 */
object ChatSafety {
    private val NUM_WORDS = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "shunya" to 0, "shoonya" to 0, "sunya" to 0, "ek" to 1, "do" to 2, "teen" to 3, "tin" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5, "pach" to 5,
        "chhe" to 6, "chhah" to 6, "che" to 6, "cheh" to 6, "saat" to 7, "sat" to 7, "aath" to 8, "ath" to 8, "aat" to 8, "nau" to 9, "no" to 9,
    )
    private val ROMAN = mapOf("i" to 1, "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9)
    private val HI_WORDS = listOf("शून्य" to "0", "एक" to "1", "दो" to "2", "तीन" to "3", "चार" to "4", "पांच" to "5", "पाँच" to "5", "छह" to "6", "छः" to "6", "छे" to "6", "सात" to "7", "आठ" to "8", "नौ" to "9")
    private const val I = "(?i)"
    private val ABUSE = Regex("$I\\b(f+u+c+k+\\w*|fa+ck+(ing|er|ed|s)?|phu+c?k+\\w*|f+u+k+\\w*|fck\\w*|motherf\\w*|b+i+t+c+h+\\w*|bastard\\w*|slut\\w*|whore\\w*|cunt\\w*|dick(head)?s?|asshole\\w*|chut(iya|iye|ia|iyapa)?|madar ?chod\\w*|maa? ?ki ?chut\\w*|beh?e?n ?chod\\w*|bhen ?chod\\w*|bhosd\\w*|gaa?ndu\\b|gaand\\w*|g[a@]nd\\b|lund\\w*|lau?da\\w*|lavd\\w*|lawd\\w*|randi\\w*|harami\\w*|kamin[ai]\\w*|jhaat\\w*|chodu\\b|chodna\\b)\\b")
    private val ABUSE_HI = Regex("(मादरचोद|मादर चोद|बहनचोद|बहन चोद|भेनचोद|भोसड़|भोसड|चूतिय|चूत|गांडू|गाँडू|गांड|लौड़|लौडा|लंड|रंडी|हरामी|कमीन|झाट)")
    private val LOOKALIKE = mapOf(
        'ᴀ' to 'a', 'ʙ' to 'b', 'ᴄ' to 'c', 'ᴅ' to 'd', 'ᴇ' to 'e', 'ꜰ' to 'f', 'ғ' to 'f', 'ɢ' to 'g', 'ʜ' to 'h', 'ɪ' to 'i', 'ᴊ' to 'j', 'ᴋ' to 'k', 'ʟ' to 'l', 'ᴍ' to 'm', 'ɴ' to 'n', 'ᴏ' to 'o', 'ᴘ' to 'p', 'ǫ' to 'q', 'ʀ' to 'r', 'ꜱ' to 's', 'ᴛ' to 't', 'ᴜ' to 'u', 'ᴠ' to 'v', 'ᴡ' to 'w', 'ʏ' to 'y', 'ᴢ' to 'z',
        'а' to 'a', 'в' to 'b', 'с' to 'c', 'е' to 'e', 'һ' to 'h', 'і' to 'i', 'ј' to 'j', 'к' to 'k', 'м' to 'm', 'н' to 'h', 'о' to 'o', 'р' to 'p', 'ѕ' to 's', 'т' to 't', 'у' to 'y', 'х' to 'x', 'ԁ' to 'd', 'ԛ' to 'q', 'ԝ' to 'w',
        'α' to 'a', 'ε' to 'e', 'ι' to 'i', 'κ' to 'k', 'ν' to 'v', 'ο' to 'o', 'ρ' to 'p', 'τ' to 't', 'υ' to 'u', 'χ' to 'x',
    )
    private const val SHORT_APPS = "fb|ig|yt|tg|sc|snap|insta|wp|whtsp|wtsp"
    private val EXTRA_RULES = listOf(
        Regex("$I\\b(?:$SHORT_APPS)\\s*(?:pe|par|pr|per|me|mein|mai|on|id|ids|account|acc|handle|channel|username|user\\s*name|profile|link)\\b"),
        Regex("$I\\b(?:on|pe|par|pr|via|at)\\s+(?:$SHORT_APPS)\\b"),
        Regex("$I\\b(?:$SHORT_APPS)\\s+(?:karo|kar\\s*do|kro|kar\\s*lo|follow|add|msg|message|dm)\\b"),
        Regex("$I\\b(you\\s*tube|linked\\s*in|share\\s*chat|threads\\s*app|hike\\s*app|imo\\s*app|viber|tinder|bumble|hinge|omegle|true\\s*caller|google\\s*meet|botim|kik\\s*app)\\b"),
        Regex("$I\\b(koi|apni|apna|teri|tera|tumhari|tumhara|aapki|aapka|your|ur|my|mera|meri|send|share|de|do|dedo|bhej\\w*)\\s+(i\\.?\\s?d\\.?|user\\s*name|user\\s*id)(?![a-z])"),
        Regex("$I\\b(i\\.?\\s?d|user\\s*name|user\\s*id)\\s+(do|de|dedo|dena|dijiye|bhej\\w*|send|share|batao|bata|bta|btao|chahiye|milegi|mil\\s*jayegi)\\b"),
    )
    private val EXTRA_RULES_HI = listOf(
        Regex("(आईडी|आइडी|यूजरनेम|यूज़रनेम)\\s*(दो|दे|दें|दीजिए|भेज|बताओ|बता|चाहिए)"),
        Regex("(अपनी|अपना|मेरी|मेरा|तेरी|तेरा|तुम्हारी|आपकी|कोई)\\s*(आईडी|आइडी|यूजरनेम|यूज़रनेम)"),
        Regex("(यूट्यूब|लिंक्डइन|शेयरचैट|ट्रूकॉलर)"),
    )
    private val SQUEEZE = Regex("(\\d)[\\s.\\-_/\\\\,()*#+|:;'\"~`^=<>\\[\\]{}]+(\\d)")
    private val EMAIL1 = Regex("$I[a-z0-9._%+-]+\\s*@\\s*[a-z0-9-]+\\s*(\\.|\\(dot\\)|\\[dot\\]|\\s+dot\\s+)\\s*[a-z]{2,}")
    private val EMAIL2 = Regex("$I[a-z0-9._%+-]+\\s*(\\(at\\)|\\[at\\]|\\{at\\}|\\bat\\s*the\\s*rate\\b|\\battherate\\b)")
    private val EMAIL3 = Regex("$I\\b(g\\s*mail|ymail|yahoo\\s*mail|hotmail|rediff\\s*mail|e-?mail|mail\\s*id)\\b")
    private val EMAIL_HI = Regex("(जीमेल|ईमेल|मेल आईडी)")
    private val LINK = Regex("$I(https?://|www\\.|w\\s*w\\s*w\\s*\\.|\\b[a-z0-9-]{2,}\\.(com|in|net|org|me|io|co|app|xyz|link|ly|gg|tv|info|biz|site|online|store|live|club|page|to|be)\\b|\\b[a-z0-9-]{2,}\\s*(\\(dot\\)|\\[dot\\]|\\s+dot\\s+)\\s*(com|in|net|org|me|io|co|app)\\b|youtu\\.?be|bit\\.ly|tinyurl)")
    private val HANDLE = Regex("$I@[a-z0-9_.]{3,}")
    private val SOCIAL = Regex("$I\\b(insta(gram)?|ig\\s*id|telegram|t\\.me|tele\\s*gram|whats\\s*app|whatsapp|watsapp|wa\\.me|snap\\s*chat|snapchat|snap\\s*id|facebook|fb\\s*id|twitter|discord|signal\\s*app|wechat|skype|gmail|e-?mail|my\\s*id|mera\\s*id|meri\\s*id|apni\\s*id|teri\\s*id|tumhari\\s*id|your\\s*id|id\\s*(bhejo|bhej\\s*do|de\\s*do|dedo|share|send)|dm\\s*me|upi|paytm|phonepe|gpay)\\b")
    private val SOCIAL_HI = Regex("(इंस्टा|इन्स्टा|टेलीग्राम|व्हाट्स|वॉट्स|स्नैप|फेसबुक)")
    private val NUMBER_ASK = Regex("$I\\b(mera|my|apna|tera|your|tumhara|call)\\s*(no\\.?|num|number|nmbr|numbr|contact)\\b")
    private val NUMBER_ASK_HI = Regex("(मेरा|अपना)\\s*(नंबर|नम्बर)")

    fun problem(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        val raw = input.replace(Regex("[\\u00ad\\u180e\\u200b-\\u200f\\u2060-\\u2064\\ufeff]"), "")
        var t = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        t = buildString(t.length) { for (ch in t) append(LOOKALIKE[ch] ?: ch) }.lowercase().replace("️", "").replace("⃣", "")
        t = buildString(t.length) { for (ch in t) append(if (ch in '०'..'९') ('0' + (ch - '०')) else ch) }
        for ((w, d) in HI_WORDS) t = t.split(w).joinToString(" $d ")
        // abuse (also l33t-speak like "f@ck", "ch00tiya", "b!tch")
        val plain = t.replace(Regex("[@4]"), "a").replace("0", "o").replace(Regex("[1!|]"), "i").replace(Regex("[\$5]"), "s").replace("3", "e").replace(Regex("(.)\\1{2,}"), "\$1\$1")
        if (ABUSE.containsMatchIn(plain) || ABUSE.containsMatchIn(plain.replace(Regex("[\\s.*_-]+"), "")) || ABUSE_HI.containsMatchIn(raw)) return "Abusive words aren't allowed. Please be respectful."
        // digits split by spaces, dots, dashes…
        var squeezed = t
        repeat(6) { squeezed = squeezed.replace(SQUEEZE, "\$1\$2") }
        if (Regex("\\d{7,}").containsMatchIn(squeezed)) return "Phone numbers aren't allowed."
        // numbers in words ("nine eight…", "nau aath…", "double nine…") or Roman numerals ("ix viii vii…")
        val tokens = t.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        var run = ""; var words = 0; var times = 1
        for (tok in tokens) {
            if (tok == "double" || tok == "dubble") { times = 2; continue }
            if (tok == "triple" || tok == "tripple") { times = 3; continue }
            var d: String? = null
            if (tok.all { it.isDigit() }) d = tok
            else if (NUM_WORDS[tok] != null) { d = NUM_WORDS[tok].toString(); words += 1 }
            else if (ROMAN[tok] != null) { d = ROMAN[tok].toString(); words += 1 }
            if (d == null) { run = ""; words = 0; times = 1; continue }
            run += d.repeat(times); times = 1
            if (run.length >= 7 && (words >= 3 || tok.all { it.isDigit() })) return "Phone numbers aren't allowed, even in words."
        }
        if (EMAIL1.containsMatchIn(t) || EMAIL2.containsMatchIn(t) || EMAIL3.containsMatchIn(t) || EMAIL_HI.containsMatchIn(raw)) return "Email addresses aren't allowed."
        if (LINK.containsMatchIn(t)) return "Links aren't allowed."
        if (HANDLE.containsMatchIn(t)) return "IDs and @handles aren't allowed."
        if (SOCIAL.containsMatchIn(t) || SOCIAL_HI.containsMatchIn(raw)) return "Social media IDs aren't allowed."
        if (NUMBER_ASK.containsMatchIn(t) || NUMBER_ASK_HI.containsMatchIn(raw)) return "Sharing numbers isn't allowed."
        if (EXTRA_RULES.any { it.containsMatchIn(t) } || EXTRA_RULES_HI.any { it.containsMatchIn(raw) }) return "Social media IDs aren't allowed."
        return ""
    }
}
