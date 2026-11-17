package app.truefind.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** A page of the Profile address: purple (or black) top + the rounded part under it, scrolling. */
private fun pageShell(ctx: Context, head: View, ground: Int, bottomPad: Float): Triple<ScrollView, LinearLayout, LinearLayout> {
    val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true; setBackgroundColor(ground) }
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    col.addView(head, LinearLayout.LayoutParams(-1, -2))
    val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = Tf.topRound(ground, 28f)
        setPadding(Ui.dpi(16f), Ui.dpi(20f), Ui.dpi(16f), Ui.dpi(bottomPad))
    }
    col.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = -Ui.dpi(28f) })
    scroll.addView(col, FrameLayout.LayoutParams(-1, -2))
    return Triple(scroll, col, body)
}

/**
 * Edit Profile (?edit=1): photo, name, bio, state, city, show my location — Save on the top
 * and at the bottom. Back with changes asks "Discard changes?".
 */
@SuppressLint("ViewConstructor")
class PEditPage(ctx: Context, private val ps: ProfileScreen) : FrameLayout(ctx) {
    data class Draft(var fullName: String, var bio: String, var state: String, var city: String, var locationVisible: Boolean)

    private val start: Draft
    private val d: Draft
    private var busy = false
    private val face = PImg(ctx, round = true).apply { placeholder = Ui.c("#E9DDFB") }
    private val avatarBtn: TextView
    private val avatarSpin = TfLoader(ctx, 16f, PC.head)
    private val topSave: FrameLayout
    private val topSaveText: TextView
    private val topSaveSpin = TfLoader(ctx, 15f, PC.head)
    private val saveBtn: TfBtn
    private val stateText: TextView
    private val count: TextView
    private var sheetClose: (() -> Unit)? = null

