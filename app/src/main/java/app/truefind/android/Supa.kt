package app.truefind.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The server: Supabase (since 24 Nov 2026; before, Base44).
 *  - The app's functions and tables answer at [API]: the "api" Edge Function, which takes the same addresses
 *    Base44 did (…/api/apps/<id>/functions/<name>, …/entities/<Table>, …/integration-endpoints/Core/…), so every
 *    screen calls them as before ([Api.base]).
 *  - Signing in is Supabase Auth with a WhatsApp OTP ([Auth]): one mobile number = one account.
 *  - Web pages (policies, invite and share links) stay on the website, truefindapp.com ([Api.origin]).
 */
object Supa {
    const val URL = "https://ltffzgmnhjeqoguquovf.supabase.co"
    /** the publishable key: made to be inside the app (each table's rules decide what a member may read or change) */
    const val KEY = "sb_publishable_fQ6jdqIDFe9HrU5fbRWJqA_a9rQAPrn"
    /** the Edge Functions; the "api" function answers under $API/api/apps/<APP_ID>/… */
    const val API = "$URL/functions/v1"
    /** the app's id in those addresses (Base44 had a long one; the api function takes any) */
    const val APP_ID = "truefind"

    /** a request to Supabase Auth ([path] after /auth/v1/): status 0 = no internet */
    fun auth(method: String, path: String, body: JSONObject?, bearer: String = "", timeoutMs: Int = 20000): Api.Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$URL/auth/v1/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = timeoutMs
                setRequestProperty("apikey", KEY)
                setRequestProperty("Accept", "application/json")
                if (bearer.isNotEmpty()) setRequestProperty("Authorization", "Bearer $bearer")
                if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
            }
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try { if (text.trim().startsWith("{")) JSONObject(text) else JSONObject() } catch (e: Exception) { JSONObject() }
            if (code !in 200..299) android.util.Log.w("TFAuth", "$method $path → $code ${text.take(160)}")
            Api.Result(code, json, if (code in 200..299) null else authMessage(json))
        } catch (e: Exception) {
            Api.Result(0, JSONObject(), "Please check your internet and try again.")
        } finally { conn?.disconnect() }
    }

    /** Supabase Auth's own words for an error ({ msg } / { message } / { error_description }) */
    fun authMessage(j: JSONObject): String =
        j.optString("msg").ifBlank { j.optString("message") }.ifBlank { j.optString("error_description") }.ifBlank { j.optString("error") }
}

/**
 * The member's sign-in (Supabase Auth): an access token that lasts an hour ([Api.token]) and a refresh token
 * that makes the next one, both kept on the phone (Session's private storage, which no other app can read).
 *  - [fresh] gives the access token, renewed first when it has less than [EARLY_S] seconds left (on a
 *    background thread only: never on the main thread);
 *  - a refresh token the server no longer accepts (signed out on the server, account removed) ends the
 *    sign-in: [onEnded] (the app goes back to the sign-in page).
 * Only one renewal runs at a time: the server hands out a new refresh token each time and the old one may be
 * used once more for a few seconds only, so two at once could end the sign-in.
 */
object Auth {
    private const val PREFS = "tf_session"
    /** renewed this many seconds before the access token runs out */
    private const val EARLY_S = 180L

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var refresh = ""
    /** when the access token runs out (seconds since 1970) */
    @Volatile private var exp = 0L
    /** the access token's account (Supabase user id) and number, as the server last said */
    @Volatile var userId = ""; private set
    @Volatile var phone = ""; private set

    /** the server ended this sign-in (main thread) */
    @Volatile var onEnded: (() -> Unit)? = null
    /** a new access token is in [Api.token] (main thread): live updates and notifications take it */
    @Volatile var onRenewed: ((String) -> Unit)? = null

