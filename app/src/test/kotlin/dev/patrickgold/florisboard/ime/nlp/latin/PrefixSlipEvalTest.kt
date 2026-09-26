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
import kotlin.test.assertTrue

/**
 * A measuring stand for a slip inside a half-typed word (issue #381): `dixt` on the way to `dictionary`.
 *
 * The strip is modelled as the keyboard builds it for a word it does not know: the completion walk's
 * words (W — the most frequent dictionary words starting with exactly what was typed, five of them,
 * because three slots are reserved for fixes), then the fixes (F — [CorrectionReaders.merge]), with the
 * completions of what was probably meant worked in ([CorrectionReaders.completionsByTouch] and
 * [CorrectionReaders.completionsByString]). Where those completions go is the decision this file made, so
 * every placement is measured against three populations:
 *
 *  - **prefix slips** — the case #381 is about, and the one that must get better;
 *  - **correct prefixes** — typed exactly; must not get worse;
 *  - **whole-word slips** — what the fix slots already serve; must not get worse.
 *
 * The walk is modelled here rather than called, because in the provider it is interleaved with the
 * user's own words; its dictionary half is one line and cannot drift in any way that matters. Everything
 * that reads taps or strings, and the placement that ships, is the shipping code. The placements that did
 * not ship are defined here, because they are candidates under measurement and nothing else uses them.
 *
 * Deliberately without the bigram context bonus, as in [AutocorrectEvalTest]: it is what tells `keyboard`
 * from `kevin` after "my", and leaving it out keeps the table about the readers.
 */
class PrefixSlipEvalTest {

    private companion object {
        const val SAMPLE_SIZE = 500
        const val SEED = 20260927L
        const val STRIP = 8
        const val WALK_CAP = STRIP - 3
    }

    private val layout = EvalKeyboard.layout

    private val sqDistance: (Char, Char) -> Float? = { a, b ->
        if (a == b) {
            0f
        } else {
            val ia = layout.indexOf(a)
            val ib = layout.indexOf(b)
            if (ia < 0 || ib < 0) null else layout.sqDistanceBetween(ia, ib)
        }
    }

    private lateinit var dict: Dict

    private class Dict(
        val freq: Map<String, Int>,
        val ranked: List<String>,
        val alphabet: Set<Char>,
        val index: TouchBeamDecoder.PrefixIndex,
    )

    /** One placement: given the fixes (f), beam completions (t) and string completions (s), what follows W. */
    private class Placement(val name: String, val place: (f: List<String>, t: List<String>, s: List<String>) -> List<String>)

    private val placements = listOf(
        Placement("before") { f, _, _ -> f },
        // Rejected: completions only ever after the fixes. Harmless, and mostly out of sight.
        Placement("appended") { f, t, s -> f + CorrectionReaders.completions(t, s) },
        // Rejected: the beam's completions alone. Blind to a transposition, a dropped, doubled or far key.
        Placement("beam, 2nd") { f, t, _ -> f.take(1) + t.take(1) + f.drop(1) + t.drop(1) },
        // Rejected: the best completion always second. Finds the most, and costs finished words the most.
        Placement("always 2nd") { f, t, s ->
            val c = CorrectionReaders.completions(t, s)
            f.take(1) + c.take(1) + f.drop(1) + c.drop(1)
        },
        Placement("SHIPPED") { f, t, s ->
            CorrectionReaders.withCompletions(f, CorrectionReaders.completions(t, s)) { dict.freq[it] ?: 0 }
        },
    )

    private val touchNanos = ArrayList<Long>()
    private val stringNanos = ArrayList<Long>()

