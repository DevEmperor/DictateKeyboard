/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.text.gestures

import kotlin.math.abs

/**
 * How far a finger must travel, in quarter-thresholds, before a swipe on a character key commits
 * without waiting for the finger to lift.
 *
 * The detector reports travel in units of a quarter of the user's *swipe distance threshold*, so 6 units
 * is one and a half of that threshold — 48 dp at the stock setting. That is wider than a key on a normal
 * phone, which is the point: it has to be a distance no ordinary tap covers, because this path has no
 * speed requirement to fall back on. It is deliberately larger than the threshold used by the lift-off
 * path, which still demands real speed and may therefore be more forgiving about distance.
 *
 * Measured on a 420 dpi device, keys 41 dp wide and 57 dp tall, with the stock settings:
 *
 *  - nothing below **53 dp** of travel commits, at any speed up to 1650 dp/s
 *  - everything from **57 dp** commits, at any speed, including a finger that has come to a stop
 *
 * The gap between the nominal 48 and the observed 57 is one reporting step: travel is only inspected when
 * the detector reports a move, and that happens once per unit. Reliable commitment therefore begins about
 * one and a half key widths out, which is the distance this is meant to describe.
 */
internal const val SWIPE_COMMIT_UNITS = 6

/**
 * How much the travelled axis must beat the other one for the direction to count as deliberate.
 *
 * A thumb never moves along a perfect axis, and the eight-sector [SwipeGesture.Detector.detectDirection]
 * used at lift-off calls anything within 22.5° of horizontal a LEFT. That is fine once speed has already
 * shown intent, but committing mid-gesture on a 40° diagonal would turn a sloppy reach into a language
 * switch. 1.5 is the same ratio the legacy layout uses to claim a horizontal swipe.
 */
internal const val SWIPE_COMMIT_DOMINANCE = 1.5f

/**
 * Whether a swipe still in progress has already gone far enough, and clearly enough in one direction, to
 * act on without waiting for the finger to be lifted (issue #327).
 *
 * Waiting for lift-off is what made key swipes feel broken: the accepting rule sampled speed at the moment
 * the finger left the glass, so a swipe the user *ends* — decelerating onto a target, which is exactly
 * what "swipe down to hide the keyboard" is — measured as nearly stationary and was thrown away however
 * far it had travelled. Only a flick still in motion got through.
 *
 * This is the other half of the rule and asks a different question: not "was that fast" but "has that gone
 * somewhere unmistakable". Both coordinates are totals since touch-down — never the increment since the
 * last reported move, which is a wobble and would let a jitter pick the direction.
 *
 * Returns the committed direction, or null while the gesture is still ambiguous. Only the four axis
 * directions can be committed this way; the diagonals exist for the lift-off path and have no actions
 * bound to them.
 */
internal fun swipeCommitDirection(
    absUnitCountX: Int,
    absUnitCountY: Int,
    commitUnits: Int = SWIPE_COMMIT_UNITS,
    dominance: Float = SWIPE_COMMIT_DOMINANCE,
): SwipeGesture.Direction? {
    val x = abs(absUnitCountX)
    val y = abs(absUnitCountY)
    return when {
        x >= commitUnits && x >= y * dominance -> {
            if (absUnitCountX > 0) SwipeGesture.Direction.RIGHT else SwipeGesture.Direction.LEFT
        }
        y >= commitUnits && y >= x * dominance -> {
            if (absUnitCountY > 0) SwipeGesture.Direction.DOWN else SwipeGesture.Direction.UP
        }
        else -> null
    }
}
