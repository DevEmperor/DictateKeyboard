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

import android.graphics.drawable.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.DrawableImage
import coil3.compose.AsyncImage
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.ui.MediaAction
import dev.patrickgold.florisboard.dictate.ui.MediaActionOverlay
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.editor.EditorInstance
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.jetpref.datastore.model.collectAsState as collectPrefAsState
import kotlinx.coroutines.launch
import org.florisboard.lib.android.showShortToast
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.compose.panelScrollbar
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * The GIF panel, its own [ImeUiMode.GIF] next to the typing keyboard (opened via the GIF QuickAction).
 * Two views:
 *  - **Home** (no submitted query): recently used GIFs + trending, plus a search bar entry.
 *  - **Results** (after the user typed a query and pressed Enter in [GifSearchPanel]): a large-thumbnail
 *    grid of search results, with a back arrow to the home view.
 * GIFs are shown large (unlike emojis they'd be unreadable small). Tapping a GIF inserts it; long-pressing
 * a recently used GIF removes it from history. Requires a free KLIPY API key (Settings → Emojis & GIFs);
 * without one an onboarding empty-state is shown. Attribution per KLIPY's terms.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GifPanel(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val prefs by FlorisPreferenceStore
    val accent by prefs.theme.accentColor.collectPrefAsState()
    val apiKey by prefs.gif.klipyApiKey.collectPrefAsState()
    val history by prefs.gif.history.collectPrefAsState()
    val submittedQuery by keyboardManager.gifSearchSubmit.collectAsState()
    val scope = rememberCoroutineScope()
    val inputFeedbackController = LocalInputFeedbackController.current
    val hasKey = apiKey.isNotBlank()

    var gifs by remember { mutableStateOf<List<GifItem>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var hasNext by remember { mutableStateOf(false) }
    var initialLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    // The long-pressed GIF, if any. Its own state rather than an id, because the sheet shows it.
    var menuItem by remember { mutableStateOf<GifItem?>(null) }
    val gridState = rememberLazyStaggeredGridState()
    // The recents row scrolls independently of the grid, and its GIFs animate independently too.
    val recentsState = rememberLazyListState()

    suspend fun loadPage(p: Int): GifPage =
        if (submittedQuery.isNullOrBlank()) GifManager.provider.trending(p)
        else GifManager.provider.search(submittedQuery!!, p)

    // First page (and reset) whenever the key or the submitted query changes.
    LaunchedEffect(hasKey, submittedQuery) {
        if (!hasKey) return@LaunchedEffect
        initialLoading = true; loadError = false; gifs = emptyList(); page = 1; hasNext = false
        GifManager.ensureCustomerId()
        // Remember the term so it shows up as a recent-search chip next time.
        submittedQuery?.takeIf { it.isNotBlank() }?.let { GifHistoryHelper.addSearch(prefs, it) }
        try {
            val first = loadPage(1)
            gifs = appendUnseenGifs(emptyList(), first.items)
            hasNext = first.hasNext
        } catch (e: Exception) {
            loadError = true
        }
        initialLoading = false
    }

    // Infinite scroll: fetch the next page as the user nears the end of the grid.
    LaunchedEffect(gridState, submittedQuery, hasKey) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (hasKey && hasNext && !loadingMore && gifs.isNotEmpty() && lastVisible >= gifs.size - 6) {
                    loadingMore = true
                    try {
                        val next = loadPage(page + 1)
                        gifs = appendUnseenGifs(gifs, next.items)
                        page += 1
                        hasNext = next.hasNext
                    } catch (e: Exception) {
                        hasNext = false
                    }
                    loadingMore = false
                }
            }
    }

    fun insert(item: GifItem) {
        scope.launch {
            when (GifManager.insertGif(context, item)) {
                EditorInstance.MediaCommitResult.COMMITTED ->
                    keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
                EditorInstance.MediaCommitResult.COPIED_TO_CLIPBOARD -> {
                    context.showShortToast(R.string.gif__copied_to_clipboard)
                    keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
                }
                EditorInstance.MediaCommitResult.FAILED ->
                    context.showShortToast(R.string.gif__insert_failed)
            }
        }
    }

    val inResults = !submittedQuery.isNullOrBlank()

    SnyggColumn(
        elementName = FlorisImeUi.Media.elementName,
        modifier = modifier
            .fillMaxWidth()
            // Taller than a normal keyboard so more (and larger) GIFs are visible at once.
            .height(FlorisImeSizing.imeUiHeight() + FlorisImeSizing.keyboardRowBaseHeight * 2),
    ) {
        // Header: back, tappable search field, settings.
        SnyggRow(
            elementName = FlorisImeUi.MediaBottomRow.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.smartbarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnyggIconButton(
                elementName = FlorisImeUi.MediaBottomRowButton.elementName,
                onClick = {
                    if (inResults) {
                        keyboardManager.gifSearchSubmit.value = null // back to the home view
                    } else {
                        keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
                    }
                },
                modifier = Modifier.size(FlorisImeSizing.smartbarHeight),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x22808080))
                    .clickable(enabled = hasKey) { keyboardManager.activateGifSearch() }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 8.dp),
                    )
                    SnyggText(
                        elementName = FlorisImeUi.SmartbarCandidateWordText.elementName,
                        text = submittedQuery?.takeIf { it.isNotBlank() }
                            ?: stringRes(R.string.gif__search_placeholder),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SnyggIconButton(
                elementName = FlorisImeUi.MediaBottomRowButton.elementName,
                onClick = { FlorisImeService.launchSettings("settings/media") },
                modifier = Modifier.size(FlorisImeSizing.smartbarHeight),
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            // Dimmed rather than covered, so the GIF being acted on stays visible behind the sheet —
            // which on a wall of moving thumbnails is the only way to see which one was long-pressed.
            val panelAlpha by animateFloatAsState(targetValue = if (menuItem != null) 0.12f else 1f)
            Box(modifier = Modifier.fillMaxSize().alpha(panelAlpha)) {
                when {
                    !hasKey -> GifSetupNeeded { FlorisImeService.launchSettings("settings/media") }
                    initialLoading -> GifCentered { CircularProgressIndicator(color = accent) }
                    loadError -> GifCentered {
                        SnyggText(FlorisImeUi.MediaEmojiSubheader.elementName, text = stringRes(R.string.gif__error))
                    }
                    else -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Home view: a row of recently used GIFs above trending. (Hidden while showing results.)
                            if (!inResults && history.recentGifs.isNotEmpty()) {
                                LazyRow(
                                    state = recentsState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(96.dp),
                                    contentPadding = PaddingValues(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    items(history.recentGifs, key = { "recent-${it.id}" }) { item ->
                                        GifThumb(
                                            item = item,
                                            fixedHeight = true,
                                            scrolling = { recentsState.isScrollInProgress },
                                            onClick = {
                                                inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                                                insert(item)
                                            },
                                            onLongClick = {
                                                inputFeedbackController.keyLongPress(TextKeyData.UNSPECIFIED)
                                                menuItem = item
                                            },
                                        )
                                    }
                                }
                            }
                            if (gifs.isEmpty()) {
                                GifCentered {
                                    SnyggText(FlorisImeUi.MediaEmojiSubheader.elementName, text = stringRes(R.string.gif__no_results))
                                }
                            } else {
                                // Staggered so GIFs keep their own aspect ratio and pack tightly (no fixed
                                // cell height leaving gaps under shorter GIFs). More pages load on scroll.
                                LazyVerticalStaggeredGrid(
                                    state = gridState,
                                    columns = StaggeredGridCells.Adaptive(minSize = 128.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .panelScrollbar(gridState, accent),
                                    contentPadding = PaddingValues(4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalItemSpacing = 4.dp,
                                ) {
                                    items(gifs, key = { it.id }) { item ->
                                        GifThumb(
                                            item = item,
                                            scrolling = { gridState.isScrollInProgress },
                                            onClick = {
                                                inputFeedbackController.keyPress(TextKeyData.UNSPECIFIED)
                                                insert(item)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (hasKey) {
                    SnyggText(
                        elementName = FlorisImeUi.SmartbarCandidateWordSecondaryText.elementName,
                        text = stringRes(R.string.gif__powered_by),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp),
                    )
                }
            }
            val sheetItem = menuItem
            if (sheetItem != null) {
                MediaActionOverlay(
                    onDismiss = { menuItem = null },
                    preview = {
                        AsyncImage(
                            model = sheetItem.previewUrl,
                            contentDescription = sheetItem.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .aspectRatio(1f),
                        )
                    },
                ) {
                    MediaAction(
                        icon = Icons.Default.Delete,
                        text = stringRes(R.string.action__delete),
                    ) {
                        scope.launch { GifHistoryHelper.removeGif(prefs, sheetItem.id) }
                        menuItem = null
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GifThumb(
    item: GifItem,
    onClick: () -> Unit,
    scrolling: () -> Boolean,
    fixedHeight: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    // Every cell here is animated, which makes this panel the heavier of the two: Coil hands over an
    // AnimatedImageDrawable and never memory-caches it, so a grid in motion is decoding new frames and
    // whole new images at the same time. Pausing for the length of the scroll gives the incoming
    // decodes the room, and costs nothing anyone can perceive — an animation cannot be read mid-fling.
    var animation by remember(item.id) { mutableStateOf<Animatable?>(null) }
    LaunchedEffect(animation) {
        val running = animation ?: return@LaunchedEffect
        snapshotFlow { scrolling() }.collect { moving ->
            if (moving) running.stop() else running.start()
        }
    }
    val ratio = if (item.width > 0 && item.height > 0) {
        (item.width.toFloat() / item.height.toFloat()).coerceIn(0.6f, 2.2f)
    } else 1f
    val base = if (fixedHeight) Modifier.fillMaxHeight() else Modifier.fillMaxWidth()
    val clickMod = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable { onClick() }
    }
    AsyncImage(
        model = item.previewUrl,
        contentDescription = item.title,
        contentScale = ContentScale.Crop,
        onSuccess = { state ->
            animation = (state.result.image as? DrawableImage)?.drawable as? Animatable
        },
        modifier = base
            .then(if (fixedHeight) Modifier.padding(horizontal = 3.dp) else Modifier)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x22808080))
            .then(clickMod),
    )
}

@Composable
private fun GifCentered(content: @Composable () -> Unit) {
    SnyggBox(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) { content() }
}

@Composable
private fun GifSetupNeeded(onSetup: () -> Unit) {
    SnyggBox(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SnyggText(FlorisImeUi.MediaEmojiSubheader.elementName, text = stringRes(R.string.gif__setup_needed))
            SnyggText(
                elementName = FlorisImeUi.SmartbarCandidateWordText.elementName,
                text = stringRes(R.string.gif__setup_needed_action),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onSetup() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
