package com.example.domain.model

import java.util.Locale

/**
 * Resolves country and special flag codes from Unicode regional indicators,
 * subdivision tag sequences, special flag sequences (pirate/pride/trans/checkered),
 * and English/Russian country or city names. Also strips flag emojis from server labels.
 */
object CountryFlagResolver {

    private const val REGIONAL_INDICATOR_START = 0x1F1E6 // 'A'
    private const val REGIONAL_INDICATOR_END = 0x1F1FF   // 'Z'
    private const val TAG_START = 0xE0020
    private const val TAG_END = 0xE007F
    private const val CANCEL_TAG = 0xE007F
    private const val BLACK_FLAG_CP = 0x1F3F4
    private const val WHITE_FLAG_CP = 0x1F3F3
    private const val CHEQUERED_FLAG_CP = 0x1F3C1
    private const val TRIANGULAR_FLAG_CP = 0x1F6A9
    private const val CROSSED_FLAGS_CP = 0x1F38C
    private const val SKULL_AND_CROSSBONES_CP = 0x2620
    private const val RAINBOW_CP = 0x1F308
    private const val TRANS_SYMBOL_CP = 0x26A7
    private const val ZWJ = 0x200D
    private const val VS16 = 0xFE0F

    const val CODE_PIRATE = "PIRATE"
    const val CODE_PRIDE = "PRIDE"
    const val CODE_TRANS = "TRANS"
    const val CODE_CHECKERED = "CHECKERED"
    const val CODE_WHITE_FLAG = "WHITE_FLAG"
    const val CODE_BLACK_FLAG = "BLACK_FLAG"
    const val CODE_RED_FLAG = "RED_FLAG"
    const val CODE_ENGLAND = "GB-ENG"
    const val CODE_SCOTLAND = "GB-SCT"
    const val CODE_WALES = "GB-WLS"

    data class ResolvedServerLabel(
        val countryCode: String?,
        val cleanName: String,
    )

    private val ALL_ISO_COUNTRIES: Set<String> by lazy {
        buildSet {
            addAll(Locale.getISOCountries().map { it.uppercase(Locale.US) })
            addAll(listOf("EU", "UN", "XK", "AQ", "UK", "TW", "HK", "MO", "PS", "AC", "CP", "DG", "EA", "IC", "TA"))
        }
    }

    fun resolve(serverName: String, host: String = ""): ResolvedServerLabel {
        val emojiCode = extractFlagEmojiIsoCode(serverName)
        val stripped = stripFlagEmoji(serverName).ifBlank { serverName.trim() }
        val resolvedCode = emojiCode
            ?: detectCountryFromKeywords(stripped)
            ?: detectCountryFromHostTld(host)
        return ResolvedServerLabel(
            countryCode = resolvedCode,
            cleanName = stripped,
        )
    }

    /**
     * Converts any resolved code (ISO-3166 alpha-2 or special flag code) back into its Unicode emoji sequence
     * so Android's Noto Color Emoji engine can rasterize ANY world/special flag on demand.
     */
    fun flagCodeToEmoji(code: String): String? {
        val upper = code.uppercase(Locale.US)
        return when (upper) {
            CODE_PIRATE -> "\uD83C\uDFF4\u200D\u2620\uFE0F"
            CODE_PRIDE -> "\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08"
            CODE_TRANS -> "\uD83C\uDFF3\uFE0F\u200D\u26A7\uFE0F"
            CODE_CHECKERED -> "\uD83C\uDFC1"
            CODE_WHITE_FLAG -> "\uD83C\uDFF3\uFE0F"
            CODE_BLACK_FLAG -> "\uD83C\uDFF4"
            CODE_RED_FLAG -> "\uD83D\uDEA9"
            CODE_ENGLAND -> "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"
            CODE_SCOTLAND -> "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F"
            CODE_WALES -> "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC77\uDB40\uDC6C\uDB40\uDC73\uDB40\uDC7F"
            else -> {
                val normalized = if (upper == "UK") "GB" else upper
                if (normalized.length == 2 && normalized[0] in 'A'..'Z' && normalized[1] in 'A'..'Z') {
                    val cp1 = REGIONAL_INDICATOR_START + (normalized[0].code - 'A'.code)
                    val cp2 = REGIONAL_INDICATOR_START + (normalized[1].code - 'A'.code)
                    StringBuilder(4).appendCodePoint(cp1).appendCodePoint(cp2).toString()
                } else {
                    null
                }
            }
        }
    }

