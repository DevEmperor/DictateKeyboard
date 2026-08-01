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
 * Where the finger actually landed for each character of the word currently being composed (issue #242).
 *
 * The keyboard resolves every tap to its nearest key and then throws the coordinate away, which is exactly
 * the information the autocorrect needs: "n" tapped dead-centre and "n" tapped halfway towards "m" are the
 * same character but very different evidence. This object keeps that evidence for the composing word so
 * [TouchBeamDecoder] can decode from the taps instead of from the already-resolved string.
 *
 * **The trace is self-validating.** [pointsFor] hands out coordinates only if the recorded characters still
 * spell the word the caller is asking about. Anything that desyncs the two — pasting, moving the cursor, a
 * hardware keyboard, a glide commit, text arriving from dictation — simply yields null and the caller falls
 * back to the classic edit-distance path. That is far more robust than trying to hook every such site.
 *
 * Written from the UI thread (touch handling), read from a background coroutine (suggestion generation),
 * hence the synchronization.
 */
object TouchTrace {

    /** Longest word we keep evidence for; beyond this the trace is dropped and autocorrect falls back. */
    private const val MAX_LENGTH = 48

    /** Marks a character the user chose deliberately (long-press accent picker), where position is moot. */
    private const val EXACT = Float.NaN

    private val chars = StringBuilder(MAX_LENGTH)
    private val xs = FloatArray(MAX_LENGTH)
    private val ys = FloatArray(MAX_LENGTH)

    // Position of the touch-down that is about to produce a character, in key-width units. Set when a key
    // is pressed and consumed when the resulting character is committed, so the coordinate travels with the
    // keystroke without threading it through the whole input-event plumbing.
    private var pendingX = EXACT
    private var pendingY = EXACT
    private var hasPending = false

    /** A character key was pressed at the given raw touch position (pixels). */
    @Synchronized
    fun pendingTap(xPx: Float, yPx: Float) {
        val p = KeyProximityInfo.normalize(xPx, yPx)
        if (p == null) {
            markPendingExact()
            return
        }
        pendingX = p[0]
        pendingY = p[1]
        hasPending = true
    }

    /**
     * The next character comes from a deliberate choice rather than a plain tap (long-press accent picker,
     * a popup selection). Recorded as certain: the decoder will not consider neighbouring keys for it.
     */
    @Synchronized
    fun markPendingExact() {
        pendingX = EXACT
        pendingY = EXACT
        hasPending = true
    }

    /**
     * A character reached the editor. Appends it together with the pending tap position; a character with
     * no pending tap (hardware keyboard, injected text) invalidates the trace, because from that point on
     * the coordinates no longer line up with the word.
     */
    @Synchronized
    fun commit(text: String) {
        if (!hasPending || text.length != 1 || chars.length >= MAX_LENGTH) {
            reset()
            return
        }
        val i = chars.length
        chars.append(text[0])
        xs[i] = pendingX
        ys[i] = pendingY
        hasPending = false
    }

    /** A backspace removed the last character; drop its evidence so the trace stays aligned. */
    @Synchronized
    fun pop() {
        hasPending = false
        if (chars.isNotEmpty()) chars.setLength(chars.length - 1)
    }

    /** Forget everything — the composing word ended or the editor state changed underneath us. */
    @Synchronized
    fun reset() {
        chars.setLength(0)
        hasPending = false
    }

    /**
     * Tap positions for [word], or null when the trace does not describe exactly that word (and the caller
     * must fall back). Returns a flat `[x0, y0, x1, y1, …]` copy so the decoder is unaffected by further
     * typing while it runs; NaN marks a character the user picked deliberately.
     */
    @Synchronized
    fun pointsFor(word: String): FloatArray? {
        if (word.isEmpty() || word.length != chars.length) return null
        for (i in word.indices) {
            if (!word[i].equals(chars[i], ignoreCase = true)) return null
        }
        val out = FloatArray(word.length * 2)
        for (i in word.indices) {
            out[i * 2] = xs[i]
            out[i * 2 + 1] = ys[i]
        }
        return out
    }
}
