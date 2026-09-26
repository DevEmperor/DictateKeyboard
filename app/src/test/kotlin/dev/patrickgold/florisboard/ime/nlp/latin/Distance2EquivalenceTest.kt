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

/**
 * [CorrectionReaders.distance2] exists only to be fast (issue #381), so it must find exactly what the
 * definition it replaced found: every dictionary word among every edit of every edit. The definition is
 * kept here, as the oracle, and both are asked the same questions — English and German, whole-word
 * typos, half-typed words and plain garbage.
 */
class Distance2EquivalenceTest {

    /** The old way, verbatim: build every edit of every edit and look each one up. */
    private fun oracle(folded: String, alphabet: Set<Char>, words: Set<String>): Set<String> {
        val out = HashSet<String>()
        for (e in EditDistance.edits1(folded, alphabet)) {
            for (ee in EditDistance.edits1(e, alphabet)) if (ee in words) out.add(ee)
        }
        return out
    }

    private fun check(dictName: String, samples: Int, seed: Long) {
        val words = EvalKeyboard.readDict(dictName).keys
        val sorted = words.sorted().toTypedArray()
        val index = TouchBeamDecoder.PrefixIndex(sorted)
        val alphabet = words.flatMapTo(HashSet()) { it.asIterable() }
        val letters = alphabet.toList()
        val rng = Random(seed)
        val pool = sorted.filter { it.length in 3..12 }

        var oracleNanos = 0L
        var fastNanos = 0L
        repeat(samples) { i ->
            val word = pool[rng.nextInt(pool.size)]
            // Two random edits of a real word, a real word cut short, or letters at random.
            val typed = when (i % 3) {
                0 -> {
                    val chars = word.toCharArray()
                    repeat(2) { chars[rng.nextInt(chars.size)] = letters[rng.nextInt(letters.size)] }
                    String(chars)
                }
                1 -> word.take(3 + rng.nextInt(word.length - 2))
                else -> String(CharArray(3 + rng.nextInt(7)) { letters[rng.nextInt(letters.size)] })
            }
            var started = System.nanoTime()
            val expected = oracle(typed, alphabet, words)
            oracleNanos += System.nanoTime() - started
            started = System.nanoTime()
            val actual = CorrectionReaders.distance2(typed, alphabet, index)
            fastNanos += System.nanoTime() - started
            assertEquals(expected, actual, "$dictName: \"$typed\"")
        }
        println(
            "%s: %d words, every edit of every edit %.1f ms/word, the index walk %.2f ms/word".format(
                dictName, samples, oracleNanos / 1e6 / samples, fastNanos / 1e6 / samples,
            ),
        )
    }

    @Test
    fun findsExactlyWhatEveryEditOfEveryEditFindsInEnglish() = check("en.json", samples = 150, seed = 381L)

    @Test
    fun findsExactlyWhatEveryEditOfEveryEditFindsInGerman() = check("de.json", samples = 150, seed = 382L)

    @Test
    fun theWordsOfATinyDictionaryAreFoundByEveryKindOfEdit() {
        // "bca" and "ba" are the two cases an alignment misses: `ab` swapped with a letter inserted between,
        // and `acb` swapped with the letter between dropped.
        val words = setOf("hello", "help", "held", "world", "bca", "ba")
        val index = TouchBeamDecoder.PrefixIndex(words.sorted().toTypedArray())
        val alphabet = words.flatMapTo(HashSet()) { it.asIterable() } + 'x'
        assertEquals(setOf("bca", "ba"), CorrectionReaders.distance2("ab", alphabet, index) intersect setOf("bca", "ba"))
        assertEquals(setOf("ba"), CorrectionReaders.distance2("acb", alphabet, index) intersect setOf("ba"))
        for (typed in listOf("hlelo", "helo", "hellloo", "xhelp", "hepl", "wrold", "heldxx", "hxllx", "ab", "acb", "xab")) {
            assertEquals(
                oracle(typed, alphabet, words),
                CorrectionReaders.distance2(typed, alphabet, index),
                typed,
            )
        }
    }
}
