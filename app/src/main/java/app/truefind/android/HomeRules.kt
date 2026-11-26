package app.truefind.android

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.roundToLong

/** The rules of Home.jsx (who is online, busy, verified, ages, faces …), word for word. */
object HomeRules {
    fun num(v: Any?): Double = when (v) { is Number -> v.toDouble().takeIf { it.isFinite() } ?: 0.0; is String -> v.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0; is Boolean -> if (v) 1.0 else 0.0; else -> 0.0 }
    /** pick(...): the first value that is not undefined / null / "" */
    fun pick(o: JSONObject?, vararg keys: String): Any? {
        if (o == null) return null
        for (k in keys) { val v = o.opt(k); if (v != null && v != JSONObject.NULL && v != "") return v }
        return null
    }
    fun str(o: JSONObject?, vararg keys: String): String = pick(o, *keys)?.toString() ?: ""

    fun srcOf(x: Any?): String = Tf.srcOf(x)
    private fun firstPhoto(a: Any?): String = if (a is JSONArray) { (0 until a.length()).map { srcOf(a.opt(it)) }.firstOrNull { it.isNotEmpty() } ?: "" } else ""
    fun photoOf(p: JSONObject?): String = srcOf(pick(p, "profile_picture", "photo", "avatar", "avatar_url", "photo_url", "image")).ifEmpty { firstPhoto(p?.opt("photos")) }.ifEmpty { firstPhoto(p?.opt("images")) }
    fun fullName(p: JSONObject?): String = str(p, "full_name", "name", "display_name").trim()
    fun firstName(p: JSONObject?): String = fullName(p).split(Regex("\\s+")).firstOrNull().orEmpty()
    fun ageOf(p: JSONObject?): Int? {
        val a = num(p?.opt("age"))
        if (a >= 18 && a < 100) return floor(a).toInt()
        val t = Tf.ms(str(p, "dob", "date_of_birth", "birthday"))
        if (t <= 0L) return null
        val y = (System.currentTimeMillis() - t) / (365.25 * 864e5)
        return if (y >= 18 && y < 100) floor(y).toInt() else null
    }
    fun cityOf(p: JSONObject?): String = str(p, "city", "location_city", "district", "state")
    fun idOf(p: JSONObject?): String = str(p, "user_id", "created_by_id", "owner_id", "id")
    fun genderOf(p: JSONObject?): String {
        val g = str(p, "gender", "sex").trim().lowercase()
        if (Regex("^(f|female|woman|girl)$").matches(g)) return "f"
        if (Regex("^(m|male|man|boy)$").matches(g)) return "m"
        return ""
    }
    private fun callState(p: JSONObject?): String = str(p, "call_status", "call_state", "availability", "status").lowercase()
    /** JavaScript truthiness of p[k] */
    fun truthy(p: JSONObject?, k: String): Boolean = when (val v = p?.opt(k)) {
        null, JSONObject.NULL -> false
        is Boolean -> v
        is Number -> v.toDouble().let { it != 0.0 && !it.isNaN() }
        is String -> v.isNotEmpty()
        else -> true
    }
    fun isBusy(p: JSONObject?): Boolean = truthy(p, "is_busy") || truthy(p, "busy") || truthy(p, "in_call") || truthy(p, "on_call") ||
        callState(p) in listOf("busy", "in_call", "on_call", "matching", "ringing", "calling")
    fun isOnline(p: JSONObject?): Boolean {
        if (p?.opt("online") == true || p?.opt("is_online") == true) return true
        val pr = str(p, "presence", "online_status").lowercase()
        if (pr == "online" || pr == "active") return true
        if (pr == "offline") return false
        if (callState(p) in listOf("online", "available")) return true
        val t = Tf.ms(str(p, "last_active", "last_seen", "last_seen_at", "last_active_at", "online_at"))
        return t > 0 && System.currentTimeMillis() - t < 5 * 60 * 1000
    }
    fun isFree(p: JSONObject?) = isOnline(p) && !isBusy(p)
    fun isVerified(p: JSONObject?): Boolean = truthy(p, "verified") || truthy(p, "is_verified") || p?.optString("creator_status") == "approved" ||
        p?.optString("host_status") == "approved" || p?.optString("account_type")?.lowercase() in listOf("creator", "host")
    fun weekMins(p: JSONObject?): Double = num(pick(p, "week_talk_minutes", "weekly_talk_minutes", "talk_minutes_week", "week_minutes") ?: 0)
    /** 2.5 → "2.5", 3.0 → "3" (like a JS number) */
    fun jsNum(v: Double): String = if (v == floor(v) && abs(v) < 1e15) v.toLong().toString() else v.toString()
    private fun abs(v: Double) = if (v < 0) -v else v
    fun jsRound(v: Double): Long = floor(v + 0.5).toLong()
    fun hoursText(m: Double): String { val h = m / 60; return if (h >= 10) "${jsRound(h)} Hours" else if (h >= 1) "${jsNum(jsRound(h * 10) / 10.0)} Hours" else "${jsRound(m)} Min" }
    fun uniq(list: List<JSONObject>): List<JSONObject> { val seen = HashSet<String>(); return list.filter { p -> val k = p.optString("id").ifEmpty { idOf(p) }; if (k.isEmpty() || !seen.add(k)) false else true } }

