package app.truefind.android

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Calls the SAME Base44 backend functions the web app uses
 * (getOnlineNow, getWallet, startCall, follow, getAppConfig …).
 * Login is shared: the token and app id come from the Base44 page
 * running in the WebView (see WebBridge), so the user signs in once.
 */
object Api {
    @Volatile var origin: String = MainActivity.START_URL.trimEnd('/')
    @Volatile var appId: String = ""
    @Volatile var token: String = ""

    class Result(val status: Int, val data: JSONObject, val error: String?) {
        val ok get() = status in 200..299
    }

    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    val ready: Boolean get() = appId.isNotEmpty() && token.isNotEmpty()

    fun invoke(name: String, payload: JSONObject = JSONObject(), timeoutMs: Int = 30000, done: (Result) -> Unit) {
        pool.execute {
            val r = call(name, payload, timeoutMs)
            main.post { done(r) }
        }
    }

    private fun call(name: String, payload: JSONObject, timeoutMs: Int): Result {
        if (!ready) return Result(0, JSONObject(), "Not signed in yet")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$origin/api/apps/$appId/functions/$name").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-App-Id", appId)
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
            val err = if (code in 200..299) null else
                (json.optString("error").takeIf { it.isNotBlank() } ?: json.optString("message").takeIf { it.isNotBlank() }
                    ?: "Something went wrong. Please try again.")
            Result(code, json, err)
        } catch (e: Exception) {
            Result(0, JSONObject(), "Please check your internet and try again.")
        } finally {
            conn?.disconnect()
        }
    }
}
