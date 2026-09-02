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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where the floating button is allowed to write (issue #310).
 *
 * The rule is one sentence — only what input focus points at — and the case it exists for is the one
 * from the report: a browser where the focused thing is not a field the accessibility tree recognises,
 * while the *window* contains a perfectly good editable node somewhere else. That node is the address
 * bar. Taking it meant dictation went into the URL bar, and because the service focused its chosen node
 * before writing, it moved the user's cursor there too.
 */
class DictationTargetTest {

    /** A stand-in for an accessibility node: a name, whether it is a field, and its children. */
    private data class Node(
        val name: String,
        val editable: Boolean = false,
        val children: List<Node> = emptyList(),
    )

    private fun target(focused: Node?, maxDepth: Int = 6): Node? =
        DictateAccessibilityService.targetUnderFocus(
            focused = focused,
            editable = { it.editable },
            children = { it.children },
            maxDepth = maxDepth,
        )

    // Chrome, as reported: the toolbar's URL bar is a real editable node, the notepad in the page is a
    // contenteditable the tree does not mark as one.
    private val urlBar = Node("urlBar", editable = true)
    private val notepad = Node("contenteditable div")
    private val chrome = Node(
        "window",
        children = listOf(
            Node("toolbar", children = listOf(urlBar)),
            Node("web contents", children = listOf(notepad)),
        ),
    )

    @Test
    fun `an unrecognised focused field never resolves to the address bar`() {
        // The whole point. Nothing is a legitimate answer: the caller writes through the input connection,
        // which addresses the field the user actually tapped, and only falls back to the clipboard.
        assertNull(target(notepad))
    }

    @Test
    fun `a focused field is the target`() {
        assertEquals(urlBar, target(urlBar))
    }

    @Test
    fun `a focused container hands over the field it holds`() {
        // Cross-platform and wrapped UIs focus the wrapper, not the editor inside it.
        val input = Node("input", editable = true)
        val wrapper = Node("wrapper", children = listOf(Node("row", children = listOf(input))))
        assertEquals(input, target(wrapper))
    }

    @Test
    fun `the first field in the focused subtree wins`() {
        val first = Node("first", editable = true)
        val second = Node("second", editable = true)
        assertEquals(first, target(Node("form", children = listOf(first, second))))
    }

    @Test
    fun `nothing focused, nothing to write into`() {
        assertNull(target(null))
    }

    @Test
    fun `the walk stays bounded`() {
        // Every level costs an IPC round trip, so a field buried deeper than the bound is not looked for.
        var deep = Node("field", editable = true)
        repeat(8) { i -> deep = Node("level$i", children = listOf(deep)) }
        assertNull(target(deep, maxDepth = 3))
        assertEquals("field", target(deep, maxDepth = 9)?.name)
    }

    @Test
    fun `a sibling branch is never searched`() {
        // Restating the bug from the other side: focus inside the web contents must not reach the toolbar.
        assertNull(target(chrome.children[1]))
        // …while focusing the toolbar does find its own field, so the walk itself still works.
        assertEquals(urlBar, target(chrome.children[0]))
    }
}
