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

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.emoji2.text.EmojiCompat
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.PanelHeaderButton
import dev.patrickgold.florisboard.ime.smartbar.KeyboardSearchBar
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.subtypeManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery

/**
 * The in-keyboard emoji search panel (issues #110, #274). Shown in place of the Smartbar while a search
 * is active (see [dev.patrickgold.florisboard.ime.keyboard.KeyboardManager.emojiSearchQuery]); the
 * user's own keyboard layout below it types the query, which is intercepted in the input pipeline.
 *
 * Results appear in one row *above* the search bar, scrolled sideways — the shape the sticker and GIF
 * searches use, so the three read alike and none of them takes a third of the screen from the app being
 * typed into. They share [EmojiKey] with the palette, so a result can still be long-pressed for its
 * skin tones. Tapping inserts straight into the editor (bypassing the query interception) and leaves
 * the search open.
 */
@Composable
fun EmojiSearchPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val editorInstance by context.editorInstance()
    val subtypeManager by context.subtypeManager()
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()

    val query = keyboardManager.emojiSearchQuery.collectAsState().value ?: return
    val preferredSkinTone by prefs.emoji.preferredSkinTone.collectAsState()

    val activeEditorInfo by editorInstance.activeInfoFlow.collectAsState()
    val emojiCompatInstance by FlorisEmojiCompat
        .getAsFlow(activeEditorInfo.emojiCompatReplaceAll).collectAsState()

    // Searched in the active subtype's language, with English behind it, so a Hungarian layout answers
    // to "csók" and to "kiss" alike (issue #274).
    val locale = subtypeManager.activeSubtype.primaryLocale
    val systemFontPaint = remember(Typeface.DEFAULT) {
        Paint().apply { typeface = Typeface.DEFAULT }
    }
    val metadataVersion = activeEditorInfo.emojiCompatMetadataVersion
    // Same support test the palette uses: prefer the EmojiCompat match and only fall back to the system
    // font. Using hasGlyph() alone (with the default typeface) rejects most emojis, leaving no results.
    val isSupported: (String) -> Boolean = { value ->
        emojiCompatInstance?.getEmojiMatch(value, metadataVersion) == EmojiCompat.EMOJI_SUPPORTED ||
            systemFontPaint.hasGlyph(value)
    }
    // Built once per language and font state — not per keystroke, which is what the old matcher did
    // (an EmojiCompat lookup plus a native hasGlyph() call over ~3700 emojis for every character).
    var index by remember { mutableStateOf<EmojiSearchIndex?>(null) }
    LaunchedEffect(locale, emojiCompatInstance, metadataVersion) {
        index = withContext(Dispatchers.Default) {
            EmojiSearchIndex.build(
                data = EmojiData.get(context, EmojiData.RootPath),
                annotations = EmojiAnnotations.get(context, EmojiAnnotations.assetLanguage(locale)),
                fallbackAnnotations = EmojiAnnotations.get(context, EmojiAnnotations.FallbackLanguage),
                isSupported = isSupported,
            )
        }
    }
    // A null value means "still working" and keeps the no-results text from flashing up in between.
    val results by produceState<List<EmojiSet>?>(null, query, index) {
        val current = index
        value = if (current == null) null else withContext(Dispatchers.Default) { current.search(query) }
    }

    // Until something is typed the grid shows what the palette's "recently used" tab shows, so the
    // space is filled with something worth tapping instead of standing empty. Read once when the
    // search opens, like the palette does: tapping an emoji marks it used, and a live list would
    // reorder itself under the user's finger.
    val historyEnabled by prefs.emoji.historyEnabled.collectAsState()
    val recentlyUsed = remember(historyEnabled) {
        if (!historyEnabled) {
            emptyList()
        } else {
            val history = prefs.emoji.historyData.get()
            (history.pinned + history.recent).map { EmojiSet(listOf(it)) }
        }
    }

    val shown = if (query.isBlank()) recentlyUsed else results

    // Every new letter is a new result set, so start it at the top instead of wherever the previous
    // one had been scrolled to.
    val listState = rememberLazyListState()
    LaunchedEffect(shown) {
        if (!shown.isNullOrEmpty()) listState.scrollToItem(0)
    }

    SnyggColumn(
        elementName = FlorisImeUi.SmartbarCandidatesRow.elementName,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.smartbarHeight),
            contentAlignment = Alignment.Center,
        ) {
            val current = shown
            when {
                // Still computing, or nothing typed and no history to offer.
                current == null || (query.isBlank() && current.isEmpty()) -> Unit
                current.isEmpty() -> {
                    val style = rememberSnyggThemeQuery(FlorisImeUi.SmartbarCandidatesRow.elementName)
                    Text(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        text = stringRes(R.string.emoji__search__no_results),
                        color = style.foreground(),
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(current, key = { it.emojis.first().value }) { emojiSet ->
                        // A row hands its items an unbounded width, so the cell names its own — the
                        // same width the palette's grid gives each emoji.
                        Box(modifier = Modifier.width(EmojiBaseWidth)) {
                            EmojiKey(
                                emojiSet = emojiSet,
                                emojiCompatInstance = emojiCompatInstance,
                                preferredSkinTone = preferredSkinTone,
                                isPinned = false,
                                isRecent = false,
                                onEmojiInput = { emoji ->
                                    // No feedback call here: [EmojiKey] already ticks on the confirmed
                                    // tap before it hands the emoji over, and adding one here played the
                                    // sound twice for a single tap.
                                    // Commit straight to the editor: routing through the dispatcher
                                    // would be swallowed by the active search-query interception.
                                    editorInstance.commitText(emoji.value)
                                    scope.launch { EmojiHistoryHelper.markEmojiUsed(prefs, emoji) }
                                },
                                onHistoryAction = { },
                            )
                        }
                    }
                }
            }
        }
        KeyboardSearchBar(
            query = query,
            placeholder = stringRes(R.string.emoji__search__hint),
            onClear = { keyboardManager.clearEmojiSearch() },
            leading = {
                // Back, not a second ✕: the ✕ in the field empties the query, and two crosses next to
                // each other look like the same button drawn twice.
                PanelHeaderButton(
                    onClick = { keyboardManager.closeEmojiSearch() },
                    modifier = Modifier.size(FlorisImeSizing.smartbarHeight),
                ) {
                    SnyggIcon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringRes(R.string.action__back),
                        modifier = Modifier.size(FlorisImeSizing.mediaHeaderIconSize),
                    )
                }
            },
        )
    }
}
