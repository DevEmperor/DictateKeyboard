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

import android.content.Context
import dev.patrickgold.florisboard.dictate.field.SEAM_FOLLOWING_DEFAULT
import dev.patrickgold.florisboard.dictate.field.SEAM_PRECEDING_DEFAULT
import dev.patrickgold.florisboard.dictate.field.seamSeparator
import dev.patrickgold.florisboard.nlpManager

/**
 * Puts the pieces a transcription arrives in back together (issue #356).
 *
 * Every engine that settles its text more than once — the realtime sessions, the on-device streaming and
 * VAD-segmented batch decoders, long-form segments, chunked import — glued its pieces with an
 * unconditional space. That is right for a piece that starts with a word and wrong for one that starts
 * with a mark binding to the word in front of it, which is exactly what a streaming transducer produces:
 * it can only release a sentence-final `.` once the encoder has seen the trailing silence, and by then
 * the words have already settled as their own piece. `"Hey there"` + `"."` became `Hey there .`.
 *
 * It shows up on the fragments rather than on finished sentences: say a whole sentence and the model is
 * sure enough to emit the mark together with the last word, so the piece never splits. Break off
 * mid-thought and the mark only arrives after the pause has already ended the segment.
 *
 * Which marks bind backwards is a property of the language, not of this code — French wants a space
 * before `? ! ; :`, Devanagari adds `।` and `॥` — so the caller passes the active punctuation rule's
 * [dev.patrickgold.florisboard.ime.nlp.PunctuationRule.symbolsTighteningSpace]. That is the same list
 * that decides whether a space the *user* typed in front of a mark gets swallowed (issue #329), which is
 * the same question asked one step later. Unlike that one this sits behind no preference: taking away a
 * space somebody typed is a judgement call, declining to invent one of our own is not.
 */
object TranscriptJoin {

    /**
     * For callers with no keyboard in reach (the Wear transcriber, tests). Mirrors the "default" rule in
     * the localization extension — a caller that can ask the active rule should, or French silently loses
     * the space it wants before `?` and `!`.
     */
    const val DEFAULT_TIGHTENING_SYMBOLS = ".,;:?!‽" // . , ; : ? ! ‽

    /**
     * Appends [piece] to [out], separated by a space unless the piece opens with one of
     * [tighteningSymbols] — then it goes straight against what is already there, taking any spacing in
     * between with it. A blank piece changes nothing, so callers need no emptiness check of their own.
     */
    fun appendPiece(
        out: StringBuilder,
        piece: String,
        tighteningSymbols: String = DEFAULT_TIGHTENING_SYMBOLS,
    ): StringBuilder {
        val text = piece.trim()
        if (text.isEmpty()) return out
        if (out.isNotEmpty()) {
            if (tighteningSymbols.contains(text.first())) {
                while (out.isNotEmpty() && out.last().isWhitespace()) out.setLength(out.length - 1)
            } else if (!out.last().isWhitespace()) {
                out.append(' ')
            }
        }
        return out.append(text)
    }

    /** The phantom space's default marks, see [seamSeparator] (moved to `:lib:dictate-core` for the watch). */
    const val DEFAULT_SEAM_PRECEDING = SEAM_PRECEDING_DEFAULT
    const val DEFAULT_SEAM_FOLLOWING = SEAM_FOLLOWING_DEFAULT

    /**
     * The separator a dictation needs in front of it to sit next to [textBefore], the text already in the
     * field up to the cursor: a single space, or nothing. The rule is [seamSeparator]'s.
     */
    fun separatorBefore(
        textBefore: String?,
        piece: String,
        precedingSymbols: String = DEFAULT_SEAM_PRECEDING,
        followingSymbols: String = DEFAULT_SEAM_FOLLOWING,
    ): String = seamSeparator(textBefore, piece, precedingSymbols, followingSymbols)

