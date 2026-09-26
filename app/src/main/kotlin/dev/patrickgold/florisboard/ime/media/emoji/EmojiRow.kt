/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.media.emoji

import android.app.KeyguardManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.PrivateSession
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.launch
import org.florisboard.lib.android.systemService
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow

/**
 * The row's glyph size: smaller than the palette's, because this row is paid for out of the height of
 * every keyboard it sits on, and a glyph that fills it leaves room for seven emojis where nine fit
 * (issue #394). Not smaller than this — past it the faces stop being recognisable at a glance.
 */
private val EmojiRowFontSize = 20.sp

/** What a cell adds to its glyph's width, so neighbours don't touch. */
private val EmojiRowCellPadding = 14.dp

/**
 * What goes into the recent-emoji row, as a rule that can be checked without a phone (issue #340).
 */
object EmojiRowContent {

    /**
     * The emojis to show: pinned first, then the recently used — all of them, the row scrolls
     * (issue #394). It used to stop at what fit on one screen, which left most of a 90-entry history
     * unreachable from the row. How much is *visible* is the screen's business; how much is *there* is
     * the history's.
     *
     * Pinned first is what makes the plain recent order enough. Someone who wants a handful of emojis
     * *always* has already said so by pinning them, and those then sit on the left where the thumb is;
     * the rest is the history's own order, which across the visible places is what a frecency score would
     * mostly produce anyway.
     *
     * Duplicates are dropped rather than assumed away: pinning moves an emoji out of the recents, so
     * the two lists should already be disjoint — but a strip showing the same face twice for a history
     * written by an older version is a worse outcome than one call to distinctBy.
     */
    fun pick(pinned: List<Emoji>, recent: List<Emoji>): List<Emoji> {
        return (pinned.asSequence() + recent.asSequence())
            .distinctBy { it.value }
            .toList()
    }
}

/**
 * Whether the recent-emoji row should be on screen right now.
 *
 * Deliberately one function with two callers: the row itself and [FlorisImeSizing.panelUiHeight],
 * which has to reserve exactly the height the row will take. If those two ever disagreed, the keyboard
 * would visibly jump by one row the moment a panel opened over it.
 */
@Composable
fun emojiRowVisible(): Boolean {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val rowEnabled by prefs.emoji.rowEnabled.collectAsState()
    // Not left to `enabledIf` in the settings screen: a greyed-out switch is not an off switch, and a
    // child preference that keeps running because its parent merely *looks* disabled is issue #297.
    val historyEnabled by prefs.emoji.historyEnabled.collectAsState()
    if (!rowEnabled || !historyEnabled) return false

    // The emoji panel already refuses to show its history on a locked device; a strip would hand out
    // the same list on the lock screen without even being opened.
    val keyguardManager = remember { context.systemService(KeyguardManager::class) }
    if (keyguardManager.isDeviceLocked || keyguardManager.isKeyguardLocked) return false

    val editorInstance by context.editorInstance()
    val editorInfo by editorInstance.activeInfoFlow.collectAsState()
    val isPasswordField = when (editorInfo.inputAttributes.variation) {
        InputAttributes.Variation.PASSWORD,
        InputAttributes.Variation.VISIBLE_PASSWORD,
        InputAttributes.Variation.WEB_PASSWORD,
        -> true
        else -> false
    }
    if (isPasswordField) return false

    // An empty history would leave a row containing nothing but the button that opens the panel — worse
    // than no row, and it would make the keyboard taller for someone who has never used an emoji.
    val history by prefs.emoji.historyData.collectAsState()
    return history.pinned.isNotEmpty() || history.recent.isNotEmpty()
}

