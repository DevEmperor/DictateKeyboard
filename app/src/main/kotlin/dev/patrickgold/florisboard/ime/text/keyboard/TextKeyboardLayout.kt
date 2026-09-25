/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.text.keyboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import android.view.animation.AccelerateInterpolator
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toSize
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.ui.LegacyLayoutState
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.glideTypingManager
import dev.patrickgold.florisboard.ime.editor.OperationScope
import dev.patrickgold.florisboard.ime.editor.OperationUnit
import dev.patrickgold.florisboard.ime.input.InputEventDispatcher
import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.nlp.latin.KeyProximityInfo
import dev.patrickgold.florisboard.ime.nlp.latin.TouchTrace
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.keyboard.SpaceBarMode
import dev.patrickgold.florisboard.ime.popup.ExceptionsForKeyCodes
import dev.patrickgold.florisboard.ime.popup.PopupUiController
import dev.patrickgold.florisboard.ime.popup.rememberPopupUiController
import dev.patrickgold.florisboard.ime.text.gestures.GlideTypingGesture
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.text.gestures.SwipeGesture
import dev.patrickgold.florisboard.ime.text.gestures.SWIPE_COMMIT_UNITS
import dev.patrickgold.florisboard.ime.text.gestures.swipeCommitDirection
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.ime.window.LocalWindowController
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.FlorisRect
import dev.patrickgold.florisboard.lib.Pointer
import dev.patrickgold.florisboard.lib.PointerMap
import dev.patrickgold.florisboard.lib.devtools.LogTopic
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.lib.toIntOffset
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.isActive
import org.florisboard.lib.android.isOrientationLandscape
import org.florisboard.lib.compose.DisposableLifecycleEffect
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggText
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery
import kotlin.math.abs
import kotlin.math.sqrt

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TextKeyboardLayout(
    modifier: Modifier = Modifier,
    evaluator: ComputingEvaluator,
): Unit = with(LocalDensity.current) {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val glideTypingManager by context.glideTypingManager()

    val keyboard = evaluator.keyboard as TextKeyboard
    val glideEnabledInternal by prefs.glide.enabled.collectAsState()
    // Suppressed while the modern keyboard is reached via the legacy swipe gesture (issue #125), so a
    // horizontal glide doesn't swallow the swipe-back that returns to the dictation UI.
    val glideSuppressed by dev.patrickgold.florisboard.dictate.ui.LegacyLayoutState.suppressGlide.collectAsState()
    val glideEnabled = glideEnabledInternal && !glideSuppressed && evaluator.editorInfo.isRichInputEditor &&
        evaluator.state.keyVariation != KeyVariation.PASSWORD && !isTouchExplorationEnabled(context)
    val glideShowTrail by prefs.glide.showTrail.collectAsState()
    val glideTrailStyle = rememberSnyggThemeQuery(FlorisImeUi.GlideTrail.elementName)
    val glideTrailColor = glideTrailStyle.foreground(default = Color.Green)

    val controller = remember { TextKeyboardLayoutController(context) }.also {
        it.keyboard = keyboard
        if (keyboard.mode == KeyboardMode.CHARACTERS) {
            val keys = keyboard.keys().asSequence().toList()
            // Feed key geometry to the autocorrect proximity model regardless of glide (which is off for
            // many users); the glide classifier still only gets it when glide is enabled.
            KeyProximityInfo.update(keys)
            if (glideEnabled) {
                glideTypingManager.setLayout(keys)
            }
        }
    }
    val touchEventChannel = remember { Channel<MotionEvent>(64) }

    fun resetAllKeys() {
        try {
            val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            controller.onTouchEventInternal(event)
            controller.popupUiController.hide()
            event.recycle()
        } catch (_: Throwable) {
            // Ignore
        }
    }

    DisposableEffect(Unit) {
        controller.glideTypingDetector.registerListener(controller)
        controller.glideTypingDetector.registerListener(glideTypingManager)
        onDispose {
            controller.glideTypingDetector.unregisterListener(controller)
            controller.glideTypingDetector.unregisterListener(glideTypingManager)
            resetAllKeys()
        }
    }

    DisposableLifecycleEffect(
        onResume = { /* Do nothing */ },
        onPause = { resetAllKeys() },
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.keyboardUiHeight())
            .onGloballyPositioned { coords ->
                controller.size = coords.size.toSize()
            }
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN,
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_POINTER_UP,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL,
                        -> {
                        val clonedEvent = MotionEvent.obtain(event)
                        touchEventChannel
                            .trySend(clonedEvent)
                            .onFailure {
                                // Make sure to prevent MotionEvent memory leakage
                                // in case the input channel is full
                                clonedEvent.recycle()
                            }
                        return@pointerInteropFilter true
                    }
                }
                return@pointerInteropFilter false
            }
            .drawWithContent {
                drawContent()
                if (glideEnabled && glideShowTrail) {
                    val targetDist = 3.0f
                    val radius = 20.0f

                    val radiusReductionFactor = 0.99f
                    if (controller.fadingGlideRadius > 0) {
                        controller.drawGlideTrail(
                            this,
                            controller.fadingGlide,
                            targetDist,
                            controller.fadingGlideRadius,
                            radiusReductionFactor,
                            glideTrailColor,
                        )
                    }
                    if (controller.isGliding && controller.glideDataForDrawing.isNotEmpty()) {
                        controller.drawGlideTrail(
                            this, controller.glideDataForDrawing, targetDist, radius,
                            radiusReductionFactor, glideTrailColor,
                        )
                    }
                }
            },
    ) {
        // FIXME (when rewriting TextKeyboardLayout): constrains.maxWidth is not stable!
        val keyboardWidth = constraints.maxWidth.toFloat()
        val keyboardHeight = constraints.maxHeight.toFloat()
        val keyboardRowBaseHeight = FlorisImeSizing.keyboardRowBaseHeight

        val windowController = LocalWindowController.current
        val windowSpec by windowController.activeWindowSpec.collectAsState()
        val keyMarginH by remember { derivedStateOf { windowSpec.keyMarginH.toPx() } }
        val keyMarginV by remember { derivedStateOf { windowSpec.keyMarginV.toPx() } }
        // Keyed on the keyboard and its width, unlike the margins above: whether there is a gap at all
        // depends on which keyboard this is — a number pad has nothing to split (issue #362).
        val splitGap by remember(keyboard, keyboardWidth) {
            derivedStateOf { keyboard.effectiveSplitGap(keyboardWidth, windowSpec.splitGap.toPx()) }
        }

        val desiredKey = remember(
            keyboard, keyboardWidth, keyboardHeight, keyMarginH, keyMarginV,
            keyboardRowBaseHeight, evaluator, splitGap,
        ) {
            TextKey(data = TextKeyData.UNSPECIFIED).also { desiredKey ->
                desiredKey.touchBounds.apply {
                    // The gap belongs to no key, so the reference width is a tenth of what the two halves
                    // share, not of the whole window (issue #362): every key asks for its width in
                    // multiples of this, and a half laid out from a width it does not have gets keys that
                    // shrink into each other.
                    width = (keyboardWidth - splitGap) / 10f
                    height = when (keyboard.mode) {
                        KeyboardMode.CHARACTERS,
                        KeyboardMode.NUMERIC_ADVANCED,
                        KeyboardMode.SYMBOLS,
                        KeyboardMode.SYMBOLS2 -> {
                            (keyboardHeight / keyboard.rowCount)
                                .coerceAtMost(keyboardRowBaseHeight.toPx() * 1.12f)
                        }
                        else -> keyboardRowBaseHeight.toPx()
                    }
                }
                desiredKey.visibleBounds.applyFrom(desiredKey.touchBounds).deflateBy(keyMarginH, keyMarginV)
                keyboard.layout(keyboardWidth, keyboardHeight, desiredKey, true, splitGap)
            }
        }

        // The momentary layer (issue #366) asked for this keyboard while a finger was already down, and
        // has been holding that finger's re-binding back until it arrived. Reported from here rather than
        // where `controller.keyboard` is assigned, because only now has `keyboard.layout(...)` run above
        // and the new keys have bounds to be found by.
        SideEffect { controller.onKeyboardSettled() }

        val desiredKeyHack = rememberUpdatedState(desiredKey) // TODO quick'n'dirty hack
        val popupUiController = rememberPopupUiController(
            key1 = keyboard,
            key2 = Unit, // TODO quick'n'dirty hack
            boundsProvider = { key ->
                val keyPopupWidth: Float
                val keyPopupHeight: Float
                when {
                    configuration.isOrientationLandscape() -> {
                        keyPopupWidth = desiredKeyHack.value.visibleBounds.width * 1.0f
                        keyPopupHeight = desiredKeyHack.value.visibleBounds.height * 3.0f
                    }
                    else -> {
                        keyPopupWidth = desiredKeyHack.value.visibleBounds.width * 1.1f
                        keyPopupHeight = desiredKeyHack.value.visibleBounds.height * 2.5f
                    }
                }
                val keyPopupDiffX = (key.visibleBounds.width - keyPopupWidth) / 2.0f
                FlorisRect.new().apply {
                    left = key.visibleBounds.left + keyPopupDiffX
                    top = key.visibleBounds.bottom - keyPopupHeight
                    right = left + keyPopupWidth
                    bottom = top + keyPopupHeight
                }
            },
            isSuitableForBasicPopup = { key ->
                if (key is TextKey) {
                    val keyCode = key.computedData.code
                    val keyType = key.computedData.type
                    val numeric = keyboard.mode == KeyboardMode.NUMERIC ||
                        keyboard.mode == KeyboardMode.PHONE || keyboard.mode == KeyboardMode.PHONE2 ||
                        keyboard.mode == KeyboardMode.NUMERIC_ADVANCED && keyType == KeyType.NUMERIC
                    keyCode > KeyCode.SPACE && keyCode != KeyCode.CJK_SPACE && !numeric
                } else {
                    true
                }
            },
            isSuitableForExtendedPopup = { key ->
                if (key is TextKey) {
                    val keyCode = key.computedData.code
                    keyCode > KeyCode.SPACE && keyCode != KeyCode.CJK_SPACE || ExceptionsForKeyCodes.contains(keyCode)
                } else {
                    true
                }
            },
        )
        popupUiController.evaluator = evaluator
        popupUiController.keyHintConfiguration = prefs.keyboard.keyHintConfiguration()
        controller.popupUiController = popupUiController
        val debugShowTouchBoundaries by prefs.devtools.showKeyTouchBoundaries.collectAsState()
        for (textKey in keyboard.keys()) {
            TextKeyButton(
                textKey, evaluator, desiredKey,
                debugShowTouchBoundaries,
            )
        }

        popupUiController.RenderPopups()
    }

    LaunchedEffect(Unit) {
        for (event in touchEventChannel) {
            if (!isActive) break
            controller.onTouchEventInternal(event)
            event.recycle()
        }
    }
}