    /**
     * Extracts the first Unicode flag emoji (Regional Indicator pair, subdivision tag flag,
     * pirate/pride/trans/checkered/white/black/red/crossed flag) and converts it to a normalized code.
     */
    fun extractFlagEmojiIsoCode(text: String): String? {
        val cps = toCodePoints(text)
        var i = 0
        while (i < cps.size) {
            val cp = cps[i]
            // 1. Standard Regional Indicator pair (all 249+ ISO countries, EU, UN, AQ, etc.)
            if (cp in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END && i + 1 < cps.size) {
                val cp2 = cps[i + 1]
                if (cp2 in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END) {
                    val c1 = ('A'.code + (cp - REGIONAL_INDICATOR_START)).toChar()
                    val c2 = ('A'.code + (cp2 - REGIONAL_INDICATOR_START)).toChar()
                    val iso = "$c1$c2"
                    return if (iso == "UK") "GB" else iso
                }
            }
            // Black flag sequences: Pirate (ZWJ skull), England/Scotland/Wales tags, or plain black flag
            if (cp == BLACK_FLAG_CP) {
                // Check ZWJ pirate skull sequence
                var j = i + 1
                if (j < cps.size && cps[j] == VS16) j++
                if (j + 1 < cps.size && cps[j] == ZWJ && cps[j + 1] == SKULL_AND_CROSSBONES_CP) {
                    return CODE_PIRATE
                }
                // Check subdivision tag sequence (U+E0020..U+E007F)
                if (i + 1 < cps.size && cps[i + 1] in TAG_START..TAG_END) {
                    val tagChars = StringBuilder()
                    var k = i + 1
                    while (k < cps.size && cps[k] in TAG_START..TAG_END) {
                        if (cps[k] != CANCEL_TAG) {
                            tagChars.append((cps[k] - 0xE0000).toChar())
                        }
                        k++
                    }
                    val tag = tagChars.toString().lowercase(Locale.US)
                    return when (tag) {
                        "gbeng" -> CODE_ENGLAND
                        "gbsct" -> CODE_SCOTLAND
                        "gbwls" -> CODE_WALES
                        else -> if (tag.length >= 2) tag.substring(0, 2).uppercase(Locale.US) else CODE_BLACK_FLAG
                    }
                }
                return CODE_BLACK_FLAG
            }
            // White flag sequences: Pride/Trans ZWJ sequences or plain white flag
            if (cp == WHITE_FLAG_CP) {
                var j = i + 1
                if (j < cps.size && cps[j] == VS16) j++
                if (j + 1 < cps.size && cps[j] == ZWJ) {
                    when (cps[j + 1]) {
                        RAINBOW_CP -> return CODE_PRIDE
                        TRANS_SYMBOL_CP -> return CODE_TRANS
                    }
                }
                return CODE_WHITE_FLAG
            }
            // 4. Other standalone flag emojis
            when (cp) {
                CHEQUERED_FLAG_CP -> return CODE_CHECKERED
                TRIANGULAR_FLAG_CP -> return CODE_RED_FLAG
                CROSSED_FLAGS_CP -> return "JP"
            }
            i++
        }
        return null
    }

