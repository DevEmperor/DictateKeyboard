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
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType

/**
 * Finding a clip by what it says (issue #333).
 *
 * The panel already lets clips be filtered by kind, but a long history is a wall of text boxes that
 * all look alike, and the one worth pasting is usually recognised by a word inside it. Kept apart from
 * the panel so the rule — especially what it refuses — can be read and tested on its own.
 */
object ClipboardSearch {

    /**
     * The clips whose text carries every word of [query], most recently copied first.
     *
     * Words rather than one string, so a half-remembered phrase still finds the clip without the
     * spacing and word order having to match what was copied — which is the whole reason someone is
     * searching instead of scrolling.
     *
     * Two kinds of clip are never returned, and both refusals are deliberate:
     *
     *  - **Images and videos.** They carry no text to match, so including them would mean showing
     *    results that no query could ever have found on purpose.
     *  - **Clips marked sensitive.** These come out of password fields and the like, and the panel
     *    already refuses to display their contents. A search that matched them would report their
     *    contents by another route: type a guess, and the presence of a result answers it.
     *
     * A blank query matches nothing rather than everything — [ClipboardHistory.pinned] and
     * [ClipboardHistory.recent] are what the strip shows until something is typed, so that the space
     * holds something worth tapping rather than the whole history in miniature.
     */
    fun filter(items: List<ClipboardItem>, query: String): List<ClipboardItem> {
        val terms = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        return items
            .filter { it.type == ItemType.TEXT && !it.isSensitive }
            .filter { item ->
                val text = item.text?.lowercase() ?: return@filter false
                terms.all { text.contains(it) }
            }
            .sortedByDescending { it.creationTimestampMs }
    }

    /**
     * What the strip shows before anything is typed: pinned clips first, then whatever was copied in
     * the last few minutes, with the same two refusals applied.
     */
    fun fallback(history: ClipboardHistory): List<ClipboardItem> =
        (history.pinned + history.recent).filter { it.type == ItemType.TEXT && !it.isSensitive }
}
