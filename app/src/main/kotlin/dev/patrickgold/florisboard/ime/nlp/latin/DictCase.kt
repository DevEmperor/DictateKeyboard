/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp.latin

/**
 * The share of a word list's spellings that must start with a capital before the list is trusted on case.
 *
 * Every list that was cased at all is far above it: Spanish, the lowest, writes 0.5 % of its words with a
 * capital, German almost half. The two below it were built without casing and caught a handful of capitals
 * from their corpus: Estonian 15 of 78,742 (Ameerikas, Microsoft), Vietnamese 5 of 13,314 (Nguyễn, Phan).
 */
internal const val KNOWS_CASE_SHARE = 0.001

/**
 * Whether a word list with these [spellings] writes words with a capital where they take one, so that a word
 * it stores small is small for a reason (#476). A dictation that opens in the middle of a sentence has its first
 * letter lowered only on that word; a list that does not know case leaves the speech service's capital alone.
 *
 * Any capital at all used to be enough, and the few that Estonian and Vietnamese caught made every name in them
 * count as a small word: Eesti, Jüri, Việt, Hà Nội came out small mid-sentence (#480). Vietnamese could not be
 * helped by its list anyway, because each syllable of a name is an ordinary word too.
 */
internal fun listKnowsCase(spellings: Collection<String>): Boolean {
    val capitals = spellings.count { it.firstOrNull()?.isUpperCase() == true }
    return capitals > 0 && capitals >= spellings.size * KNOWS_CASE_SHARE
}
