/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.snippet

import dev.patrickgold.florisboard.dictate.data.prompts.PromptModel
import dev.patrickgold.florisboard.dictate.data.prompts.snippetBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What may expand while typing (issue #283).
 *
 * Two halves matter here: which prompts end up in the index at all — an AI prompt must never be
 * reachable by typing, or a shortcut would quietly start a paid request — and which typed word counts
 * as a trigger, which is the difference between a useful shortcut and a keyboard that rewrites words
 * the user meant to keep.
 */
class SnippetTriggersTest {

    private fun prompt(name: String, text: String?, trigger: String? = null) =
        PromptModel(
            id = 0,
            pos = 0,
            name = name,
            prompt = text,
            requiresSelection = false,
            autoApply = false,
            trigger = trigger,
        )

    // --- the index --------------------------------------------------------------------------------

    @Test
    fun `a snippet with a trigger is indexed under its lowercased trigger`() {
        val index = SnippetTriggers.indexOf(listOf(prompt("Sign-off", "[Best regards,\nMax]", "R5")))
        assertEquals(mapOf("r5" to "Best regards,\nMax"), index)
    }

    @Test
    fun `an AI prompt never enters the index, even with a trigger`() {
        val index = SnippetTriggers.indexOf(listOf(prompt("Fix grammar", "Fix the grammar", "fix")))
        assertTrue(index.isEmpty())
    }

    @Test
    fun `a snippet without a trigger is not reachable by typing`() {
        val index = SnippetTriggers.indexOf(listOf(prompt("Shrug", "[¯\\_(ツ)_/¯]")))
        assertTrue(index.isEmpty())
    }

    @Test
    fun `a blank or whitespace trigger is ignored`() {
        val prompts = listOf(
            prompt("A", "[a]", "   "),
            prompt("B", "[b]", ""),
            prompt("C", "[c]", "two words"),
        )
        assertTrue(SnippetTriggers.indexOf(prompts).isEmpty())
    }

    @Test
    fun `a trigger is trimmed before it is indexed`() {
        val index = SnippetTriggers.indexOf(listOf(prompt("Sign-off", "[bye]", "  br  ")))
        assertEquals(mapOf("br" to "bye"), index)
    }

    @Test
    fun `two prompts claiming the same trigger resolve to the first one`() {
        val prompts = listOf(
            prompt("First", "[one]", "br"),
            prompt("Second", "[two]", "BR"),
        )
        assertEquals(mapOf("br" to "one"), SnippetTriggers.indexOf(prompts))
    }

    @Test
    fun `an empty snippet is still a snippet`() {
        // "[]" means the user wants the shortcut to disappear — a strange but legitimate wish.
        assertEquals(mapOf("x" to ""), SnippetTriggers.indexOf(listOf(prompt("Nothing", "[]", "x"))))
    }

    // --- snippetBody ------------------------------------------------------------------------------

    @Test
    fun `snippet body is everything between the brackets, line breaks included`() {
        assertEquals("Hi,\n\nBye", prompt("S", "[Hi,\n\nBye]").snippetBody())
    }

    @Test
    fun `a half-written bracket is not a snippet`() {
        assertNull(prompt("S", "[unclosed").snippetBody())
        assertNull(prompt("S", "unopened]").snippetBody())
        assertNull(prompt("S", "[").snippetBody())
        assertNull(prompt("S", null).snippetBody())
    }

    // --- the typed word ---------------------------------------------------------------------------

    @Test
    fun `the word before the cursor is the trailing run of non-whitespace`() {
        assertEquals("r5", SnippetTriggers.triggerCandidate("Hi, r5"))
    }

    @Test
    fun `a word at the very start of the field counts`() {
        assertEquals("r5", SnippetTriggers.triggerCandidate("r5"))
    }

    @Test
    fun `a line break separates just like a space`() {
        assertEquals("r5", SnippetTriggers.triggerCandidate("Hello\nr5"))
    }

    @Test
    fun `nothing is pending right after a space`() {
        assertNull(SnippetTriggers.triggerCandidate("Hi, r5 "))
        assertNull(SnippetTriggers.triggerCandidate(""))
    }

    @Test
    fun `punctuation stays part of the word, so iOS-style shortcuts work`() {
        assertEquals(";sig", SnippetTriggers.triggerCandidate("Hello ;sig"))
    }

    @Test
    fun `a trigger buried inside a longer word is not a candidate`() {
        // Whitespace-delimited on purpose: "Hallor5" is one word and must stay one word.
        assertEquals("Hallor5", SnippetTriggers.triggerCandidate("Hallor5"))
    }

    @Test
    fun `a word longer than any possible trigger is refused outright`() {
        assertNull(SnippetTriggers.triggerCandidate("a".repeat(SnippetTriggers.MAX_LENGTH + 1)))
        assertEquals(
            "a".repeat(SnippetTriggers.MAX_LENGTH),
            SnippetTriggers.triggerCandidate("word " + "a".repeat(SnippetTriggers.MAX_LENGTH)),
        )
    }

    // --- validation -------------------------------------------------------------------------------

    @Test
    fun `a valid trigger is one word of at most the maximum length`() {
        assertTrue(SnippetTriggers.isValidTrigger("r5"))
        assertTrue(SnippetTriggers.isValidTrigger(";sig"))
        assertTrue(SnippetTriggers.isValidTrigger("грüß"))
        assertTrue(SnippetTriggers.isValidTrigger("a".repeat(SnippetTriggers.MAX_LENGTH)))
    }

    @Test
    fun `whitespace, emptiness and overlong input are refused`() {
        assertFalse(SnippetTriggers.isValidTrigger(""))
        assertFalse(SnippetTriggers.isValidTrigger("two words"))
        assertFalse(SnippetTriggers.isValidTrigger("line\nbreak"))
        assertFalse(SnippetTriggers.isValidTrigger("tab\ttab"))
        assertFalse(SnippetTriggers.isValidTrigger("a".repeat(SnippetTriggers.MAX_LENGTH + 1)))
    }
}
