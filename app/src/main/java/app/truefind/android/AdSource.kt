package app.truefind.android

import android.graphics.Bitmap
import org.json.JSONObject

/**
 * Where the Control Center gets its data: the same Base44 server calls the web Control
 * Center makes (adminOps, adminUsers, adminPayouts, support, expertTalk, whatsappCheck,
 * entity reads and the verification updates). [AdDemo] answers with made-up data for the
 * test pictures on GitHub.
 */
interface AdSrc {
    /** base44.functions.invoke(name, body) */
    fun fn(name: String, body: JSONObject, done: (Api.Result) -> Unit)
    /** base44.auth.me() */
    fun me(done: (Api.Result) -> Unit)
    /** entities.X.filter(query, { sort, limit }) — one page: { items, next_cursor, has_more } */
    fun page(entity: String, query: JSONObject, sort: String?, limit: Int, done: (Api.Result) -> Unit)
    /** entities.X.get(id) */
    fun get(entity: String, id: String, done: (Api.Result) -> Unit)
    /** entities.X.update(id, patch) */
    fun update(entity: String, id: String, patch: JSONObject, done: (Api.Result) -> Unit)
    /** a photo, cut to fill w × h px (object-fit: cover) */
    fun image(raw: String, w: Int, h: Int, done: (Bitmap?) -> Unit)
    /** a photo as it is (the photo viewer), at most [maxSide] px */
    fun full(raw: String, maxSide: Int, done: (Bitmap?) -> Unit)
    /** a private file's address to download / play ("" when it can't be signed) */
    fun signed(raw: String, done: (String) -> Unit)
    /** the app's address (for the jobs link) and id */
    val origin: String
    val appId: String
}

object AdNet : AdSrc {
    override fun fn(name: String, body: JSONObject, done: (Api.Result) -> Unit) = Api.invoke(name, body, 60000, done)
    override fun me(done: (Api.Result) -> Unit) = Api.me(done)
    override fun page(entity: String, query: JSONObject, sort: String?, limit: Int, done: (Api.Result) -> Unit) {
        val q = StringBuilder("?q=").append(java.net.URLEncoder.encode(query.toString(), "UTF-8"))
        q.append("&limit=").append(if (limit > 0) limit else 100)
        if (sort != null) q.append("&sort=").append(java.net.URLEncoder.encode(sort, "UTF-8"))
        // (the plain list address: the same rows Base44's ".../v2/list" gave; Supabase's api answers this one)
        Api.async({ Api.request("GET", "/entities/$entity$q", null, null) }, done)
    }
    override fun get(entity: String, id: String, done: (Api.Result) -> Unit) = Api.get(entity, id, done)
    override fun update(entity: String, id: String, patch: JSONObject, done: (Api.Result) -> Unit) {
        Api.async({ Api.request("PUT", "/entities/$entity/${java.net.URLEncoder.encode(id, "UTF-8")}", patch.toString().toByteArray(Charsets.UTF_8), "application/json") }, done)
    }
    override fun image(raw: String, w: Int, h: Int, done: (Bitmap?) -> Unit) = Photos.loadCover(listOf(raw), w, h, done)
    override fun full(raw: String, maxSide: Int, done: (Bitmap?) -> Unit) = Photos.loadFull(raw, maxSide, done)
    override fun signed(raw: String, done: (String) -> Unit) {
        if (raw.isBlank()) { done(""); return }
        if (raw.startsWith("http")) { done(raw); return }
        // PrivateVideo: CreateFileSignedUrl (1 hour), then resolveAvatar's way as a second try
        Api.core("CreateFileSignedUrl", JSONObject().put("file_uri", raw).put("expires_in", 3600)) { r ->
            val u = r.data.optString("signed_url").ifBlank { r.data.optString("url") }.ifBlank { r.data.optJSONObject("data")?.optString("signed_url").orEmpty() }
            if (u.isNotBlank() && u != "null") done(u) else Photos.url(raw) { done(it) }
        }
    }
    override val origin: String get() = Supa.API
    override val appId: String get() = Api.appId
}
