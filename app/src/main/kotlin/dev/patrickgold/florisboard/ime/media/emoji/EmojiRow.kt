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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
 * What goes into the recent-emoji row, as arithmetic that can be checked without a phone (issue #340).
 */
object EmojiRowContent {

    /**
     * How many emoji cells fit into [availableDp] when each is [cellDp] wide and the trailing button
     * that opens the emoji panel takes one cell of its own.
     *
     * This is the whole of "fill the row, don't count it": nowhere is there a number saying nine or
     * twelve, so a narrow phone, a folded cover screen and a tablet each get what they have room for.
     * Never returns less than one — a row that decided to show nothing would be a row that should not
     * have been drawn at all, and that call belongs to [emojiRowVisible], not here.
     */
    fun cellCount(availableDp: Float, cellDp: Float): Int {
        if (cellDp <= 0f) return 1
        val total = (availableDp / cellDp).toInt()
        return (total - 1).coerceAtLeast(1)
    }

    /**
     * The emojis to show, at most [cells] of them: pinned first, then the recently used.
     *
     * Pinned first is what makes the plain recent order enough. Someone who wants a handful of emojis
     * *always* has already said so by pinning them, and those then sit on the left where the thumb is;
     * the rest is the history's own order, which across the ten or so visible places is what a frecency
     * score would mostly produce anyway.
     *
     * Duplicates are dropped rather than assumed away: pinning moves an emoji out of the recents, so
     * the two lists should already be disjoint — but a strip showing the same face twice for a history
     * written by an older version is a worse outcome than one call to distinctBy.
     */
    fun pick(pinned: List<Emoji>, recent: List<Emoji>, cells: Int): List<Emoji> {
        if (cells <= 0) return emptyList()
        return (pinned.asSequence() + recent.asSequence())
            .distinctBy { it.value }
            .take(cells)
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
 * A single row of recently used emojis between the Smartbar and the keyboard (issue #340), with a
 * button at its end that opens the full emoji panel.
 *
 * **The order is frozen while the row is on screen.** The content is read once per input session and
 * then left alone, even though tapping an emoji does update the history underneath. Re-sorting live
 * would move the emoji that was just tapped to the front — under the finger — so a second tap would
 * insert a different one. The emoji panel made the same call for the same reason (`remember`, with the
 * comment "prevents rapid emoji changes for the user"). Freshness is not lost: opening the emoji panel
 * unmounts this whole layout, so coming back re-reads, and so does moving to another field.
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

    // One read per input session — see the note on freezing above.
    val history = remember(activeEditorInfo) { prefs.emoji.historyData.get() }

    val rowHeight = FlorisImeSizing.smartbarHeight
    SnyggRow(
        elementName = FlorisImeUi.SmartbarSharedActionsRow.elementName,
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cells = EmojiRowContent.cellCount(maxWidth.value, rowHeight.value)
            val emojis = remember(history, cells) {
                EmojiRowContent.pick(history.pinned, history.recent, cells)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                emojis.forEach { emoji ->
                    Box(modifier = Modifier.size(rowHeight)) {
                        EmojiKey(
                            emojiSet = EmojiSet(listOf(emoji)),
                            emojiCompatInstance = emojiCompatInstance,
                            preferredSkinTone = preferredSkinTone,
                            // No long-press popup here: it opens above its cell, and this row is the
                            // topmost line of the keyboard, so it would open against the window edge.
                            // Pinning and removing stay in the emoji panel, where they have room.
                            isPinned = false,
                            isRecent = false,
                            onEmojiInput = { tapped ->
                                keyboardManager.inputEventDispatcher.sendDownUp(tapped)
                                scope.launch {
                                    EmojiHistoryHelper.markEmojiUsed(
                                        prefs, PrivateSession.isActive(context), tapped,
                                    )
                                }
                            },
                            onHistoryAction = { },
                        )
                    }
                }
                // Whatever the cell arithmetic left over, so the button below sits flush right.
                Box(modifier = Modifier.weight(1f))
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
                        imageVector = Icons.Default.MoreHoriz,
                        contentDescription = stringResource(R.string.emoji__row__open_panel),
                    )
                }
            }
        }
    }
}
