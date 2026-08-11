/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.ui

import androidx.compose.ui.unit.IntRect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the on-screen targets of a hold-to-record gesture (issue #235) actually are.
 *
 * The swollen mic is drawn by the Smartbar's mic key, but the bin it is thrown into belongs to the
 * recording bar — a different composable in a different package. Guessing a direction and a distance
 * produced a throw that only roughly pointed at the bin; reporting the measured position lets it land
 * in it. Null while the bar is not on screen.
 */
object DictateHoldTargets {
    private val _binBounds = MutableStateFlow<IntRect?>(null)

    /** Window bounds of the discard button on the recording bar. */
    val binBounds: StateFlow<IntRect?> = _binBounds.asStateFlow()

    fun reportBinBounds(bounds: IntRect?) {
        _binBounds.value = bounds
    }
}
