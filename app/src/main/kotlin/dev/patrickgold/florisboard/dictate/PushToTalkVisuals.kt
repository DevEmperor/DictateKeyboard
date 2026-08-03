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

/**
 * Everything the hold-to-record visuals (issue #235) need, in one value.
 *
 * Deliberately a single state rather than a flow per concern. The phase, the lock confirmation and the
 * discard flight all change at the same instant, but as separate flows they reached the UI in separate
 * frames — and each of those in-between frames was visible: the key flashing its ordinary icon before
 * the lock appeared, and the thrown mic snapping back to the key for a frame before setting off. One
 * value cannot be observed half-updated.
 */
data class PushToTalkVisuals(
    val phase: PushToTalkPhase = PushToTalkPhase.NONE,
    /** True for a moment after latching, while the key shows a lock instead of its usual icon. */
    val lockFlash: Boolean = false,
    /** True from the instant a held recording is thrown away until the mic has landed in the bin. */
    val discarding: Boolean = false,
) {
    /** True whenever the swollen mic should be on screen — held, or still in flight towards the bin. */
    val micShown: Boolean get() = phase.isHolding || discarding
}
