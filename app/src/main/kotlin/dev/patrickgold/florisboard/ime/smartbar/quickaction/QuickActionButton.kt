/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.compose.tooltip.PlainTooltip
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictatePromptsLayout
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Deselect
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.keyboard.computeImageVector
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dev.patrickgold.florisboard.ime.keyboard.computeLabel
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggIcon
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.unit.dp
import org.florisboard.lib.snygg.ui.SnyggText

/** How long the mic must be held before it becomes push-to-talk rather than a tap (#235). */
private const val PUSH_TO_TALK_HOLD_MS = 160L

/** How far the swollen mic travels left, and how far the finger must slide, to discard (#235). */
private val PUSH_TO_TALK_TRAVEL = 105.dp

/** Slide-left distance that arms discarding a held recording — same as the visual travel (#235). */
private val PUSH_TO_TALK_CANCEL_SLIDE = PUSH_TO_TALK_TRAVEL

/** Slide-down distance to the lock target that latches a held recording (#235). */
private val PUSH_TO_TALK_LOCK_SLIDE = 70.dp

/** Room reserved to the left of the key so the discarded mic can fly all the way to the bin (#235). */
private val PUSH_TO_TALK_FLIGHT_SPAN = 340.dp

/** How long the discarded mic takes to reach the bin — slow enough to read as a throw (#235). */
private const val PUSH_TO_TALK_FLIGHT_MS = 750

/** Space kept above the mic inside its window, so the throw's arc is not clipped (#235). */
private val BUBBLE_HEADROOM_TOP = 44.dp

/** Space kept below it, so dragging to the lock never cuts the mic off at the window edge (#235). */
private val BUBBLE_HEADROOM_BOTTOM = 24.dp

/** Diameter of the swollen mic, relative to the row it grows out of (#235). */
private const val HELD_MIC_DIAMETER = 1.7f

/**
 * The swollen mic shown while the key is held for push-to-talk (#235), plus the lock target below it.
 *
 * Deliberately popups rather than a scaled key: scaling the key transforms the node that owns the
 * pointer input, and Compose maps finger positions through that transform, which silently registered as
 * a slide-to-cancel. Popups are separate windows — they animate freely, are not clipped by the Smartbar,
 * and cannot perturb the gesture.
 *
 * Positioned from [keyBounds] rather than from the popup's own anchor: this is composed outside the key,
 * so its layout placeholder sits wherever the parent puts a zero-size child — which is not the key.
 *
 * It only appears when the rewording row is on. That row sits above the Smartbar and is the only thing
 * that gives us room upwards; without it the circle could only grow down and left, which looks lopsided
 * rather than pressed, so nothing grows at all.
 */
