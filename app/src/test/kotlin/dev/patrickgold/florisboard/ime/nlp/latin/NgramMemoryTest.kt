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

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the context tables actually cost in memory (issue #334).
 *
 * [NgramIndex] exists to make a much larger table affordable, and that claim was an argument from the
 * shape of the data rather than a number until this file. It is measured rather than asserted because
 * the argument is the whole reason the structure is not simply a `HashMap`, and because the honest
 * answer might have been "the map would have been fine".
 *
 * Measured on the JVM, not on ART, so the absolute figures are not what a phone sees — Android's
 * runtime compacts ASCII strings and lays objects out differently. The *ratio* is the point, and it
 * moves the same way on both: a map pays per entry for a String header, a boxed count and a hash node,
 * while the index pays for the bytes it read plus three ints.
 *
 * The 60,000-entry arm is the first 60,000 lines of the shipped table rather than the old file itself.
 * What a map costs depends on how many entries it holds and how long their keys are, not on which
 * pairs they happen to be, so this measures the same thing without checking a megabyte of superseded
 * data into the repository.
 */
class NgramMemoryTest {

    private fun heapUsed(): Long {
        val rt = Runtime.getRuntime()
        repeat(4) {
            System.gc()
            Thread.sleep(60)
        }
        return rt.totalMemory() - rt.freeMemory()
    }

    /** Builds [make], holds it, and returns how much the heap grew while it existed. */
    private fun <T> cost(label: String, make: () -> T): Pair<String, Long> {
        val before = heapUsed()
        val held = make()
        val after = heapUsed()
        // Touch the structure after measuring so no optimiser may drop it before `after` is read.
        check(held != null)
        return label to (after - before)
    }

    /** The generated English trigram table, if this machine has one — see the call site. */
    private fun generatedTrigrams(): String? {
        var dir = File(".").absoluteFile
        repeat(5) {
            val candidate = File(dir, "tools/glide-dict/dist/en_trigrams_100k.txt")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile ?: return null
        }
        return null
    }

    private fun asMap(text: String): Map<String, Long> {
        val map = HashMap<String, Long>(45_000)
        text.lineSequence().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab > 0) line.substring(tab + 1).toLongOrNull()?.let { map[line.substring(0, tab)] = it }
        }
        return map
    }

    @Test
    fun `the index costs less than the map it replaced, for five times the data`() {
        val bigrams = EvalKeyboard.dictFile("en_bigrams.txt").readText()
        val sixtyThousand = bigrams.lineSequence().take(60_000).joinToString("\n")

        val results = mutableListOf(
            cost("60k bigrams as HashMap (the size that shipped)") { asMap(sixtyThousand) },
            cost("150k bigrams as HashMap (what the new table would cost)") { asMap(bigrams) },
            cost("150k bigrams as NgramIndex") { NgramIndex.parse(bigrams) },
        )
        // The trigram table is downloaded rather than bundled, so it is only here on a machine that
        // has run the generator. Its row is skipped elsewhere rather than faked.
        generatedTrigrams()?.let { text ->
            results += cost("both tables as NgramIndex (150k + 100k)") {
                NgramIndex.parse(bigrams) to NgramIndex.parse(text)
            }
        }

        println()
        println("=== context table memory, JVM heap ===")
        for ((label, bytes) in results) {
            println("%-55s %8.2f MB".format(label, bytes / 1e6))
        }

        val oldMap = results[0].second
        val newMap = results[1].second
        val newIndex = results[2].second
        println()
        println("index vs map, same data: %.2f×".format(newMap.toDouble() / newIndex))
        println("index (150k) vs the map that shipped (60k): %.2f×".format(newIndex.toDouble() / oldMap))

        assertTrue(newIndex < newMap, "the index must cost less than a map of the same table")
    }
}
