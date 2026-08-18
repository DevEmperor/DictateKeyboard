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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the folder scan lets through and what it names things (issue #280).
 *
 * These are the decisions a user notices immediately: a sticker missing from the grid, or a whole
 * folder that looks empty. The documents provider is the unreliable half of the equation — it is free
 * to answer `application/octet-stream` for a perfectly ordinary PNG, and some do — so the fallback to
 * the file extension is pinned here rather than trusted to hold.
 */
class StickerScannerTest {

    @Test
    fun `accepts the image types a sticker collection is made of`() {
        assertEquals("image/png", StickerScanner.mimeFor("pepe.png", "image/png"))
        assertEquals("image/webp", StickerScanner.mimeFor("wave.webp", "image/webp"))
        assertEquals("image/gif", StickerScanner.mimeFor("dance.gif", "image/gif"))
        assertEquals("image/jpeg", StickerScanner.mimeFor("photo.jpg", "image/jpeg"))
    }

    @Test
    fun `falls back to the extension when the provider reports a useless type`() {
        assertEquals("image/png", StickerScanner.mimeFor("pepe.png", "application/octet-stream"))
        assertEquals("image/webp", StickerScanner.mimeFor("wave.WEBP", null))
        assertEquals("image/jpeg", StickerScanner.mimeFor("holiday.JPEG", "application/octet-stream"))
    }

    @Test
    fun `rejects what is not an image`() {
        assertNull(StickerScanner.mimeFor("tags.json", "application/json"))
        assertNull(StickerScanner.mimeFor("notes.txt", "text/plain"))
        assertNull(StickerScanner.mimeFor("clip.mp4", "video/mp4"))
        // No extension and no usable type: nothing to go on, so it stays out of the grid.
        assertNull(StickerScanner.mimeFor("README", "application/octet-stream"))
    }

    @Test
    fun `labels strip only the extension`() {
        assertEquals("pepe", StickerScanner.displayLabel("pepe.png"))
        assertEquals("very.happy", StickerScanner.displayLabel("very.happy.webp"))
        // A leading dot is the whole name, not an empty label with an extension.
        assertEquals(".hidden", StickerScanner.displayLabel(".hidden"))
        assertEquals("no-extension", StickerScanner.displayLabel("no-extension"))
    }

    @Test
    fun `an unusable row produces no item`() {
        assertNull(StickerScanner.toItem("doc1", "notes.txt", "text/plain", 0L))
        assertNull(StickerScanner.toItem("doc2", "", "image/png", 0L))
    }

    @Test
    fun `a usable row keeps the id, the label, the type and the stamp`() {
        val item = StickerScanner.toItem("tree%3Astickers%2Fpepe.png", "pepe.png", "image/png", 1234L)
        assertEquals(
            StickerItem(
                docId = "tree%3Astickers%2Fpepe.png",
                name = "pepe",
                mime = "image/png",
                lastModified = 1234L,
            ),
            item,
        )
    }

    @Test
    fun `an imported file gets a usable name whatever the sharing app sends`() {
        assertEquals("pepe.webp", StickerWriter.fileName("pepe.webp", "webp"))
        // A share often carries no display name at all; the fallback has to be stable rather than
        // unique, or the duplicate check would never match the second time the same sticker arrives.
        assertEquals("sticker.png", StickerWriter.fileName("", "png"))
        assertEquals("sticker.png", StickerWriter.fileName("   ", "png"))
        // Path separators and the characters no file system wants, replaced rather than dropped.
        assertEquals("a_b_c.gif", StickerWriter.fileName("a/b:c.gif", "gif"))
        // The extension follows the type actually written, not the one the sender claimed.
        assertEquals("photo.jpg", StickerWriter.fileName("photo.png", "jpg"))
    }

    @Test
    fun `sorting ignores case, so Apple and apple stay together`() {
        val items = listOf("banana", "Apple", "apricot", "Cherry").map {
            StickerItem(docId = it, name = it, mime = "image/png", lastModified = 0L)
        }
        assertEquals(
            listOf("Apple", "apricot", "banana", "Cherry"),
            StickerScanner.sorted(items).map { it.name },
        )
    }
}
