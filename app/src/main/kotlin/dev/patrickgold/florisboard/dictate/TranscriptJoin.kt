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

    /** [appendPiece] for the callers that hold their head as a plain string. */
    fun join(
        head: String,
        piece: String,
        tighteningSymbols: String = DEFAULT_TIGHTENING_SYMBOLS,
    ): String = appendPiece(StringBuilder(head), piece, tighteningSymbols).toString()
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
