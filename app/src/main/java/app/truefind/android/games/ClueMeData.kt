package app.truefind.android.games

/** Clue Me clue bank (same as src/lib/clueMeData.js). */
object ClueMeData {
    const val ROUNDS = 8
    const val SECONDS_PER_ROUND = 45
    class Category(val key: String, val label: String, val emoji: String, val blurb: String)
    class Item(val answer: String, val aliases: List<String>, val clues: List<String>, val id: String = "")
    val CATEGORIES = listOf(
        Category("mixed", "Mixed", "🎲", "A bit of everything"),
        Category("fruits", "Fruits", "🍎", "Sweet & juicy"),
        Category("animals", "Animals", "🐾", "Beasts & birds"),
        Category("food", "Indian Food", "🍛", "Desi favourites"),
        Category("bollywood", "Bollywood", "🎬", "Films & stars"),
        Category("places", "Places", "📍", "India & beyond"),
    )
    val ITEMS: Map<String, List<Item>> = linkedMapOf(
        "fruits" to listOf(
            Item("Mango", listOf("aam"), listOf("I am called the king of fruits.", "I turn golden yellow when I ripen.", "Summer shakes and 'aam panna' are named after me.")),
            Item("Banana", listOf("kela"), listOf("I come in a bunch and wear a yellow peel.", "Monkeys go crazy for me.", "I am long and curved.")),
            Item("Apple", listOf("seb"), listOf("I keep the doctor away, they say.", "I can be red or green and I'm crunchy.", "A famous phone brand uses a bitten version of me as its logo.")),
            Item("Grapes", listOf("angoor"), listOf("I grow in bunches on a vine.", "Green or black, I am small and juicy.", "The fox in the story said I was sour.")),
            Item("Watermelon", listOf("tarbuj", "tarbooz"), listOf("I am green outside and bright red inside.", "I am mostly water, perfect for summer.", "I have small black seeds all over.")),
            Item("Pomegranate", listOf("anar"), listOf("My name is the same as a firecracker.", "I am full of ruby-red juicy seeds.", "I wear a crown-like top on a hard round shell.")),
            Item("Guava", listOf("amrud", "amrood"), listOf("I am green outside, pink or white inside.", "I have tiny hard seeds in the centre.", "In India I'm often eaten with black salt and chilli.")),
            Item("Pineapple", listOf("ananas"), listOf("I wear a crown of spiky leaves.", "My skin has a diamond pattern.", "I am sweet, tangy and yellow inside.")),
            Item("Orange", listOf("santra", "santara"), listOf("My name is also a colour.", "I am famous for vitamin C.", "I come in a thick peel that is easy to remove.")),
            Item("Papaya", listOf("papita"), listOf("I am green when raw and orange when ripe.", "My raw form is used in sabzi and salads.", "I have a hollow centre full of black seeds.")),
            Item("Coconut", listOf("nariyal"), listOf("I am hard and hairy outside, sweet and white inside.", "I am offered at temples and grated into chutney.", "My water is a summer drink.")),
            Item("Strawberry", listOf(), listOf("I am red with tiny seeds on the outside.", "My shape looks like a little heart.", "I am the star of milkshakes and desserts.")),
        ),
        "animals" to listOf(
            Item("Elephant", listOf("hathi"), listOf("I am the largest land animal.", "My nose is a long trunk.", "My big ears help me stay cool.")),
            Item("Peacock", listOf("mor"), listOf("I am India's national bird.", "I spread a fan of blue-green feathers.", "My dance is a sign of rain in Indian stories.")),
            Item("Tiger", listOf("sher", "bagh"), listOf("I am India's national animal.", "My skin has black stripes on orange.", "The Sundarbans is famous for my swimming kind.")),
            Item("Monkey", listOf("bandar"), listOf("I live in trees and love bananas.", "I have a long tail and quick hands.", "I am known for copying whatever you do.")),
            Item("Lion", listOf("singh", "sher"), listOf("I am called the king of the jungle.", "My mane is my crown.", "Gir forest in Gujarat is my home in India.")),
            Item("Penguin", listOf(), listOf("I am a bird who cannot fly.", "I wear a black and white tuxedo.", "I slide on ice and love cold places.")),
            Item("Camel", listOf("oont"), listOf("I am called the ship of the desert.", "I store fat in a hump on my back.", "I can walk for days without water.")),
            Item("Dolphin", listOf(), listOf("I live in the sea but I breathe air.", "I am known to be very smart and playful.", "I click and whistle to talk with my group.")),
            Item("Butterfly", listOf("titli"), listOf("I start life as a caterpillar.", "My wings are covered in colour.", "I sit on flowers to drink nectar.")),
            Item("Snake", listOf("saap"), listOf("I move without legs.", "I shed my skin as I grow.", "I flick my forked tongue to sense the air.")),
            Item("Rabbit", listOf("khargosh"), listOf("My ears are long and I hop.", "I live in a burrow underground.", "I am famous for eating carrots.")),
            Item("Owl", listOf("ullu"), listOf("I stay awake at night.", "My eyes are big and fixed forward.", "I am the wise one in many stories.")),
        ),
        "food" to listOf(
            Item("Samosa", listOf(), listOf("I am triangular and deep fried.", "My filling is spicy potato and peas.", "I am the monsoon and chai companion.")),
            Item("Biryani", listOf(), listOf("I am a layered rice dish.", "You find me in Hyderabadi and Lucknowi styles.", "I am cooked with rice, spices and meat or veg.")),
            Item("Paneer Butter Masala", listOf("paneer", "paneer makhani", "butter paneer"), listOf("I am creamy, orange and slightly sweet.", "My main ingredient is cottage cheese cubes.", "I am best eaten with naan.")),
            Item("Dosa", listOf(), listOf("I am thin, crisp and made from fermented batter.", "I come from South India and roll around a filling.", "I am served with sambar and coconut chutney.")),
            Item("Chole Bhature", listOf("chole bhature", "chana bhatura"), listOf("I am a fluffy fried bread with a spicy chickpeas curry.", "I am a famous Punjabi breakfast.", "I am often eaten with onions and pickle.")),
            Item("Gulab Jamun", listOf("gulab jamoon"), listOf("I am brown and round, floating in sweet syrup.", "My name carries a flower and a fruit.", "I am made from khoya and served on festivals.")),
            Item("Pani Puri", listOf("golgappa", "gol gappa", "puchka"), listOf("I am a small hollow crispy ball filled with tangy water.", "I am Golgappa in Delhi and Pani Puri in Mumbai.", "I am the crowd's favourite street snack.")),
            Item("Butter Chicken", listOf("murgh makhani"), listOf("I am a creamy orange chicken curry.", "Delhi's Moti Mahal is said to be my birthplace.", "I am loved with naan and extra butter.")),
            Item("Pav Bhaji", listOf(), listOf("I come from Mumbai and my name has two words.", "My curry is mashed vegetables with lots of butter.", "I am served with a toasted bun.")),
            Item("Jalebi", listOf(), listOf("I am bright orange and spiral shaped.", "I am soaked in sugar syrup and crispy.", "I am eaten hot with rabri in winter.")),
            Item("Idli", listOf(), listOf("I am white, soft and steamed.", "I am made from rice and urad dal batter.", "I come in rounds and love sambar.")),
            Item("Rajma Chawal", listOf("rajma", "rajma rice"), listOf("I am a kidney bean curry with steamed rice.", "I am the comfort food of North India.", "My beans are red and shaped like a kidney.")),
        ),
        "bollywood" to listOf(
            Item("Shah Rukh Khan", listOf("srk", "shahrukh khan", "shahrukh"), listOf("I am called the King of Bollywood.", "I spread my arms wide in my signature pose.", "I starred in DDLJ and Pathaan.")),
            Item("Amitabh Bachchan", listOf("amitabh", "big b"), listOf("I am called Big B.", "My deep voice made me the 'angry young man'.", "I host Kaun Banega Crorepati.")),
            Item("Dilwale Dulhania Le Jayenge", listOf("ddlj", "dilwale"), listOf("I am one of the longest-running films in Indian theatres.", "My hero and heroine are Raj and Simran.", "My story ends with a train at a Punjab railway station.")),
            Item("Sholay", listOf(), listOf("My cast is Dharmendra, Amitabh and Hema Malini.", "My villain is Gabbar Singh.", "Thakur and Jai-Veeru are my characters.")),
            Item("Hrithik Roshan", listOf("hrithik"), listOf("I am called the Greek God of Bollywood.", "I danced my way to fame in Kaho Naa Pyaar Hai.", "I played Akbar in Jodhaa Akbar.")),
            Item("Deepika Padukone", listOf("deepika"), listOf("I am a top actress from Bengaluru.", "I played Mastani and Padmavati.", "I made my Hollywood debut in xXx.")),
            Item("3 Idiots", listOf("three idiots"), listOf("I am a film about engineering college life.", "Aamir Khan plays Rancho in me.", "My famous line is 'All is well'.")),
            Item("Zindagi Na Milegi Dobara", listOf("znmd"), listOf("I am a film about a road trip in Spain.", "My title is a line from a song about living once.", "The bull run of Pamplona is in me.")),
            Item("Alia Bhatt", listOf("alia"), listOf("My first film was Student of the Year.", "I played a spy in Raazi and then Gangubai.", "My father is a filmmaker and my mother is an actress.")),
            Item("Lagaan", listOf(), listOf("I am about a cricket bet against the British.", "Aamir Khan starred in and produced me.", "I was nominated for an Oscar in the foreign film category.")),
            Item("Dangal", listOf(), listOf("I am about a wrestler and his daughters.", "My line is 'Mhaari chhoriyan chhoron se kam hai ke'.", "The Phogat sisters are my real-life heroes.")),
            Item("Salman Khan", listOf("salman", "bhaijaan"), listOf("I am known as Bhaijaan.", "My films include Dabangg and Bajrangi Bhaijaan.", "I host Bigg Boss.")),
        ),
        "places" to listOf(
            Item("Taj Mahal", listOf("taj"), listOf("I am made of white marble.", "Shah Jahan built me for Mumtaz.", "I sit in Agra and am one of the seven wonders.")),
            Item("Goa", listOf(), listOf("I am known for beaches and Portuguese churches.", "People come to me for sun, sea and parties.", "Panaji is my capital.")),
            Item("Jaipur", listOf(), listOf("I am called the Pink City.", "Hawa Mahal and Amber Fort are in me.", "I am the capital of Rajasthan.")),
            Item("Kerala", listOf("god's own country"), listOf("I am called God's Own Country.", "My backwaters and houseboats are famous.", "Onam is my biggest festival.")),
            Item("Ladakh", listOf(), listOf("I am a cold desert full of mountains.", "Pangong Lake and Khardung La are mine.", "I am famous for bike road trips and monasteries.")),
            Item("Mumbai", listOf("bombay"), listOf("I am called the City of Dreams.", "The Gateway of India and Marine Drive are in me.", "I am home to Bollywood.")),
            Item("Varanasi", listOf("banaras", "kashi"), listOf("I am one of the oldest living cities.", "My ghats host the Ganga aarti every evening.", "I am also called Kashi.")),
            Item("Manali", listOf(), listOf("I am a hill station in Himachal.", "Solang valley and snow are my attractions.", "The Rohtang pass is near me.")),
            Item("Hyderabad", listOf(), listOf("I am called the City of Pearls.", "Charminar and biryani are mine.", "I am home to huge IT parks.")),
            Item("Rishikesh", listOf(), listOf("I sit on the banks of the Ganges.", "I am called the yoga capital.", "Rafting and the Laxmi Jhula bridge are famous here.")),
            Item("Darjeeling", listOf(), listOf("I am famous for tea gardens.", "A toy train runs through me.", "I offer views of Kanchenjunga.")),
            Item("Andaman Islands", listOf("andaman", "andamans"), listOf("I am a group of islands in the Bay of Bengal.", "Radhanagar beach and scuba diving are my draws.", "My beaches are often compared with Thailand's.")),
        ),
    )
}
