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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The verdict behind the floating button's read-back verification (issue #277).
 *
 * The accessibility input connection's `commitText` returns void, so the only way to learn whether a
 * write reached the field is to read it back. An earlier attempt at that — through the accessibility
 * node, with its placeholder heuristic — produced false "couldn't insert" errors and was abandoned, so
 * the rule here is deliberately lopsided: **only a field that demonstrably did not change counts as a
 * failure.** Everything else keeps the behaviour that existed before verification.
 */
class InsertLandedTest {

    private fun landed(before: String?, after: String?) =
        DictateAccessibilityService.insertLandedFrom(before, after)

    @Test
    fun `an unchanged field is the one case that counts as swallowed`() {
        assertFalse(landed("", ""))
        assertFalse(landed("Hello", "Hello"))
    }

    @Test
    fun `text appearing means it landed`() {
        assertTrue(landed("", "Hello"))
        assertTrue(landed("Hello ", "Hello world"))
    }

    @Test
    fun `an app that reformats what it was given still counts as landed`() {
        // The reason the rule is "did anything change" rather than "does it end with what we sent":
        // apps capitalise, trim and reflow, and every one of those would otherwise read as a failure.
        assertTrue(landed("", "Hello"))          // capitalised
        assertTrue(landed("", "hello"))          // trimmed
        assertTrue(landed("a", "a hello…"))      // reflowed / ellipsised
    }

    @Test
    fun `a field that got shorter still counts as landed`() {
        // commitText replaces the active selection, so a shorter result is a legitimate outcome —
        // and either way, something happened.
        assertTrue(landed("a long selection", "x"))
    }

    @Test
    fun `an unreadable field is never called a failure`() {
        // No connection, no getSurroundingText support, an exception: all null, all treated exactly as
        // they were before verification existed.
        assertTrue(landed(null, null))
        assertTrue(landed(null, "anything"))
        assertTrue(landed("anything", null))
    }

    @Test
    fun `whitespace is not special-cased away`() {
        // A write of a single space is a real write; it must not read as unchanged.
        assertTrue(landed("word", "word "))
    }
}
