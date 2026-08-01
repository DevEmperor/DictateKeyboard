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

/**
 * Deterministic paragraph splitter for long *plain* transcripts (issue #225): once at least [minWords]
 * words have accumulated, the next sentence end starts a new paragraph (a blank line). No model, no topic
 * detection — a single pass over the string.
 *
 * Rules:
 *  - Breaks only right after sentence-ending punctuation (`.`, `!`, `?`, `…`, and runs thereof) plus any
 *    trailing closing quotes/brackets, and only when followed by whitespace and more text — never
 *    mid-sentence and never at the very end.
 *  - A dot that only *looks* like a sentence end never breaks (issue #239): decimals (`3.14`, no space
 *    after the dot), ordinals and dates (`29. Juli`), initials (`J. R. R. Tolkien`) and known
 *    abbreviations (`usw.`, `z.B.`, `e.g.`, `Dr.`).
 *  - An existing newline in the source is treated as a fresh paragraph, so the word count restarts there.
 *  - Contractions and hyphenated words (`don't`, `well-known`) count as a single word.
 *
 * The suppression rules are deliberately one-sided. Missing a real sentence end only makes one paragraph
 * longer — the next sentence end breaks instead — whereas a wrong break cuts a sentence in half in text
 * the user cannot easily repair.
 *
 * This is applied only to a pure transcript (no rewording / auto-format pass changed the text) — AI output
 * already carries its own paragraphing. [minWords] `<= 0` is off and returns the text unchanged.
 */
object TranscriptParagraphs {

    private fun isSentenceEnd(c: Char): Boolean = c == '.' || c == '!' || c == '?' || c == '…' // …

    /** Closing punctuation that can trail a sentence end: quotes and brackets. */
    private fun isTrailing(c: Char): Boolean = when (c) {
        '"', '\'', '’', '”', '»', ')', ']', '}' -> true // " ' ’ ” » ) ] }
        else -> false
    }

    /** Characters that glue a word together (so contractions/hyphenated words count once), mid-word only. */
    private fun isWordGlue(c: Char): Boolean = c == '\'' || c == '-' || c == '’' // ' - ’

    /**
     * Words that routinely end in a dot without ending a sentence (issue #239), stored lowercase and
     * *without* the trailing dot. German and English are combined rather than selected per language: a
     * dictation switches language freely, and a wrong entry only ever costs a longer paragraph.
     *
     * Single-letter entries are deliberately absent — `s.`, `f.`, `u.` are real abbreviations, but a
     * lower-case single letter far more often genuinely ends a sentence. Initials are covered by the
     * capital-letter rule instead. `no.` is left out for the same reason: "the answer is no." is common.
     */
    private val ABBREVIATIONS: Set<String> = setOf(
        // German
        "usw", "bzw", "z.b", "u.a", "d.h", "i.d.r", "v.a", "z.t", "o.g", "u.g", "ca", "nr", "abs", "bspw",
        "evtl", "ggf", "inkl", "exkl", "mind", "sog", "vgl", "zzgl", "abzgl", "geb", "gest", "bzgl", "o.ä",
        "u.ä", "ähnl", "eigtl", "urspr", "jew", "insb", "hrsg", "erw",
        // English
        "etc", "e.g", "i.e", "vs", "approx", "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "inc",
        "ltd", "co", "dept", "est", "fig", "vol", "pp", "ed", "cf", "al", "incl", "min", "max", "sec",
        "dept", "univ", "assn", "misc", "orig", "esp",
    )

    /**
     * True when the dot at [dotIndex] belongs to something other than a sentence end, judged from the
     * whitespace-delimited token in front of it: an ordinal or date (`29.`), an initial (`J.`), or a known
     * abbreviation (`usw.`, `z.B.`).
     */
    private fun isNonBreakingDot(text: String, dotIndex: Int): Boolean {
        var start = dotIndex
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        // Drop leading punctuation so a bracketed or quoted abbreviation still matches — `(e.g.`, `"Dr.`.
        while (start < dotIndex && !text[start].isLetterOrDigit()) start++
        if (start >= dotIndex) return false
        val token = text.substring(start, dotIndex)
        // Ordinals and dates: "29. Juli", "3. Platz".
        if (token.all { it.isDigit() }) return true
        // Initials: a single capital letter, as in "J. R. R. Tolkien". Lower case is left alone on purpose,
        // so a sentence genuinely ending in a single letter still breaks.
        if (token.length == 1 && token[0].isUpperCase()) return true
        return token.lowercase() in ABBREVIATIONS
    }

    fun split(text: String, minWords: Int): String {
        if (minWords <= 0 || text.length < 2) return text
        val sb = StringBuilder(text.length + 16)
        val n = text.length
        var wordCount = 0
        var inWord = false
        var i = 0
        while (i < n) {
            val c = text[i]
            when {
                c == '\n' -> {
                    // Existing paragraph boundary: keep it, restart the word count.
                    sb.append(c)
                    wordCount = 0
                    inWord = false
                    i++
                }
                c.isLetterOrDigit() || (isWordGlue(c) && inWord) -> {
                    if (!inWord) {
                        inWord = true
                        wordCount++
                    }
                    sb.append(c)
                    i++
                }
                isSentenceEnd(c) -> {
                    inWord = false
                    // Consume the full punctuation run + any trailing quotes/brackets.
                    var j = i
                    while (j < n && (isSentenceEnd(text[j]) || isTrailing(text[j]))) j++
                    sb.append(text, i, j)
                    // Skip inline whitespace (spaces/tabs) after the punctuation.
                    var k = j
                    while (k < n && (text[k] == ' ' || text[k] == '\t')) k++
                    // Break only when there was whitespace (rules out decimals like 3.14), more text
                    // follows, that text isn't already the start of a new line, and the dot actually ends a
                    // sentence rather than an abbreviation, ordinal or initial (issue #239).
                    // A run of several enders ("..." / "?!") is always a real sentence end; only a lone dot
                    // can be an abbreviation.
                    val loneDot = c == '.' && (i + 1 until j).none { isSentenceEnd(text[it]) }
                    val breakHere = wordCount >= minWords && k > j && k < n && text[k] != '\n' &&
                        !(loneDot && isNonBreakingDot(text, i))
                    if (breakHere) {
                        sb.append("\n\n")
                        wordCount = 0
                    } else {
                        sb.append(text, j, k) // keep the original whitespace
                    }
                    i = k
                }
                else -> {
                    inWord = false
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }
}
