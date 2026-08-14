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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule that decides whether a typewriter-mode dictation counts as inserted (issue #277).
 *
 * In that mode every character is a separate write into the accessibility service, so a 400-character
 * dictation makes 400 chances to come back false. Latching on any one of them reported "Couldn't insert
 * into this app" while the text sat visibly in the field — and those false alarms are the reason
 * verifying the insert was ruled out in the first place.
 */
class TypedCommitLandedTest {

    @Test
    fun `a clean run has landed`() {
        assertTrue(DictateController.typedCommitLanded(List(400) { true }))
    }

    @Test
    fun `a field that refuses the first character has not landed`() {
        assertFalse(DictateController.typedCommitLanded(listOf(false)))
        assertFalse(DictateController.typedCommitLanded(listOf(false) + List(399) { true }))
    }

    @Test
    fun `one flake in the middle of a long dictation is not a failure`() {
        // The case that produced the false alarm: 399 of 400 characters land, one write is refused
        // while the host app rebuilds its field, and the user was told nothing was inserted.
        val results = MutableList(400) { true }
        results[217] = false
        assertTrue(DictateController.typedCommitLanded(results))
    }

    @Test
    fun `even a mostly refused run counts as landed once the first character went in`() {
        // Deliberate: if the field took the first character it accepts our writes at all, and what
        // happens afterwards is the app's business, not a reason to claim the dictation was lost.
        assertTrue(DictateController.typedCommitLanded(listOf(true) + List(399) { false }))
    }

    @Test
    fun `nothing to write cannot fail`() {
        assertTrue(DictateController.typedCommitLanded(emptyList()))
    }
}
