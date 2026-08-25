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
 * Geometry is measured in pixels rather than in items — see [ScrollbarMetrics] for why that is what
 * makes it glide instead of stutter.
 */
private fun Modifier.panelScrollbar(
    accent: Color,
    width: Dp,
    metrics: () -> ScrollbarMetrics?,
): Modifier = drawWithContent {
    drawContent()
    val m = metrics() ?: return@drawWithContent
    val viewport = size.height
    if (m.contentHeight <= viewport || viewport <= 0f) return@drawWithContent
    val barWidth = width.toPx()
    val thumbHeight = (viewport * viewport / m.contentHeight).coerceAtLeast(barWidth * 5f)
    val travel = viewport - thumbHeight
    val progress = (m.scrolled / (m.contentHeight - viewport)).coerceIn(0f, 1f)
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
        topLeft = Offset(x, travel * progress),
        size = Size(barWidth, thumbHeight),
        cornerRadius = radius,
    )
}

/**
 * The bar's geometry in pixels, not in items.
 *
 * Counting items is what made the first version stutter. Progress from `firstVisibleItemIndex` alone
 * is integer division: the thumb stands still until a whole row has passed and then jumps, which
 * reads as lag even though the scroll itself is smooth. And sizing the thumb from the *count* of
 * visible items makes it grow and shrink by a row as rows edge into view. Measuring in pixels — how
 * far the content has scrolled, how tall it is in total — gives a thumb that keeps its size and
 * glides.
 */
internal data class ScrollbarMetrics(
    val contentHeight: Float,
    val scrolled: Float,
)

/**
 * Row pitch from the laid-out items: the distance between the tops of two consecutive rows, which
 * includes the spacing between them. Falls back to an item's own height when only one row is visible.
 */
private fun pitchOf(offsets: List<Int>, sizes: List<Int>): Float {
    val distinct = offsets.distinct().sorted()
    if (distinct.size >= 2) return (distinct[1] - distinct[0]).toFloat()
    return sizes.maxOrNull()?.toFloat() ?: 0f
}

private fun metricsFrom(
    totalItems: Int,
    columns: Int,
    firstIndex: Int,
    firstOffset: Int,
    pitch: Float,
): ScrollbarMetrics? {
    if (totalItems <= 0 || pitch <= 0f) return null
    val cols = columns.coerceAtLeast(1)
    val totalRows = (totalItems + cols - 1) / cols
    val scrolled = (firstIndex / cols) * pitch + firstOffset
    return ScrollbarMetrics(contentHeight = totalRows * pitch, scrolled = scrolled)
}

fun Modifier.panelScrollbar(state: LazyListState, accent: Color, width: Dp = 5.dp): Modifier =
    panelScrollbar(accent, width) {
        val visible = state.layoutInfo.visibleItemsInfo
        metricsFrom(
            totalItems = state.layoutInfo.totalItemsCount,
            columns = 1,
            firstIndex = state.firstVisibleItemIndex,
            firstOffset = state.firstVisibleItemScrollOffset,
            pitch = pitchOf(visible.map { it.offset }, visible.map { it.size }),
        )
    }

fun Modifier.panelScrollbar(state: LazyGridState, accent: Color, width: Dp = 5.dp): Modifier =
    panelScrollbar(accent, width) {
        val visible = state.layoutInfo.visibleItemsInfo
        // Cells sharing the topmost row give the column count of the current layout, which an
        // adaptive grid only knows once it has measured itself.
        val columns = visible.firstOrNull()?.let { top -> visible.count { it.offset.y == top.offset.y } } ?: 1
        metricsFrom(
            totalItems = state.layoutInfo.totalItemsCount,
            columns = columns,
            firstIndex = state.firstVisibleItemIndex,
            firstOffset = state.firstVisibleItemScrollOffset,
            pitch = pitchOf(visible.map { it.offset.y }, visible.map { it.size.height }),
        )
    }

fun Modifier.panelScrollbar(state: LazyStaggeredGridState, accent: Color, width: Dp = 5.dp): Modifier =
    panelScrollbar(accent, width) {
        val visible = state.layoutInfo.visibleItemsInfo
        // A staggered grid has no rows: items of different heights sit in lanes. The average visible
        // height stands in for a row pitch, which is an estimate — but a scrollbar is an estimate.
        val lanes = visible.map { it.lane }.distinct().size.coerceAtLeast(1)
        val averageHeight = if (visible.isEmpty()) 0f else {
            visible.sumOf { it.size.height }.toFloat() / visible.size
        }
        metricsFrom(
            totalItems = state.layoutInfo.totalItemsCount,
            columns = lanes,
            firstIndex = state.firstVisibleItemIndex,
            firstOffset = state.firstVisibleItemScrollOffset,
            pitch = averageHeight,
        )
    }
