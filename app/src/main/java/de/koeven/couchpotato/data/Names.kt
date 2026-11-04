package de.koeven.couchpotato.data

/**
 * Display clean-up for provider names: "DE: Der Pate", "|DE| Der Pate", "[DE] Der Pate",
 * "DE - 4K - Der Pate" → "Der Pate". Only known language/quality/platform tags are removed,
 * so real titles like "CSI: Miami" stay intact. Idempotent – safe to apply twice.
 */
object Names {
    private const val TAGS =
        "DE|GER|DEU|GERMAN|AT|CH|EN|ENG|UK|US|USA|FR|IT|ES|NL|PL|TR|RU|AR|EX|EU|MULTI|DUAL|" +
            "4K|UHD|FHD|HD|SD|HEVC|NF|NETFLIX|AMZ|AMAZON|PRIME|DSNP|DISNEY|SKY|ATV|APPLE|HBO|MAX|VOD|SERIE|SERIES|FILM|MOVIE|KIDS"

    private val LEADING = Regex(
        """^\s*(?:\|[^|]{1,14}\||\[[^\]]{1,14}]|\((?:$TAGS)\)|(?:$TAGS)(?:\s*[:|]\s*|\s+-\s+|\s+(?=\|)))"""
    )
    private val SXXEXX = Regex("""\bS\s?(\d{1,3})\s?[ ._-]?\s?E\s?(\d{1,4})\b""", RegexOption.IGNORE_CASE)
    private val SEPARATORS = charArrayOf(' ', '-', ':', '|', '·', '–', '—', '_')

    fun display(raw: String): String {
        // fast path for the catalogue load: "Der Pate" can't carry a tag – only "|..", "[..", "(.." or "DE…" can
        val t = raw.trimStart()
        if (t.length < 3 || (t[0] !in "|[(" && !(t[0].isUpperCase() || t[0].isDigit()) ) ||
            (t[0] !in "|[(" && t[1].isLowerCase())
        ) return raw.trim()
        var s = raw
        while (true) {
            val m = LEADING.find(s) ?: break
            if (m.range.last + 1 >= s.length) break          // never strip everything
            s = s.substring(m.range.last + 1)
        }
        return s.trim().trim(*SEPARATORS).ifEmpty { raw.trim() }
    }

    private val BRACKETS = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val EPISODE_TAIL = Regex("""\s*[-–—:|,·]?\s*\b(Folge|Episode|Teil|Staffel|Season|Ep\.?)\s*\d+.*$""", RegexOption.IGNORE_CASE)
    private val SEASON_EPISODE = Regex("""\s*[-–—:|,·]?\s*\bS\s?\d{1,3}\s?[ ._-]?\s?E\s?\d{1,4}\b.*$""", RegexOption.IGNORE_CASE)
    private val APP_TAIL = Regex("""\s*[|–—-]\s*(ARD|ZDF|Mediathek|ARD Mediathek|ZDFmediathek|arte|3sat|Joyn|RTL\+?).*$""", RegexOption.IGNORE_CASE)
    private val SPLIT = Regex("""\s+[-–—|]\s+|:\s+""")

    /**
     * Names to look a title from another app up by (TMDB): "Werwölfe (1/6) | ARD Mediathek",
     * "Werwölfe – Folge 3: Vollmond" → "Werwölfe"; also the part before " - " / ":" as a second try.
     * Most likely first, at most [max].
     */
    fun lookupNames(vararg raw: String?, max: Int = 3): List<String> {
        val out = LinkedHashSet<String>()
        for (r in raw) {
            if (r.isNullOrBlank()) continue
            var s = display(r)
            s = APP_TAIL.replace(s, "")
            s = BRACKETS.replace(s, "")
            s = EPISODE_TAIL.replace(s, "")
            s = SEASON_EPISODE.replace(s, "")
            s = s.trim().trim(*SEPARATORS)
            if (s.length >= 3) out += s
            SPLIT.split(s).first().trim().trim(*SEPARATORS).takeIf { it.length >= 3 }?.let { out += it }
        }
        return out.take(max)
    }

    /** "DE - Breaking Bad - S01E03 - Der Bote" → "Der Bote"; nothing left → "Folge 3". */
    fun episode(raw: String?, seriesName: String, number: Int): String {
        val fallback = "Folge $number"
        var s = display(raw ?: return fallback)
        val series = display(seriesName).trim()
        if (series.length >= 2 && s.startsWith(series, ignoreCase = true)) s = s.substring(series.length)
        s = s.trim().trim(*SEPARATORS)
        SXXEXX.find(s)?.let { m ->
            // drop "S01E03" and everything in front of it (often the series name in another spelling)
            if (m.range.first <= 40) s = s.substring(m.range.last + 1)
        }
        s = s.trim().trim(*SEPARATORS)
        return s.ifEmpty { fallback }
    }
}
