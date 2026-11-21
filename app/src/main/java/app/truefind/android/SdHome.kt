package app.truefind.android

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Shader
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs

/**
 * Secret Dating Home in the look of the owner's photo 1 (20 Nov night): one photo filling the screen, up under the
 * phone's status bar · "Online" · the name and age, big · up to three of their interests · their city · four
 * see-through round buttons: the last profile again · Send Request (chat) · skip · like (the maroon heart).
 * Swipe left / right for the next / last person; tap the photo for the full profile.
 * The same people and the same rules as before (who is online now, the other gender); every lock and plan check
 * stays SecretHome's own (askRequest, the like on the profile page).
 */
@SuppressLint("ViewConstructor")
class SdHomeView(private val h: SecretHome) : FrameLayout(h.context) {
    private var people: List<JSONObject> = emptyList()
    private var idx = 0
    private val faces = arrayOf(Face(), Face())
    private var cur = 0
    private val card = Pager()
    private val topShade = Shade(top = true)
    private val empty = vbox(context, 0f, Gravity.CENTER_HORIZONTAL)
    private val loadingDots = LinearLayout(context)

    // the top: my photo (my profile) · credits (the wallet) · the bell (My Activity)
    private val meBtn = FrameLayout(context)
    private val mePhoto = RoundPhoto(context, 46f)
    private val pill = LinearLayout(context)
    private val pillNum = jk(context, "", 13.5f, Color.WHITE, 700)
    private val bell = FrameLayout(context)
    private val bellDot = View(context)
    private val banners = vbox(context, 8f)
    private val notice = jk(context, "", 13.5f, Ui.c("#166534"), 600)
    private val complete = LinearLayout(context)
    private val completeSub = jk(context, "", 12.5f, Color.argb(224, 255, 255, 255), 500)

    // the four round buttons
    private val buttons = LinearLayout(context)
    private val rewindBtn = FrameLayout(context)
    private val chatBtn = FrameLayout(context)
    private val skipBtn = FrameLayout(context)
    private val likeBtn = FrameLayout(context)
    private val likeIcon = ImageView(context)
    private val sentMark = ImageView(context)
    private var liking = false

    private var join: View? = null

    init {
        setBackgroundColor(SD.NAV_BG)
        clipChildren = false
        // the card: the photo to the screen's top edge, rounded at the bottom (30)
        card.setBackgroundColor(Ui.c("#1C1C21"))
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { val r = Ui.dp(30f); o.setRoundRect(0, -r.toInt(), v.width, v.height, r) }
        }
        card.clipToOutline = true
        addView(card, LayoutParams(-1, -1))
        for (f in faces) card.addView(f, LayoutParams(-1, -1))
        faces[1].visibility = INVISIBLE
        card.addView(topShade, LayoutParams(-1, -1))
        topShade.translationZ = 3f

