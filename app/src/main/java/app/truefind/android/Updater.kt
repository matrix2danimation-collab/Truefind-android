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
    private val prefs get() = activity.getSharedPreferences("tf_updater", 0)
    private val token: String get() = prefs.getString("gh_token", "") ?: ""

    companion object {
        // the code repo is private: the app reads it with a read-only GitHub key
        // that the owner pastes once (kept only on this phone)
        const val API = "https://api.github.com/repos/matrix2danimation-collab/Truefind-android/releases/tags/test-build"
    }

    /** Called when the app comes to the screen; asks GitHub at most every 10 minutes. */
    fun check() {
        if (!BuildConfig.SELF_UPDATE || showing) return
        // came back from "Allow from this source": carry on with the install
        pendingApk?.let { if (canInstall()) { pendingApk = null; install(it); return } }
        val now = System.currentTimeMillis()
        if (now - lastCheck < 10 * 60 * 1000) return
        lastCheck = now
        if (pendingApk == null) dir.listFiles()?.forEach { it.delete() } // old downloads
        if (token.isEmpty()) { askToken(); return }
        thread {
            val rel = fetchJson(API, token)
            if (rel == null) { if (lastStatus == 401) main.post { prefs.edit().remove("gh_token").apply(); askToken("That key didn't work. Please paste it again.") }; return@thread }
            val assets = rel.optJSONArray("assets") ?: return@thread
            var infoUrl = ""; var apkUrl = ""
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name") == "version.json") infoUrl = a.optString("url")
                if (a.optString("name") == "TrueFind-test.apk") apkUrl = a.optString("url")
            }
            if (infoUrl.isEmpty() || apkUrl.isEmpty()) return@thread
            val info = fetchJson(infoUrl, token, asset = true) ?: return@thread
            if (info.optInt("versionCode", 0) > BuildConfig.VERSION_CODE) main.post { offer(apkUrl) }
        }
    }

    /** One time only: paste the read-only GitHub key. */
    private fun askToken(note: String? = null) {
        if (activity.isFinishing || showing) return
        showing = true
        val input = android.widget.EditText(activity).apply {
            hint = "github_pat_…"
            setSingleLine(true)
        }
        val pad = Ui.dpi(20f)
        val box = android.widget.FrameLayout(activity).apply { setPadding(pad, Ui.dpi(8f), pad, 0); addView(input) }
        AlertDialog.Builder(activity)
            .setTitle("Turn on app updates")
            .setMessage((note?.let { "$it\n\n" } ?: "") + "Paste your read-only GitHub key once. After that, new versions come with one tap on Update.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                showing = false
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) { prefs.edit().putString("gh_token", t).apply(); lastCheck = 0L; check() }
            }
            .setNegativeButton("Later") { _, _ -> showing = false }
            .setOnCancelListener { showing = false }
            .show()
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
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Accept", "application/octet-stream")
                conn.connectTimeout = 20000; conn.readTimeout = 60000
                // GitHub sends the file from another address: follow it
                var hops = 0
                while (conn.responseCode in 300..399 && hops < 5) {
                    val next = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    conn = URL(next).openConnection() as HttpURLConnection // the file address: no key sent there
                    conn.instanceFollowRedirects = false
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

    @Volatile private var lastStatus = 0

    private fun fetchJson(url: String, key: String, asset: Boolean = false): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000; readTimeout = 15000; useCaches = false
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Accept", if (asset) "application/octet-stream" else "application/vnd.github+json")
            }
            lastStatus = conn.responseCode
            if (conn.responseCode in 300..399) {
                val next = conn.getHeaderField("Location") ?: return null
                conn.disconnect()
                conn = (URL(next).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 15000; useCaches = false }
                lastStatus = conn.responseCode
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
