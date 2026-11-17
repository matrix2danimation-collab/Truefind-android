package app.truefind.android

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Random
import java.util.TimeZone

/**
 * Random Match: who may be rung, and in what order — the same rules as
 * startRandom() in the web's src/pages/Home.jsx (idOf, genderOf, isBusy,
 * isOnline, isFree, isMe, shuffle, lastMatch). Plain Kotlin (no Android), so
 * it is checked on a computer before every push.
 */
object RandomPick {

    /** A profile card, read through [Obj] (JSONObject in the app, a Map in the checks). */
    interface Obj {
        /** The value as text; "" when missing, null or empty (like the web's pick()). */
        fun str(key: String): String
        /** true only for a real JSON true. */
        fun isTrue(key: String): Boolean
        /** true for any "truthy" JSON value (true, a non-zero number, a non-empty text). */
        fun truthy(key: String): Boolean
    }

    class Cand(
        val key: String,      // p.id || idOf(p) — used to drop repeats
        val callId: String,   // idOf(p): who startCall rings
        val name: String,     // first name for "Calling Riya…"
        val gender: String,   // "f" | "m" | ""
        val free: Boolean,    // online and not on a call
        val ids: List<String>, // id, user_id, created_by_id (to leave myself out)
    )

    private fun pick(o: Obj, vararg keys: String): String {
        for (k in keys) { val v = o.str(k); if (v.isNotEmpty()) return v }
        return ""
    }

    /** idOf = pick(user_id, created_by_id, owner_id, id) */
    fun idOf(o: Obj): String = pick(o, "user_id", "created_by_id", "owner_id", "id")

    /** genderOf: f / female / woman / girl → "f", m / male / man / boy → "m" */
    fun genderOf(raw: String): String = when (raw.trim().lowercase(Locale.ROOT)) {
        "f", "female", "woman", "girl" -> "f"
        "m", "male", "man", "boy" -> "m"
        else -> ""
    }

    private fun callState(o: Obj) = pick(o, "call_status", "call_state", "availability", "status").lowercase(Locale.ROOT)

    fun isBusy(o: Obj): Boolean =
        o.truthy("is_busy") || o.truthy("busy") || o.truthy("in_call") || o.truthy("on_call") ||
            callState(o) in setOf("busy", "in_call", "on_call", "matching", "ringing", "calling")

    fun isOnline(o: Obj, now: Long = System.currentTimeMillis()): Boolean {
        if (o.isTrue("online") || o.isTrue("is_online")) return true
        val pr = pick(o, "presence", "online_status").lowercase(Locale.ROOT)
        if (pr == "online" || pr == "active") return true
        if (pr == "offline") return false
        if (callState(o) in setOf("online", "available")) return true
        val t = parseTime(pick(o, "last_active", "last_seen", "last_seen_at", "last_active_at", "online_at"))
        return t > 0 && now - t < 5 * 60 * 1000
    }

    fun isFree(o: Obj, now: Long = System.currentTimeMillis()) = isOnline(o, now) && !isBusy(o)

    fun cand(o: Obj, now: Long = System.currentTimeMillis()): Cand {
        val name = pick(o, "full_name", "name", "display_name").trim()
        val callId = idOf(o)
        return Cand(
            key = o.str("id").ifEmpty { callId },
            callId = callId,
            name = name.split(Regex("\\s+")).firstOrNull().orEmpty(),
            gender = genderOf(pick(o, "gender", "sex")),
            free = isFree(o, now),
            ids = listOf(o.str("id"), o.str("user_id"), o.str("created_by_id")).filter { it.isNotEmpty() },
        )
    }

    /**
     * Who to ring, in order: not me, free, with an id; only the other gender
     * (never a fallback to the same gender); shuffled; the person matched last
     * time goes to the back. The web then tries the first 4.
     */
    fun order(list: List<Cand>, myIds: Set<String>, myGender: String, lastMatch: String?, rnd: Random = Random()): List<Cand> {
        val seen = HashSet<String>()
        val unique = list.filter { it.key.isNotEmpty() && seen.add(it.key) } // uniq()
        val usable = unique.filter { c -> c.ids.none { it in myIds } && c.free && c.callId.isNotEmpty() }
        val opposite = if (myGender.isNotEmpty()) usable.filter { it.gender.isNotEmpty() && it.gender != myGender } else usable
        val pool = opposite.toMutableList()
        // shuffle: same Fisher–Yates as the web
        for (i in pool.size - 1 downTo 1) { val j = rnd.nextInt(i + 1); val t = pool[i]; pool[i] = pool[j]; pool[j] = t }
        if (pool.size > 1 && pool[0].callId == lastMatch) pool.add(pool.removeAt(0))
        return pool
    }

    /**
     * "2026-11-17T14:05:09.123Z" / "…+05:30" → ms; 0 when unreadable. A time with
     * no zone is the phone's own time, like Date.parse in the web page.
     */
    fun parseTime(s: String, zone: TimeZone = TimeZone.getDefault()): Long {
        val t = s.trim()
        if (t.length < 19) return 0L
        return try {
            val rest0 = t.substring(19).let { r -> if (r.startsWith(".")) r.drop(1).dropWhile { it.isDigit() } else r }
            val hasZone = rest0 == "Z" || rest0 == "z" || Regex("^[+-]\\d{2}:?\\d{2}$").matches(rest0)
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { timeZone = if (hasZone) TimeZone.getTimeZone("UTC") else zone }
            var ms = f.parse(t.substring(0, 19).replace(' ', 'T'))?.time ?: return 0L
            var rest = t.substring(19)
            if (rest.startsWith(".")) {
                val digits = rest.drop(1).takeWhile { it.isDigit() }
                if (digits.isNotEmpty()) ms += (digits.take(3).padEnd(3, '0')).toLong()
                rest = rest.drop(1 + digits.length)
            }
            val m = Regex("^([+-])(\\d{2}):?(\\d{2})$").find(rest)
            if (m != null) {
                val off = (m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()) * 60000L
                ms -= if (m.groupValues[1] == "+") off else -off
            }
            ms
        } catch (e: Exception) {
            0L
        }
    }
}
