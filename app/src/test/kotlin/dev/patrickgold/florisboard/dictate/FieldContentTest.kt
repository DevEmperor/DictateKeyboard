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

import dev.patrickgold.florisboard.dictate.overlay.DictateAccessibilityService
import dev.patrickgold.florisboard.dictate.overlay.DictateAccessibilityService.FieldContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the floating button is allowed to assume about a field before rewriting it (issue #314).
 *
 * The bug this exists for: an empty Telegram or WhatsApp field hands out its placeholder ("Message",
 * "Ask Meta AI or Search") as if it were content, and reports no caret. `ACTION_SET_TEXT` sends the
 * whole field back, so the placeholder was written in as real text ahead of the dictation.
 *
 * The rule is therefore lopsided in the other direction from [DictateAccessibilityService.insertLandedFrom]:
 * **only proven content may be rebuilt, and a refusal proves nothing.** Reading "unknown" as "empty"
 * would be the worse bug — it deletes what the user wrote.
 *
 * This path cannot be exercised from an instrumented test (Android will not bind an accessibility
 * service into a process under instrumentation), so the decision lives here as a pure function.
 */
class FieldContentTest {

    private fun resolve(
        ic: FieldContent? = null,
        nodeText: String = "",
        nodeStart: Int = -1,
        nodeEnd: Int = -1,
        caretConfirmed: Boolean = false,
    ) = DictateAccessibilityService.fieldContentFrom(
        icContent = ic,
        nodeText = nodeText,
        nodeStart = nodeStart,
        nodeEnd = nodeEnd,
        confirmCaretAtEnd = { caretConfirmed },
    )

    // --- The reported bug -------------------------------------------------------------------------

    @Test
    fun `a placeholder with no caret proves nothing and is not rebuilt`() {
        // WhatsApp's composer as measured: the hint arrives as text, hintText is unset, no selection.
        assertNull(resolve(nodeText = "Message", caretConfirmed = false))
    }

    @Test
    fun `the same field is settled the moment the editor itself can be asked`() {
        // Android 13+: the input connection reports the field as empty, which the node never would.
        val content = resolve(ic = FieldContent("", 0, 0, "ic"), nodeText = "Message")
        assertEquals(FieldContent("", 0, 0, "ic"), content)
    }

    @Test
    fun `a field that confirms the caret is real content and may be appended to`() {
        val content = resolve(nodeText = "Message", caretConfirmed = true)
        assertEquals(FieldContent("Message", 7, 7, "probe"), content)
    }

    // --- Sources, in order of trust ---------------------------------------------------------------

    @Test
    fun `the input connection wins over whatever the node claims`() {
        val content = resolve(
            ic = FieldContent("hello world", 5, 5, "ic"),
            nodeText = "something else entirely",
            nodeStart = 0,
            nodeEnd = 0,
        )
        assertEquals("hello world", content?.text)
        assertEquals(5, content?.start)
        assertEquals(5, content?.end)
    }

    @Test
    fun `an empty node needs no proof at all`() {
        assertEquals(FieldContent("", 0, 0, "empty"), resolve(nodeText = ""))
    }

    @Test
    fun `a node that reports its caret is trusted without the probe`() {
        val content = resolve(nodeText = "hello", nodeStart = 2, nodeEnd = 2, caretConfirmed = false)
        assertEquals(FieldContent("hello", 2, 2, "node"), content)
    }

    @Test
    fun `a selection is kept so the write replaces it`() {
        val content = resolve(nodeText = "hello world", nodeStart = 6, nodeEnd = 11)
        assertEquals(6, content?.start)
        assertEquals(11, content?.end)
    }

    // --- Malformed answers must not become out-of-bounds writes -----------------------------------

    @Test
    fun `a reversed selection is normalised rather than trusted as given`() {
        val content = resolve(nodeText = "hello", nodeStart = 4, nodeEnd = 1)
        assertEquals(1, content?.start)
        assertEquals(4, content?.end)
    }

    @Test
    fun `a caret past the end of the text is clamped to it`() {
        val content = resolve(nodeText = "hi", nodeStart = 99, nodeEnd = 99)
        assertEquals(2, content?.start)
        assertEquals(2, content?.end)
    }

    @Test
    fun `an input connection without a usable selection appends instead of writing at minus one`() {
        val content = resolve(ic = FieldContent("hello", -1, -1, "ic"))
        assertEquals(FieldContent("hello", 5, 5, "ic"), content)
    }

    // --- The splice the caller performs with all of this -------------------------------------------

    @Test
    fun `proven content splices the dictation in without touching the rest`() {
        val content = resolve(nodeText = "hello world", nodeStart = 5, nodeEnd = 5)!!
        val spliced = content.text.substring(0, content.start) + " there" +
            content.text.substring(content.end)
        assertEquals("hello there world", spliced)
    }

    @Test
    fun `an unproven field never reaches the splice at all`() {
        // The point of returning null: the caller pastes instead, and a paste cannot prepend anything.
        assertTrue(resolve(nodeText = "Ask Meta AI or Search") == null)
    }
}