@Composable
private fun HeldMicBubble(keyBounds: IntRect, flying: Boolean) {
    val prefs by FlorisPreferenceStore
    val accent = remember { prefs.theme.accentColor.get() }
    val cancelProgress by DictateController.cancelSlideProgress.collectAsState()
    val lockProgress by DictateController.lockSlideProgress.collectAsState()
    val rowHeight = FlorisImeSizing.smartbarHeight
    val diameter = rowHeight * HELD_MIC_DIAMETER
    val density = LocalDensity.current
    val diameterPx = with(density) { diameter.roundToPx() }
    val headroomPx = with(density) { BUBBLE_HEADROOM_TOP.roundToPx() }

    val grow = remember { Animatable(0.35f) }
    LaunchedEffect(Unit) {
        grow.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    // Where the mic stood when the throw began. The slide progress is already reset by then, so without
    // remembering it the mic would snap back to the key and set off from there instead of from the hand.
    var thrownFrom by remember { mutableStateOf(0f) }
    LaunchedEffect(cancelProgress) { if (cancelProgress > 0f) thrownFrom = cancelProgress }
    // The throw: one shot, unhurried, and a real arc rather than a fade — the mic is a thing being
    // thrown away, so it should travel and land rather than dissolve where it stands.
    val flight = remember { Animatable(0f) }
    LaunchedEffect(flying) {
        if (flying) flight.animateTo(1f, tween(PUSH_TO_TALK_FLIGHT_MS, easing = FastOutSlowInEasing))
    }

    Popup(
        properties = PopupProperties(focusable = false, clippingEnabled = false),
        popupPositionProvider = remember(keyBounds, diameterPx, headroomPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ) = IntOffset(
                    // Right edge of the box holds the circle exactly over the key; everything to the
                    // left of it is runway for the slide and the throw, and the headroom above it is
                    // why the y is offset rather than simply centred.
                    x = keyBounds.center.x + diameterPx / 2 - popupContentSize.width,
                    y = keyBounds.center.y - headroomPx - diameterPx / 2,
                )
            }
        },
    ) {
        // The window is deliberately oversized: headroom above for the throw's arc, and room below for
        // the full drag to the lock, so the mic is never cut off at its own window edge mid-gesture.
        Box(
            modifier = Modifier.size(
                width = PUSH_TO_TALK_FLIGHT_SPAN + diameter,
                height = diameter + BUBBLE_HEADROOM_TOP + PUSH_TO_TALK_LOCK_SLIDE + BUBBLE_HEADROOM_BOTTOM,
            ),
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = BUBBLE_HEADROOM_TOP)
                    .size(diameter)
                    .graphicsLayer {
                        val f = flight.value
                        // Rides the finger along whichever axis it committed to (the gesture feeds only
                        // one of these at a time), so it never drifts off diagonally.
                        val slideX = -cancelProgress * PUSH_TO_TALK_TRAVEL.toPx()
                        val slideY = lockProgress * PUSH_TO_TALK_LOCK_SLIDE.toPx()
                        // Sets off from wherever the hand let go, not from the key.
                        val fromX = -thrownFrom * PUSH_TO_TALK_TRAVEL.toPx()
                        val toX = -PUSH_TO_TALK_FLIGHT_SPAN.toPx()
                        translationX = if (f > 0f) fromX + f * (toX - fromX) else slideX
                        // Tossed up first, then down into the bin — a straight line reads as sliding.
                        translationY = slideY - (4f * f * (1f - f)) * rowHeight.toPx()
                        rotationZ = -f * 90f
                        val thrown = 1f - 0.9f * f
                        scaleX = grow.value * thrown
                        scaleY = grow.value * thrown
                        alpha = 1f - f * f
                        shadowElevation = 12f
                        shape = CircleShape
                        clip = true
                    }
                    .background(accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(rowHeight * 0.6f),
                )
            }
        }
    }

    // Lock target under the mic. Below the Smartbar are our own keys, so unlike above there is room —
    // and a target that is simply visible from the start explains the gesture better than one that has
    // to be guessed at.
    if (!flying) {
        Popup(
            properties = PopupProperties(focusable = false, clippingEnabled = false),
            popupPositionProvider = remember(keyBounds) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ) = IntOffset(
                        x = keyBounds.center.x - popupContentSize.width / 2,
                        y = keyBounds.bottom + popupContentSize.height * 3 / 4,
                    )
                }
            },
        ) {
            val locked = lockProgress >= 1f
            Box(
                modifier = Modifier
                    .size(width = rowHeight * 0.9f, height = rowHeight * 1.25f)
                    .graphicsLayer {
                        alpha = (1f - cancelProgress).coerceIn(0f, 1f)
                        val lift = 1f + 0.15f * lockProgress
                        scaleX = lift
                        scaleY = lift
                    }
                    .background(
                        // Fills with the accent the closer the finger gets, so "how much further" needs
                        // no text.
                        lerp(Color.Black.copy(alpha = 0.55f), accent, lockProgress),
                        RoundedCornerShape(percent = 45),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(rowHeight * 0.38f),
                    )
                    if (!locked) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(rowHeight * 0.3f),
                        )
                    }
                }
            }
        }
    }
}

enum class QuickActionBarType {
    INTERACTIVE_BUTTON,
    INTERACTIVE_TILE,
    EDITOR_TILE;
}

