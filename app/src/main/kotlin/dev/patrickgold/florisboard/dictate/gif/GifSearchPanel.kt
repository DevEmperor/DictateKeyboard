/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.gif

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.jetpref.datastore.model.collectAsState as collectPrefAsState
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * The GIF search bar shown in the Smartbar's slot while the user is typing a query (the keyboard below
 * does the typing; keystrokes are folded into
 * [dev.patrickgold.florisboard.ime.keyboard.KeyboardManager.gifSearchQuery]). Unlike emoji search, GIFs
 * are too small for an inline results strip — so this bar only captures the query; pressing Enter (or the
 * search button) opens a full large-thumbnail results page (see [GifPanel]). While the query is empty the
 * bar offers the recent search terms as chips (tap to search, long-press to delete).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GifSearchPanel(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val query = keyboardManager.gifSearchQuery.collectAsState().value ?: return
    val history by prefs.gif.history.collectPrefAsState()
    var confirmDeleteTerm by remember { mutableStateOf<String?>(null) }

    SnyggRow(
        elementName = FlorisImeUi.MediaBottomRow.elementName,
        modifier = modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.smartbarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SnyggIconButton(
            elementName = FlorisImeUi.MediaBottomRowButton.elementName,
            onClick = { keyboardManager.closeGifSearch() },
            modifier = Modifier.size(FlorisImeSizing.smartbarHeight),
        ) {
            Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(28.dp))
        }
        if (query.isBlank() && history.recentSearches.isNotEmpty()) {
            // Nothing typed yet: offer recent searches as chips.
            LazyRow(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(history.recentSearches, key = { it }) { term ->
                    Box {
                        SnyggText(
                            elementName = FlorisImeUi.SmartbarCandidateWordText.elementName,
                            text = term,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color(0x22808080))
                                .combinedClickable(
                                    onClick = { keyboardManager.submitGifSearch(term) },
                                    onLongClick = { confirmDeleteTerm = term },
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        DropdownMenu(
                            expanded = confirmDeleteTerm == term,
                            onDismissRequest = { confirmDeleteTerm = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringRes(R.string.action__delete)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    scope.launch { GifHistoryHelper.removeSearch(prefs, term) }
                                    confirmDeleteTerm = null
                                },
                            )
                        }
                    }
                }
            }
        } else {
            SnyggText(
                elementName = FlorisImeUi.SmartbarCandidateWordText.elementName,
                text = query.ifBlank { stringRes(R.string.gif__search_placeholder) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
        }
        SnyggIconButton(
            elementName = FlorisImeUi.MediaBottomRowButton.elementName,
            onClick = { keyboardManager.submitGifSearch(query) },
            enabled = query.isNotBlank(),
            modifier = Modifier.size(FlorisImeSizing.smartbarHeight),
        ) {
            Icon(imageVector = Icons.Default.Search, contentDescription = null, modifier = Modifier.size(28.dp))
        }
    }
}
