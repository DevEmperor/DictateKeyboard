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

/**
 * The tab order the user has chosen for their packs (issue #317).
 *
 * The rule that matters most is the one about packs the user has *not* placed: they have to keep the
 * folder's own alphabetical order and stay behind the placed ones. Otherwise adding a pack would
 * shuffle the row every time the folder is read, which is the kind of thing that looks like data loss.
 */
class StickerPackSettingsTest {

    private fun category(id: String, name: String) =
        StickerCategory(id = id, name = name, items = emptyList())

    // As the scanner hands them over: the loose files first, then the folders alphabetically.
    private val scanned = listOf(
        category(StickerCategory.ROOT_ID, ""),
        category("id-animals", "Animals"),
        category("id-cats", "Cats"),
        category("id-memes", "Memes"),
    )

    private fun names(order: List<String>) =
        StickerPackSettings.ordered(scanned, order).map { it.name }

    @Test
    fun `with nothing chosen the folder's own order stands`() {
        assertEquals(listOf("", "Animals", "Cats", "Memes"), names(emptyList()))
    }

    @Test
    fun `packs sit where they were put`() {
        assertEquals(listOf("", "Memes", "Cats", "Animals"), names(listOf("Memes", "Cats", "Animals")))
    }

    /**
     * The combined tab is not a pack — it is every pack at once — so it stays at the front whatever the
     * stored order happens to say, including an order written before a pack was renamed.
     */
    @Test
    fun `the combined tab stays first even if the order tries to move it`() {
        assertEquals(listOf("", "Memes", "Animals", "Cats"), names(listOf("Memes", "", "Animals")))
    }

    @Test
    fun `a pack nobody has placed follows the placed ones, alphabetically`() {
        assertEquals(listOf("", "Memes", "Animals", "Cats"), names(listOf("Memes", "Animals")))
    }

    /** A name left over from a deleted or renamed pack simply matches nothing. */
    @Test
    fun `an order naming a pack that is gone changes nothing about the rest`() {
        assertEquals(listOf("", "Cats", "Animals", "Memes"), names(listOf("Gone", "Cats")))
    }

    @Test
    fun `settings survive a round trip`() {
        val settings = StickerPackSettings(
            order = listOf("Memes", "Cats"),
            icons = mapOf("Memes" to "doc-42"),
        )
        val roundTripped = StickerPackSettings.Serializer.deserialize(
            StickerPackSettings.Serializer.serialize(settings)
        )
        assertEquals(settings, roundTripped)
    }

    /**
     * Both fields are optional, so an installation that has never touched a pack — and one written by a
     * version that only knew half of this — reads back rather than throwing its settings away.
     */
    @Test
    fun `a blob missing a field reads the field as empty`() {
        assertEquals(
            StickerPackSettings(order = listOf("Memes")),
            StickerPackSettings.Serializer.deserialize("""{"order":["Memes"]}"""),
        )
        assertEquals(StickerPackSettings.Empty, StickerPackSettings.Serializer.deserialize("{}"))
    }

    @Test
    fun `an unreadable blob falls back to empty instead of throwing`() {
        assertEquals(StickerPackSettings.Empty, StickerPackSettings.Serializer.deserialize("not json"))
    }
}
