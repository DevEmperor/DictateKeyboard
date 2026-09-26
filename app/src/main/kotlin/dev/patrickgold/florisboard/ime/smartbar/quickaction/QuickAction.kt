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
import androidx.compose.runtime.Composable
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.keyboardManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.florisboard.lib.compose.stringRes

@Serializable
sealed class QuickAction {
    /**
     * [onLongPress] is the button's second action (issue #385): returning true from it runs that
     * instead of the tap, and — because the dispatcher enters its repeat loop only when a long press
     * declines — also stops the key repeating. Unpaired buttons pass nothing and behave as before.
     */
    open fun onPointerDown(context: Context, onLongPress: () -> Boolean = { false }) = Unit

    open fun onPointerUp(context: Context) = Unit

    open fun onPointerCancel(context: Context) = Unit

    @Serializable
    @SerialName("insert_key")
    data class InsertKey(val data: KeyData) : QuickAction() {
        override fun onPointerDown(context: Context, onLongPress: () -> Boolean) {
            val keyboardManager by context.keyboardManager()
            keyboardManager.inputEventDispatcher.sendDown(data, onLongPress)
        }

        override fun onPointerUp(context: Context) {
            val keyboardManager by context.keyboardManager()
            keyboardManager.inputEventDispatcher.sendUp(data)
            if (!keyboardManager.inputEventDispatcher.isRepeatable(data) &&
                data.code != KeyCode.TOGGLE_ACTIONS_OVERFLOW && data.code != KeyCode.CLIPBOARD_SELECT_ALL) {
                keyboardManager.activeState.isActionsOverflowVisible = false
            }
        }

        override fun onPointerCancel(context: Context) {
            val keyboardManager by context.keyboardManager()
            keyboardManager.inputEventDispatcher.sendCancel(data)
        }
    }

    @Serializable
    @SerialName("insert_text")
    data class InsertText(val data: String) : QuickAction() {
        override fun onPointerUp(context: Context) {
            val editorInstance by context.editorInstance()
            editorInstance.commitText(data)
        }
    }
}

fun QuickAction.keyData(): KeyData {
    return if (this is QuickAction.InsertKey) data else TextKeyData.UNSPECIFIED
}

/**
 * Runs this action as another button's long press (issue #385).
 *
 * One complete down-up, the same way the globe key's hold reaches the input method picker: a hold is
 * a finished errand, so there is no press left over to repeat and nothing to release afterwards.
 * Going through the dispatcher also keeps `massSelection.begin()`/`end()` balanced for the cursor
 * actions, which a bare `sendUp` would not.
 */
fun QuickAction.performAsSecondAction(context: Context) {
    val keyboardManager by context.keyboardManager()
    when (this) {
        is QuickAction.InsertKey -> keyboardManager.inputEventDispatcher.sendDownUp(data)
        is QuickAction.InsertText -> {
            val editorInstance by context.editorInstance()
            editorInstance.commitText(data)
        }
    }
    // The tap path closes the overflow panel itself, but exempts select-all so the deselect that may
    // follow is still one tap away. A hold is not that case: it ran something else entirely.
    if (keyboardManager.activeState.isActionsOverflowVisible) {
        keyboardManager.activeState.isActionsOverflowVisible = false
    }
}