    /**
     * Removes ALL flag emojis (Regional Indicators, subdivision tag flags, pirate/pride/trans/checkered/red/black/white)
     * and cleans up leftover extra spaces/separators.
     */
    fun stripFlagEmoji(text: String): String {
        val cps = toCodePoints(text)
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < cps.size) {
            val cp = cps[i]
            // Regional Indicator pair
            if (cp in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END) {
                i += if (i + 1 < cps.size && cps[i + 1] in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END) 2 else 1
                continue
            }
            // Black flag + optional ZWJ pirate skull OR tag sequence
            if (cp == BLACK_FLAG_CP) {
                var next = i + 1
                if (next < cps.size && cps[next] == VS16) next++
                if (next + 1 < cps.size && cps[next] == ZWJ && cps[next + 1] == SKULL_AND_CROSSBONES_CP) {
                    next += 2
                    if (next < cps.size && cps[next] == VS16) next++
                } else {
                    while (next < cps.size && cps[next] in TAG_START..TAG_END) {
                        next++
                    }
                }
                i = next
                continue
            }
            // White flag + optional ZWJ pride/trans
            if (cp == WHITE_FLAG_CP) {
                var next = i + 1
                if (next < cps.size && cps[next] == VS16) next++
                if (next + 1 < cps.size && cps[next] == ZWJ &&
                    (cps[next + 1] == RAINBOW_CP || cps[next + 1] == TRANS_SYMBOL_CP)
                ) {
                    next += 2
                    if (next < cps.size && cps[next] == VS16) next++
                }
                i = next
                continue
            }
            // Standalone flag emojis
            if (cp == CHEQUERED_FLAG_CP || cp == TRIANGULAR_FLAG_CP || cp == CROSSED_FLAGS_CP) {
                i++
                if (i < cps.size && cps[i] == VS16) i++
                continue
            }
            sb.appendCodePoint(cp)
            i++
        }
        return sb.toString()
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .trimStart('-', '•', '|', ':')
            .trim()
    }

    private fun toCodePoints(text: String): IntArray {
        val list = IntArray(text.codePointCount(0, text.length))
        var idx = 0
        var offset = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            list[idx++] = cp
            offset += Character.charCount(cp)
        }
        return list
    }

    private fun detectCountryFromHostTld(host: String): String? {
        val clean = host.trim().lowercase(Locale.US).trimEnd('.')
        val tld = clean.substringAfterLast('.', "")
        if (tld.length != 2) return null
        val upper = tld.uppercase(Locale.US)
        if (upper in EXCLUDED_GENERIC_TLDS) return null
        return if (upper in ALL_ISO_COUNTRIES) (if (upper == "UK") "GB" else upper) else null
    }

    fun detectCountryFromKeywords(raw: String): String? {
        val lower = raw.lowercase(Locale.ROOT)
        for ((keyword, code) in KEYWORD_TO_ISO) {
            if (containsWordOrSubstring(lower, keyword)) {
                return code
            }
        }
        // Check standalone 2-letter ISO code tokens like "(NL)", "[DE]", "FI-1", "US 2"
        val tokens = lower.split(Regex("[^a-zа-яё]+")).filter { it.isNotEmpty() }
        for (token in tokens) {
            val upper = token.uppercase(Locale.US)
            if (upper.length == 2 && upper in STANDALONE_ISO_CODES) {
                return if (upper == "UK") "GB" else upper
            }
        }
        return null
    }

    private fun containsWordOrSubstring(haystack: String, keyword: String): Boolean {
        val idx = haystack.indexOf(keyword)
        if (idx < 0) return false
        if (keyword.length >= 4) return true
        val beforeOk = idx == 0 || !haystack[idx - 1].isLetter()
        val afterIdx = idx + keyword.length
        val afterOk = afterIdx >= haystack.length || !haystack[afterIdx].isLetter()
        return beforeOk && afterOk
    }

    // Generic 2-letter ccTLDs heavily used globally as tech domains rather than server locations
    private val EXCLUDED_GENERIC_TLDS: Set<String> = setOf(
        "IO", "AI", "CO", "ME", "TV", "CC", "TO", "GG", "SH", "FM", "LY", "SO", "AC", "MS", "VC", "WS",
    )

    private val STANDALONE_ISO_CODES: Set<String> = setOf(
        "NL", "DE", "FR", "FI", "SE", "GB", "UK", "US", "CA", "PL", "CH", "AT", "CZ",
        "ES", "IT", "PT", "NO", "DK", "IE", "BE", "LU", "EE", "LV", "LT", "TR", "KZ",
        "JP", "SG", "KR", "HK", "TW", "AU", "NZ", "BR", "AR", "MX", "AE", "IL", "IN",
        "UA", "BY", "RU", "GE", "AM", "AZ", "MD", "RO", "BG", "RS", "HR", "SI", "SK",
        "HU", "GR", "CY", "IS", "ZA", "CL", "CO", "VN", "TH", "ID", "MY", "PH", "CN",
        "IR", "EG", "SA", "QA", "PK", "PE", "AL", "BA", "ME", "MK", "MT", "UZ", "KG",
        "TJ", "TM", "MN", "NP", "LK", "BD", "NG", "KE", "MA", "TN", "EU", "UN", "AQ",
    )

    private val KEYWORD_TO_ISO: List<Pair<String, String>> = listOf(
        // Special / fun keywords
        "pirate" to CODE_PIRATE, "пират" to CODE_PIRATE,
        "scotland" to CODE_SCOTLAND, "шотланд" to CODE_SCOTLAND,
        "wales" to CODE_WALES, "уэльс" to CODE_WALES,
        "antarctica" to "AQ", "антарктид" to "AQ",
        // Europe
        "netherlands" to "NL", "holland" to "NL", "amsterdam" to "NL", "нидерланд" to "NL", "голланд" to "NL", "амстердам" to "NL",
        "germany" to "DE", "deutschland" to "DE", "frankfurt" to "DE", "berlin" to "DE", "munich" to "DE", "nuremberg" to "DE", "falkenstein" to "DE", "герман" to "DE", "франкфурт" to "DE", "берлин" to "DE",
        "france" to "FR", "paris" to "FR", "marseille" to "FR", "strasbourg" to "FR", "франц" to "FR", "париж" to "FR",
        "finland" to "FI", "helsinki" to "FI", "финлянд" to "FI", "хельсинк" to "FI",
        "sweden" to "SE", "stockholm" to "SE", "швец" to "SE", "стокгольм" to "SE",
        "united kingdom" to "GB", "great britain" to "GB", "england" to "GB", "london" to "GB", "manchester" to "GB", "великобритан" to "GB", "англи" to "GB", "лондон" to "GB", "британ" to "GB",
        "switzerland" to "CH", "zurich" to "CH", "geneva" to "CH", "швейцар" to "CH", "цюрих" to "CH",
        "poland" to "PL", "warsaw" to "PL", "krakow" to "PL", "польш" to "PL", "варшав" to "PL",
        "austria" to "AT", "vienna" to "AT", "австри" to "AT", "вена" to "AT",
        "czech" to "CZ", "prague" to "CZ", "чехи" to "CZ", "праг" to "CZ",
        "spain" to "ES", "madrid" to "ES", "barcelona" to "ES", "испан" to "ES", "мадрид" to "ES",
        "italy" to "IT", "milan" to "IT", "rome" to "IT", "итал" to "IT", "милан" to "IT",
        "portugal" to "PT", "lisbon" to "PT", "португал" to "PT", "лиссабон" to "PT",
        "norway" to "NO", "oslo" to "NO", "норвег" to "NO", "осло" to "NO",
        "denmark" to "DK", "copenhagen" to "DK", "дани" to "DK", "копенгаген" to "DK",
        "ireland" to "IE", "dublin" to "IE", "ирланд" to "IE", "дублин" to "IE",
        "belgium" to "BE", "brussels" to "BE", "бельг" to "BE", "брюссел" to "BE",
        "luxembourg" to "LU", "люксембург" to "LU",
        "estonia" to "EE", "tallinn" to "EE", "эстон" to "EE", "таллин" to "EE",
        "latvia" to "LV", "riga" to "LV", "латв" to "LV", "рига" to "LV",
        "lithuania" to "LT", "vilnius" to "LT", "литв" to "LT", "вильнюс" to "LT",
        "serbia" to "RS", "belgrade" to "RS", "серб" to "RS", "белград" to "RS",
        "montenegro" to "ME", "черногор" to "ME",
        "croatia" to "HR", "zagreb" to "HR", "хорват" to "HR",
        "slovenia" to "SI", "ljubljana" to "SI", "словен" to "SI",
        "slovakia" to "SK", "bratislava" to "SK", "словак" to "SK",
        "hungary" to "HU", "budapest" to "HU", "венгр" to "HU", "будапешт" to "HU",
        "romania" to "RO", "bucharest" to "RO", "румын" to "RO",
        "bulgaria" to "BG", "sofia" to "BG", "болгар" to "BG",
        "moldova" to "MD", "chisinau" to "MD", "молдов" to "MD", "молдав" to "MD",
        "greece" to "GR", "athens" to "GR", "грец" to "GR", "афин" to "GR",
        "cyprus" to "CY", "limassol" to "CY", "кипр" to "CY",
        "iceland" to "IS", "reykjavik" to "IS", "исланд" to "IS",
        "ukraine" to "UA", "kyiv" to "UA", "украин" to "UA", "киев" to "UA",
        "belarus" to "BY", "minsk" to "BY", "беларус" to "BY", "минск" to "BY",
        "albania" to "AL", "албан" to "AL",
        "bosnia" to "BA", "босни" to "BA",
        "malta" to "MT", "мальт" to "MT",
        // Americas
        "united states" to "US", "usa" to "US", "america" to "US", "new york" to "US", "los angeles" to "US", "dallas" to "US", "miami" to "US", "chicago" to "US", "seattle" to "US", "ashburn" to "US", "silicon" to "US", "сша" to "US", "америк" to "US", "нью-йорк" to "US",
        "canada" to "CA", "toronto" to "CA", "montreal" to "CA", "vancouver" to "CA", "канад" to "CA", "торонто" to "CA",
        "brazil" to "BR", "sao paulo" to "BR", "бразил" to "BR",
        "mexico" to "MX", "мексик" to "MX",
        "argentina" to "AR", "аргентин" to "AR",
        "chile" to "CL", "чили" to "CL",
        "colombia" to "CO", "колумб" to "CO",
        "peru" to "PE", "перу" to "PE",
        // Asia / Middle East / CIS / Oceania / Africa
        "turkey" to "TR", "turkiye" to "TR", "istanbul" to "TR", "ankara" to "TR", "турц" to "TR", "стамбул" to "TR",
        "kazakhstan" to "KZ", "almaty" to "KZ", "astana" to "KZ", "казахстан" to "KZ", "алмат" to "KZ", "астан" to "KZ",
        "uzbekistan" to "UZ", "tashkent" to "UZ", "узбекистан" to "UZ", "ташкент" to "UZ",
        "kyrgyzstan" to "KG", "bishkek" to "KG", "кыргызстан" to "KG", "киргиз" to "KG",
        "armenia" to "AM", "yerevan" to "AM", "армен" to "AM", "ереван" to "AM",
        "georgia" to "GE", "tbilisi" to "GE", "груз" to "GE", "тбилис" to "GE",
        "azerbaijan" to "AZ", "baku" to "AZ", "азербайджан" to "AZ", "баку" to "AZ",
        "russia" to "RU", "moscow" to "RU", "petersburg" to "RU", "росси" to "RU", "москв" to "RU",
        "china" to "CN", "beijing" to "CN", "shanghai" to "CN", "кита" to "CN", "пекин" to "CN", "шанхай" to "CN",
        "iran" to "IR", "tehran" to "IR", "иран" to "IR", "тегеран" to "IR",
        "egypt" to "EG", "cairo" to "EG", "египет" to "EG", "каир" to "EG",
        "saudi" to "SA", "riyadh" to "SA", "саудов" to "SA",
        "qatar" to "QA", "doha" to "QA", "катар" to "QA",
        "uae" to "AE", "dubai" to "AE", "emirates" to "AE", "оаэ" to "AE", "дуба" to "AE",
        "israel" to "IL", "tel aviv" to "IL", "израил" to "IL",
        "japan" to "JP", "tokyo" to "JP", "osaka" to "JP", "япон" to "JP", "токио" to "JP",
        "singapore" to "SG", "сингапур" to "SG",
        "hong kong" to "HK", "hongkong" to "HK", "гонконг" to "HK",
        "south korea" to "KR", "korea" to "KR", "seoul" to "KR", "коре" to "KR", "сеул" to "KR",
        "taiwan" to "TW", "taipei" to "TW", "тайван" to "TW",
        "india" to "IN", "mumbai" to "IN", "инди" to "IN",
        "pakistan" to "PK", "пакистан" to "PK",
        "vietnam" to "VN", "hanoi" to "VN", "вьетнам" to "VN",
        "thailand" to "TH", "bangkok" to "TH", "таиланд" to "TH",
        "indonesia" to "ID", "jakarta" to "ID", "индонез" to "ID",
        "malaysia" to "MY", "малайз" to "MY",
        "philippines" to "PH", "manila" to "PH", "филиппин" to "PH",
        "australia" to "AU", "sydney" to "AU", "melbourne" to "AU", "австрал" to "AU", "сидней" to "AU",
        "new zealand" to "NZ", "новая зеланд" to "NZ",
        "south africa" to "ZA", "johannesburg" to "ZA", "юар" to "ZA",
    )

    /** Non-country symbolic/emoji flags that trigger the Mystery "?" Glass Shatter reveal. */
    val SPECIAL_NON_COUNTRY_CODES: Set<String> = setOf(
        CODE_PIRATE,
        CODE_PRIDE,
        CODE_TRANS,
        CODE_CHECKERED,
        CODE_WHITE_FLAG,
        CODE_BLACK_FLAG,
        CODE_RED_FLAG,
    )

    /** Returns true if [code] represents a non-sovereign symbolic emoji flag (e.g., Pirate, Pride, Checkered). */
    fun isSpecialNonCountryFlag(code: String?): Boolean {
        val normalized = code?.trim()?.uppercase() ?: return false
        return normalized in SPECIAL_NON_COUNTRY_CODES
    }

    /**
     * Full sovereign country ISO-3166-1 alpha-2 catalog (~195 UN states) used to populate
     * the infinite non-repeating 2D World Flag Matrix during transitions.
     * Strictly excludes all symbolic / non-country emoji flags (Pirate, Pride, Trans, Checkered, etc.).
     */
    val WORLD_MATRIX_COUNTRY_CODES: List<String> = listOf(
        // Europe
        "AL", "AD", "AT", "BY", "BE", "BA", "BG", "HR", "CY", "CZ",
        "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IS", "IE", "IT",
        "LV", "LI", "LT", "LU", "MT", "MD", "MC", "ME", "NL", "MK",
        "NO", "PL", "PT", "RO", "RU", "SM", "RS", "SK", "SI", "ES",
        "SE", "CH", "UA", "GB", "VA",
        // Asia & Middle East
        "AF", "AM", "AZ", "BH", "BD", "BT", "BN", "KH", "CN", "GE", "HK",
        "IN", "ID", "IR", "IQ", "IL", "JP", "JO", "KZ", "KW", "KG",
        "LA", "LB", "MY", "MV", "MN", "MM", "NP", "KP", "OM", "PK",
        "PS", "PH", "QA", "SA", "SG", "KR", "LK", "SY", "TW", "TJ",
        "TH", "TL", "TR", "TM", "AE", "UZ", "VN", "YE",
        // Americas
        "AG", "AR", "BS", "BB", "BZ", "BO", "BR", "CA", "CL", "CO",
        "CR", "CU", "DM", "DO", "EC", "SV", "GD", "GT", "GY", "HT",
        "HN", "JM", "MX", "NI", "PA", "PY", "PE", "KN", "LC", "VC",
        "SR", "TT", "US", "UY", "VE",
        // Africa
        "DZ", "AO", "BJ", "BW", "BF", "BI", "CV", "CM", "CF", "TD",
        "KM", "CG", "CD", "CI", "DJ", "EG", "GQ", "ER", "SZ", "ET",
        "GA", "GM", "GH", "GN", "GW", "KE", "LS", "LR", "LY", "MG",
        "MW", "ML", "MR", "MU", "MA", "MZ", "NA", "NE", "NG", "RW",
        "ST", "SN", "SC", "SL", "SO", "ZA", "SS", "SD", "TZ", "TG",
        "TN", "UG", "ZM", "ZW",
        // Oceania
        "AU", "FJ", "KI", "MH", "FM", "NR", "NZ", "PW", "PG", "WS",
        "SB", "TO", "TV", "VU",
    )
}


