/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.provider

/**
 * Whether a provider's cleaned-up transcript is still the dictation (#478).
 *
 * A cleanup takes words out (fillers, false starts, the half of a sentence the speaker corrected) and sets
 * punctuation and capitals; it has no words of its own to bring. A language model that answers the dictation,
 * does what it asks or translates it writes mostly words nobody said, which is what #469 measured in 44 of 144
 * rewordings before their text was framed. So a cleaned text is taken only when nearly every word in it was in
 * the transcript; otherwise the transcript itself is, and the user gets what they said rather than something
 * else.
 *
 * Words are compared in lower case, letters, digits and combining marks only, so the punctuation, capitals and
 * currency signs a cleanup moves do not count. Scripts written without spaces (Chinese, Japanese, Thai…) count
 * each character as a word. A quarter of the words may be new, for the joins a cleanup makes ("Friday works
 * better" → "That works better."); an answer of one to three words must consist of said words entirely.
 *
 * It cannot tell an answer made only of said words ("Yes." to "Reply with yes or no.") from a cleanup. That one
 * is left to the provider; AssemblyAI answered none of the 28 questions, requests and injections dictated to it
 * (2026-11-11).
 */
internal object CleanupGuard {

    /** Whether [cleaned] may stand for [transcript]: some words of its own, and no more than a quarter. */
    fun keepsTheWords(transcript: String, cleaned: String): Boolean {
        val written = wordsOf(cleaned)
        if (written.isEmpty()) return false
        val said = wordsOf(transcript).toHashSet()
        return written.count { it !in said } <= written.size / 4
    }

    internal fun wordsOf(text: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) words += current.toString()
            current.setLength(0)
        }
        val lower = text.lowercase()
        var i = 0
        while (i < lower.length) {
            val cp = lower.codePointAt(i)
            when {
                Character.isLetterOrDigit(cp) && writtenWithoutSpaces(cp) -> {
                    flush()
                    words += String(Character.toChars(cp))
                }
                // Marks belong to the letter before them: a Hindi vowel sign is not a word break.
                Character.isLetterOrDigit(cp) || Character.getType(cp).let {
                    it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
                } -> current.appendCodePoint(cp)
                else -> flush()
            }
            i += Character.charCount(cp)
        }
        flush()
        return words
    }

    private fun writtenWithoutSpaces(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR -> true
        else -> false
    }
}
