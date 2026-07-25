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
 *    mid-sentence, never at the very end, and never inside a decimal/abbreviation like `3.14` / `e.g.`
 *    (those have no space after the dot).
 *  - An existing newline in the source is treated as a fresh paragraph, so the word count restarts there.
 *  - Contractions and hyphenated words (`don't`, `well-known`) count as a single word.
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
                    // Break only when there was whitespace (rules out 3.14 / e.g.), more text follows, and
                    // that text isn't already the start of a new line.
                    val breakHere = wordCount >= minWords && k > j && k < n && text[k] != '\n'
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
