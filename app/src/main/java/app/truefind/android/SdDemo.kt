package app.truefind.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * Secret Dating demo profiles (Nov 20, the web's src/lib/sdDemo.js) — so the Online Map and Home look full while the
 * app is being tested. They exist only inside the app: no accounts, nothing saved anywhere. Each one has a 3D avatar
 * face (the Random Match faces in assets/home/faces — never a real person's photo), says "Demo profile" in its About,
 * and can't be liked, requested, blocked or reported — no credit or coin is ever spent on one.
 * SHOW = false switches them all off again (do that before the real launch, with the web's SHOW_SD_DEMO).
 */
object SdDemo {
    const val SHOW = true

    // the same names, places and details as the web, in the same order (so both show the same 100 people)
    private val GIRLS = listOf("Simran", "Priya", "Anjali", "Neha", "Kavya", "Riya", "Pooja", "Sneha", "Aarohi", "Diya", "Ishita", "Meher", "Naina", "Tanvi", "Jasleen", "Sana", "Mahi", "Ritika", "Payal", "Komal", "Bhavna", "Aditi", "Muskan", "Prachi", "Rhea", "Gunjan", "Harleen", "Nidhi", "Sakshi", "Tamanna", "Khushi", "Preeti", "Shruti", "Nisha", "Kirti", "Manvi", "Ira", "Ananya", "Avni", "Charu", "Deepika", "Esha", "Garima", "Heena", "Isha", "Jyoti", "Kajal", "Lavanya", "Megha", "Nandini", "Ojasvi", "Pallavi", "Radhika", "Saloni", "Tanya", "Urvashi", "Vanshika", "Yashika", "Zoya", "Mansi")
    private val BOYS = listOf("Aarav", "Rohan", "Kabir", "Arjun", "Vivaan", "Karan", "Rahul", "Aman", "Yash", "Dev", "Harsh", "Nikhil", "Sahil", "Varun", "Mohit", "Ankit", "Rohit", "Tushar", "Gaurav", "Deepak", "Manav", "Ishaan", "Pranav", "Kunal", "Lakshya", "Raghav", "Sameer", "Tarun", "Vikas", "Ajay", "Jatin", "Naveen", "Parth", "Ritesh", "Shivam", "Uday", "Abhishek", "Akash", "Bharat", "Chirag", "Dhruv", "Gagan", "Hemant", "Jayant", "Keshav", "Lalit", "Mayank", "Neeraj", "Ojas", "Pankaj", "Rajat", "Saurabh", "Tejas", "Utkarsh", "Vipin", "Yuvraj", "Aditya", "Siddharth", "Amit", "Vishal")
    private val SURNAMES = listOf("Sharma", "Kaur", "Malik", "Verma", "Dahiya", "Gupta", "Hooda", "Singh", "Rathi", "Arora", "Yadav", "Bansal", "Chauhan", "Mehta", "Saini", "Kapoor", "Jain", "Sehgal", "Joshi", "Bhatia", "Nair", "Reddy", "Das", "Patel", "Iyer", "Rao", "Mishra", "Chaudhary", "Shah", "Sen")
    private val INTERESTS = listOf("Music", "Movies", "Travel", "Long drives", "Cooking", "Dancing", "Singing", "Shayari", "Fitness", "Gaming", "Reading", "Photography", "Chai", "Cricket", "Romantic talk")
    private val JOBS = listOf("Student", "Teacher", "Designer", "Engineer", "Nurse", "Banker", "Business", "Software developer", "Doctor", "Fashion designer", "Marketing", "Preparing for govt. exams")
    private val HEIGHTS_F = listOf("5'1\"", "5'2\"", "5'3\"", "5'4\"", "5'5\"", "5'6\"", "5'7\"")
    private val HEIGHTS_M = listOf("5'6\"", "5'7\"", "5'8\"", "5'9\"", "5'10\"", "5'11\"", "6'0\"")
    private val PLACES: List<Pair<String, String>> = listOf(
        5 to ("Rohtak" to "Haryana"),
        3 to ("Sonipat" to "Haryana"),
        3 to ("Panipat" to "Haryana"),
        3 to ("Karnal" to "Haryana"),
        3 to ("Hisar" to "Haryana"),
        3 to ("Gurugram" to "Haryana"),
        3 to ("Faridabad" to "Haryana"),
        2 to ("Jhajjar" to "Haryana"),
        2 to ("Bhiwani" to "Haryana"),
        2 to ("Ambala" to "Haryana"),
        2 to ("Kurukshetra" to "Haryana"),
        2 to ("Jind" to "Haryana"),
        1 to ("Sirsa" to "Haryana"),
        1 to ("Rewari" to "Haryana"),
        1 to ("Kaithal" to "Haryana"),
        1 to ("Yamunanagar" to "Haryana"),
        1 to ("Panchkula" to "Haryana"),
        1 to ("Fatehabad" to "Haryana"),
        1 to ("Palwal" to "Haryana"),
        1 to ("Charkhi Dadri" to "Haryana"),
        2 to ("South Delhi" to "Delhi"),
        2 to ("North West Delhi" to "Delhi"),
        2 to ("West Delhi" to "Delhi"),
        1 to ("East Delhi" to "Delhi"),
        1 to ("New Delhi" to "Delhi"),
        2 to ("Gautam Buddh Nagar (Noida)" to "Uttar Pradesh"),
        2 to ("Ghaziabad" to "Uttar Pradesh"),
        1 to ("Meerut" to "Uttar Pradesh"),
        2 to ("Lucknow" to "Uttar Pradesh"),
        1 to ("Agra" to "Uttar Pradesh"),
        1 to ("Kanpur Nagar" to "Uttar Pradesh"),
        1 to ("Varanasi" to "Uttar Pradesh"),
        2 to ("Ludhiana" to "Punjab"),
        2 to ("Amritsar" to "Punjab"),
        1 to ("Jalandhar" to "Punjab"),
        1 to ("Patiala" to "Punjab"),
        1 to ("Mohali (SAS Nagar)" to "Punjab"),
        1 to ("Bathinda" to "Punjab"),
        2 to ("Chandigarh" to "Chandigarh"),
        3 to ("Jaipur" to "Rajasthan"),
        1 to ("Jodhpur" to "Rajasthan"),
        1 to ("Udaipur" to "Rajasthan"),
        1 to ("Kota" to "Rajasthan"),
        1 to ("Alwar" to "Rajasthan"),
        1 to ("Shimla" to "Himachal Pradesh"),
        1 to ("Kangra" to "Himachal Pradesh"),
        2 to ("Dehradun" to "Uttarakhand"),
        2 to ("Indore" to "Madhya Pradesh"),
        1 to ("Bhopal" to "Madhya Pradesh"),
        1 to ("Gwalior" to "Madhya Pradesh"),
        2 to ("Mumbai" to "Maharashtra"),
        2 to ("Pune" to "Maharashtra"),
        1 to ("Nagpur" to "Maharashtra"),
        1 to ("Ahmedabad" to "Gujarat"),
        1 to ("Surat" to "Gujarat"),
        2 to ("Bengaluru Urban" to "Karnataka"),
        1 to ("Hyderabad" to "Telangana"),
        1 to ("Chennai" to "Tamil Nadu"),
        1 to ("Kolkata" to "West Bengal"),
        1 to ("Patna" to "Bihar"),
        1 to ("Ernakulam" to "Kerala"),
        1 to ("Khordha" to "Odisha"),
        1 to ("Kamrup Metro" to "Assam"),
        1 to ("Jammu" to "Jammu and Kashmir"),
    ).flatMap { (n, p) -> List(n) { p } }
    /** one demo person, before it becomes a profile */
    class D(val id: String, val name: String, val age: Int, val girl: Boolean, val city: String, val state: String, val photo: String,
            val job: String, val height: String, val hobbies: List<String>, val online: Boolean, val hours: Int)

    /** 100 demo people of one gender (girls = the ones boys see) */
    fun list(girls: Boolean): List<D> {
        val key = if (girls) "f" else "m"
        val names = if (girls) GIRLS else BOYS
        return List(100) { i ->
            val (city, state) = PLACES[(i * 37) % PLACES.size]
            val first = names[i % names.size]; val last = SURNAMES[(i * 7 + (if (i >= names.size) 3 else 0)) % SURNAMES.size]
            val hobbies = listOf(INTERESTS[i % INTERESTS.size], INTERESTS[(i * 3 + 4) % INTERESTS.size], INTERESTS[(i * 7 + 9) % INTERESTS.size]).distinct()
            D("demo-sd-$key$i", "$first $last", 19 + (i * 7) % 11, girls, city, state, "asset:home/faces/${if (girls) "g" else "b"}${(i % 20).toString().padStart(2, '0')}.jpg",
                JOBS[(i * 5) % JOBS.size], (if (girls) HEIGHTS_F else HEIGHTS_M)[i % 7], hobbies, i % 5 < 2, 1 + (i * 13) % 70)
        }
    }

    private val made = HashMap<String, List<JSONObject>>()
    /** the 100 demo people of the gender this member sees on Secret Dating (boys see girls, girls see boys) — made once */
    fun people(myGender: String): List<JSONObject> {
        val girls = myGender != "f" && myGender != "female"
        val key = if (girls) "f" else "m"
        made[key]?.let { return it }
        val now = System.currentTimeMillis()
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val out = list(girls).map { d ->
            JSONObject().put("id", d.id).put("user_id", "").put("name", d.name).put("age", d.age).put("gender", if (girls) "female" else "male")
                .put("city", d.city).put("state", d.state).put("photo", d.photo).put("photo_thumb", "")
                .put("bio", "Demo profile (for testing)").put("occupation", d.job).put("height", d.height).put("hobbies", JSONArray(d.hobbies))
                .put("looking_for", "dating").put("account_type", if (girls) "creator" else "user").put("verified", true).put("premium", false)
                .put("online", d.online).put("_online", d.online).put("last_active", iso.format(java.util.Date(if (d.online) now else now - d.hours * 3_600_000L)))
                .put("demo", true).put("_demo", true).put("liked_by_me", false).put("chatting", false)
        }
        made[key] = out
        return out
    }

    /** a demo profile (made here, never a real member) */
    fun isDemo(p: JSONObject?): Boolean = p != null && (p.optBoolean("_demo") || (p.optBoolean("demo") && p.optString("id").startsWith("demo-sd-")))
}