    init {
        setBackgroundColor(PC.ground)
        isClickable = true
        val p = ps.profile
        start = Draft(PR.s(p, "full_name").ifEmpty { ps.name }, PR.s(p, "bio"), PR.s(p, "state"), PR.s(p, "city"), p?.opt("location_visible") != false)
        d = start.copy()
        // Save on the purple top
        topSave = FrameLayout(ctx).apply {
            background = Tf.box(Color.WHITE, 12f); contentDescription = "Save"; minimumWidth = Ui.dpi(68f)
            setPadding(Ui.dpi(18f), 0, Ui.dpi(18f), 0)
            setOnClickListener { save() }; Ui.pressable(this, .97f)
            layoutParams = LinearLayout.LayoutParams(-2, Ui.dpi(44f))
        }
        topSaveText = Tf.t(ctx, "Save", 14.5f, PC.head)
        topSave.addView(topSaveText, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        topSave.addView(topSaveSpin, FrameLayout.LayoutParams(Ui.dpi(15f), Ui.dpi(15f), Gravity.CENTER))
        topSaveSpin.visibility = GONE
        val intro = PUi.para(ctx, "Update your photo, name, bio and location.", 13.5f, PC.onHead, 20f)
        val head = PUi.pageHead(ctx, "Edit Profile", PUi.headBtn(ctx, "Back to profile", PUi.lu(Lu.ChevronLeft, Color.WHITE, 22f, 2.2f)) { back() }, topSave, listOf(intro), gap = 16f)
        val (scroll, _, body) = pageShell(ctx, head, PC.ground, 160f)
        // photo
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val fwrap = FrameLayout(ctx).apply { background = Tf.circle(Ui.c("#E9DDFB"), 1f, PC.wineLine); setPadding(Ui.dpi(1f), Ui.dpi(1f), Ui.dpi(1f), Ui.dpi(1f)) }
        fwrap.addView(face, FrameLayout.LayoutParams(-1, -1))
        row.addView(fwrap, LinearLayout.LayoutParams(Ui.dpi(64f), Ui.dpi(64f)).apply { rightMargin = Ui.dpi(14f) })
        val ab = FrameLayout(ctx).apply { background = Tf.box(Color.WHITE, 12f, 1.5f, PC.head); setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0); setOnClickListener { if (!ps.uploadingAvatar) ps.changeAvatar() }; Ui.pressable(this, .97f) }
        avatarBtn = Tf.t(ctx, "Change profile photo", 14f, PC.head).apply { gravity = Gravity.CENTER_VERTICAL; compoundDrawablePadding = Ui.dpi(8f) }
        ab.addView(avatarBtn, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL))
        ab.addView(avatarSpin, FrameLayout.LayoutParams(Ui.dpi(16f), Ui.dpi(16f), Gravity.CENTER_VERTICAL))
        row.addView(ab, LinearLayout.LayoutParams(-2, Ui.dpi(44f)))
        body.addView(row)
        // name
        body.addView(label("Name"))
        val nameIn = TfInput(ctx, "Your name").apply { setText(d.fullName); maxLen(40); contentDescription = "Name"; onText = { d.fullName = it } }
        body.addView(nameIn, LinearLayout.LayoutParams(-1, Ui.dpi(48f)))
        // bio
        body.addView(label("Bio"))
        val bioIn = TfInput(ctx, "Write a few words about yourself", multiLine = true, lines = 3).apply { setText(d.bio); maxLen(PR.MAX_BIO); contentDescription = "Bio" }
        body.addView(bioIn, LinearLayout.LayoutParams(-1, -2))
        count = Tf.t(ctx, "", 12f, PC.sub, false).lineH(14f).apply { gravity = Gravity.END }
        body.addView(count, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(4f); leftMargin = Ui.dpi(2f); rightMargin = Ui.dpi(2f) })
        bioIn.onText = { d.bio = it; count.text = "${it.length} / ${PR.MAX_BIO}" }
        count.text = "${d.bio.length} / ${PR.MAX_BIO}"
        // state (a list to choose from, like the web's select)
        body.addView(label("State"))
        val sel = FrameLayout(ctx).apply { background = Tf.box(Color.WHITE, 12f, 1.5f, PC.line); setPadding(Ui.dpi(14f), 0, Ui.dpi(12f), 0); contentDescription = "State"; setOnClickListener { pickState() } }
        stateText = Tf.t(ctx, "", 16f, PC.ink, false).ell()
        sel.addView(stateText, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply { rightMargin = Ui.dpi(24f) })
        sel.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.ChevronDown, PC.sub, 16f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END))
        body.addView(sel, LinearLayout.LayoutParams(-1, Ui.dpi(48f)))
        // city
        body.addView(label("City"))
        val cityIn = TfInput(ctx, "Your city").apply { setText(d.city); maxLen(40); contentDescription = "City"; onText = { d.city = it } }
        body.addView(cityIn, LinearLayout.LayoutParams(-1, Ui.dpi(48f)))
        // show my location
        val loc = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = Tf.box(Color.WHITE, 14f, 1f, PC.line); setPadding(Ui.dpi(14f), 0, Ui.dpi(14f), 0); minimumHeight = Ui.dpi(56f) }
        loc.addView(Tf.t(ctx, "Show my location on profile", 14.5f, PC.ink).lineH(20f), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Ui.dpi(12f) })
        val tg = TfToggle(ctx, PC.wine).apply { set(d.locationVisible, false); contentDescription = "Show my location on profile" }
        tg.onChange = { v -> d.locationVisible = v; tg.set(v) }
        loc.addView(tg)
        body.addView(loc, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(16f) })
        saveBtn = PUi.btn(ctx, "Save changes", height = 50f, radius = 12f) { save() }
        body.addView(saveBtn, LinearLayout.LayoutParams(-1, Ui.dpi(50f)).apply { topMargin = Ui.dpi(16f) })
        addView(scroll, LayoutParams(-1, -1))
        renderState()
        profileChanged()
    }

    private fun label(t: String): TextView = Tf.t(context, t, 13f, PC.sub).lineH(18f).apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(16f); bottomMargin = Ui.dpi(6f) }
    }

    private fun renderState() {
        stateText.text = d.state.ifEmpty { "Select state" }
        stateText.setTextColor(if (d.state.isEmpty()) PC.ink else PC.ink)
    }

    private fun pickState() {
        val ctx = context
        // the saved state stays in the list even when it is not one of the usual names
        val states = if (PR.STATES.contains(d.state) || d.state.isEmpty()) PR.STATES else listOf(d.state) + PR.STATES
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (s in listOf("") + states) {
            val on = s == d.state
            val r = Tf.t(ctx, s.ifEmpty { "Select state" }, 15f, if (on) PC.head else PC.ink, on).apply {
                gravity = Gravity.CENTER_VERTICAL; minHeight = Ui.dpi(46f); setPadding(Ui.dpi(6f), 0, Ui.dpi(6f), 0)
                if (on) { compoundDrawablePadding = Ui.dpi(8f); setCompoundDrawablesWithIntrinsicBounds(null, null, PUi.lu(Lu.Check, PC.head, 16f, 2.6f), null) }
                setOnClickListener { d.state = s; renderState(); sheetClose?.invoke() }
            }
            col.addView(r, LinearLayout.LayoutParams(-1, -2))
            col.addView(View(ctx).apply { setBackgroundColor(PC.line) }, LinearLayout.LayoutParams(-1, Ui.dpi(1f)))
        }
        sheetClose = PUi.sheet(ps.pages.layer, "State", null, col) { sheetClose = null }
    }

    private val dirty: Boolean get() = d != start

    private fun save() {
        if (busy) return
        busy = true; renderBusy()
        ps.saveEdit(d.copy()) { busy = false; renderBusy() }
    }
    private fun renderBusy() {
        topSaveText.visibility = if (busy) INVISIBLE else VISIBLE
        topSaveSpin.visibility = if (busy) VISIBLE else GONE
        topSave.isEnabled = !busy
        saveBtn.busy = busy
    }

    private fun back() {
        if (!dirty) { ps.backToPage(); return }
        PConfirm(ps.pages.layer, "Discard changes?", "Your changes will not be saved.", "Discard", danger = true, onConfirm = { box -> box.close(); ps.backToPage() })
    }

    /** the photo / upload state changed on the profile */
    fun profileChanged() {
        face.set(ps.avatarRaws)
        val up = ps.uploadingAvatar
        avatarSpin.visibility = if (up) VISIBLE else GONE
        avatarBtn.setCompoundDrawablesWithIntrinsicBounds(if (up) null else PUi.lu(Lu.Camera, PC.head, 16f), null, null, null)
        avatarBtn.setPadding(if (up) Ui.dpi(24f) else 0, 0, 0, 0)
    }

    fun closed() { sheetClose?.invoke() }

    fun previewOpen(what: String) { if (what == "state") pickState() else if (what == "discard") { d.bio = d.bio + " "; back() } }
}

