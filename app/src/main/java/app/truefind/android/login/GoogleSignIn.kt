package app.truefind.android.login

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import app.truefind.android.Api
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Google sign-in, the same Base44 sign-in as the web (lib/startGoogleSession.js → signInWithProvider):
 * the app's sign-in settings are read (login-info), then Base44's own Google route is opened with the
 * same address the web SDK builds (/api/apps/auth/login?app_id=…&from_url=…), and Base44 brings the person
 * back to [returnTo] signed in (or to /mfa when the app needs a second step).
 *
 * Where it opens:
 *  - in the app's web page, exactly like the web app does today (the default);
 *  - in the phone's browser (Chrome Custom Tab) only when the web app has the small hand-back page
 *    /app-login.html (it passes the sign-in back to the app: truefind://auth). Google does not allow its
 *    sign-in inside an app's web view, so this is the way it works in an app — it starts by itself as soon
 *    as that page is published on the web side; until then nothing changes.
 */
internal object GoogleSignIn {
    private const val HANDOFF = "/app-login.html"
    private const val MARK = "tf-app-login"
    private val main = Handler(Looper.getMainLooper())
    private val rnd = SecureRandom()

    /** a sign-in that went to the phone's browser and has not come back yet */
    private var waiting: ((String) -> Unit)? = null
    private var leftAt = 0L

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun enc(s: String) = Uri.encode(s)

    /**
     * startGoogleSession(phone, returnTo): [done] gets "started" (the page is leaving for Google), "fail"
     * (it could not start: the web's catch), or "back" (the person came back from the browser without signing in).
     */
    fun start(layer: LoginLayer, phone: String, returnTo: String?, done: (String) -> Unit) {
        val digits = WelcomePage.onlyDigits(phone).replace(Regex("^91"), "").takeLast(10)
        val target = returnTo ?: ("/welcome" + if (digits.isNotEmpty()) "?verified_phone=" + enc("91$digits") else "")
        provider(layer, "google", target, false, done)
    }

    /** signInWithProvider(provider, returnTo, { manage }) of lib/authMfaFlow.js */
    fun provider(layer: LoginLayer, provider: String, returnTo: String, manage: Boolean, done: (String) -> Unit) {
        if (layer.preview.isNotEmpty()) { layer.toast("→ Google"); main.postDelayed({ done("back") }, 600); return }
        LoginApi.loginInfo { r ->
            if (!r.ok) { done("fail"); return@loginInfo }
            val cfg = r.data.optJSONObject("auth_config") ?: JSONObject()
            val origin = Api.origin
            val appId = Api.appId
            if (appId.isEmpty()) { done("fail"); return@loginInfo }
            // Enterprise SSO sign-in is exempt from Base44 MFA; only managing factors asks for it
            if (!manage && (cfg.optString("mfa_policy") != "required" || provider == "sso")) {
                val providerPath = if (provider == "google") "" else "/$provider"
                val path = if (provider == "sso") "/api/apps/$appId/auth/sso/login" else "/api/apps/auth$providerPath/login"
                val plain = { "$origin$path?app_id=$appId&from_url=${enc(absolute(origin, returnTo))}" }
                if (provider != "google") { layer.host.loadWeb(plain()); done("started"); return@loginInfo }
                // the phone's browser, when the web app can hand the sign-in back to the app
                LoginApi.page(HANDOFF) { h ->
                    if (h.ok && h.text.contains(MARK)) {
                        val nonce = b64(ByteArray(18).also { rnd.nextBytes(it) })
                        val back = "$origin$HANDOFF?s=${enc(nonce)}"
                        LoginStore.putGoogle(nonce, returnTo)
                        if (layer.host.openTab("$origin$path?app_id=$appId&from_url=${enc(back)}")) {
                            waiting = done; leftAt = SystemClock.uptimeMillis()
                            return@page
                        }
                        LoginStore.dropGoogle()
                    }
                    layer.host.loadWeb(plain()); done("started")
                }
                return@loginInfo
            }
            // the app needs a second step (MFA): Base44's code-challenge sign-in, back to /mfa (web page)
            val verifier = b64(ByteArray(32).also { rnd.nextBytes(it) })
            val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
            val workspaceSso = provider == "sso" && cfg.optBoolean("use_workspace_sso") && r.data.optString("organization_id").isNotEmpty()
            val path = when {
                provider == "sso" && workspaceSso -> "/api/apps/${r.data.optString("organization_id")}/$appId/auth/sso/login"
                provider == "sso" -> "/api/apps/$appId/auth/sso/login"
                else -> "/api/apps/auth${if (provider == "google") "" else "/$provider"}/login"
            }
            val q = StringBuilder("from_url=").append(enc(absolute(origin, returnTo)))
            if (!workspaceSso) q.append("&app_id=").append(appId)
            if (manage) q.append("&app_mfa_action=manage")
            q.append("&app_mfa_code_challenge=").append(challenge).append("&app_mfa_return=").append(enc("/mfa"))
            layer.host.js("(function(){try{sessionStorage.setItem(${JSONObject.quote("app-mfa-pkce:$appId")},${JSONObject.quote(verifier)})}catch(e){}location.assign(${JSONObject.quote("$origin$path?$q")})})()")
            done("started")
        }
    }

    private fun absolute(origin: String, path: String) = if (path.startsWith("http")) path else origin + (if (path.startsWith("/")) path else "/$path")

    /** truefind://auth?s=…&access_token=… (from /app-login.html in the phone's browser) */
    fun onReturn(layer: LoginLayer, uri: Uri) {
        val nonce = uri.getQueryParameter("s").orEmpty()
        val token = uri.getQueryParameter("access_token").orEmpty()
        val next = LoginStore.takeGoogle(nonce)
        val cb = waiting; waiting = null
        if (next == null || token.isEmpty()) { cb?.invoke("back"); return }
        cb?.invoke("started")
        layer.signInWith(token, next, replace = true)
    }

    /** back in the app: a browser sign-in that did not come back in a moment was given up */
    fun appVisible(v: Boolean) {
        if (!v || waiting == null) return
        main.postDelayed({
            val cb = waiting ?: return@postDelayed
            if (SystemClock.uptimeMillis() - leftAt < 1500) return@postDelayed
            waiting = null
            cb("back")
        }, 900)
    }
}
