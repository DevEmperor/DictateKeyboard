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

package dev.patrickgold.florisboard.ime.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.translate.TranslateBar
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.dictate.gif.GifSearchPanel
import dev.patrickgold.florisboard.dictate.sticker.StickerSearchPanel
import dev.patrickgold.florisboard.ime.clipboard.ClipboardSearchPanel
import dev.patrickgold.florisboard.ime.media.emoji.EmojiRow
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSearchPanel
import dev.patrickgold.florisboard.ime.media.emoji.emojiRowVisible
import dev.patrickgold.florisboard.ime.smartbar.IncognitoDisplayMode
import dev.patrickgold.florisboard.ime.smartbar.InlineSuggestionsStyleCache
import dev.patrickgold.florisboard.ime.smartbar.Smartbar
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionsOverflowPanel
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboardLayout
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import org.florisboard.lib.snygg.ui.SnyggIcon

@Composable
fun TextInputLayout(
    modifier: Modifier = Modifier,
    // Applied to the key area alone. The legacy SWIPE mode (#125) hangs its swipe-back gesture here
    // rather than around the whole layout: it has to win over the keys, so it runs ahead of them on the
    // Initial pass, and everything above the keys scrolls sideways — rewording prompts, candidates,
    // autofill chips — and would never get a drag of its own if that reached up here (#290).
    keyboardModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()

    val prefs by FlorisPreferenceStore

    val state by keyboardManager.activeState.collectAsState()
    val evaluator by keyboardManager.activeEvaluator.collectAsState()
    val emojiSearchActive by keyboardManager.emojiSearchQuery.collectAsState()
    val gifSearchActive by keyboardManager.gifSearchQuery.collectAsState()
    val stickerSearchActive by keyboardManager.stickerSearchQuery.collectAsState()
    val clipboardSearchActive by keyboardManager.clipboardSearchQuery.collectAsState()
    val translateActive by keyboardManager.translateQuery.collectAsState()

    InlineSuggestionsStyleCache()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight(),
    ) {
        // While one of the keyboard's own fields is open — a search (issues #110, #274, #317, #333) or the
        // translate bar (#424) — it sits on top, where the rewording prompt row otherwise is, and the
        // Smartbar stays below it, as in Gboard: its strip then suggests words for the field being typed
        // in, and its mic dictates into it. The keys below type into the field. Each field sizes itself,
        // so the keyboard grows for as long as it is open. Only one is ever open: every one of them, and
        // every panel opener, closes the rest first.
        val fieldOpen = translateActive != null || emojiSearchActive != null || gifSearchActive != null ||
            stickerSearchActive != null || clipboardSearchActive != null
        if (fieldOpen) {
            when {
                translateActive != null -> TranslateBar()
                emojiSearchActive != null -> EmojiSearchPanel()
                gifSearchActive != null -> GifSearchPanel()
                stickerSearchActive != null -> StickerSearchPanel()
                clipboardSearchActive != null -> ClipboardSearchPanel()
            }
        }
        // Called in the same place whether a field is open or not, so opening one does not rebuild it.
        Smartbar(showPromptRow = !fieldOpen)
        // The recent-emoji row (#340) sits between the Smartbar and the keys, exactly where a number row
        // would. Not while a field is open — its taps are character keys and would land in the field,
        // and the field has already made the keyboard taller — and not over the actions overflow, which
        // replaces the keys with a grid of its own.
        if (!fieldOpen && !state.isActionsOverflowVisible && emojiRowVisible()) {
            EmojiRow()
        }
        if (state.isActionsOverflowVisible) {
            QuickActionsOverflowPanel()
        } else {
            Box(modifier = keyboardModifier) {
                val incognitoDisplayMode by prefs.keyboard.incognitoDisplayMode.collectAsState()
                val showIncognitoIcon = evaluator.state.isIncognitoMode &&
                    incognitoDisplayMode == IncognitoDisplayMode.DISPLAY_BEHIND_KEYBOARD
                if (showIncognitoIcon) {
                    SnyggIcon(
                        FlorisImeUi.IncognitoModeIndicator.elementName,
                        modifier = Modifier
                            .matchParentSize()
                            .align(Alignment.Center),
                        painter = painterResource(R.drawable.ic_incognito),
                    )
                }
                TextKeyboardLayout(evaluator = evaluator)
            }
        }
    }
}