        // no one online / still loading
        empty.visibility = GONE
        empty.addView(sdIconView(context, SdIcon(listOf(SdPaths.HEART to Color.WHITE), 34f, 1.8f)), lp(dpi(34f), dpi(34f)))
        empty.addView(jk(context, "No one online right now", 22f, Color.WHITE, 700).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP, t = 14f))
        empty.addView(jk(context, "Members appear here the moment they come online.", 14.5f, Color.argb(214, 255, 255, 255), 500).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) },
            lp(MATCH, WRAP, t = 6f))
        card.addView(empty, LayoutParams(-1, -2, Gravity.CENTER).apply { leftMargin = dpi(32f); rightMargin = dpi(32f) })
        loadingDots.orientation = LinearLayout.HORIZONTAL; loadingDots.gravity = Gravity.CENTER
        for (i in 0 until 3) loadingDots.addView(View(context).apply { background = oval(Color.argb(200, 255, 255, 255)) }, lp(dpi(8f), dpi(8f), l = if (i == 0) 0f else 7f))
        card.addView(loadingDots, LayoutParams(-2, dpi(10f), Gravity.CENTER))

        // top (clear of the status bar): my profile · credits · bell
        val over = InsetFrame(context)
        addView(over, LayoutParams(-1, -1))
        val top = dpi(6f)
        meBtn.background = SD.glass(dark = true)
        meBtn.setPadding(dpi(3f), dpi(3f), dpi(3f), dpi(3f))
        meBtn.addView(mePhoto, LayoutParams(-1, -1))
        meBtn.contentDescription = "My profile"
        meBtn.press().onTap { h.openMyProfile(false) }
        over.addView(meBtn, LayoutParams(dpi(52f), dpi(52f)).apply { leftMargin = dpi(16f); topMargin = top })
        pill.orientation = LinearLayout.HORIZONTAL; pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = SD.glass(dark = true)
        pill.setPadding(dpi(9f), 0, dpi(5f), 0)
        pill.addView(ImageView(context).apply { setImageDrawable(Coins(20f)) }, lp(dpi(20f), dpi(20f)))
        pill.addView(pillNum, lp(WRAP, WRAP, l = 6f))
        pill.addView(sdIconView(context, SdIcon(listOf(SdPaths.PLUS to SD.M), 14f, 3f)).apply { background = oval(Color.WHITE); setPadding(dpi(6f), dpi(6f), dpi(6f), dpi(6f)) },
            lp(dpi(26f), dpi(26f), l = 6f))
        pill.press().onTap { h.wallet() }
        over.addView(pill, LayoutParams(-2, dpi(38f), Gravity.END).apply { rightMargin = dpi(76f); topMargin = top + dpi(7f) })
        bell.background = SD.glass(dark = true)
        bell.addView(sdIconView(context, SdIcon(listOf(SdPaths.BELL to Color.WHITE), 24f, 1.8f)), LayoutParams(dpi(24f), dpi(24f), Gravity.CENTER))
        bellDot.background = oval(Ui.c("#FF3B5C"), 2f, Color.WHITE)
        bellDot.visibility = GONE
        bell.addView(bellDot, LayoutParams(dpi(11f), dpi(11f), Gravity.END).apply { topMargin = dpi(12f); rightMargin = dpi(13f) })
        bell.press().onTap { h.goLikes() }
        over.addView(bell, LayoutParams(dpi(52f), dpi(52f), Gravity.END).apply { rightMargin = dpi(16f); topMargin = top })

        // under the top: the payment notice, "Complete your profile"
        notice.background = rounded(Ui.c("#F0FDF4"), 16f, 1f, Color.argb(89, 22, 163, 74))
        notice.setPadding(dpi(14f), dpi(11f), dpi(14f), dpi(11f)); notice.setLineSpacing(0f, 1.15f)
        notice.visibility = GONE
        notice.setOnClickListener { h.clearNotice() }
        banners.addView(notice, lp(MATCH, WRAP))
        complete.orientation = LinearLayout.HORIZONTAL; complete.gravity = Gravity.CENTER_VERTICAL
        complete.background = SD.glass(dark = true, radiusDp = 20f)
        complete.setPadding(dpi(10f), dpi(9f), dpi(10f), dpi(9f))
        complete.addView(sdIconView(context, Icon(Lucide.User, SD.ON_M, 20f, 2.2f)).apply { background = oval(SD.M) }, lp(dpi(38f), dpi(38f), r = 10f))
        val ct = vbox(context)
        ct.addView(jk(context, "Complete your profile", 14.5f, Color.WHITE, 700))
        ct.addView(completeSub, lp(MATCH, WRAP, t = 2f))
        complete.addView(ct, lp(0, WRAP, 1f))
        complete.addView(sdIconView(context, Icon(Lucide.ChevronRight, Color.WHITE, 20f, 2.4f)), lp(dpi(20f), dpi(20f), l = 8f))
        complete.contentDescription = "Complete your profile"
        complete.press().onTap { h.openMyProfile(true) }
        complete.visibility = GONE
        banners.addView(complete, lp(MATCH, WRAP))
        over.addView(banners, LayoutParams(-1, -2).apply { leftMargin = dpi(16f); rightMargin = dpi(16f); topMargin = top + dpi(64f) })

        // the four buttons
        buttons.orientation = LinearLayout.HORIZONTAL; buttons.gravity = Gravity.CENTER
        roundBtn(rewindBtn, SdIcon(listOf(SdPaths.REWIND to Color.WHITE), 28f, 1.7f), "The last profile again") { back() }
        roundBtn(chatBtn, SdIcon(listOf(SdPaths.CHATS to Color.WHITE), 28f, 1.7f), "Send Request") { now()?.let { h.askRequest(it) } }
        roundBtn(skipBtn, SdIcon(listOf(SdPaths.XSQ to Color.WHITE), 28f, 1.7f), "Skip") { if (anim?.isRunning != true) fly(-1) }
        likeBtn.background = oval(SD.M)
        likeBtn.elevation = Ui.dp(8f)
        likeBtn.outlineSpotShadowColorCompat(Color.argb(150, 160, 110, 0))
        likeIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        likeBtn.addView(likeIcon, LayoutParams(dpi(30f), dpi(30f), Gravity.CENTER))
        likeBtn.press().onTap { likeTap() }
        sentMark.setImageDrawable(SdIcon(listOf(SdPaths.CHECK to SD.ON_M), 14f, 3f))
        sentMark.background = oval(Color.WHITE); sentMark.setPadding(dpi(3f), dpi(3f), dpi(3f), dpi(3f)); sentMark.visibility = GONE
        chatBtn.addView(sentMark, LayoutParams(dpi(22f), dpi(22f), Gravity.END or Gravity.TOP))
        for ((i, b) in listOf(rewindBtn, chatBtn, skipBtn, likeBtn).withIndex()) buttons.addView(b, lp(dpi(70f), dpi(70f), l = if (i == 0) 0f else 11f))
        addView(buttons, LayoutParams(-1, dpi(70f), Gravity.BOTTOM).apply { bottomMargin = dpi(35f) })
        paintLike(false)
    }

    private fun roundBtn(b: FrameLayout, icon: SdIcon, label: String, onClick: () -> Unit) {
        b.background = SD.glass(dark = false)
        b.addView(sdIconView(context, icon), LayoutParams(dpi(28f), dpi(28f), Gravity.CENTER))
        b.contentDescription = label
        b.press().onTap(onClick)
    }

    // ═════════════ data ═════════════
    private fun now(): JSONObject? = people.getOrNull(idx)
    private fun mod(k: Int) = if (people.isEmpty()) 0 else ((k % people.size) + people.size) % people.size

    /** the page draws again (new data, a request sent, a like) */
    fun render() {
        // a member who has not joined Secret Dating yet: the Join card, nothing else
        val needJoin = h.joinNeeded
        if (needJoin && join == null) {
            join = joinPage().also { addView(it, LayoutParams(-1, -1)) }
        } else if (!needJoin) join?.let { removeView(it); join = null }
        val list = h.deckNow
        val keep = now()?.optString("id")
        val changed = list.map { it.optString("id") } != people.map { it.optString("id") }
        people = list
        if (changed) {
            idx = keep?.let { k -> list.indexOfFirst { it.optString("id") == k } }?.takeIf { it >= 0 } ?: idx.coerceAtMost(maxOf(0, list.size - 1))
            settle()
            faces[cur].bind(now())
        } else faces[cur].bind(now(), force = false)
        val loading = h.deckLoading
        loadingDots.visibility = if (loading) VISIBLE else GONE
        empty.visibility = if (!loading && people.isEmpty()) VISIBLE else GONE
        val has = people.isNotEmpty()
        buttons.visibility = if (has) VISIBLE else GONE
        rewindBtn.alpha = if (people.size > 1) 1f else .45f
        skipBtn.alpha = if (people.size > 1) 1f else .45f
        // top
        mePhoto.set(listOf(h.myPhotoRaw))
        pillNum.text = SdRules.fmtCredits(h.creditsShown)
        pill.contentDescription = "${h.creditsShown ?: 0} request credits. Open Secret Dating wallet"
        val dot = h.hasNewActivity
        bellDot.visibility = if (dot) VISIBLE else GONE
        bell.contentDescription = if (dot) "My Activity, new" else "My Activity"
        // banners
        val n = h.noticeNow
        notice.text = n; notice.visibility = if (n.isNotEmpty()) VISIBLE else GONE
        val miss = h.missingNow
        complete.visibility = if (miss.isNotEmpty()) VISIBLE else GONE
        completeSub.text = "Add ${miss.joinToString(", ") { SdRules.NEED_LABEL[it] ?: it }} to unlock Premium features."
        paintNow()
    }

    /** the buttons for the person on screen: their like, a request already sent */
    private fun paintNow() {
        val p = now()
        paintLike(p?.optBoolean("liked_by_me") == true)
        val sent = p != null && h.inviteOf(p.optString("id")) == "sent"
        sentMark.visibility = if (sent) VISIBLE else GONE
        chatBtn.contentDescription = if (p != null && h.chatIdFor(p).isNotEmpty()) "Open chat" else if (sent) "Request sent" else "Send Request"
    }
    private fun paintLike(on: Boolean) {
        likeIcon.setImageDrawable(if (on) SdIcon(listOf(SdPaths.HEART_FILL to Color.WHITE), 30f) else SdIcon(listOf(SdPaths.HEART to Color.WHITE), 30f, 1.9f))
        likeBtn.contentDescription = if (on) "Liked. Tap to take back" else "Like"
    }

    /** the like, the same as the heart on their profile page (likeProfile; a demo profile only changes here) */
    private fun like() {
        val p = now() ?: return
        if (liking) return
        if (h.isSameGender(p)) return
        val next = !p.optBoolean("liked_by_me")
        if (next) HeartBurst.play(likeBtn, h.layer.root, likeIcon)
        p.put("liked_by_me", next); paintLike(next)
        if (SdDemo.isDemo(p)) { if (next) h.say("Demo profile: likes reach real members only"); return }
        liking = true
        Api.invoke("likeProfile", JSONObject().put("to_profile_id", p.optString("id")).put("app", "secret").put("action", if (next) "like" else "unlike")) { r ->
            liking = false
            if (r.ok && r.data.optString("error").isEmpty()) {
                h.liked(p.optString("id"), next)
                if (next) h.say(if (r.data.optBoolean("mutual")) "You both like each other" else "${SdRules.firstName(SdRules.s(p, "name")).ifEmpty { "They" }} will be notified")
            } else {
                p.put("liked_by_me", !next); if (now() === p) paintLike(!next)
                h.say(r.data.optString("message").ifEmpty { r.data.optString("error").ifEmpty { "Couldn't send the like. Try again." } }, "err")
            }
        }
    }

    // ═════════════ moving between people (owner, 21 Nov morning) ═════════════
    // Swipe right / the heart: a heart is stamped on the photo, they are liked and the card flies off to the right.
    // Swipe left / the cross: a cross is stamped on it and it flies off to the left. The next person waits underneath.
    // The rewind button brings the last person back in from the left.
    private var anim: ValueAnimator? = null
    private var dragDx = 0f
    private var under = -1          // the person bound under the top card (index), -1 = none
    val dragging get() = dragDx != 0f || anim?.isRunning == true

    /** faces back in place: the current one on top in the middle, the other hidden */
    private fun settle() {
        anim?.cancel(); anim = null
        dragDx = 0f; under = -1
        for (f in faces) { f.translationX = 0f; f.rotation = 0f; f.stamp(0f) }
        faces[cur].visibility = VISIBLE; faces[cur].translationZ = 1f
        faces[1 - cur].visibility = INVISIBLE; faces[1 - cur].translationZ = 0f
    }

    /** the top card [dx] off the middle, turned a little, with its stamp; the next person underneath */
    private fun place(dx: Float) {
        dragDx = dx
        val w = width.toFloat().coerceAtLeast(1f)
        val top = faces[cur]
        top.pivotX = w / 2f; top.pivotY = top.height * 1.15f
        top.translationX = dx; top.rotation = dx / w * 12f
        top.stamp(dx / w)
        if (people.size >= 2) {
            val want = mod(idx + 1)
            val f = faces[1 - cur]
            if (under != want || f.visibility != VISIBLE) { under = want; f.bind(people[want]); f.visibility = VISIBLE; f.translationX = 0f; f.rotation = 0f; f.stamp(0f) }
        }
    }

    /** +1: liked, off to the right · -1: skipped, off to the left · 0: back to the middle */
    private fun fly(dir: Int) {
        val w = width.toFloat().coerceAtLeast(1f)
        if (dir > 0) now()?.let { p -> if (!p.optBoolean("liked_by_me")) like() }
        val from = dragDx
        val to = if (dir == 0 || people.size < 2) 0f else dir * w * 1.45f
        anim?.cancel()
        val out = to != 0f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (PageFx.off(context)) 0 else if (out) 420 else if (dir != 0) 620 else 260
            interpolator = PathInterpolator(.3f, .6f, .3f, 1f)
            addUpdateListener { v ->
                val f = v.animatedValue as Float
                // one person only: the stamp shows, then the card comes back
                place(if (!out && dir != 0) dir * w * .3f * (if (f < .5f) f * 2 else (1 - f) * 2) else from + (to - from) * f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    if (anim !== a) return
                    if (out) { idx = mod(idx + 1); cur = 1 - cur }
                    anim = null
                    settle()
                    faces[cur].bind(now())
                    paintNow()
                }
            })
            start()
        }
    }

    /** the rewind button: the last person comes back in from the left */
    private fun back() {
        if (people.size < 2 || anim?.isRunning == true) return
        val w = width.toFloat().coerceAtLeast(1f)
        val f = faces[1 - cur]
        f.bind(people[mod(idx - 1)]); f.visibility = VISIBLE; f.translationZ = 2f; f.stamp(0f)
        f.pivotX = w / 2f; f.pivotY = f.height * 1.15f
        anim = ValueAnimator.ofFloat(-w * 1.45f, 0f).apply {
            duration = if (PageFx.off(context)) 0 else 380
            interpolator = PathInterpolator(.2f, .8f, .2f, 1f)
            addUpdateListener { v -> val x = v.animatedValue as Float; f.translationX = x; f.rotation = x / w * 12f }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    if (anim !== a) return
                    idx = mod(idx - 1); cur = 1 - cur; anim = null
                    settle(); faces[cur].bind(now()); paintNow()
                }
            })
            start()
        }
    }

    /** the heart button: like → stamp and off to the right; already liked → take the like back (as before) */
    private fun likeTap() {
        val p = now() ?: return
        if (anim?.isRunning == true) return
        if (p.optBoolean("liked_by_me") || h.isSameGender(p)) { like(); return }
        fly(1)
    }

    /** the photo: drag sideways to move, tap to open the profile */
    private inner class Pager : FrameLayout(this@SdHomeView.context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f; private var downY = 0f
        private var horizontal = false
        private var vt: VelocityTracker? = null
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; horizontal = false; vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e) }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dx = e.x - downX; val dy = e.y - downY
                    if (!horizontal && abs(dx) > slop && abs(dx) > abs(dy) * 1.2f && people.isNotEmpty()) { horizontal = true; parent?.requestDisallowInterceptTouchEvent(true); return true }
                }
            }
            return false
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            vt?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; return true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    if (!horizontal && abs(dx) > slop && abs(dx) > abs(e.y - downY) * 1.2f && people.isNotEmpty()) { horizontal = true; parent?.requestDisallowInterceptTouchEvent(true) }
                    if (horizontal) { anim?.cancel(); anim = null; place(dx) }
                }
                MotionEvent.ACTION_UP -> {
                    if (horizontal) {
                        vt?.computeCurrentVelocity(1000)
                        val v = vt?.xVelocity ?: 0f
                        val w = width.toFloat()
                        // far enough (or fast) to the right = like, to the left = skip; else it comes back
                        val dir = when {
                            dragDx > w * .25f || (v > Ui.dp(800f) && dragDx > 0) -> 1
                            dragDx < -w * .25f || (v < -Ui.dp(800f) && dragDx < 0) -> -1
                            else -> 0
                        }
                        if (dir != 0 && people.size < 2) { if (dir > 0) now()?.let { p -> if (!p.optBoolean("liked_by_me")) like() }; fly(0) } else fly(dir)
                    } else if (abs(e.x - downX) < slop && abs(e.y - downY) < slop) { performClick(); now()?.let { h.openProfile(it) } }
                    horizontal = false; vt?.recycle(); vt = null
                }
                MotionEvent.ACTION_CANCEL -> { if (horizontal) fly(0); horizontal = false; vt?.recycle(); vt = null }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }

    // ═════════════ one person: the photo and the words on it ═════════════
    private inner class Face : FrameLayout(this@SdHomeView.context) {
        val photo = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val info = vbox(context, 0f, Gravity.CENTER_HORIZONTAL)
        private val onlineRow = LinearLayout(context)
        private val onlineText = jk(context, "Online", 14f, Color.WHITE, 500)
        private val name = jk(context, "", 50f, Color.WHITE, 700).apply { maxLines = 1; gravity = Gravity.CENTER; letterSpacing = -0.024f }
        private val chips = LinearLayout(context)
        private val city = jk(context, "", 15f, Color.WHITE, 500).oneLine()
        private val cityRow = LinearLayout(context)
        private val heartStamp = stampView(SdIcon(listOf(SdPaths.HEART_FILL to SD.ON_M), 56f), SD.M)
        private val crossStamp = stampView(SdIcon(listOf(SdPaths.X to Color.WHITE), 56f, 3.2f), SD.RED)
        private var boundId = ""
        private var boundKey = ""

        init {
            addView(photo, LayoutParams(-1, -1))
            // the dark fade at the bottom goes under the words (not over them)
            addView(Shade(top = false), LayoutParams(-1, -1))
            onlineRow.orientation = LinearLayout.HORIZONTAL; onlineRow.gravity = Gravity.CENTER_VERTICAL
            onlineRow.addView(View(context).apply { background = oval(SD.ONLINE) }, lp(dpi(10f), dpi(10f), r = 7f))
            onlineRow.addView(onlineText)
            info.addView(onlineRow, lp(WRAP, dpi(20f)))
            info.addView(name, lp(MATCH, WRAP, t = 4f))
            chips.orientation = LinearLayout.HORIZONTAL; chips.gravity = Gravity.CENTER
            info.addView(chips, lp(MATCH, dpi(41f), t = 13f))
            cityRow.orientation = LinearLayout.HORIZONTAL; cityRow.gravity = Gravity.CENTER_VERTICAL
            cityRow.addView(sdIconView(context, SdIcon(listOf(SdPaths.MAP_PIN to Color.WHITE), 18f, 1.8f)), lp(dpi(18f), dpi(18f), r = 7f))
            cityRow.addView(city)
            info.addView(cityRow, lp(WRAP, dpi(22f), t = 12f))
            addView(info, LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = dpi(16f); rightMargin = dpi(16f); bottomMargin = dpi(130f) })
            for (st in listOf(heartStamp, crossStamp)) addView(st, LayoutParams(dpi(124f), dpi(124f), Gravity.CENTER).apply { bottomMargin = dpi(120f) })
        }
        private fun stampView(icon: SdIcon, bg: Int) = ImageView(context).apply {
            setImageDrawable(icon); scaleType = ImageView.ScaleType.CENTER
            background = oval(bg, 4f, Color.WHITE); elevation = Ui.dp(10f); alpha = 0f; visibility = INVISIBLE
        }
        /** how far the card is pushed (–1…1 of its width): the heart (right) or the cross (left) shows */
        fun stamp(f: Float) {
            val a = (abs(f) / .2f).coerceIn(0f, 1f)
            val on = if (f > 0) heartStamp else crossStamp
            val off = if (f > 0) crossStamp else heartStamp
            off.alpha = 0f; off.visibility = INVISIBLE
            on.alpha = a; on.visibility = if (a > 0f) VISIBLE else INVISIBLE
            val sc = .6f + .4f * a; on.scaleX = sc; on.scaleY = sc
            on.rotation = if (f > 0) -12f else 12f
        }

        fun bind(p: JSONObject?, force: Boolean = true) {
            val id = p?.optString("id").orEmpty()
            val key = if (p == null) "" else listOf(id, SdRules.s(p, "name"), SdRules.s(p, "age"), SdRules.cityOf(p), SdRules.s(p, "state"), p.optJSONArray("hobbies")?.toString().orEmpty(),
                SdRules.s(p, "photo", "profile_picture"), SdRules.liveOf(p).toString()).joinToString("|")
            if (!force && key == boundKey) return
            val newPhoto = id != boundId
            boundId = id; boundKey = key
            info.visibility = if (p == null) INVISIBLE else VISIBLE
            if (p == null) { photo.setImageDrawable(null); return }
            onlineRow.visibility = if (SdRules.liveOf(p)) VISIBLE else GONE
            // "Simran 22": the age a little lighter (as in the photo)
            val first = SdRules.firstName(SdRules.s(p, "name", "full_name"))
            val age = SdRules.ageOf(p)?.toString() ?: SdRules.s(p, "age")
            val sb = SpannableStringBuilder(first)
            if (age.isNotEmpty()) {
                sb.append(" "); val a = sb.length; sb.append(age)
                sb.setSpan(ForegroundColorSpan(Ui.c("#D7D3E2")), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(JkSpan(Jk.of(600)), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            name.text = sb
            name.contentDescription = "$first${if (age.isNotEmpty()) ", $age" else ""}"
            fitName()
            // up to three of their interests (their True Find profile); without them, their height / work
            chips.removeAllViews()
            for ((icon, t) in chipsOf(p)) chips.addView(chip(icon, t), lp(WRAP, dpi(41f), l = if (chips.childCount == 0) 0f else 8f))
            chips.visibility = if (chips.childCount > 0) VISIBLE else GONE
            val c = SdRules.cityOf(p); val st = SdRules.s(p, "state")
            city.text = c + if (st.isNotEmpty() && SdRules.s(p, "city").isNotEmpty() && st != SdRules.s(p, "city")) ", $st" else ""
            cityRow.visibility = if (c.isEmpty()) GONE else VISIBLE
            if (newPhoto) { photo.setImageBitmap(null); loadPhoto(p) }
        }

        private fun loadPhoto(p: JSONObject) {
            val w = (if (width > 0) width else resources.displayMetrics.widthPixels)
            val hh = (if (height > 0) height else resources.displayMetrics.heightPixels)
            val pid = p.optString("id")
            val demo = Demo.cover(p, w, hh)
            if (demo != null) { photo.setImageBitmap(demo); return }
            val raws = listOf(SdRules.s(p, "photo", "profile_picture"), SdRules.s(p, "photo_thumb")).filter { it.isNotBlank() }.distinct()
            if (raws.isEmpty()) return
            Photos.cachedCover(raws, w, hh)?.let { photo.setImageBitmap(it); return }
            Photos.loadCover(raws, w, hh) { b -> if (boundId == pid && b != null) photo.setImageBitmap(b) }
        }

        /** a long name gets smaller so it stays on one line */
        private fun fitName() {
            val avail = (resources.displayMetrics.widthPixels - dpi(32f)).toFloat()
            var size = 50f
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(size))
            while (size > 28f && name.paint.measureText(name.text.toString()) > avail) {
                size -= 2f; name.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(size))
            }
        }

        private fun chip(icon: String, t: String): View = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = SD.glass(dark = false, radiusDp = 21f)
            setPadding(dpi(14f), 0, dpi(16f), 0)
            addView(sdIconView(context, SdIcon(listOf(icon to Color.WHITE), 18f, 1.8f)), lp(dpi(18f), dpi(18f), r = 8f))
            addView(jk(context, t, 14.5f, Color.WHITE, 500).oneLine().apply { maxWidth = dpi(110f) })
        }
    }

    /** interests first (up to 3); a free member gets none from the server (hobbies are Premium on the map) → height / work */
    private fun chipsOf(p: JSONObject): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val arr = p.optJSONArray("interests") ?: p.optJSONArray("hobbies")
        if (arr != null) for (i in 0 until arr.length()) {
            val t = arr.optString(i).trim()
            if (t.isNotEmpty() && out.none { it.second.equals(t, true) }) out.add(iconFor(t) to t)
            if (out.size == 3) return out
        }
        SdRules.s(p, "height").takeIf { it.isNotEmpty() }?.let { if (out.size < 3) out.add(SdPaths.RULER to it) }
        SdRules.s(p, "profession", "job", "work", "occupation").takeIf { it.isNotEmpty() }?.let { if (out.size < 3) out.add(SdPaths.BRIEFCASE to it) }
        return out
    }
    private fun iconFor(t: String): String {
        val s = t.lowercase()
        return when {
            listOf("music", "song", "sing", "dance", "danc").any { it in s } -> SdPaths.HEADPHONES
            listOf("travel", "trip", "drive", "trek", "hik", "tour").any { it in s } -> SdPaths.PLANE
            listOf("movie", "film", "cinema", "series", "netflix", "show").any { it in s } -> SdPaths.CLAPPER
            else -> SdPaths.SPARKLE
        }
    }

    /** the two soft shades over the photo: a little at the top (for the buttons), more at the bottom (for the name) */
    private inner class Shade(private val top: Boolean) : View(this@SdHomeView.context) {
        private val p = Paint()
        private var made = ""
        override fun onDraw(c: Canvas) {
            // the top one: the status bar + 150 (the status bar is known only once the window has its insets)
            val hh = if (top) (SD.topInset + Ui.dp(150f)).toFloat() else height.toFloat()
            val key = "$hh"
            if (key != made) {
                made = key
                p.shader = if (top) LinearGradient(0f, 0f, 0f, hh, Color.argb(87, 30, 8, 18), Color.argb(0, 30, 8, 18), Shader.TileMode.CLAMP)
                else LinearGradient(0f, 0f, 0f, hh, intArrayOf(Color.argb(0, 24, 6, 14), Color.argb(0, 24, 6, 14), Color.argb(128, 24, 6, 14), Color.argb(224, 24, 6, 14)),
                    floatArrayOf(0f, .4f, .58f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, width.toFloat(), hh, p)
        }
    }

    /** not a member yet: the maroon top and the Join card on a white sheet */
    private fun joinPage(): View {
        val outer = FrameLayout(context).apply { setBackgroundColor(SD.HEAD); isClickable = true }
        outer.addView(MaroonDeco(context), LayoutParams(-1, -1))
        val page = InsetFrame(context)
        outer.addView(page, LayoutParams(-1, -1))
        page.addView(jk(context, "Secret Dating", 33f, Color.WHITE, 700), LayoutParams(-2, -2).apply { leftMargin = dpi(16f); topMargin = dpi(SD.TITLE_TOP) })
        page.addView(jk(context, "Serious singles only", 12.5f, Color.argb(204, 255, 255, 255), 600), LayoutParams(-2, -2).apply { leftMargin = dpi(16f); topMargin = dpi(SD.TITLE_TOP + 44f) })
        val sheet = FrameLayout(context).apply { background = SD.sheetBg() }
        sheet.addView(sdHandle(context))
        val sc = ScrollView(context).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        val col = vbox(context)
        col.setPadding(dpi(16f), dpi(28f), dpi(16f), dpi(24f))
        col.addView(JoinCard.build(context, "secret") { h.joined() }, lp(MATCH, WRAP))
        sc.addView(col, LayoutParams(-1, -2))
        sheet.addView(sc, LayoutParams(-1, -1))
        page.addView(sheet, LayoutParams(-1, -1).apply { topMargin = dpi(SD.TITLE_TOP + 80f) })
        return outer
    }

    /** test shots */
    fun preview(what: String) { when (what) { "next" -> fly(-1); "drag" -> place(width * .35f); "dragleft" -> place(-width * .35f) } }
}

/** a run of text in another weight of the photos' font (TypefaceSpan(Typeface) needs Android 9) */
class JkSpan(private val tf: android.graphics.Typeface) : android.text.style.MetricAffectingSpan() {
    override fun updateDrawState(tp: android.text.TextPaint) { tp.typeface = tf }
    override fun updateMeasureState(tp: android.text.TextPaint) { tp.typeface = tf }
}
