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
 * Where a typed run of characters ends, for the runs Unicode's word rules take apart and a keyboard
 * should not: e-mail addresses and web addresses (issue #318, round 3).
 *
 * ### Why this exists
 *
 * The composing word is derived from the editor text by an ICU break iterator, and UAX#29 makes `@`,
 * `.` and `/` separators. So `jannis@example.com` reaches the learner as three words — `jannis`,
 * `example`, `com` — and the last of them is what a space offers up. The address is invisible to the
 * mechanism twice over: it never exists as a unit, and even if it did, [WordLearningGate.isLearnableForm]
 * used to refuse anything that was not letters.
 *
 * That is not a law of nature, only a choice, and the reporter of #318 was right that it is the wrong
 * one. The rules below are the exception block that makes an address one word.
 *
 * ### The rules, and why they are this narrow
 *
 * Prose must not notice. Every rule is therefore conditioned on the run *already* looking like an
 * address or a web address, so a full stop between sentences, a decimal point and an abbreviation like
 * `z.B.` all still end the word exactly where they did before:
 *
 *  - `@` continues a run that has content and does not already carry one. This is the trigger.
 *  - `.` and `-` continue a run that contains `@`, or one that starts with `http`/`www`.
 *  - `:` and `/` continue a run that starts with `http`.
 *  - `_` and `+` never end a run. They are connectors like `'` and `-` are in Unicode's own rules, and
 *    they have to be unconditional: `user123+tag@gmail.com` types its `+` *before* the `@` that would
 *    justify it, and nothing at that moment can know an address is coming.
 *
 * Deliberately **not** implemented are the looser "looks like a URL" shapes (contains `//`, or a dot
 * and a slash somewhere): they are true of `3.14/2` and of dates written with dots, and the two
 * unambiguous prefixes cover every address anyone actually types.
 *
 * ### Two callers, one rule
 *
 * [continuesRun] is asked forwards, by the input path, at the moment a separator is pressed: *may this
 * character be written without ending the word?* [runStart] is asked backwards, by the provider that
 * derives the composing region: *how far left does the run at the cursor reach?* They must agree — a
 * keyboard whose two halves disagree about where a word ends hands one half's work to the other, which
 * is exactly how `top10` became `top0` (issue #311). [runStart] therefore replays [continuesRun] over
 * the candidate rather than reasoning about the shape a second time.
 *
 * Pure, no Android dependency, so all of it is unit-testable — same reason [DictFold], [EditDistance]
 * and `DevanagariBase` are shaped this way.
 */
object WordRun {

    /** Longest run we glue together. Matches [TouchTrace]'s cap: beyond it nothing can be learned anyway. */
    const val MAX_LENGTH = 48

    /** The shortest thing that can pass for a domain suffix: `.de`, `.co`. */
    private const val MIN_TLD_LENGTH = 2

    /**
     * Whether [char] may be written into [run] without ending the word.
     *
     * [run] is the word as it stands *before* [char] — the composing text, not including it. Letters and
     * digits answer true so that this function is total and [runStart] can replay it over a whole
     * candidate; the input path never asks about them, because they never ended a word in the first
     * place.
     */
    fun continuesRun(run: String, char: Char): Boolean = when {
        run.isEmpty() -> false
        char.isLetterOrDigit() -> true
        // The trigger. One per address — a second `@` is somebody's typo or a different construction.
        char == '@' -> !run.contains('@') && run.any { it.isLetterOrDigit() }
        // Connectors, unconditionally: they arrive before the `@` that would justify them.
        char == '_' || char == '+' -> run.any { it.isLetterOrDigit() }
        // The domain's dots and hyphens. `-` before the `@` still ends the word, which costs the rare
        // hyphenated local part and keeps every hyphenated German compound exactly as it was.
        char == '.' || char == '-' -> run.contains('@') || isWebPrefixed(run)
        char == ':' || char == '/' -> isHttpPrefixed(run)
        else -> false
    }

    /**
     * How far left the run ending at the cursor reaches, or [defaultStart] when nothing is glued.
     *
     * Only ever moves the start *earlier*: this widens what the break iterator found, it never narrows
     * it. The walk stops at the first whitespace, and gives up entirely once the run would exceed
     * [MAX_LENGTH] — gives up rather than truncates, because half of a long run is not a word, and a
     * pasted wall of text without spaces must not turn every keystroke into a long scan.
     */
    fun runStart(textBeforeCursor: CharSequence, defaultStart: Int): Int {
        val end = textBeforeCursor.length
        if (end == 0) return defaultStart
        var start = defaultStart.coerceIn(0, end)
        while (start > 0 && !textBeforeCursor[start - 1].isWhitespace()) {
            start--
            if (end - start > MAX_LENGTH) return defaultStart
        }
        if (start >= defaultStart) return defaultStart
        val candidate = textBeforeCursor.subSequence(start, end).toString()
        return if (isOneRun(candidate)) start else defaultStart
    }

    /**
     * Whether [text] holds together as a single run under [continuesRun].
     *
     * A run has to *start* on a letter or a digit, which [continuesRun] never gets to say because it is
     * only ever asked about characters that already have something in front of them. Without it the
     * backward walk swallows the leading punctuation people write on purpose — `_wichtig_` in markdown,
     * `@mention` in a chat — and hands the engine a word it then refuses to correct.
     */
    fun isOneRun(text: String): Boolean {
        if (text.isEmpty() || !text[0].isLetterOrDigit()) return false
        for (i in 1 until text.length) {
            if (!continuesRun(text.substring(0, i), text[i])) return false
        }
        return true
    }

    /**
     * Whether [word] is an address or a web address — the shape that may be learned verbatim, and the
     * shape the dictionary has no business judging.
     *
     * An e-mail address needs exactly one `@` with something on both sides and a real domain suffix
     * behind the last dot; `a@b` is a fragment, not an address. A web address is recognised by its
     * prefix alone, because that is the only thing that made it a run to begin with.
     */
    fun isAddressLike(word: String): Boolean {
        if (isWebPrefixed(word) && (word.contains('.') || word.contains('/'))) return true
        val at = word.indexOf('@')
        if (at <= 0 || at != word.lastIndexOf('@')) return false
        val domain = word.substring(at + 1)
        val dot = domain.lastIndexOf('.')
        return dot > 0 && domain.length - dot - 1 >= MIN_TLD_LENGTH
    }

    /** `http…` or `www…`: the two prefixes that let dots and hyphens stay inside a run. */
    fun isWebPrefixed(run: String): Boolean = isHttpPrefixed(run) || run.startsWith("www", ignoreCase = true)

    private fun isHttpPrefixed(run: String): Boolean = run.startsWith("http", ignoreCase = true)

    /**
     * [word] without the punctuation a sentence left on it.
     *
     * Once `.` stays inside an address, a sentence that ends on one hands the learner
     * `jannis@example.com.` — the address plus the full stop that had nothing to do with it. Trailing
     * separators come off before anything is stored; the ones that are part of the address (a dot
     * inside the domain, a trailing digit) are not at the end.
     */
    fun trimTrailingPunctuation(word: String): String = word.trimEnd('.', ',', ';', ':', '!', '?', '-')
}
