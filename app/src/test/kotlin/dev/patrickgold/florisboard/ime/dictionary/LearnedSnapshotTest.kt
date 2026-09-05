/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.dictionary

import dev.patrickgold.florisboard.ime.nlp.latin.WordLearningGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The read side of the learned store (issue #318): the structure the suggestion strip queries on every
 * keystroke.
 *
 * Worth its own tests because it is the one place in this feature where a bug is silent. A wrong
 * binary-search bound does not throw, it just quietly stops offering some of the user's words — and
 * "the keyboard sometimes forgets what I taught it" is close to unreportable.
 */
class LearnedSnapshotTest {

    private val now = 1_700_000_000L
    private val day = 86_400L

    private fun entry(word: String, count: Int, ageDays: Long = 0, promoted: Boolean = false) =
        LearnedWordEntry(
            id = 0,
            word = word,
            key = word.lowercase(),
            lang = "en",
            count = count,
            lastUsed = now - ageDays * day,
            promoted = promoted,
        )

    private fun snapshotOf(vararg entries: LearnedWordEntry) =
        LearnedSnapshot.of("en", entries.toList(), now)

    @Test
    fun `an empty store answers nothing rather than throwing`() {
        val snapshot = snapshotOf()
        assertTrue(snapshot.isEmpty)
        assertEquals(emptyList(), snapshot.startingWith("a", 0.0, 5))
        assertEquals(0.0, snapshot.scoreOfKey("a"))
    }

    @Test
    fun `a prefix finds its words and nothing else`() {
        val snapshot = snapshotOf(
            entry("klabautersteg", 3),
            entry("klaviatur", 2),
            entry("brotkasten", 4),
        )
        assertEquals(listOf("klabautersteg", "klaviatur"), snapshot.startingWith("kla", 0.0, 5).sorted())
        assertEquals(listOf("brotkasten"), snapshot.startingWith("brot", 0.0, 5))
        assertEquals(emptyList(), snapshot.startingWith("zzz", 0.0, 5))
    }

    @Test
    fun `the whole store is reachable from an empty prefix`() {
        // The boundary the binary search gets wrong first: an empty prefix must not narrow to nothing.
        val snapshot = snapshotOf(entry("aal", 1), entry("zebra", 1))
        assertEquals(2, snapshot.startingWith("", 0.0, 10).size)
    }

    @Test
    fun `the first and last words are not lost at the range edges`() {
        val snapshot = snapshotOf(entry("aaa", 1), entry("mmm", 1), entry("zzz", 1))
        assertEquals(listOf("aaa"), snapshot.startingWith("aaa", 0.0, 5))
        assertEquals(listOf("zzz"), snapshot.startingWith("zzz", 0.0, 5))
    }

    @Test
    fun `the strongest words come first and the limit is honoured`() {
        val snapshot = snapshotOf(
            entry("klaus", 1),
            entry("klabautersteg", 9),
            entry("klaviatur", 5),
        )
        assertEquals(listOf("klabautersteg", "klaviatur"), snapshot.startingWith("kla", 0.0, 2))
    }

    @Test
    fun `a word below the minimum score is not offered`() {
        val snapshot = snapshotOf(entry("klabautersteg", 1), entry("klaviatur", 3))
        val suggestable = WordLearningGate.SIGHTINGS_FOR_SUGGESTIONS.toDouble()
        assertEquals(listOf("klaviatur"), snapshot.startingWith("kla", suggestable, 5))
    }

    @Test
    fun `an old word is worth less than a fresh one with the same count`() {
        val snapshot = snapshotOf(
            entry("frisch", 3),
            entry("fossil", 3, ageDays = (WordLearningGate.HALF_LIFE_DAYS * 2).toLong()),
        )
        assertTrue(snapshot.scoreOfKey("frisch") > snapshot.scoreOfKey("fossil"))
        assertTrue(snapshot.scoreOfKey("fossil") < 1.0, "two half-lives must take 3 below 1")
    }

    @Test
    fun `a promoted word does not decay`() {
        // Once it is in the personal dictionary it belongs to the user; the row here is only the record
        // of how it got there.
        val snapshot = snapshotOf(
            entry("gelernt", 3, ageDays = 1000, promoted = true),
            entry("vergessen", 3, ageDays = 1000),
        )
        assertEquals(3.0, snapshot.scoreOfKey("gelernt"))
        assertTrue(snapshot.scoreOfKey("vergessen") < 0.1)
    }

    @Test
    fun `the typed capitalisation is what comes back, not the lookup key`() {
        val snapshot = LearnedSnapshot.of(
            "de",
            listOf(
                LearnedWordEntry(
                    id = 0, word = "Klabautersteg", key = "klabautersteg",
                    lang = "de", count = 3, lastUsed = now,
                ),
            ),
            now,
        )
        assertEquals(listOf("Klabautersteg"), snapshot.startingWith("kla", 0.0, 5))
    }
}
