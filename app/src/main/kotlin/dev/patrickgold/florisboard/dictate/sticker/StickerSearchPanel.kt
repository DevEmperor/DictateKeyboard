/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.sticker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.editor.EditorInstance
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.PanelHeaderButton
import dev.patrickgold.florisboard.ime.smartbar.KeyboardSearchBar
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.jetpref.datastore.model.collectAsState as collectPrefAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.android.showShortToast
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * Finding a sticker by name (issue #317), shown in the Smartbar's slot while the search is open so the
 * keyboard below can type the query — see
 * [dev.patrickgold.florisboard.ime.keyboard.KeyboardManager.stickerSearchQuery].
 *
 * Built on the emoji search rather than the GIF one, and the difference is where the answer lives.
 * KLIPY has to be asked over the network, so a GIF query is submitted with Enter and answered on a
 * page of its own; file names are already in memory, so these results narrow on every keystroke and
 * there is nothing to submit. That is what "instant filter" was asked for, and it costs nothing.
 *
 * One row, scrolled sideways, and it sits directly on top of the search bar: two rows of thumbnails
 * take a third of the screen away from the app being typed into, and a sticker is recognised at a
 * glance rather than read.
 *
 * With nothing typed the strip shows the favourites and recents, so the space holds something worth
 * tapping instead of standing empty — the same courtesy the emoji search does.
 */
@Composable
fun StickerSearchPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val inputFeedbackController = LocalInputFeedbackController.current

    val query = keyboardManager.stickerSearchQuery.collectAsState().value ?: return
    val accent by prefs.theme.accentColor.collectPrefAsState()
    val folderUri by prefs.sticker.folderUri.collectPrefAsState()
    val thumbnailSize by prefs.sticker.thumbnailSize.collectPrefAsState()
    val confirmBeforeInsert by prefs.sticker.confirmBeforeInsert.collectPrefAsState()

    // Read from the index file, not from the folder: the panel that does the scanning is not composed
    // while the keyboard is up, and walking the tree again would be a call into another process for
    // every keystroke. The search can only be opened from that panel, which has just written the file.
    var index by remember { mutableStateOf<StickerIndex?>(null) }
    LaunchedEffect(folderUri) {
        index = withContext(Dispatchers.IO) { StickerScanner.loadCached(context, folderUri) }
    }
    val treeUri = remember(folderUri) { folderUri.toUri() }
    var preparingDocId by remember { mutableStateOf<String?>(null) }
    var armedDocId by remember { mutableStateOf<String?>(null) }

    val items = index?.allItems.orEmpty()
    val byId = remember(items) { items.associateBy { it.docId } }
    // Read once, when the search opens: inserting marks a sticker used, and a live list would reorder
    // itself under the finger that just tapped it.
    val fallback = remember(byId) {
        val history = prefs.sticker.historyData.get()
        (history.pinned + history.recent).mapNotNull { byId[it] }
    }
    val hits = remember(items, query) { StickerSearch.filter(items, query) }
    val shown = if (query.isBlank()) fallback else hits

    val listState = rememberLazyListState()
    // Every letter is a new result set, so it starts at the front rather than wherever the previous one
    // happened to be scrolled to.
    LaunchedEffect(shown) {
        if (shown.isNotEmpty()) listState.scrollToItem(0)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) armedDocId = null
        }
    }

    fun insert(item: StickerItem) {
        val tree = folderUri.takeIf { it.isNotBlank() }?.toUri() ?: return
        scope.launch {
            val outcome = StickerManager.insert(context, tree, item) { preparing ->
                preparingDocId = if (preparing) item.docId else null
            }
            if (outcome == EditorInstance.MediaCommitResult.COPIED_TO_CLIPBOARD) {
                context.showLongToast(StickerManager.refusalReason(context, item))
            } else if (outcome == EditorInstance.MediaCommitResult.FAILED) {
                context.showShortToast(R.string.sticker__insert_failed)
            }
            // Back to the keyboard rather than to the panel: the sticker is sent, and what usually
            // follows a sticker is a word.
            keyboardManager.closeStickerSearch(returnToPanel = false)
        }
    }

    SnyggColumn(
        elementName = FlorisImeUi.SmartbarCandidatesRow.elementName,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(thumbnailSize.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                // Nothing to say yet: the index is still being read, or the query has no answer and
                // there is no history to fall back on either.
                index == null -> Unit
                shown.isEmpty() && query.isBlank() -> Unit
                shown.isEmpty() -> SnyggText(
                    elementName = FlorisImeUi.MediaEmojiSubheader.elementName,
                    text = stringRes(R.string.sticker__search_no_results),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                else -> LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(shown, key = { it.docId }) { item ->
                        // A row hands its items an unbounded width, so each cell has to name its own
                        // — the thumbnail inside fills what it is given.
                        Box(modifier = Modifier.width(thumbnailSize.dp)) {
                            StickerCell(
                                item = item,
                                treeUri = treeUri,
                                armed = armedDocId == item.docId,
                                preparing = preparingDocId == item.docId,
                                accent = accent,
                                scrolling = { listState.isScrollInProgress },
                                onClick = {
                                    inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                                    // The same two-tap rule the panel follows. A misfire out of a
                                    // search result is exactly as unwanted as one out of the grid, so
                                    // the setting that guards one has to guard the other.
                                    if (!confirmBeforeInsert || armedDocId == item.docId) {
                                        armedDocId = null
                                        insert(item)
                                    } else {
                                        armedDocId = item.docId
                                    }
                                },
                                onLongClick = { },
                            )
                        }
                    }
                }
            }
        }
        KeyboardSearchBar(
            query = query,
            placeholder = stringRes(R.string.sticker__search_placeholder),
            onClear = { keyboardManager.clearStickerSearch() },
            leading = {
                PanelHeaderButton(
                    onClick = { keyboardManager.closeStickerSearch() },
                    modifier = Modifier.size(FlorisImeSizing.smartbarHeight),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringRes(R.string.action__back),
                        modifier = Modifier.size(FlorisImeSizing.mediaHeaderIconSize),
                    )
                }
            },
        )
    }
}
