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
 * One file of a translation model, as it is served (a `.gz` release asset) and as it is installed
 * (unpacked, under [fileName]).
 *
 * [fileName] keeps Mozilla's own name because Marian picks its loader by the extension — a model saved
 * as plain `model` fails with "Unknown extension for model", which is how that was learned (#424).
 */
data class TranslationModelFile(
    val role: Role,
    val fileName: String,
    val url: String,
    val downloadBytes: Long,
    val installedBytes: Long,
    val sha256: String,
) {
    enum class Role { MODEL, SHORTLIST, VOCAB, SOURCE_VOCAB, TARGET_VOCAB }
}

/** A model translating [from] → [to]. One side is always English: Mozilla trains no other pairs. */
data class TranslationDirection(
    val from: String,
    val to: String,
    val architecture: String,
    val files: List<TranslationModelFile>,
) {
    val id: String get() = "$from-$to"
    val downloadBytes: Long get() = files.sumOf { it.downloadBytes }
    val installedBytes: Long get() = files.sumOf { it.installedBytes }

    fun file(role: TranslationModelFile.Role): TranslationModelFile? = files.firstOrNull { it.role == role }
}

/**
 * A language the translator can be taught — both of its directions, because that is what the user gets
 * to choose: a language in which text can be translated *from* and *to*. Downloaded and removed as one.
 */
data class TranslationLanguage(
    val code: String,
    val toEnglish: TranslationDirection,
    val fromEnglish: TranslationDirection,
) {
    val directions: List<TranslationDirection> get() = listOf(toEnglish, fromEnglish)
    val downloadBytes: Long get() = toEnglish.downloadBytes + fromEnglish.downloadBytes
    val installedBytes: Long get() = toEnglish.installedBytes + fromEnglish.installedBytes

    /** Mozilla's code as a BCP-47 tag, for display names and for matching a detected language. */
    val languageTag: String get() = code.replace('_', '-')
}

/**
 * The on-device translator's languages (issue #424): Mozilla's Firefox Translations models, mirrored
 * to a release of this repository by `tools/bergamot/prepare_models.py`, which also generates the list.
 *
 * English is the hub. It needs no download of its own — every language's pack already carries the
 * directions to and from it — and every pair without English on one side runs through it in two steps.
 */
object TranslationCatalog {
    const val ENGLISH = "en"

    val languages: List<TranslationLanguage> get() = TRANSLATION_LANGUAGES

    fun language(code: String): TranslationLanguage? = languages.firstOrNull { it.code == code }

    /** Every code a user can pick: English plus the catalog. */
    val codes: List<String> get() = listOf(ENGLISH) + languages.map { it.code }

    /**
     * The models translating [from] → [to], in the order they run: none for the same language, one when
     * English is on either side, two through English otherwise. `null` when either side is a language
     * the catalog does not have.
     */
    fun route(from: String, to: String): List<TranslationDirection>? {
        if (from == to) return emptyList()
        return when {
            from == ENGLISH -> language(to)?.let { listOf(it.fromEnglish) }
            to == ENGLISH -> language(from)?.let { listOf(it.toEnglish) }
            else -> {
                val source = language(from) ?: return null
                val target = language(to) ?: return null
                listOf(source.toEnglish, target.fromEnglish)
            }
        }
    }

    /** The languages that have to be downloaded for [from] → [to]; English never is. */
    fun requiredLanguages(from: String, to: String): Set<String> =
        setOf(from, to) - ENGLISH

    /**
     * The catalog code for a detected or system [languageTag], or `null` when the catalog has none.
     *
     * Exact tag first, so Traditional Chinese finds `zh_hant`; then the bare language, so `pt-BR` finds
     * Portuguese and `nb-NO` finds Bokmål. A script only falls back to the bare language when it is the
     * script that language is written in by default: `zh-Hans` is the plain `zh` model, but `zh-Hant`
     * must never be translated with the Simplified one just because both are "zh".
     */
    fun codeFor(languageTag: String): String? {
        val parts = languageTag.replace('_', '-').split('-').filter { it.isNotEmpty() }
        val language = parts.firstOrNull()?.lowercase() ?: return null
        if (language == ENGLISH) return ENGLISH
        ALIASES[language]?.let { return it }
        val script = parts.drop(1).firstOrNull { it.length == 4 }?.lowercase()
        if (script != null) {
            languages.firstOrNull { it.languageTag.equals("$language-$script", ignoreCase = true) }?.let { return it.code }
            // Only matters where the catalog splits a language by script; elsewhere "hi-Deva" is just Hindi.
            val splitByScript = languages.any { it.languageTag.startsWith("$language-", ignoreCase = true) }
            if (splitByScript && DEFAULT_SCRIPTS[language] != script) return null
        }
        return language(language)?.code
    }

    /** The script a bare language code implies, where the catalog also carries another one. */
    private val DEFAULT_SCRIPTS = mapOf("zh" to "hans")

    /**
     * Codes Mozilla publishes a model under that is byte for byte another code's: its "no" (Norwegian)
     * is the "nb" (Bokmål) model, so the catalog carries it once and a detected "no" lands there.
     */
    private val ALIASES = mapOf("no" to "nb")
}
