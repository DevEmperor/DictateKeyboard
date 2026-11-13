/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.editor

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When the keyboard may take what it holds of a field for the whole field (issue #473).
 *
 * A prompt tapped with nothing selected rewords the whole field and commits the result over a select-all.
 * It used to send the keyboard's window around the cursor instead — 256 characters before it, 128 after —
 * so a long text came back as the rewording of its last 256 characters, and the rest was gone.
 */
class WholeFieldTextTest {

    private val longText = (1..40).joinToString(" ") { "Sentence number $it is here." }

    /** Content the way [AbstractEditorInstance] builds it from one selection report. */
    private fun content(before: String, selected: String, after: String, selectionStart: Int) = EditorContent(
        text = before + selected + after,
        offset = selectionStart - before.length,
        localSelection = EditorRange(before.length, before.length + selected.length),
        localComposing = EditorRange.Unspecified,
        localCurrentWord = EditorRange.Unspecified,
    )

    private val cursorAtEnd = content(longText.takeLast(256), "", "", longText.length)
    private val allSelected = content("", longText, "", 0)

    @Test
    fun `a select-all the editor reported is the whole field`() {
        assertEquals(longText, wholeFieldText(allSelected))
    }

    @Test
    fun `the window in front of a cursor at the end is not the field`() {
        // The case of the report: what #473 sent to the model, without its first part.
        assertTrue(longText.length > 256)
        assertNull(wholeFieldText(cursorAtEnd))
    }

    @Test
    fun `a short field read in full is still not selected`() {
        // Its text is complete, but nothing is selected yet: a commit would add to it, not replace it.
        assertNull(wholeFieldText(content("Short text.", "", "", 11)))
    }

    @Test
    fun `a selection that starts at the beginning but stops short of the end is not the field`() {
        assertNull(wholeFieldText(content("", "Hello", " world", 0)))
    }

    @Test
    fun `a selection behind the start of the field is not the field`() {
        assertNull(wholeFieldText(content("Hello ", "world", "", 6)))
    }

    @Test
    fun `contents that carry no text prove nothing`() {
        assertNull(wholeFieldText(EditorContent.Unspecified))
        // Mass selection keeps the editor's numbers but no text.
        assertNull(wholeFieldText(EditorContent.selectionOnly(EditorRange(0, longText.length))))
    }

    @Test
    fun `the report that follows the select-all is waited for`() = runTest {
        val contents = MutableStateFlow(cursorAtEnd)
        launch {
            delay(40)
            // Finishing the composing text comes first and reports the cursor again.
            contents.value = cursorAtEnd.copy(phantomSpacePending = true)
            delay(10)
            contents.value = allSelected
        }
        assertEquals(longText, awaitWholeField(contents, timeoutMs = 2000))
        assertEquals(50, currentTime)
    }

    @Test
    fun `no report means no text, once the wait is over`() = runTest {
        // An editor that ignores the select-all: committing would write the text a second time.
        val contents = MutableStateFlow(cursorAtEnd)
        assertNull(awaitWholeField(contents, timeoutMs = 2000))
        assertEquals(2000, currentTime)
    }

    @Test
    fun `a select-all whose read disagreed with the editor is not taken`() = runTest {
        // A selected text cut short by the app does not match the editor's numbers, and the keyboard keeps
        // no content for it — the wait runs out instead of accepting part of the field.
        val contents = MutableStateFlow(cursorAtEnd)
        launch {
            delay(20)
            contents.value = EditorContent.Unspecified
        }
        assertNull(awaitWholeField(contents, timeoutMs = 2000))
    }
}
