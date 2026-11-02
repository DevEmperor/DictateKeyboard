/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.search

import dev.patrickgold.florisboard.R
import kotlin.test.Test
import kotlin.test.assertEquals

/** The "Popular" list under an empty search box: each title once, opening the screen itself. */
class PopularSearchEntriesTest {

    @Test
    fun `every popular title appears exactly once`() {
        val popular = popularEntries(SettingsSearchIndex.entries)
        assertEquals(POPULAR_TITLES, popular.map { it.titleRes })
    }

    @Test
    fun `a screen indexed twice is listed as itself, not as its parent's row`() {
        val providers = popularEntries(SettingsSearchIndex.entries)
            .single { it.titleRes == R.string.dictate__providers_title }
        assertEquals(R.string.dictate__providers_title, providers.sectionRes)
    }
}
