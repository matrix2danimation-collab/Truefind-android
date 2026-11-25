package app.truefind.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Made-up answers for the Control Center, used ONLY for the test pictures on GitHub
 * (the app opened with tf_admin=…). Never used with a real account.
 */
class AdDemo(private val ctx: Context) : AdSrc {
    private val main = Handler(Looper.getMainLooper())
    private fun later(r: Api.Result, done: (Api.Result) -> Unit) { main.postDelayed({ done(r) }, 250) }
    private fun ok(o: JSONObject) = Api.Result(200, o, null)
    private fun iso(minAgo: Double): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(System.currentTimeMillis() - (minAgo * 60_000).toLong()))
    private fun face(g: Boolean, i: Int) = "asset:home/faces/${if (g) "g" else "b"}${(i % 20).toString().padStart(2, '0')}.jpg"
    private fun arr(vararg o: Any) = JSONArray().also { a -> o.forEach { a.put(it) } }

    override val origin: String get() = "https://true-find.base44.app"
    override val appId: String get() = "68f1demo0000000000000000"

    // ── members (UserProfile) ──
    private val profiles: List<JSONObject> by lazy {
        val girls = listOf("Priya Sharma", "Ananya Verma", "Kavya Singh", "Isha Gupta", "Riya Kapoor", "Neha Yadav", "Pooja Rani", "Simran Kaur")
        val boys = listOf("Rahul Kumar", "Aman Sharma", "Vikas Yadav", "Rohit Singh", "Arjun Mehta", "Karan Malhotra")
        val cities = listOf("Rohtak", "Delhi", "Jaipur", "Lucknow", "Pune", "Indore", "Chandigarh", "Hisar")
        val out = ArrayList<JSONObject>()
        girls.forEachIndexed { i, n ->
            val p = JSONObject().put("id", "p-g$i").put("created_by_id", "u-g$i").put("full_name", n).put("gender", "female").put("age", 21 + i).put("city", cities[i % cities.size])
                .put("state", "Haryana").put("phone", "98${(12345678 + i * 1111).toString().take(8)}").put("email", "${n.split(" ")[0].lowercase()}@gmail.com")
                .put("created_date", iso(60.0 * (i + 2))).put("profile_picture", face(true, i + 1)).put("images", arr(face(true, i + 1)))
            if (i < 3) {
                p.put("creator_submitted_at", iso(30.0 * (i + 1))).put("creator_status", "pending").put("creator_video_uri", if (i == 0) "asset:demo-video" else "")
                    .put("creator_selfie_uri", face(true, i + 1)).put("creator_pose", listOf("Touch your left ear", "Show a thumbs up", "Smile with both hands up")[i]).put("creator_code", listOf("4 7 2 9", "", "1 8 3 6")[i])
                    .put("creator_upi_name", if (i == 1) "Ananya V" else n).put("creator_upi_id", if (i == 2) "" else "${n.split(" ")[0].lowercase()}@okaxis")
            }
            if (i == 3) iso(2000.0).let { at -> p.put("creator_submitted_at", at).put("creator_status", "approved").put("creator_reviewed_for", at) }
            if (i == 3) p.put("creator_reviewed_at", iso(1900.0))
                .put("creator_reviewed_by", "owner@truefindapp.com").put("creator_upi_name", n).put("creator_upi_id", "isha@ybl").put("creator_approved_upi_id", "isha@ybl").put("creator_selfie_uri", face(true, 4))
            if (i == 4) {
                iso(5000.0).let { at -> p.put("creator_submitted_at", at).put("creator_status", "approved").put("creator_reviewed_for", at) }
                p.put("host_applied_at", iso(45.0)).put("host_status", "pending").put("host_types", arr("audio_room", "video_room")).put("host_video_uri", "asset:demo-video")
                    .put("host_line", "Hi, I'm Riya and I want to host on True Find").put("host_call_seconds", 21600).put("host_about", "I love music and long talks. Evenings are best for me.")
            }
            if (i == 5) p.put("photo_queue", arr(JSONObject().put("uri", face(true, 9)).put("status", "pending").put("at", iso(12.0)).put("main", true)))
            if (i == 6) p.put("banned", true)
            out.add(p)
        }
        boys.forEachIndexed { i, n ->
            val p = JSONObject().put("id", "p-b$i").put("created_by_id", "u-b$i").put("full_name", n).put("gender", "male").put("age", 23 + i).put("city", cities[(i + 3) % cities.size])
                .put("phone", "97${(22345678 + i * 2111).toString().take(8)}").put("created_date", iso(40.0 * (i + 1))).put("profile_picture", face(false, i + 1))
            if (i == 0) p.put("host_applied_at", iso(90.0)).put("host_status", "pending").put("host_types", arr("audio_room")).put("host_upi_name", "Rahul K").put("host_upi_id", "rahul@paytm")
                .put("host_line", "Main True Find par host banna chahta hoon").put("host_call_seconds", 9000)
            if (i == 1) p.put("photo_queue", arr(JSONObject().put("uri", face(false, 8)).put("status", "pending").put("at", iso(5.0))))
            out.add(p)
        }
        out
    }

    override fun me(done: (Api.Result) -> Unit) = later(ok(JSONObject().put("id", "admin-1").put("email", "owner@truefindapp.com").put("role", "admin")), done)
    override fun page(entity: String, query: JSONObject, sort: String?, limit: Int, done: (Api.Result) -> Unit) {
        val items = when (entity) {
            "UserProfile" -> if (query.length() == 0) JSONArray(profiles) else JSONArray()
            "Call" -> JSONArray().apply { put(JSONObject().put("id", "c1-${query.optString("caller_id")}${query.optString("callee_id")}").put("duration_seconds", if (query.optString("type") == "video") 7200 else 4200)) }
            else -> JSONArray()
        }
        later(ok(JSONObject().put("items", items).put("has_more", false)), done)
    }
    override fun get(entity: String, id: String, done: (Api.Result) -> Unit) = later(ok(profiles.firstOrNull { it.optString("id") == id } ?: JSONObject()), done)
    override fun update(entity: String, id: String, patch: JSONObject, done: (Api.Result) -> Unit) = later(ok(JSONObject().put("id", id)), done)
    override fun image(raw: String, w: Int, h: Int, done: (Bitmap?) -> Unit) {
        if (!raw.startsWith("asset:") || raw == "asset:demo-video") { main.post { done(null) }; return }
        Api.async({ AdPics.asset(ctx, raw.removePrefix("asset:"), w, h) }, done)
    }
    override fun full(raw: String, maxSide: Int, done: (Bitmap?) -> Unit) {
        Api.async({ try { ctx.assets.open(raw.removePrefix("asset:")).use { BitmapFactory.decodeStream(it) } } catch (e: Throwable) { null } }, done)
    }
    override fun signed(raw: String, done: (String) -> Unit) { main.post { done(if (raw.isBlank()) "" else raw) } }

    override fun fn(name: String, body: JSONObject, done: (Api.Result) -> Unit) {
        val a = body.optString("action")
        if (name == "support" && body.has("ticket_id")) lastTicket = body.optString("ticket_id")
        val r: JSONObject = when (name) {
            "adminOps" -> ops(a, body)
            "adminUsers" -> users(a, body)
            "adminHealth" -> health(a, body)
            "adminPayouts" -> payouts(a)
            "support" -> support(a)
            "expertTalk" -> experts()
            "whatsappCheck" -> whatsapp()
            else -> JSONObject().put("ok", true)
        }
        later(ok(r), done)
    }

    private fun ops(a: String, body: JSONObject): JSONObject = when (a) {
        "overview" -> JSONObject()
            .put("users", JSONObject().put("total", 18452).put("new_today", 236).put("online", 1284).put("in_call", 312))
            .put("live", JSONObject().put("calls", 148).put("ringing", 16).put("stuck_calls", 1).put("rooms", 9).put("dance", 3))
            .put("money_today", JSONObject().put("topup_coins", 48250).put("topups", 214).put("coins_spent", 39870.5).put("creator_earned", 11960.25))
            .put("todo", JSONObject().put("creators", 3).put("hosts", 2).put("photos", 2).put("payouts", 2).put("reports", 4).put("support", 6).put("support_unread", 3).put("billing", 1).put("errors", 2))
            .put("config", JSONObject().put("maintenance_mode", false).put("calls_enabled", true).put("games_enabled", false).put("live_enabled", true).put("signups_enabled", true))
            .put("jobs", JSONObject().put("last_started_at", iso(1.5)).put("runs", 9311))
        "growth" -> {
            val days = JSONArray()
            for (i in 0 until 14) { val s = 150 + (i * 37 % 90); days.put(JSONObject().put("day", "2026-11-${(5 + i).toString().padStart(2, '0')}").put("signups", s).put("talked", s * (40 + i % 4 * 6) / 100).put("recharged", s / 6)) }
            JSONObject().put("days", body.optInt("days", 7)).put("signups", 1640).put("capped", false)
                .put("funnel", arr(
                    JSONObject().put("key", "signup").put("label", "Finished sign-up").put("count", 1640).put("pct", 100),
                    JSONObject().put("key", "coins").put("label", "Claimed free coins").put("count", 1312).put("pct", 80),
                    JSONObject().put("key", "call").put("label", "First call").put("count", 702).put("pct", 43),
                    JSONObject().put("key", "recharge").put("label", "First recharge").put("count", 238).put("pct", 15)))
                .put("came_back", JSONObject().put("count", 688).put("pct", 42)).put("call_reports", 17)
                .put("by_day", days)
                .put("ratings", JSONObject().put("dist", arr(41, 33, 120, 410, 905)).put("count", 1509).put("avg", 4.39).put("with_report", 6)
                    .put("low", arr(JSONObject().put("user_id", "u-g6").put("name", "Pooja Rani").put("photo", face(true, 7)).put("count", 12).put("low", 7).put("avg", 2.4).put("creator", true)))
                    .put("top", arr(JSONObject().put("user_id", "u-g0").put("name", "Priya Sharma").put("photo", face(true, 1)).put("count", 88).put("avg", 4.9).put("creator", true),
                        JSONObject().put("user_id", "u-g3").put("name", "Isha Gupta").put("photo", face(true, 4)).put("count", 41).put("low", 1).put("avg", 4.8).put("creator", true))))
                .put("referrals", JSONObject().put("joined", 312).put("waiting", 120).put("rewarded", 171).put("coins_paid", 17100).put("blocked", 21))
        }
        "live" -> JSONObject()
            .put("calls", arr(
                JSONObject().put("id", "call1").put("type", "video").put("status", "active").put("started_at", iso(6.2)).put("rate", 30).put("caller", JSONObject().put("id", "u-b0").put("name", "Rahul Kumar")).put("callee", JSONObject().put("id", "u-g0").put("name", "Priya Sharma")),
                JSONObject().put("id", "call2").put("type", "voice").put("status", "active").put("started_at", iso(14.0)).put("rate", 10).put("stuck", true).put("quiet_seconds", 240).put("caller", JSONObject().put("id", "u-b1").put("name", "Aman Sharma")).put("callee", JSONObject().put("id", "u-g3").put("name", "Isha Gupta")),
                JSONObject().put("id", "call3").put("type", "voice").put("status", "ringing").put("created_date", iso(0.3)).put("rate", 10).put("caller", JSONObject().put("id", "u-b2").put("name", "Vikas Yadav")).put("callee", JSONObject().put("id", "u-g1").put("name", "Ananya Verma"))))
            .put("rooms", arr(JSONObject().put("id", "r1").put("kind", "live").put("name", "Late night songs 🎶").put("host_name", "Kavya Singh").put("host_photo", face(true, 3)).put("since", iso(38.0))))
            .put("dance", arr(JSONObject().put("id", "d1").put("kind", "dance").put("name", "Bollywood battle").put("host_name", "Riya Kapoor").put("host_photo", face(true, 5)).put("since", iso(12.0)).put("viewers", 84)))
        "billing_issues" -> JSONObject().put("calls", arr(JSONObject().put("id", "bill1").put("type", "video").put("ended_at", iso(95.0)).put("duration_seconds", 412).put("charged_coins", 150).put("creator_earned", 25).put("uncollected_coins", 60)
            .put("caller", JSONObject().put("id", "u-b3").put("name", "Rohit Singh")).put("callee", JSONObject().put("id", "u-g2").put("name", "Kavya Singh"))))
        "errors" -> JSONObject().put("errors", arr(
            JSONObject().put("id", "e1").put("message", "Cannot read properties of undefined (reading 'photo')").put("source", "react").put("page", "/secret-app").put("count", 41).put("people", 12).put("first_seen", iso(3000.0)).put("last_seen", iso(25.0)).put("status", "open")
                .put("stack", "TypeError: Cannot read properties of undefined (reading 'photo')\n    at ProfileCard (assets/index-a1b2.js:12:3401)\n    at renderWithHooks (assets/index-a1b2.js:4:1022)").put("app_version", "index-a1b2c3").put("user_agent", "Mozilla/5.0 (Linux; Android 14) TrueFindApp/1"),
            JSONObject().put("id", "e2").put("message", "Android: java.lang.IllegalStateException: Not attached").put("source", "manual").put("page", "/wallet").put("count", 3).put("people", 1).put("first_seen", iso(500.0)).put("last_seen", iso(240.0)).put("status", "open")))
        "config_get" -> JSONObject().put("config", JSONObject().put("maintenance_mode", false).put("maintenance_message", "").put("announcement", "Diwali offer: 2x coins on every recharge today!").put("announcement_id", "a1")
            .put("signups_enabled", true).put("calls_enabled", true).put("games_enabled", false).put("live_enabled", true).put("voice_rate", 10).put("video_rate", 30).put("voice_creator_rate", 3).put("video_creator_rate", 8)
            .put("support_email", "matrixsupportworld@gmail.com").put("support_whatsapp", "").put("daily_coins", 10).put("streak_bonus", 20).put("referral_reward", 50).put("version", 14).put("updated_at", iso(1440.0)).put("updated_by", "owner@truefindapp.com"))
        "jobs" -> JSONObject().put("status", JSONObject().put("last_started_at", iso(1.5)).put("last_trigger", "app").put("last_duration_ms", 2380).put("runs", 9311).put("last_cleanup_at", iso(300.0))
            .put("last_result", JSONObject().put("calls_ended", 2).put("rings_closed", 5).put("settlements_flagged", 0).put("people_freed", 3)))
        "audit_log" -> JSONObject().put("actions", arr(
            JSONObject().put("id", "x1").put("action", "payout_paid").put("amount", 1340).put("details", "to isha@ybl ref 431512345678").put("created_date", iso(50.0)).put("admin_email", "owner@truefindapp.com"),
            JSONObject().put("id", "x2").put("action", "config_saved").put("details", "games_enabled: true → false").put("created_date", iso(1440.0)).put("admin_email", "owner@truefindapp.com"),
            JSONObject().put("id", "x3").put("action", "wallet_adjust").put("amount", 50).put("details", "Refund for a call that dropped").put("created_date", iso(2900.0)).put("admin_email", "owner@truefindapp.com")))
        "offers" -> JSONObject().put("phones", 9214).put("offers", arr(JSONObject().put("id", "o1").put("title", "50% extra coins today").put("body", "Buy any pack before midnight and get 50% extra coins.").put("status", "done").put("sent", 9102).put("total", 9188).put("created_date", iso(2880.0))))
        else -> JSONObject().put("ok", true)
    }

    private fun card(i: Int, girl: Boolean): JSONObject {
        val p = profiles.first { it.optString("id") == "p-${if (girl) "g" else "b"}$i" }
        return JSONObject().put("user_id", p.optString("created_by_id")).put("profile_id", p.optString("id")).put("name", p.optString("full_name")).put("gender", p.optString("gender"))
            .put("age", p.optInt("age")).put("city", p.optString("city")).put("photo", p.optString("profile_picture")).put("phone", p.optString("phone")).put("joined", p.optString("created_date"))
            .put("banned", p.optBoolean("banned")).put("creator", girl && i in 3..4)
    }

    private fun users(a: String, body: JSONObject): JSONObject = when (a) {
        "search" -> JSONObject().put("results", arr(card(0, true)))
        "shared_devices" -> JSONObject().put("devices", arr(JSONObject().put("device_id", "dev1").put("last_seen", iso(30.0)).put("accounts", arr(card(1, false), card(5, true), card(7, true)))))
        "user_detail" -> {
            val c = card(0, true).put("state", "Haryana").put("warnings", 1).put("online", true).put("last_active", iso(1.0)).put("creator", true).put("host", false)
                .put("rating", JSONObject().put("avg", 4.9).put("count", 88).put("low", 2).put("score", 93))
            JSONObject().put("user", JSONObject().put("id", "u-g0").put("email", "priya@gmail.com").put("role", "user")).put("card", c)
                .put("wallets", arr(JSONObject().put("app", "truefind").put("coins", 120).put("earned_coins", 2310.5), JSONObject().put("app", "secret").put("coins", 15)))
                .put("transactions", arr(
                    JSONObject().put("id", "t1").put("type", "call_earn").put("amount", 18).put("description", "Video call with Rahul Kumar · 6 min").put("created_date", iso(20.0)),
                    JSONObject().put("id", "t2").put("type", "topup").put("amount", 200).put("description", "Recharge ₹199").put("created_date", iso(300.0)),
                    JSONObject().put("id", "t3").put("type", "chat_spend").put("app", "secret").put("amount", -1).put("description", "Chat request").put("created_date", iso(900.0))))
                .put("calls", arr(
                    JSONObject().put("id", "c1").put("type", "video").put("status", "ended").put("role", "callee").put("created_date", iso(26.0)).put("duration_seconds", 372).put("creator_earned", 18).put("other", JSONObject().put("name", "Rahul Kumar")),
                    JSONObject().put("id", "c2").put("type", "voice").put("status", "missed").put("role", "callee").put("created_date", iso(200.0)).put("ended_by", "caller").put("other", JSONObject().put("name", "Aman Sharma"))))
                .put("reports_against", arr(JSONObject().put("id", "r9").put("reason", "Rude").put("details", "Hung up rudely").put("status", "dismissed").put("created_date", iso(4000.0))))
                .put("counts", JSONObject().put("reports_against", 1).put("reports_made", 0).put("blocked_by", 2))
                .put("devices", arr(JSONObject().put("device_id", "d1").put("user_agent", "Android 14 · True Find app").put("last_seen", iso(1.0)).put("ip", "49.36.xx.xx")))
                .put("same_device_accounts", arr(JSONObject().put("user_id", "u-b5").put("name", "Karan Malhotra").put("gender", "male").put("city", "Delhi")))
                .put("admin_actions", arr(JSONObject().put("id", "aa1").put("action", "creator_approved").put("details", "Live video checked").put("created_date", iso(2000.0)).put("admin_email", "owner@truefindapp.com")))
                .put("payouts", arr(JSONObject().put("id", "py1").put("net", 1340).put("status", "paid").put("decided_at", iso(50.0))))
                .put("health", memberHealth())
        }
        "reports" -> JSONObject().put("reports", arr(
            JSONObject().put("id", "rep1").put("kind", "profile").put("status", "open").put("reason", "Asking for money").put("details", "She asked me to send money on GPay before talking.").put("match_id", "m1").put("call_id", "call9")
                .put("created_date", iso(35.0)).put("reporter", card(2, false)).put("reported", card(6, true).put("creator", true).put("warnings", 1)).put("reports_against", 3),
            JSONObject().put("id", "rep2").put("kind", "profile").put("status", "open").put("reason", "Fake photos").put("details", "").put("evidence_uris", arr(face(true, 12), face(true, 13))).put("held_photos", arr(face(true, 14), face(true, 15)))
                .put("created_date", iso(140.0)).put("reporter", card(3, false)).put("reported", card(7, true)).put("reports_against", 1)))
            .put("room_reports", arr(JSONObject().put("id", "rr1").put("kind", "room").put("status", "open").put("reason", "Abusive language").put("details", "Host was abusing listeners.").put("created_date", iso(400.0)).put("reporter", card(4, false)).put("reported", card(2, true)).put("reports_against", 0)))
        "report_chat" -> JSONObject().put("reporter_id", "u-b2").put("reported_id", "u-g6").put("messages", arr(
            JSONObject().put("id", "mm1").put("from", "u-b2").put("from_name", "Vikas Yadav").put("text", "Hi! How are you?").put("at", iso(60.0)),
            JSONObject().put("id", "mm2").put("from", "u-g6").put("from_name", "Pooja Rani").put("text", "Send ₹500 on GPay first, then we talk").put("at", iso(58.0)),
            JSONObject().put("id", "mm3").put("from", "u-b2").put("from_name", "Vikas Yadav").put("text", "That's not okay. Reporting.").put("at", iso(57.0))))
        else -> JSONObject().put("ok", true)
    }

    // ── App Health (25 Nov): made-up numbers for the test pictures only ──
    private fun dayAgo(n: Int): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }.format(Date(System.currentTimeMillis() - n * 86_400_000L))
    private fun issue(id: String, area: String, kind: String, code: String, detail: String, user: String, min: Double, n: Int = 1, page: String = "", device: String = "Xiaomi 23021RAAEG",
                      android: String = "13 (33)", net: String = "mobile", op: String = "Jio", status: String = "open", ref: String = "", source: String = "app") =
        JSONObject().put("id", id).put("area", area).put("kind", kind).put("code", code).put("detail", detail).put("user_id", user).put("count", n).put("page", page)
            .put("device", device).put("android", android).put("app_version", "0.1.252").put("network", net).put("operator", op).put("status", status).put("ref_id", ref)
            .put("source", source).put("first_at", iso(min + 20)).put("last_at", iso(min))
    private fun people(): JSONObject = JSONObject().apply {
        for (i in 0..7) put("u-g$i", card(i, true))
        for (i in 0..5) put("u-b$i", card(i, false))
    }
    private val demoIssues: List<JSONObject> by lazy { listOf(
        issue("i1", "payment", "payment_stuck", "google_play", "259 coins · ₹259 · truefind", "u-b0", 6.0, ref = "pt-81", source = "server", device = "", android = "", net = "", op = ""),
        issue("i2", "call", "call_dropped", "PEER_DROPPED", "Video call · The other phone's connection dropped", "u-g1", 9.0, ref = "call-71"),
        issue("i3", "crash", "crash", "IllegalStateException", "java.lang.IllegalStateException: The specified child already has a parent · at ExploreScreen.bubble", "u-b2", 14.0, 2, "Explore", "Samsung SM-A155F", "14 (34)", "mobile", "Airtel"),
        issue("i4", "call", "call_failed", "PASS_NETWORK", "Voice call · Could not connect the call service. Please end the call and try again.", "u-b3", 21.0, ref = "call-64", device = "Realme RMX3830", op = "Vi"),
        issue("i5", "message", "msg_failed", "NO_CONNECTION", "Message did not send (no connection)", "u-g4", 26.0, 3, "Chat room"),
        issue("i6", "hang", "hang", "FROZE", "The app froze for 3.4 s", "u-b1", 33.0, 1, "Secret Dating", "Vivo V2250", "13 (33)", "wifi", "Jio"),
        issue("i7", "call", "call_no_audio", "NO_SOUND_15S", "Voice call · No sound from the other phone for 15 s", "u-g2", 41.0, ref = "call-58", op = "Airtel"),
        issue("i8", "page", "page_slow", "OPEN_SLOW", "Took 3.6 s to appear", "u-b4", 52.0, 1, "Explore", "Redmi 220333QAI", "12 (31)"),
        issue("i9", "server", "server_timeout", "getOnlineNow", "No answer in 30 s", "u-g5", 66.0, 4, "Home"),
        issue("i10", "call", "call_dropped", "NET_LOST_14", "Voice call · This phone lost the call's connection", "u-b5", 80.0, ref = "call-41", device = "OnePlus CPH2491", net = "mobile", op = "Jio"),
        issue("i11", "server", "server_error", "getWallet · 500", "Something went wrong. Please try again. · 412 ms", "u-g6", 95.0, source = "server", device = "", android = "", net = "", op = ""),
        issue("i12", "crash", "anr", "ANR", "The app froze and Android closed it", "u-g3", 130.0, 1, "Live Rooms", "Oppo CPH2387", "12 (31)", status = "resolved"),
    ) }
    private val demoPages: JSONArray by lazy {
        val rows = listOf(
            listOf("Home", 18230, 3980, 412_000, 41, 2, 380, "Redmi 220333QAI"), listOf("Explore", 9120, 3010, 388_500, 96, 5, 910, "Xiaomi 23021RAAEG"),
            listOf("Call Back", 6420, 2240, 96_300, 12, 0, 340, ""), listOf("Wallet", 4310, 1870, 61_200, 9, 0, 520, ""), listOf("Profile", 3880, 1950, 70_500, 4, 0, 300, ""),
            listOf("Secret Dating", 3120, 1340, 240_800, 22, 3, 760, "Vivo V2250"), listOf("Chat room", 2950, 880, 301_100, 6, 1, 280, ""),
            listOf("Live Rooms", 1210, 640, 158_400, 15, 1, 830, "Oppo CPH2387"), listOf("Games", 980, 520, 88_900, 3, 0, 450, ""), listOf("Dashboard", 410, 220, 9_800, 0, 0, 260, ""),
            listOf("Edit profile", 380, 300, 21_400, 1, 0, 330, ""), listOf("Expert Talk", 160, 120, 12_100, 0, 0, 410, ""),
        )
        JSONArray().also { a -> for (r in rows) a.put(JSONObject().put("page", r[0]).put("opens", r[1]).put("members", r[2]).put("seconds", r[3]).put("slow", r[4]).put("hangs", r[5]).put("avg_open_ms", r[6])
            .put("max_open_ms", (r[6] as Int) * 5).put("bad_devices", if ((r[7] as String).isEmpty()) JSONArray() else arr(JSONObject().put("device", r[7]).put("slow", r[4]).put("hangs", r[5])))) }
    }
    private fun health(a: String, body: JSONObject): JSONObject {
        val range = body.optString("range").ifEmpty { "today" }
        val week = range == "week" || range == "30d"
        val f = if (week) 6.4 else 1.0
        fun n(v: Int) = Math.round(v * f).toInt()
        val rg = JSONObject().put("key", range).put("label", if (week) "Last 7 days" else "Today").put("when", if (week) "in the last 7 days" else "today")
            .put("from", dayAgo(if (week) 6 else 0)).put("to", dayAgo(0))
        return when (a) {
            "health" -> JSONObject().put("range", rg)
                .put("users", JSONObject().put("total", 18452).put("new", n(236)).put("active", n(4210)).put("with_usage", n(3890)).put("opens", n(12480)).put("seconds", n(3890) * 1260))
                .put("money", JSONObject().put("revenue_inr", n(48210)).put("paid", n(412)).put("buyers", n(356)).put("pending", 6).put("stuck", 2).put("failed", n(9)).put("refunded", 3).put("refunded_inr", 597))
                .put("calls", JSONObject().put("total", n(2840)).put("voice", n(2210)).put("video", n(630)).put("connected", n(2164)).put("minutes", n(21840)).put("not_answered", n(402)).put("declined", n(188)).put("not_started", n(6)).put("billing_problems", 1))
                .put("messages", JSONObject().put("sent", n(18230)).put("senders", n(2104)))
                .put("problems", JSONObject().put("call_dropped", n(12)).put("call_failed", n(7)).put("call_no_audio", n(4)).put("call_no_video", n(2)).put("msg_failed", n(9)).put("msg_slow", n(14))
                    .put("payment_stuck", 2).put("payment_not_added", 1).put("crash", n(3)).put("anr", 1).put("hang", n(11)).put("page_slow", n(6)).put("server_error", n(5)).put("server_timeout", n(8)).put("no_internet", n(41)))
                .put("pages", JSONObject().put("slow_opens", n(87)).put("hangs", n(11)).put("opens", n(51230)))
                .put("top", arr(
                    JSONObject().put("kind", "payment_stuck").put("area", "payment").put("n", 2).put("members", 2).put("line", "2 payments are stuck (money may be taken, coins / plans not given yet) ${rg.optString("when")}").put("hint", "2 members · Google Play"),
                    JSONObject().put("kind", "call_dropped").put("area", "call").put("n", n(12)).put("members", n(11)).put("line", "${n(12)} calls dropped in the middle ${rg.optString("when")}").put("hint", "Mostly on Jio (${n(9)}) · ${n(11)} members · Xiaomi 23021RAAEG (${n(4)}) · app 0.1.252"),
                    JSONObject().put("kind", "crash").put("area", "crash").put("n", n(3)).put("members", n(3)).put("line", "${n(3)} app crashes ${rg.optString("when")}").put("hint", "Mostly on Airtel (${n(2)}) · ${n(3)} members · Samsung SM-A155F (${n(2)}) · page Explore · app 0.1.252"),
                    JSONObject().put("kind", "call_failed").put("area", "call").put("n", n(7)).put("members", n(6)).put("line", "${n(7)} calls could not connect ${rg.optString("when")}").put("hint", "Mostly on Vi (${n(4)}) · ${n(6)} members · Realme RMX3830 (${n(3)})"),
                    JSONObject().put("kind", "msg_failed").put("area", "message").put("n", n(9)).put("members", n(5)).put("line", "${n(9)} messages did not send ${rg.optString("when")}").put("hint", "Mostly on Jio (${n(6)}) · ${n(5)} members · page Chat room"),
                    JSONObject().put("kind", "hang").put("area", "hang").put("n", n(11)).put("members", n(8)).put("line", "${n(11)} times the app froze for a few seconds ${rg.optString("when")}").put("hint", "Mostly on Wi-Fi (${n(5)}) · ${n(8)} members · Vivo V2250 (${n(4)}) · page Secret Dating"),
                ))
                .put("slow_pages", arr(JSONObject().put("page", "Explore").put("slow", n(96)).put("hangs", n(5)), JSONObject().put("page", "Home").put("slow", n(41)).put("hangs", n(2)), JSONObject().put("page", "Secret Dating").put("slow", n(22)).put("hangs", n(3))))
                .put("days", if (!week) JSONArray() else JSONArray().also { d -> for (i in 6 downTo 0) d.put(JSONObject().put("day", dayAgo(i)).put("active", 3700 + i * 83).put("new", 210 + i * 7).put("revenue_inr", 6100 + i * 420)
                    .put("calls", 2700 + i * 31).put("connected", 2080 + i * 25).put("call_problems", 20 + (i * 3) % 9).put("crashes", i % 3).put("messages", 17500 + i * 130)) })
                .put("alerts", JSONObject().put("enabled", true).put("push_ready", true).put("last", arr(JSONObject().put("title", "⚠️ 6 call problems in 10 min").put("body", "Mostly on Jio (5)").put("created_date", iso(95.0)))))
            "pages" -> JSONObject().put("range", rg).put("pages", demoPages).put("members", n(3980))
            "page_detail" -> JSONObject().put("range", rg).put("page", body.optString("page")).put("people", people())
                .put("devices", arr(
                    JSONObject().put("device", "Xiaomi 23021RAAEG").put("opens", 2410).put("members", 790).put("slow", 51).put("hangs", 3).put("avg_open_ms", 1340),
                    JSONObject().put("device", "Samsung SM-A155F").put("opens", 1980).put("members", 640).put("slow", 12).put("hangs", 1).put("avg_open_ms", 720),
                    JSONObject().put("device", "Vivo V2250").put("opens", 1320).put("members", 470).put("slow", 21).put("hangs", 1).put("avg_open_ms", 980),
                    JSONObject().put("device", "OnePlus CPH2491").put("opens", 860).put("members", 300).put("slow", 0).put("hangs", 0).put("avg_open_ms", 410)))
                .put("members", arr(
                    JSONObject().put("user_id", "u-b0").put("opens", 96).put("seconds", 7420).put("slow", 4).put("hangs", 0),
                    JSONObject().put("user_id", "u-g1").put("opens", 81).put("seconds", 6110).put("slow", 0).put("hangs", 1),
                    JSONObject().put("user_id", "u-b2").put("opens", 77).put("seconds", 5300).put("slow", 0).put("hangs", 0),
                    JSONObject().put("user_id", "u-g3").put("opens", 64).put("seconds", 4980).put("slow", 2).put("hangs", 0)))
                .put("days", JSONArray().also { d -> for (i in 6 downTo 0) d.put(JSONObject().put("day", dayAgo(i)).put("opens", 1250 + i * 17).put("members", 420 + i * 5).put("slow", 12 + i % 4).put("hangs", i % 2)) })
                .put("issues", arr(demoIssues[2], demoIssues[7]))
            "issues" -> {
                val areas = body.optJSONArray("areas")?.let { x -> (0 until x.length()).map { x.optString(it) } }.orEmpty()
                val st = body.optString("status")
                val list = demoIssues.filter { (areas.isEmpty() || it.optString("area") in areas) && (st == "all" || st.isEmpty() || it.optString("status") == st) }
                JSONObject().put("range", rg).put("issues", JSONArray(list)).put("people", people())
                    .put("counts", JSONObject().put("call", 4).put("message", 3).put("payment", 1).put("crash", 3).put("hang", 1).put("page", 1).put("server", 5))
            }
            "alerts_get" -> JSONObject().put("enabled", true).put("push_ready", true).put("admin_phones", 2)
                .put("rules", JSONObject().put("calls", JSONObject().put("on", true).put("n", 5).put("min", 10)).put("payments", JSONObject().put("on", true).put("n", 1).put("min", 30))
                    .put("crashes", JSONObject().put("on", true).put("n", 5).put("min", 15)).put("server", JSONObject().put("on", true).put("n", 20).put("min", 10)).put("messages", JSONObject().put("on", false).put("n", 10).put("min", 10)))
                .put("last", arr(JSONObject().put("title", "⚠️ 6 call problems in 10 min").put("body", "Calls failing, dropping or without sound / picture · 5 members · mostly on Jio (5) · Tap to open App Health.").put("sent_to", 2).put("created_date", iso(95.0)),
                    JSONObject().put("title", "⚠️ 1 payment problem in 30 min").put("body", "Stuck, failed, or paid without coins added — please check · 1 member · Tap to open App Health.").put("sent_to", 2).put("created_date", iso(1440.0))))
            else -> JSONObject().put("ok", true).put("resolved", 1)
        }
    }
    private fun memberHealth(): JSONObject = JSONObject()
        .put("usage", JSONObject().put("opens", 214).put("seconds", 151_200).put("opens_7d", 38).put("seconds_7d", 25_400).put("days_used", 41).put("days_used_30d", 24).put("first_at", iso(60.0 * 24 * 52)).put("last_at", iso(1.0)))
        .put("phone", JSONObject().put("device", "Xiaomi 23021RAAEG").put("android", "13 (33)").put("app_version", "0.1.252").put("network", "mobile").put("operator", "Jio"))
        .put("pages", arr(JSONObject().put("page", "Home").put("opens", 96).put("seconds", 7420).put("slow", 2).put("hangs", 0), JSONObject().put("page", "Call Back").put("opens", 61).put("seconds", 3100).put("slow", 0).put("hangs", 0),
            JSONObject().put("page", "Explore").put("opens", 44).put("seconds", 5200).put("slow", 6).put("hangs", 1), JSONObject().put("page", "Wallet").put("opens", 12).put("seconds", 640).put("slow", 0).put("hangs", 0)))
        .put("purchases", arr(
            JSONObject().put("plan_name", "259 coins").put("amount_inr", 259).put("status", "succeeded").put("provider_ref", "GPA.3312-8841-2290-41775").put("app", "truefind").put("created_date", iso(300.0)),
            JSONObject().put("plan_name", "Starter · 1 week").put("amount_inr", 39).put("status", "pending").put("provider_ref", "GPA.3398-1123-7712-90021").put("app", "secret").put("created_date", iso(40.0)),
            JSONObject().put("plan_name", "49 coins").put("amount_inr", 49).put("status", "failed").put("provider_ref", "").put("app", "truefind").put("created_date", iso(2900.0)),
            JSONObject().put("plan_name", "99 coins").put("amount_inr", 99).put("status", "refunded").put("provider_ref", "GPA.3301-2211-0091-11220").put("app", "truefind").put("created_date", iso(9000.0))))
        .put("purchase_totals", JSONObject().put("paid", 6).put("paid_inr", 1123).put("pending", 1).put("failed", 1).put("refunded", 1).put("refunded_inr", 99))
        .put("calls", JSONObject().put("made", 12).put("received", 131).put("connected", 118).put("voice", 74).put("video", 44).put("voice_minutes", 612.5).put("video_minutes", 388.0)
            .put("not_answered", 19).put("declined", 6).put("not_started", 1).put("coins_charged", 220).put("creator_earned", 2310.5))
        .put("coins", JSONObject().put("spent", 412).put("spent_calls", 220).put("spent_chat", 61).put("spent_gifts_games", 131).put("bought", 1250).put("free", 140))
        .put("messages", JSONObject().put("sent", 1820).put("received", 2045).put("sent_7d", 214))
        .put("issues", arr(demoIssues[1], demoIssues[4]))
        .put("issue_counts", JSONObject().put("call", 1).put("message", 3))

    private fun payouts(a: String): JSONObject = when (a) {
        "list" -> JSONObject().put("pending", arr(
            JSONObject().put("profile_id", "p-g3").put("user_id", "u-g3").put("name", "Isha Gupta").put("phone", "9812345678").put("photo", face(true, 4)).put("requested_at", iso(180.0)).put("amount", 2000)
                .put("breakdown", JSONObject().put("gross", 2000).put("fee", 660).put("share", 1340).put("tds", 0).put("net", 1340).put("tdsApplies", false))
                .put("earned", 6120).put("paid_total", 1340).put("available", 4780).put("too_much", false).put("verified", true).put("upi", "isha@ybl").put("upi_name", "Isha Gupta").put("pan", "uploaded"),
            JSONObject().put("profile_id", "p-b0").put("user_id", "u-b0").put("name", "Rahul Kumar").put("phone", "9712345678").put("photo", face(false, 1)).put("requested_at", iso(60.0)).put("amount", 900)
                .put("breakdown", JSONObject().put("gross", 900).put("fee", 297).put("share", 603).put("tds", 60).put("net", 543).put("tdsApplies", true))
                .put("earned", 700).put("paid_total", 0).put("available", 700).put("too_much", true).put("verified", false).put("upi", "").put("upi_name", "").put("pan", "none")))
        "history" -> JSONObject().put("records", arr(JSONObject().put("id", "h1").put("name", "Isha Gupta").put("status", "paid").put("net", 1340).put("gross", 2000).put("upi_id", "isha@ybl").put("utr", "431512345678").put("decided_at", iso(50.0)).put("decided_by", "owner@truefindapp.com"),
            JSONObject().put("id", "h2").put("name", "Neha Yadav").put("status", "rejected").put("net", 400).put("gross", 600).put("reason", "UPI name does not match").put("decided_at", iso(3000.0)).put("decided_by", "owner@truefindapp.com")))
        "export" -> JSONObject().put("fy", "2026-27").put("count", 2).put("totals", JSONObject().put("net", 1880).put("tds", 60)).put("csv", "name,upi,net\nIsha Gupta,isha@ybl,1340")
        else -> JSONObject().put("ok", true)
    }

    private val tickets: List<JSONObject> by lazy {
        listOf(
            JSONObject().put("id", "t1").put("user_id", "u-b1").put("name", "Aman Sharma").put("category", "payment").put("subject", "Coins not added").put("status", "open").put("admin_unread", true).put("created_date", iso(30.0))
                .put("messages", arr(JSONObject().put("from", "user").put("text", "I paid ₹99 but the coins are not in my wallet").put("at", iso(30.0)).put("attachment", face(false, 6)),
                    JSONObject().put("from", "admin").put("text", "Thanks for writing to us. We are checking this and will reply soon.").put("at", iso(20.0)),
                    JSONObject().put("from", "user").put("text", "Ok, thank you").put("at", iso(18.0)))),
            JSONObject().put("id", "t2").put("contact", "9876543210").put("category", "deletion").put("subject", "Delete my account").put("status", "open").put("admin_unread", true).put("created_date", iso(90.0))
                .put("messages", arr(JSONObject().put("from", "user").put("text", "Please delete my True Find account and data.").put("at", iso(90.0)))),
            JSONObject().put("id", "t3").put("user_id", "u-g4").put("name", "Riya Kapoor").put("category", "payout").put("subject", "Withdrawal").put("status", "answered").put("created_date", iso(1500.0))
                .put("messages", arr(JSONObject().put("from", "user").put("text", "When will my withdrawal come?").put("at", iso(1500.0)), JSONObject().put("from", "admin").put("text", "Your withdrawal was sent today. It can take up to 24 hours to show in your bank.").put("at", iso(1400.0)))),
        )
    }
    private var lastTicket = "t1"
    private fun support(a: String): JSONObject = when (a) {
        "admin_list" -> JSONObject().put("counts", JSONObject().put("open", 3)).put("tickets", JSONArray(tickets))
        "admin_get" -> JSONObject().put("ticket", tickets.firstOrNull { it.optString("id") == lastTicket } ?: tickets[0])
        else -> JSONObject().put("ok", true)
    }

    private fun experts(): JSONObject = JSONObject()
        .put("applications", arr(JSONObject().put("id", "ea1").put("name", "Dr. Meera Joshi").put("status", "new").put("title", "Relationship counsellor").put("years", 6).put("langs", arr("Hindi", "English")).put("phone", "9811122233")
            .put("specs", arr("Breakup", "Loneliness", "Stress")).put("qual", "MA Psychology").put("durs", arr(15, 30)).put("created_date", iso(600.0)).put("about", "I help people feel heard. Calm, kind and practical.")))
        .put("cases", arr(JSONObject().put("id", "ec1").put("kind", "expert_report").put("user_id", "u-b4").put("items", arr("Used bad words", "Asked for my number"))
            .put("booking", JSONObject().put("when", "Today, 7:30 pm").put("dur", 15).put("coins", 120).put("expert_name", "Ritu Arora").put("user_alias", "Member 4821").put("talk_seconds", 312))))
        .put("experts", arr(
            JSONObject().put("id", "ex1").put("name", "Ritu Arora").put("title", "Life coach").put("photo", face(true, 15)).put("sessions", 48).put("hours", 21).put("rating", 4.8).put("reviews", 31).put("accepting", true)
                .put("on_time", 96).put("first_ring", 88).put("missed", 1).put("warnings", 0).put("user_warnings", 0).put("earned_available", 1840).put("pending_photo", face(true, 16)),
            JSONObject().put("id", "ex2").put("name", "Sana Khan").put("title", "Career guide").put("photo", face(true, 17)).put("sessions", 12).put("hours", 5).put("reviews", 0).put("accepting", false).put("admin_paused", true).put("paused_reason", "Missed 4 sessions")
                .put("on_time", 70).put("first_ring", 60).put("missed", 4).put("warnings", 4).put("user_warnings", 1).put("earned_available", 0).put("leave_status", "pending").put("leave_reason", "Moving to a full-time job").put("leave_at", iso(100.0))))

    private fun whatsapp(): JSONObject = JSONObject()
        .put("secrets", JSONObject().put("token_set", true).put("token_length", 212).put("phone_id_set", true).put("phone_id_ok", true).put("phone_id_end", "4471").put("template", "truefind_otp").put("language", "en"))
        .put("number", JSONObject().put("ok", true).put("display_phone_number", "+91 98xxxx4471").put("verified_name", "True Find").put("name_status", "APPROVED").put("status", "CONNECTED").put("account_mode", "LIVE").put("quality_rating", "GREEN").put("messaging_limit_tier", "TIER_1K"))
        .put("requests", JSONObject().put("last_hour", 42).put("last_day", 611))
        .put("summary", JSONObject()
            .put("today", JSONObject().put("sent", 236).put("verified", 214).put("failed", 3).put("wrong", 11).put("limited", 2).put("tests", 1).put("cost", 30.68))
            .put("month", JSONObject().put("sent", 5122).put("verified", 4630).put("failed", 41).put("wrong", 180).put("limited", 25).put("tests", 9).put("cost", 665.86))
            .put("added", 1000).put("spent_all", 812.4).put("balance", 187.6).put("rate", 0.13)
            .put("recent", arr(JSONObject().put("kind", "send").put("ok", true).put("phone_end", "3210").put("at", System.currentTimeMillis() - 60_000),
                JSONObject().put("kind", "verify").put("ok", true).put("phone_end", "3210").put("at", System.currentTimeMillis() - 30_000),
                JSONObject().put("kind", "limited").put("ok", false).put("phone_end", "9981").put("error", "Too many codes asked").put("at", System.currentTimeMillis() - 400_000))))
}
