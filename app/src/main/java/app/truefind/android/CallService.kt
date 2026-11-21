package app.truefind.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * "Call in progress" (launch list #18): while a call is on, this service keeps its microphone — and on a video
 * call the camera — working when True Find leaves the screen (the Home button, another app, the screen locked).
 * Android stops both for an app in the background otherwise, so the other person would hear silence while the
 * call goes on (Agora's advice for this: a foreground service of type microphone / camera).
 *
 * It shows one quiet notification ("Voice call in progress · Tap to return to your call"); a tap opens the call
 * screen again. It never rings or buzzes, and it goes away as soon as the call is over.
 *
 *  - CallSession holds it ([hold]) while my call rings (once the microphone is allowed) and while it runs, and
 *    lets go ([release]) when the call is over or its screen closes. Real calls only. An Expert Talk call
 *    (EtCallPage) holds it while it is live.
 *  - Android lets an app start such a service only while the app is on the screen. Calls start on the screen;
 *    if the phone still says no (True Find was already away), the call goes on as before and [refresh] tries
 *    again the next time the app comes back (MainActivity.onResume).
 *  - It is started with startService (not startForegroundService): if the phone refuses the foreground step,
 *    nothing crashes — the service just stops.
 *
 * Everything here runs on the main thread (CallSession, MainActivity and the service itself).
 */
class CallService : Service() {
    private class Hold(val title: String, val text: String, val path: String, val camera: Boolean, val since: Long)

    companion object {
        private const val CHANNEL = "tf_ongoing_call"
        private const val NOTE_ID = 7301
        /** who needs the service now (a call: "call:<id>"), the newest last */
        private val holds = LinkedHashMap<String, Hold>()

        /**
         * [tag] (a call: "call:<id>", Expert Talk: "expert:<booking>") needs the service. [path]: the page its
         * notification opens ("" = the app just comes back where it was). [camera]: a video
         * call (the camera is kept too, when it is allowed). [since]: when the call started, so the notification's
         * clock shows the same time as the call screen; 0 = no clock (the call still rings).
         */
        fun hold(ctx: Context, tag: String, title: String, text: String, path: String, camera: Boolean, since: Long) {
            holds.remove(tag)
            holds[tag] = Hold(title, text, path, camera, since)
            send(ctx)
        }

        /** [tag] is done; the service stops when nobody needs it any more */
        fun release(ctx: Context, tag: String) {
            if (holds.remove(tag) == null) return
            send(ctx)
        }

        /** True Find is on the screen again: a start the phone refused while the app was away is tried again */
        fun refresh(ctx: Context) {
            if (holds.isNotEmpty()) send(ctx)
        }

        private fun send(ctx: Context) {
            val app = ctx.applicationContext
            val i = Intent(app, CallService::class.java)
            try {
                if (holds.isEmpty()) app.stopService(i) else app.startService(i)
            } catch (t: Throwable) {
                // True Find is in the background with no service running yet: refresh() starts it when the app is back
            }
        }
    }

    /** the type the service runs with now; -1 = not in the foreground (yet) */
    private var running = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val h = holds.values.lastOrNull()
        if (h == null) { stopSelf(); return START_NOT_STICKY }
        val note = note(h)
        val want = typeFor(h)
        if (want == 0 || !enter(note, want)) {
            // the phone said no (no microphone allowed, True Find already off the screen, the camera asked for from
            // the background …): a service that already runs keeps its type and shows the new words; else it stops
            if (running < 0 || !enter(note, running)) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = -1
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (t: Throwable) { }
        super.onDestroy()
    }

    private fun allowed(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** the microphone (when allowed) + on a video call the camera (when allowed); 0 = nothing to keep */
    @SuppressLint("InlinedApi")
    private fun typeFor(h: Hold): Int {
        var t = 0
        if (allowed(Manifest.permission.RECORD_AUDIO)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (h.camera && allowed(Manifest.permission.CAMERA)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        return t
    }

    /** into the foreground with [type] (Android 11+; older phones take the types from the manifest); false = the phone said no */
    private fun enter(note: Notification, type: Int): Boolean = try {
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTE_ID, note, type) else startForeground(NOTE_ID, note)
        running = type
        true
    } catch (t: Throwable) {
        false
    }

    private fun note(h: Hold): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ongoing call", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while you are on a call, so the call keeps its sound when you leave True Find"
                    setShowBadge(false)
                })
            }
        }
        // a tap: the app comes back — on the call screen when there is a page to open (MainActivity.pushOpen →
        // /call/<id>), else just where it was (Expert Talk: its call page is still open)
        val open = PendingIntent.getActivity(this, NOTE_ID, Intent(this, MainActivity::class.java).apply {
            action = "app.truefind.android.ONGOING_CALL"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (h.path.isNotEmpty()) putExtra(Push.EXTRA_URL, h.path)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tf)
            .setContentTitle(h.title)
            .setContentText(h.text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (h.since > 0) b.setWhen(h.since).setShowWhen(true).setUsesChronometer(true) else b.setShowWhen(false)
        return b.build()
    }
}
