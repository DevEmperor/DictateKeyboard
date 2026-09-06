/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.keyboard

/**
 * What a second tap on the space bar writes in place of the space (issue #333).
 *
 * Previously a boolean and a hard-coded `". "`, which is wrong in every language that does not end a
 * sentence with a full stop — Hindi writes the danda `।` (issue #315). [PUNCTUATION] therefore asks
 * the active punctuation rule instead of naming a character, and the other three are here because the
 * shortcut is worth having for people who never wanted a full stop from it.
 */
enum class DoubleSpaceAction {
    PUNCTUATION,
    NEWLINE,
    COMMA,
    SLASH;
}

/**
 * The double-tap shortcut, kept apart from the input path so both halves of it can be read — and
 * tested — as one rule rather than as a regex on one side of a file and a string literal on the other.
 *
 * Both functions take the sentence terminators of the active punctuation rule
 * ([dev.patrickgold.florisboard.ime.nlp.PunctuationRule.symbolsTerminatingSentence]) rather than
 * assuming them, which is the whole point: the set that decides *whether* to fire and the character
 * that gets written have to come from the same place, or a language whose full stop we do not write
 * would have its own sentence ending doubled.
 */
object DoubleSpace {

    /** What replaces the trailing space. */
    fun replacementFor(action: DoubleSpaceAction, sentenceTerminators: String): String = when (action) {
        // The first terminator listed is the one a sentence is normally ended with; the rest are there
        // to be recognised. `.` as a fallback for a rule that lists none at all.
        DoubleSpaceAction.PUNCTUATION -> "${sentenceTerminators.firstOrNull() ?: '.'} "
        DoubleSpaceAction.NEWLINE -> "\n"
        DoubleSpaceAction.COMMA -> ", "
        // No trailing space: a slash binds the two things it stands between (and/or, a date), which is
        // the only reason anyone would bind it to this shortcut.
        DoubleSpaceAction.SLASH -> "/"
    }

    /**
     * Whether the two characters before the cursor are a word character followed by the space that was
     * just typed — i.e. the second tap ends a word rather than following a sentence that is already
     * finished. A sentence already ended with its own terminator is left alone, so a third tap does not
     * write ".. " and Hindi does not get "।। ".
     */
    fun triggersOn(textBeforeCursor: CharSequence, sentenceTerminators: String): Boolean {
        if (textBeforeCursor.length != 2) return false
        return matcherFor(sentenceTerminators).matches(textBeforeCursor)
    }

    // One entry is enough: the punctuation rule only changes when the subtype does, and this is asked
    // once per space keystroke.
    private var cachedTerminators: String? = null
    private var cachedMatcher: Regex = Regex("")

    private fun matcherFor(sentenceTerminators: String): Regex {
        if (cachedTerminators != sentenceTerminators) {
            cachedMatcher = Regex("[^${Regex.escape(sentenceTerminators)}\\s]\\s")
            cachedTerminators = sentenceTerminators
        }
        return cachedMatcher
    }
}
