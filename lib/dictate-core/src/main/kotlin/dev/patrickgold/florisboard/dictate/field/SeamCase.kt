/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.field

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
 * abbreviation (`usw.`, `z.B.`). Paragraph breaks (#225), the final full stop (#468) and the case at a
 * dictation's seam (#476) all ask it.
 */
fun isNonBreakingDot(text: String, dotIndex: Int): Boolean {
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

/**
 * What the text in front of the cursor says about the first letter of a dictation written there (#476).
 *
 * A provider writes every utterance as if it opened a sentence, so a dictation into the middle of one
 * arrived as `We met Again on Tuesday.`
 */
enum class SeamCase {
    /** A sentence begins here: the first letter is a capital, also where the provider wrote it small. */
    SENTENCE_START,

    /** The dictation carries a sentence on: a capital the provider put there for its own start goes. */
    MID_SENTENCE,

    /** Nothing to go by — an unread field, an ellipsis, an abbreviation, a colon, a quote: as written. */
    AS_WRITTEN,
}

/** How much of the text in front of the cursor [seamCaseOf] needs: enough to see an abbreviation whole. */
const val SEAM_CASE_CONTEXT = 24

/** Marks after which a sentence goes on. */
private const val CONTINUING_MARKS = ",;–—" // , ; – —

/** Marks after which a new sentence begins, the full stop aside (it has its own rule). */
private const val SENTENCE_END_MARKS = "!?‽。！？" // ! ? ‽ 。 ！ ？

/**
 * The case a dictation's first word takes after [textBefore], the end of the text in front of the cursor
 * ([SEAM_CASE_CONTEXT] characters of it are enough).
 *
 * - An empty field, or a line break right before the cursor: a sentence starts.
 * - After `.` `!` `?`: a sentence starts — but not after `...`, a dot inside a word (`U.S.`, `11.11.`),
 *   an ordinal, an initial or an abbreviation ([isNonBreakingDot]), which all go on as often as they end.
 * - After a letter, a digit, `,` `;` `–` `—`: the sentence goes on.
 * - Anything else — `…`, `:`, brackets, quotes, a field that could not be read — leaves the word as written.
 */
fun seamCaseOf(textBefore: String?): SeamCase {
    if (textBefore == null) return SeamCase.AS_WRITTEN
    val text = textBefore.trimEnd()
    if (text.isEmpty()) return SeamCase.SENTENCE_START
    if (textBefore.substring(text.length).any { it == '\n' || it == '\r' || it == ' ' }) {
        return SeamCase.SENTENCE_START
    }
    val last = text.last()
    return when {
        last.isLetterOrDigit() || last in CONTINUING_MARKS -> SeamCase.MID_SENTENCE
        last in SENTENCE_END_MARKS -> SeamCase.SENTENCE_START
        last == '.' -> {
            val token = text.dropLast(1).takeLastWhile { !it.isWhitespace() }
            when {
                // "...", "U.S.", "11.11.", "e.V."
                '.' in token -> SeamCase.AS_WRITTEN
                isNonBreakingDot(text, text.lastIndex) -> SeamCase.AS_WRITTEN
                else -> SeamCase.SENTENCE_START
            }
        }
        else -> SeamCase.AS_WRITTEN
    }
}

/**
 * [piece] with its first letter cased for [case].
 *
 * [writtenSmall] says whether a word is written small inside a sentence — the keyboard's dictionaries,
 * which keep a German noun (`Bahnhof`), a name (`Tuesday`) and English `I` capitalised. A capital is only
 * taken away where it says so; an unknown word, or no dictionary at all, keeps it.
 *
 * A first word with capitals of its own past its first letter is spelled that way on purpose and stays
 * as it is in both directions (`EU`, `iPhone`, `McDonald's`), as does one that is part of an address
 * (`www.example.com`, `anna@example.com`). A piece that does not open with a letter is left alone.
 */
fun applySeamCase(piece: String, case: SeamCase, writtenSmall: (String) -> Boolean): String {
    if (case == SeamCase.AS_WRITTEN || piece.isEmpty() || !piece[0].isLetter()) return piece
    var end = 1
    while (end < piece.length && isWordChar(piece[end])) end++
    val word = piece.substring(0, end)
    if (word.drop(1).any { it.isUpperCase() }) return piece
    val token = piece.takeWhile { !it.isWhitespace() }
    if ('@' in token || '/' in token || ADDRESS_DOT.containsMatchIn(token)) return piece
    val first = piece[0]
    val cased = when (case) {
        SeamCase.SENTENCE_START -> if (first.isLowerCase()) first.titlecaseChar() else first
        SeamCase.MID_SENTENCE -> if (first.isUpperCase() && writtenSmall(word)) first.lowercaseChar() else first
        SeamCase.AS_WRITTEN -> first
    }
    return if (cased == first) piece else cased + piece.substring(1)
}

/** A dot with a letter right behind it: a host name or an address, not a sentence end. */
private val ADDRESS_DOT = Regex("""\.\p{L}""")