/**
 * Followers / Following (?people=followers | following): black top with the two tabs, the
 * list (Follow / Follow back / Following), "People you can follow", Unfollow asks first.
 */
@SuppressLint("ViewConstructor")
class PPeoplePage(ctx: Context, private val ps: ProfileScreen) : FrameLayout(ctx) {
    private var kind = ""
    private val lists = HashMap<String, List<JSONObject>?>()
    private var suggestions: List<JSONObject> = emptyList()
    private var error = ""
    private var busyId = ""
    private var alive = true
    private val tabs = LinearLayout(ctx)
    private val body: LinearLayout

    init {
        setBackgroundColor(Color.WHITE)
        isClickable = true
        lists["followers"] = null; lists["following"] = null
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.background = Tf.box(PC.fHeadBtn, 12f)
        tabs.setPadding(Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f), Ui.dpi(4f))
        tabs.contentDescription = "Followers or Following"
        tabs.layoutParams = LinearLayout.LayoutParams(-1, Ui.dpi(44f))
        val head = PUi.pageHead(ctx, "Followers and Following", PUi.headBtn(ctx, "Back to profile", PUi.lu(Lu.ChevronLeft, Color.WHITE, 22f, 2.2f), PC.fHeadBtn) { ps.backToPage() },
            null, listOf(tabs), bg = PC.fHead, subColor = PC.fOnHead)
        val shell = pageShell(ctx, head, Color.WHITE, 28f)
        body = shell.third
        addView(shell.first, LayoutParams(-1, -1))
    }

    override fun onDetachedFromWindow() { alive = false; super.onDetachedFromWindow() }

    fun setKind(k: String) {
        if (k == kind) return
        kind = k
        renderTabs(); render()
        load(k, false)
    }

    fun renderTabs() {
        val ctx = context
        tabs.removeAllViews()
        val fc = ps.followCounts
        val p = ps.profile
        val counts = listOf(fc?.get(0) ?: PR.countOf(p, listOf("followers_count", "followers")), fc?.get(1) ?: PR.countOf(p, listOf("following_count", "following")))
        listOf("followers" to "Followers", "following" to "Following").forEachIndexed { i, (id, text) ->
            val on = kind == id
            val sp = android.text.SpannableStringBuilder("$text ")
            val st = sp.length
            sp.append(counts[i].toString())
            sp.setSpan(TfTypefaceSpan(Ui.regular), st, sp.length, 0)
            val t = Tf.t(ctx, sp, 14.5f, if (on) Color.WHITE else PC.fTabOff).apply {
                gravity = Gravity.CENTER; background = if (on) Tf.box(PC.fTabOn, 9f) else null
                contentDescription = "$text ${counts[i]}"
                setOnClickListener { if (kind != id) ps.replaceQuery("?people=$id") }
                Ui.pressable(this, .97f)
            }
            tabs.addView(t, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) leftMargin = Ui.dpi(4f) })
        }
    }

    private fun box(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = Tf.box(Color.WHITE, 16f, 1f, PC.fLine); clipToOutline = true }

    private fun render() {
        val ctx = context
        body.removeAllViews()
        val rows = lists[kind]
        if (rows == null && error.isEmpty()) {
            val f = FrameLayout(ctx); f.addView(TfLoader(ctx, 26f, PC.fBadge), FrameLayout.LayoutParams(Ui.dpi(26f), Ui.dpi(26f), Gravity.CENTER))
            body.addView(f, LinearLayout.LayoutParams(-1, Ui.dpi(98f)))
        }
        if (rows == null && error.isNotEmpty()) {
            val b = box().apply { setPadding(Ui.dpi(16f), Ui.dpi(22f), Ui.dpi(16f), Ui.dpi(22f)) }
            b.addView(PUi.para(ctx, error, 13.5f, PC.fSub, 19f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dpi(14f) })
            b.addView(PUi.btn(ctx, "Try again", "light", Lu.RefreshCw, 16f, 44f) { load(kind, false) }, LinearLayout.LayoutParams(-1, Ui.dpi(44f)))
            body.addView(b)
        }
        if (rows != null && rows.isEmpty()) {
            val b = box().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(Ui.dpi(16f), Ui.dpi(26f), Ui.dpi(16f), Ui.dpi(26f)) }
            val t = FrameLayout(ctx).apply { background = Tf.box(PC.fFace, 12f) }
            t.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.Users, PC.wine, 22f)) }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            b.addView(t, LinearLayout.LayoutParams(Ui.dpi(44f), Ui.dpi(44f)))
            val (a, sub) = if (kind == "followers") "No followers yet" to "When someone follows you, they will show here."
                else "You are not following anyone yet" to (if (suggestions.isNotEmpty()) "Follow someone from the list below to see them here." else "People you follow will show here.")
            b.addView(Tf.t(ctx, a, 15f, PC.fHead).lineH(20f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dpi(12f); bottomMargin = Ui.dpi(2f) })
            b.addView(PUi.para(ctx, sub, 13f, PC.fSub, 18f).apply { gravity = Gravity.CENTER })
            body.addView(b)
        }
        if (rows != null && rows.isNotEmpty()) {
            val b = box()
            rows.forEachIndexed { i, p -> b.addView(row(p, i == 0)) }
            body.addView(b)
        }
        if (suggestions.isNotEmpty()) {
            val sec = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            sec.addView(Tf.t(ctx, "People you can follow", 15f, PC.fHead).lineH(20f), LinearLayout.LayoutParams(-1, -2).apply { leftMargin = Ui.dpi(2f); rightMargin = Ui.dpi(2f) })
            sec.addView(PUi.para(ctx, "Follow someone and they show in your Following list.", 12.5f, PC.fSub, 17f),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dpi(2f); bottomMargin = Ui.dpi(10f); leftMargin = Ui.dpi(2f); rightMargin = Ui.dpi(2f) })
            val b = box()
            suggestions.forEachIndexed { i, p -> b.addView(row(p, i == 0)) }
            sec.addView(b)
            body.addView(sec, LinearLayout.LayoutParams(-1, -2).apply { if (body.childCount > 0) topMargin = Ui.dpi(20f) })
        }
    }

    /** one member: photo (+ online dot), name (+ tick), "Follows you · City", and the button */
    private fun row(p: JSONObject, first: Boolean): View {
        val ctx = context
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = Ui.dpi(64f); setPadding(Ui.dpi(12f), 0, Ui.dpi(6f), 0) }
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (!first) wrap.addView(View(ctx).apply { setBackgroundColor(PC.fLine) }, LinearLayout.LayoutParams(-1, Ui.dpi(1f)))
        wrap.addView(r, LinearLayout.LayoutParams(-1, -2))
        val name = PR.s(p, "name")
        val faceBox = FrameLayout(ctx).apply { clipChildren = false }
        val ring = FrameLayout(ctx).apply { background = Tf.circle(PC.fFace, 1f, PC.fFaceLine); setPadding(Ui.dpi(1f), Ui.dpi(1f), Ui.dpi(1f), Ui.dpi(1f)) }
        val face = FacePic(ctx).apply { bg = PC.fFace }
        ring.addView(face, FrameLayout.LayoutParams(-1, -1))
        faceBox.addView(ring, FrameLayout.LayoutParams(Ui.dpi(44f), Ui.dpi(44f)))
        face.post { face.setPhoto(listOf(PR.s(p, "photo"))) }
        if (PR.truthy(p.opt("online"))) faceBox.addView(View(ctx).apply { background = Tf.circle(PC.green, 2f, Color.WHITE); contentDescription = "Online" },
            FrameLayout.LayoutParams(Ui.dpi(13f), Ui.dpi(13f), Gravity.END or Gravity.BOTTOM).apply { rightMargin = -Ui.dpi(1f); bottomMargin = -Ui.dpi(1f) })
        r.addView(faceBox, LinearLayout.LayoutParams(Ui.dpi(44f), Ui.dpi(44f)).apply { rightMargin = Ui.dpi(12f) })
        val mid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val nr = ShrinkRow(ctx, Ui.dpi(5f))
        nr.addView(Tf.t(ctx, name, 14.5f, PC.fHead).lineH(19f).ell())
        if (PR.truthy(p.opt("verified"))) nr.addView(ImageView(ctx).apply { setImageDrawable(PUi.lu(Lu.BadgeCheck, Color.WHITE, 16f, 2f, "#6D28D9")); contentDescription = "Verified"; layoutParams = ViewGroup.LayoutParams(Ui.dpi(16f), Ui.dpi(16f)) })
        mid.addView(nr, LinearLayout.LayoutParams(-2, -2))
        val place = listOf(PR.s(p, "city"), PR.s(p, "state")).filter { it.isNotEmpty() }.joinToString(", ")
        val iFollow = PR.truthy(p.opt("i_follow")); val followsMe = PR.truthy(p.opt("follows_me"))
        val tie = if (iFollow && followsMe) "You follow each other" else if (followsMe) "Follows you" else ""
        val sub = listOf(tie, place).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "True Find member" }
        mid.addView(Tf.t(ctx, sub, 12.5f, PC.fSub, false).lineH(16f).ell())
        r.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        val label = if (iFollow) "Following" else if (followsMe) "Follow back" else "Follow"
        val uid = PR.s(p, "user_id")
        val btnBox = FrameLayout(ctx).apply { setPadding(Ui.dpi(6f), 0, Ui.dpi(6f), 0); contentDescription = "$label $name" }
        val pill = FrameLayout(ctx).apply { background = Tf.box(if (iFollow) PC.fBtnOn else PC.fHead, 10f); minimumWidth = Ui.dpi(96f); setPadding(Ui.dpi(12f), 0, Ui.dpi(12f), 0) }
        if (busyId == uid) pill.addView(TfLoader(ctx, 15f, if (iFollow) PC.fHead else Color.WHITE), FrameLayout.LayoutParams(Ui.dpi(15f), Ui.dpi(15f), Gravity.CENTER))
        else pill.addView(Tf.t(ctx, label, 13.5f, if (iFollow) PC.fHead else Color.WHITE).apply {
            gravity = Gravity.CENTER; maxLines = 1
            if (iFollow) { compoundDrawablePadding = Ui.dpi(5f); setCompoundDrawablesWithIntrinsicBounds(PUi.lu(Lu.Check, PC.fHead, 14f, 2.6f), null, null, null) }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        btnBox.addView(pill, FrameLayout.LayoutParams(-2, Ui.dpi(34f), Gravity.CENTER_VERTICAL))
        btnBox.setOnClickListener { if (busyId.isEmpty()) press(p) }
        Ui.pressable(btnBox, .97f)
        r.addView(btnBox, LinearLayout.LayoutParams(-2, Ui.dpi(44f)))
        return wrap
    }

    private fun press(p: JSONObject) {
        if (PR.truthy(p.opt("i_follow"))) {
            val name = PR.s(p, "name")
            PConfirm(ps.pages.layer, "Unfollow $name?", "They will be removed from your Following list. You can follow them again anytime.", "Unfollow",
                danger = true, dark = true, onConfirm = { box -> box.close(); setFollow(p, false) })
        } else setFollow(p, true)
    }

    private fun follow(payload: JSONObject, done: (JSONObject?, Api.Result) -> Unit) {
        Api.invoke("follow", payload) { r -> done(if (r.ok && !PR.truthy(r.data.opt("error"))) r.data else null, r) }
    }

    private fun load(k: String, quiet: Boolean, attempt: Int = 0) {
        if (!quiet) { error = ""; if (lists[k] == null) render() }
        follow(JSONObject().put("action", "list").put("kind", k)) { d, r ->
            if (!alive) return@follow
            if (d == null) {
                // a busy moment on the server: quietly ask again (twice) before showing "Try again"
                val st = r.status
                if (st != 400 && st != 401 && st != 403 && attempt < 2) { postDelayed({ if (alive) load(k, quiet, attempt + 1) }, 1200L * (attempt + 1)); return@follow }
                if (!quiet) {
                    val why = PR.s(r.data, "detail").take(160)
                    val code = if (r.ok) PR.s(r.data, "error").take(60) else if (st > 0) "code $st${if (why.isNotEmpty()) ": $why" else ""}" else "Network Error"
                    error = PR.errText(r, "Could not load this list. Please check your internet and try again.") + (if (code.isNotEmpty()) " ($code)" else "")
                    if (k == kind) render()
                }
                return@follow
            }
            lists[k] = Tf.arr(d.optJSONArray("people"))
            suggestions = Tf.arr(d.optJSONArray("suggestions"))
            ps.setCounts(PR.firstNum(d, listOf("followers"))?.toLong() ?: 0, PR.firstNum(d, listOf("following"))?.toLong() ?: 0)
            if (k == kind) render()
        }
    }

    private fun setFollow(p: JSONObject, on: Boolean) {
        if (busyId.isNotEmpty()) return
        val uid = PR.s(p, "user_id")
        busyId = uid; render()
        follow(JSONObject().put("action", "set").put("user_id", uid).put("on", on)) { d, r ->
            if (!alive) return@follow
            busyId = ""
            if (d == null) { render(); ps.say(PR.errText(r, "Could not update. Please try again."), true); return@follow }
            ps.setCounts(PR.firstNum(d, listOf("followers"))?.toLong() ?: 0, PR.firstNum(d, listOf("following"))?.toLong() ?: 0)
            for (key in listOf("followers", "following")) lists[key] = lists[key]?.map { x -> if (PR.s(x, "user_id") == uid) JSONObject(x.toString()).put("i_follow", on) else x }
            if (on) suggestions = suggestions.filter { PR.s(it, "user_id") != uid }
            render()
            val name = PR.s(p, "name")
            ps.say(if (on) "You follow $name now." else "You unfollowed $name.")
            load(kind, true)
        }
    }

    fun previewOpen(what: String) { if (what == "unfollow") (lists[kind] ?: emptyList()).firstOrNull { PR.truthy(it.opt("i_follow")) }?.let { press(it) } }
}

/** a span with its own typeface (the count in the tabs is not bold) */
class TfTypefaceSpan(private val tf: android.graphics.Typeface) : android.text.style.MetricAffectingSpan() {
    override fun updateDrawState(p: android.text.TextPaint) { p.typeface = tf }
    override fun updateMeasureState(p: android.text.TextPaint) { p.typeface = tf }
}
