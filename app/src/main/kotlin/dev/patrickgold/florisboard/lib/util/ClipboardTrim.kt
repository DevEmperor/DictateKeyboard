/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.lib.util

/**
 * Trims the padding off text on its way into the clipboard (issue #335).
 *
 * Selecting by hand is imprecise: the handles catch the space before a word, or the line break after the
 * last one, and that padding then travels into every place the clip is pasted. Opt-in, because it changes
 * what the user copied.
 *
 * Two rules keep it from ever being surprising:
 *
 * * **Only the ends.** Whatever the selection holds between its first and last visible character is
 *   copied exactly as it was, line breaks and double spaces included.
 * * **A selection of nothing but whitespace is left alone.** Someone who selects three spaces and copies
 *   them means those three spaces; trimming would put an empty clip on the clipboard instead, which is
 *   not a shorter version of what they asked for but a different thing entirely.
 */
object ClipboardTrim {

    /** [text] without leading and trailing whitespace, or [text] itself when that would leave nothing. */
    fun applyTo(text: CharSequence): String {
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) text.toString() else trimmed.toString()
    }
}
