/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.prompts

/**
 * The spoken command word (issue #139): a dictation that opens with the user's own trigger — "Jarvis,
 * make this more formal" — is an instruction rather than something to write down, and runs as a live
 * prompt instead of being inserted verbatim.
 *
 * Everything here is about being forgiving in one direction and strict in the other, because the two
 * mistakes are not the same size. Missing the trigger costs a tap on the live-prompt chip. Firing when
 * nobody meant to costs the user their sentence — it is *executed* instead of written. So:
 *
 *  * **Forgiving about how the word arrives.** A transcription model decides on its own whether to
 *    write "Jarvis", "jarvis", "Jarvis," or "Jarvis:" and may put a stray quote in front, so case and
 *    surrounding punctuation are all ignored. A multi-word trigger ("hey Jarvis") tolerates whatever
 *    the model put between its words.
 *  * **Strict about where it sits.** Only at the very start, and only as a whole word: what follows it
 *    has to be whitespace or a sentence mark. `command-line` and `Jarvis's` are therefore not the
 *    trigger, which matters exactly for the everyday words people are most likely to pick.
 *  * **Strict about there being an instruction.** A dictation that is nothing *but* the trigger is
 *    inserted as text. There is no instruction to run, and an empty prompt would come back as noise.
 *
 * [Match.PARTIAL] exists for the streaming path: while the words are still arriving, "Ja" could become
 * either "Jarvis" or "Ja, das passt", and the caller has to hold the preview back until that is decided
 * instead of typing a command into the field and taking it out again.
 */
object CommandTrigger {

    /**
     * What the transcript *so far* says about the trigger: it is there ([MATCHED]), it is not and
     * cannot become there ([NONE]), or it is still too early to tell ([PARTIAL]).
     */
    enum class Match { NONE, PARTIAL, MATCHED }

    /**
     * What may stand between the trigger and the instruction and still count as the end of the trigger.
     * Deliberately only sentence punctuation, in the scripts Dictate transcribes: a hyphen or an
     * apostrophe *joins* words rather than separating them, so `command-line` and `Jarvis's` stay
     * ordinary words — which is the whole protection for a trigger people actually say out loud.
     */
    private const val BOUNDARY_MARKS = ",.;:!?…，。；：！？、،؛؟।॥"

    /**
     * Additionally dropped off the front of the instruction once the trigger has been recognised. A dash
     * is here and not in [BOUNDARY_MARKS] on purpose: it is not allowed to *end* the trigger (that is
     * what keeps `command-line` whole), but an instruction never means to start with one.
     */
    private const val LEADING_NOISE = "$BOUNDARY_MARKS—–-"

    private const val NO_MATCH = -1
    private const val UNDECIDED = -2

    /**
     * The instruction in [transcript] once [trigger] has been taken off the front, or null when this
     * dictation is not a command (no trigger, a different opening word, or nothing said after it).
     */
    fun instructionFor(transcript: String, trigger: String): String? {
        val after = scan(transcript, trigger)
        if (after < 0) return null
        return instructionAfter(transcript, after).ifEmpty { null }
    }

    /** The same look at [transcript], for a caller that only has part of it yet (the realtime stream). */
    fun match(transcript: String, trigger: String): Match = when (val after = scan(transcript, trigger)) {
        NO_MATCH -> Match.NONE
        UNDECIDED -> Match.PARTIAL
        // The trigger is there but the instruction has not been spoken yet — for a finished transcript
        // that means "not a command", for a growing one it means "keep waiting". Both callers get what
        // they need from PARTIAL, since [instructionFor] answers null in the same case.
        else -> if (instructionAfter(transcript, after).isEmpty()) Match.PARTIAL else Match.MATCHED
    }

    /**
     * The index in [transcript] just past [trigger], or [NO_MATCH] / [UNDECIDED]. One scan shared by
     * both entry points so the finished transcript and the growing one can never disagree.
     */
    private fun scan(transcript: String, trigger: String): Int {
        val words = trigger.trim().split(' ', '\t', '\n', '\u00A0').filter { it.isNotEmpty() }
        if (words.isEmpty()) return NO_MATCH
        // Anything before the first letter is noise the model put there (a quote, a dash, a stray comma).
        var i = skipToWord(transcript, 0)
        for ((n, word) in words.withIndex()) {
            if (n > 0) {
                val separatorAt = i
                i = skipToWord(transcript, i)
                // The words of a multi-word trigger have to be separated: "heyJarvis" is one word, and
                // one word is not this trigger.
                if (i == separatorAt) return NO_MATCH
            }
            if (i >= transcript.length) return UNDECIDED
            val remaining = transcript.length - i
            if (remaining < word.length) {
                // The transcript ends in the middle of this word of the trigger: still a candidate.
                return if (transcript.regionMatches(i, word, 0, remaining, ignoreCase = true)) {
                    UNDECIDED
                } else {
                    NO_MATCH
                }
            }
            if (!transcript.regionMatches(i, word, 0, word.length, ignoreCase = true)) return NO_MATCH
            i += word.length
        }
        if (i >= transcript.length) return UNDECIDED // the trigger, and nothing after it yet
        val next = transcript[i]
        if (!next.isWhitespace() && next !in BOUNDARY_MARKS) return NO_MATCH
        return i
    }

    /** Advances past everything that is not a letter or a digit — the separators, whatever they are. */
    private fun skipToWord(text: String, from: Int): Int {
        var i = from
        while (i < text.length && !text[i].isLetterOrDigit()) i++
        return i
    }

    /**
     * The instruction that follows the trigger at [from]: the separating marks are dropped, everything
     * else is kept as spoken. Only whitespace and [LEADING_NOISE] are stripped, so an instruction that
     * genuinely opens with a quote or a bracket keeps it.
     */
    private fun instructionAfter(text: String, from: Int): String {
        var i = from
        while (i < text.length && (text[i].isWhitespace() || text[i] in LEADING_NOISE)) i++
        return text.substring(i).trim()
    }
}
