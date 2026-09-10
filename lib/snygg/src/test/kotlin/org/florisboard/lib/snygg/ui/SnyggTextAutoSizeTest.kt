/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.florisboard.lib.snygg.ui

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The shrink-to-fit range a themed size turns into (issue #346).
 *
 * The refusals matter more than the arithmetic: this runs on whatever a stylesheet resolved to, and a
 * third-party theme can hand it a size that is missing or not a number at all. A NaN bound reaches
 * Compose's measure pass and crashes the keyboard there, which is the failure the guards in this file
 * already exist for.
 */
class SnyggTextAutoSizeTest {
    @Test
    fun `a ratio turns the themed size into its own upper bound`() {
        assertEquals(
            TextAutoSize.StepBased(minFontSize = 10.5.sp, maxFontSize = 14.sp, stepSize = 0.5.sp),
            autoSizeFor(14.sp, 0.75f),
        )
    }

    @Test
    fun `no ratio means the size stays as the theme set it`() {
        assertNull(autoSizeFor(14.sp, null))
    }

    @Test
    fun `a ratio that cannot shrink anything is not a range`() {
        // Both would ask Compose to search a range of one size, or an inverted one.
        assertNull(autoSizeFor(14.sp, 1f))
        assertNull(autoSizeFor(14.sp, 1.5f))
        assertNull(autoSizeFor(14.sp, 0f))
        assertNull(autoSizeFor(14.sp, -0.5f))
    }

    @Test
    fun `a size the theme never gave is left alone`() {
        assertNull(autoSizeFor(TextUnit.Unspecified, 0.75f))
    }

    @Test
    fun `a malformed theme cannot hand a non-finite bound to the measure pass`() {
        assertNull(autoSizeFor(Float.NaN.sp, 0.75f))
        assertNull(autoSizeFor(Float.POSITIVE_INFINITY.sp, 0.75f))
    }
}
