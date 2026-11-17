package app.truefind.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Test builds only: when GitHub has built a newer APK, the app says
 * "Update available" with an Update button. Tapping it downloads the new
 * version inside the app and opens Android's install screen — no file is
 * left in the phone's Downloads. (Not for the Play Store version: there the
 * Play Store updates the app.)
 */
class Updater(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private var lastCheck = 0L
    private var showing = false
    private var pendingApk: File? = null

    private val dir get() = File(activity.cacheDir, "updates")

    /** Called when the app comes to the screen; asks GitHub at most every 10 minutes. */
    fun check() {
        if (!BuildConfig.SELF_UPDATE || showing) return
        // came back from "Allow from this source": carry on with the install
        pendingApk?.let { if (canInstall()) { pendingApk = null; install(it); return } }
        val now = System.currentTimeMillis()
        if (now - lastCheck < 10 * 60 * 1000) return
        lastCheck = now
        if (pendingApk == null) dir.listFiles()?.forEach { it.delete() } // old downloads
        thread {
            val info = fetchJson(BuildConfig.UPDATE_INFO_URL) ?: return@thread
            val code = info.optInt("versionCode", 0)
            val url = info.optString("apk", "")
            if (code > BuildConfig.VERSION_CODE && url.startsWith("https://")) main.post { offer(url) }
        }
    }

    private fun offer(url: String) {
        if (activity.isFinishing || showing) return
        showing = true
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage("A new version of True Find is ready. Update now?")
            .setPositiveButton("Update") { _, _ -> showing = false; download(url) }
            .setNegativeButton("Later") { _, _ -> showing = false }
            .setOnCancelListener { showing = false }
            .show()
    }

    private fun download(url: String) {
        showing = true
        val box = AlertDialog.Builder(activity)
            .setTitle("Updating True Find")
            .setMessage("Downloading… 0%")
            .setCancelable(false)
            .show()
        thread {
            val file = File(dir, "TrueFind-update.apk")
            val ok = try {
                dir.mkdirs()
                var conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20000; conn.readTimeout = 60000
                // GitHub sends the file from another address: follow it
                var hops = 0
                while (conn.responseCode in 300..399 && hops < 5) {
                    val next = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    conn = URL(next).openConnection() as HttpURLConnection
                    conn.connectTimeout = 20000; conn.readTimeout = 60000
                    hops++
                }
                if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                var done = 0L
                var lastPct = -1
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; main.post { box.setMessage("Downloading… $pct%") } }
                            }
                        }
                    }
                }
                conn.disconnect()
                true
            } catch (e: Exception) {
                false
            }
            main.post {
                box.dismiss()
                showing = false
                if (ok) install(file)
                else AlertDialog.Builder(activity).setTitle("Update failed")
                    .setMessage("Please check your internet and try again.")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()

    private fun install(file: File) {
        if (!canInstall()) {
            // first time only: Android asks to allow installs from True Find
            pendingApk = file
            AlertDialog.Builder(activity)
                .setTitle("One-time permission")
                .setMessage("Android needs your OK to install updates from True Find. Turn on \"Allow from this source\", then come back.")
                .setPositiveButton("Open settings") { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                }
                .setNegativeButton("Later", null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { activity.startActivity(i) } catch (e: Exception) { }
    }

    private fun fetchJson(url: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$url?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000; readTimeout = 15000
                useCaches = false
            }
            if (conn.responseCode !in 200..299) null
            else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