    /** Every placement's strip for one typed word, in [placements] order. */
    private fun strips(typed: String, taps: FloatArray): List<List<String>> {
        val known = typed in dict.freq
        val walk = dict.ranked.asSequence().filter { it.startsWith(typed) }.take(if (known) STRIP else WALK_CAP).toList()
        // A word the dictionary knows never reaches the fix branch at all.
        if (known) return placements.map { walk }

        val touch = CorrectionReaders.byTouch(
            points = taps, typed = typed, folded = typed, freq = dict.freq, alphabet = dict.alphabet,
            prefixIndex = dict.index, layout = layout, maxCount = CorrectionReaders.MAX_CORRECTIONS,
            contextScore = { 0.0 },
        )
        val string = CorrectionReaders.byString(
            typed, dict.freq, dict.alphabet, dict.index, sqDistance, { 0.0 }, allowDistance2 = touch == null,
        )
        val fixes = CorrectionReaders.merge(touch?.words.orEmpty(), string.words, CorrectionReaders.MAX_CORRECTIONS)

        var started = System.nanoTime()
        val byTouch = CorrectionReaders.completionsByTouch(
            points = taps, typed = typed, folded = typed, prefixIndex = dict.index, layout = layout,
            maxCount = STRIP, contextScore = { 0.0 },
        )
        touchNanos.add(System.nanoTime() - started)
        started = System.nanoTime()
        val byString = CorrectionReaders.completionsByString(
            folded = typed, prefixIndex = dict.index, alphabet = dict.alphabet, maxCount = STRIP,
            sqDistance = sqDistance, contextScore = { 0.0 },
        )
        stringNanos.add(System.nanoTime() - started)

        return placements.map { p ->
            val offers = p.place(fixes, byTouch, byString)
            // The word the silent swap is decided about must lead the offers in every placement.
            assertEquals(fixes.firstOrNull() ?: offers.firstOrNull(), offers.firstOrNull(), "${p.name} moved the lead for $typed")
            (walk + offers).distinct().take(STRIP)
        }
    }

    // ── Slips ────────────────────────────────────────────────────────────────────────────────────

    private fun farKey(word: String, rng: Random): Pair<String, FloatArray> {
        val at = rng.nextInt(word.length)
        val near = EvalKeyboard.neighbours[word[at]].orEmpty()
        val far = EvalKeyboard.centres.keys.filter { it != word[at] && it !in near }
        val typed = word.substring(0, at) + far[rng.nextInt(far.size)] + word.substring(at + 1)
        return typed to EvalKeyboard.tapsFor(typed)
    }

    private fun dropped(word: String, rng: Random): Pair<String, FloatArray>? {
        if (word.length < 4) return null
        val at = rng.nextInt(word.length)
        val typed = word.removeRange(at, at + 1)
        return typed to EvalKeyboard.tapsFor(typed)
    }

    private fun doubled(word: String, rng: Random): Pair<String, FloatArray> {
        val at = rng.nextInt(word.length)
        val typed = word.substring(0, at + 1) + word.substring(at)
        return typed to EvalKeyboard.tapsFor(typed)
    }

    private fun slips(text: String, rng: Random): List<Pair<String, () -> Pair<String, FloatArray>?>> = listOf(
        "clean 1" to { EvalKeyboard.cleanNeighbourSlip(text, rng, slips = 1) },
        "noisy" to { EvalKeyboard.noisySlip(text, rng, sigma = 0.55f) },
        "transpose" to { EvalKeyboard.transposition(text, rng) },
        "far key" to { farKey(text, rng) },
        "dropped" to { dropped(text, rng) },
        "doubled" to { doubled(text, rng) },
    )

    // ── The experiment ───────────────────────────────────────────────────────────────────────────

    private class Row(val kind: String, val intended: String, val strips: List<List<String>>)

