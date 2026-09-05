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
import kotlin.test.assertTrue

/** What the beam decided about one word, as [EvalKeyboard] reports it. */
private typealias Decision = EvalKeyboard.Decision

/**
 * A measuring stand for the silent half of autocorrect (issues #242, #295).
 *
 * ### Why this exists
 *
 * The threshold in [AutoCommitGate] was tuned by a throwaway Python simulation. Two things went wrong
 * with that, and both are the reason this is a file in the repository rather than a script in a tmp
 * directory. The simulation *reimplemented* the decoder, so it could drift from the shipping code
 * without anyone noticing; and it built its typos by perturbing tap coordinates, which meant the
 * population "the finger landed squarely on the wrong key" never appeared in it. That is an ordinary
 * motor slip — you knew the word, your hand went one key over — and it is precisely what #295 reports
 * as uncorrected.
 *
 * ### What it does
 *
 * Runs the **real** [TouchBeamDecoder] and the **real** [TouchScoring] over two populations that pull
 * in opposite directions, and prints what each candidate rule does to both at once:
 *
 *  - **must be fixed** — English words with a deliberate mis-tap;
 *  - **must not be touched** — German words absent from the English dictionary, typed dead-centre.
 *    They stand in for names, foreign words and jargon: correctly typed, and unknown to the dictionary
 *    that is about to judge them. Both files ship in the app, so this needs no network and no corpus.
 *
 * A rule is only ever interesting as a pair of numbers. Reporting the first without the second is the
 * mistake this file exists to prevent, so [sweep] prints them in one table and never separately.
 *
 * ### What it deliberately leaves out
 *
 * Length-changing slips (a letter dropped or typed twice). The beam cannot produce them, so they never
 * reach this gate — they go down the edit-distance path with its own `hadCandidatesBefore` rule. Also
 * the bigram context bonus: the reporter's cases are single words, and mixing the language model in
 * would blur which lever moved the numbers.
 */
class AutocorrectEvalTest {

    private companion object {
        /** The dictionary frequency a correction needs before it may be auto-committed. */
        const val MIN_FREQ = EvalKeyboard.MIN_FREQ

        const val SAMPLE_SIZE = 1500
        const val SEED = 20260830L
    }

    // The keyboard, the dictionaries and the ways a finger goes wrong all live in [EvalKeyboard], so
    // this file and [LearningEvalTest] measure opposite questions against identical evidence. Thin
    // forwarders rather than call sites rewritten to `EvalKeyboard.x`: the experiment below reads the
    // same as it did when its numbers were taken, which is what makes them comparable.

    private fun readDict(name: String): Map<String, Int> = EvalKeyboard.readDict(name)

    private fun typeable(word: String): Boolean = EvalKeyboard.typeable(word)

    private fun decide(
        taps: FloatArray,
        typed: String,
        index: TouchBeamDecoder.PrefixIndex,
        freq: Map<String, Int>,
    ): Decision? = EvalKeyboard.decide(taps, typed, index, freq)

    /** A rule decides, given the tap evidence, whether the swap may happen unasked. */
    private fun interface Rule {
        fun allows(d: Decision): Boolean
    }

    // ── Tap generation ───────────────────────────────────────────────────────────────────────────

    private fun tapsFor(word: String): FloatArray = EvalKeyboard.tapsFor(word)

    /** The case #295 is about: the finger lands dead-centre, on the wrong key. */
    private fun cleanNeighbourSlip(word: String, rng: Random, slips: Int): Pair<String, FloatArray>? =
        EvalKeyboard.cleanNeighbourSlip(word, rng, slips)

    /** The case #242 measured: the finger is off-centre enough to land on the neighbouring key. */
    private fun noisySlip(word: String, rng: Random, sigma: Float): Pair<String, FloatArray>? =
        EvalKeyboard.noisySlip(word, rng, sigma)

    /** Two adjacent characters swapped: the fingers were right, the order was not. */
    private fun transposition(word: String, rng: Random): Pair<String, FloatArray>? =
        EvalKeyboard.transposition(word, rng)

    // ── The experiment ───────────────────────────────────────────────────────────────────────────