    // Become a Host / Creator status (an application counts as approved / rejected only when the admin reviewed THIS one)
    fun reviewedStatus(appliedAt: String, reviewedFor: String, status: String): String {
        if (appliedAt.isEmpty()) return "none"
        if (reviewedFor.isNotEmpty() && reviewedFor == appliedAt && (status == "approved" || status == "rejected")) return status
        return "pending"
    }
    fun hostStatus(p: JSONObject?) = reviewedStatus(str(p, "host_applied_at"), str(p, "host_reviewed_for"), str(p, "host_status"))
    fun creatorStatus(p: JSONObject?) = reviewedStatus(str(p, "creator_submitted_at"), str(p, "creator_reviewed_for"), str(p, "creator_status"))
    fun creatorOk(p: JSONObject?): Boolean {
        val at = str(p, "creator_submitted_at")
        if (at.isNotEmpty() && str(p, "creator_reviewed_for") == at) return p?.optString("creator_status") == "approved"
        return str(p, "account_type").lowercase() == "creator"
    }

    class Verify(val state: String, val left: Int, val waiting: Boolean, val step: String, val tf: List<String>, val sd: List<String>, val to: String)
    private class Step(val label: String, val ok: Boolean, val wait: Boolean, val to: String)

    /** The Verification bar (verifyOf in Home.jsx). */
    fun verifyOf(p: JSONObject?): Verify? {
        if (p == null) return null
        val queue = Tf.arr(p.optJSONArray("photo_queue"))
        val apps = Tf.strs(p.optJSONArray("apps"))
        val photoOk = when (val pp = p.opt("profile_picture")) { null, JSONObject.NULL, false -> false; is String -> pp.trim().isNotEmpty(); is Number -> pp.toDouble() != 0.0; else -> true }
        val digits = str(p, "phone").replace(Regex("\\D"), "")
        val detailsOk = str(p, "full_name").trim().isNotEmpty() && (ageOf(p) ?: 0) >= 18 && str(p, "state").trim().isNotEmpty() && str(p, "city").trim().isNotEmpty()
        val tf = listOf(
            Step("Mobile number", p.opt("phone_verified") == true || Regex("^(91)?[6-9]\\d{9}$").matches(digits), false, "/profile?open=verification"),
            Step("Profile details", detailsOk, false, "/profile/edit"),
            Step("Profile photo", photoOk, !photoOk && queue.any { it.optString("status") == "pending" }, "/profile/edit"),
        )
        val videoOk = creatorOk(p)
        val sd = ArrayList<Step>()
        sd.add(Step("Reconnect", apps.contains("secret"), false, "/secret-app"))
        if (genderOf(p) == "f") sd.add(Step("Live video", videoOk, !videoOk && creatorStatus(p) == "pending", "/profile?open=creator"))
        val state = if (tf.any { !it.ok }) "pending" else if (sd.any { !it.ok }) "tf" else "full"
        val open = (if (state == "pending") tf + sd else sd).filter { !it.ok }
        val nextStep = open.firstOrNull { !it.wait }
        fun mark(x: Step) = if (x.ok) "done" else if (x.wait) "wait" else "todo"
        return Verify(state, open.size, open.isNotEmpty() && nextStep == null, (nextStep ?: open.firstOrNull())?.label ?: "",
            tf.map { mark(it) }, sd.map { mark(it) }, nextStep?.to ?: "/profile?open=verification")
    }

    /** thumbOf / listPhoto of lib/avatar.js */
    fun thumbOf(p: JSONObject?, full: String?): String {
        if (p == null) return ""
        val want = full ?: str(p, "profile_picture", "photo")
        val pt = p.opt("photo_thumb")
        if (pt is String && pt.isNotEmpty() && (full == null || full.isEmpty() || p.optString("photo").isEmpty() || full == p.optString("photo"))) return pt
        if (want.isNotEmpty()) {
            val a = p.optJSONArray("photo_thumbs")
            if (a != null) for (i in 0 until a.length()) { val t = a.optJSONObject(i) ?: continue; if (t.optString("uri") == want) { val th = t.opt("thumb"); if (th is String) return th } }
        }
        return ""
    }
    fun listPhoto(p: JSONObject?, full: String): String = thumbOf(p, full).ifEmpty { full }

    /** (n * 31 + code) >>> 0 over the key, like the web */
    fun hash(key: String): Long { var n = 0L; for (ch in key) n = (n * 31 + ch.code) and 0xFFFFFFFFL; return n }

    @Suppress("unused") private fun keep(v: Double) = v.roundToLong()
}
