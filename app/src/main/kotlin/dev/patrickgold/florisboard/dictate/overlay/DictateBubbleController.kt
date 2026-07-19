/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.DictateFloatingButtonDesign
import dev.patrickgold.florisboard.dictate.DictateFloatingButtonSize
import dev.patrickgold.florisboard.dictate.data.prompts.PromptModel
import dev.patrickgold.florisboard.dictate.data.prompts.PromptsDatabaseHelper
import dev.patrickgold.florisboard.dictate.ui.AudioReactiveCloudOrbView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * Owns the floating dictation button (issue #88): a small draggable bubble shown over other apps via a
 * [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] window (no draw-over-apps permission needed —
 * the window is hosted by the accessibility service). The bubble appears whenever an editable field is
 * focused (or a dictation is in flight), toggles recording on tap, snaps to the nearest screen edge when
 * dragged, and routes the result through [DictateController] with [DictateController.OutputTarget.OVERLAY]
 * so the text is injected into the focused field.
 *
 * The visuals are provided by a [BubbleSkin] — [RingSkin], [PillSkin], [OrbSkin], or [CloudSkin] — selected by the
 * `floatingButtonDesign` preference. While recording, a level ticker feeds the chosen skin the shared
 * normalized microphone level.
 *
 * Created and owned by [DictateAccessibilityService], which also provides the foreground-microphone
 * promotion the recording needs while the app is in the background.
 */
class DictateBubbleController(private val service: DictateAccessibilityService) {

    private val context: Context get() = service
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val prefs by FlorisPreferenceStore

    private var rootView: View? = null
    private var skin: BubbleSkin? = null
    private var params: WindowManager.LayoutParams? = null
    private var added = false

    /** Secondary cancel button shown beside the bubble while recording. */
    private var cancelView: View? = null
    private var cancelParams: WindowManager.LayoutParams? = null
    private var cancelAdded = false
    private val cancelSize get() = sdp(34)

    /** Optional undo button shown beside the bubble right after a dictation (issue #133). */
    private var undoView: View? = null
    private var undoParams: WindowManager.LayoutParams? = null
    private var undoAdded = false
    private val undoSize get() = sdp(34)
    /** True from when a dictation just finished until the next recording starts or undo is tapped. */
    private var justDictated = false

    /** Long-press rewording menu window. */
    private var menuView: View? = null
    private var menuAdded = false

    private var currentDesign = DictateFloatingButtonDesign.PILL
    private var sizeScale = DictateFloatingButtonSize.MEDIUM.scale
    private var accentColor = 0xFF30B7E6.toInt()

    /** Whether the bubble is currently anchored to the right edge (drives which way the pill expands). */
    private var anchoredToRight = false

    /**
     * Set until the bubble has been placed at its default spot (right edge, vertically centered) once the
     * view is first measured. The window params can't compute that before the bubble's size is known, so
     * the default placement is finalized in the first layout pass — with a margin regardless of the
     * snap-to-edge setting (unlike snapping, which only adds the margin when enabled).
     */
    private var needsInitialPlacement = true

    /** Per-app saved positions (in-memory for the service's lifetime) and the current foreground app. */
    private val positions = HashMap<String, Pair<Int, Int>>()
    private var currentPackage: String? = null

    /** While true a transient error/success flash owns the visuals; live-state updates are suppressed. */
    private var holding = false
    private var holdJob: Job? = null

    /** Polls the mic level while recording to drive the waveform. */
    private var tickerJob: Job? = null

    /** Animates the horizontal snap-to-edge after a drag. */
    private var snapAnim: ValueAnimator? = null

    /** Idle auto-dim: shrinks/fades the bubble to a small dot after a while; restored on touch. */
    private var dimJob: Job? = null
    private var dimmed = false
    private var idleShownPrev = false

    /** The state of the previous emission, used to tell a successful finish (busy → idle) from a cancel. */
    private var prevState: DictateController.UiState = DictateController.UiState.Idle

    /** The latest Recording state (timer source for the ticker), or null when not recording. */
    private var recordingState: DictateController.UiState.Recording? = null

    /** The last state pushed to the skin; lets us skip redundant re-applies (combine re-emits on focus). */
    private var lastAppliedState: DictateController.UiState? = null

    /**
     * Whether the bubble started the in-flight dictation. Used to attribute a terminal Error/success state
     * to the overlay (so it is surfaced here) without reacting to a keyboard-driven dictation that happens
     * while the bubble is also visible.
     */
    private var weStartedDictation = false

    /** Inputs that decide whether the bubble is shown and how it looks, combined from prefs + service. */
    private data class Inputs(
        val enabled: Boolean,
        val showWithDictateKeyboard: Boolean,
        val focused: Boolean,
        val dictateKeyboardActive: Boolean,
        val state: DictateController.UiState,
    )

    /** [Inputs] plus the design/size/color prefs and the IME-visible signal; one combined emission. */
    private data class Emission(
        val inputs: Inputs,
        val design: DictateFloatingButtonDesign,
        val size: DictateFloatingButtonSize,
        val imeVisible: Boolean,
        val accentColor: Int,
    )

    /** Starts observing the feature toggle + focus + design + dictation state to drive the bubble. */
    fun start() {
        scope.launch {
            DictateAccessibilityService.foregroundPackage.collect { pkg -> onForegroundPackageChanged(pkg) }
        }
        scope.launch {
            val base = combine(
                prefs.dictate.floatingButtonEnabled.asFlow(),
                prefs.dictate.floatingButtonShowWithDictateKeyboard.asFlow(),
                DictateAccessibilityService.editableFocused,
                DictateAccessibilityService.dictateKeyboardActive,
                DictateController.state,
            ) { enabled, showWithKeyboard, focused, dictateKeyboard, state ->
                Inputs(enabled, showWithKeyboard, focused, dictateKeyboard, state)
            }
            combine(
                base,
                prefs.dictate.floatingButtonDesign.asFlow(),
                prefs.dictate.floatingButtonSize.asFlow(),
                DictateAccessibilityService.imeVisible,
                prefs.dictate.floatingButtonColor.asFlow(),
            ) { inputs, design, size, imeVisible, color ->
                Emission(inputs, design, size, imeVisible, color.toArgb())
            }.collect { (inputs, design, size, imeVisible, accent) ->
                val (enabled, showWithKeyboard, focused, dictateKeyboard, state) = inputs
                if (design != currentDesign || size.scale != sizeScale || accent != accentColor) {
                    currentDesign = design
                    sizeScale = size.scale
                    accentColor = accent
                    rebuildSkin()
                }
                val active = state !is DictateController.UiState.Idle
                // The Dictate keyboard is on screen when it is the selected IME *and* an IME window is
                // visible. While it is up it already has its own mic, so hide the bubble (unless the user
                // opted in). Using the IME-visible signal (not the dictation state) means a keyboard-driven
                // dictation keeps the bubble hidden, while a bubble-driven one — which opens no IME window —
                // still keeps it shown.
                val dictateKeyboardShown = dictateKeyboard && imeVisible
                val hiddenByOwnKeyboard = dictateKeyboardShown && !showWithKeyboard
                val show = enabled && (focused || active) && !hiddenByOwnKeyboard
                if (show) ensureShown() else hide()
                recordingState = state as? DictateController.UiState.Recording
                applyState(state)
                manageForeground(state)
                manageTicker(state)
                manageCancel(state, show)
                // Track when a dictation just finished so the undo button is offered only in that
                // window (until the next recording), not perpetually from a stale cached result.
                if (state is DictateController.UiState.Recording) {
                    justDictated = false
                } else if (state is DictateController.UiState.Idle &&
                    (prevState is DictateController.UiState.Transcribing || prevState is DictateController.UiState.Rewording)
                ) {
                    justDictated = true
                }
                manageUndo(state, show)
                reportTerminalState(state)
                // Auto-dim only while idle and shown; restore (and stop the timer) otherwise.
                val idleShown = state is DictateController.UiState.Idle && show
                if (idleShown && !idleShownPrev) scheduleDim()
                if (!idleShown) {
                    cancelDim()
                    applyDim(false)
                }
                idleShownPrev = idleShown
                prevState = state
            }
        }
    }

    /** Tears everything down: stops observing, removes the window and drops the foreground state. */
    fun destroy() {
        scope.cancel()
        stopTicker()
        cancelDim()
        snapAnim?.cancel()
        hidePromptMenu()
        hide()
        skin?.destroy()
        service.stopMicForeground()
    }

    // --- Window add/remove -----------------------------------------------------------------------

    private fun ensureShown() {
        if (added) return
        val view = rootView ?: createView().also { rootView = it }
        val lp = params ?: createParams().also { params = it }
        // Pin the window height for skins that want it fixed (the pill), so the width-only expand animation
        // never makes the bubble appear to grow vertically; ring uses content size (a fixed square).
        lp.height = skin?.fixedHeight ?: WindowManager.LayoutParams.WRAP_CONTENT
        runCatching {
            windowManager.addView(view, lp)
            added = true
        }
    }

    private fun hide() {
        val view = rootView
        if (added && view != null) runCatching { windowManager.removeView(view) }
        added = false
        // Reset the dim so a re-shown bubble starts fully visible.
        cancelDim()
        dimmed = false
        idleShownPrev = false
        view?.apply {
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
        }
        hideCancel()
        hideUndo()
    }

    // --- Cancel button (shown while recording) ---------------------------------------------------

    private fun manageCancel(state: DictateController.UiState, shown: Boolean) {
        if (shown && state is DictateController.UiState.Recording) showCancel() else hideCancel()
    }

    private fun showCancel() {
        if (cancelAdded) return
        val v = cancelView ?: createCancelView().also { cancelView = it }
        val lp = cancelParams ?: createCancelParams().also { cancelParams = it }
        runCatching {
            windowManager.addView(v, lp)
            cancelAdded = true
            positionCancel()
        }
    }

    private fun hideCancel() {
        val v = cancelView
        if (cancelAdded && v != null) runCatching { windowManager.removeView(v) }
        cancelAdded = false
    }

    private fun createCancelView(): View {
        val size = cancelSize
        val pad = sdp(7)
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_dictate_overlay_close)
            setPadding(pad, pad, pad, pad)
            background = circle(R.color.dictate_overlay_cancel)
            elevation = sdpf(6f)
        }
        return FrameLayout(context).apply {
            addView(icon, FrameLayout.LayoutParams(size, size))
            setOnClickListener {
                if (prefs.dictate.floatingButtonHaptic.get()) vibrateTap()
                DictateController.cancelRecording()
            }
        }
    }

    private fun createCancelParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    // --- Undo button (shown beside the bubble right after a dictation, issue #133) ----------------

    private fun manageUndo(state: DictateController.UiState, shown: Boolean) {
        // Not while the bubble has auto-dimmed to a dot — the undo button would otherwise be left
        // standing at full size next to a shrunken bubble (issue #133 follow-up).
        val canUndo = shown && !dimmed && state is DictateController.UiState.Idle && justDictated &&
            prefs.dictate.floatingButtonUndoEnabled.get() && DictateController.hasLastDictation()
        if (canUndo) showUndo() else hideUndo()
    }

    private fun showUndo() {
        if (undoAdded) return
        val v = undoView ?: createUndoView().also { undoView = it }
        val lp = undoParams ?: createUndoParams().also { undoParams = it }
        runCatching {
            windowManager.addView(v, lp)
            undoAdded = true
            positionUndo()
        }
    }

    private fun hideUndo() {
        val v = undoView
        if (undoAdded && v != null) runCatching { windowManager.removeView(v) }
        undoAdded = false
    }

    private fun createUndoView(): View {
        val size = undoSize
        val pad = sdp(7)
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_dictate_overlay_undo)
            setPadding(pad, pad, pad, pad)
            background = circle(R.color.dictate_overlay_cancel)
            elevation = sdpf(6f)
        }
        return FrameLayout(context).apply {
            addView(icon, FrameLayout.LayoutParams(size, size))
            setOnClickListener {
                if (prefs.dictate.floatingButtonHaptic.get()) vibrateTap()
                val ok = DictateController.undoLastDictation(context)
                justDictated = false
                hideUndo()
                if (!ok) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.dictate__floating_button_undo_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun createUndoParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun positionUndo() {
        val ulp = undoParams ?: return
        val undo = undoView ?: return
        val blp = params ?: return
        val bubble = rootView ?: return
        val uw = undoSize
        val gap = sdp(6)
        ulp.y = (blp.y + (bubble.height - uw) / 2).coerceIn(0, (screenHeight() - uw).coerceAtLeast(0))
        // Same inward-side logic as the cancel button so it sits beside the bubble and follows drags.
        val onRight = blp.x + bubble.width / 2 >= screenWidth() / 2
        val rawX = if (onRight) blp.x - gap - uw else blp.x + bubble.width + gap
        ulp.x = rawX.coerceIn(0, (screenWidth() - uw).coerceAtLeast(0))
        if (undoAdded) runCatching { windowManager.updateViewLayout(undo, ulp) }
    }

    // --- Long-press rewording menu ---------------------------------------------------------------

    private fun onLongPress() {
        val state = DictateController.state.value
        // Rewording only makes sense when not already recording/transcribing.
        if (state !is DictateController.UiState.Idle && state !is DictateController.UiState.Error) return
        if (prefs.dictate.floatingButtonHaptic.get()) vibrateTap()
        cancelDim()
        applyDim(false)
        showPromptMenu()
    }

    private fun showPromptMenu() {
        if (menuAdded) return
        scope.launch {
            val prompts = withContext(Dispatchers.IO) {
                runCatching { PromptsDatabaseHelper.getInstance(context).getAll() }.getOrDefault(emptyList())
            }.filter { !it.name.isNullOrBlank() }
            if (menuAdded) return@launch
            if (prompts.isEmpty()) {
                Toast.makeText(context, context.getString(R.string.dictate__floating_button_no_prompts), Toast.LENGTH_SHORT).show()
                return@launch
            }
            addPromptMenu(prompts)
        }
    }

    private fun addPromptMenu(prompts: List<PromptModel>) {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(color(R.color.dictate_overlay_menu_surface), dpf(16f))
            val p = dp(8)
            setPadding(p, p, p, p)
            isClickable = true // swallow taps so they don't dismiss via the scrim
            elevation = dpf(8f)
        }
        prompts.forEach { prompt ->
            val item = TextView(context).apply {
                text = prompt.name
                setTextColor(color(R.color.dictate_overlay_icon))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                val hz = dp(20)
                val vt = dp(12)
                setPadding(hz, vt, hz, vt)
                setOnClickListener {
                    hidePromptMenu()
                    DictateController.applyPrompt(
                        context, prompt, target = DictateController.OutputTarget.OVERLAY,
                    )
                }
            }
            card.addView(item, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        val scroll = ScrollView(context).apply {
            addView(card)
            val m = dp(24)
            setPadding(m, m, m, m)
            clipToPadding = false
        }
        val scrim = FrameLayout(context).apply {
            setBackgroundColor(0x66000000.toInt())
            setOnClickListener { hidePromptMenu() }
            addView(scroll, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        menuView = scrim
        runCatching {
            windowManager.addView(scrim, lp)
            menuAdded = true
        }
    }

    private fun hidePromptMenu() {
        val v = menuView
        if (menuAdded && v != null) runCatching { windowManager.removeView(v) }
        menuAdded = false
        menuView = null
    }

    private fun roundedRect(colorInt: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(colorInt)
    }

    /** Places the cancel button on the inward side of the bubble, vertically centered, tracking its position. */
    private fun positionCancel() {
        val clp = cancelParams ?: return
        val cancel = cancelView ?: return
        val blp = params ?: return
        val bubble = rootView ?: return
        val cw = cancelSize
        val gap = sdp(6)
        clp.y = (blp.y + (bubble.height - cw) / 2).coerceIn(0, (screenHeight() - cw).coerceAtLeast(0))
        // Put the cancel button on the side that has more room (the inward side), based on the bubble's
        // *current* center — so it follows during a drag and flips when crossing the middle of the screen.
        val onRight = blp.x + bubble.width / 2 >= screenWidth() / 2
        val rawX = if (onRight) blp.x - gap - cw else blp.x + bubble.width + gap
        clp.x = rawX.coerceIn(0, (screenWidth() - cw).coerceAtLeast(0))
        if (cancelAdded) runCatching { windowManager.updateViewLayout(cancel, clp) }
    }

    private fun createView(): View {
        val newSkin = when (currentDesign) {
            DictateFloatingButtonDesign.RING -> RingSkin(context)
            DictateFloatingButtonDesign.PILL -> PillSkin(context)
            DictateFloatingButtonDesign.ORB -> OrbSkin(context)
            DictateFloatingButtonDesign.CLOUD -> CloudSkin(context)
        }
        skin = newSkin
        val root = newSkin.root
        attachTouch(root)
        // Reposition only when the view's *width* changes (the pill expanding/collapsing). A plain
        // position change from dragging/snapping also fires this listener, and repositioning then would
        // fight the drag — pulling the bubble back to the edge mid-drag (flicker). The width check ignores
        // those, so dragging is smooth and it only snaps back on release (via snapToEdge).
        root.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (needsInitialPlacement && right - left > 0) {
                // First time the bubble has a real size: drop it at the default spot (right edge + margin,
                // vertically centered). Independent of snap-to-edge so the margin is always there.
                needsInitialPlacement = false
                applyInitialPlacement()
            } else if (right - left != oldRight - oldLeft) {
                repositionForSize()
                if (cancelAdded) positionCancel() // keep the cancel button beside the (resized) pill
                if (undoAdded) positionUndo()
            }
        }
        newSkin.applyState(DictateController.state.value)
        return root
    }

    /** Rebuilds the view with the currently selected skin, preserving whether it was shown. */
    private fun rebuildSkin() {
        val wasShown = added
        hide()
        skin?.destroy()
        skin = null
        rootView = null
        lastAppliedState = null // fresh skin starts blank; force the next applyState to paint it
        if (wasShown) ensureShown()
    }

    private fun createParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Rough seed for the right edge near mid-height, just to avoid a left-edge flash before the
            // bubble is measured. The exact default (right edge + margin, vertically centered) is applied
            // in applyInitialPlacement once we know the bubble's size.
            anchoredToRight = true
            x = screenWidth()
            y = (screenHeight() * 2 / 5 - dp(28)).coerceAtLeast(0)
        }
    }

    // --- Touch: tap toggles dictation, drag repositions + snaps to the edge ----------------------

    private fun attachTouch(view: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressFired = false
        val longPress = Runnable {
            if (!moved) {
                longPressFired = true
                onLongPress()
            }
        }
        view.setOnTouchListener { v, e ->
            val lp = params ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    longPressFired = false
                    snapAnim?.cancel()
                    cancelDim()
                    applyDim(false) // wake the bubble on touch
                    v.postDelayed(longPress, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > slop) {
                        moved = true
                        v.removeCallbacks(longPress) // a drag cancels the pending long-press
                    }
                    if (moved) {
                        val maxX = (screenWidth() - v.width).coerceAtLeast(0)
                        val maxY = (screenHeight() - v.height).coerceAtLeast(0)
                        lp.x = (startX + dx.toInt()).coerceIn(0, maxX)
                        lp.y = (startY + dy.toInt()).coerceIn(0, maxY)
                        runCatching { windowManager.updateViewLayout(v, lp) }
                        if (cancelAdded) positionCancel() // keep the cancel button following the bubble
                        if (undoAdded) positionUndo()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    when {
                        longPressFired -> Unit // handled by the long-press (prompt menu)
                        !moved -> onTap()
                        // When snapping is off the bubble stays where it was dropped (already clamped within
                        // the screen by ACTION_MOVE); otherwise it animates to the nearer side edge.
                        prefs.dictate.floatingButtonSnapToEdge.get() -> snapToEdge()
                    }
                    if (moved) saveCurrentPosition()
                    scheduleDim() // restart the idle timer after the interaction
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }
    }

    /** Animates the bubble to whichever side edge is nearer, clamping the vertical position on screen. */
    private fun snapToEdge() {
        val lp = params ?: return
        val v = rootView ?: return
        val maxX = (screenWidth() - v.width).coerceAtLeast(0)
        val margin = dp(8).coerceAtMost(maxX / 2)
        anchoredToRight = lp.x + v.width / 2 >= screenWidth() / 2
        val targetX = if (anchoredToRight) maxX - margin else margin
        lp.y = lp.y.coerceIn(0, (screenHeight() - v.height).coerceAtLeast(0))
        snapAnim?.cancel()
        snapAnim = ValueAnimator.ofInt(lp.x, targetX).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                lp.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(v, lp) }
                if (cancelAdded) positionCancel() // let the cancel button ride along to the edge
                if (undoAdded) positionUndo()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    saveCurrentPosition() // persist the final snapped position for this app
                }
            })
            start()
        }
    }

    /**
     * Repositions the window after a size change (e.g. the pill expanding/collapsing). When snapping is on,
     * the bubble stays pinned to its anchored edge with the margin — so the pill grows *inward* from the
     * right edge instead of off-screen, and returns to the edge when it collapses. When snapping is off it
     * is just clamped within the screen bounds.
     */
    private fun repositionForSize() {
        val lp = params ?: return
        val v = rootView ?: return
        if (!added) return
        val maxX = (screenWidth() - v.width).coerceAtLeast(0)
        val maxY = (screenHeight() - v.height).coerceAtLeast(0)
        val margin = dp(8).coerceAtMost(maxX / 2)
        val nx = when {
            !prefs.dictate.floatingButtonSnapToEdge.get() -> lp.x.coerceIn(0, maxX)
            anchoredToRight -> maxX - margin
            else -> margin
        }
        val ny = lp.y.coerceIn(0, maxY)
        if (nx != lp.x || ny != lp.y) {
            lp.x = nx
            lp.y = ny
            runCatching { windowManager.updateViewLayout(v, lp) }
        }
    }

    /** Places the bubble at its default spot — right edge with a margin, ~60% up from the bottom — once it
     *  has been measured. Used for the first show; the margin is applied whether or not snap-to-edge is on. */
    private fun applyInitialPlacement() {
        val lp = params ?: return
        val v = rootView ?: return
        val maxX = (screenWidth() - v.width).coerceAtLeast(0)
        val maxY = (screenHeight() - v.height).coerceAtLeast(0)
        val margin = dp(8).coerceAtMost(maxX / 2)
        anchoredToRight = true
        lp.x = (maxX - margin).coerceAtLeast(0)
        // Vertically center the bubble at ~60% up from the bottom edge (≈40% down from the top).
        lp.y = (screenHeight() * 2 / 5 - v.height / 2).coerceIn(0, maxY)
        if (added) runCatching { windowManager.updateViewLayout(v, lp) }
    }

    private fun screenWidth(): Int = context.resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = context.resources.displayMetrics.heightPixels

    /** Saves the bubble position for the leaving app and restores the position saved for the new app. */
    private fun onForegroundPackageChanged(pkg: String?) {
        if (!prefs.dictate.floatingButtonRememberPosition.get()) {
            currentPackage = pkg
            return
        }
        saveCurrentPosition()
        currentPackage = pkg
        val saved = pkg?.let { positions[it] } ?: return
        val lp = params ?: return
        val v = rootView
        val w = v?.width ?: 0
        val maxX = (screenWidth() - w).coerceAtLeast(0)
        val maxY = (screenHeight() - (v?.height ?: 0)).coerceAtLeast(0)
        lp.x = saved.first.coerceIn(0, maxX)
        lp.y = saved.second.coerceIn(0, maxY)
        anchoredToRight = lp.x + w / 2 >= screenWidth() / 2
        if (added && v != null) runCatching { windowManager.updateViewLayout(v, lp) }
    }

    private fun saveCurrentPosition() {
        if (!prefs.dictate.floatingButtonRememberPosition.get()) return
        val lp = params ?: return
        val pkg = currentPackage ?: return
        positions[pkg] = lp.x to lp.y
    }

    private fun scheduleDim() {
        cancelDim()
        if (!prefs.dictate.floatingButtonAutoDim.get()) return
        dimJob = scope.launch {
            delay(AUTO_DIM_DELAY_MS)
            if (added && DictateController.state.value is DictateController.UiState.Idle) applyDim(true)
        }
    }

    private fun cancelDim() {
        dimJob?.cancel()
        dimJob = null
    }

    /** Fades + shrinks the bubble to a small dot (or restores it), pivoting toward the anchored edge. */
    private fun applyDim(dim: Boolean) {
        if (dimmed == dim) return
        dimmed = dim
        val view = rootView ?: return
        // Shrink toward the nearer screen edge based on the *current* position (anchoredToRight can be
        // stale), so the dimmed dot stays put instead of appearing to drift toward the middle.
        val onRight = (params?.x ?: 0) + view.width / 2 >= screenWidth() / 2
        view.pivotX = if (onRight) view.width.toFloat() else 0f
        view.pivotY = view.height / 2f
        view.animate()
            .alpha(if (dim) 0.45f else 1f)
            .scaleX(if (dim) 0.5f else 1f)
            .scaleY(if (dim) 0.5f else 1f)
            .setDuration(200)
            .start()
        // Keep the undo button in step with the bubble: hide it while dimmed, restore it on wake.
        if (dim) hideUndo() else manageUndo(DictateController.state.value, added)
    }

    private fun vibrateTap() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun onTap() {
        if (prefs.dictate.floatingButtonHaptic.get()) vibrateTap()
        val current = DictateController.state.value
        // Recovery (#160): if the previous floating-button dictation failed but its recording was kept
        // (e.g. a flaky signal dropped the upload), a tap re-sends that audio instead of starting a new
        // recording — so the dictation isn't lost just because the bubble has no resend chip of its own.
        if (current is DictateController.UiState.Error &&
            current.action == DictateController.ErrorAction.RESEND
        ) {
            service.startMicForeground()
            weStartedDictation = true
            DictateController.sendRetainedAudio(context)
            return
        }
        // Promote the service to a microphone foreground service *before* recording starts, so the mic
        // capture is allowed while the app is in the background (Android 14+). Demoted again when the
        // dictation finishes (see manageForeground).
        val starting = current is DictateController.UiState.Idle
        if (starting) {
            service.startMicForeground()
            weStartedDictation = true
        }
        DictateController.onMicClick(context, DictateController.OutputTarget.OVERLAY)
    }

    // --- State → visuals -------------------------------------------------------------------------

    private fun applyState(state: DictateController.UiState) {
        if (holding) return // a transient error/success flash is currently shown
        if (state == lastAppliedState) return // combine re-emits on focus/window churn; ignore no-op repeats
        skin?.applyState(state)
        lastAppliedState = state
    }

    /**
     * Reacts to a finished bubble dictation. On [DictateController.UiState.Error] it flashes the error
     * indicator on the button and shows the message as a toast (there is no inline text), then clears the
     * error so the keyboard's own chip does not also fire. A clean finish (a busy state returning to idle)
     * flashes a brief success check; a plain cancel just resets the flag.
     */
    private fun reportTerminalState(state: DictateController.UiState) {
        when (state) {
            is DictateController.UiState.Error -> if (weStartedDictation) {
                weStartedDictation = false
                // When the recording was kept (#160), tell the user a tap re-sends it.
                val msg = if (state.action == DictateController.ErrorAction.RESEND) {
                    context.getString(R.string.dictate__floating_button_retry_hint, state.message)
                } else {
                    state.message
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                holdVisual(ERROR_HOLD_MS, FlashKind.ERROR)
            }
            is DictateController.UiState.Idle -> {
                val finishedWork = prevState is DictateController.UiState.Transcribing ||
                    prevState is DictateController.UiState.Rewording
                if (weStartedDictation && finishedWork) holdVisual(SUCCESS_HOLD_MS, FlashKind.SUCCESS)
                weStartedDictation = false
            }
            else -> Unit // recording / transcribing / rewording still in flight
        }
    }

    /** Applies a transient flash for [durationMs], suppressing live-state updates, then restores them. */
    private fun holdVisual(durationMs: Long, kind: FlashKind) {
        stopTicker()
        skin?.showFlash(kind)
        lastAppliedState = null // the flash overwrote the visuals; force a re-apply when it ends
        holding = true
        holdJob?.cancel()
        holdJob = scope.launch {
            delay(durationMs)
            holding = false
            applyState(DictateController.state.value)
        }
    }

    // --- Recording level ticker ------------------------------------------------------------------

    private fun manageTicker(state: DictateController.UiState) {
        if (!holding && state is DictateController.UiState.Recording) startTicker() else stopTicker()
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                val rec = recordingState
                val level = if (rec?.paused == true) {
                    0f
                } else {
                    DictateController.audioLevel.value
                }
                val elapsed = rec?.let { elapsedOf(it) } ?: 0L
                skin?.onRecordingTick(level, elapsed)
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun elapsedOf(rec: DictateController.UiState.Recording): Long =
        rec.accumulatedMs + if (rec.paused) 0L else SystemClock.elapsedRealtime() - rec.startedAtMs

    private fun manageForeground(state: DictateController.UiState) {
        when (state) {
            is DictateController.UiState.Recording,
            is DictateController.UiState.Transcribing,
            is DictateController.UiState.Rewording,
            -> Unit // keep the microphone foreground service running
            else -> service.stopMicForeground()
        }
    }

    // --- Shared drawing helpers ------------------------------------------------------------------

    private fun circle(colorRes: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color(colorRes))
    }

    /**
     * Resolves a color resource — except the accent, which is overridden by the user's chosen button color
     * so every skin's idle/accent visuals follow the preference without each call site needing to change.
     */
    private fun color(colorRes: Int): Int =
        if (colorRes == R.color.dictate_overlay_accent) accentColor
        else ContextCompat.getColor(context, colorRes)

    /**
     * White by default, switching to black only for *very light* button colors, so the glyph stays legible
     * on a near-white accent without flipping on ordinary colors like the default light-blue. Threshold is
     * intentionally high (not the WCAG contrast crossover).
     */
    private fun contrastForeground(bg: Int): Int {
        val opaque = bg or 0xFF000000.toInt()
        return if (ColorUtils.calculateLuminance(opaque) > 0.7) Color.BLACK else Color.WHITE
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun dpf(value: Float): Float = value * context.resources.displayMetrics.density

    /** Like [dp]/[dpf] but scaled by the user's chosen button size; used for the skin dimensions. */
    private fun sdp(value: Int): Int = (value * sizeScale * context.resources.displayMetrics.density).toInt()

    private fun sdpf(value: Float): Float = value * sizeScale * context.resources.displayMetrics.density

    /** A transient, non-state flash shown briefly on the button. */
    private enum class FlashKind { ERROR, SUCCESS }

    /** Strategy that renders the bubble for a given design; owned by the controller. */
    private interface BubbleSkin {
        val root: View
        /** A fixed window height in px, or null to size to the content. Pinning it stops the pill from
         *  appearing to grow vertically while its width animates. */
        val fixedHeight: Int?
        fun applyState(state: DictateController.UiState)
        fun showFlash(kind: FlashKind)
        fun onRecordingTick(level: Float, elapsedMs: Long)
        fun destroy()
    }

    // --- Waveform --------------------------------------------------------------------------------

    /** A small rolling bar waveform fed normalized 0..1 mic levels. */
    private inner class WaveformView(context: Context, barCount: Int = WAVE_BARS) : View(context) {
        var barColor: Int = color(R.color.dictate_overlay_icon)
        private val levels = FloatArray(barCount)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        fun push(level: Float) {
            for (i in 0 until levels.size - 1) levels[i] = levels[i + 1]
            levels[levels.size - 1] = level.coerceIn(0f, 1f)
            invalidate()
        }

        fun reset() {
            levels.fill(0f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val n = levels.size
            val gap = dpf(2f)
            val barW = (width - gap * (n - 1)) / n
            val radius = barW / 2f
            val cy = height / 2f
            val minH = dpf(4f)
            val maxH = height - dpf(2f)
            paint.color = barColor
            for (i in 0 until n) {
                val h = (minH + levels[i] * (maxH - minH)).coerceIn(minH, height.toFloat())
                val left = i * (barW + gap)
                canvas.drawRoundRect(left, cy - h / 2f, left + barW, cy + h / 2f, radius, radius, paint)
            }
        }
    }

    // --- Ring skin (design 1) --------------------------------------------------------------------

    private enum class RingMode { SOLID, SPIN, PULSE }

    private inner class RingSkin(context: Context) : BubbleSkin {
        // Filled core with the ring set a touch outside it: a small transparent gap so the ring reads as a
        // ring, but not so wide that it looks detached (a middle ground between the two earlier extremes).
        private val viewSize = sdp(64)
        private val coreSize = sdp(40)
        private val iconInset = sdp(10)
        private val ringStrokePx = sdpf(3f)
        private val ringRadiusPx = sdpf(25f)

        private val ring = RingView(context)
        private val core = View(context).apply {
            background = circle(R.color.dictate_overlay_accent)
            elevation = sdpf(6f)
        }
        private val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_dictate_overlay_mic)
            setPadding(iconInset, iconInset, iconInset, iconInset)
            elevation = sdpf(6f)
            imageTintList = ColorStateList.valueOf(contrastForeground(accentColor))
        }
        private val wave = WaveformView(context).apply {
            visibility = View.GONE
            elevation = sdpf(6f)
        }
        private var ringAnim: ValueAnimator? = null

        override val fixedHeight: Int? = null

        override val root: View = FrameLayout(context).apply {
            addView(ring, FrameLayout.LayoutParams(viewSize, viewSize))
            addView(core, FrameLayout.LayoutParams(coreSize, coreSize, Gravity.CENTER))
            addView(icon, FrameLayout.LayoutParams(coreSize, coreSize, Gravity.CENTER))
            addView(wave, FrameLayout.LayoutParams(sdp(24), sdp(16), Gravity.CENTER))
        }

        override fun applyState(state: DictateController.UiState) {
            when (state) {
                is DictateController.UiState.Recording -> {
                    setCore(R.color.dictate_overlay_recording)
                    showWave(true)
                    pulseRing(R.color.dictate_overlay_recording)
                }
                is DictateController.UiState.Transcribing -> {
                    setCore(R.color.dictate_overlay_accent)
                    showGlyph(R.drawable.ic_dictate_overlay_mic)
                    spinRing(R.color.dictate_overlay_accent)
                }
                is DictateController.UiState.Rewording -> {
                    setCore(R.color.dictate_overlay_accent)
                    showGlyph(R.drawable.ic_dictate_overlay_mic)
                    spinRing(R.color.dictate_overlay_rewording)
                }
                else -> {
                    setCore(R.color.dictate_overlay_accent)
                    showGlyph(R.drawable.ic_dictate_overlay_mic)
                    setSolidRing(R.color.dictate_overlay_accent)
                }
            }
        }

        override fun showFlash(kind: FlashKind) {
            when (kind) {
                FlashKind.ERROR -> {
                    setCore(R.color.dictate_overlay_recording)
                    showGlyph(R.drawable.ic_dictate_overlay_error)
                    setSolidRing(R.color.dictate_overlay_recording)
                }
                FlashKind.SUCCESS -> {
                    setCore(R.color.dictate_overlay_success)
                    showGlyph(R.drawable.ic_dictate_overlay_check)
                    setSolidRing(R.color.dictate_overlay_success)
                }
            }
        }

        override fun onRecordingTick(level: Float, elapsedMs: Long) {
            wave.push(level)
        }

        override fun destroy() {
            ringAnim?.cancel()
            ringAnim = null
        }

        private fun setCore(colorRes: Int) {
            core.background = circle(colorRes)
            icon.imageTintList = ColorStateList.valueOf(contrastForeground(color(colorRes)))
        }

        private fun showGlyph(resId: Int) {
            icon.setImageResource(resId)
            icon.alpha = 1f
            icon.visibility = View.VISIBLE
            wave.visibility = View.GONE
        }

        private fun showWave(show: Boolean) {
            wave.visibility = if (show) View.VISIBLE else View.GONE
            icon.visibility = if (show) View.GONE else View.VISIBLE
            if (show) wave.reset()
        }

        private fun setSolidRing(colorRes: Int) {
            ringAnim?.cancel()
            ringAnim = null
            ring.ringColor = color(colorRes)
            ring.mode = RingMode.SOLID
            ring.invalidate()
        }

        private fun spinRing(colorRes: Int) {
            ring.ringColor = color(colorRes)
            ring.mode = RingMode.SPIN
            ringAnim?.cancel()
            ringAnim = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                addUpdateListener {
                    ring.spinDeg = it.animatedValue as Float
                    ring.invalidate()
                }
                start()
            }
        }

        private fun pulseRing(colorRes: Int) {
            ring.ringColor = color(colorRes)
            ring.mode = RingMode.PULSE
            ringAnim?.cancel()
            ringAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 480
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener {
                    ring.pulse = it.animatedValue as Float
                    ring.invalidate()
                }
                start()
            }
        }

        private inner class RingView(context: Context) : View(context) {
            var ringColor: Int = color(R.color.dictate_overlay_accent)
            var mode: RingMode = RingMode.SOLID
            var spinDeg: Float = 0f
            var pulse: Float = 0f

            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
            private val oval = RectF()

            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f
                val cy = height / 2f
                val r = ringRadiusPx
                paint.color = ringColor
                paint.alpha = 255
                paint.strokeWidth = ringStrokePx
                when (mode) {
                    RingMode.SOLID -> canvas.drawCircle(cx, cy, r, paint)
                    RingMode.SPIN -> {
                        oval.set(cx - r, cy - r, cx + r, cy + r)
                        canvas.drawArc(oval, spinDeg, 270f, false, paint)
                    }
                    RingMode.PULSE -> {
                        // A strong heartbeat: the ring grows and thickens and brightens with the pulse.
                        paint.strokeWidth = ringStrokePx + pulse * sdpf(4f)
                        paint.alpha = (170 + pulse * 85f).toInt().coerceIn(0, 255)
                        canvas.drawCircle(cx, cy, r + pulse * sdpf(3f), paint)
                    }
                }
            }
        }
    }

    // --- Pill skin (design 2) --------------------------------------------------------------------

    private inner class PillSkin(context: Context) : BubbleSkin {
        // Match the ring design's overall footprint, scaled by the chosen size.
        private val pillHeight = sdp(48)
        private val iconSize = sdp(24)
        private val pad = sdp(12)

        private val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = pillHeight / 2f
            setColor(color(R.color.dictate_overlay_accent))
        }
        private val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_dictate_overlay_mic)
            imageTintList = ColorStateList.valueOf(contrastForeground(accentColor))
        }
        private val timer = TextView(context).apply {
            setTextColor(color(R.color.dictate_overlay_icon))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sdpf(14f))
        }
        // Thinner bars: more bars across a similar width than the ring's waveform.
        private val wave = WaveformView(context, barCount = 13)

        // The timer + waveform live in this container, which grows from 0 width (circle) to its content
        // width (pill) when recording starts, so the circle→pill transition animates instead of jumping.
        private val expand = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            visibility = View.GONE
            addView(timer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = sdp(8) })
            addView(wave, LinearLayout.LayoutParams(sdp(48), sdp(20)).apply {
                marginStart = sdp(8)
                marginEnd = sdp(2)
            })
        }

        private var spinAnim: ValueAnimator? = null
        private var expandAnim: ValueAnimator? = null

        override val fixedHeight: Int = pillHeight

        override val root: View = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            elevation = sdpf(6f)
            minimumHeight = pillHeight
            minimumWidth = pillHeight
            setPadding(pad, 0, pad, 0)
            addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
            addView(expand, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        override fun applyState(state: DictateController.UiState) {
            stopSpin()
            when (state) {
                is DictateController.UiState.Recording -> {
                    setColor(R.color.dictate_overlay_recording)
                    icon.alpha = 1f
                    icon.rotation = 0f
                    icon.setImageResource(R.drawable.ic_dictate_overlay_stop)
                    wave.reset()
                    timer.text = formatElapsed(0)
                    setExpanded(true)
                }
                is DictateController.UiState.Transcribing -> busySpinner(R.color.dictate_overlay_accent)
                is DictateController.UiState.Rewording -> busySpinner(R.color.dictate_overlay_rewording)
                else -> {
                    setColor(R.color.dictate_overlay_accent)
                    icon.alpha = 1f
                    icon.rotation = 0f
                    icon.setImageResource(R.drawable.ic_dictate_overlay_mic)
                    setExpanded(false)
                }
            }
        }

        override fun showFlash(kind: FlashKind) {
            stopSpin()
            icon.alpha = 1f
            icon.rotation = 0f
            setExpanded(false)
            when (kind) {
                FlashKind.ERROR -> {
                    setColor(R.color.dictate_overlay_recording)
                    icon.setImageResource(R.drawable.ic_dictate_overlay_error)
                }
                FlashKind.SUCCESS -> {
                    setColor(R.color.dictate_overlay_success)
                    icon.setImageResource(R.drawable.ic_dictate_overlay_check)
                }
            }
        }

        override fun onRecordingTick(level: Float, elapsedMs: Long) {
            wave.push(level)
            timer.text = formatElapsed(elapsedMs)
        }

        override fun destroy() {
            stopSpin()
            expandAnim?.cancel()
        }

        private fun busySpinner(colorRes: Int) {
            setColor(colorRes)
            setExpanded(false)
            icon.alpha = 1f
            icon.setImageResource(R.drawable.ic_dictate_overlay_spinner)
            spinAnim?.cancel()
            spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                addUpdateListener { icon.rotation = it.animatedValue as Float }
                start()
            }
        }

        /** Animates the expand container open (pill) or closed (circle). */
        private fun setExpanded(expanded: Boolean) {
            expandAnim?.cancel()
            if (expanded) {
                expand.visibility = View.VISIBLE
                expand.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                val target = expand.measuredWidth
                expandAnim = ValueAnimator.ofInt(expand.width, target).apply {
                    duration = 240
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        val w = it.animatedValue as Int
                        setExpandWidth(w)
                        expand.alpha = if (target > 0) (w.toFloat() / target).coerceIn(0f, 1f) else 1f
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            // Let the timer text changes resize the pill naturally once fully open.
                            setExpandWidth(LinearLayout.LayoutParams.WRAP_CONTENT)
                            expand.alpha = 1f
                        }
                    })
                    start()
                }
            } else {
                val start = if (expand.width > 0) expand.width else 0
                if (start == 0 || expand.visibility != View.VISIBLE) {
                    setExpandWidth(0)
                    expand.visibility = View.GONE
                    return
                }
                expandAnim = ValueAnimator.ofInt(start, 0).apply {
                    duration = 200
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        val w = it.animatedValue as Int
                        setExpandWidth(w)
                        expand.alpha = (w.toFloat() / start).coerceIn(0f, 1f)
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            expand.visibility = View.GONE
                        }
                    })
                    start()
                }
            }
        }

        private fun setExpandWidth(w: Int) {
            val lp = expand.layoutParams
            lp.width = w
            expand.layoutParams = lp
        }

        private fun stopSpin() {
            spinAnim?.cancel()
            spinAnim = null
            icon.rotation = 0f
        }

        private fun setColor(colorRes: Int) {
            bg.setColor(color(colorRes))
            val fg = contrastForeground(color(colorRes))
            icon.imageTintList = ColorStateList.valueOf(fg)
            timer.setTextColor(fg)
        }

        private fun formatElapsed(ms: Long): String {
            val totalSec = (ms / 1000).toInt()
            return "%d:%02d".format(totalSec / 60, totalSec % 60)
        }
    }

    // --- Orb skin (design 3) ---------------------------------------------------------------------

    private inner class OrbSkin(context: Context) : BubbleSkin {
        private val viewSize = sdp(64)
        private val coreSize = sdp(44)
        private val iconInset = sdp(11)
        private val coreRadiusPx = coreSize / 2f
        private val minGlowPx = sdpf(2f)
        private val maxGlowPx = sdpf(8f)

        private val glow = GlowView(context)
        private val core = View(context).apply {
            background = circle(R.color.dictate_overlay_accent)
            elevation = sdpf(6f)
        }
        private val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_dictate_overlay_mic)
            setPadding(iconInset, iconInset, iconInset, iconInset)
            elevation = sdpf(6f)
            imageTintList = ColorStateList.valueOf(contrastForeground(accentColor))
        }
        private var breatheAnim: ValueAnimator? = null
        private var smoothed = 0f

        override val fixedHeight: Int? = null

        override val root: View = FrameLayout(context).apply {
            addView(glow, FrameLayout.LayoutParams(viewSize, viewSize))
            addView(core, FrameLayout.LayoutParams(coreSize, coreSize, Gravity.CENTER))
            addView(icon, FrameLayout.LayoutParams(coreSize, coreSize, Gravity.CENTER))
        }

        override fun applyState(state: DictateController.UiState) {
            stopBreathe()
            when (state) {
                is DictateController.UiState.Recording -> {
                    setCore(R.color.dictate_overlay_recording)
                    setGlyph(R.drawable.ic_dictate_overlay_stop)
                    glow.glowColor = color(R.color.dictate_overlay_recording)
                    smoothed = 0f
                    setGlow(0f) // the ticker drives it from the live amplitude
                }
                is DictateController.UiState.Transcribing -> {
                    setCore(R.color.dictate_overlay_accent)
                    setGlyph(R.drawable.ic_dictate_overlay_mic)
                    startBreathe(R.color.dictate_overlay_accent)
                }
                is DictateController.UiState.Rewording -> {
                    setCore(R.color.dictate_overlay_accent)
                    setGlyph(R.drawable.ic_dictate_overlay_mic)
                    startBreathe(R.color.dictate_overlay_rewording)
                }
                else -> {
                    setCore(R.color.dictate_overlay_accent)
                    setGlyph(R.drawable.ic_dictate_overlay_mic)
                    setGlow(0f)
                }
            }
        }

        override fun showFlash(kind: FlashKind) {
            stopBreathe()
            setGlow(0f)
            when (kind) {
                FlashKind.ERROR -> {
                    setCore(R.color.dictate_overlay_recording)
                    setGlyph(R.drawable.ic_dictate_overlay_error)
                }
                FlashKind.SUCCESS -> {
                    setCore(R.color.dictate_overlay_success)
                    setGlyph(R.drawable.ic_dictate_overlay_check)
                }
            }
        }

        override fun onRecordingTick(level: Float, elapsedMs: Long) {
            smoothed += (level - smoothed) * 0.35f
            setGlow(smoothed)
        }

        override fun destroy() {
            stopBreathe()
        }

        private fun setCore(colorRes: Int) {
            core.background = circle(colorRes)
            icon.imageTintList = ColorStateList.valueOf(contrastForeground(color(colorRes)))
        }

        private fun setGlyph(resId: Int) {
            icon.setImageResource(resId)
            icon.alpha = 1f
        }

        /** Drives the glow radius/alpha and a subtle orb scale from a 0..1 level. */
        private fun setGlow(level: Float) {
            val l = level.coerceIn(0f, 1f)
            glow.level = l
            glow.invalidate()
            val s = 1f + l * 0.08f
            core.scaleX = s
            core.scaleY = s
            icon.scaleX = s
            icon.scaleY = s
        }

        private fun startBreathe(colorRes: Int) {
            glow.glowColor = color(colorRes)
            breatheAnim?.cancel()
            breatheAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { setGlow(0.2f + 0.5f * (it.animatedValue as Float)) }
                start()
            }
        }

        private fun stopBreathe() {
            breatheAnim?.cancel()
            breatheAnim = null
        }

        private inner class GlowView(context: Context) : View(context) {
            var glowColor: Int = color(R.color.dictate_overlay_accent)
            var level: Float = 0f
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            override fun onDraw(canvas: Canvas) {
                if (level <= 0.01f || width == 0) return
                val cx = width / 2f
                val cy = height / 2f
                val glowR = coreRadiusPx + minGlowPx + level * maxGlowPx
                if (glowR <= coreRadiusPx) return
                val inner = (coreRadiusPx / glowR).coerceIn(0f, 0.95f)
                val a = (50 + level * 150f).toInt().coerceIn(0, 255)
                val rgb = glowColor and 0x00FFFFFF
                val cIn = rgb or (a shl 24)
                paint.shader = RadialGradient(
                    cx, cy, glowR,
                    intArrayOf(cIn, cIn, rgb),
                    floatArrayOf(0f, inner, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(cx, cy, glowR, paint)
            }
        }
    }

    // --- Cloud skin (design 4) -------------------------------------------------------------------

    private inner class CloudSkin(context: Context) : BubbleSkin {
        private val viewSize = sdp(64)
        private val coreSize = sdp(44)
        private val idleInset = sdp(13)

        private val cloud = AudioReactiveCloudOrbView(context)
        private val icon = ImageView(context).apply {
            elevation = sdpf(6f)
        }

        override val fixedHeight: Int? = null

        override val root: View = FrameLayout(context).apply {
            // The cloud spans the whole button so it has room to grow while recording; a small glyph
            // overlays it as the idle/terminal affordance (there is no glyph while recording).
            addView(cloud, FrameLayout.LayoutParams(viewSize, viewSize, Gravity.CENTER))
            addView(icon, FrameLayout.LayoutParams(coreSize, coreSize, Gravity.CENTER))
            minimumWidth = viewSize
            minimumHeight = viewSize
        }

        override fun applyState(state: DictateController.UiState) {
            when (state) {
                is DictateController.UiState.Recording -> {
                    cloud.setPaused(state.paused)
                    cloud.setLevel(0f) // the shared level ticker takes over immediately after this state update
                    cloud.setMode(AudioReactiveCloudOrbView.Mode.LISTENING)
                    icon.visibility = View.INVISIBLE // the growing, turbulent cloud alone signals recording
                }
                is DictateController.UiState.Transcribing,
                is DictateController.UiState.Rewording -> {
                    cloud.setPaused(false)
                    cloud.setMode(AudioReactiveCloudOrbView.Mode.THINKING)
                    icon.visibility = View.INVISIBLE // the cloud draws its own activity spinner
                }
                else -> showIdle()
            }
        }

        override fun showFlash(kind: FlashKind) {
            // Keep the cloud alive and tint the whole field, so the terminal feedback stays part of the
            // same design instead of swapping in a detached colored circle.
            cloud.setPaused(false)
            when (kind) {
                FlashKind.ERROR -> {
                    cloud.setMode(AudioReactiveCloudOrbView.Mode.ERROR)
                    showGlyph(R.drawable.ic_dictate_overlay_error, idleInset, Color.WHITE, 0.95f)
                }
                FlashKind.SUCCESS -> {
                    cloud.setMode(AudioReactiveCloudOrbView.Mode.SUCCESS)
                    showGlyph(R.drawable.ic_dictate_overlay_check, idleInset, Color.WHITE, 0.95f)
                }
            }
        }

        override fun onRecordingTick(level: Float, elapsedMs: Long) {
            cloud.setLevel(level)
        }

        override fun destroy() {
            cloud.stop()
        }

        // Idle: the cloud itself is the button, with a mic hint so it reads as "tap to dictate".
        private fun showIdle() {
            cloud.setPaused(false)
            cloud.setMode(AudioReactiveCloudOrbView.Mode.IDLE)
            showGlyph(R.drawable.ic_dictate_overlay_mic, idleInset, CLOUD_GLYPH_COLOR, 0.9f)
        }

        private fun showGlyph(resId: Int, inset: Int, tint: Int, alpha: Float) {
            icon.visibility = View.VISIBLE
            icon.setImageResource(resId)
            icon.setPadding(inset, inset, inset, inset)
            icon.imageTintList = ColorStateList.valueOf(tint)
            icon.alpha = alpha
        }
    }

    private companion object {
        private const val ERROR_HOLD_MS = 1800L
        private const val SUCCESS_HOLD_MS = 1700L
        private const val TICK_MS = 50L
        private const val AUTO_DIM_DELAY_MS = 3500L
        private const val WAVE_BARS = 7
        private const val CLOUD_GLYPH_COLOR = 0xFF343B8F.toInt()
    }
}
