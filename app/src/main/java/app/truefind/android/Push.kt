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

    /** true while the app is on screen (MainActivity onResume … onPause) */
    @Volatile var visible = false
    /** the web page / app hears about a new token (MainActivity sets it) */
    @Volatile var onToken: ((String) -> Unit)? = null
    /** a message arrived while the app is on screen (its kind: msg, like, info …): the app shows it itself */
    @Volatile var onMessage: ((String) -> Unit)? = null
    private val pool = Executors.newSingleThreadExecutor()

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
        onToken?.invoke(t)
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

    /** one message from the server (FCM data message, all values text) */
    fun show(ctx: Context, d: Map<String, String>) {
        channels(ctx)
        val t = d["t"].orEmpty()
        // the app is on screen: it rings / shows calls and messages itself (offers and tests still show)
        if (visible && t != "test" && t != "offer") { onMessage?.invoke(t); return }
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val title = d["title"].orEmpty().ifEmpty { "True Find" }
        val body = d["body"].orEmpty()
        val callId = d["call_id"].orEmpty()
        val url = d["url"].orEmpty().takeIf { it.startsWith("/") } ?: "/"
        val big = photo(d["icon"].orEmpty())
        val req = (d["tag"].orEmpty().ifEmpty { callId.ifEmpty { "$t${System.currentTimeMillis()}" } }).hashCode()
        try {
            if (t == "call" && callId.isNotEmpty()) {
                val video = d["kind"] == "video"
                val decline = PendingIntent.getBroadcast(ctx, req + 1, Intent(ctx, DeclineReceiver::class.java).apply {
                    action = "app.truefind.android.DECLINE.$callId"; putExtra(EXTRA_CALL, callId)
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val n = NotificationCompat.Builder(ctx, CH_CALL)
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
                    .addAction(0, "Decline", decline)
                    .addAction(0, "Answer", open(ctx, req + 3, "/", callId, "answer"))
                    .build()
                n.flags = n.flags or android.app.Notification.FLAG_INSISTENT
                nm.notify(callTag(callId), 1, n)
                return
            }
            // "missed call": the ringing one goes first
            if (t == "missed" && callId.isNotEmpty()) nm.cancel(callTag(callId), 1)
            val ch = if (t == "msg" || t == "missed") CH_MSG else CH_INFO
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
                .build()
            nm.notify(d["tag"].orEmpty().ifEmpty { "tf-$t" }, if (d["tag"].isNullOrEmpty()) req else 2, n)
        } catch (e: SecurityException) { /* notifications not allowed */ }
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
        Push.show(applicationContext, d)
    }
}

class DeclineReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(Push.EXTRA_CALL).orEmpty()
        if (id.isEmpty()) return
        val pending = goAsync()
        Push.decline(ctx.applicationContext, id) { pending.finish() }
    }
}