/**
 * A single row of recently used emojis between the Smartbar and the keyboard (issue #340), scrolled
 * sideways, with a button at its end that opens the full emoji panel.
 *
 * **The order is frozen while the row is on screen.** The content is read once per input session and
 * then left alone, even though tapping an emoji does update the history underneath. Re-sorting live
 * would move the emoji that was just tapped to the front — under the finger — so a second tap would
 * insert a different one. The emoji panel made the same call for the same reason (`remember`, with the
 * comment "prevents rapid emoji changes for the user"). Freshness is not lost: opening the emoji panel
 * unmounts this whole layout, so coming back re-reads, and so does moving to another field.
 *
 * The one exception is the long-press popup (issue #394): pinning or removing an emoji there is a change
 * the user asked for, and a row that went on showing the removed emoji would look as if nothing had
 * happened. Removal is also the opposite of the tap problem — the list shrinks away from the finger
 * instead of reshuffling under it.
 */
@Composable
fun EmojiRow(modifier: Modifier = Modifier) {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val editorInstance by context.editorInstance()
    val scope = rememberCoroutineScope()

    val activeEditorInfo by editorInstance.activeInfoFlow.collectAsState()
    val emojiCompatInstance by FlorisEmojiCompat.getAsFlow(activeEditorInfo.emojiCompatReplaceAll)
        .collectAsState()
    val preferredSkinTone by prefs.emoji.preferredSkinTone.collectAsState()

    // One read per input session — see the note on freezing above — and one more after each action
    // taken in the long-press popup.
    var historyVersion by remember { mutableIntStateOf(0) }
    val history = remember(activeEditorInfo, historyVersion) { prefs.emoji.historyData.get() }
    val emojis = remember(history) { EmojiRowContent.pick(history.pinned, history.recent) }
    val pinnedValues = remember(history) { history.pinned.mapTo(HashSet()) { it.value } }

    // A new field starts the row at its beginning, where the pinned ones are.
    val listState = rememberLazyListState()
    LaunchedEffect(activeEditorInfo) {
        listState.scrollToItem(0)
    }

    val rowHeight = FlorisImeSizing.smartbarHeight
    // Follows the glyph, so a larger system font widens the cells instead of clipping the emojis.
    val cellWidth = with(LocalDensity.current) { EmojiRowFontSize.toDp() } + EmojiRowCellPadding
    SnyggRow(
        elementName = FlorisImeUi.SmartbarSharedActionsRow.elementName,
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentPadding = PaddingValues(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(emojis, key = { it.value }) { emoji ->
                val isPinned = emoji.value in pinnedValues
                EmojiKey(
                    emojiSet = EmojiSet(listOf(emoji)),
                    emojiCompatInstance = emojiCompatInstance,
                    preferredSkinTone = preferredSkinTone,
                    // Real membership, so the long-press offers the palette's own pin/unpin and remove.
                    // Its popup opens above the cell, over the Smartbar — the row sits below it.
                    isPinned = isPinned,
                    isRecent = !isPinned,
                    onEmojiInput = { tapped ->
                        keyboardManager.inputEventDispatcher.sendDownUp(tapped)
                        scope.launch {
                            EmojiHistoryHelper.markEmojiUsed(
                                prefs, PrivateSession.isActive(context), tapped,
                            )
                        }
                    },
                    onHistoryAction = { historyVersion++ },
                    // The full row height to aim at, but no longer a full square of width.
                    modifier = Modifier
                        .width(cellWidth)
                        .fillMaxHeight(),
                    fontSize = EmojiRowFontSize,
                )
            }
        }
        // Outside the scroll, so it never moves. A face rather than "⋯": the Smartbar's own overflow
        // button right above is "⋯", and the two sat stacked looking like one control drawn twice. The
        // face is what the emoji key and the Smartbar's emoji action show for the same panel.
        SnyggIconButton(
            elementName = FlorisImeUi.SmartbarActionKey.elementName,
            modifier = Modifier
                .width(rowHeight)
                .fillMaxHeight(),
            onClick = {
                keyboardManager.inputEventDispatcher.sendDownUp(TextKeyData.IME_UI_MODE_MEDIA)
            },
        ) {
            SnyggIcon(
                imageVector = Icons.Default.SentimentSatisfiedAlt,
                contentDescription = stringResource(R.string.emoji__row__open_panel),
            )
        }
    }
}
