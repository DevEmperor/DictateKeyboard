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

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the invariant the GIF grid stands on: the list it renders never holds the same id twice
 * (issue #307).
 *
 * This is not a style rule. `LazyVerticalStaggeredGrid` keys its items by [GifItem.id], and a repeated
 * key throws out of the measure pass — the keyboard process goes down, mid-sentence, in whatever app
 * the user was typing in. KLIPY hands out repeats on its own, so the only place the invariant can be
 * established is where the list is built.
 */
class AppendUnseenGifsTest {

    private fun gif(id: String) = GifItem(
        id = id,
        title = id,
        previewUrl = "https://example.invalid/$id-preview.webp",
        fullGifUrl = "https://example.invalid/$id.gif",
        width = 200,
        height = 150,
    )

    private fun ids(items: List<GifItem>) = items.map { it.id }

    @Test
    fun `a clean page is passed through unchanged`() {
        val page = listOf(gif("a"), gif("b"), gif("c"))
        assertEquals(listOf("a", "b", "c"), ids(appendUnseenGifs(emptyList(), page)))
    }

    /**
     * The crash as reported: it happened on opening the panel, so on the very first page, before any
     * scrolling could have appended a second one.
     */
    @Test
    fun `a duplicate inside the first page is dropped`() {
        val page = listOf(gif("love-you-1224"), gif("b"), gif("love-you-1224"), gif("c"))
        assertEquals(listOf("love-you-1224", "b", "c"), ids(appendUnseenGifs(emptyList(), page)))
    }

    @Test
    fun `a duplicate inside a later page is dropped too`() {
        val first = appendUnseenGifs(emptyList(), listOf(gif("a")))
        val second = appendUnseenGifs(first, listOf(gif("b"), gif("b"), gif("c")))
        assertEquals(listOf("a", "b", "c"), ids(second))
    }

    /** Trending re-ranks between requests, so page two can repeat what page one already showed. */
    @Test
    fun `items already on screen are not appended again`() {
        val first = appendUnseenGifs(emptyList(), listOf(gif("a"), gif("b")))
        val second = appendUnseenGifs(first, listOf(gif("b"), gif("c"), gif("a")))
        assertEquals(listOf("a", "b", "c"), ids(second))
    }

    /**
     * The order the user sees must not shuffle when a page arrives: the grid is scrolled, and the
     * items already laid out have to stay where they are.
     */
    @Test
    fun `the existing order is kept and new items go to the end`() {
        val first = appendUnseenGifs(emptyList(), listOf(gif("a"), gif("b"), gif("c")))
        val second = appendUnseenGifs(first, listOf(gif("d"), gif("b"), gif("e")))
        assertEquals(listOf("a", "b", "c", "d", "e"), ids(second))
    }

    /** A page that turns out to be entirely repeats leaves the list untouched rather than growing it. */
    @Test
    fun `a page of nothing but repeats adds nothing`() {
        val first = appendUnseenGifs(emptyList(), listOf(gif("a"), gif("b")))
        assertEquals(first, appendUnseenGifs(first, listOf(gif("b"), gif("a"))))
    }

    /**
     * A blank slug is not filtered out upstream — [KlipyGifProvider] only requires the field to be
     * present. Two of them would collide on the empty-string key exactly like any other repeat.
     */
    @Test
    fun `blank ids collide like any other id`() {
        val merged = appendUnseenGifs(emptyList(), listOf(gif(""), gif("a"), gif("")))
        assertEquals(listOf("", "a"), ids(merged))
    }

    @Test
    fun `an empty page is a no-op`() {
        val first = appendUnseenGifs(emptyList(), listOf(gif("a")))
        assertEquals(first, appendUnseenGifs(first, emptyList()))
        assertEquals(emptyList(), appendUnseenGifs(emptyList(), emptyList()))
    }
}
