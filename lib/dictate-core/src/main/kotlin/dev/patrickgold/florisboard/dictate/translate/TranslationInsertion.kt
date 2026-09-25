/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.translate

/**
 * How the translate bar's live result is kept in the app's text field (issue #424).
 *
 * The translation is ordinary text in the field, not a composing region, and every new result replaces
 * the previous one in a single edit — delete what was written last time, write the new text. That only
 * works while the text in front of the cursor still ends with what was written, so the check is made
 * against the field every time: if the user moved the cursor or the app rewrote the text, the old
 * result is left alone and the new one starts where the cursor now is, instead of deleting whatever
 * happens to sit there.
 */
object TranslationInsertion {
    /** Delete [deleteBefore] characters before the cursor, then write [text]. */
    data class Edit(val deleteBefore: Int, val text: String)

    /**
     * The edit that turns the field into one showing [translation], given [previous] — exactly what the
     * last edit wrote, separator included — and [textBeforeCursor] as the field reports it now.
     * An empty [translation] removes the previous result.
     */
    fun edit(textBeforeCursor: String, previous: String?, translation: String): Edit {
        val owned = previous != null && previous.isNotEmpty() && textBeforeCursor.endsWith(previous)
        val deleteBefore = if (owned) previous!!.length else 0
        if (translation.isEmpty()) return Edit(deleteBefore, "")
        val before = textBeforeCursor.dropLast(deleteBefore)
        return Edit(deleteBefore, separatorAfter(before) + translation)
    }

    /** A space between the result and a word in front of it; nothing at the start or after whitespace. */
    fun separatorAfter(before: String): String =
        if (before.isEmpty() || before.last().isWhitespace()) "" else " "
}
