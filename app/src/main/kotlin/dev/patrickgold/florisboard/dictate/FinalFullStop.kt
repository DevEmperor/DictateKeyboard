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
 * Leaves the full stop off a dictation that is one sentence, for those who switch it on (issue #468).
 *
 * Speech models end everything with a full stop, and the style prompt asks them to. In running text that
 * is right; on a note, a list item or a chat reply it is a mark nobody would have typed, and "buy tofu"
 * comes out as "Buy tofu.". A rewording prompt was the obvious answer and a poor one: asked to end only
 * whole sentences with a stop, Gemini 3.6 Flash and gpt-oss-120b both kept "Buy tofu." (an imperative is a
 * sentence), wrote German nouns in lower case, and Gemini answered a dictated question instead of writing
 * it down — at 2–7 s per dictation (2026-11-03). Whether something is a note is beyond a rule; whether it is
 * one sentence is not, and that is the line the request draws itself.
 *
 * - **One sentence:** nothing in front of the last mark ends a sentence, and there is no line break. A
 *   sentence end is judged as [TranscriptParagraphs] judges one, so "z.B. Tofu kaufen." is one sentence
 *   and "3.50" holds no end. How long the sentence is does not matter. A dictation of several sentences
 *   keeps every mark it has, and so do two dictations in a row: the second puts the first one's stop back
 *   (`DictateController.leadFor`).
 * - **A full stop that is the sentence's:** "?" and "!" stay, and so does a dot that belongs to its word
 *   ("etc.", "p.m.", "Plan B."), an ellipsis, or a mark followed by a closing quote.
 *
 * Where it is unsure it keeps the stop: one too many is what everybody had before.
 *
 * The capital at the start stays. The keyboard sets the same one on a typed note, and a rule that lowered
 * it would lower German nouns with it.
 */
object FinalFullStop {

    /** The marks that end a sentence without asking or exclaiming: Latin, CJK, Devanagari. */
    private const val FULL_STOPS = ".。।" // . 。 ।

    /** End a sentence where they stand: the scripts that use them set no space after them. */
    private const val UNSPACED_ENDS = "。！？" // 。 ！ ？

    /** End a sentence when whitespace follows, past any closing quotes or brackets. */
    private const val SPACED_ENDS = ".!?…।" // . ! ? … ।

    /**
     * Whether the full stop [text] ends on may go. [head] is the part of the same dictation that already
     * stands in the field because the user took it over (#421); it counts toward the sentence.
     */
    fun droppable(text: String, head: String = ""): Boolean {
        if (text.length < 2 || text.last() !in FULL_STOPS) return false
        val body = text.dropLast(1)
        if (isEnd(body.last())) return false
        if (text.last() == '.') {
            val lastWord = body.takeLastWhile { !it.isWhitespace() }
            // "p.m.", "e.V.", "U.S.": a dot inside the last word makes the final one part of it too.
            if ('.' in lastWord) return false
            // "etc.", "Plan B.". Not a number: "at 5." ends a sentence far more often than it is the
            // ordinal that [TranscriptParagraphs] takes it for in running text.
            if (!lastWord.all { it.isDigit() } && TranscriptParagraphs.isNonBreakingDot(text, text.lastIndex)) {
                return false
            }
        }
        if (body.none { it.isLetterOrDigit() }) return false
        val sentence = if (head.isBlank()) body else "$head $body"
        return !hasSentenceEnd(sentence)
    }

    /** [text] without its final full stop when [droppable], else [text] as it is. */
    fun drop(text: String, head: String = ""): String = if (droppable(text, head)) text.dropLast(1) else text

    /** Whether [c] can end a sentence. */
    fun isEnd(c: Char): Boolean = c in SPACED_ENDS || c in UNSPACED_ENDS

    private fun hasSentenceEnd(text: String): Boolean {
        for (i in text.indices) {
            val c = text[i]
            if (c == '\n' || c in UNSPACED_ENDS) return true
            if (c !in SPACED_ENDS) continue
            var next = i + 1
            while (next < text.length && TranscriptParagraphs.isTrailing(text[next])) next++
            if (next >= text.length || !text[next].isWhitespace()) continue
            if (c == '.' && TranscriptParagraphs.isNonBreakingDot(text, i)) continue
            return true
        }
        return false
    }
}
