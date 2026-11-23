package app.truefind.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Notifications while True Find is closed — the same ones the website gets through web push
 * (public/sw.js), here through Firebase Cloud Messaging (base44/shared/fcm.ts sends them):
 *  - an incoming call RINGS: heads-up bar, full screen on the lock screen, the phone's ringtone on
 *    repeat, Answer / Decline (Decline works without opening the app)
 *  - missed call, new message, like, daily free coins, offer, test → a normal notification
 *  - tap → the right page opens in the app
 * While the app is on screen nothing pops up (the app rings / shows it itself), as on the website.
 *
 * Firebase is only switched on when the build has app/google-services.json (CI writes it from the
 * GOOGLE_SERVICES_JSON secret). Without it the app works exactly as before, just without these.
 * The web page gets the phone's push token through window.TrueFindNative (lib/push.js).
 */
object Push {
    const val NAME = "TrueFindNative"
    const val EXTRA_URL = "tf_push_url"
    const val EXTRA_CALL = "tf_push_call"
    const val EXTRA_ACT = "tf_push_act"
    private const val CH_CALL = "tf_calls"
    private const val CH_MSG = "tf_messages"
    private const val CH_INFO = "tf_info"
    private const val PREFS = "tf_push"
    private const val RING_MS = 45000L
    /**
     * Android 16 and newer group an app's ungrouped notifications themselves from the second one on
     * (their own "Aggregate" group) and mark every one of them SILENT: no sound, no pop-up, and for a
     * call no full screen — seen in the push test on Android 16 (23 Nov). So:
     *  - a call is a CallStyle notification, which Android never groups;
     *  - messages, missed calls and the rest go into True Find's own group (with a summary), which
     *    Android leaves alone, so each new one still pops up and sounds.
     */
    private const val GROUP = "tf_alerts"
    private const val SUMMARY_TAG = "tf-summary"
    private const val SUMMARY_ID = 9

    /** true while the app is on screen (MainActivity onResume … onPause) */
    @Volatile var visible = false
    /** the web page / app hears about a new token (MainActivity sets it) */
    @Volatile var onToken: ((String) -> Unit)? = null
    /** a message arrived while the app is on screen (its kind: msg, like, info …): the app shows it itself */
    @Volatile var onMessage: ((String) -> Unit)? = null
    /** a call rang while the app is on screen: the app checks for it at once (its live connection may have dropped) */
    @Volatile var onCall: ((String) -> Unit)? = null
    private val pool = Executors.newSingleThreadExecutor()
    private val io = Executors.newCachedThreadPool()
    /** the caller's photo may hold up the ringing for at most this long (a slow network must never delay a call) */
    private const val PHOTO_WAIT_MS = 1500L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ready(ctx: Context): Boolean = try { FirebaseApp.getApps(ctx).isNotEmpty() } catch (e: Throwable) { false }

    fun token(ctx: Context): String = prefs(ctx).getString("token", "").orEmpty()

