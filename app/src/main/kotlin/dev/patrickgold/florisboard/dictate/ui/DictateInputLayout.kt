/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * The Dictate AI prompt panel, rendered as its own [ImeUiMode.DICTATE] next to the regular typing
 * keyboard (see `ImeWindow`). Opened via the prompt-panel QuickAction (magic-wand) in the Smartbar so
 * the rewording prompts are always one tap away – independent of whether anything is selected.
 *
 * Each prompt chip runs [DictateController.applyPrompt] and returns to the typing keyboard; the
 * Smartbar then shows the rewording progress. A `requiresSelection` prompt tapped with no active
 * selection first selects the whole field (see the controller), so users can reword everything
 * without manually selecting first.
 *
 * Reuses the already-themed `media-*` Snygg elements so it inherits the active theme with no
 * stylesheet changes (dedicated `dictate-*` theming is deferred polish).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DictateInputLayout(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val prompts by DictateController.prompts.collectAsState()
    val livePromptActive by DictateController.livePromptActive.collectAsState()
    val scrollState = rememberScrollState()
    val prefs by FlorisPreferenceStore
    val accent by prefs.theme.accentColor.collectAsState() // follows the user's keyboard accent.

    SnyggColumn(
        elementName = FlorisImeUi.Media.elementName,
        modifier = modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.imeUiHeight()),
    ) {
        // Header: back to the typing keyboard + panel title.
        SnyggRow(
            elementName = FlorisImeUi.MediaBottomRow.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.smartbarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnyggIconButton(
                elementName = FlorisImeUi.MediaBottomRowButton.elementName,
                onClick = { keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT },
                modifier = Modifier.size(FlorisImeSizing.smartbarHeight),
            ) {
                SnyggIcon(imageVector = Icons.AutoMirrored.Filled.ArrowBack)
            }
            SnyggText(
                elementName = FlorisImeUi.MediaEmojiSubheader.elementName,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
                text = stringRes(R.string.dictate__panel_title),
            )
        }

        // Body: the live-prompt chip plus all saved prompts, flowing from the top-left to the right and
        // wrapping onto further lines (left-aligned, not centered). The live chip is always present, so
        // the panel is never truly empty; a hint is shown underneath only when no saved prompts exist.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
                // Persistent, theme-independent scrollbar pinned to the right edge. Uses the same
                // geometry as the shared florisScrollbar (the modifier sits inside the scroll, so
                // size.height is the content height and the offset compensates with state.value), but
                // with a hard-coded accent color – MaterialTheme is not wired up in the IME, so the
                // shared scrollbar's onSurface tint is effectively invisible here.
                .dictatePanelScrollbar(scrollState, accent)
                .padding(8.dp),
            horizontalArrangement = Arrangement.Start,
        ) {
            // In the panel the chips are roomier and sit closer together than in the compact row, so
            // they are easier to hit (larger icon + extra tap padding, tighter inter-chip spacing).
            val panelChipSpacing = Modifier.padding(2.dp)
            val panelIconSize = 22.dp
            val panelTapPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
            DictateLivePromptChip(
                modifier = panelChipSpacing,
                iconSize = panelIconSize,
                tapPadding = panelTapPadding,
                // Accent-highlight while its recording runs (tap again stops it — startLivePrompt toggles).
                highlighted = livePromptActive,
                onClick = {
                    DictateController.startLivePrompt(context)
                    // Return to the keyboard so the recording indicator + field stay visible.
                    keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
                },
            )
            prompts.forEach { prompt ->
                DictatePromptChip(
                    icon = dictatePromptIcon(prompt),
                    text = prompt.name.orEmpty(),
                    modifier = panelChipSpacing,
                    iconSize = panelIconSize,
                    tapPadding = panelTapPadding,
                    onClick = {
                        DictateController.applyPrompt(context, prompt)
                        // Return to the keyboard so the field + the Smartbar progress are visible.
                        keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
                    },
                    onLongClick = { editPromptInSettings(prompt) },
                )
            }
        }
        if (prompts.isEmpty()) {
            SnyggBox(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                SnyggText(
                    elementName = FlorisImeUi.MediaEmojiSubheader.elementName,
                    text = stringRes(R.string.dictate__panel_no_prompts),
                )
            }
        }
    }
}


/**
 * Draws a slim, persistent scrollbar at the right edge of a [verticalScroll]ed container whenever the
 * content overflows. Apply *after* `verticalScroll(state)` (i.e. inside the scroll), exactly like the
 * shared `florisScrollbar`: there `size.height` is the full content height, so the geometry subtracts
 * the scroll range to recover the viewport height and adds `state.value` to the y-offsets to keep the
 * bar pinned to the visible area while the content scrolls underneath.
 *
 * Unlike the shared helper it does not fade out and uses a hard-coded accent color instead of
 * `MaterialTheme.colorScheme.onSurface` (MaterialTheme is not provided inside the keyboard IME, so that
 * tint renders invisible against the Snygg-themed panel).
 */
/**
 * Same accent scrollbar as [dictatePanelScrollbar], for a [LazyListState]-backed list (the history panel,
 * which must stay lazy so a large history never blocks the UI thread). A lazy list has no total pixel
 * height, so the thumb is derived from the item counts — an approximation that is accurate enough for the
 * roughly uniform panel rows.
 */
internal fun Modifier.dictateLazyPanelScrollbar(
    state: LazyListState,
    accent: Color,
    width: Dp = 5.dp,
): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total == 0 || visible == 0 || visible >= total) return@drawWithContent
    val barWidth = width.toPx()
    val viewport = size.height
    val thumbHeight = (viewport * visible / total).coerceAtLeast(barWidth * 5f)
    val maxIndex = (total - visible).toFloat()
    val progress = (state.firstVisibleItemIndex / maxIndex).coerceIn(0f, 1f)
    val x = size.width - barWidth
    val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
    drawRoundRect(
        color = accent.copy(alpha = 0.12f),
        topLeft = Offset(x, 0f),
        size = Size(barWidth, viewport),
        cornerRadius = radius,
    )
    drawRoundRect(
        color = accent.copy(alpha = 0.85f),
        topLeft = Offset(x, (viewport - thumbHeight) * progress),
        size = Size(barWidth, thumbHeight),
        cornerRadius = radius,
    )
}

internal fun Modifier.dictatePanelScrollbar(
    state: ScrollState,
    accent: Color,
    width: Dp = 5.dp,
): Modifier = drawWithContent {
    drawContent()
    val scrollMax = state.maxValue.toFloat()
    if (scrollMax <= 0f) return@drawWithContent
    val scrollValue = state.value.toFloat()
    val barWidth = width.toPx()
    // size.height is the content height here (the modifier sits inside the scroll); recover the viewport.
    val viewport = size.height - scrollMax
    val thumbHeight = (viewport * (viewport / size.height)).coerceAtLeast(barWidth * 5f)
    val x = size.width - barWidth
    // +scrollValue pins the bar to the viewport (content is translated up by scrollValue).
    val trackY = scrollValue
    val thumbY = scrollValue + (viewport - thumbHeight) * (scrollValue / scrollMax)
    val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
    drawRoundRect(
        color = accent.copy(alpha = 0.12f),
        topLeft = Offset(x, trackY),
        size = Size(barWidth, viewport),
        cornerRadius = radius,
    )
    drawRoundRect(
        color = accent.copy(alpha = 0.85f),
        topLeft = Offset(x, thumbY),
        size = Size(barWidth, thumbHeight),
        cornerRadius = radius,
    )
}