@Composable
fun QuickActionButton(
    action: QuickAction,
    evaluator: ComputingEvaluator,
    modifier: Modifier = Modifier,
    type: QuickActionBarType = QuickActionBarType.INTERACTIVE_BUTTON,
) {
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val inputFeedbackController = LocalInputFeedbackController.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isEnabled = type == QuickActionBarType.EDITOR_TILE || evaluator.evaluateEnabled(action.keyData())
    val elementName = when (type) {
        QuickActionBarType.INTERACTIVE_BUTTON -> FlorisImeUi.SmartbarActionKey
        QuickActionBarType.INTERACTIVE_TILE -> FlorisImeUi.SmartbarActionTile
        QuickActionBarType.EDITOR_TILE -> FlorisImeUi.SmartbarActionsEditorTile
    }.elementName
    val attributes = mapOf(FlorisImeUi.Attr.Code to action.keyData().code)
    val selector = when {
        isPressed -> SnyggSelector.PRESSED
        !isEnabled -> SnyggSelector.DISABLED
        else -> null
    }

    // Need to manually cancel an action if this composable suddenly leaves the composition to prevent the key from
    // being stuck in the pressed state
    DisposableEffect(action, isEnabled) {
        onDispose {
            if (action is QuickAction.InsertKey) {
                action.onPointerCancel(context)
            }
        }
    }

    // The Dictate action has a dynamic icon (mic → send → hourglass) that depends on the recording state;
    // observed here so the icon recomputes on state changes (for all other actions it's a cheap, stable
    // subscription). Also drives tooltip suppression below.
    val dictateState by DictateController.state.collectAsState()
    // Suppress the tooltip while a long-press shortcut is armed on the Dictate mic, so holding it to run
    // the shortcut (pick a file when idle, or send-with-local-model while recording, #228) doesn't also
    // pop the tooltip text.
    val dictateLongPressArmed = action.keyData().code == KeyCode.IME_UI_MODE_DICTATE && (
        dictateState is DictateController.UiState.Idle ||
            (prefs.dictate.longPressSendLocalModel.get() && DictateController.canLongPressSendLocal())
    )
    // Push-to-talk (#235): the mic swells while it is being held, the way a voice-message button does —
    // the one piece of feedback that survives the finger covering the button itself.
    val pushToTalkPhase by DictateController.pushToTalkPhase.collectAsState()
    val holdingMic = action.keyData().code == KeyCode.IME_UI_MODE_DICTATE && pushToTalkPhase.isHolding
    // Where the key actually is on screen — the popups are anchored to this rather than to their own
    // placeholder, which sits wherever the parent puts a zero-size child.
    var micKeyBounds by remember { mutableStateOf<IntRect?>(null) }
    var micHiddenByBubble by remember { mutableStateOf(false) }
    // Only with the rewording row above the Smartbar is there room to grow upwards; without it the
    // circle would only be able to expand down and left, which reads as lopsided rather than pressed.
    val hasRoomAbove = remember {
        prefs.dictate.rewordingEnabled.get() &&
            prefs.dictate.promptsLayout.get() == DictatePromptsLayout.ROW
    }
    // The throw outlives the hold: the phase is already back to NONE by the time the mic starts moving.
    val discards by DictateController.pushToTalkDiscards.collectAsState()
    var flying by remember { mutableStateOf(false) }
    LaunchedEffect(discards) {
        if (discards > 0) {
            flying = true
            delay((PUSH_TO_TALK_FLIGHT_MS + 60).toLong())
            flying = false
        }
    }
    val bounds = micKeyBounds
    val bubbleShown = (holdingMic || flying) && hasRoomAbove && bounds != null
    micHiddenByBubble = holdingMic && hasRoomAbove && bounds != null
    if (bubbleShown && bounds != null) HeldMicBubble(bounds, flying)
    PlainTooltip(
        action.computeTooltip(evaluator),
        enabled = type == QuickActionBarType.INTERACTIVE_BUTTON && !dictateLongPressArmed,
    ) {
        SnyggBox(
            elementName = elementName,
            attributes = attributes,
            selector = selector,
            // Never transformed. Compose maps pointer positions through a layer transform, so scaling
            // this node moved the reported finger position by the very amount that reads as a
            // slide-to-cancel — measured at progress 1.009 every single time. The held-state swell is a
            // separate popup instead (see HeldMicBubble), which cannot touch the gesture.
            // Hidden while the overlay stands in for it — seeing the small key peek out from under the
            // big one gives away that they are two different things. Alpha only: a transform here would
            // move the reported finger position and be read as a slide.
            modifier = modifier.alpha(if (micHiddenByBubble) 0f else 1f).onGloballyPositioned {
                if (action.keyData().code == KeyCode.IME_UI_MODE_DICTATE) {
                    micKeyBounds = it.boundsInWindow().roundToIntRect()
                }
            },
            clickAndSemanticsModifier = Modifier
                .aspectRatio(1f)
                .indication(interactionSource, LocalIndication.current)
                .pointerInput(action, isEnabled) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        if (isEnabled && type != QuickActionBarType.EDITOR_TILE) {
                            val press = PressInteraction.Press(down.position)
                            inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                            interactionSource.tryEmit(press)
                            action.onPointerDown(context)

                            // The Dictate mic supports two long-press shortcuts:
                            //  • idle: hold to pick an existing audio/video file to transcribe (#88).
                            //  • recording (opt-in, #228): hold the send button to transcribe this
                            //    recording with the on-device model instead of the cloud provider.
                            val isDictate = action.keyData().code == KeyCode.IME_UI_MODE_DICTATE
                            val dictateIdle = isDictate &&
                                DictateController.state.value is DictateController.UiState.Idle
                            val dictateSendLocal = isDictate && !dictateIdle &&
                                prefs.dictate.longPressSendLocalModel.get() &&
                                DictateController.canLongPressSendLocal()
                            // Push-to-talk (#235) takes the whole gesture: hold to record, slide left to
                            // discard, slide up to latch. It replaces the long-press shortcuts below,
                            // which is why it is opt-in.
                            if (dictateIdle && DictateController.isPushToTalkActive(context)) {
                                // Tap and hold both work, as on a voice-message button: a quick tap is
                                // the ordinary start/stop toggle, holding past the long-press delay
                                // switches to push-to-talk. Waiting for that delay first is also what
                                // makes a mis-tap impossible to turn into a recording.
                                val longPressDelay = PUSH_TO_TALK_HOLD_MS
                                var heldPastDelay = false
                                var tapUp: PointerInputChange? = null
                                try {
                                    tapUp = withTimeout(longPressDelay) { waitForUpOrCancellation() }
                                } catch (_: PointerEventTimeoutCancellationException) {
                                    heldPastDelay = true
                                }
                                if (!heldPastDelay) {
                                    handleUpOrCancel(tapUp, press, interactionSource, action, context)
                                } else {
                                interactionSource.tryEmit(PressInteraction.Release(press))
                                DictateController.onPushToTalkDown(context)
                                val cancelSlide = PUSH_TO_TALK_CANCEL_SLIDE.toPx()
                                val lockSlide = PUSH_TO_TALK_LOCK_SLIDE.toPx()
                                var ended = false
                                while (true) {
                                    val change = awaitPointerEvent().changes
                                        .firstOrNull { it.id == down.id } ?: break
                                    change.consume()
                                    if (!change.pressed) break
                                    val dx = change.position.x - down.position.x
                                    val dy = change.position.y - down.position.y
                                    // Commit to one axis: whichever the finger has travelled furthest
                                    // along wins, and the other is held at zero. Feeding both let the
                                    // mic wander off diagonally to places neither target lives.
                                    val left = (-dx).coerceAtLeast(0f)
                                    val downward = dy.coerceAtLeast(0f)
                                    val goingDown = downward > left
                                    if (goingDown && downward > lockSlide) {
                                        DictateController.lockPushToTalk()
                                        inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                                        ended = true
                                        break
                                    }
                                    DictateController.onPushToTalkLockSlide(
                                        if (goingDown) downward / lockSlide else 0f,
                                    )
                                    // Crossing the cancel threshold discards there and then.
                                    if (DictateController.onPushToTalkSlide(
                                            if (goingDown) 0f else left / cancelSlide,
                                        )
                                    ) {
                                        inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                                        ended = true
                                        break
                                    }
                                }
                                if (ended) {
                                    // Latched or discarded: the rest of the gesture is not ours any more.
                                    waitForUpOrCancellation()?.consume()
                                } else {
                                    DictateController.onPushToTalkUp(context)
                                }
                                }
                            } else if (dictateIdle || dictateSendLocal) {
                                val longPressDelay = prefs.keyboard.longPressDelay.get().toLong()
                                try {
                                    val up = withTimeout(longPressDelay) { waitForUpOrCancellation() }
                                    handleUpOrCancel(up, press, interactionSource, action, context)
                                } catch (_: PointerEventTimeoutCancellationException) {
                                    // Held long enough: run the shortcut and swallow the rest of the
                                    // gesture so the normal tap (start recording / send) does not run.
                                    interactionSource.tryEmit(PressInteraction.Cancel(press))
                                    action.onPointerCancel(context)
                                    if (dictateIdle) {
                                        DictateController.startFileTranscription(context)
                                    } else {
                                        DictateController.stopAndTranscribeLocal(context)
                                    }
                                    waitForUpOrCancellation()?.consume()
                                }
                            } else {
                                handleUpOrCancel(
                                    waitForUpOrCancellation(), press, interactionSource, action, context,
                                )
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Render foreground
                when (action) {
                    is QuickAction.InsertKey -> {
                        // Uses the hoisted [dictateState] above (dynamic mic → send → hourglass icon).
                        // Select-all is a toggle (issue #152): reflect the field's selection live so the
                        // icon shows "deselect" when text is selected. distinctUntilChanged keeps this
                        // cheap for every action button — it only recomposes when selection presence flips.
                        val editorInstance by context.editorInstance()
                        val hasSelection by remember(editorInstance) {
                            editorInstance.activeContentFlow
                                .map { it.selection.isSelectionMode }
                                .distinctUntilChanged()
                        }.collectAsState(initial = editorInstance.activeContent.selection.isSelectionMode)
                        val (imageVector, label) = remember(action, evaluator, dictateState, hasSelection) {
                            val icon = if (action.data.code == KeyCode.CLIPBOARD_SELECT_ALL && hasSelection) {
                                Icons.Default.Deselect
                            } else {
                                evaluator.computeImageVector(action.data)
                            }
                            icon to evaluator.computeLabel(action.data)
                        }
                        if (imageVector != null) {
                            SnyggBox(
                                elementName = "$elementName-icon",
                                attributes = attributes,
                                selector = selector,
                            ) {
                                // The Material "GIF" glyph draws small lettering inside a lot of padding;
                                // scale it up so the "GIF" text is legible at the Smartbar icon size.
                                val iconModifier = if (action.data.code == KeyCode.IME_UI_MODE_GIF) {
                                    Modifier.scale(1.45f)
                                } else {
                                    Modifier
                                }
                                SnyggIcon(imageVector = imageVector, modifier = iconModifier)
                            }
                        } else if (label != null) {
                            SnyggText(
                                elementName = "$elementName-text",
                                attributes = attributes,
                                selector = selector,
                                text = label,
                            )
                        }
                    }

                    is QuickAction.InsertText -> {
                        SnyggText(
                            elementName = "$elementName-text",
                            attributes = attributes,
                            selector = selector,
                            text = action.data.firstOrNull().toString().ifBlank { "?" },
                        )
                    }
                }

                // Render additional info if this is a tile
                if (type != QuickActionBarType.INTERACTIVE_BUTTON) {
                    SnyggText(
                        elementName = "$elementName-text",
                        attributes = attributes,
                        selector = selector,
                        text = action.computeDisplayName(evaluator = evaluator),
                    )
                }
            }
        }
    }
}

/** Finishes a pointer gesture: a non-null [up] is a normal release (click), null is a cancellation. */
private fun handleUpOrCancel(
    up: PointerInputChange?,
    press: PressInteraction.Press,
    interactionSource: MutableInteractionSource,
    action: QuickAction,
    context: Context,
) {
    if (up != null) {
        up.consume()
        interactionSource.tryEmit(PressInteraction.Release(press))
        action.onPointerUp(context)
    } else {
        interactionSource.tryEmit(PressInteraction.Cancel(press))
        action.onPointerCancel(context)
    }
}
