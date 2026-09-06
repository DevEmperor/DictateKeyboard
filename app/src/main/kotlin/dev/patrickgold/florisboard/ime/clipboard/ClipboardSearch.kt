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
     * A blank query returns nothing from *here* — [fallback] is what the strip shows until something is
     * typed, and it already holds everything this could match.
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
     * What the strip shows before anything is typed: **every** searchable clip, pinned ones first and
     * the rest newest first, with the same two refusals applied.
     *
     * Deliberately the whole list rather than the panel's "recent" group, which means the last few
     * minutes and is usually one clip. A search that starts empty and fills up as you type reads as a
     * search that found nothing; starting from everything and narrowing is what a filter is, and it
     * means the strip is useful before the first keystroke — often the clip is simply there.
     */
    fun fallback(history: ClipboardHistory): List<ClipboardItem> =
        (history.pinned + history.unpinned.sortedByDescending { it.creationTimestampMs })
            .filter { it.type == ItemType.TEXT && !it.isSensitive }
}