    @Test
    fun measureTheAutoCommitGateAgainstBothPopulations() {
        val en = readDict("en.json").filterKeys { typeable(it) }
        val de = readDict("de.json").filterKeys { typeable(it) }
        val words = en.keys.sorted().toTypedArray()
        val index = TouchBeamDecoder.PrefixIndex(words)

        // Sampling is frequency-weighted: an evaluation over the flat vocabulary would be dominated by
        // rare words nobody types, and would flatter any rule that simply refuses to act.
        val rng = Random(SEED)
        val pool = en.entries.filter { it.value >= 150 }.map { it.key }
        val corpus = List(SAMPLE_SIZE) { pool[rng.nextInt(pool.size)] }

        // Population 1 — must be fixed. Kept apart by the kind of slip, because they answer different
        // questions: "clean 1" is what #295 reports, "noisy" is what #242 measured, and reporting them
        // as one average is how the first was lost inside the second.
        val mustFix = ArrayList<Triple<String, Decision, String>>()
        var noOpinion = 0
        for (word in corpus) {
            val generators = listOf(
                "clean 1" to { cleanNeighbourSlip(word, rng, slips = 1) },
                "clean 2" to { cleanNeighbourSlip(word, rng, slips = 2) },
                "noisy" to { noisySlip(word, rng, sigma = 0.55f) },
                "transpose" to { transposition(word, rng) },
            )
            for ((kind, generate) in generators) {
                val (typed, taps) = generate() ?: continue
                if (en.containsKey(typed)) continue // lands on a real word: a different problem (#242)
                val decision = decide(taps, typed, index, en)
                if (decision == null) noOpinion++ else mustFix.add(Triple(word, decision, kind))
            }
        }

        // Population 2 — must not be touched. German words the English dictionary does not know, plus
        // names, all typed dead-centre.
        val foreign = de.keys.filter { it !in en && typeable(it) }.sorted()
        val names = listOf(
            "jannis", "sarahs", "pete", "dads", "linus", "mira", "tobi", "nadja", "lars", "svenja",
        ).filter { it !in en }
        val mustKeep = ArrayList<Pair<String, Decision?>>()
        val keepSample = (foreign.shuffled(Random(SEED)).take(SAMPLE_SIZE) + names)
        for (word in keepSample) {
            mustKeep.add(word to decide(tapsFor(word), word, index, en))
        }

        println()
        println("=== #295 · auto-commit gate ==============================================")
        println("must be fixed:      ${mustFix.size} mis-typed words (beam had no candidate for $noOpinion)")
        println("must not be touched: ${mustKeep.size} correctly typed words unknown to en.json")
        println()

        sweep(
            mustFix = mustFix,
            mustKeep = mustKeep,
            rules = buildList {
                // The ceiling: what the decoder gets right at all, before any gate. No rule can beat it,
                // and a gate close to it is spending its whole budget on candidates that are wrong anyway.
                add("no gate (ceiling)" to Rule { true })
                // What actually ships, asked of the real gate rather than a copy of its arithmetic.
                for (strength in AutoCorrectStrength.entries) {
                    add(
                        "SHIPPED · $strength" to
                            Rule { AutoCommitGate.allows(it.cost, it.taps, strength) },
                    )
                }
                add("total ≤ 0.8  (was)" to Rule { it.cost <= 0.8f })
                for (t in listOf(1.2f, 2.0f, 3.0f)) {
                    add("total ≤ %.1f".format(t) to Rule { it.cost <= t })
                }
                for (m in listOf(0.10f, 0.15f, 0.20f, 0.25f, 0.30f, 0.35f, 0.40f, 0.50f, 0.70f)) {
                    add("mean  ≤ %.2f per tap".format(m) to Rule { it.cost <= m * it.taps })
                }
                for (b in listOf(0.8f, 1.2f)) {
                    for (s in listOf(0.2f, 0.4f)) {
                        add(
                            "total ≤ %.1f + %.1f·(len−3)".format(b, s) to
                                Rule { it.cost <= b + s * (it.taps - 3).coerceAtLeast(0) },
                        )
                    }
                }
            },
        )

        reportedCases(index, en)

        // The regression guard, on the levels that actually ship. Deliberately loose: the point of this
        // file is the table above, and a tight assertion on a sampled statistic fails for reasons nobody
        // wants to debug. What must hold is the shape — every level protects correctly typed input, and
        // the default earns its loosening by fixing the slips the old rule could not see.
        fun harm(strength: AutoCorrectStrength) = mustKeep.count { (word, d) ->
            d != null && d.word != word && d.freq >= MIN_FREQ && AutoCommitGate.allows(d.cost, d.taps, strength)
        } * 100.0 / mustKeep.size

        fun cleanOneKeyFixed(strength: AutoCorrectStrength): Double {
            val cases = mustFix.filter { it.third == "clean 1" }
            return cases.count { (intended, d, _) ->
                d.word == intended && d.freq >= MIN_FREQ && AutoCommitGate.allows(d.cost, d.taps, strength)
            } * 100.0 / cases.size
        }

        assertTrue(harm(AutoCorrectStrength.CAUTIOUS) < 0.2, "cautious must touch essentially nothing")
        assertTrue(harm(AutoCorrectStrength.BALANCED) < 1.0, "the default must not rewrite correct words")
        assertTrue(harm(AutoCorrectStrength.AGGRESSIVE) < 3.0, "even aggressive has a ceiling")
        // The whole point of #295: the rule this replaced fixed 0.0 % of these.
        assertTrue(
            cleanOneKeyFixed(AutoCorrectStrength.BALANCED) > 40.0,
            "the default must fix ordinary whole-key slips (${cleanOneKeyFixed(AutoCorrectStrength.BALANCED)} %)",
        )
    }