@Composable
private fun TextKeyButton(
    key: TextKey,
    evaluator: ComputingEvaluator,
    desiredKey: TextKey,
    debugShowTouchBoundaries: Boolean,
) = with(LocalDensity.current) {
    val attributes = mapOf(
        FlorisImeUi.Attr.Code to key.computedData.code,
        FlorisImeUi.Attr.Mode to evaluator.keyboard.mode.toString(),
        FlorisImeUi.Attr.ShiftState to evaluator.state.inputShiftState.toString(),
        FlorisImeUi.Attr.Composing to (key.computedData is ComposedMatraKeyData),
    )
    val selector = when {
        !key.isEnabled -> SnyggSelector.DISABLED
        key.isPressed -> SnyggSelector.PRESSED
        else -> SnyggSelector.NONE
    }
    val size = remember(key, desiredKey) {
        key.visibleBounds.size.toDpSize()
    }
    SnyggBox(
        FlorisImeUi.Key.elementName,
        attributes = attributes,
        selector = selector,
        modifier = Modifier
            .requiredSize(size)
            .absoluteOffset { key.visibleBounds.topLeft.toIntOffset() },
    ) {
        val isTelPadKey = key.computedData.type == KeyType.NUMERIC && evaluator.keyboard.mode == KeyboardMode.PHONE
        key.label?.let { label ->
            var customLabel = label
            if (key.computedData.code == KeyCode.SPACE) {
                val prefs by FlorisPreferenceStore
                val spaceBarMode by prefs.keyboard.spaceBarMode.collectAsState()
                when (spaceBarMode) {
                    SpaceBarMode.NOTHING -> return@let
                    SpaceBarMode.CURRENT_LANGUAGE -> {}
                    SpaceBarMode.SPACE_BAR_KEY -> customLabel = "␣"
                }
            }
            SnyggText(
                modifier = Modifier
                    .wrapContentSize()
                    .align(if (isTelPadKey) BiasAlignment(-0.5f, 0f) else Alignment.Center),
                text = customLabel,
            )
        }
        key.hintedLabel?.let { hintedLabel ->
            SnyggText(
                elementName = FlorisImeUi.KeyHint.elementName,
                attributes = attributes,
                selector = selector,
                modifier = Modifier
                    .wrapContentSize()
                    .align(if (isTelPadKey) BiasAlignment(0.5f, 0f) else Alignment.TopEnd),
                text = hintedLabel,
            )
        }
        key.foregroundImageVector?.let { imageVector ->
            SnyggIcon(
                modifier = Modifier.align(Alignment.Center),
                imageVector = imageVector,
                contentDescription = null,
            )
        }
    }
    if (debugShowTouchBoundaries) {
        Box(
            modifier = Modifier
                .requiredSize(key.touchBounds.size.toDpSize())
                .absoluteOffset { key.touchBounds.topLeft.toIntOffset() }
                .border(Dp.Hairline, Color.Red),
        )
    }
}

