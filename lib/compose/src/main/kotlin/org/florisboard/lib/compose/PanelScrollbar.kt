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
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Breathing room between the last column of content and the bar (issue #317). */
private val ScrollbarGap = 4.dp

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
): Modifier = drawScrollbar(accent, width, metrics)
    // The order is the whole trick: the draw node sits *outside* the padding, so it still measures the
    // full width and paints the bar against the panel's real edge, while the padding narrows only the
    // content. Stacked the other way round the content would keep its width and the last column would
    // go on running underneath the bar, which is what it did.
    .padding(end = width + ScrollbarGap)

private fun Modifier.drawScrollbar(
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

/**
 * Grid geometry measured in **rows**, which is what a grid with section headers actually has.
 *
 * The plain item model — `rows = items / columns`, `row = index / columns` — holds only while every
 * item is one cell wide. A full-span header is one item that occupies a whole row, so it makes the
 * row count too small and the row of everything after it too low. Worse, taking the pitch from the
 * first two visible offsets picks up the *header's* height whenever a header is on screen: the
 * content then measures as a fraction of its real height and the thumb inflates to fill the track,
 * only to shrink again once the header scrolls away. That is the sticker panel's scrollbar growing
 * at the top and stranding a short thumb up there at the bottom (#308), and the clipboard, which has
 * had headers all along, has been doing it just as quietly.
 *
 * [LazyGridItemInfo.row] already accounts for spans, so it is the honest unit. Only the rows below
 * the fold have to be estimated, and only their count — headers there are counted as cells, which
 * costs a fraction of a row each and never moves the thumb visibly.
 */
private fun gridMetrics(state: LazyGridState): ScrollbarMetrics? {
    val visible = state.layoutInfo.visibleItemsInfo
    val first = visible.firstOrNull() ?: return null
    val last = visible.last()
    return rowMetrics(
        totalItems = state.layoutInfo.totalItemsCount,
        // Cells sharing the topmost row give the column count of the current layout, which an
        // adaptive grid only knows once it has measured itself.
        columns = visible.count { it.row == first.row },
        firstRow = first.row,
        firstOffset = state.firstVisibleItemScrollOffset,
        lastRow = last.row,
        lastIndex = last.index,
        pitch = rowPitchOf(visible.map { it.offset.y }, visible.maxOfOrNull { it.size.height } ?: 0),
    )
}

/**
 * The bar's geometry from row positions that are already known, plus an estimate of what is below.
 *
 * Only the rows past the last laid-out item have to be guessed, and only their count: headers down
 * there are counted as cells, which costs a fraction of a row each and never moves the thumb enough
 * to see. Everything above is exact, so the thumb reaches the bottom when the content does.
 */
internal fun rowMetrics(
    totalItems: Int,
    columns: Int,
    firstRow: Int,
    firstOffset: Int,
    lastRow: Int,
    lastIndex: Int,
    pitch: Float,
): ScrollbarMetrics? {
    if (totalItems <= 0 || pitch <= 0f) return null
    val cols = columns.coerceAtLeast(1)
    val itemsBelow = (totalItems - 1 - lastIndex).coerceAtLeast(0)
    val rowsBelow = (itemsBelow + cols - 1) / cols
    val totalRows = lastRow + 1 + rowsBelow
    return ScrollbarMetrics(
        contentHeight = totalRows * pitch,
        scrolled = firstRow * pitch + firstOffset,
    )
}

/**
 * The distance between two consecutive rows of cells, given the tops of the rows on screen.
 *
 * The **most common** gap, not the first one: a header sits closer to the row beneath it than two
 * rows of cells sit to each other, so the first gap is the wrong answer exactly when a header is on
 * screen — and taking it there is what made the thumb balloon at the top of a sectioned grid. With a
 * single row visible there is nothing to measure and [tallest] stands in.
 */
internal fun rowPitchOf(rowTops: List<Int>, tallest: Int): Float {
    val tops = rowTops.distinct().sorted()
    if (tops.size < 2) return tallest.toFloat()
    return tops.zipWithNext { a, b -> b - a }
        .groupingBy { it }.eachCount()
        .entries
        // Ties go to the larger gap: with one header row and one cell row the two gaps are equally
        // common, and the cell pitch is the one that describes the content.
        .maxWithOrNull(compareBy({ it.value }, { it.key }))
        ?.key?.toFloat() ?: 0f
}

fun Modifier.panelScrollbar(state: LazyGridState, accent: Color, width: Dp = 5.dp): Modifier =
    panelScrollbar(accent, width) { gridMetrics(state) }

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
