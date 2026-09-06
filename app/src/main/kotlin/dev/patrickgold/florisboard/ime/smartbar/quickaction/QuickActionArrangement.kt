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

import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.lib.io.DefaultJsonConfig
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import kotlinx.serialization.modules.polymorphic

val QuickActionJsonConfig = Json(DefaultJsonConfig) {
    classDiscriminator = "$"
    encodeDefaults = false
    ignoreUnknownKeys = true
    isLenient = false

    serializersModule += SerializersModule {
        polymorphic(QuickAction::class) {
            subclass(QuickAction.InsertKey::class, QuickAction.InsertKey.serializer())
            subclass(QuickAction.InsertText::class, QuickAction.InsertText.serializer())
            defaultDeserializer { QuickAction.InsertKey.serializer() }
        }
    }
}

@Serializable
data class QuickActionArrangement(
    val stickyAction: QuickAction?,
    val dynamicActions: List<QuickAction>,
    val hiddenActions: List<QuickAction>,
) {
    operator fun contains(action: QuickAction): Boolean {
        return stickyAction == action || dynamicActions.contains(action) || hiddenActions.contains(action)
    }

    fun distinct(): QuickActionArrangement {
        val distinctSet = mutableSetOf<QuickAction>()
        if (stickyAction != null) {
            distinctSet.add(stickyAction)
        }
        val distinctDynamicActions = dynamicActions.filter { distinctSet.add(it) }
        val distinctHiddenActions = hiddenActions.filter { distinctSet.add(it) }
        return QuickActionArrangement(
            stickyAction = stickyAction,
            dynamicActions = distinctDynamicActions,
            hiddenActions = distinctHiddenActions,
        )
    }

    companion object {
        val Default = QuickActionArrangement(
            // Dictate's flagship action: the AI voice panel is always one tap away in the Smartbar.
            stickyAction = QuickAction.InsertKey(TextKeyData.IME_UI_MODE_DICTATE),
            dynamicActions = listOf(
                // Default visible order requested by the user. The live prompt is no longer a Smartbar
                // button – it lives as a chip inside the prompt panel/row – so only the panel opener
                // (DICTATE_PROMPTS) remains here.
                QuickAction.InsertKey(TextKeyData.DICTATE_PROMPTS),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_SELECT_ALL),
                QuickAction.InsertKey(TextKeyData.UNDO),
                QuickAction.InsertKey(TextKeyData.REDO),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_CUT),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_COPY),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_PASTE),
                QuickAction.InsertKey(TextKeyData.SETTINGS),
                QuickAction.InsertKey(TextKeyData.TOGGLE_FLOATING_WINDOW),
                QuickAction.InsertKey(TextKeyData.TOGGLE_RESIZE_MODE),
                QuickAction.InsertKey(TextKeyData.IME_UI_MODE_CLIPBOARD),
                QuickAction.InsertKey(TextKeyData.IME_UI_MODE_MEDIA),
                // GIF search panel (KLIPY). Present in the action list so users can drag it into the bar
                // for one-tap GIF access; it does nothing until a free KLIPY API key is added in settings.
                QuickAction.InsertKey(TextKeyData.IME_UI_MODE_GIF),
                // Local sticker panel (issue #280): the folder the user picked, no network involved.
                // Like the GIF action it sits in the list until dragged into the bar.
                QuickAction.InsertKey(TextKeyData.IME_UI_MODE_STICKER),
                QuickAction.InsertKey(TextKeyData.TOGGLE_COMPACT_LAYOUT),
                QuickAction.InsertKey(TextKeyData.TOGGLE_INCOGNITO_MODE),
                QuickAction.InsertKey(TextKeyData.ARROW_UP),
                QuickAction.InsertKey(TextKeyData.ARROW_DOWN),
                QuickAction.InsertKey(TextKeyData.ARROW_LEFT),
                QuickAction.InsertKey(TextKeyData.ARROW_RIGHT),
                // Jump to the very start or end of the field (issue #335). The key codes and their
                // handler have been here all along, but only a swipe gesture could reach them — next to
                // the arrows, because that is what they are: the same journey, in one step.
                QuickAction.InsertKey(TextKeyData.MOVE_START_OF_PAGE),
                QuickAction.InsertKey(TextKeyData.MOVE_END_OF_PAGE),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_CLEAR_PRIMARY_CLIP),
                QuickAction.InsertKey(TextKeyData.LANGUAGE_SWITCH),
                // IME-switch actions (issue #122): one-tap return to the previously used keyboard, plus the
                // system keyboard picker. Useful when pairing Dictate with another IME (e.g. a Japanese
                // Kana–Kanji keyboard). Visible in the Smartbar by default; users can hide them in the editor.
                QuickAction.InsertKey(TextKeyData.SYSTEM_PREV_INPUT_METHOD),
                QuickAction.InsertKey(TextKeyData.SYSTEM_INPUT_METHOD_PICKER),
                QuickAction.InsertKey(TextKeyData.FORWARD_DELETE),
                QuickAction.InsertKey(TextKeyData.IME_HIDE_UI),
                // Re-insert / re-send the last transcription safety net (issue #111). Placed at the end
                // so it is present in the action list without taking a prominent spot at the top of the bar.
                QuickAction.InsertKey(TextKeyData.DICTATE_REINSERT),
                // Fold the digit row away and back without a trip through settings (issue #333). At the
                // end for the same reason: worth having in the list, not worth a Smartbar slot for
                // everyone who never turned the row on in the first place.
                QuickAction.InsertKey(TextKeyData.TOGGLE_NUMBER_ROW),
            ),
            hiddenActions = listOf(
            ),
        )
    }

    object Serializer : PreferenceSerializer<QuickActionArrangement> {
        override fun serialize(value: QuickActionArrangement): String {
            return QuickActionJsonConfig.encodeToString(value)
        }

        // Key codes of actions that were removed from the app; dropped from any existing stored
        // arrangement so they don't linger as "!! invalid !!". -245 = the old autocorrect-toggle
        // placeholder (autocorrect is now fully automatic). -27/-28 = the line-start/line-end buttons
        // that existed for a day between two commits of #335 before they became field-start/field-end;
        // they never reached a release, but a debug arrangement can still carry them.
        private val REMOVED_ACTION_CODES = setOf(-245, -27, -28)

        override fun deserialize(value: String): QuickActionArrangement {
            val raw: QuickActionArrangement = QuickActionJsonConfig.decodeFromString(value)
            fun QuickAction.isRemoved() = this is QuickAction.InsertKey && data.code in REMOVED_ACTION_CODES
            val stored = raw.copy(
                stickyAction = raw.stickyAction?.takeUnless { it.isRemoved() },
                dynamicActions = raw.dynamicActions.filterNot { it.isRemoved() },
                hiddenActions = raw.hiddenActions.filterNot { it.isRemoved() },
            )
            // Make newly-added known actions (e.g. the IME-switch actions, #122) show up for existing users
            // too: any Default action not already present is appended to the visible (dynamic) actions, in
            // Default order. In practice only brand-new actions are ever missing, since hiding an action
            // keeps it in the stored arrangement.
            val missing = (listOfNotNull(Default.stickyAction) + Default.dynamicActions + Default.hiddenActions)
                .filter { it !in stored }
            return if (missing.isEmpty()) stored
            else stored.copy(dynamicActions = stored.dynamicActions + missing).distinct()
        }
    }
}
