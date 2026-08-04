/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

import java.util.Locale

/**
 * A dictation language the user can transcribe in. [code] is the language hint sent to the speech
 * provider (ISO-639-1, or a BCP-47 tag for regional variants such as `zh-CN`); the special value
 * [DictateLanguages.DETECT] means "let the model auto-detect" and is never sent to the API.
 */
data class DictateLanguage(val code: String, val englishName: String) {
    /** Short uppercase badge for the smartbar chip, e.g. `DE`, `EN`, `ZH`, `YUE`. */
    val shortCode: String
        get() = code.substringBefore('-').uppercase(Locale.ROOT)

    /**
     * Human-readable name, localized to the device language when possible and falling back to the
     * bundled English name. [DictateLanguages.DETECT] is special-cased by callers (globe icon), so
     * this returns its English label.
     */
    fun displayName(): String {
        if (code == DictateLanguages.DETECT) return englishName
        val localized = Locale.forLanguageTag(code).getDisplayName(Locale.getDefault())
        // A code Android has no name for comes back as the code itself, which would put "Jw" or "Haw" in
        // the picker. Anything that short is the tag, not a language.
        val usable = localized.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
        return (usable ?: englishName).replaceFirstChar { it.uppercase(Locale.getDefault()) }
    }
}

/**
 * Catalog of the dictation languages ported 1:1 from the legacy Dictate app's
 * `dictate_input_languages` arrays. Users pick a subset in settings (stored comma-separated in
 * `prefs.dictate.inputLanguages`) and cycle through it on the recording bar.
 */
object DictateLanguages {
    const val DETECT = "detect"

    val all: List<DictateLanguage> = listOf(
        // Stays first: `of` and `parseSelection` fall back to `all.first()` meaning "let the model decide".
        DictateLanguage(DETECT, "Detect automatically"),
        DictateLanguage("af", "Afrikaans"),
        DictateLanguage("sq", "Albanian"),
        DictateLanguage("am", "Amharic"),
        DictateLanguage("ar", "Arabic"),
        DictateLanguage("hy", "Armenian"),
        DictateLanguage("as", "Assamese"),
        DictateLanguage("az", "Azerbaijani"),
        DictateLanguage("ba", "Bashkir"),
        DictateLanguage("eu", "Basque"),
        DictateLanguage("be", "Belarusian"),
        DictateLanguage("bn", "Bengali"),
        DictateLanguage("bs", "Bosnian"),
        DictateLanguage("br", "Breton"),
        DictateLanguage("bg", "Bulgarian"),
        DictateLanguage("my", "Burmese"),
        DictateLanguage("yue-CN", "Cantonese (CN)"),
        DictateLanguage("yue-HK", "Cantonese (HK)"),
        DictateLanguage("ca", "Catalan"),
        DictateLanguage("hr", "Croatian"),
        DictateLanguage("cs", "Czech"),
        DictateLanguage("da", "Danish"),
        DictateLanguage("nl", "Dutch"),
        DictateLanguage("en", "English"),
        DictateLanguage("et", "Estonian"),
        DictateLanguage("fo", "Faroese"),
        DictateLanguage("fi", "Finnish"),
        DictateLanguage("fr", "French"),
        DictateLanguage("gl", "Galician"),
        DictateLanguage("ka", "Georgian"),
        DictateLanguage("de", "German"),
        DictateLanguage("el", "Greek"),
        DictateLanguage("gu", "Gujarati"),
        DictateLanguage("ht", "Haitian Creole"),
        DictateLanguage("ha", "Hausa"),
        DictateLanguage("haw", "Hawaiian"),
        DictateLanguage("he", "Hebrew"),
        DictateLanguage("hi", "Hindi"),
        DictateLanguage("hu", "Hungarian"),
        DictateLanguage("is", "Icelandic"),
        DictateLanguage("id", "Indonesian"),
        DictateLanguage("it", "Italian"),
        DictateLanguage("ja", "Japanese"),
        DictateLanguage("jw", "Javanese"),
        DictateLanguage("kn", "Kannada"),
        DictateLanguage("kk", "Kazakh"),
        DictateLanguage("km", "Khmer"),
        DictateLanguage("ko", "Korean"),
        DictateLanguage("lo", "Lao"),
        DictateLanguage("la", "Latin"),
        DictateLanguage("lv", "Latvian"),
        DictateLanguage("ln", "Lingala"),
        DictateLanguage("lt", "Lithuanian"),
        DictateLanguage("lb", "Luxembourgish"),
        DictateLanguage("mk", "Macedonian"),
        DictateLanguage("mg", "Malagasy"),
        DictateLanguage("ms", "Malay"),
        DictateLanguage("ml", "Malayalam"),
        DictateLanguage("mt", "Maltese"),
        DictateLanguage("zh-CN", "Mandarin (CN)"),
        DictateLanguage("zh-TW", "Mandarin (TW)"),
        DictateLanguage("mi", "Maori"),
        DictateLanguage("mr", "Marathi"),
        DictateLanguage("mn", "Mongolian"),
        DictateLanguage("ne", "Nepali"),
        DictateLanguage("no", "Norwegian"),
        DictateLanguage("nn", "Nynorsk"),
        DictateLanguage("oc", "Occitan"),
        DictateLanguage("ps", "Pashto"),
        DictateLanguage("fa", "Persian"),
        DictateLanguage("pl", "Polish"),
        DictateLanguage("pt", "Portuguese"),
        DictateLanguage("pa", "Punjabi"),
        DictateLanguage("ro", "Romanian"),
        DictateLanguage("ru", "Russian"),
        DictateLanguage("sa", "Sanskrit"),
        DictateLanguage("sr", "Serbian"),
        DictateLanguage("sn", "Shona"),
        DictateLanguage("sd", "Sindhi"),
        DictateLanguage("si", "Sinhala"),
        DictateLanguage("sk", "Slovak"),
        DictateLanguage("sl", "Slovenian"),
        DictateLanguage("so", "Somali"),
        DictateLanguage("es", "Spanish"),
        DictateLanguage("su", "Sundanese"),
        DictateLanguage("sw", "Swahili"),
        DictateLanguage("sv", "Swedish"),
        DictateLanguage("tl", "Tagalog"),
        DictateLanguage("tg", "Tajik"),
        DictateLanguage("ta", "Tamil"),
        DictateLanguage("tt", "Tatar"),
        DictateLanguage("te", "Telugu"),
        DictateLanguage("th", "Thai"),
        DictateLanguage("bo", "Tibetan"),
        DictateLanguage("tr", "Turkish"),
        DictateLanguage("tk", "Turkmen"),
        DictateLanguage("uk", "Ukrainian"),
        DictateLanguage("ur", "Urdu"),
        DictateLanguage("uz", "Uzbek"),
        DictateLanguage("vi", "Vietnamese"),
        DictateLanguage("cy", "Welsh"),
        DictateLanguage("yi", "Yiddish"),
        DictateLanguage("yo", "Yoruba"),
    )