    /**
     * The words from the #295 report, typed dead-centre on the wrong keys exactly as described, next to
     * a handful that must survive. A table of averages is the evidence; this is the part anyone can check.
     */
    private fun reportedCases(index: TouchBeamDecoder.PrefixIndex, freq: Map<String, Int>) {
        val reported = listOf(
            "hkmd" to "home", "eill" to "will", "miuntes" to "minutes",
            "ler" to "let", "knoe" to "know", "rwady" to "ready", "tsenth" to "twenty",
        )
        val leaveAlone = listOf("jannis", "sarahs", "pete", "dads")

        println("%-10s %-10s %-10s %6s %6s   %s".format("typed", "wanted", "decoded", "cost", "mean", "gate"))
        println("-".repeat(64))
        for ((typed, wanted) in reported + leaveAlone.map { it to it }) {
            if (typed in freq) {
                // `suggest()` only enters the correction branch for words it does not know, so the gate
                // never sees these at all — they are safe for a reason that has nothing to do with taps.
                println("%-10s %-10s %-10s %6s %6s   %s".format(typed, wanted, typed, "—", "—", "in dictionary"))
                continue
            }
            val d = decide(tapsFor(typed), typed, index, freq)
            if (d == null) {
                println("%-10s %-10s %-10s %6s %6s   %s".format(typed, wanted, "—", "—", "—", "no candidate"))
                continue
            }
            val mean = d.cost / d.taps
            val verdict = buildString {
                append(if (d.freq >= MIN_FREQ) "" else "below min-freq; ")
                append("shipped=${if (d.cost <= 0.8f) "swap" else "offer only"}")
                append(", mean≤0.30=${if (mean <= 0.30f) "swap" else "offer only"}")
            }
            println("%-10s %-10s %-10s %6.2f %6.2f   %s".format(typed, wanted, d.word, d.cost, mean, verdict))
        }
        println("-".repeat(64))
        println()
    }

    /** Prints every rule against both populations. There is no method that prints only one of them. */
    private fun sweep(
        mustFix: List<Triple<String, Decision, String>>,
        mustKeep: List<Pair<String, Decision?>>,
        rules: List<Pair<String, Rule>>,
    ) {
        val kinds = mustFix.map { it.third }.distinct()
        val totals = kinds.associateWith { k -> mustFix.count { it.third == k } }

        println(
            "%-28s %8s %s %9s %10s".format(
                "rule", "fixed", kinds.joinToString(" ") { "%8s".format(it) }, "→WRONG", "BROKEN",
            ),
        )
        println("-".repeat(28 + 9 + kinds.size * 9 + 21))
        for ((name, rule) in rules) {
            val fixed = mustFix.count { (intended, d, _) ->
                d.word == intended && d.freq >= MIN_FREQ && rule.allows(d)
            }
            val perKind = kinds.joinToString(" ") { k ->
                val hit = mustFix.count { (intended, d, kind) ->
                    kind == k && d.word == intended && d.freq >= MIN_FREQ && rule.allows(d)
                }
                "%7.1f%%".format(hit * 100.0 / (totals[k] ?: 1))
            }
            // A typo swapped for a word that is neither what was typed nor what was meant. Counted
            // separately because it is not a missed fix but a fresh error, and a looser gate buys more
            // of them at the same time as it buys more real fixes.
            val wrong = mustFix.count { (intended, d, _) ->
                d.word != intended && d.freq >= MIN_FREQ && rule.allows(d)
            }
            val broken = mustKeep.count { (word, d) ->
                d != null && d.word != word && d.freq >= MIN_FREQ && rule.allows(d)
            }
            println(
                "%-28s %7.1f%% %s %8.1f%% %9.2f%%".format(
                    name,
                    fixed * 100.0 / mustFix.size,
                    perKind,
                    wrong * 100.0 / mustFix.size,
                    broken * 100.0 / mustKeep.size,
                ),
            )
        }
        println("-".repeat(28 + 9 + kinds.size * 9 + 21))
        println("counts: " + kinds.joinToString(", ") { "$it=${totals[it]}" })
        println()
    }
}
