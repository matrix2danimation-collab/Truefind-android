package app.truefind.android

import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import org.json.JSONObject
import kotlin.math.max

/** Verify (VerifyTab.jsx): Creators (live video + UPI), Hosts (video + call time) and new profile Photos. */
class AdVerify(a: AdminScreen) : AdTab(a) {
    companion object {
        const val HOST_MIN_VIDEO_HOURS = 5      // girls: video-call hours
        const val MALE_MIN_CALL_MINUTES = 200   // boys: call minutes (voice + video)
        val HOST_LABEL = mapOf("audio_room" to "Audio room", "video_room" to "Video room", "single" to "Single entry (1-on-1)", "game" to "Game host")
        val REJECT_REASONS = listOf("Selfie is blurry or dark", "Pose not done", "Face not clearly visible", "Name doesn't match UPI", "Looks fake / not the same person")
        val PHOTO_REJECT = listOf("AI-generated photo", "Fake / not the same person", "Celebrity or someone else", "Face not visible / blurry", "Inappropriate photo")

        fun photoOf(p: JSONObject?): String = AF.s(p, "profile_picture").ifEmpty { p?.optJSONArray("images")?.let { PR.srcOf(it.opt(0)) } ?: "" }
        /** true when the two names share at least one word of 3+ letters */
        fun namesMatch(a: String, b: String): Boolean {
            fun words(s: String) = s.lowercase().replace(Regex("[^a-z\\s]"), " ").split(Regex("\\s+")).filter { it.length >= 3 }
            val x = words(a); val y = words(b).toSet()
            if (x.isEmpty() || y.isEmpty()) return false
            return x.any { y.contains(it) }
        }
        fun meta(p: JSONObject): String = listOf(AF.s(p, "gender"), AF.s(p, "age").let { if (it.isNotEmpty()) "$it yrs" else "" }, AF.s(p, "city")).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private var kind = "creators"
    private var filter = "pending"
    private var busy = ""
    private val box = AU.col(ctx)

    override fun build() { render() }
    override fun onShared() { render() }

    private fun render() {
        if (!alive) return
        root.removeAllViews()
        if (a.profError.isNotEmpty()) root.addView(a.profilesErrorLine())
        if (a.profLoading && a.profiles.isEmpty()) { root.addView(AU.loading(ctx)); return }
        val st = a.stats()
        root.addView(AU.filters(ctx, listOf(Triple("creators", "Creators", st.pending), Triple("hosts", "Hosts", st.hostPending), Triple("photos", "Photos", st.photoPending)), kind) { kind = it; filter = "pending"; render() })
        box.removeAllViews()
        when (kind) { "creators" -> creators(); "hosts" -> hosts(); else -> photos() }
        root.addView(box, AU.lp(-1, -2))
    }

    private fun head(p: JSONObject, `when`: String, status: String, zoom: Boolean): LinearLayout {
        val r = AU.row(ctx)
        val img = AdImg(ctx, src, true, 48f, 48f)
        img.set(photoOf(p))
        if (zoom) img.onOpen = { a.zoom(it) }
        r.addView(img, LinearLayout.LayoutParams(Ui.dpi(48f), Ui.dpi(48f)))
        val c = AU.col(ctx)
        c.addView(AU.t(ctx, AF.s(p, "full_name").ifEmpty { "—" }, 15.5f, AK.ink, true).ell())
        c.addView(AU.t(ctx, meta(p), 12f, AK.sub))
        c.addView(AU.t(ctx, `when`, 12f, AK.faint))
        r.addView(c, AU.lp(0, -2, 1f, l = 12f, r = 12f))
        r.addView(AU.pill(ctx, status))
        r.layoutParams = AU.lp(-1, -2, b = 12f)
        return r
    }

    private fun decide(status: String, b: Boolean, onApprove: () -> Unit, onReject: () -> Unit, approveLabel: String = "Approve", rejectLabel: String? = null, disabledApprove: Boolean = false): LinearLayout {
        val ap = if (status != "approved") AU.btn(ctx, approveLabel, "dark", icon = AdIc.CheckCircle2) { onApprove() }.also { it.busy = b; it.off = disabledApprove } else null
        val rj = if (status != "rejected") AU.btn(ctx, rejectLabel ?: if (status == "approved") "Revoke" else "Reject", "dangerLight", icon = AdIc.XCircle) { onReject() }.also { it.off = b } else null
        return AU.btnRow(ctx, 10f, true, ap, rj)
    }

    private fun label(text: String) = AU.t(ctx, text, 12f, AK.sub).apply { layoutParams = AU.lp(-1, -2, b = 4f) }

    // ───────── Creators ─────────
    private fun creators() {
        val subs = a.subs()
        val pending = subs.count { PR.creatorStatus(it) == "pending" }
        box.addView(AU.filters(ctx, listOf(Triple("pending", "Pending", pending), Triple("approved", "Approved", 0), Triple("rejected", "Rejected", 0)), filter) { filter = it; render() })
        val list = subs.filter { PR.creatorStatus(it) == filter }
        if (list.isEmpty()) { box.addView(AU.empty(ctx, AdIc.ShieldCheck, "Nothing here", "No $filter verifications.")); return }
        list.forEachIndexed { i, p ->
            val status = PR.creatorStatus(p)
            val c = AU.card(ctx)
            c.addView(head(p, "Submitted ${AF.fmt(AF.s(p, "creator_submitted_at"))}", status, true))
            val video = AF.s(p, "creator_video_uri")
            if (video.isNotEmpty()) {
                c.addView(label("Live video (turn sound on)"))
                c.addView(AdVideo(ctx, src).also { it.set(video) }, AU.lp(-1, -2, b = 10f))
            }
            val g = AdGrid(ctx, 0f, 10f, 2)
            g.addView(AU.col(ctx).apply {
                addView(label(if (video.isNotEmpty()) "Frame from video" else "Live selfie"))
                addView(AdImg(ctx, src, false, 0f, 0f, 4f / 3f).also { it.set(AF.s(p, "creator_selfie_uri")); it.onOpen = { u -> a.zoom(u) } }, AU.lp(-1, -2))
            })
            g.addView(AU.col(ctx).apply {
                addView(label("Profile photo"))
                addView(AdImg(ctx, src, false, 0f, 0f, 4f / 3f).also { it.set(photoOf(p)); it.onOpen = { u -> a.zoom(u) } }, AU.lp(-1, -2))
            })
            c.addView(g, AU.lp(-1, -2, b = 10f))
            val pose = android.text.SpannableStringBuilder(AU.boldLead("Pose asked: ", AF.s(p, "creator_pose").ifEmpty { "—" }, AK.deep))
            val code = AF.s(p, "creator_code")
            if (code.isNotEmpty()) {
                pose.append("\n").append(AU.boldLead("Numbers to say: ", "", AK.deep))
                val st = pose.length; pose.append(code.toCharArray().joinToString("\u2009"))
                pose.setSpan(TypefaceSpanCompat(Ui.bold), st, pose.length, 0)
            }
            c.addView(AU.note(ctx, pose), AU.lp(-1, -2, b = 10f))
            c.addView(AU.rows(ctx, 3f,
                AU.kv(ctx, "Profile name", AF.s(p, "full_name")),
                AU.kv(ctx, "UPI name", AF.s(p, "creator_upi_name")),
                AU.kv(ctx, "UPI ID", AF.s(p, "creator_upi_id")),
                if (status == "approved") AU.kv(ctx, "Approved UPI", AF.s(p, "creator_approved_upi_id")) else null,
                AU.kv(ctx, "Phone", AF.s(p, "phone")),
            ), AU.lp(-1, -2, b = 10f))
            val missing = listOfNotNull(if (video.isEmpty()) "No video" else null, if (AF.s(p, "creator_upi_id").isEmpty()) "No UPI ID" else null, if (AF.s(p, "creator_upi_name").isEmpty()) "No UPI name" else null)
            val mismatch = !namesMatch(AF.s(p, "creator_upi_name"), AF.s(p, "full_name"))
            if (mismatch || AF.s(p, "gender") != "female" || missing.isNotEmpty()) {
                val w = AU.rows(ctx, 5f,
                    if (mismatch) AU.warn(ctx, "Profile name and UPI name don't match") else null,
                    if (AF.s(p, "gender") != "female") AU.warn(ctx, "Profile gender is not female") else null,
                    *missing.map { AU.warn(ctx, it) }.toTypedArray())
                c.addView(w, AU.lp(-1, -2, b = 10f))
            }
            if (status != "pending") {
                val at = AF.s(p, "creator_reviewed_at"); val by = AF.s(p, "creator_reviewed_by")
                c.addView(AU.t(ctx, "${if (status == "approved") "Approved" else "Rejected"}${if (at.isNotEmpty()) " on ${AF.fmt(at)}" else ""}${if (by.isNotEmpty()) " by $by" else ""}", 12f, AK.sub), AU.lp(-1, -2, b = if (status == "rejected" && AF.s(p, "creator_reject_reason").isNotEmpty()) 0f else 10f))
                if (status == "rejected" && AF.s(p, "creator_reject_reason").isNotEmpty()) c.addView(AU.t(ctx, "Reason: ${AF.s(p, "creator_reject_reason")}", 12f, AK.red), AU.lp(-1, -2, b = 10f))
            }
            c.addView(decide(status, busy == p.optString("id"), { act(p, "approved", "") }, { askReject(p) }))
            box.addView(c, AU.lp(-1, -2, t = if (i > 0) 12f else 0f))
        }
    }

    private fun act(p: JSONObject, status: String, reason: String) {
        busy = p.optString("id"); render()
        a.decideCreator(p, status, reason) { e -> busy = ""; if (e != null) say(e, true); render() }
    }
    private fun askReject(p: JSONObject) {
        AdAsk(a, "Reject verification", "She will see this reason and can submit again.", REJECT_REASONS, "Reason (she sees this)", "Reject", true) { r, done ->
            a.decideCreator(p, "rejected", r) { e -> if (e != null) { say(e, true); done(false) } else { done(true); render() } }
        }.open()
    }

    // ───────── Hosts ─────────
    private fun hosts() {
        val apps = a.profiles.filter { PR.hostStatus(it) != "none" }.sortedByDescending { AF.ms(AF.s(it, "host_applied_at")) }
        val count = { k: String -> apps.count { PR.hostStatus(it) == k } }
        box.addView(AU.filters(ctx, listOf(Triple("pending", "Pending", count("pending")), Triple("approved", "Approved", 0), Triple("rejected", "Rejected", 0)), filter) { filter = it; render() })
        val list = apps.filter { PR.hostStatus(it) == filter }
        if (list.isEmpty()) { box.addView(AU.empty(ctx, AdIc.Radio, "Nothing here", "No $filter host applications.")); return }
        list.forEachIndexed { i, p ->
            val st = PR.hostStatus(p)
            val female = AF.s(p, "gender") == "female"
            val creatorOk = PR.creatorStatus(p) == "approved"
            val c = AU.card(ctx)
            c.addView(head(p, "Applied ${AF.fmt(AF.s(p, "host_applied_at"))}", st, true))
            val types = AF.strs(p, "host_types")
            if (types.isNotEmpty()) c.addView(AU.wrap(ctx, 6f, *types.map { AU.pill(ctx, "x", HOST_LABEL[it] ?: it) }.toTypedArray()), AU.lp(-1, -2, b = 10f))
            val video = AF.s(p, "host_video_uri")
            if (video.isNotEmpty()) {
                c.addView(label("Host video (turn sound on)"))
                c.addView(AdVideo(ctx, src).also { it.set(video) }, AU.lp(-1, -2, b = 10f))
            } else if (st == "pending") c.addView(AU.warn(ctx, "No host video"), AU.lp(-1, -2, b = 10f))
            val line = AF.s(p, "host_line")
            if (line.isNotEmpty()) c.addView(AU.note(ctx, AU.boldLead("Line to say: ", line, AK.deep)), AU.lp(-1, -2, b = 10f))
            c.addView(callTime(AF.s(p, "created_by_id"), p.opt("host_call_seconds").takeIf { it != null && it != JSONObject.NULL }, !female), AU.lp(-1, -2, b = 10f))
            if (!female) {
                val upi = AF.s(p, "host_upi_id"); val upiName = AF.s(p, "host_upi_name")
                c.addView(AU.rows(ctx, 3f,
                    AU.kv(ctx, "Profile name", AF.s(p, "full_name")),
                    AU.kv(ctx, "UPI name", upiName),
                    AU.kv(ctx, "UPI ID", upi),
                    if (st == "approved") AU.kv(ctx, "Approved UPI", AF.s(p, "host_approved_upi_id")) else null,
                    if (st == "pending" && (upi.isEmpty() || upiName.isEmpty())) AU.warn(ctx, "No UPI details") else null,
                    if (st == "pending" && upiName.isNotEmpty() && !namesMatch(upiName, AF.s(p, "full_name"))) AU.warn(ctx, "Profile name and UPI name don't match") else null,
                ), AU.lp(-1, -2, b = 10f))
            }
            val about = AF.s(p, "host_about")
            if (about.isNotEmpty()) c.addView(AU.note(ctx, about, AK.soft), AU.lp(-1, -2, b = 10f))
            if (female && !creatorOk && st == "pending") c.addView(AU.warn(ctx, "Creator verification is not approved yet"), AU.lp(-1, -2, b = 10f))
            if (st == "rejected" && AF.s(p, "host_reject_reason").isNotEmpty()) c.addView(AU.t(ctx, "Reason: ${AF.s(p, "host_reject_reason")}", 12f, AK.red), AU.lp(-1, -2, b = 8f))
            if (st != "pending") { val at = AF.s(p, "host_reviewed_at"); c.addView(AU.t(ctx, "${if (st == "approved") "Approved" else "Rejected"}${if (at.isNotEmpty()) " on ${AF.fmt(at)}" else ""}", 12f, AK.sub), AU.lp(-1, -2, b = 8f)) }
            c.addView(decide(st, busy == p.optString("id"), { actHost(p, "approved", "") }, { askHost(p) }, rejectLabel = if (st == "approved") "Remove host" else "Reject", disabledApprove = female && !creatorOk))
            box.addView(c, AU.lp(-1, -2, t = if (i > 0) 12f else 0f))
        }
    }

    private fun actHost(p: JSONObject, status: String, reason: String) {
        busy = p.optString("id"); render()
        a.decideHost(p, status, reason) { e -> busy = ""; if (e != null) say(e, true); render() }
    }
    private fun askHost(p: JSONObject) {
        val approved = PR.hostStatus(p) == "approved"
        AdAsk(a, if (approved) "Remove host" else "Reject host application", "The member sees this reason.", emptyList(), "Reason", if (approved) "Remove host" else "Reject", true) { r, done ->
            a.decideHost(p, "rejected", r) { e -> if (e != null) { say(e, true); done(false) } else { done(true); render() } }
        }.open()
    }

    /** Real call time for one user, read on the admin side (all calls, or video calls for girls). */
    private fun callTime(userId: String, reported: Any?, male: Boolean): LinearLayout {
        val c = AU.col(ctx)
        val need = if (male) MALE_MIN_CALL_MINUTES * 60.0 else HOST_MIN_VIDEO_HOURS * 3600.0
        fun show(x: Double) = if (male) "${Math.round(max(0.0, x) / 60)} min" else String.format(java.util.Locale.US, "%.1f h", max(0.0, x) / 3600)
        fun draw(secs: Double?) {
            c.removeAllViews()
            val real = secs != null && secs >= 0
            c.addView(AU.kv(ctx, if (male) "Call minutes (voice + video)" else "Video call hours", if (secs == null) "Checking…" else if (real) show(secs) else "Can't read"))
            if (reported != null) c.addView(AU.kv(ctx, "When applied", show(AF.num(reported))), AU.lp(-1, -2, t = 3f))
            val ok = if (real) secs!! >= need else AF.num(reported) >= need
            if (secs != null && !ok) c.addView(AU.warn(ctx, if (male) "Less than $MALE_MIN_CALL_MINUTES minutes of calls" else "Less than $HOST_MIN_VIDEO_HOURS hours of video calls"), AU.lp(-1, -2, t = 3f))
        }
        draw(null)
        if (userId.isEmpty()) { draw(-1.0); return c }
        val qa = JSONObject().put("caller_id", userId); val qb = JSONObject().put("callee_id", userId)
        if (!male) { qa.put("type", "video"); qb.put("type", "video") }
        val got = ArrayList<JSONObject>(); var left = 2; var failed = false
        val each: (Api.Result) -> Unit = { r ->
            if (r.ok) got.addAll(Tf.list(r)) else failed = true
            if (--left == 0 && alive) {
                if (failed) draw(-1.0) else {
                    val seen = HashSet<String>(); var t = 0.0
                    for (call in got) {
                        if (!seen.add(call.optString("id"))) continue
                        var x = AF.n(call, "duration_seconds")
                        if (x == 0.0) { val s = AF.ms(AF.s(call, "started_at")); val e = AF.ms(AF.s(call, "ended_at")); if (s > 0 && e > s) x = (e - s) / 1000.0 }
                        t += x
                    }
                    draw(t)
                }
            }
        }
        src.page("Call", qa, null, 1000, each)
        src.page("Call", qb, null, 1000, each)
        return c
    }

    // ───────── Photos ─────────
    private fun photos() {
        val items = ArrayList<Pair<JSONObject, JSONObject>>()
        for (p in a.profiles) for (q in Tf.arr(p.optJSONArray("photo_queue"))) if (AF.s(q, "status") != "rejected") items.add(p to q)
        val list = items.sortedBy { AF.ms(AF.s(it.second, "at")).let { t -> if (t <= 0) 0L else t } }
        if (list.isEmpty()) { box.addView(AU.empty(ctx, AdIc.Image, "No photos to review", "New profile photos show up here.")); return }
        box.addView(AU.t(ctx, "Check: is it a real photo of this person? Reject AI-made, fake, celebrity or someone else's photos.", 12.5f, AK.sub), AU.lp(-1, -2, b = 10f))
        list.forEachIndexed { i, (p, q) ->
            val uri = AF.s(q, "uri")
            val c = AU.card(ctx)
            c.addView(head(p, "Uploaded ${AF.fmt(AF.s(q, "at"))}${if (AF.b(q, "main")) " · main photo" else ""}", "pending", false))
            c.addView(AdImg(ctx, src, false, 0f, 0f, 5f / 4f).also { it.set(uri); it.onOpen = { u -> a.zoom(u) } }, AU.lp(-1, -2))
            val approved = (listOf(AF.s(p, "profile_picture")) + (0 until (p.optJSONArray("images")?.length() ?: 0)).map { PR.srcOf(p.optJSONArray("images")!!.opt(it)) }).filter { it.isNotEmpty() }.distinct()
            val selfie = AF.s(p, "creator_selfie_uri")
            if (approved.isNotEmpty() || selfie.isNotEmpty()) {
                c.addView(AU.t(ctx, "Compare with", 12f, AK.sub), AU.lp(-1, -2, t = 10f, b = 4f))
                val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
                val r = AU.row(ctx, Gravity.TOP)
                if (selfie.isNotEmpty()) r.addView(AU.col(ctx).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(AdImg(ctx, src, false, 64f, 80f).also { it.set(selfie); it.onOpen = { u -> a.zoom(u) } }, LinearLayout.LayoutParams(Ui.dpi(64f), Ui.dpi(80f)))
                    addView(AU.t(ctx, "Verify video", 10.5f, AK.deep, true))
                }, AU.lp(-2, -2, r = 8f))
                for (u in approved) r.addView(AdImg(ctx, src, false, 64f, 80f).also { it.set(u); it.onOpen = { x -> a.zoom(x) } }, LinearLayout.LayoutParams(Ui.dpi(64f), Ui.dpi(80f)).apply { rightMargin = Ui.dpi(8f) })
                hs.addView(r)
                c.addView(hs, AU.lp(-1, -2, b = 2f))
            }
            val b = busy == uri
            c.addView(AU.btnRow(ctx, 10f, true,
                AU.btn(ctx, "Real · Approve", "dark", icon = AdIc.CheckCircle2) { actPhoto(p, uri, "approved", "") }.also { it.busy = b },
                AU.btn(ctx, "Reject", "dangerLight", icon = AdIc.XCircle) { askPhoto(p, uri) }.also { it.off = b }), AU.lp(-1, -2, t = 12f))
            box.addView(c, AU.lp(-1, -2, t = if (i > 0) 12f else 0f))
        }
    }

    private fun actPhoto(p: JSONObject, uri: String, status: String, reason: String) {
        if (busy.isNotEmpty()) return // one photo at a time: two saves on the same member would overwrite each other
        busy = uri; render()
        a.decidePhoto(p, uri, status, reason) { e -> busy = ""; if (e != null) say(e, true); render() }
    }
    private fun askPhoto(p: JSONObject, uri: String) {
        AdAsk(a, "Why reject this photo?", null, PHOTO_REJECT, "Reason", "Reject photo", true) { r, done ->
            if (busy.isNotEmpty()) { done(false); return@AdAsk }
            busy = uri; render()
            a.decidePhoto(p, uri, "rejected", r) { e -> busy = ""; if (e != null) { say(e, true); done(false) } else done(true); render() }
        }.open()
    }

    override fun previewOpen(action: String) {
        when (action) {
            "hosts" -> { kind = "hosts"; filter = "pending"; render() }
            "photos" -> { kind = "photos"; render() }
            "reject" -> a.subs().firstOrNull { PR.creatorStatus(it) == "pending" }?.let { askReject(it) }
        }
    }
}
