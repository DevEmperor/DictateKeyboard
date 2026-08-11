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
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.ime.input.InputFeedbackController
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickAction
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The whole press on the mic, from the moment it lands until it lifts (#235).
 *
 * None of this can live in the gesture layer's coroutine. Measured across several builds, Compose ends
 * that coroutine of its own accord part way into a press — sometimes by handing it a synthetic release
 * with the finger unmoved and still pressed, sometimes by cancelling it outright, leaving no trace at
 * all — while the window goes on receiving the real touch until the genuine release seconds later. That
 * is what made the first press after every held recording do nothing: the press was recognised, the
 * overlay windows were even created, and then the coroutine holding the rest of the logic was gone.
 *
 * So the gesture layer only reports that the mic was pressed, and everything after that — the delay that
 * separates a tap from a hold, sliding, latching, releasing — is decided here, driven by [dispatch] from
 * the IME root view and a plain main-thread delay. Thresholds arrive in pixels because this side has no
 * density, and the [QuickAction] comes along so a tap can still be delivered as the ordinary key press.
 */
object DictateHoldTouch {
    /** Which way a held mic has committed to travel. It stays committed until the finger comes back. */
    private enum class Axis { NONE, LEFT, UP }

    private val handler = Handler(Looper.getMainLooper())

    private var lastDownId = -1
    private var lastDownX = 0f
    private var lastDownY = 0f
    private var lastDownStillDown = false

    /** The press being waited on to see whether it becomes a hold, or -1. */
    private var pendingId = -1
    /** The finger of a hold in progress, or -1. */
    private var trackedId = -1
    private var originX = 0f
    private var originY = 0f
    private var axis = Axis.NONE

    private var cancelSlidePx = 0f
    private var lockSlidePx = 0f
    private var commitPx = 0f
    private var releasePx = 0f
    private var context: Context? = null
    private var feedback: InputFeedbackController? = null
    private var action: QuickAction? = null

    private val _pressed = MutableStateFlow(false)

    /**
     * True from the finger landing on the mic until the press is over, however it ends.
     *
     * The overlay reads this to put its window on screen early: adding one costs several frames, and
     * paying them at the moment the hold begins is exactly when they show.
     */
    val pressed: StateFlow<Boolean> = _pressed.asStateFlow()

    private val holdDue = Runnable { startHold() }

    /**
     * The mic was pressed. If the finger is still there once [holdDelayMs] have passed this becomes a
     * recording; if it lifts first it stays the ordinary tap it looks like.
     */
    fun arm(
        context: Context,
        action: QuickAction,
        feedback: InputFeedbackController,
        holdDelayMs: Long,
        cancelSlidePx: Float,
        lockSlidePx: Float,
        commitPx: Float,
        releasePx: Float,
    ) {
        if (lastDownId < 0 || !lastDownStillDown) return
        cancel()
        pendingId = lastDownId
        this.context = context
        this.action = action
        this.feedback = feedback
        this.cancelSlidePx = cancelSlidePx
        this.lockSlidePx = lockSlidePx
        this.commitPx = commitPx
        this.releasePx = releasePx
        _pressed.value = true
        handler.postDelayed(holdDue, holdDelayMs)
    }

    /** Every touch the IME window receives, before anyone gets to consume or cancel it. */
    fun dispatch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                lastDownId = event.getPointerId(index)
                lastDownX = event.getX(index)
                lastDownY = event.getY(index)
                lastDownStillDown = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (trackedId < 0) return
                val index = event.findPointerIndex(trackedId).takeIf { it >= 0 } ?: return
                onMove(event.getX(index) - originX, event.getY(index) - originY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (id == lastDownId) lastDownStillDown = false
                when (id) {
                    trackedId -> {
                        val ctx = context
                        cancel()
                        if (ctx != null) DictateController.onPushToTalkUp(ctx)
                    }
                    // Lifted before it ever became a hold: the ordinary tap, delivered as the key press
                    // the gesture layer opened and never closed.
                    pendingId -> {
                        val ctx = context
                        val act = action
                        cancel()
                        if (ctx != null && act != null) act.onPointerUp(ctx)
                    }
                }
            }
            // A real cancel. Mid-hold there is nothing to send — nobody released anything — so it latches,
            // which at least keeps what was said. Before that it is simply a press that came to nothing.
            MotionEvent.ACTION_CANCEL -> {
                lastDownStillDown = false
                val wasHolding = trackedId >= 0
                val act = action
                val ctx = context
                cancel()
                if (wasHolding) DictateController.lockPushToTalk() else if (ctx != null) act?.onPointerCancel(ctx)
            }
        }
    }

    /** The delay elapsed with the finger still down: this press is a recording. */
    private fun startHold() {
        val ctx = context ?: return
        if (pendingId < 0 || !lastDownStillDown || lastDownId != pendingId) {
            cancel()
            return
        }
        // Withdraw the key press the gesture layer opened with. The hold takes the whole press, so the key
        // itself must not also fire — and the dispatcher drops any later press of a key it still believes
        // to be held (see InputEventDispatcher.sendDown).
        action?.onPointerCancel(ctx)
        DictateController.onPushToTalkDown(ctx)
        // The one moment worth feeling: the press has become a recording. The tick when the finger landed
        // is the ordinary key one and fires for a tap that records nothing.
        feedback?.keyLongPress(TextKeyData.UNSPECIFIED)
        trackedId = pendingId
        pendingId = -1
        originX = lastDownX
        originY = lastDownY
        axis = Axis.NONE
    }

    private fun onMove(dx: Float, dy: Float) {
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
            cancel()
            return
        }
        DictateController.onPushToTalkLockSlide(if (goingUp) upward / lockSlidePx else 0f)
        // Crossing the cancel threshold discards there and then, rather than on release: waiting would
        // leave the user holding a recording they have already thrown away.
        if (DictateController.onPushToTalkSlide(if (axis == Axis.LEFT) left / cancelSlidePx else 0f)) {
            feedback?.gestureSwipe(TextKeyData.UNSPECIFIED)
            cancel()
        }
    }

    /** Hands the finger back; further events are none of our business until the next press. */
    fun cancel() {
        handler.removeCallbacks(holdDue)
        pendingId = -1
        trackedId = -1
        axis = Axis.NONE
        context = null
        feedback = null
        action = null
        _pressed.value = false
    }
}
