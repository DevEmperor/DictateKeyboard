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
 * A measuring stand for which spelling fixes reach the strip at all (issue #381).
 *
 * [AutocorrectEvalTest] measures the silent half — may Space swap a word unasked. This measures the half
 * the user taps: is the intended word among the corrections offered? Until #381 the answer came from one
 * reader or the other — the beam whenever it found anything, the edit-distance reader only when it did
 * not — so a beam that read the taps into a plausible wrong word hid the string reader's right one.
 *
 * Both readers and the merge are called from [CorrectionReaders], exactly as the keyboard calls them.
 * The two rules that did *not* ship are defined here, because they are candidates under measurement and
 * nothing else uses them:
 *
 *  - **appended** — the string reader only fills slots the beam left empty. Loses nothing, finds little:
 *    the beam rarely leaves a slot empty.
 *  - **with d2** — the shipped merge, but distance 2 also beside a beam that spoke. Finds the same words
 *    and costs two orders of magnitude more per keystroke; the timings are printed with the table.
 *
 * What it leaves out, deliberately: the bigram context bonus (as in [AutocorrectEvalTest]), and prefix
 * completions — a typo inside a half-typed word (`dixt` for `dictionary`) is a different question, and
 * neither reader can answer it.
 */
class ReaderMergeEvalTest {

    private companion object {
        const val SAMPLE_SIZE = 400
        const val SEED = 20260926L
        const val MAX = CorrectionReaders.MAX_CORRECTIONS
    }

    private val layout = EvalKeyboard.layout

    /** Key distance on the synthetic layout, the way `KeyProximityInfo.normSqDistance` reads the real one. */
    private val sqDistance: (Char, Char) -> Float? = { a, b ->
        if (a == b) {
            0f
        } else {
            val ia = layout.indexOf(a)
            val ib = layout.indexOf(b)
            if (ia < 0 || ib < 0) null else layout.sqDistanceBetween(ia, ib)
        }
    }

    /** The rejected rule: the string reader only ever fills slots the beam left empty. */
    private fun appended(touch: List<String>, text: List<String>): List<String> =
        (touch + text).distinct().take(MAX)

    private class Reading(
        val intended: String,
        val typed: String,
        val kind: String,
        /** What shipped before #381: the beam's list whenever it had one, the string reader's otherwise. */
        val before: List<String>,
        val appended: List<String>,
        val shipped: List<String>,
        val withD2: List<String>,
        val beamSpoke: Boolean,
        /** Nanoseconds a distance-2 search took, when one ran for the "with d2" rule; 0 otherwise. */
        val d2Nanos: Long,
        val d1Nanos: Long,
    )

    private class Dict(val freq: Map<String, Int>, val alphabet: Set<Char>, val index: TouchBeamDecoder.PrefixIndex)

    private fun read(typed: String, taps: FloatArray, dict: Dict, intended: String, kind: String): Reading {
        val touch = CorrectionReaders.byTouch(
            points = taps,
            typed = typed,
            folded = typed,
            freq = dict.freq,
            alphabet = dict.alphabet,
            prefixIndex = dict.index,
            layout = layout,
            maxCount = MAX,
            contextScore = { 0.0 },
        )
        fun byString(allowDistance2: Boolean) =
            CorrectionReaders.byString(typed, dict.freq, dict.alphabet, sqDistance, { 0.0 }, allowDistance2)

        var started = System.nanoTime()
        val shipped = byString(allowDistance2 = touch == null)
        val d1Nanos = if (touch != null) System.nanoTime() - started else 0L
        started = System.nanoTime()
        val always = byString(allowDistance2 = true)
        val d2Nanos = if (always.distance1Empty) System.nanoTime() - started else 0L

        val touchWords = touch?.words.orEmpty()
        return Reading(
            intended = intended,
            typed = typed,
            kind = kind,
            before = touch?.words ?: shipped.words,
            appended = appended(touchWords, shipped.words),
            shipped = CorrectionReaders.merge(touchWords, shipped.words, MAX),
            withD2 = CorrectionReaders.merge(touchWords, always.words, MAX),
            beamSpoke = touch != null,
            d2Nanos = d2Nanos,
            d1Nanos = d1Nanos,
        )
    }

    // ── Slips the existing generators do not produce ─────────────────────────────────────────────

    /** A key nowhere near the right one — the finger went to the wrong letter, not the wrong spot. */
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

    // ── The experiment ───────────────────────────────────────────────────────────────────────────

    @Test
    fun measureWhichFixesReachTheStrip() {
        val en = EvalKeyboard.readDict("en.json").filterKeys { EvalKeyboard.typeable(it) }
        val dict = Dict(
            freq = en,
            alphabet = en.keys.flatMapTo(HashSet()) { it.asIterable() },
            index = TouchBeamDecoder.PrefixIndex(en.keys.sorted().toTypedArray()),
        )

        val rng = Random(SEED)
        val pool = en.entries.filter { it.value >= 150 }.map { it.key }
        val corpus = List(SAMPLE_SIZE) { pool[rng.nextInt(pool.size)] }

        val readings = ArrayList<Reading>()
        for (word in corpus) {
            val generators = listOf(
                "clean 1" to { EvalKeyboard.cleanNeighbourSlip(word, rng, slips = 1) },
                "clean 2" to { EvalKeyboard.cleanNeighbourSlip(word, rng, slips = 2) },
                "noisy" to { EvalKeyboard.noisySlip(word, rng, sigma = 0.55f) },
                "transpose" to { EvalKeyboard.transposition(word, rng) },
                "far key" to { farKey(word, rng) },
                "dropped" to { dropped(word, rng) },
                "doubled" to { doubled(word, rng) },
            )
            for ((kind, generate) in generators) {
                val (typed, taps) = generate() ?: continue
                // A slip that lands on a real word never reaches the correction branch (`!isKnown`).
                if (typed.length < 3 || en.containsKey(typed)) continue
                readings.add(read(typed, taps, dict, word, kind))
            }
        }

        println()
        println("=== #381 · is the intended word among the offered fixes? ================")
        println(
            "%-10s %6s %9s %9s %9s %9s %11s".format("slip", "n", "before", "appended", "SHIPPED", "with d2", "beam spoke"),
        )
        println("-".repeat(70))
        val kinds = readings.map { it.kind }.distinct()
        for (kind in kinds + "ALL") {
            val rs = if (kind == "ALL") readings else readings.filter { it.kind == kind }
            fun pct(pick: (Reading) -> List<String>) = rs.count { it.intended in pick(it) } * 100.0 / rs.size
            println(
                "%-10s %6d %8.1f%% %8.1f%% %8.1f%% %8.1f%% %10.1f%%".format(
                    kind, rs.size, pct { it.before }, pct { it.appended }, pct { it.shipped }, pct { it.withD2 },
                    rs.count { it.beamSpoke } * 100.0 / rs.size,
                ),
            )
        }
        println("-".repeat(70))
        for ((name, pick) in listOf<Pair<String, (Reading) -> List<String>>>(
            "appended" to { it.appended }, "SHIPPED" to { it.shipped }, "with d2" to { it.withD2 },
        )) {
            val won = readings.count { it.intended !in it.before && it.intended in pick(it) }
            val lost = readings.count { it.intended in it.before && it.intended !in pick(it) }
            println("%-9s vs before: +%d found, −%d lost".format(name, won, lost))
        }
        val d1 = readings.filter { it.beamSpoke }.map { it.d1Nanos }
        val d2 = readings.filter { it.beamSpoke && it.d2Nanos > 0 }.map { it.d2Nanos }.sorted()
        println("added per keystroke beside a beam, on this JVM:")
        println("  distance 1 (shipped): %.3f ms mean".format(d1.average() / 1e6))
        if (d2.isNotEmpty()) {
            println(
                "  distance 2 (with d2): %.1f ms mean, %.1f ms p90, needed for %.1f %% of those words".format(
                    d2.average() / 1e6, d2[d2.size * 9 / 10] / 1e6, d2.size * 100.0 / d1.size,
                ),
            )
        }
        println()

        val video = videoCases(dict)

        // What the merge guarantees by construction: the first offer — the one the silent swap is decided
        // on — never changes.
        for (r in readings) {
            assertEquals(r.before.firstOrNull(), r.shipped.firstOrNull(), "merge changed the lead for ${r.typed}")
        }
        val won = readings.count { it.intended !in it.before && it.intended in it.shipped }
        val lost = readings.count { it.intended in it.before && it.intended !in it.shipped }
        assertTrue(won > 5 * lost, "the merge must find far more than it loses (+$won, −$lost)")
        // The two from #381 that only the string reader can see.
        assertTrue("hello" in video.getValue("helwo"), "helwo must offer hello: ${video["helwo"]}")
        assertTrue("dictate" in video.getValue("diltate"), "diltate must offer dictate: ${video["diltate"]}")
    }

    /** The words from the #381 videos, typed dead-centre as he typed them. Returns what ships, per word. */
    private fun videoCases(dict: Dict): Map<String, List<String>> {
        val cases = listOf(
            "helwo" to "hello", "blotehr" to "brother", "malkrt" to "market", "vecaude" to "because",
            "developrt" to "developer", "dictaye" to "dictate", "whatt" to "what", "diltate" to "dictate",
            "emeperot" to "emperor", "birthdat" to "birthday", "laplop" to "laptop", "blother" to "brother",
        )
        val shipped = LinkedHashMap<String, List<String>>()
        println("%-10s %-34s %s".format("typed", "before", "SHIPPED"))
        println("-".repeat(80))
        for ((typed, wanted) in cases) {
            val r = read(typed, EvalKeyboard.tapsFor(typed), dict, wanted, "video")
            fun show(list: List<String>) = list.joinToString(" · ") { if (it == wanted) "[$it]" else it }
            println("%-10s %-34s %s".format(typed, show(r.before), show(r.shipped)))
            shipped[typed] = r.shipped
        }
        println("-".repeat(80))
        println()
        return shipped
    }
}