    private val byCode: Map<String, DictateLanguage> = all.associateBy { it.code }

    /** Resolves a code to its [DictateLanguage], falling back to "detect" for unknown codes. */
    fun of(code: String): DictateLanguage = byCode[code] ?: all.first()

    /**
     * Finds the dictation language matching a device [locale] (e.g. the system language), or `null`
     * when none of the supported languages correspond to it. The full BCP-47 tag is tried first so
     * regional variants such as `zh-CN` / `zh-TW` resolve correctly, then the base language is used
     * as a fallback. [DETECT] is never returned.
     */
    fun matchDevice(locale: Locale): DictateLanguage? {
        val tag = locale.toLanguageTag().lowercase(Locale.ROOT)
        all.firstOrNull { it.code != DETECT && it.code.lowercase(Locale.ROOT) == tag }?.let { return it }
        val base = locale.language.lowercase(Locale.ROOT)
        if (base.isEmpty()) return null
        return all.firstOrNull {
            it.code != DETECT && it.code.substringBefore('-').lowercase(Locale.ROOT) == base
        }
    }

    /**
     * English language name for [code] (e.g. `"German"`), used as the auto-formatting *language hint*.
     * Returns `null` for [DETECT], blank, or unknown codes so the caller can substitute "unknown" – a
     * readable name guides the model far better than the bare ISO code.
     */
    fun englishNameFor(code: String?): String? {
        if (code.isNullOrEmpty() || code == DETECT) return null
        return byCode[code]?.englishName
    }

    /** Parses the comma-separated [prefs.dictate.inputLanguages] value into a sanitized subset. */
    fun parseSelection(raw: String): List<DictateLanguage> {
        val parsed = raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { byCode[it] }
            .distinct()
        return parsed.ifEmpty { listOf(all.first()) }
    }

    /** Serializes a subset back into the comma-separated pref format. */
    fun serializeSelection(languages: List<DictateLanguage>): String =
        languages.joinToString(",") { it.code }
}
