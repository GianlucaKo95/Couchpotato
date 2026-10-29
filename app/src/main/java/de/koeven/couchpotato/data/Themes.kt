package de.koeven.couchpotato.data

/**
 * German search words → TMDB themes. TMDB tags titles with (English) keywords and genres;
 * this table turns "Weihnachtsfilm" or "Fußball" into those, so the search can find films by topic.
 */
data class Theme(
    val label: String,                 // shown in the headings: „Weihnachten“
    val keywords: List<String> = emptyList(),   // TMDB keyword names (English) to look up
    val movieGenres: List<Int> = emptyList(),
    val tvGenres: List<Int> = emptyList(),
)

object Themes {
    // stems are matched against the normalised query (lowercase, no accents, ß→ss)
    private val TABLE: List<Pair<List<String>, Theme>> = listOf(
        listOf("weihnacht", "xmas", "christmas", "advent", "nikolaus") to Theme("Weihnachten", listOf("christmas", "santa claus")),
        listOf("fussball", "soccer") to Theme("Fußball", listOf("football (soccer)", "soccer")),
        listOf("football") to Theme("Football", listOf("american football")),
        listOf("basketball") to Theme("Basketball", listOf("basketball")),
        listOf("boxen", "boxer", "boxing") to Theme("Boxen", listOf("boxing")),
        listOf("tennis") to Theme("Tennis", listOf("tennis")),
        listOf("eishockey", "hockey") to Theme("Eishockey", listOf("ice hockey")),
        listOf("rennen", "rennfahrer", "formel", "autorennen", "racing") to Theme("Rennen", listOf("car race", "racing")),
        listOf("hai", "haie", "shark") to Theme("Haie", listOf("shark", "shark attack")),
        listOf("dinosaur", "dino", "saurier") to Theme("Dinosaurier", listOf("dinosaur")),
        listOf("pferd", "pony", "reiten") to Theme("Pferde", listOf("horse", "horseback riding")),
        listOf("hund", "hunde", "welpe") to Theme("Hunde", listOf("dog")),
        listOf("katze", "katzen") to Theme("Katzen", listOf("cat")),
        listOf("weltraum", "weltall", "astronaut", "raumschiff", "space") to Theme("Weltraum", listOf("outer space", "space travel", "astronaut")),
        listOf("ausserirdisch", "alien", "ufo") to Theme("Außerirdische", listOf("alien", "alien invasion")),
        listOf("zeitreise", "zeitmaschine") to Theme("Zeitreisen", listOf("time travel")),
        listOf("zombie") to Theme("Zombies", listOf("zombie")),
        listOf("vampir", "dracula") to Theme("Vampire", listOf("vampire")),
        listOf("hexe", "hexen") to Theme("Hexen", listOf("witch")),
        listOf("zauberer", "magie", "zauber") to Theme("Magie", listOf("magic", "wizard")),
        listOf("drache", "drachen") to Theme("Drachen", listOf("dragon")),
        listOf("pirat") to Theme("Piraten", listOf("pirate")),
        listOf("ritter", "mittelalter") to Theme("Mittelalter", listOf("knight", "middle ages")),
        listOf("roboter", "robot", "kuenstliche intelligenz", "ki ") to Theme("Roboter & KI", listOf("robot", "artificial intelligence (a.i.)")),
        listOf("superheld", "superhero", "superkraft") to Theme("Superhelden", listOf("superhero")),
        listOf("halloween") to Theme("Halloween", listOf("halloween")),
        listOf("ostern", "osterhase") to Theme("Ostern", listOf("easter")),
        listOf("silvester", "neujahr") to Theme("Silvester", listOf("new year's eve")),
        listOf("hochzeit", "heirat", "braut") to Theme("Hochzeit", listOf("wedding")),
        listOf("urlaub", "ferien", "reise") to Theme("Urlaub", listOf("vacation", "road trip")),
        listOf("strand", "sommer") to Theme("Sommer", listOf("summer", "beach")),
        listOf("schnee", "winter", "ski") to Theme("Winter", listOf("snow", "skiing")),
        listOf("meer", "ozean", "unterwasser", "tauchen") to Theme("Meer", listOf("ocean", "underwater")),
        listOf("berg", "klettern", "bergsteig") to Theme("Berge", listOf("mountain", "mountain climbing")),
        listOf("schule", "abitur", "highschool") to Theme("Schule", listOf("high school", "school")),
        listOf("weltkrieg", "nazi", "ww2") to Theme("Zweiter Weltkrieg", listOf("world war ii")),
        listOf("mafia", "gangster") to Theme("Mafia", listOf("mafia", "gangster")),
        listOf("serienmoerder", "serienkiller", "killer") to Theme("Serienmörder", listOf("serial killer")),
        listOf("spion", "agent", "geheimagent") to Theme("Spione", listOf("spy", "secret agent")),
        listOf("raub", "ueberfall", "heist", "coup") to Theme("Raubüberfälle", listOf("heist", "bank robbery")),
        listOf("gefaengnis", "knast") to Theme("Gefängnis", listOf("prison")),
        listOf("kochen", "koch", "restaurant", "essen") to Theme("Kochen", listOf("cooking", "chef")),
        listOf("tanz", "tanzen") to Theme("Tanzen", listOf("dancing", "dance")),
        listOf("musical") to Theme("Musicals", listOf("musical")),
        listOf("wahre geschichte", "wahre begebenheit", "biografie", "biopic") to Theme("Wahre Geschichten", listOf("based on true story", "biography")),
        listOf("apokalypse", "endzeit", "weltuntergang") to Theme("Endzeit", listOf("post-apocalyptic future", "apocalypse")),
        listOf("katastrophe", "erdbeben", "tornado", "vulkan") to Theme("Katastrophen", listOf("natural disaster", "disaster")),
        // genres
        listOf("komoedie", "lustig", "witzig", "comedy") to Theme("Komödie", movieGenres = listOf(35), tvGenres = listOf(35)),
        listOf("horror", "grusel", "gruselig") to Theme("Horror", movieGenres = listOf(27)),
        listOf("zeichentrick", "animation", "trickfilm", "animiert", "anime") to Theme("Animation", movieGenres = listOf(16), tvGenres = listOf(16)),
        listOf("doku", "dokumentation") to Theme("Dokumentation", movieGenres = listOf(99), tvGenres = listOf(99)),
        listOf("krimi") to Theme("Krimi", movieGenres = listOf(80), tvGenres = listOf(80)),
        listOf("thriller", "spannend") to Theme("Thriller", movieGenres = listOf(53)),
        listOf("action") to Theme("Action", movieGenres = listOf(28), tvGenres = listOf(10759)),
        listOf("science fiction", "scifi", "sci fi", "zukunft") to Theme("Science-Fiction", movieGenres = listOf(878), tvGenres = listOf(10765)),
        listOf("fantasy") to Theme("Fantasy", movieGenres = listOf(14), tvGenres = listOf(10765)),
        listOf("familie", "familienfilm") to Theme("Familie", movieGenres = listOf(10751), tvGenres = listOf(10751)),
        listOf("kinderfilm", "kinderserie", "fuer kinder") to Theme("Für Kinder", movieGenres = listOf(10751, 16), tvGenres = listOf(10762)),
        listOf("liebesfilm", "romanze", "romantisch", "liebe") to Theme("Liebe", movieGenres = listOf(10749)),
        listOf("western", "cowboy") to Theme("Western", movieGenres = listOf(37), tvGenres = listOf(37)),
        listOf("kriegsfilm", "krieg") to Theme("Krieg", movieGenres = listOf(10752), tvGenres = listOf(10768)),
        listOf("musikfilm") to Theme("Musik", movieGenres = listOf(10402)),
        listOf("abenteuer") to Theme("Abenteuer", movieGenres = listOf(12), tvGenres = listOf(10759)),
        listOf("mystery", "raetsel") to Theme("Mystery", movieGenres = listOf(9648), tvGenres = listOf(9648)),
        listOf("historie", "historisch", "geschichte") to Theme("Geschichte", movieGenres = listOf(36)),
    )

    private val FILLERS = Regex("""\b(film|filme|movie|movies|serie|serien|series|mit|ueber|thema|zum|zur|der|die|das|ein|eine|einen|ueber)\b""")

    /** The theme a query asks for, or null. "Weihnachtsfilm" → Weihnachten, "Film mit Fußball" → Fußball. */
    fun match(query: String): Theme? {
        val q = SearchIndex.normalize(query)
        if (q.length < 3) return null
        val spaced = " $q "
        for ((stems, theme) in TABLE) {
            if (stems.any { st -> spaced.contains(" $st") || q.startsWith(st) }) return theme
        }
        return null
    }

    /** Leftover words for a free keyword lookup at TMDB when the table knows nothing ("Film über Bienen" → "bienen"). */
    fun freeWords(query: String): String =
        SearchIndex.normalize(query).replace(FILLERS, " ").replace(Regex("\\s+"), " ").trim()
}
