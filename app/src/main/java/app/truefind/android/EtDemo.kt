package app.truefind.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * Test mode only (emulator pictures on GitHub, never in the real app): made-up answers for the
 * native Expert Talk, My Dashboard and Secret Dating Earning, so every screen can be opened and
 * photographed without logging in. Off unless a test screen turns it on.
 */
object EtDemo {
    @Volatile var on = false
    var expert = false      // the made-up user is an expert (her panel)
    var creator = false     // My Dashboard as a creator

    private fun iso(minAgo: Long) = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(System.currentTimeMillis() - minAgo * 60000))
    private val today get() = Et.todayIST()
    private fun nowMinIst(): Int { val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = System.currentTimeMillis() + Et.IST }; return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE) }

    private val rules: JSONObject get() = JSONObject().put("price", JSONObject().put("25", 250).put("35", 350).put("45", 450)).put("durs", JSONArray(listOf(25, 35, 45)))
        .put("book_ahead", 30).put("move_max", 2).put("move_cutoff_ms", 3 * 3600_000L).put("open_before_ms", 120_000L).put("ring_ms", 45_000).put("report_gap_ms", 30_000)
        .put("warn_max", 4).put("share_pct", 50)
        .put("specs", JSONArray(listOf("Breakup", "Relationship", "Emotional support", "Stress & overthinking", "Loneliness", "Family", "Friendship", "Confidence", "Career", "Personal growth", "Counselling")))
        .put("langs", JSONArray(listOf("Hindi", "English", "Punjabi", "Haryanvi", "Bengali", "Marathi", "Tamil", "Telugu", "Gujarati", "Kannada", "Malayalam", "Urdu")))
        .put("concerns", JSONArray(listOf("Breakup", "Relationship", "Emotional support", "Stress & overthinking", "Loneliness", "Family", "Friendship", "Confidence", "Career", "Personal growth", "Counselling", "Something else")))

    private class E(val id: String, val name: String, val title: String, val specs: List<String>, val langs: List<String>, val rating: Double, val reviews: Int, val hours: Double, val freeToday: Boolean)
    private val EX = listOf(
        E("demo-g1", "Ananya Sharma", "Relationship Coach", listOf("Breakup", "Relationship", "Loneliness"), listOf("Hindi", "English"), 4.9, 38, 126.5, true),
        E("demo-g2", "Meera Kapoor", "Life Listener", listOf("Emotional support", "Stress & overthinking", "Family"), listOf("Hindi", "Punjabi"), 4.7, 21, 64.0, true),
        E("demo-g3", "Riya Sen", "Confidence Mentor", listOf("Confidence", "Career", "Personal growth"), listOf("English", "Bengali"), 4.8, 12, 31.5, false),
        E("demo-g4", "Kavya Nair", "Friendship & Family Guide", listOf("Friendship", "Family", "Counselling"), listOf("English", "Malayalam", "Hindi"), 0.0, 0, 2.0, true),
    )
    private fun expertCard(e: E, full: Boolean = false): JSONObject {
        val nextDate = if (e.freeToday) today else Et.addDays(today, 2)
        val o = JSONObject().put("id", e.id).put("name", e.name).put("title", e.title).put("specs", JSONArray(e.specs)).put("years", 6).put("langs", JSONArray(e.langs))
            .put("qual", "Helped friends through breakups for 6 years").put("qual_checked", e.id == "demo-g1")
            .put("about", "I listen without judging. Many people come to me after a breakup or when they feel alone in a big city. We talk, slowly, until things feel lighter.")
            .put("durs", JSONArray(listOf(25, 35, 45))).put("days", JSONArray(listOf(1, 2, 3, 4, 5, 6))).put("per_day", 6).put("photo", "")
            .put("rating", e.rating).put("reviews", e.reviews).put("sessions", e.reviews + 9).put("hours", e.hours).put("on_time", 98).put("first_ring", 95).put("missed", 1).put("verified", true)
            .put("next", JSONObject().put("date", nextDate).put("start_min", 960))
        if (full) o.put("review_list", JSONArray().apply {
            put(JSONObject().put("stars", 5).put("text", "She really listened. I felt light after a long time.").put("by", "Quiet Sparrow").put("at", iso(600)).put("topic", "Breakup"))
            put(JSONObject().put("stars", 4).put("text", "Kind and patient. Helped me sort my thoughts.").put("by", "Gentle Otter").put("at", iso(3000)).put("topic", "Stress & overthinking"))
        })
        return o
    }

    private fun booking(id: String, e: E, date: String, start: Int, status: String, extra: JSONObject.() -> Unit = {}): JSONObject = JSONObject().put("id", id)
        .put("expert", JSONObject().put("id", e.id).put("name", e.name).put("title", e.title).put("photo", "")).put("concerns", JSONArray(listOf(e.specs[0])))
        .put("dur", 25).put("coins", 250).put("date", date).put("start_min", start).put("start_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(Et.startMs(date, start))))
        .put("status", status).put("missed_rings", 0).put("moves", 0).put("report_count", 0).put("stars", 0).put("review", "").put("refund_reason", "").put("case_id", "")
        .apply(extra)

    private fun soonStart(): Pair<String, Int> { val n = nowMinIst() + 1; return today to n }

    fun sessions(): JSONArray = JSONArray().apply {
        val (d0, s0) = soonStart()
        put(booking("bk1", EX[0], d0, s0, "confirmed"))
        put(booking("bk2", EX[1], Et.addDays(today, 2), 960, "confirmed") { put("resched", JSONObject().put("date", Et.addDays(today, 3)).put("start_min", 990).put("reason", "Scheduling conflict")) })
        put(booking("bk3", EX[2], Et.addDays(today, 5), 690, "confirmed") { put("moves", 1); put("moved_from", JSONObject().put("date", Et.addDays(today, 4)).put("start_min", 660)) })
        put(booking("bk4", EX[0], Et.addDays(today, -3), 900, "completed") { put("stars", 5); put("review", "She really listened.") })
        put(booking("bk5", EX[1], Et.addDays(today, -6), 720, "completed"))
        put(booking("bk6", EX[3], Et.addDays(today, -8), 660, "expert_no_show") { put("refund_reason", "Expert didn't pick up") })
    }

    private fun slotsFor(date: String, dur: Int): JSONArray {
        val a = JSONArray()
        val wd = Et.wdOf(date)
        if (wd == 0) return a
        var t = 600
        val now = nowMinIst()
        while (t + dur <= 1080) {
            if (!(t < 840 && t + dur > 780) && !(date == today && t < now + 30)) a.put(JSONObject().put("t", t).put("taken", t == 690 || t == 900).put("full", false))
            t += 30
        }
        return a
    }

    fun function(name: String, p: JSONObject): Api.Result? {
        if (!on) return null
        val d: JSONObject = when (name) {
            "expertTalk" -> when (p.optString("action")) {
                "home" -> JSONObject().put("ok", true).put("rules", rules).put("experts", JSONArray(EX.map { expertCard(it) })).put("today", today).put("coins", 620).put("upcoming", 3)
                    .put("next", JSONObject().put("id", "bk1").put("expert_name", EX[0].name).put("date", soonStart().first).put("start_min", soonStart().second).put("dur", 25).put("status", "confirmed"))
                    .put("me", JSONObject().put("is_expert", expert).put("creator", true).put("name", "Neha Verma").put("alias", "Quiet Sparrow")).put("application", JSONObject.NULL)
                "expert" -> JSONObject().put("ok", true).put("expert", expertCard(EX.firstOrNull { it.id == p.optString("expert_id") } ?: EX[0], true))
                "slots" -> {
                    val dur = p.optInt("dur", 25)
                    val days = JSONArray()
                    for (i in 0..30) { val dt = Et.addDays(today, i); val w = Et.wdOf(dt) != 0; val sl = slotsFor(dt, dur); days.put(JSONObject().put("date", dt).put("works", w).put("open", (0 until sl.length()).any { !sl.getJSONObject(it).optBoolean("taken") }).put("full", w && i == 3)) }
                    val asked = p.optString("date")
                    val first = (0 until days.length()).map { days.getJSONObject(it) }.firstOrNull { it.optBoolean("open") }?.optString("date") ?: today
                    JSONObject().put("ok", true).put("today", today).put("last", Et.addDays(today, 30)).put("per_day", 6).put("work_days", JSONArray(listOf(1, 2, 3, 4, 5, 6))).put("days", days)
                        .put("slots", slotsFor(if (asked.isNotEmpty()) asked else first, dur)).put("first_date", if (asked.isNotEmpty()) JSONObject.NULL else first).put("accepting", true)
                }
                "sessions" -> JSONObject().put("ok", true).put("rules", rules).put("coins", 620).put("sessions", sessions())
                "book" -> JSONObject().put("ok", true).put("booking", booking("bk9", EX[0], p.optString("date"), p.optInt("start_min"), "confirmed").put("dur", p.optInt("dur", 25)).put("coins", Et.price(rules, p.optInt("dur", 25))))
                "state", "call" -> JSONObject().put("ok", true).put("role", if (expert) "expert" else "member").put("state", booking("bk1", EX[0], today, nowMinIst() - 3, "live") {
                    put("answered_at", iso(3)); put("ring_at", iso(4)); put("user_alias", "Quiet Sparrow"); put("share", 125)
                })
                "dash" -> dash()
                else -> JSONObject().put("ok", true)
            }
            "getDashboard" -> dashboard()
            "getSecretEarnings" -> JSONObject().put("creator", JSONObject().put("name", "Neha Verma")).put("viewed_by_admin", p.has("creator_id"))
                .put("total", JSONObject().put("week", 220).put("month", 865).put("all", 2310.5))
                .put("like_bonus", JSONObject().put("inr_month", 300).put("likes_month", 46).put("count_month", 6).put("inr_all", 900))
                .put("renewal_bonus", JSONObject().put("inr_month", 120).put("count_month", 4).put("messages_renewed", 9).put("inr_all", 420))
                .put("gifts", JSONObject().put("inr_month", 445).put("count_month", 31).put("inr_all", 990.5))
                .put("locked_messages", JSONObject().put("limit", 10).put("sent_today", 3).put("left_today", 7).put("sent_month", 58))
                .put("rates", JSONObject().put("first", JSONObject().put("1", 10).put("7", 25).put("30", 50)).put("renewal", 30).put("gift_pct", 100)).put("capped", false)
            else -> return null
        }
        return Api.Result(200, d, null)
    }

    private fun dash(): JSONObject {
        val ex = expertCard(EX[0], true).put("accepting", true).put("resume_at", "").put("admin_paused", false).put("paused_reason", "").put("warnings", 1)
            .put("pending_photo", false).put("pending_photo_url", "").put("leave_status", "none").put("leave_reason", "")
        fun bk(id: String, alias: String, date: String, start: Int, status: String, extra: JSONObject.() -> Unit = {}) = JSONObject().put("id", id).put("user_alias", alias)
            .put("concerns", JSONArray(listOf("Breakup", "Loneliness"))).put("dur", 25).put("share", 125).put("date", date).put("start_min", start)
            .put("start_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(Et.startMs(date, start))))
            .put("status", status).put("missed_rings", 0).put("report_count", 0).put("explain", "").put("explain_due", "").put("warns", 0).put("earn_state", "none").put("stars", 0).put("review", "").apply(extra)
        val b = JSONArray().apply {
            put(bk("x1", "Quiet Sparrow", today, nowMinIst() + 40, "confirmed"))
            put(bk("x2", "Gentle Otter", Et.addDays(today, 1), 690, "confirmed") { put("moved_from", JSONObject().put("date", today).put("start_min", 900)) })
            put(bk("x3", "Bright Finch", Et.addDays(today, 2), 960, "confirmed") { put("resched", JSONObject().put("date", Et.addDays(today, 3)).put("start_min", 960).put("reason", "Scheduling conflict")) })
            put(bk("x4", "Calm Panda", Et.addDays(today, -1), 840, "expert_no_show") { put("explain_due", iso(-600)) })
            put(bk("x5", "Kind Robin", Et.addDays(today, -2), 720, "completed") { put("stars", 5) })
            put(bk("x6", "Misty Deer", Et.addDays(today, -3), 630, "user_no_show"))
        }
        return JSONObject().put("ok", true).put("rules", rules).put("now", System.currentTimeMillis()).put("expert", ex)
            .put("earnings", JSONObject().put("available", 3250).put("pending", 375)).put("today_count", 2).put("bookings", b).put("cases", JSONArray())
    }

    private fun dashboard(): JSONObject {
        val week = JSONArray(); val labels = listOf("Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed")
        val wv = if (creator) listOf(120, 340, 0, 260, 410, 180, 530) else listOf(30, 0, 60, 10, 0, 40, 20)
        for (i in 0 until 7) week.put(JSONObject().put("l", labels[i]).put("v", wv[i]))
        val month = JSONArray(); for (i in 0 until 30) month.put(JSONObject().put("l", "${(i % 30) + 1} Nov").put("v", if (creator) (i * 37) % 420 else (i * 7) % 60))
        val all = JSONArray(); for ((i, l) in listOf("Jun", "Jul", "Aug", "Sep", "Oct", "Nov").withIndex()) all.put(JSONObject().put("l", l).put("v", if (creator) listOf(0, 0, 900, 2400, 3100, 1840)[i] else listOf(0, 0, 80, 140, 210, 160)[i]))
        return JSONObject().put("role", if (creator) "creator" else "user")
            .put("profile", JSONObject().put("name", if (creator) "Neha Verma" else "Aman Verma").put("photo", "").put("verified", creator).put("label", if (creator) "Creator" else "Member").put("joined", "2026-11-02T10:15:00"))
            .put("stats", JSONObject().put("voice_calls", 42).put("video_calls", 7).put("talk_seconds", 3 * 3600 + 25 * 60).put("gifts", 18))
            .put("money", JSONObject().put("unit", if (creator) "inr" else "coins").put("total", if (creator) 8240.5 else 590).put("week", if (creator) 1840 else 160).put("month", if (creator) 3610 else 420).put("prev_week", 1500).put("change_pct", 23))
            .put("series", JSONObject().put("week", week).put("month", month).put("all", all))
            .put("secret", if (creator) JSONObject().put("active", false).put("until", "") else JSONObject().put("active", true).put("until", iso(-3 * 24 * 60)))
            .put("capped", false).put("partial", false)
    }
}
