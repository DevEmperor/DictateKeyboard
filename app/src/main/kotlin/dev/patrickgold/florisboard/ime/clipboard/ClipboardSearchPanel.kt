/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.clipboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.PanelHeaderButton
import dev.patrickgold.florisboard.ime.smartbar.KeyboardSearchBar
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import org.florisboard.lib.compose.rippleClickable
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggText

/** Wide enough for a recognisable phrase, narrow enough that several fit on screen at once. */
private val ResultWidth = 148.dp

/**
 * Finding a clip by what it says (issue #333), shown in the Smartbar's slot while the search is open
 * so the keyboard below can type the query — see
 * [dev.patrickgold.florisboard.ime.keyboard.KeyboardManager.clipboardSearchQuery].
 *
 * The clipboard panel replaces the keyboard, which is what makes this shape necessary rather than a
 * search field inside the panel: there would be nothing to type into it with. Emoji, stickers and GIFs
 * solved the same problem the same way, and this reads like them on purpose.
 *
 * Built on the sticker search rather than the GIF one, because like sticker names the clips are
 * already in memory: the list narrows on every keystroke and there is nothing to submit. One row,
 * scrolled sideways, sitting directly on the search bar — two rows of text boxes would take a third of
 * the screen away from the app being written in.
 *
 * With nothing typed the strip already holds every searchable clip, pinned ones first — so typing
 * narrows a list that is useful before the first keystroke, rather than filling an empty one.
 */
@Composable
fun ClipboardSearchPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val clipboardManager by context.clipboardManager()
    val inputFeedbackController = LocalInputFeedbackController.current

    val query = keyboardManager.clipboardSearchQuery.collectAsState().value ?: return
    val history by clipboardManager.historyFlow.collectAsState()

    // Read once when the search opens, like the sticker and emoji searches do: pasting a clip moves it
    // to the front of the history, and a live fallback list would reorder itself under the finger that
    // just tapped it.
    val fallback = remember { ClipboardSearch.fallback(history) }
    val hits = remember(history.all, query) { ClipboardSearch.filter(history.all, query) }
    val shown = if (query.isBlank()) fallback else hits

    val listState = rememberLazyListState()
    // Every letter is a new result set, so it starts at the front rather than wherever the previous one
    // happened to be scrolled to.
    LaunchedEffect(shown) {
        if (shown.isNotEmpty()) listState.scrollToItem(0)
    }

    SnyggColumn(
        elementName = FlorisImeUi.SmartbarCandidatesRow.elementName,
        modifier = modifier.fillMaxWidth(),
    ) {
        // The cells bring 4dp of margin with them from the stylesheet, which is the whole gap at the top
        // and at the left, while the bottom one is that margin plus the search bar's own inset. Adding
        // the same amount on those two sides evens the three out — and stays even if a theme sets no
        // margin at all, since it is added to both alike.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.smartbarHeight * 1.6f)
                .padding(start = 4.dp, top = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                // Nothing typed and nothing pinned or recent: an empty strip says that better than a
                // sentence explaining it would.
                shown.isEmpty() && query.isBlank() -> Unit
                shown.isEmpty() -> SnyggText(
                    elementName = FlorisImeUi.ClipboardHeaderText.elementName,
                    text = stringRes(R.string.clipboard__search_no_results),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                else -> LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(shown, key = { it.id }) { item ->
                        // The type has to be declared, and not only for tidiness: the stylesheet puts the
                        // inner padding on `clipboard-item[type=text]`, so a cell that does not say what
                        // it holds gets the background and the shape but no breathing room, and its text
                        // sits in the corner. The margin between cells comes from the same rule, which is
                        // why this row adds no spacing of its own.
                        val attributes = remember(item) {
                            mapOf("type" to item.type.toString().lowercase())
                        }
                        // A row hands its items an unbounded width, so each cell has to name its own.
                        SnyggBox(
                            elementName = FlorisImeUi.ClipboardItem.elementName,
                            attributes = attributes,
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier
                                .width(ResultWidth)
                                .fillMaxHeight(),
                            clickAndSemanticsModifier = Modifier.rippleClickable {
                                inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                                clipboardManager.pasteItem(item)
                                // Back to the keyboard rather than to the panel: the clip is in, and
                                // what follows a paste is usually more writing.
                                keyboardManager.closeClipboardSearch(returnToPanel = false)
                            },
                        ) {
                            SnyggText(
                                modifier = Modifier.fillMaxWidth(),
                                text = item.stringRepresentation(),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        KeyboardSearchBar(
            query = query,
            placeholder = stringRes(R.string.clipboard__search_placeholder),
            onClear = { keyboardManager.clearClipboardSearch() },
            leading = {
                PanelHeaderButton(
                    onClick = { keyboardManager.closeClipboardSearch() },
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
