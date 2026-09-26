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
            // Four bands (issue #402): the dictation loop, then the other panels that insert something,
            // then the shape of the keyboard, then the single text actions. What this replaced was not a
            // considered order but the order the features shipped in — which is how the transcription
            // history, the second half of what this keyboard is for, ended up at position 32 of 34.
            //
            // Only the first five or six entries are ever on screen: QuickActionsRow shows
            // (width / height) - 1 buttons, which is six on a 411dp phone and five on a 360dp one, so the
            // band boundary that matters sits after SETTINGS. Everything below that is the
            // overflow grid, one tap away — and that grid, not hiddenActions, is where the rest belongs:
            // a hidden action is invisible until someone opens the actions editor, which is the opposite
            // of what a default is for.
            dynamicActions = listOf(
                // --- The dictation loop -------------------------------------------------------------
                // The prompt panel opener. The live prompt is no longer a Smartbar button – it lives as a
                // chip inside the prompt panel/row – so only the opener remains here. It costs a fresh
                // install nothing either way: the always-on prompt ROW is the default layout, and
                // filterDictateHidden drops this action from bar and grid alike while that row is up, so
                // the slot only exists for the users who went back to the PANEL layout.
                QuickAction.InsertKey(TextKeyData.DICTATE_PROMPTS),
                // The transcription history (issue #140). Despite what the key code is called, this has
                // not re-inserted the last dictation since #140 — it opens the browsable panel: read back,
                // re-insert, re-transcribe. First of the visible actions, because it is the one surface
                // that answers "what did I just dictate?", and because the moment to ask that is the
                // moment the actions row is on screen: the bar shows the actions when the field is idle
                // and during a selection, not mid-word.
                QuickAction.InsertKey(TextKeyData.DICTATE_REINSERT),
                QuickAction.InsertKey(TextKeyData.IME_UI_MODE_CLIPBOARD),
                // On-device translation (issue #424). Third, ahead of GIF: it is the start of an errand
                // like the two before it, nobody looks for it in a keyboard until they see it there, and
                // Gboard keeps its translate button in the visible bar — which is where people switching
                // from it will look.
                QuickAction.InsertKey(TextKeyData.TRANSLATE),
                // GIF search panel (KLIPY). In the bar by default rather than waiting to be dragged there:
                // it is one of the few actions people go looking for, and unlike the split or language
                // actions it is never greyed out — without an API key the panel itself says so.
                QuickAction.InsertKey(TextKeyData.IME_UI_MODE_GIF),
                // Scan text (issue #390): camera → recognised lines → the one you tap. Fifth, ahead of
                // Settings, for discovery rather than frequency: it is the feature nobody guesses a keyboard
                // has, so it only gets found where it is seen — and five is what a 360 dp phone shows.
                QuickAction.InsertKey(TextKeyData.IME_UI_MODE_SCAN),
                // A keyboard with this much behind it — the provider, the key, the prompts, the languages
                // — needs a door of its own; without one the way in is hunting for the app icon in the
                // launcher, which is a long walk from the field the user is standing in. Sixth, after Scan:
                // somebody who wants the settings goes looking for them, and on a 360 dp phone this is the
                // first tile of the overflow grid, one tap further.
                QuickAction.InsertKey(TextKeyData.SETTINGS),
                // First of the overflow grid rather than in the bar (#424 moved it out). The actions row is
                // on screen when the field is idle; while typing, the strip shows suggestions — so Undo was
                // rarely there at the moment it was wanted, and one tap into the grid costs little more.
                QuickAction.InsertKey(TextKeyData.UNDO),
                // Which provider hears the dictation (issue #431). The dictation loop's own, so at the head of
                // the grid — but in the grid and not the bar, because most people have one provider set up
                // and would be giving a slot to a list of one.
                QuickAction.InsertKey(TextKeyData.DICTATE_SWITCH_PROVIDER),
                // --- Everything else that inserts something -----------------------------------------
                // The text editing panel (issue #386) — cursor pad, select, clipboard. First tile of the
                // overflow grid rather than a slot in the bar: it is the umbrella over fourteen of the
                // actions below, so whoever needs any of them finds all of them here in one tap.
                QuickAction.InsertKey(TextKeyData.IME_UI_MODE_EDITING),
                // Emoji has the utility key next to the space bar by default, so it does not need a
                // Smartbar slot — but it is the first grid tile for the users who gave that key to the
                // language switch instead.
                QuickAction.InsertKey(TextKeyData.IME_UI_MODE_MEDIA),
                // Local sticker panel (issue #280): the folder the user picked, no network involved.
                QuickAction.InsertKey(TextKeyData.IME_UI_MODE_STICKER),
                // --- The shape of the keyboard ------------------------------------------------------
                // Fold the digit row away and back without a trip through settings (issue #333), and the
                // number pad (issue #388) that had no way of being asked for at all. Together, because
                // they are the two digit answers.
                QuickAction.InsertKey(TextKeyData.TOGGLE_NUMBER_ROW),
                QuickAction.InsertKey(TextKeyData.VIEW_NUMERIC_ADVANCED),
                QuickAction.InsertKey(TextKeyData.TOGGLE_COMPACT_LAYOUT),
                QuickAction.InsertKey(TextKeyData.TOGGLE_FLOATING_WINDOW),
                QuickAction.InsertKey(TextKeyData.TOGGLE_RESIZE_MODE),
                // Split keyboard for two thumbs on a wide window (issue #362). Next to one-handed
                // because they are the same kind of answer: greyed out below 600dp, where two halves
                // would be two rows of slivers.
                QuickAction.InsertKey(TextKeyData.SPLIT_LAYOUT),
                QuickAction.InsertKey(TextKeyData.TOGGLE_INCOGNITO_MODE),
                // --- The text actions the editing panel already holds -------------------------------
                // Every one of these is a tile in the editing panel above, so they are here for the
                // people who want a specific one as its own button, not for everyone.
                QuickAction.InsertKey(TextKeyData.REDO),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_SELECT_ALL),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_CUT),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_COPY),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_PASTE),
                QuickAction.InsertKey(TextKeyData.FORWARD_DELETE),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_CLEAR_PRIMARY_CLIP),
                // Jump to the very start or end of the field (issue #335). The key codes and their
                // handler have been here all along, but only a swipe gesture could reach them — next to
                // the arrows, because that is what they are: the same journey, in one step.
                QuickAction.InsertKey(TextKeyData.MOVE_START_OF_PAGE),
                QuickAction.InsertKey(TextKeyData.MOVE_END_OF_PAGE),
                QuickAction.InsertKey(TextKeyData.ARROW_LEFT),
                QuickAction.InsertKey(TextKeyData.ARROW_RIGHT),
                QuickAction.InsertKey(TextKeyData.ARROW_UP),
                QuickAction.InsertKey(TextKeyData.ARROW_DOWN),
                // --- The way out --------------------------------------------------------------------
                QuickAction.InsertKey(TextKeyData.LANGUAGE_SWITCH),
                // IME-switch actions (issue #122): one-tap return to the previously used keyboard, plus the
                // system keyboard picker. Useful when pairing Dictate with another IME (e.g. a Japanese
                // Kana–Kanji keyboard).
                QuickAction.InsertKey(TextKeyData.SYSTEM_PREV_INPUT_METHOD),
                QuickAction.InsertKey(TextKeyData.SYSTEM_INPUT_METHOD_PICKER),
                QuickAction.InsertKey(TextKeyData.IME_HIDE_UI),
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
