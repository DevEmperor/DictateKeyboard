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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers when an app's `TYPE_TEXT_FLAG_NO_SUGGESTIONS` may be overruled (issue #296).
 *
 * The setting is the easy half. The half worth a test is the exception: a password field is what the flag
 * was written for, and a composing region there hands the typed word to the dictionary, the autocorrect
 * and — in the wrong app — the screen. Every spelling of a password field has to be caught, which is why
 * this is a list rather than a single check.
 */
class NoSuggestionsFlagOverrideTest {

    private fun mayIgnore(enabled: Boolean, variation: InputAttributes.Variation) =
        mayIgnoreNoSuggestionsFlag(ignoreAppSuggestionBlock = enabled, variation = variation)

    @Test
    fun `off means the app is obeyed`() {
        assertFalse(mayIgnore(enabled = false, variation = InputAttributes.Variation.NORMAL))
    }

    @Test
    fun `on overrules an ordinary text field`() {
        assertTrue(mayIgnore(enabled = true, variation = InputAttributes.Variation.NORMAL))
        assertTrue(mayIgnore(enabled = true, variation = InputAttributes.Variation.WEB_EDIT_TEXT))
        assertTrue(mayIgnore(enabled = true, variation = InputAttributes.Variation.SHORT_MESSAGE))
    }

    @Test
    fun `no setting overrules a password field`() {
        for (variation in listOf(
            InputAttributes.Variation.PASSWORD,
            InputAttributes.Variation.VISIBLE_PASSWORD,
            InputAttributes.Variation.WEB_PASSWORD,
        )) {
            assertFalse(mayIgnore(enabled = true, variation = variation), "$variation must stay untouched")
        }
    }
}
