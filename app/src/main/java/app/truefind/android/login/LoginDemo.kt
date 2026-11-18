package app.truefind.android.login

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Test mode only (the emulator pictures on GitHub): made-up answers instead of the server, so every sign-in
 * screen can be photographed without a real number, email or login. Never used in the real app.
 */
internal object LoginDemo {
    var scene = ""
    private fun ok(o: JSONObject = JSONObject()) = LoginApi.R(200, o, o.toString())
    private fun no(status: Int, o: JSONObject) = LoginApi.R(status, o, o.toString())

    fun answer(kind: String, name: String, body: JSONObject?): LoginApi.R {
        val signedIn = scene.startsWith("basic") || scene.startsWith("complete")
        return when (kind) {
            "me" -> if (signedIn) ok(JSONObject().put("id", "demo-user").put("email", "aarav@example.com")) else no(401, JSONObject().put("detail", "Not authenticated"))
            "profiles" -> if (scene.startsWith("complete")) ok(JSONObject().put("items", JSONArray().put(JSONObject()
                .put("id", "p1").put("full_name", "Aarav Sharma").put("bio", "").put("hobbies", JSONArray().put("Music").put("Travel"))
                .put("occupation", "").put("preferred_age_min", 21).put("preferred_age_max", 30).put("preferred_gender", "female")
                .put("photo_queue", if (scene.contains("photo")) JSONArray().put(JSONObject().put("status", "pending")) else JSONArray())))) else ok(JSONObject().put("items", JSONArray()))
            "loginInfo" -> ok(JSONObject().put("name", "True Find").put("auth_config", JSONObject().put("enable_username_password", true).put("enable_google_login", true)))
            "page" -> no(404, JSONObject())
            "updateMe" -> ok()
            "fn" -> when (name) {
                "sendPhoneOtp" -> ok(JSONObject().put("success", true).apply { if (scene.contains("demo")) put("demo", true).put("demo_code", "482913") })
                "verifyPhoneOtp" -> ok(JSONObject().put("success", false).put("error", "Invalid or expired OTP."))
                "checkPhoneLogin" -> ok(JSONObject().put("status", "ok"))
                "createMyProfile" -> ok(JSONObject().put("success", true))
                "updateMyProfile" -> ok(JSONObject().put("success", true).put("profile", JSONObject().put("photo_queue", JSONArray())))
                "support" -> ok(JSONObject().put("success", true))
                else -> ok()
            }
            "auth" -> when (name) {
                "login" -> no(401, JSONObject().put("detail", "Invalid email or password"))
                "register", "resend-otp", "reset-password-request" -> ok()
                "verify-otp" -> no(400, JSONObject().put("detail", "Invalid or expired code"))
                "reset-password" -> no(400, JSONObject().put("detail", "Reset token has expired"))
                "mfa/status" -> ok(JSONObject().put("methods", JSONArray().put("totp")))
                "mfa/totp/setup" -> ok(JSONObject().put("secret_formatted", "JBSW Y3DP EHPK 3PXP").put("qr_code", ""))
                "mfa/resend", "mfa/sms/send-code" -> ok()
                else -> no(400, JSONObject().put("detail", "Invalid code"))
            }
            else -> ok()
        }
    }

    /** sets up the Welcome flow for a test picture */
    fun welcome(page: WelcomePage, variant: String) {
        if (variant.startsWith("verify") || variant.startsWith("gender") || variant.startsWith("profile")) page.phone = "9876543210"
        if (variant.startsWith("verify")) { page.sentAt = SystemClock.elapsedRealtime(); page.otpPhone = page.phone }
        if (variant.contains("demo")) page.demoOtp = "482913"
        if (variant.startsWith("profile")) page.gender = if (variant.contains("female")) "female" else "male"
    }
}
