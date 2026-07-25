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
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.compose.tooltip.PlainTooltip
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Deselect
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.keyboard.computeImageVector
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dev.patrickgold.florisboard.ime.keyboard.computeLabel
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggText

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
    PlainTooltip(
        action.computeTooltip(evaluator),
        enabled = type == QuickActionBarType.INTERACTIVE_BUTTON && !dictateLongPressArmed,
    ) {
        SnyggBox(
            elementName = elementName,
            attributes = attributes,
            selector = selector,
            modifier = modifier,
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
                            if (dictateIdle || dictateSendLocal) {
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