@Composable
fun QuickAction.computeDisplayName(evaluator: ComputingEvaluator): String {
    return when (this) {
        is QuickAction.InsertKey -> stringRes(when (data.code) {
            KeyCode.ARROW_UP -> R.string.quick_action__arrow_up
            KeyCode.ARROW_DOWN -> R.string.quick_action__arrow_down
            KeyCode.ARROW_LEFT -> R.string.quick_action__arrow_left
            KeyCode.ARROW_RIGHT -> R.string.quick_action__arrow_right
            KeyCode.MOVE_START_OF_PAGE -> R.string.quick_action__move_start_of_page
            KeyCode.MOVE_END_OF_PAGE -> R.string.quick_action__move_end_of_page
            KeyCode.CLIPBOARD_CLEAR_PRIMARY_CLIP -> R.string.quick_action__clipboard_clear_primary_clip
            KeyCode.CLIPBOARD_COPY -> R.string.quick_action__clipboard_copy
            KeyCode.CLIPBOARD_CUT -> R.string.quick_action__clipboard_cut
            KeyCode.CLIPBOARD_PASTE -> R.string.quick_action__clipboard_paste
            KeyCode.CLIPBOARD_SELECT_ALL -> R.string.quick_action__clipboard_select_all
            KeyCode.FORWARD_DELETE -> R.string.quick_action__forward_delete
            KeyCode.IME_UI_MODE_CLIPBOARD -> R.string.quick_action__ime_ui_mode_clipboard
            KeyCode.IME_UI_MODE_MEDIA -> R.string.quick_action__ime_ui_mode_media
            KeyCode.IME_UI_MODE_GIF -> R.string.quick_action__ime_ui_mode_gif
            KeyCode.IME_UI_MODE_STICKER -> R.string.quick_action__ime_ui_mode_sticker
            KeyCode.IME_UI_MODE_EDITING -> R.string.quick_action__ime_ui_mode_editing
            KeyCode.IME_UI_MODE_SCAN -> R.string.quick_action__ime_ui_mode_scan
            KeyCode.TRANSLATE -> R.string.quick_action__translate
            KeyCode.IME_UI_MODE_DICTATE -> R.string.quick_action__ime_ui_mode_dictate
            KeyCode.DICTATE_LIVE_PROMPT -> R.string.quick_action__dictate_live_prompt
            KeyCode.DICTATE_PROMPTS -> R.string.quick_action__dictate_prompts
            KeyCode.DICTATE_REINSERT -> R.string.quick_action__dictate_reinsert
            KeyCode.DICTATE_SWITCH_PROVIDER -> R.string.quick_action__dictate_switch_provider
            KeyCode.LANGUAGE_SWITCH -> R.string.quick_action__language_switch
            KeyCode.SYSTEM_PREV_INPUT_METHOD -> R.string.quick_action__system_prev_input_method
            KeyCode.SYSTEM_INPUT_METHOD_PICKER -> R.string.quick_action__system_input_method_picker
            KeyCode.SETTINGS -> R.string.quick_action__settings
            KeyCode.UNDO -> R.string.quick_action__undo
            KeyCode.REDO -> R.string.quick_action__redo
            KeyCode.TOGGLE_ACTIONS_OVERFLOW -> R.string.quick_action__toggle_actions_overflow
            KeyCode.TOGGLE_INCOGNITO_MODE -> R.string.quick_action__toggle_incognito_mode
            KeyCode.IME_HIDE_UI -> R.string.quick_action__ime_hide_ui
            KeyCode.TOGGLE_FLOATING_WINDOW -> R.string.quick_action__floating_window_mode
            // TODO: In the future this will be merged into the resize keyboard panel, for now it is a separate action
            KeyCode.TOGGLE_COMPACT_LAYOUT -> R.string.quick_action__one_handed_mode
            KeyCode.SPLIT_LAYOUT -> R.string.quick_action__split_layout
            KeyCode.TOGGLE_RESIZE_MODE -> R.string.quick_action__resize_mode
            KeyCode.TOGGLE_NUMBER_ROW -> R.string.quick_action__toggle_number_row
            KeyCode.VIEW_NUMERIC_ADVANCED -> R.string.quick_action__view_numeric_advanced
            KeyCode.DRAG_MARKER -> if (evaluator.state.debugShowDragAndDropHelpers) {
                R.string.quick_action__drag_marker
            } else {
                R.string.general__empty_string
            }
            KeyCode.NOOP -> R.string.quick_action__noop
            else -> R.string.general__invalid_fatal
        })
        is QuickAction.InsertText -> data
    }
}