@Suppress("unused_parameter")
/**
 * Whether a screen reader's touch exploration (e.g. TalkBack) is active. Glide typing conflicts with it
 * — the swipe is intercepted for exploration — so we disable glide in that case (issue #127, mirroring
 * HeliBoard's GestureEnabler gate).
 */
private fun isTouchExplorationEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    return am?.isTouchExplorationEnabled == true
}

private class TextKeyboardLayoutController(
    context: Context,
) : SwipeGesture.Listener, GlideTypingGesture.Listener {
    private val prefs by FlorisPreferenceStore
    private val appContext = context.applicationContext
    private val editorInstance by context.editorInstance()
    private val keyboardManager by context.keyboardManager()

    private val inputEventDispatcher get() = keyboardManager.inputEventDispatcher
    private val inputFeedbackController get() = FlorisImeService.inputFeedbackController()
    private val keyHintConfiguration = prefs.keyboard.keyHintConfiguration()
    private val pointerMap: PointerMap<TouchPointer> = PointerMap { TouchPointer() }
    lateinit var popupUiController: PopupUiController

    /** The layer a finger is currently holding open, if any (issue #366). */
    private val momentary = MomentaryLayer()

    /**
     * The key the finger sits on in the *new* layer, highlighted for looks only.
     *
     * While the finger has not left the layer key, [TouchPointer.activeKey] deliberately stays pointed at
     * the old layer's key object — its up event is what latches the layer, exactly as a tap always has.
     * That object is no longer rendered though, so without this the highlight would vanish out from under
     * a finger that has not moved.
     */
    private var momentaryPressedKey: TextKey? = null

    private var initSelectionStart: Int = 0
    private var initSelectionEnd: Int = 0
    var isGliding by mutableStateOf(false)

    val glideTypingDetector = GlideTypingGesture.Detector(context)
    val glideDataForDrawing = mutableStateListOf<Pair<GlideTypingGesture.Detector.Position, Long>>()
    val fadingGlide = mutableStateListOf<Pair<GlideTypingGesture.Detector.Position, Long>>()
    var fadingGlideRadius by mutableFloatStateOf(0.0f)
    private val swipeGestureDetector = SwipeGesture.Detector(this)

    lateinit var keyboard: TextKeyboard
    var size = Size.Zero

    val isGlideEnabled: Boolean get() = prefs.glide.enabled.get() &&
        !dev.patrickgold.florisboard.dictate.ui.LegacyLayoutState.suppressGlide.value &&
        editorInstance.activeInfo.isRichInputEditor &&
        keyboardManager.activeState.keyVariation != KeyVariation.PASSWORD && !isTouchExplorationEnabled(appContext)

    /**
     * Reported from composition once the keyboard a momentary layer asked for has been laid out
     * (issue #366). Until then the finger's re-binding is held back, because the keys still under it
     * belong to the layer that is on its way out.
     */
    fun onKeyboardSettled() {
        if (!momentary.isPending) return
        momentary.onKeyboardSettled(keyboard.mode)
        if (momentary.isPending) return
        val heldKey = pointerMap.findById(momentary.ownerPointerId)?.activeKey ?: return
        val bounds = heldKey.visibleBounds
        momentaryPressedKey = keyboard
            .getKeyForPos(bounds.left + bounds.width / 2f, bounds.top + bounds.height / 2f)
            ?.also { it.isPressed = true }
    }

    private fun clearMomentaryHighlight() {
        momentaryPressedKey?.isPressed = false
        momentaryPressedKey = null
    }

    fun onTouchEventInternal(event: MotionEvent) {
        flogDebug { "event=$event" }
        swipeGestureDetector.onTouchEvent(event)
        if (isGlideEnabled && keyboard.mode == KeyboardMode.CHARACTERS) {
            val glidePointer = pointerMap.findById(0)
            val isNotBlocked = glidePointer?.hasTriggeredLongPress != true
            if (isNotBlocked && glideTypingDetector.onTouchEvent(event, glidePointer?.initialKey)) {
                for (pointer in pointerMap) {
                    if (pointer.activeKey != null) {
                        onTouchCancelInternal(event, pointer)
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    pointerMap.clear()
                }
                isGliding = true
                return
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val pointer = pointerMap.add(pointerId, pointerIndex)
                if (pointer != null) {
                    swipeGestureDetector.onTouchDown(event, pointer)
                    onTouchDownInternal(event, pointer)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val oldPointer = pointerMap.findById(pointerId)
                if (oldPointer != null) {
                    swipeGestureDetector.onTouchCancel(event, oldPointer)
                    onTouchCancelInternal(event, oldPointer)
                    pointerMap.removeById(oldPointer.id)
                }
                // Search for active character keys and cancel them
                for (pointer in pointerMap) {
                    val activeKey = pointer.activeKey
                    if (activeKey != null && popupUiController.isSuitableForPopups(activeKey)) {
                        swipeGestureDetector.onTouchCancel(event, pointer)
                        onTouchUpInternal(event, pointer)
                    }
                }
                val pointer = pointerMap.add(pointerId, pointerIndex)
                if (pointer != null) {
                    swipeGestureDetector.onTouchDown(event, pointer)
                    onTouchDownInternal(event, pointer)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (pointerIndex in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(pointerIndex)
                    val pointer = pointerMap.findById(pointerId)
                    if (pointer != null) {
                        pointer.index = pointerIndex
                        val alwaysTriggerOnMove = (pointer.hasTriggeredGestureMove
                            && (pointer.initialKey?.computedData?.code == KeyCode.DELETE
                            && prefs.gestures.deleteKeySwipeLeft.get().let {
                                it == SwipeAction.DELETE_CHARACTERS_PRECISELY || it == SwipeAction.SELECT_CHARACTERS_PRECISELY
                            }
                            || pointer.initialKey?.computedData?.code == KeyCode.SPACE
                            || pointer.initialKey?.computedData?.code == KeyCode.CJK_SPACE))
                        if (swipeGestureDetector.onTouchMove(event, pointer, alwaysTriggerOnMove) || pointer.hasTriggeredGestureMove) {
                            pointer.hasTriggeredGestureMove = true
                            pointer.activeKey?.let { activeKey ->
                                inputEventDispatcher.sendCancel(activeKey.computedDataOnDown)
                            }
                        } else {
                            onTouchMoveInternal(event, pointer)
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val pointer = pointerMap.findById(pointerId)
                if (pointer != null) {
                    pointer.index = pointerIndex
                    if (swipeGestureDetector.onTouchUp(event, pointer) || pointer.hasTriggeredGestureMove) {
                        if (pointer.hasTriggeredGestureMove && pointer.initialKey?.computedData?.code == KeyCode.DELETE) {
                            if (keyboardManager.translateTakesKeys) {
                                keyboardManager.finishTranslateSwipeDelete()
                            } else {
                                val selection = editorInstance.activeContent.selection
                                if (selection.isSelectionMode) {
                                    editorInstance.deleteBackwards(OperationUnit.CHARACTERS)
                                }
                            }
                        }
                        onTouchCancelInternal(event, pointer)
                    } else {
                        onTouchUpInternal(event, pointer)
                    }
                    pointerMap.removeById(pointer.id)
                }
            }
            MotionEvent.ACTION_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                for (pointer in pointerMap) {
                    if (pointer.id == pointerId) {
                        pointer.index = pointerIndex
                        if (swipeGestureDetector.onTouchUp(event, pointer) || pointer.hasTriggeredGestureMove) {
                            if (pointer.hasTriggeredGestureMove &&
                                pointer.initialKey?.computedData?.code == KeyCode.DELETE &&
                                prefs.gestures.deleteKeySwipeLeft.get() != SwipeAction.SELECT_CHARACTERS_PRECISELY &&
                                prefs.gestures.deleteKeySwipeLeft.get() != SwipeAction.SELECT_WORDS_PRECISELY) {
                                // The translate bar's field has its own marked stretch (issue #424).
                                if (keyboardManager.translateTakesKeys) {
                                    keyboardManager.finishTranslateSwipeDelete()
                                } else {
                                    val selection = editorInstance.activeContent.selection
                                    if (selection.isSelectionMode) {
                                        editorInstance.deleteBackwards(OperationUnit.CHARACTERS)
                                    }
                                }
                            }
                            onTouchCancelInternal(event, pointer)
                        } else {
                            onTouchUpInternal(event, pointer)
                        }
                    } else {
                        swipeGestureDetector.onTouchCancel(event, pointer)
                        onTouchCancelInternal(event, pointer)
                    }
                }
                pointerMap.clear()
            }
            MotionEvent.ACTION_CANCEL -> {
                for (pointer in pointerMap) {
                    swipeGestureDetector.onTouchCancel(event, pointer)
                    onTouchCancelInternal(event, pointer)
                }
                pointerMap.clear()
            }
        }
    }

    private fun onTouchDownInternal(event: MotionEvent, pointer: TouchPointer) {
        flogDebug(LogTopic.TEXT_KEYBOARD_VIEW) { "pointer=$pointer" }

        // Only the key the finger actually landed on may open a layer — this runs again on every re-bind
        // within the same gesture, and sliding onto `ABC` inside the symbols must not open a second one.
        val isFirstDown = pointer.initialKey == null

        val key = keyboard.getKeyForPos(event.getX(pointer.index), event.getY(pointer.index))
        if (key != null && key.isEnabled) {
            key.computedDataOnDown = key.computedData
            // Remember where the finger actually landed, not just which key won (issue #242). The autocorrect
            // decodes from these coordinates, so a tap halfway between two keys stays distinguishable from a
            // dead-centre one. Consumed when the resulting character reaches the editor.
            if (key.computedData.type == KeyType.CHARACTER) {
                TouchTrace.pendingTap(event.getX(pointer.index), event.getY(pointer.index))
            }
            pointer.pressedKeyInfo = inputEventDispatcher.sendDown(
                data = key.computedData,
                onLongPress = onLongPress@ {
                    pointer.hasTriggeredLongPress = true
                    when (key.computedData.code) {
                        KeyCode.SPACE, KeyCode.CJK_SPACE -> {
                            when (prefs.gestures.spaceBarLongPress.get()) {
                                SwipeAction.NO_ACTION,
                                SwipeAction.INSERT_SPACE -> {
                                }
                                else -> {
                                    keyboardManager.executeSwipeAction(prefs.gestures.spaceBarLongPress.get())
                                }
                            }
                            true
                        }
                        KeyCode.SHIFT -> {
                            if (inputEventDispatcher.isUninterruptedEventSequence(key.computedData)) {
                                inputEventDispatcher.sendDownUp(TextKeyData.CAPS_LOCK)
                                inputFeedbackController?.keyLongPress(key.computedData)
                            }
                            // We always return false here to prevent blockade for the up touch event
                            false
                        }
                        KeyCode.LANGUAGE_SWITCH -> {
                            inputEventDispatcher.sendDownUp(TextKeyData.SYSTEM_INPUT_METHOD_PICKER)
                            true
                        }
                        else -> {
                            if (popupUiController.isSuitableForPopups(key) && key.computedPopups.getPopupKeys(
                                    keyHintConfiguration
                                ).isNotEmpty()
                            ) {
                                popupUiController.extend(key, size)
                                inputFeedbackController?.keyLongPress(key.computedData)
                                // The long-press popup now owns the horizontal swipe (pick an accent/umlaut,
                                // e.g. o → ö), so the legacy SWIPE-mode toggle must not hijack it back to
                                // the dictation UI (issue #221).
                                LegacyLayoutState.keyOwnsSwipe.value = true
                                true
                            } else {
                                false
                            }
                        }
                    }
                },
            )
            if (prefs.keyboard.popupEnabled.get() && popupUiController.isSuitableForPopups(key)) {
                popupUiController.show(key)
            }
            inputFeedbackController?.keyPress(key.computedData)
            key.isPressed = true
            if (pointer.initialKey == null) {
                pointer.initialKey = key
            }
            pointer.activeKey = key
            initSelectionStart = editorInstance.activeContent.selection.start
            initSelectionEnd = editorInstance.activeContent.selection.end
            // A layer key opens its layer right here, under the finger, instead of waiting for the lift
            // (issue #366). Nothing is lost for anyone who only taps: what happens on the way up still
            // depends on whether another key was pressed in between, and a plain tap latches as before.
            val downCode = key.computedData.code
            if (isFirstDown && momentary.isIdle && prefs.gestures.momentaryLayer.get()) {
                MomentaryLayer.modeFor(downCode)?.takeIf { it != keyboard.mode }?.let { target ->
                    momentary.begin(pointer.id, from = keyboard.mode, to = target)
                    keyboardManager.activeState.keyboardMode = target
                }
            }
            // Space/backspace own a horizontal swipe (cursor move / delete). Flag it (and clear it for any
            // other key, so it never gets stuck) so the legacy SWIPE-mode toggle doesn't hijack that swipe
            // on the modern keyboard (issue #188). A long-press accent popup raises the same flag later (#221).
            // A held-open layer owns its slide for the same reason — and has to keep owning it across the
            // re-binds that carry the finger from the layer key to the symbol it is reaching for (#366).
            LegacyLayoutState.keyOwnsSwipe.value =
                downCode == KeyCode.SPACE || downCode == KeyCode.CJK_SPACE || downCode == KeyCode.DELETE ||
                    !momentary.isIdle
        } else {
            pointer.activeKey = null
        }
    }

    private fun onTouchMoveInternal(event: MotionEvent, pointer: TouchPointer) {
        flogDebug(LogTopic.TEXT_KEYBOARD_VIEW) { "pointer=$pointer" }

        // The layer this finger asked for has not been laid out yet (issue #366). Re-binding now would
        // press a key of the layer that is leaving — one the user cannot see any more.
        if (momentary.owns(pointer.id) && momentary.isPending) return

        val initialKey = pointer.initialKey
        val activeKey = pointer.activeKey
        if (initialKey != null && activeKey != null) {
            if (popupUiController.isShowingExtendedPopup) {
                val x = event.getX(pointer.index)
                val y = event.getY(pointer.index)
                if (!popupUiController.propagateMotionEvent(activeKey, x, y)) {
                    clearMomentaryHighlight()
                    onTouchCancelInternal(event, pointer, isRebind = true)
                    onTouchDownInternal(event, pointer)
                }
            } else {
                if ((event.getX(pointer.index) < activeKey.visibleBounds.left - 0.1f * activeKey.visibleBounds.width)
                    || (event.getX(pointer.index) > activeKey.visibleBounds.right + 0.1f * activeKey.visibleBounds.width)
                    || (event.getY(pointer.index) < activeKey.visibleBounds.top - 0.35f * activeKey.visibleBounds.height)
                    || (event.getY(pointer.index) > activeKey.visibleBounds.bottom + 0.35f * activeKey.visibleBounds.height)
                ) {
                    clearMomentaryHighlight()
                    onTouchCancelInternal(event, pointer, isRebind = true)
                    onTouchDownInternal(event, pointer)
                }
            }
        }
    }

    private fun onTouchUpInternal(event: MotionEvent, pointer: TouchPointer) {
        flogDebug(LogTopic.TEXT_KEYBOARD_VIEW) { "pointer=$pointer" }
        // Read before the key is released below, which clears `activeKey` (issue #366).
        val landedCode = pointer.activeKey?.computedData?.code
        val layerData = pointer.initialKey?.computedData
        LegacyLayoutState.keyOwnsSwipe.value = false // clear the legacy-swipe guard (#188 / #221)
        pointer.pressedKeyInfo?.cancelJobs()
        pointer.pressedKeyInfo = null

        if (pointer.hasTriggeredMassSelection) {
            pointer.hasTriggeredMassSelection = false
            editorInstance.massSelection.end()
        }

        val initialKey = pointer.initialKey
        val activeKey = pointer.activeKey
        if (initialKey != null && activeKey != null) {
            activeKey.isPressed = false
            if (popupUiController.isSuitableForPopups(activeKey)) {
                val retData = popupUiController.getActiveKeyData(activeKey)
                if (retData != null && !pointer.hasTriggeredGestureMove) {
                    if (retData == activeKey.computedData) {
                        if (activeKey.computedData != activeKey.computedDataOnDown) {
                            inputEventDispatcher.sendCancel(activeKey.computedDataOnDown)
                            inputEventDispatcher.sendDownUp(activeKey.computedData)
                        } else {
                            inputEventDispatcher.sendUp(activeKey.computedDataOnDown)
                        }
                    } else {
                        inputEventDispatcher.sendCancel(activeKey.computedDataOnDown)
                        // Picked from the long-press popup (an accent, a variant): a deliberate choice, not a
                        // tap that might have missed. Recorded as certain so autocorrect never second-guesses
                        // it against neighbouring keys (issue #242).
                        TouchTrace.markPendingExact()
                        inputEventDispatcher.sendDownUp(retData)
                    }
                } else {
                    inputEventDispatcher.sendCancel(activeKey.computedDataOnDown)
                }
                popupUiController.hide()
            } else {
                if (pointer.hasTriggeredGestureMove) {
                    inputEventDispatcher.sendCancel(activeKey.computedDataOnDown)
                } else {
                    if (activeKey.computedData != activeKey.computedDataOnDown) {
                        inputEventDispatcher.sendCancel(activeKey.computedDataOnDown)
                        inputEventDispatcher.sendDownUp(activeKey.computedData)
                    } else {
                        inputEventDispatcher.sendUp(activeKey.computedDataOnDown)
                    }
                }
            }
            pointer.activeKey = null
        }
        pointer.hasTriggeredGestureMove = false

        // After the key has been sent, never before: a layer key's own up event puts the keyboard where a
        // tap would leave it, and only then is there something to undo (issue #366).
        if (momentary.owns(pointer.id)) {
            clearMomentaryHighlight()
            val restore = momentary.end(
                landedCode = landedCode,
                wasUninterrupted = layerData != null &&
                    inputEventDispatcher.isUninterruptedEventSequence(layerData),
            )
            restore?.let { keyboardManager.activeState.keyboardMode = it }
        }
    }

    /**
     * @param isRebind whether this is the release half of a slide onto another key rather than the end of
     *   the gesture. A held-open layer (issue #366) has to survive that — sliding onto the symbol is the
     *   whole point of it — but must not survive a real cancel.
     */
    private fun onTouchCancelInternal(event: MotionEvent, pointer: TouchPointer, isRebind: Boolean = false) {
        flogDebug(LogTopic.TEXT_KEYBOARD_VIEW) { "pointer=$pointer" }
        LegacyLayoutState.keyOwnsSwipe.value = false // clear the legacy-swipe guard (#188 / #221)
        pointer.pressedKeyInfo?.cancelJobs()
        pointer.pressedKeyInfo = null

        if (pointer.hasTriggeredMassSelection) {
            pointer.hasTriggeredMassSelection = false
            editorInstance.massSelection.end()
        }

        val activeKey = pointer.activeKey
        if (activeKey != null) {
            activeKey.isPressed = false
            inputEventDispatcher.sendCancel(activeKey.computedDataOnDown)
            if (popupUiController.isSuitableForPopups(activeKey)) {
                popupUiController.hide()
            }
            pointer.activeKey = null
        }
        pointer.hasTriggeredGestureMove = false

        // A press the system took away is not a choice: the keyboard goes back to the layer the gesture
        // started in rather than staying in one nobody confirmed (issue #366). This is also the path
        // `resetAllKeys` takes when the keyboard is dismissed mid-press.
        if (!isRebind && momentary.owns(pointer.id)) {
            clearMomentaryHighlight()
            momentary.endCancelled()?.let { keyboardManager.activeState.keyboardMode = it }
        }
    }

    override fun onSwipe(event: SwipeGesture.Event): Boolean {
        val pointer = pointerMap.findById(event.pointerId) ?: return false
        val initialKey = pointer.initialKey ?: return false
        val activeKey = pointer.activeKey
        flogDebug(LogTopic.TEXT_KEYBOARD_VIEW)

        return when (initialKey.computedData.code) {
            KeyCode.DELETE -> handleDeleteSwipe(event)
            KeyCode.SPACE, KeyCode.CJK_SPACE -> handleSpaceSwipe(event)
            else -> when {
                (initialKey.computedData.code == KeyCode.SHIFT && activeKey?.computedData?.code == KeyCode.SPACE ||
                    initialKey.computedData.code == KeyCode.SHIFT && activeKey?.computedData?.code == KeyCode.CJK_SPACE) &&
                    event.type == SwipeGesture.Type.TOUCH_MOVE -> handleSpaceSwipe(event)
                initialKey.computedData.code == KeyCode.SHIFT && activeKey?.computedData?.code != KeyCode.SHIFT &&
                    event.type == SwipeGesture.Type.TOUCH_UP -> {
                    activeKey?.let {
                        inputEventDispatcher.sendUp(popupUiController.getActiveKeyData(it) ?: it.computedDataOnDown)
                    }
                    inputEventDispatcher.sendCancel(TextKeyData.SHIFT)
                    true
                }
                initialKey.computedData.code > KeyCode.SPACE && !popupUiController.isShowingExtendedPopup -> when {
                    !isGlideEnabled && !pointer.hasTriggeredGestureMove -> when (event.type) {
                        // Under the finger, the moment the travel is unmistakable (issue #327). Waiting
                        // for lift-off is what made these feel dead: the accepting rule sampled speed as
                        // the finger left the glass, so a swipe you *end* — decelerating onto a target,
                        // which is what "swipe down to hide" is — measured as stationary and was dropped
                        // however far it had gone.
                        //
                        // Returning true here has the caller cancel the key press for this pointer and
                        // set hasTriggeredGestureMove, which this same branch checks — so the action can
                        // fire only once per gesture and the lift-off path below is skipped afterwards.
                        SwipeGesture.Type.TOUCH_MOVE -> {
                            val direction = swipeCommitDirection(event.absUnitCountX, event.absUnitCountY)
                            val swipeAction = direction?.let { swipeActionFor(it) } ?: SwipeAction.NO_ACTION
                            if (swipeAction != SwipeAction.NO_ACTION) {
                                keyboardManager.executeSwipeAction(swipeAction)
                                true
                            } else {
                                false
                            }
                        }
                        // The short-but-fast half of the rule: a flick that lifts before covering the
                        // distance above still counts, and there speed is the evidence of intent.
                        SwipeGesture.Type.TOUCH_UP -> {
                            val swipeAction = swipeActionFor(event.direction)
                            if (swipeAction != SwipeAction.NO_ACTION) {
                                keyboardManager.executeSwipeAction(swipeAction)
                                true
                            } else {
                                false
                            }
                        }
                    }
                    else -> false
                }
                else -> false
            }
        }
    }

    /** The action bound to a swipe direction on an ordinary character key. Diagonals carry none. */
    private fun swipeActionFor(direction: SwipeGesture.Direction): SwipeAction = when (direction) {
        SwipeGesture.Direction.UP -> prefs.gestures.swipeUp.get()
        SwipeGesture.Direction.DOWN -> prefs.gestures.swipeDown.get()
        SwipeGesture.Direction.LEFT -> prefs.gestures.swipeLeft.get()
        SwipeGesture.Direction.RIGHT -> prefs.gestures.swipeRight.get()
        else -> SwipeAction.NO_ACTION
    }

    private fun handleDeleteSwipe(event: SwipeGesture.Event): Boolean {
        if (editorInstance.activeInfo.isRawInputEditor) return false

        return when (event.type) {
            SwipeGesture.Type.TOUCH_MOVE -> when (prefs.gestures.deleteKeySwipeLeft.get()) {
                SwipeAction.DELETE_CHARACTERS_PRECISELY, SwipeAction.SELECT_CHARACTERS_PRECISELY -> {
                    if (abs(event.relUnitCountX) > 0) {
                        inputFeedbackController?.gestureMovingSwipe(TextKeyData.DELETE)
                    }
                    // While the translate bar has the keys the swipe marks its text, not the app's (#424).
                    if (keyboardManager.translateTakesKeys) {
                        keyboardManager.selectInTranslate(
                            units = -event.absUnitCountX - 1,
                            words = false,
                            forward = inputEventDispatcher.isPressed(KeyCode.SHIFT),
                        )
                        return true
                    }
                    val activeSelection = editorInstance.activeContent.selection
                    if (activeSelection.isValid) {
                        if (!inputEventDispatcher.isPressed(KeyCode.SHIFT)) {
                            // Backward select
                            editorInstance.setSelectionSurrounding(
                                n = -event.absUnitCountX - 1,
                                unit = OperationUnit.CHARACTERS,
                                scope = OperationScope.BEFORE_CURSOR,
                            )
                        } else {
                            // Forward select
                            editorInstance.setSelectionSurrounding(
                                n = -event.absUnitCountX - 1,
                                unit = OperationUnit.CHARACTERS,
                                scope = OperationScope.AFTER_CURSOR,
                            )
                        }
                    }
                    true
                }
                SwipeAction.DELETE_WORDS_PRECISELY, SwipeAction.SELECT_WORDS_PRECISELY -> {
                    if (abs(event.relUnitCountX) > 0) {
                        inputFeedbackController?.gestureMovingSwipe(TextKeyData.DELETE)
                    }
                    if (keyboardManager.translateTakesKeys) {
                        keyboardManager.selectInTranslate(
                            units = -event.absUnitCountX / 2 - 1,
                            words = true,
                            forward = inputEventDispatcher.isPressed(KeyCode.SHIFT),
                        )
                        return true
                    }
                    val activeSelection = editorInstance.activeContent.selection
                    if (activeSelection.isValid) {
                        if (!inputEventDispatcher.isPressed(KeyCode.SHIFT)) {
                            // Backward select
                            editorInstance.setSelectionSurrounding(
                                n = -event.absUnitCountX / 2 - 1,
                                unit = OperationUnit.WORDS,
                                scope = OperationScope.BEFORE_CURSOR,
                            )
                        } else {
                            // Forward select
                            editorInstance.setSelectionSurrounding(
                                n = -event.absUnitCountX / 2 - 1,
                                unit = OperationUnit.WORDS,
                                scope = OperationScope.AFTER_CURSOR,
                            )
                        }
                    }
                    true
                }
                else -> false
            }
            SwipeGesture.Type.TOUCH_UP -> {
                if (event.direction == SwipeGesture.Direction.LEFT &&
                    prefs.gestures.deleteKeySwipeLeft.get() == SwipeAction.DELETE_WORD
                ) {
                    keyboardManager.executeSwipeAction(prefs.gestures.deleteKeySwipeLeft.get())
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun handleSpaceSwipe(event: SwipeGesture.Event): Boolean {
        val pointer = pointerMap.findById(event.pointerId) ?: return false

        return when (event.type) {
            // Both axes on every report, rather than one of four directions (issue #364). The gesture is
            // a trackpad: the cursor is meant to end up where the finger points, and a diagonal is the
            // ordinary way to reach a spot three lines up and a few words in. Reading `event.direction`
            // here would make the two axes take turns, so it is not consulted at all any more.
            SwipeGesture.Type.TOUCH_MOVE -> {
                val movedAcross = glideAcross(event, pointer)
                val movedDown = glideDown(event, pointer)
                val fired = commitSpaceAction(event, pointer, SWIPE_COMMIT_UNITS)
                // Claiming the gesture is not only about having moved the cursor. Returning false leaves
                // the press with the ordinary key dispatch, which re-binds it to whatever the finger has
                // reached — and starts that key's long-press timer, so an upward slide off the space bar
                // opened the accent popup of the letter above it. Any configured space-bar gesture owns
                // the finger for the whole glide; only turning all of them off gives the keys back.
                movedAcross || movedDown || fired ||
                    horizontalAction(event) != SwipeAction.NO_ACTION ||
                    prefs.gestures.spaceBarSwipeUp.get() != SwipeAction.NO_ACTION ||
                    prefs.gestures.spaceBarSwipeDown.get() != SwipeAction.NO_ACTION
            }
            // The lift-off half of the same rule, for every direction. It asks for no distance of its
            // own — the detector has already required real travel and real speed to report at all.
            SwipeGesture.Type.TOUCH_UP -> commitSpaceAction(event, pointer, commitUnits = 1)
        }
    }

    /** The preference governing this sample's sideways travel — the axis has one pref per direction. */
    private fun horizontalAction(event: SwipeGesture.Event): SwipeAction = when {
        event.relUnitCountX < 0 -> prefs.gestures.spaceBarSwipeLeft.get()
        event.relUnitCountX > 0 -> prefs.gestures.spaceBarSwipeRight.get()
        // A sample with no sideways travel still has to name an action, or a purely vertical glide would
        // read as "nothing configured" and hand the finger back to the keys.
        event.absUnitCountX < 0 -> prefs.gestures.spaceBarSwipeLeft.get()
        else -> prefs.gestures.spaceBarSwipeRight.get()
    }

    /** One character per detector unit of sideways travel. Returns whether the cursor actually moved. */
    private fun glideAcross(event: SwipeGesture.Event, pointer: TouchPointer): Boolean {
        val rel = event.relUnitCountX
        if (rel == 0) return false
        val wanted = if (rel < 0) SwipeAction.MOVE_CURSOR_LEFT else SwipeAction.MOVE_CURSOR_RIGHT
        if (horizontalAction(event) != wanted) return false
        // The opening report is the one that crossed the threshold; its first unit is the price of
        // starting the glide, not a character the finger asked to pass.
        val count = abs(rel).let { if (!pointer.hasTriggeredGestureMove) it - 1 else it }
        if (count <= 0) return false
        beginGlideStep(pointer)
        keyboardManager.handleArrow(if (rel < 0) KeyCode.ARROW_LEFT else KeyCode.ARROW_RIGHT, count)
        return true
    }

    /**
     * One line per [SpaceGlide.LINE_TRAVEL_DP] of vertical travel (issue #364).
     *
     * A preference per direction, like the sideways half — so either can be given some other job without
     * taking the opposite one with it. [SpaceGlide.allowedLine] is what keeps a refused direction from
     * moving the count anyway.
     */
    private fun glideDown(event: SwipeGesture.Event, pointer: TouchPointer): Boolean {
        val upAllowed = prefs.gestures.spaceBarSwipeUp.get() == SwipeAction.MOVE_CURSOR_UP
        val downAllowed = prefs.gestures.spaceBarSwipeDown.get() == SwipeAction.MOVE_CURSOR_DOWN
        if (!upAllowed && !downAllowed) return false
        val unitsPerLine = SpaceGlide.unitsPerLine(prefs.gestures.swipeDistanceThreshold.get())
        val target = SpaceGlide.lineAt(event.absUnitCountY, unitsPerLine)
        val line = SpaceGlide.allowedLine(pointer.glideLine, target, upAllowed, downAllowed)
        val delta = line - pointer.glideLine
        if (delta == 0) return false
        pointer.glideLine = line
        beginGlideStep(pointer)
        keyboardManager.handleArrow(if (delta < 0) KeyCode.ARROW_UP else KeyCode.ARROW_DOWN, abs(delta))
        return true
    }

    /** The preference a space-bar swipe in this direction answers to. */
    private fun spaceActionFor(direction: SwipeGesture.Direction?): SwipeAction? = when (direction) {
        SwipeGesture.Direction.UP -> prefs.gestures.spaceBarSwipeUp.get()
        SwipeGesture.Direction.DOWN -> prefs.gestures.spaceBarSwipeDown.get()
        SwipeGesture.Direction.LEFT -> prefs.gestures.spaceBarSwipeLeft.get()
        SwipeGesture.Direction.RIGHT -> prefs.gestures.spaceBarSwipeRight.get()
        // An ambiguous diagonal is nobody's.
        else -> null
    }

    /** The cursor move the glide itself consumes in this direction, and so the one-shot must not. */
    private fun glideActionFor(direction: SwipeGesture.Direction?): SwipeAction? = when (direction) {
        SwipeGesture.Direction.UP -> SwipeAction.MOVE_CURSOR_UP
        SwipeGesture.Direction.DOWN -> SwipeAction.MOVE_CURSOR_DOWN
        SwipeGesture.Direction.LEFT -> SwipeAction.MOVE_CURSOR_LEFT
        SwipeGesture.Direction.RIGHT -> SwipeAction.MOVE_CURSOR_RIGHT
        else -> null
    }

    /**
     * The one-shot bound to a swipe on the space bar, fired once per gesture (issue #364).
     *
     * Called twice with different distances, which is the whole point. [SWIPE_COMMIT_UNITS] under the
     * finger, and on release whatever the detector was already willing to report — because the detector
     * only reports a lift-off swipe that was still moving at 1900 dp/s, and a downward swipe on the space
     * bar cannot be. There are about 77 dp between the middle of that key and the bottom of the screen,
     * so the finger is braking against the edge by the time it leaves the glass and measures as
     * stationary. Upwards has the whole screen to fling into and always worked, which is exactly how the
     * asymmetry showed up. `SwipeCommit` names this failure and issue #327 fixed it for the character
     * keys; the space bar was left on the lift-off path alone.
     *
     * Sideways has 411 dp of runway and a flick clears that speed easily, so it never looked broken — but
     * it is the same bug, and a swipe that is *ended* rather than flicked was dropped there too. All four
     * directions go through the one rule.
     *
     * The direction comes from [swipeCommitDirection] on both paths rather than the detector's
     * eight-sector reading, so a swipe 30° off an axis counts the same on release as it does mid-gesture.
     */
    private fun commitSpaceAction(
        event: SwipeGesture.Event,
        pointer: TouchPointer,
        commitUnits: Int,
    ): Boolean {
        if (pointer.hasCommittedSpaceAction) return false
        val direction = swipeCommitDirection(event.absUnitCountX, event.absUnitCountY, commitUnits)
        val action = spaceActionFor(direction) ?: return false
        // A cursor move is what the glide has been doing all along; firing it again here would add a step
        // nobody asked for.
        if (action == SwipeAction.NO_ACTION || action == glideActionFor(direction)) return false
        pointer.hasCommittedSpaceAction = true
        keyboardManager.executeSwipeAction(action)
        return true
    }

    /**
     * The tick and the selection latch every cursor step shares. The latch is opened once per glide and
     * closed again by [onTouchUpInternal] / [onTouchCancelInternal], which is why it is a pointer flag
     * rather than something either axis owns.
     */
    private fun beginGlideStep(pointer: TouchPointer) {
        inputFeedbackController?.gestureMovingSwipe(TextKeyData.SPACE)
        if (!pointer.hasTriggeredMassSelection) {
            pointer.hasTriggeredMassSelection = true
            editorInstance.massSelection.begin()
        }
    }

    /**
     * The tick that says a glide has begun — the same moment Gboard marks (issue #325).
     *
     * It runs off the "gesture swipe" preference rather than one of its own: a glide is the other thing
     * a swipe across the keys can turn into, and the alternative was a new setting whose only job is to
     * split a hair the finger cannot feel. Worth knowing when reading that switch's name.
     */
    override fun onGlideStart() {
        inputFeedbackController?.gestureSwipe(TextKeyData.UNSPECIFIED)
    }

    override fun onGlideAddPoint(point: GlideTypingGesture.Detector.Position) {
        if (isGlideEnabled) {
            glideDataForDrawing.add(point to System.currentTimeMillis())
        }
    }

    override fun onGlideComplete(data: GlideTypingGesture.Detector.PointerData) {
        onGlideCancelled()
    }

    override fun onGlideCancelled() {
        if (prefs.glide.showTrail.get()) {
            fadingGlide.clear()
            fadingGlide.addAll(glideDataForDrawing)

            val animator = ValueAnimator.ofFloat(20.0f, 0.0f)
            animator.interpolator = AccelerateInterpolator()
            animator.duration = prefs.glide.trailDuration.get().toLong()
            animator.addUpdateListener {
                fadingGlideRadius = it.animatedValue as Float
            }
            animator.start()

            glideDataForDrawing.clear()
            isGliding = false
        }
    }

    fun drawGlideTrail(
        drawScope: ContentDrawScope,
        gestureData: MutableList<Pair<GlideTypingGesture.Detector.Position, Long>>,
        targetDist: Float,
        initialRadius: Float,
        radiusReductionFactor: Float,
        color: Color,
    ) {
        var radius = initialRadius
        var drawnPoints = 0
        var prevX = gestureData.lastOrNull()?.first?.x ?: 0.0f
        var prevY = gestureData.lastOrNull()?.first?.y ?: 0.0f
        val time = System.currentTimeMillis()

        outer@ for (i in gestureData.size - 1 downTo 1) {
            if (time - gestureData[i - 1].second > prefs.glide.trailDuration.get()) break

            val dx = prevX - gestureData[i - 1].first.x
            val dy = prevY - gestureData[i - 1].first.y
            val dist = sqrt(dx * dx + dy * dy)

            val numPoints = (dist / targetDist).toInt()
            for (j in 0 until numPoints) {
                radius *= radiusReductionFactor
                val intermediateX =
                    gestureData[i].first.x * (1 - j.toFloat() / numPoints) + gestureData[i - 1].first.x * (j.toFloat() / numPoints)
                val intermediateY =
                    gestureData[i].first.y * (1 - j.toFloat() / numPoints) + gestureData[i - 1].first.y * (j.toFloat() / numPoints)
                drawScope.drawCircle(color, radius, center = Offset(intermediateX, intermediateY))
                drawnPoints += 1
                prevX = intermediateX
                prevY = intermediateY
            }
        }
    }

    private class TouchPointer : Pointer() {
        var initialKey: TextKey? = null
        var activeKey: TextKey? = null
        var hasTriggeredGestureMove: Boolean = false
        var hasTriggeredLongPress: Boolean = false
        var hasTriggeredMassSelection: Boolean = false
        /** Lines travelled by a space-bar glide so far, counted from where it began (issue #364). */
        var glideLine: Int = 0
        /**
         * Whether this gesture has already fired a space-bar one-shot. `hasTriggeredGestureMove` cannot
         * serve as that latch here the way it does for character keys: the glide claims the gesture on
         * its first report, so the flag is already set long before the action is decided.
         */
        var hasCommittedSpaceAction: Boolean = false
        var pressedKeyInfo: InputEventDispatcher.PressedKeyInfo? = null

        override fun reset() {
            super.reset()
            initialKey = null
            activeKey = null
            hasTriggeredGestureMove = false
            hasTriggeredLongPress = false
            hasTriggeredMassSelection = false
            glideLine = 0
            hasCommittedSpaceAction = false
            pressedKeyInfo = null
        }

        override fun toString(): String {
            return "${TouchPointer::class.simpleName} { id=$id, index=$index, initialKey=$initialKey, activeKey=$activeKey }"
        }
    }
}
