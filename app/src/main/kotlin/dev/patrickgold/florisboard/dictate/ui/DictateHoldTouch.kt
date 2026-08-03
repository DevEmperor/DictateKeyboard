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

import android.content.Context
import android.view.MotionEvent
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.ime.input.InputFeedbackController
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData

/**
 * The finger, for as long as a push-to-talk recording is being held (#235).
 *
 * Compose's own pointer stream cannot be relied on here. Measured on a real-time hold: the gesture is
 * handed exactly one event, a synthetic release with the finger unmoved and still pressed, about 110 ms
 * in — while the window keeps being delivered the real touch for another two and a half seconds, and the
 * genuine ACTION_UP arrives long after. Compose ends its own dispatch for the whole tree; no MotionEvent
 * is involved, the key never leaves composition, and neither `pointerInput` key changes. Whatever inside
 * Compose does that, a hold must not depend on it.
 *
 * So the gesture layer only decides that the mic was pressed and held. Everything after that — sliding,
 * latching, releasing — is driven from [dispatch], fed straight from the IME root view, which is ours and
 * sees every event the window gets. Thresholds arrive in pixels because this side has no density.
 */
object DictateHoldTouch {
    /** Which way a held mic has committed to travel. It stays committed until the finger comes back. */
    private enum class Axis { NONE, LEFT, UP }

    private var lastDownId = -1
    private var lastDownX = 0f
    private var lastDownY = 0f

    private var trackedId = -1
    private var originX = 0f
    private var originY = 0f
    private var axis = Axis.NONE
    private var finished = false

    private var cancelSlidePx = 0f
    private var lockSlidePx = 0f
    private var commitPx = 0f
    private var releasePx = 0f
    private var context: Context? = null
    private var feedback: InputFeedbackController? = null

    /** True while a hold is being tracked here rather than by the gesture layer. */
    val isTracking: Boolean get() = trackedId >= 0

    /**
     * Takes over the finger that is currently down, which is the one that just became a hold. Its position
     * when it landed is the origin every distance below is measured from — the same reference the gesture
     * layer used, so the thresholds mean exactly what they did.
     */
    fun begin(
        context: Context,
        feedback: InputFeedbackController,
        cancelSlidePx: Float,
        lockSlidePx: Float,
        commitPx: Float,
        releasePx: Float,
    ) {
        if (lastDownId < 0) return
        trackedId = lastDownId
        originX = lastDownX
        originY = lastDownY
        axis = Axis.NONE
        finished = false
        this.context = context
        this.feedback = feedback
        this.cancelSlidePx = cancelSlidePx
        this.lockSlidePx = lockSlidePx
        this.commitPx = commitPx
        this.releasePx = releasePx
    }

    /** Every touch the IME window receives, before anyone gets to consume or cancel it. */
    fun dispatch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                lastDownId = event.getPointerId(index)
                lastDownX = event.getX(index)
                lastDownY = event.getY(index)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(trackedId).takeIf { it >= 0 } ?: return
                onMove(event.getX(index) - originX, event.getY(index) - originY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == trackedId) release()
            }
            // A real cancel: the finger is gone without a decision having been made. Sending something the
            // user never released on would be worse than keeping it, so this latches like the lock does.
            MotionEvent.ACTION_CANCEL -> if (isTracking) {
                val ctx = context
                stop()
                if (ctx != null) DictateController.lockPushToTalk()
            }
        }
    }

    private fun onMove(dx: Float, dy: Float) {
        if (finished) return
        val left = (-dx).coerceAtLeast(0f)
        val upward = (-dy).coerceAtLeast(0f)
        axis = when (axis) {
            Axis.NONE -> when {
                left < commitPx && upward < commitPx -> Axis.NONE
                left >= upward -> Axis.LEFT
                else -> Axis.UP
            }
            // Committed: only coming back near the start frees it to pick the other way, so a drag up
            // followed by a drift left cannot snatch the mic onto the bin path mid-gesture.
            Axis.LEFT -> if (left < releasePx) Axis.NONE else Axis.LEFT
            Axis.UP -> if (upward < releasePx) Axis.NONE else Axis.UP
        }
        val goingUp = axis == Axis.UP
        if (goingUp && upward > lockSlidePx) {
            DictateController.lockPushToTalk()
            feedback?.gestureSwipe(TextKeyData.UNSPECIFIED)
            stop()
            return
        }
        DictateController.onPushToTalkLockSlide(if (goingUp) upward / lockSlidePx else 0f)
        // Crossing the cancel threshold discards there and then, rather than on release: waiting would
        // leave the user holding a recording they have already thrown away.
        if (DictateController.onPushToTalkSlide(if (axis == Axis.LEFT) left / cancelSlidePx else 0f)) {
            feedback?.gestureSwipe(TextKeyData.UNSPECIFIED)
            stop()
        }
    }

    private fun release() {
        val ctx = context ?: run { stop(); return }
        stop()
        DictateController.onPushToTalkUp(ctx)
    }

    /** Hands the finger back; further events are none of our business until the next hold. */
    fun stop() {
        finished = true
        trackedId = -1
        axis = Axis.NONE
        context = null
        feedback = null
    }
}