@Composable
fun QuickAction.computeTooltip(evaluator: ComputingEvaluator): String {
    return when (this) {
        is QuickAction.InsertKey -> stringRes(when (data.code) {
            KeyCode.ARROW_UP -> R.string.quick_action__arrow_up__tooltip
            KeyCode.ARROW_DOWN -> R.string.quick_action__arrow_down__tooltip
            KeyCode.ARROW_LEFT -> R.string.quick_action__arrow_left__tooltip
            KeyCode.ARROW_RIGHT -> R.string.quick_action__arrow_right__tooltip
            KeyCode.MOVE_START_OF_PAGE -> R.string.quick_action__move_start_of_page__tooltip
            KeyCode.MOVE_END_OF_PAGE -> R.string.quick_action__move_end_of_page__tooltip
            KeyCode.CLIPBOARD_CLEAR_PRIMARY_CLIP -> R.string.quick_action__clipboard_clear_primary_clip__tooltip
            KeyCode.CLIPBOARD_COPY -> R.string.quick_action__clipboard_copy__tooltip
            KeyCode.CLIPBOARD_CUT -> R.string.quick_action__clipboard_cut__tooltip
            KeyCode.CLIPBOARD_PASTE -> R.string.quick_action__clipboard_paste__tooltip
            KeyCode.CLIPBOARD_SELECT_ALL -> R.string.quick_action__clipboard_select_all__tooltip
            KeyCode.IME_UI_MODE_CLIPBOARD -> R.string.quick_action__ime_ui_mode_clipboard__tooltip
            KeyCode.IME_UI_MODE_MEDIA -> R.string.quick_action__ime_ui_mode_media__tooltip
            KeyCode.IME_UI_MODE_GIF -> R.string.quick_action__ime_ui_mode_gif__tooltip
            KeyCode.IME_UI_MODE_STICKER -> R.string.quick_action__ime_ui_mode_sticker__tooltip
            KeyCode.IME_UI_MODE_EDITING -> R.string.quick_action__ime_ui_mode_editing__tooltip
            KeyCode.IME_UI_MODE_SCAN -> R.string.quick_action__ime_ui_mode_scan__tooltip
            KeyCode.TRANSLATE -> R.string.quick_action__translate__tooltip
            KeyCode.IME_UI_MODE_DICTATE -> R.string.quick_action__ime_ui_mode_dictate__tooltip
            KeyCode.DICTATE_LIVE_PROMPT -> R.string.quick_action__dictate_live_prompt__tooltip
            KeyCode.DICTATE_PROMPTS -> R.string.quick_action__dictate_prompts__tooltip
            KeyCode.DICTATE_REINSERT -> R.string.quick_action__dictate_reinsert__tooltip
            KeyCode.DICTATE_SWITCH_PROVIDER -> R.string.quick_action__dictate_switch_provider__tooltip
            KeyCode.LANGUAGE_SWITCH -> R.string.quick_action__language_switch__tooltip
            KeyCode.SYSTEM_PREV_INPUT_METHOD -> R.string.quick_action__system_prev_input_method__tooltip
            KeyCode.SYSTEM_INPUT_METHOD_PICKER -> R.string.quick_action__system_input_method_picker__tooltip
            KeyCode.SETTINGS -> R.string.quick_action__settings__tooltip
            KeyCode.UNDO -> R.string.quick_action__undo__tooltip
            KeyCode.REDO -> R.string.quick_action__redo__tooltip
            KeyCode.TOGGLE_ACTIONS_OVERFLOW -> R.string.quick_action__toggle_actions_overflow__tooltip
            KeyCode.TOGGLE_INCOGNITO_MODE -> R.string.quick_action__toggle_incognito_mode__tooltip
            KeyCode.IME_HIDE_UI -> R.string.quick_action__ime_hide_ui__tooltip
            KeyCode.TOGGLE_FLOATING_WINDOW -> R.string.quick_action__floating_window_mode__tooltip
            // TODO: In the future this will be merged into the resize keyboard panel, for now it is a separate action
            KeyCode.TOGGLE_COMPACT_LAYOUT -> R.string.quick_action__one_handed_mode__tooltip
            KeyCode.SPLIT_LAYOUT -> R.string.quick_action__split_layout__tooltip
            KeyCode.TOGGLE_RESIZE_MODE -> R.string.quick_action__resize_mode__tooltip
            KeyCode.TOGGLE_NUMBER_ROW -> R.string.quick_action__toggle_number_row__tooltip
            KeyCode.VIEW_NUMERIC_ADVANCED -> R.string.quick_action__view_numeric_advanced__tooltip
            KeyCode.DRAG_MARKER -> if (evaluator.state.debugShowDragAndDropHelpers) {
                R.string.quick_action__drag_marker__tooltip
            } else {
                R.string.general__empty_string
            }
            KeyCode.NOOP -> R.string.quick_action__noop__tooltip
            else -> R.string.general__invalid_fatal
        })
        is QuickAction.InsertText -> "Insert text '$data'"
    }
}
