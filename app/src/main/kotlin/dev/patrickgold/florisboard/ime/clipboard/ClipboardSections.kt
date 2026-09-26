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

import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem

/** The groups the clipboard panel shows, each with a tab in the panel's header (issue #395). */
enum class ClipboardSection {
    PINNED,
    RECENT,
    OTHER,
}

/** One non-empty group as the panel's grid lays it out: its header at [headerIndex], its [items] right after. */
data class ClipboardSectionSpan(
    val section: ClipboardSection,
    val headerIndex: Int,
    val items: List<ClipboardItem>,
)

/**
 * The panel's groups in the order it shows them, empty ones left out.
 *
 * Pins go last unless [pinnedOnTop]. They only ever grow, and on top every new one pushed what was
 * just copied — the thing most likely to be pasted next — another row further down (issue #395).
 */
fun ClipboardHistory.sections(pinnedOnTop: Boolean): List<ClipboardSectionSpan> {
    val ordered = if (pinnedOnTop) {
        listOf(
            ClipboardSection.PINNED to pinned,
            ClipboardSection.RECENT to recent,
            ClipboardSection.OTHER to other,
        )
    } else {
        listOf(
            ClipboardSection.RECENT to recent,
            ClipboardSection.OTHER to other,
            ClipboardSection.PINNED to pinned,
        )
    }
    var headerIndex = 0
    return ordered.filter { (_, items) -> items.isNotEmpty() }.map { (section, items) ->
        ClipboardSectionSpan(section, headerIndex, items).also { headerIndex += 1 + items.size }
    }
}

/**
 * The section whose tab is lit while the grid's first visible item is [firstVisibleIndex]: the last one
 * whose header has reached the top.
 *
 * Once the grid is scrolled to its end ([atEnd]) the last section wins instead. A short last group never
 * reaches the top, so without this its tab could not light up at all — and with the pins at the bottom,
 * that group is the pins.
 */
fun List<ClipboardSectionSpan>.sectionAt(firstVisibleIndex: Int, atEnd: Boolean): ClipboardSection? {
    if (isEmpty()) return null
    if (atEnd) return last().section
    return (lastOrNull { it.headerIndex <= firstVisibleIndex } ?: first()).section
}

/**
 * When the oldest recent item stops being recent, or null when nothing is.
 *
 * [ClipboardHistory] splits recent from other against the clock at the moment it is built, and the
 * manager only builds one when the database changes. Left alone, a clip copied hours ago stayed under
 * "Recent" until the next copy; the panel rebuilds its copy at this instant instead.
 */
fun ClipboardHistory.nextRecentExpiryMs(): Long? {
    return recent.minOfOrNull { it.creationTimestampMs }?.plus(ClipboardHistory.RECENT_TIMESPAN_MS)
}
