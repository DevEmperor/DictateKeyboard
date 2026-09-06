/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.smartbar

import java.text.BreakIterator

/**
 * Counting what is selected (issue #335).
 *
 * Kept apart from the UI so the two decisions in here can be read and tested on their own: what counts as
 * a character, and what counts as a word. Neither is obvious, and getting either wrong shows up as a
 * number the user can see is false.
 */
object SelectionMetrics {

    /**
     * Above this many characters the exact count gives way to the cheap one.
     *
     * Selecting everything in a long document is one tap, and the count then runs over the whole thing.
     * The break iterators walk the text properly, which is what makes them right and also what makes them
     * too slow at that size; past this mark the difference between the two methods is a handful of counts
     * in a five-digit number, which nobody reads, while the delay would be plainly visible.
     */
    internal const val MaxAnalyzedChars = 20_000

    /** What the strip shows. [words] is `null` when the text was not available to count. */
    data class Counts(val words: Int?, val chars: Int)

    val Empty = Counts(words = 0, chars = 0)

    /**
     * Counts the words and characters in [text].
     *
     * Characters are **grapheme clusters**, not `length`: an emoji is two UTF-16 units and a flag or a
     * skin-toned emoji is more still, so `length` would report a number roughly twice what someone
     * looking at their own text would count. The same applies to Indic syllables and to combining marks.
     *
     * Words come from the word iterator rather than from splitting at spaces, because Chinese, Japanese
     * and Thai write without them — splitting there returns 1 for a whole paragraph. A segment counts as
     * a word when it holds at least one letter or digit, which keeps punctuation and the spaces
     * themselves out of the total.
     *
     * How well that works for those scripts is the platform's doing: on Android these iterators are
     * ICU-backed and segment by dictionary, while a plain JVM (where the unit tests run) hands back a
     * Chinese sentence as a single word. Hence the tests pin the character count there and only require
     * the word count to be sane.
     */
    fun of(text: CharSequence): Counts {
        if (text.isEmpty()) return Empty
        val string = text.toString()
        if (string.length > MaxAnalyzedChars) return approximate(string)
        return Counts(words = countWords(string), chars = countGraphemes(string))
    }

    /**
     * The count for a selection whose text the editor would not hand over — the app answered
     * `getSelectedText` with nothing, or the selection was made in one sweep and only its size is known.
     *
     * Then the length is all there is. It is reported as characters and the word count is left out
     * entirely rather than guessed: a wrong number is worse than a missing one.
     */
    fun charsOnly(length: Int): Counts = Counts(words = null, chars = length.coerceAtLeast(0))

    private fun countGraphemes(text: String): Int {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    private fun countWords(text: String): Int {
        val iterator = BreakIterator.getWordInstance()
        iterator.setText(text)
        var count = 0
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            if (holdsWordCharacter(text, start, end)) count++
            start = end
            end = iterator.next()
        }
        return count
    }

    private fun holdsWordCharacter(text: String, start: Int, end: Int): Boolean {
        for (i in start until end) {
            if (text[i].isLetterOrDigit()) return true
        }
        return false
    }

    /**
     * The cheap path above [MaxAnalyzedChars]: code points instead of graphemes, spaces instead of ICU.
     * The fourth delimiter is a non-breaking space (U+00A0), which is invisible here but common in text
     * pasted out of the web and would otherwise glue a whole line into one "word".
     */
    private fun approximate(text: String): Counts = Counts(
        words = text.split(' ', '\n', '\t', ' ').count { segment -> segment.any { it.isLetterOrDigit() } },
        chars = text.codePointCount(0, text.length),
    )
}
