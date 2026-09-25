/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.translate

/**
 * A flag beside each translation language, so a list of 49 names can be scanned by eye (issue #424).
 *
 * A language is not a country, so this is a choice of *which* country stands for it, made once here:
 * the country most people would picture. Where the user's own phone is set to a region of that very
 * language, that region wins — German reads 🇦🇹 on an Austrian phone, English 🇺🇸 on an American one,
 * Portuguese 🇧🇷 on a Brazilian one — because the flag is there to say "your language", not to rule on
 * whose it is.
 *
 * Catalan, Basque and Galician get none. The only country flag on offer would be Spain's, which is
 * exactly the wrong answer to hand those three, and their own flags have no emoji; they get a code
 * badge instead (see [NO_FLAG]).
 */
object TranslationFlags {
    /** Languages of a region without an emoji flag of its own, shown with their code instead. */
    val NO_FLAG: Set<String> = setOf("ca", "eu", "gl")

    private val COUNTRIES = mapOf(
        "en" to "GB",
        "af" to "ZA", "ar" to "SA", "bg" to "BG", "bn" to "BD", "cs" to "CZ", "da" to "DK", "de" to "DE",
        "el" to "GR", "es" to "ES", "et" to "EE", "fa" to "IR", "fi" to "FI", "fr" to "FR", "gu" to "IN",
        "he" to "IL", "hi" to "IN", "hu" to "HU", "id" to "ID", "is" to "IS", "it" to "IT", "ja" to "JP",
        "kn" to "IN", "ko" to "KR", "lt" to "LT", "lv" to "LV", "ml" to "IN", "mr" to "IN", "ms" to "MY",
        "nb" to "NO", "nl" to "NL", "pl" to "PL", "pt" to "PT", "ro" to "RO", "ru" to "RU", "sk" to "SK",
        "sl" to "SI", "sv" to "SE", "ta" to "IN", "te" to "IN", "th" to "TH", "tr" to "TR", "uk" to "UA",
        "ur" to "PK", "vi" to "VN", "zh" to "CN", "zh_hant" to "TW",
    )

    /**
     * The ISO country whose flag stands for [code], or `null` for a language in [NO_FLAG] (or one this
     * table does not know). [deviceLanguageTag] is the phone's own locale, e.g. `de-AT`.
     */
    fun countryFor(code: String, deviceLanguageTag: String? = null): String? {
        if (code in NO_FLAG) return null
        if (deviceLanguageTag != null && TranslationCatalog.codeFor(deviceLanguageTag) == code) {
            val region = deviceLanguageTag.replace('_', '-').split('-').drop(1)
                .firstOrNull { it.length == 2 && it.all(Char::isLetter) }
            if (region != null) return region.uppercase()
        }
        return COUNTRIES[code]
    }

    /** The emoji flag of an ISO 3166-1 alpha-2 [country]: two regional-indicator letters. */
    fun emoji(country: String): String = buildString {
        for (letter in country.uppercase()) appendCodePoint(REGIONAL_INDICATOR_A + (letter - 'A'))
    }

    private const val REGIONAL_INDICATOR_A = 0x1F1E6
}