    @Test
    fun measureWhereCompletionsOfASlipBelong() {
        val en = EvalKeyboard.readDict("en.json").filterKeys { EvalKeyboard.typeable(it) }
        val sorted = en.keys.sorted().toTypedArray()
        dict = Dict(
            freq = en,
            ranked = en.entries.sortedByDescending { it.value }.map { it.key },
            alphabet = en.keys.flatMapTo(HashSet()) { it.asIterable() },
            index = TouchBeamDecoder.PrefixIndex(sorted, IntArray(sorted.size) { en.getValue(sorted[it]) }),
        )

        val rng = Random(SEED)
        // Long enough that stopping part-way is when the strip matters most.
        val pool = en.entries.filter { it.value >= 150 && it.key.length >= 6 }.map { it.key }
        val corpus = List(SAMPLE_SIZE) { pool[rng.nextInt(pool.size)] }

        val results = LinkedHashMap<String, MutableList<Row>>()
        fun record(population: String, kind: String, intended: String, typed: String, taps: FloatArray) {
            results.getOrPut(population) { ArrayList() }.add(Row(kind, intended, strips(typed, taps)))
        }
        for (word in corpus) {
            val cut = 3 + rng.nextInt(word.length - 4) // leaves at least two letters untyped
            val prefix = word.take(cut)
            record("correct prefix", "exact", word, prefix, EvalKeyboard.tapsFor(prefix))
            for ((kind, generate) in slips(prefix, rng)) {
                val (typed, taps) = generate() ?: continue
                if (typed.length < 3 || word.startsWith(typed)) continue
                record("prefix slip", kind, word, typed, taps)
            }
            for ((kind, generate) in slips(word, rng)) {
                val (typed, taps) = generate() ?: continue
                if (en.containsKey(typed)) continue
                record("whole-word slip", kind, word, typed, taps)
            }
        }

        fun rate(rows: List<Row>, placement: Int, first: Int) =
            rows.count { it.intended in it.strips[placement].take(first) } * 100.0 / rows.size

        println()
        println("=== #381 · a slip inside a half-typed word ===============================")
        println("intended word among the first 3 strip entries / among all $STRIP")
        for ((population, rows) in results) {
            println()
            println("%-16s %5s   %s".format(population, "n", placements.joinToString("   ") { "%-15s".format(it.name) }))
            println("-".repeat(24 + placements.size * 18))
            for (kind in rows.map { it.kind }.distinct() + "ALL") {
                val rs = if (kind == "ALL") rows else rows.filter { it.kind == kind }
                val cells = placements.indices.joinToString("   ") { p ->
                    "%5.1f / %5.1f%%  ".format(rate(rs, p, 3), rate(rs, p, STRIP))
                }
                println("%-16s %5d   %s".format(kind, rs.size, cells))
            }
        }
        println()
        for ((name, nanos) in listOf("completionsByTouch" to touchNanos, "completionsByString" to stringNanos)) {
            val s = nanos.sorted()
            println(
                "%-20s on this JVM: %.3f ms mean, %.3f ms p90".format(name, s.average() / 1e6, s[s.size * 9 / 10] / 1e6),
            )
        }
        println()

        val shipped = placements.indexOfFirst { it.name == "SHIPPED" }
        val video = videoCases(shipped)

        // Loose on purpose: the table is the evidence, these only hold its shape.
        val prefixSlips = results.getValue("prefix slip")
        val wholeWord = results.getValue("whole-word slip")
        assertTrue(rate(prefixSlips, shipped, 3) > rate(prefixSlips, 0, 3) + 15, "prefix slips must reach the first three")
        assertTrue(rate(wholeWord, shipped, 3) > rate(wholeWord, 0, 3) - 3, "finished words must keep their fixes in sight")
        assertTrue("consequences" in video.getValue("conseqd").take(3), "conseqd: ${video["conseqd"]}")
        assertTrue("because" in video.getValue("vecau").take(3), "vecau: ${video["vecau"]}")
    }

    /** The half-typed words from the #381 issue and videos, typed dead-centre as he typed them. */
    private fun videoCases(shipped: Int): Map<String, List<String>> {
        val cases = listOf(
            "dixt" to "dictionary", "conseqd" to "consequences", "fedva" to "feedback", "ecamf" to "exams",
            "wroryi" to "writing", "vecau" to "because", "developr" to "developer", "keyv" to "keyboard",
            "birthd" to "birthday", "provid" to "providing",
        )
        val out = LinkedHashMap<String, List<String>>()
        println("%-10s %-40s %s".format("typed", "before", "SHIPPED"))
        println("-".repeat(96))
        for ((typed, wanted) in cases) {
            val strips = strips(typed, EvalKeyboard.tapsFor(typed))
            fun show(s: List<String>) = s.joinToString(" · ") { if (it == wanted) "[$it]" else it }
            println("%-10s %-40s %s".format(typed, show(strips[0]), show(strips[shipped])))
            out[typed] = strips[shipped]
        }
        println("-".repeat(96))
        println()
        return out
    }
}
