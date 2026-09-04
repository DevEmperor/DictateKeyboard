/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.text.keyboard

/**
 * Decides whether a Devanagari consonant is sitting directly in front of the cursor, waiting for a vowel
 * sign (issue #315).
 *
 * This is what turns the vowel row into a matra row: with a base pending, the अ आ इ … keys show and emit
 * ा ि ी … instead, so the user sees का कि की on the keys. Nothing about the composition itself changes —
 * consonant followed by matra is plain Unicode order — this only decides which face the keys wear.
 *
 * Deliberately free of Android types so the rule can be unit-tested on the JVM.
 */
object DevanagariBase {
    /** No consonant is pending; the vowel keys show independent vowels. */
    const val NONE: Int = 0

    private const val CONSONANT_FIRST = 0x0915 // क
    private const val CONSONANT_LAST = 0x0939 // ह
    private const val NUKTA_CONSONANT_FIRST = 0x0958 // क़
    private const val NUKTA_CONSONANT_LAST = 0x095F // य़
    private const val NUKTA = 0x093C

    /**
     * Returns the consonant immediately before the cursor, or [NONE].
     *
     * A trailing nukta is skipped, so क़ still counts as a base. Everything else — a matra, virama,
     * anusvara, punctuation, whitespace, a digit, Latin text, or an empty field — yields [NONE]: after
     * कि the vowel keys must go back to showing vowels, because a second matra never follows a first.
     */
    fun of(textBeforeCursor: CharSequence): Int {
        var end = textBeforeCursor.length
        if (end == 0) return NONE
        if (textBeforeCursor[end - 1].code == NUKTA) {
            end--
            if (end == 0) return NONE
        }
        val codePoint = codePointBefore(textBeforeCursor, end)
        return if (isConsonant(codePoint)) codePoint else NONE
    }

    /** True for the 33 base consonants plus the seven precomposed nukta letters. */
    fun isConsonant(codePoint: Int): Boolean {
        return codePoint in CONSONANT_FIRST..CONSONANT_LAST ||
            codePoint in NUKTA_CONSONANT_FIRST..NUKTA_CONSONANT_LAST
    }

    private fun codePointBefore(text: CharSequence, end: Int): Int {
        val low = text[end - 1]
        if (end >= 2 && low.isLowSurrogate()) {
            val high = text[end - 2]
            if (high.isHighSurrogate()) {
                return Character.toCodePoint(high, low)
            }
        }
        return low.code
    }
}
