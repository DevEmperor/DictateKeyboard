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

import dev.patrickgold.florisboard.dictate.DictateLanguages
import java.text.Collator
import java.util.Locale

/**
 * Names for the translator's languages, from the system in the user's language — so no string here
 * needs translating, and a new language in the catalog costs nothing (issue #424).
 */
object TranslationLanguageNames {
    fun of(code: String): String = when (code) {
        // The catalog carries Chinese in both scripts, and "Chinesisch" twice would be no choice at all.
        "zh" -> withScript("zh-Hans")
        "zh_hant" -> withScript("zh-Hant")
        else -> DictateLanguages.displayNameOf(code.replace('_', '-'))
    }

    /** [codes] sorted by their names as the user reads them. */
    fun sorted(codes: Collection<String>): List<String> {
        val collator = Collator.getInstance(Locale.getDefault())
        return codes.sortedWith { a, b -> collator.compare(of(a), of(b)) }
    }

    private fun withScript(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault())
            .replaceFirstChar { it.uppercase(Locale.getDefault()) }
}

/** The flag emoji standing for [code] on this phone (see [TranslationFlags]), or `null` where there is none. */
fun translationFlagOf(code: String): String? =
    TranslationFlags.countryFor(code, Locale.getDefault().toLanguageTag())?.let(TranslationFlags::emoji)
