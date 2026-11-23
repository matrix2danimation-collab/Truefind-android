package app.truefind.android

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** One profile card from getOnlineNow (same fields the web Explore reads). */
class Person(
    val id: String,
    val callId: String,
    val name: String,
    val age: Int,
    val gender: String,     // "female" | "male" | ""
    val city: String,
    val state: String,
    val photo: String,      // full photo
    val thumb: String,      // small copy (~360px)
    val verified: Boolean,
    val busy: Boolean,
    val online: Boolean,
    val tagsText: String,   // interests + languages + bio, lower case
    val bio: String,
    val demo: Boolean = false,
) {
    val firstName: String get() = name.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
    val place: String get() = city.ifBlank { state }

    /** Each person always shows the same call mark: video (purple) or voice (green). */
    val callType: String get() = if (hashStr(id.ifEmpty { name }) % 2 == 0) "video" else "voice"

    /** Photo URLs to try, small copy first. Empty → drawn avatar. */
    val photoUrls: List<String> get() = listOf(thumb, photo).filter { it.startsWith("http") }.distinct()

    companion object {
        fun hashStr(s: String): Int {
            var h = 0
            for (ch in s) h = (h * 31 + ch.code)
            return abs(h)
        }

        private fun src(v: Any?): String = when (v) {
            is String -> v
            is JSONObject -> listOf("url", "file_url", "uri", "file_uri").map { v.optString(it) }.firstOrNull { it.isNotBlank() } ?: ""
            else -> ""
        }

        private fun strList(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            return (0 until a.length()).map { a.optString(it) }
        }

        fun genderOf(g: String): String {
            val s = g.lowercase()
            return if (s.startsWith("f")) "female" else if (s.startsWith("m")) "male" else ""
        }

        fun from(o: JSONObject): Person {
            val name = listOf("name", "full_name", "display_name").map { o.optString(it, "") }.firstOrNull { it.isNotBlank() } ?: ""
            val full = listOf(o.opt("photo"), o.opt("profile_picture"), o.opt("avatar_url")).map { src(it) }.firstOrNull { it.isNotBlank() } ?: ""
            val callStatus = o.optString("call_status", "").lowercase()
            val busy = o.optBoolean("busy") || o.optBoolean("in_call") || o.optBoolean("on_call") ||
                callStatus in listOf("busy", "in_call", "on_call", "matching")
            val verified = o.optBoolean("verified") || o.optBoolean("is_verified") ||
                o.optString("identity_verification_status") == "verified"
            val tags = strList(o.optJSONArray("tags")) + strList(o.optJSONArray("hobbies")) +
                strList(o.optJSONArray("languages")) + listOf(o.optString("language", ""), o.optString("bio", ""))
            val callId = listOf("user_id", "created_by_id", "owner_id", "id").map { o.optString(it, "") }.firstOrNull { it.isNotBlank() } ?: ""
            return Person(
                id = o.optString("id", ""),
                callId = callId,
                name = name.trim(),
                age = o.optInt("age", 0),
                gender = genderOf(o.optString("gender", "")),
                city = o.optString("city", "").trim(),
                state = o.optString("state", "").trim(),
                photo = full,
                thumb = o.optString("photo_thumb", ""),
                verified = verified,
                busy = busy,
                online = !(o.has("online") && !o.optBoolean("online", true)),
                tagsText = tags.joinToString(" | ").lowercase(),
                bio = o.optString("bio", ""),
            )
        }

        // ── demo profiles (same as the web Explore): on in the GitHub test builds, off by themselves in the
        //    Play Store build (bundleRelease -PtfStore=true) — BuildConfig.SHOW_DEMO ──
        const val SHOW_DEMO = BuildConfig.SHOW_DEMO
        private val DEMO_GIRLS = listOf("Anvi", "Simran", "Kavya", "Riya", "Meher", "Ishita", "Pooja", "Naina", "Tanvi", "Aarohi", "Jasleen", "Diya", "Sana", "Mahi", "Ritika", "Neha", "Payal", "Komal", "Sneha", "Bhavna", "Aditi", "Muskan", "Prachi", "Rhea", "Gunjan", "Harleen", "Nidhi", "Sakshi", "Tamanna", "Khushi", "Preeti", "Shruti", "Nisha", "Kirti", "Manvi", "Ira")
        private val DEMO_BOYS = listOf("Aarav", "Rohan", "Kabir", "Arjun", "Vivaan", "Karan", "Rahul", "Aman", "Yash", "Dev", "Harsh", "Nikhil", "Sahil", "Varun", "Mohit", "Ankit", "Rohit", "Tushar", "Gaurav", "Deepak", "Manav", "Ishaan", "Pranav", "Kunal", "Lakshya", "Raghav", "Sameer", "Tarun", "Vikas", "Ajay", "Jatin", "Naveen", "Parth", "Ritesh", "Shivam", "Uday")
        private val DEMO_CITIES = listOf("Rohtak" to "Haryana", "Delhi" to "Delhi", "Jaipur" to "Rajasthan", "Pune" to "Maharashtra", "Ludhiana" to "Punjab", "Lucknow" to "Uttar Pradesh", "Indore" to "Madhya Pradesh", "Chandigarh" to "Chandigarh", "Hisar" to "Haryana", "Noida" to "Uttar Pradesh", "Amritsar" to "Punjab", "Bhopal" to "Madhya Pradesh")
        private val DEMO_LANG = listOf("Hindi", "Hindi", "English", "Punjabi", "Haryanvi", "Hindi")
        private val DEMO_TOPIC = listOf("Love", "Relationship", "Breakup", "Friendship", "Romantic Talk", "Just Talking", "Loneliness", "Stress", "Singing", "Movies", "Shayari")

        fun demoPeople(myGender: String): List<Person> {
            val girls = myGender != "female"
            return (if (girls) DEMO_GIRLS else DEMO_BOYS).mapIndexed { i, n ->
                val (city, st) = DEMO_CITIES[(i * 5) % DEMO_CITIES.size]
                val tags = listOf(DEMO_LANG[i % DEMO_LANG.size], DEMO_TOPIC[i % DEMO_TOPIC.size], DEMO_TOPIC[(i * 3 + 2) % DEMO_TOPIC.size], "Demo profile")
                Person(
                    id = "demo-${if (girls) "g" else "b"}$i", callId = "", name = n, age = 19 + ((i * 7) % 10),
                    gender = if (girls) "female" else "male", city = city, state = st, photo = "", thumb = "",
                    verified = i % 4 != 1, busy = i % 7 == 3, online = true,
                    tagsText = tags.joinToString(" | ").lowercase(), bio = "Demo profile", demo = true,
                )
            }
        }
    }
}