    private fun prefs(ctx: Context): SharedPreferences =
        prefs ?: ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }

    /**
     * Reads the kept sign-in into [Api] (true when there is one). A Base44 sign-in kept by an older version of the
     * app has no refresh token: it is not a Supabase sign-in, so it is forgotten and the sign-in page shows.
     */
    fun load(ctx: Context): Boolean {
        val p = prefs(ctx)
        val token = p.getString("token", "").orEmpty()
        val r = p.getString("refresh", "").orEmpty()
        if (token.isEmpty() || r.isEmpty()) {
            if (token.isNotEmpty()) p.edit().remove("token").remove("app_id").remove("origin").remove("expert").apply()
            return false
        }
        synchronized(lock) {
            refresh = r
            exp = p.getLong("exp", 0L)
            userId = p.getString("uid", "").orEmpty()
            phone = p.getString("phone", "").orEmpty()
            Api.appId = Supa.APP_ID
            Api.token = token
        }
        return true
    }

    /** a kept sign-in, without reading it into [Api] */
    fun has(ctx: Context): Boolean = prefs(ctx).let { !it.getString("token", "").isNullOrEmpty() && !it.getString("refresh", "").isNullOrEmpty() }

    /** a sign-in just made or renewed (the answer of /verify or /token): into [Api] and onto the phone */
    fun take(ctx: Context?, s: JSONObject): Boolean {
        val access = s.optString("access_token")
        val r = s.optString("refresh_token")
        if (access.isEmpty() || r.isEmpty()) return false
        val now = System.currentTimeMillis() / 1000
        val until = s.optLong("expires_at", 0L).takeIf { it > now } ?: (now + s.optLong("expires_in", 3600L))
        val u = s.optJSONObject("user")
        synchronized(lock) {
            refresh = r
            exp = until
            if (u != null) { userId = u.optString("id", userId); phone = u.optString("phone", phone) }
            Api.appId = Supa.APP_ID
            Api.token = access
            val p = if (ctx != null) prefs(ctx) else prefs
            p?.edit()?.putString("token", access)?.putString("refresh", r)?.putLong("exp", until)
                ?.putString("uid", userId)?.putString("phone", phone)?.putString("app_id", Supa.APP_ID)?.apply()
        }
        return true
    }

    /** signed out on this phone: the tokens are forgotten (the server is told by [logout]) */
    fun clear(ctx: Context) {
        synchronized(lock) {
            refresh = ""; exp = 0L; userId = ""; phone = ""
            prefs(ctx).edit().remove("token").remove("refresh").remove("exp").remove("uid").remove("phone").remove("expert").apply()
        }
    }

    /**
     * The access token to send now. On a background thread a token close to its end is renewed first; on the
     * main thread (no network allowed there) it is given as it is. "" = not signed in.
     */
    fun fresh(): String {
        val tok = Api.token
        if (tok.isEmpty() || refresh.isEmpty() || Demo.on) return tok
        val now = System.currentTimeMillis() / 1000
        if (exp - now > EARLY_S || Looper.myLooper() == Looper.getMainLooper()) return tok
        renew(false)
        return Api.token
    }

    /** the access token for work while the app's screen is closed (a notification's Decline): read from the phone first */
    fun freshFor(ctx: Context): String {
        if (Api.token.isEmpty()) load(ctx)
        return fresh()
    }

    /**
     * Makes a new access token with the refresh token (a background thread). [force]: even when the one we have
     * is not close to its end (the server said it ran out). true = a good access token is in [Api.token].
     */
    fun renew(force: Boolean): Boolean {
        val before = Api.token
        synchronized(lock) {
            // another thread renewed it meanwhile: that one is used
            if (Api.token != before && Api.token.isNotEmpty()) return true
            val now = System.currentTimeMillis() / 1000
            if (!force && exp - now > EARLY_S) return true
            val r = refresh
            if (r.isEmpty() || Api.token.isEmpty()) return false
            val res = Supa.auth("POST", "token?grant_type=refresh_token", JSONObject().put("refresh_token", r))
            if (res.ok && take(null, res.data)) {
                val t = Api.token
                main.post { onRenewed?.invoke(t) }
                return true
            }
            // no internet / the server busy: the old token stays (it may still be good); asked again next time
            if (res.status == 0 || res.status == 429 || res.status >= 500) return false
            // the refresh token is not accepted any more (signed out elsewhere, account removed): the sign-in is over
            android.util.Log.w("TFAuth", "sign-in ended by the server (${res.status})")
            refresh = ""; exp = 0L
            prefs?.edit()?.remove("refresh")?.remove("exp")?.apply()
            main.post { onEnded?.invoke() }
            return false
        }
    }

    /** Log out on the server: this phone's sign-in stops working there too (the other phones stay signed in). */
    fun logout(token: String) {
        if (token.isEmpty() || Demo.on) return
        pool.execute { try { Supa.auth("POST", "logout?scope=local", null, token, 8000) } catch (e: Throwable) { } }
    }
}