    /**
     * Takes the ellipsis off the end of [head] when [nextPiece] carries the same sentence on, that is when
     * it opens with a lower-case letter. Returns whether it did.
     *
     * For long-form segments (#170), whose seams are cuts the app made: when Smart Turn takes a mid-sentence
     * breath for the end of a thought, Whisper closes the fragment with "..." and the next segment goes on in
     * lower case — "Hello, this is... a short test" for "Hello, this is a short test". A next piece in upper
     * case, or in a script without case, leaves the head alone: there the dots may be the speaker's own.
     */
    fun dropCutEllipsis(head: StringBuilder, nextPiece: String): Boolean {
        val next = nextPiece.trimStart()
        if (next.isEmpty() || !next.first().isLowerCase()) return false
        var end = head.length
        while (end > 0 && head[end - 1].isWhitespace()) end--
        val dots = when {
            head.startsWith("...", end - 3) -> 3
            end > 0 && head[end - 1] == '…' -> 1
            else -> return false
        }
        var start = end - dots
        while (start > 0 && head[start - 1] == '.') start-- // "...." is still one ellipsis
        head.setLength(start)
        return true
    }

    /** [appendPiece] for the callers that hold their head as a plain string. */
    fun join(
        head: String,
        piece: String,
        tighteningSymbols: String = DEFAULT_TIGHTENING_SYMBOLS,
    ): String = appendPiece(StringBuilder(head), piece, tighteningSymbols).toString()

    /**
     * Takes the space back out from in front of every [tighteningSymbols] mark *inside* one piece.
     *
     * [appendPiece] mends the seam between two pieces; this mends the inside of one. Same question,
     * different cause: there the mark arrived too late to travel with its word, here the model's own
     * vocabulary carries the space. NVIDIA's German FastConformer has `▁,` and `▁.` — "space, then the
     * mark" — as tokens 1 and 2 of its vocabulary, so `Alles hat ein Ende , nur die Wurst hat zwei .`
     * is not a decoding slip but what it genuinely predicts. Its English sibling has only the bare marks
     * and needs none of this, which is why this belongs on the way out rather than on a model entry:
     * a tokenizer is not a fact about a language, and the next export may differ from this one.
     *
     * The space being taken away is always one a *model* wrote. A space a person typed in front of a
     * mark is a different question, asked one step later and behind a preference (issue #329).
     */
    fun tighten(text: String, tighteningSymbols: String = DEFAULT_TIGHTENING_SYMBOLS): String {
        if (text.isEmpty() || tighteningSymbols.isEmpty()) return text
        val out = StringBuilder(text.length)
        for (ch in text) {
            if (tighteningSymbols.contains(ch)) {
                while (out.isNotEmpty() && out.last().isWhitespace()) out.setLength(out.length - 1)
            }
            out.append(ch)
        }
        return out.toString()
    }
}

/**
 * The tightening set to hand [TranscriptJoin], read from the active subtype's punctuation rule.
 *
 * The *keyboard's* rule rather than the dictation language's: with auto-detect the spoken language is
 * unknown until the text has already been assembled, and the transcript is on its way into a field whose
 * surrounding text follows exactly these conventions anyway.
 *
 * Only if the NLP stack is already up, which is why the [Lazy] is inspected instead of unwrapped.
 * Building it pulls in the clipboard, the editor, the keyboard resources and both language providers —
 * a whole keyboard, to learn how a language spaces its commas. Where it is not up there is no field with
 * conventions to match either (a shared file, a Wear request), so the conservative default is the honest
 * answer rather than a degraded one.
 */
internal fun Context.transcriptTighteningSymbols(): String = runCatching {
    val nlp = nlpManager()
    if (nlp.isInitialized()) nlp.value.getActivePunctuationRule().symbolsTighteningSpace else null
}.getOrNull() ?: TranscriptJoin.DEFAULT_TIGHTENING_SYMBOLS

/**
 * [TranscriptJoin.separatorBefore] with the active punctuation rule's phantom-space marks, on the same
 * terms as [transcriptTighteningSymbols]: the keyboard's rule when it is already up, the default otherwise.
 */
internal fun Context.transcriptSeparatorBefore(textBefore: String?, piece: String): String {
    val rule = runCatching {
        val nlp = nlpManager()
        if (nlp.isInitialized()) nlp.value.getActivePunctuationRule() else null
    }.getOrNull()
    return TranscriptJoin.separatorBefore(
        textBefore = textBefore,
        piece = piece,
        precedingSymbols = rule?.symbolsPrecedingPhantomSpace ?: TranscriptJoin.DEFAULT_SEAM_PRECEDING,
        followingSymbols = rule?.symbolsFollowingPhantomSpace ?: TranscriptJoin.DEFAULT_SEAM_FOLLOWING,
    )
}
