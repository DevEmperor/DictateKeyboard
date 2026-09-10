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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What [NgramIndex] has to get right for the context tables to be trustworthy (issue #334).
 *
 * The structure exists because the tables outgrew a HashMap, and everything it saves it saves by
 * assuming the file is sorted and searching it by hand. Both of those assumptions are things a test
 * has to hold down: a binary search over a table that is not in the order it thinks silently answers
 * "not found", which in this feature looks exactly like "no prediction available" and would never be
 * noticed.
 */
class NgramIndexTest {

    private fun table(vararg lines: String) = lines.joinToString("\n", postfix = "\n")

    @Test
    fun `finds a key and answers zero for one it does not have`() {
        val index = NgramIndex.parse(table("in the\t900", "of the\t1200", "to the\t500"))
        assertEquals(1200L, index.countOf("of the"))
        assertEquals(900L, index.countOf("in the"))
        assertEquals(500L, index.countOf("to the"))
        assertEquals(0L, index.countOf("at the"))
        assertEquals(0L, index.countOf(""))
        assertEquals(3, index.size)
    }

    @Test
    fun `offers the commonest continuations first`() {
        val index = NgramIndex.parse(
            table("in the world\t50", "in the end\t300", "in the meantime\t120", "in the morning\t200"),
        )
        assertEquals(listOf("end", "morning", "meantime"), index.topContinuations("in the", 3))
        assertEquals(listOf("end"), index.topContinuations("in the", 1))
        assertEquals(emptyList<String>(), index.topContinuations("in the", 0))
    }

    /**
     * The separator is part of the needle, which is the whole reason a prefix lookup can be trusted:
     * "in the" must not reach "in theory", and it is the one boundary an off-by-one would get wrong
     * in the direction that still looks plausible on screen.
     */
    @Test
    fun `a prefix ends at a word boundary`() {
        val index = NgramIndex.parse(table("in the end\t300", "in theory it\t900", "into the end\t400"))
        assertEquals(listOf("end"), index.topContinuations("in the", 3))
        assertEquals(listOf("it"), index.topContinuations("in theory", 3))
        assertEquals(emptyList<String>(), index.topContinuations("in th", 3))
        assertEquals(emptyList<String>(), index.topContinuations("zz", 3))
    }

    /**
     * A file that is not in key order still has to work — an old count-ordered bigram file left on a
     * device, or any table whose keys were folded on the way in.
     */
    @Test
    fun `sorts a table that did not arrive sorted`() {
        val index = NgramIndex.parse(table("to the\t500", "of the\t1200", "in the\t900", "at the\t100"))
        assertEquals(1200L, index.countOf("of the"))
        assertEquals(100L, index.countOf("at the"))
        assertEquals(0L, index.countOf("by the"))
        assertEquals(4, index.size)
    }

    /**
     * Folding can put two spellings on one key. Dropping one of them would lose real evidence, so the
     * counts are added — this is the case the old `map[folded] = count` silently got wrong.
     */
    @Test
    fun `merges keys that fold together instead of letting one win`() {
        val index = NgramIndex.parse(
            table("hôte de\t30", "hote de\t70", "autre chose\t40"),
            fold = { it.replace('ô', 'o') },
        )
        assertEquals(100L, index.countOf("hote de"))
        assertEquals(2, index.size)
        assertEquals(listOf("de"), index.topContinuations("hote", 3))
    }

    /**
     * Order has to be code-point order, because that is what the generator's sort produces. Comparing
     * signed bytes would put every non-ASCII key before every ASCII one and the binary search would
     * walk past half the table.
     */
    @Test
    fun `orders non-ASCII keys the way the generator does`() {
        val index = NgramIndex.parse(table("über alles\t10", "an der\t20", "zu dem\t30", "ähnlich wie\t40"))
        assertEquals(10L, index.countOf("über alles"))
        assertEquals(40L, index.countOf("ähnlich wie"))
        assertEquals(20L, index.countOf("an der"))
        assertEquals(30L, index.countOf("zu dem"))
    }

    @Test
    fun `survives an empty or malformed table`() {
        assertTrue(NgramIndex.parse("").isEmpty)
        assertTrue(NgramIndex.parse("\n\n").isEmpty)
        assertTrue(NgramIndex.parse("no tab here\nanother line\n").isEmpty)
        val partial = NgramIndex.parse(table("good key\t5", "bad key\tnotanumber", "\t9"))
        assertEquals(1, partial.size)
        assertEquals(5L, partial.countOf("good key"))
    }

    /**
     * The bundled English table, as it will actually be read on a device.
     *
     * Everything above proves the structure; this proves the *file*. The generator sorts by key so the
     * app can load it without sorting a hundred and fifty thousand of them, and a regeneration that
     * forgot to — or a table pasted together by hand — would still parse, still answer `countOf`
     * correctly for a while, and quietly stop finding continuations. There is no user-visible symptom
     * for that other than "the strip went quiet", which is also what a language with no data looks
     * like, so it has to be caught here.
     */
    @Test
    fun `the shipped English table is sorted and answers`() {
        val text = EvalKeyboard.dictFile("en_bigrams.txt").readText()
        val keys = text.lineSequence().mapNotNull { line ->
            line.indexOf('\t').takeIf { it > 0 }?.let { line.substring(0, it) }
        }.toList()
        assertTrue(keys.size > 100_000, "expected the regenerated table, got ${keys.size} pairs")

        val unsortedAt = (1 until keys.size).firstOrNull {
            compareUtf8(keys[it - 1], keys[it]) > 0
        }
        assertTrue(
            unsortedAt == null,
            "not in key order at line ${unsortedAt?.plus(1)}: " +
                "${unsortedAt?.let { keys[it - 1] }} then ${unsortedAt?.let { keys[it] }}",
        )

        val index = NgramIndex.parse(text)
        assertEquals(keys.size, index.size)
        assertTrue(index.countOf("of the") > 0, "the commonest English pair is missing")
        assertTrue(index.topContinuations("in", 3).isNotEmpty())
        assertEquals(0L, index.countOf("zzzz qqqq"))
    }

    /** Code-point order, which is what the generator's sort and [NgramIndex] both use. */
    private fun compareUtf8(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return x.size - y.size
    }

    /** A table big enough that the binary search actually has to work for its answer. */
    @Test
    fun `finds every key in a large sorted table`() {
        val keys = (0 until 5000).map { val n = it.toString().padStart(4, '0'); "w$n next$n" }
        val index = NgramIndex.parse(keys.mapIndexed { i, k -> "$k\t${i + 1}" }.joinToString("\n"))
        assertEquals(5000, index.size)
        keys.forEachIndexed { i, k -> assertEquals((i + 1).toLong(), index.countOf(k)) }
        assertEquals(listOf("next0042"), index.topContinuations("w0042", 3))
        assertEquals(0L, index.countOf("w0042 nope"))
    }
}
