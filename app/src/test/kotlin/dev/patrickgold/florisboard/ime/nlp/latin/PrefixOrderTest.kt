/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [PrefixOrder] replaces reading the ranked list word by word for a rare prefix (issue #381), so it has to
 * answer exactly what that reading answered: the words matching the prefix, most frequent first. The
 * reading is kept here as the oracle, with the very comparison the completion walk uses.
 */
class PrefixOrderTest {

    /** The walk's own answer: every rank whose word matches, in rank order. */
    private fun oracle(words: List<String>, keys: List<String>?, prefix: String): List<Int> =
        words.indices.filter { rank ->
            if (keys != null) keys[rank].startsWith(prefix) else words[rank].startsWith(prefix, ignoreCase = true)
        }

    private fun ranked(dictName: String): List<String> =
        EvalKeyboard.readDict(dictName).entries.sortedByDescending { it.value }.map { it.key }

    private fun checkDictionary(dictName: String, seed: Long) {
        val words = ranked(dictName)
        val order = PrefixOrder(words, keys = null)
        val rng = Random(seed)
        repeat(400) {
            val word = words[rng.nextInt(words.size)]
            // A real prefix, the same in another case, or a prefix with a slip in it.
            val cut = word.take(1 + rng.nextInt(word.length))
            val prefix = when (it % 3) {
                0 -> cut
                1 -> cut.uppercase()
                else -> cut.dropLast(1) + "qxz"[rng.nextInt(3)]
            }
            val expected = oracle(words, null, prefix)
            val actual = order.ranksStartingWith(prefix, limit = Int.MAX_VALUE)
            assertEquals(expected, actual?.toList(), "$dictName: \"$prefix\"")
        }
    }

    @Test
    fun findsWhatTheWalkFindsInEnglish() = checkDictionary("en.json", 381L)

    @Test
    fun findsWhatTheWalkFindsInGermanWhereNounsAreCapitalised() = checkDictionary("de.json", 382L)

    @Test
    fun comparesFoldKeysAsTheyAreWhenTheLanguageHasThem() {
        val words = listOf("hôte", "hotel", "Hôpital", "hors", "été")
        val keys = words.map { DictFold.foldFrench(it).lowercase() }
        val order = PrefixOrder(words, keys)
        for (prefix in listOf("ho", "hot", "hop", "ete", "x", "")) {
            assertEquals(oracle(words, keys, prefix), order.ranksStartingWith(prefix, Int.MAX_VALUE)?.toList(), prefix)
        }
    }

    @Test
    fun matchesCaseTheWayIgnoreCaseDoesEvenWhereLowercasingChangesLength() {
        // Turkish İ lowercases to two characters as a string, but compares as one character ignoring case.
        val words = listOf("İstanbul", "istanbul", "Istanbul", "ılık", "IŞIK", "işte")
        val order = PrefixOrder(words, keys = null)
        for (prefix in listOf("i", "is", "İs", "ı", "I", "iş", "ış")) {
            assertEquals(oracle(words, null, prefix), order.ranksStartingWith(prefix, Int.MAX_VALUE)?.toList(), prefix)
        }
    }

    @Test
    fun aCommonPrefixIsLeftToTheWalk() {
        val words = ranked("en.json")
        assertNull(PrefixOrder(words, null).ranksStartingWith("s", limit = 1024))
    }
}
