/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.keyboard

import dev.patrickgold.florisboard.ime.text.key.KeyType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which keystrokes belong to an open in-keyboard search (issue #317).
 *
 * This is the rule that decides between the search box and the app's text field, and getting it wrong
 * is not a cosmetic failure: what does not go into the query goes into the message the user is
 * writing. It was wrong for every digit, because the number row declares its keys as `numeric` in the
 * layout files while the rule accepted only `character`.
 */
class SearchKeyTest {

    @Test
    fun `letters go into the search`() {
        assertTrue(keyProducesSearchText(KeyType.CHARACTER, "a"))
    }

    /**
     * The regression this rule exists for. `test1` used to leave the `1` in the chat while `test`
     * filtered the stickers.
     */
    @Test
    fun `digits from the number row go into the search too`() {
        assertTrue(keyProducesSearchText(KeyType.NUMERIC, "1"))
    }

    @Test
    fun `punctuation and symbols go into the search`() {
        assertTrue(keyProducesSearchText(KeyType.CHARACTER, "-"))
        assertTrue(keyProducesSearchText(KeyType.CHARACTER, "_"))
    }

    /** Keys that do a job of their own have to reach the keyboard, not the query. */
    @Test
    fun `keys that are not text stay with the keyboard`() {
        assertFalse(keyProducesSearchText(KeyType.MODIFIER, ""))
        assertFalse(keyProducesSearchText(KeyType.FUNCTION, ""))
        assertFalse(keyProducesSearchText(KeyType.NAVIGATION, ""))
        assertFalse(keyProducesSearchText(KeyType.SYSTEM_GUI, ""))
        assertFalse(keyProducesSearchText(KeyType.ENTER_EDITING, ""))
    }

    /** A key of the right type that writes nothing writes nothing into the query either. */
    @Test
    fun `a character key with no text is not text`() {
        assertFalse(keyProducesSearchText(KeyType.CHARACTER, ""))
        assertFalse(keyProducesSearchText(KeyType.NUMERIC, ""))
    }
}
