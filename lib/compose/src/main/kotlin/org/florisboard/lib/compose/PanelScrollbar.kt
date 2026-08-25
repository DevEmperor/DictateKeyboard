/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.florisboard.lib.compose

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A scrollbar that is actually visible inside the keyboard.
 *
 * The shared [florisScrollbar] tints itself with `MaterialTheme.colorScheme.onSurface`, and there is
 * no `MaterialTheme` anywhere in the IME composition — the keyboard is themed entirely through Snygg.
 * Compose therefore falls back to its built-in *light* palette, where `onSurface` is nearly black, and
 * near-black at 28 % alpha on a dark keyboard is indistinguishable from nothing at all. Every panel
 * that used it has been shipping an invisible scrollbar.
 *
 * So the colour is a parameter here and callers pass the keyboard's accent. Two more differences from
 * the shared one, both learned from the transcription-history panel, which is the one users could see:
 *
 *  - **No fading.** The shared bar disappears 1.85 s after the panel opens and 950 ms after each
 *    scroll. In a grid of several hundred stickers the bar is most wanted exactly when the finger is
 *    not moving — while deciding where to go next.
 *  - **A track behind the thumb.** A lone thumb on a busy grid is hard to find; a faint full-height
 *    track says at a glance how far down the content reaches.
 *
 * The thumb is sized from *rows*, not items. Dividing by the item count treats an N-column grid as if
 * every cell were its own row, which makes the thumb N times too short and its travel N times too
 * long — the defect the shared grid overload still has.
 */
private fun Modifier.panelScrollbar(
    accent: Color,
    width: Dp,
    metrics: () -> ScrollbarMetrics?,
): Modifier = drawWithContent {
    drawContent()
    val m = metrics() ?: return@drawWithContent
    if (m.totalRows <= 0 || m.visibleRows <= 0 || m.visibleRows >= m.totalRows) return@drawWithContent
    val barWidth = width.toPx()
    val viewport = size.height
    val thumbHeight = (viewport * m.visibleRows / m.totalRows).coerceAtLeast(barWidth * 5f)
    val maxScroll = (m.totalRows - m.visibleRows).toFloat()
    val progress = if (maxScroll <= 0f) 0f else (m.firstVisibleRow / maxScroll).coerceIn(0f, 1f)
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

/** What the bar needs to know, in rows rather than items. */
internal data class ScrollbarMetrics(
    val totalRows: Int,
    val visibleRows: Int,
    val firstVisibleRow: Int,
)

/**
 * Rows from items, given how many cells sit side by side.
 *
 * [columns] is derived from the laid-out items rather than from the grid's configuration, because an
 * adaptive grid only knows its column count once it has measured itself.
 */
internal fun rowMetrics(total: Int, visible: Int, first: Int, columns: Int): ScrollbarMetrics? {
    if (total <= 0 || visible <= 0) return null
    val cols = columns.coerceAtLeast(1)
    fun rows(items: Int) = (items + cols - 1) / cols
    return ScrollbarMetrics(
        totalRows = rows(total),
        visibleRows = rows(visible),
        firstVisibleRow = first / cols,
    )
}

fun Modifier.panelScrollbar(state: LazyListState, accent: Color, width: Dp = 5.dp): Modifier =
    panelScrollbar(accent, width) {
        val info = state.layoutInfo
        rowMetrics(
            total = info.totalItemsCount,
            visible = info.visibleItemsInfo.size,
            first = state.firstVisibleItemIndex,
            columns = 1,
        )
    }

fun Modifier.panelScrollbar(state: LazyGridState, accent: Color, width: Dp = 5.dp): Modifier =
    panelScrollbar(accent, width) {
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        // Cells sharing the topmost row give the column count of the current layout.
        val columns = visible.firstOrNull()?.let { top ->
            visible.count { it.offset.y == top.offset.y }
        } ?: 1
        rowMetrics(
            total = info.totalItemsCount,
            visible = visible.size,
            first = state.firstVisibleItemIndex,
            columns = columns,
        )
    }

fun Modifier.panelScrollbar(state: LazyStaggeredGridState, accent: Color, width: Dp = 5.dp): Modifier =
    panelScrollbar(accent, width) {
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        // A staggered grid has no rows to speak of — items of different heights sit in lanes. Counting
        // the lanes and treating each as a column is close enough for a scrollbar, and it is the only
        // reading available: the layout info exposes no row index.
        val lanes = visible.map { it.lane }.distinct().size.coerceAtLeast(1)
        rowMetrics(
            total = info.totalItemsCount,
            visible = visible.size,
            first = state.firstVisibleItemIndex,
            columns = lanes,
        )
    }
