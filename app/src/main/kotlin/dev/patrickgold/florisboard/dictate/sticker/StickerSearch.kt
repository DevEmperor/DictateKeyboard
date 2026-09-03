/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.sticker

/**
 * Finding a sticker by its file name (issue #317).
 *
 * A collection is a folder, and a folder's only searchable text is what the files are called — there
 * are no tags and nothing has been looked at. That sounds thin until you count: the folder this was
 * asked for holds several hundred files, and scrolling to one of them is the slowest thing the panel
 * asks anyone to do.
 *
 * Kept apart from the panel so the rule can be read and tested on its own.
 */
object StickerSearch {

    /**
     * The stickers whose name carries every word of [query], best guesses first.
     *
     * Words rather than one string, so "cat laugh" finds `cat_laugh` without the typist having to
     * know which separator the file happens to use — and equally without the order having to match.
     * Names that *begin* with the first word come first: someone typing "cat" is far more often after
     * `cat_laugh` than after `angry_cat`, and the sort is stable, so within each group the folder's
     * own alphabetical order survives.
     *
     * A blank query matches nothing rather than everything. The results sit above the keyboard in a
     * strip, and filling it with the whole collection before a single letter is typed would say the
     * search had done something when it has not.
     */
    fun filter(items: List<StickerItem>, query: String): List<StickerItem> {
        val terms = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val hits = items.filter { item ->
            val name = item.name.lowercase()
            terms.all { name.contains(it) }
        }
        val first = terms.first()
        return hits.sortedBy { if (it.name.lowercase().startsWith(first)) 0 else 1 }
    }
}