    /** at app start: ask Firebase for this phone's token (it changes rarely) */
    fun init(ctx: Context) {
        channels(ctx)
        if (!ready(ctx)) return
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { t -> if (!t.isNullOrEmpty()) saveToken(ctx, t) }
        } catch (e: Throwable) { }
    }

    fun saveToken(ctx: Context, t: String) {
        if (t == token(ctx)) return
        prefs(ctx).edit().putString("token", t).remove("sent").apply()
        val app = onToken
        if (app != null) app.invoke(t) else registerClosed(ctx, t)
    }

    /**
     * Firebase gave this phone a new token while True Find was closed: the server learns it now
     * (with the sign-in kept for Decline), not only the next time the app is opened — until then
     * calls would go to the old token and never ring.
     */
    private fun registerClosed(ctx: Context, t: String) {
        val auth = try { JSONObject(prefs(ctx).getString("auth", "").orEmpty()) } catch (e: Exception) { null } ?: return
        val tok = auth.optString("t")
        if (tok.isEmpty()) return
        pool.execute {
            var c: HttpURLConnection? = null
            try {
                c = (URL("${auth.optString("o")}/api/apps/${auth.optString("a")}/functions/pushSubscribe").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 8000; readTimeout = 8000; doOutput = true
                    setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-App-Id", auth.optString("a")); setRequestProperty("Authorization", "Bearer $tok")
                }
                c.outputStream.use { it.write(JSONObject().put("action", "native_save").put("token", t).put("device", device()).toString().toByteArray()) }
                if (c.responseCode in 200..299) {
                    val mark = "${tok.takeLast(24)}|$t|${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}"
                    prefs(ctx).edit().putString("sent", mark).apply()
                }
            } catch (e: Throwable) { } finally { c?.disconnect() }
        }
    }

    /** signed in: the server learns this phone's token (once a day is enough, as the web does) */
    fun register(ctx: Context, userKey: String) {
        val t = token(ctx)
        if (t.isEmpty() || userKey.isEmpty() || !Api.ready) return
        val mark = "$userKey|$t|${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}"
        if (prefs(ctx).getString("sent", "") == mark) return
        Api.invoke("pushSubscribe", JSONObject().put("action", "native_save").put("token", t).put("device", device())) { r ->
            if (r.ok) prefs(ctx).edit().putString("sent", mark).apply()
        }
    }

    /** log out: this phone stops getting this account's notifications */
    fun unregister(ctx: Context) {
        val t = token(ctx)
        prefs(ctx).edit().remove("sent").remove("auth").apply()
        if (t.isNotEmpty() && Api.ready) Api.invoke("pushSubscribe", JSONObject().put("action", "native_remove").put("token", t), 1500) { }
    }

    /** the sign-in the Decline button uses when the app is closed (kept on this phone only) */
    fun keepAuth(ctx: Context, origin: String, appId: String, tok: String) {
        val p = prefs(ctx)
        val v = if (tok.isEmpty() || appId.isEmpty()) "" else JSONObject().put("o", origin).put("a", appId).put("t", tok).toString()
        if (p.getString("auth", "") != v) p.edit().putString("auth", v).apply()
    }

    fun device(): String = "True Find Android · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}".take(160)

    /** window.TrueFindNative for lib/push.js */
    class Bridge(private val ctx: Context) {
        @JavascriptInterface fun getFcmToken(): String = token(ctx)
        @JavascriptInterface fun deviceInfo(): String = device()
    }

    // ═════════════════ the notifications ═════════════════
    private fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH_CALL) == null) nm.createNotificationChannel(NotificationChannel(CH_CALL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Voice and video calls ring like a phone call"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true); vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500, 250, 500)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
        if (nm.getNotificationChannel(CH_MSG) == null) nm.createNotificationChannel(NotificationChannel(CH_MSG, "Messages and missed calls", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); vibrationPattern = longArrayOf(0, 120, 80, 120)
        })
        if (nm.getNotificationChannel(CH_INFO) == null) nm.createNotificationChannel(NotificationChannel(CH_INFO, "Coins, likes and offers", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun open(ctx: Context, req: Int, url: String, call: String = "", act: String = ""): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            action = "app.truefind.android.PUSH.$req"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_URL, url); putExtra(EXTRA_CALL, call); putExtra(EXTRA_ACT, act)
        }
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun photo(url: String): Bitmap? {
        if (!url.startsWith("https://")) return null
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 3000; readTimeout = 3000 }
            c.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) { null } finally { c?.disconnect() }
    }

    private fun callTag(id: String) = "call-$id"

    /** the caller's photo, if it comes within PHOTO_WAIT_MS — else the call rings without it */
    private fun quickPhoto(url: String): Bitmap? {
        if (!url.startsWith("https://")) return null
        val f = io.submit<Bitmap?> { photo(url) }
        return try { f.get(PHOTO_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Throwable) { f.cancel(true); null }
    }

    /** one message from the server (FCM data message, all values text) */
    fun show(ctx: Context, d: Map<String, String>) {
        channels(ctx)
        val t = d["t"].orEmpty()
        // the app is on screen: it rings / shows calls and messages itself (offers and tests still show)
        if (visible && t != "test" && t != "offer") {
            android.util.Log.i("TFDBG", "push in app t=$t")
            if (t == "call") onCall?.invoke(d["call_id"].orEmpty()) else onMessage?.invoke(t)
            return
        }
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) { android.util.Log.i("TFDBG", "push blocked t=$t (notifications off)"); return }
        val title = d["title"].orEmpty().ifEmpty { "True Find" }
        val body = d["body"].orEmpty()
        val callId = d["call_id"].orEmpty()
        val url = d["url"].orEmpty().takeIf { it.startsWith("/") } ?: "/"
        // shown at once (Android's advice: a notification first, any network after) — only a call waits
        // a moment for the caller's photo, and never more than PHOTO_WAIT_MS
        val big = quickPhoto(d["icon"].orEmpty())
        val req = (d["tag"].orEmpty().ifEmpty { callId.ifEmpty { "$t${System.currentTimeMillis()}" } }).hashCode()
        try {
            if (t == "call" && callId.isNotEmpty()) {
                val video = d["kind"] == "video"
                val decline = PendingIntent.getBroadcast(ctx, req + 1, Intent(ctx, DeclineReceiver::class.java).apply {
                    action = "app.truefind.android.DECLINE.$callId"; putExtra(EXTRA_CALL, callId)
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val answer = open(ctx, req + 3, "/", callId, "answer")
                fun ringing(callStyle: Boolean): android.app.Notification {
                    val b = NotificationCompat.Builder(ctx, CH_CALL)
                        .setSmallIcon(R.drawable.ic_stat_tf)
                        .setContentTitle(title)
                        .setContentText(body.ifEmpty { "Incoming ${if (video) "video" else "voice"} call" })
                        .setLargeIcon(big)
                        .setCategory(NotificationCompat.CATEGORY_CALL)
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setOngoing(true).setAutoCancel(true)
                        .setTimeoutAfter(RING_MS)
                        .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), android.media.AudioManager.STREAM_RING)
                        .setVibrate(longArrayOf(0, 500, 250, 500, 250, 500, 250, 500))
                        .setContentIntent(open(ctx, req, "/", callId, ""))
                        .setFullScreenIntent(open(ctx, req + 2, "/", callId, ""), true)
                    if (callStyle) {
                        // the phone's own call look (Decline / Answer), and never grouped or silenced by Android 16+
                        // like WhatsApp: the caller's name big and bold, their photo — or, without one, the same
                        // drawn face the app shows for them (AvatarArt, by their profile id and gender)
                        val who = d["name"].orEmpty().ifEmpty { title.substringBefore(" is calling").ifEmpty { "True Find" } }
                        val face = big ?: try {
                            AvatarArt.forKey(d["pid"].orEmpty().ifEmpty { who }, d["gender"] != "male", 192)
                        } catch (e: Throwable) { null }
                        val caller = androidx.core.app.Person.Builder()
                            .setName(who)
                            .setImportant(true)
                            .apply { if (face != null) setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(face)) }
                            .build()
                        b.setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer).setIsVideo(video)).addPerson(caller)
                    } else {
                        b.addAction(0, "Decline", decline).addAction(0, "Answer", answer)
                    }
                    return b.build().also { it.flags = it.flags or android.app.Notification.FLAG_INSISTENT }
                }
                // CallStyle first; a phone that refuses it (an odd system) still gets the plain ringing one
                val style = try { nm.notify(callTag(callId), 1, ringing(true)); "call" } catch (e: SecurityException) { throw e } catch (e: Throwable) {
                    android.util.Log.i("TFDBG", "push callstyle refused: ${e.message}")
                    nm.notify(callTag(callId), 1, ringing(false)); "plain"
                }
                android.util.Log.i("TFDBG", "push shown t=call id=$callId kind=${d["kind"].orEmpty()} photo=${big != null} style=$style")
                return
            }
            // "missed call": the ringing one goes first
            if (t == "missed" && callId.isNotEmpty()) nm.cancel(callTag(callId), 1)
            val ch = if (t == "msg" || t == "missed") CH_MSG else CH_INFO
            val tag = d["tag"].orEmpty().ifEmpty { "tf-$t" }
            val id = if (d["tag"].isNullOrEmpty()) req else 2
            val n = NotificationCompat.Builder(ctx, ch)
                .setSmallIcon(R.drawable.ic_stat_tf)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setLargeIcon(big)
                .setAutoCancel(true)
                .setPriority(if (ch == CH_MSG) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(if (t == "msg") NotificationCompat.CATEGORY_MESSAGE else if (t == "missed") NotificationCompat.CATEGORY_MISSED_CALL else NotificationCompat.CATEGORY_RECOMMENDATION)
                .setContentIntent(open(ctx, req, url))
                .setGroup(GROUP)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setDeleteIntent(gone(ctx))
                .build()
            summary(ctx, nm)
            nm.notify(tag, id, n)
            android.util.Log.i("TFDBG", "push shown t=$t")
        } catch (e: SecurityException) { android.util.Log.i("TFDBG", "push refused t=$t ${e.message}") }
    }

    /** True Find's own group summary (posted before each of its notifications; it never sounds itself) */
    private fun summary(ctx: Context, nm: NotificationManagerCompat) {
        val n = NotificationCompat.Builder(ctx, CH_MSG)
            .setSmallIcon(R.drawable.ic_stat_tf)
            .setContentTitle("True Find")
            .setGroup(GROUP).setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open(ctx, SUMMARY_TAG.hashCode(), "/"))
            .build()
        nm.notify(SUMMARY_TAG, SUMMARY_ID, n)
    }

    private fun gone(ctx: Context): PendingIntent = PendingIntent.getBroadcast(ctx, 77, Intent(ctx, PushGoneReceiver::class.java).setAction("app.truefind.android.PUSH_GONE"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** the last notification of True Find's group went (swiped away, tapped): its summary goes too */
    fun tidy(ctx: Context) {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            val all = nm.activeNotifications
            val children = all.count { it.notification.group == GROUP && (it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) == 0 }
            if (children == 0 && all.any { it.tag == SUMMARY_TAG }) nm.cancel(SUMMARY_TAG, SUMMARY_ID)
        } catch (e: Throwable) { }
    }

    /** the app came on screen: its own ringer takes over, the ringing notifications go */
    fun clearCalls(ctx: Context) {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.activeNotifications.filter { it.tag?.startsWith("call-") == true }.forEach { nm.cancel(it.tag, it.id) }
        } catch (e: Throwable) { }
    }

    /** "Decline" on the ringing notification: answered without opening the app */
    fun decline(ctx: Context, callId: String, done: () -> Unit) {
        NotificationManagerCompat.from(ctx).cancel(callTag(callId), 1)
        val auth = try { JSONObject(prefs(ctx).getString("auth", "").orEmpty()) } catch (e: Exception) { null }
        if (auth == null) { done(); return }
        pool.execute {
            // tried twice: a Decline lost on a weak network would leave the caller ringing until the call times out
            try {
                for (attempt in 0 until 2) {
                    var c: HttpURLConnection? = null
                    val code = try {
                        c = (URL("${auth.optString("o")}/api/apps/${auth.optString("a")}/functions/respondCall").openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"; connectTimeout = 5000; readTimeout = 5000; doOutput = true
                            setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json")
                            setRequestProperty("X-App-Id", auth.optString("a")); setRequestProperty("Authorization", "Bearer ${auth.optString("t")}")
                        }
                        c.outputStream.use { it.write(JSONObject().put("call_id", callId).put("action", "decline").toString().toByteArray()) }
                        c.responseCode
                    } catch (e: Throwable) { -1 } finally { c?.disconnect() }
                    if (code in 200..499) break // done, or the call is already over
                    if (attempt == 0) try { Thread.sleep(1500) } catch (e: InterruptedException) { break }
                }
            } finally { done() }
        }
    }
}

/** Firebase hands the server's messages and new tokens here */
class TfMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { Push.saveToken(applicationContext, token) }
    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data ?: return
        if (d.isEmpty()) return
        android.util.Log.i("TFDBG", "push got t=${d["t"].orEmpty()} prio=${message.priority}")
        Push.show(applicationContext, d)
    }
}

/** one of True Find's notifications was swiped away: the group's summary goes when it was the last one */
class PushGoneReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { Push.tidy(ctx.applicationContext) }
}

class DeclineReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(Push.EXTRA_CALL).orEmpty()
        if (id.isEmpty()) return
        android.util.Log.i("TFDBG", "push decline id=$id")
        val pending = goAsync()
        Push.decline(ctx.applicationContext, id) { pending.finish() }
    }
}
