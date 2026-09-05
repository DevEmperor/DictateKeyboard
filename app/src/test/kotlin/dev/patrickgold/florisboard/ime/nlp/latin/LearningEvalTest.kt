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

/**
 * A measuring stand for the entry test of word learning (issue #318).
 *
 * ### Why this exists
 *
 * Learning words is a *permissiveness* change: every word remembered is a word autocorrect will
 * eventually stop repairing. Measured only against the words we want it to learn, any rule looks
 * excellent — including "learn everything the dictionary does not know", which also learns every typo.
 * So the rule is only ever interesting as a **pair** of numbers, and [sweep] prints them together and
 * never separately. That is the same discipline [AutocorrectEvalTest] enforces for the auto-commit gate,
 * for the same reason, and this file shares its fixture ([EvalKeyboard]) so both ask their opposite
 * questions of identical evidence.
 *
 * ### The two populations
 *
 *  - **must be learned** — German words absent from the English dictionary, plus a handful of first
 *    names, all typed dead-centre. They stand in for names, foreign words and jargon: the user knows
 *    exactly what they wrote, and the dictionary has never heard of it. This is the entire point of the
 *    feature.
 *  - **must not be learned** — mis-typed English words that are likewise unknown to the dictionary.
 *    Every one of them would otherwise become a permanent entry that autocorrect stops fixing.
 *
 * Both populations are unknown to the dictionary by construction, so the *only* thing that can separate
 * them is where the fingers landed — which is exactly what [WordLearningGate.correctionLooksLikeASlip]
 * asks, using the same [AutoCommitGate] constants that decide whether a correction may be applied
 * unasked. A keyboard that would silently fix a word must not, in the same instant, remember it.
 *
 * ### What it deliberately leaves out
 *
 * The rest of [WordLearningGate.shouldLearn] — the field, the origin, the sentence position. Those are
 * bookkeeping with exact answers and belong in [WordLearningGateTest]; mixing them in here would only
 * blur which lever moved the numbers.
 */
class LearningEvalTest {

    private companion object {
        const val SAMPLE_SIZE = 1500
        const val SEED = 20260905L
    }

    /** What the beam had to say about one word of either population. */
    private data class Case(val typed: String, val decision: EvalKeyboard.Decision?, val kind: String)

    /** A candidate entry rule, expressed as the slip test it applies. */
    private fun interface Rule {
        fun learns(case: Case): Boolean
    }

    /** The rule as it ships, asked of the real object rather than of a copy of its arithmetic. */
    private val shipped = Rule { case ->
        !WordLearningGate.looksLikeASlip(
            typedWord = case.typed,
            hadTapEvidence = true,
            beamCorrection = case.decision?.word,
            beamCost = case.decision?.cost,
            nearestKnownFreq = nearest(case),
        )
    }

    /** The same, with the tap evidence removed: what a physical keyboard is judged on. */
    private val shippedWithoutTaps = Rule { case ->
        !WordLearningGate.looksLikeASlip(
            typedWord = case.typed,
            hadTapEvidence = false,
            beamCorrection = null,
            beamCost = null,
            nearestKnownFreq = nearest(case),
        )
    }

    /** The most common dictionary word one edit from this one — the non-spatial witness. */
    private lateinit var nearestOf: (String) -> Int

    private fun nearest(case: Case): Int = nearestOf(case.typed)

    /**
     * Whether the beam read the taps as some other dictionary word at all, at any tap cost.
     *
     * Deliberately without the [AutoCommitGate.MIN_FREQ] filter that guards *applying* a correction.
     * Those are different questions: a rare word is too rare to swap in unasked, but it is still
     * evidence that the letters under those fingers spell something the dictionary knows. Requiring the
     * frequency here measured 31 % of clean one-key slips learned instead of 0 %.
     */
    private fun beamHasCorrection(case: Case): Boolean {
        val d = case.decision ?: return false
        return !d.word.equals(case.typed, ignoreCase = true)
    }

    /** The beam read these taps as another word, and cheaply enough to call the difference a slip. */
    private fun beamSlip(case: Case, budgetPerTap: Float): Boolean {
        val d = case.decision ?: return false
        if (d.word.equals(case.typed, ignoreCase = true)) return false
        return d.cost <= budgetPerTap * case.typed.length
    }

    @Test
    fun measureTheLearningGateAgainstBothPopulations() {
        val en = EvalKeyboard.readDict("en.json").filterKeys { EvalKeyboard.typeable(it) }
        val de = EvalKeyboard.readDict("de.json").filterKeys { EvalKeyboard.typeable(it) }
        val index = TouchBeamDecoder.PrefixIndex(en.keys.sorted().toTypedArray())
        val alphabet = EvalKeyboard.centres.keys
        val nearestCache = HashMap<String, Int>()
        nearestOf = { word ->
            nearestCache.getOrPut(word) { EditDistance.nearestKnownFrequency(word, alphabet, en) }
        }

        val rng = Random(SEED)
        val pool = en.entries.filter { it.value >= 150 }.map { it.key }
        val corpus = List(SAMPLE_SIZE) { pool[rng.nextInt(pool.size)] }

        // Population 1 — must be learned. Correctly typed, unknown to the dictionary judging them.
        //
        // Split by length, because that is where an edit-distance blocker does its damage: a nine-letter
        // German noun has almost no one-edit neighbours in an English dictionary, while a four-letter
        // name has dozens. Reporting only the first would make any threshold look free.
        val names = listOf(
            "jannis", "sarahs", "pete", "dads", "linus", "mira", "tobi", "nadja", "lars", "svenja",
            "kata", "elif", "yara", "nils", "rune", "vera", "iben", "silke", "malte", "ronja",
        ).filter { it !in en }
        val unknownToEn = de.keys.filter { it !in en }.sorted()
        fun caseOf(word: String, kind: String) =
            Case(word, EvalKeyboard.decide(EvalKeyboard.tapsFor(word), word, index, en), kind)
        // Length is the split that matters. In a 48 k dictionary a four-letter word has dozens of
        // one-edit neighbours and a nine-letter word has almost none, so an edit-distance blocker is
        // nearly free on the second and nearly total on the first. A single averaged column would report
        // the cheap half and hide the expensive one — and the expensive half is where the names are.
        val short = unknownToEn.filter { it.length in 3..5 }.shuffled(Random(SEED)).take(SAMPLE_SIZE / 2)
        val long = unknownToEn.filter { it.length >= 6 }.shuffled(Random(SEED)).take(SAMPLE_SIZE)
        val mustLearn = long.map { caseOf(it, "long") } +
            short.map { caseOf(it, "short") } +
            names.map { caseOf(it, "short") }

        // Population 2 — must not be learned. The same slips [AutocorrectEvalTest] measures, kept apart
        // by kind: they are not equally easy to recognise, and one average would hide that.
        val mustNotLearn = ArrayList<Case>()
        for (word in corpus) {
            val generators = listOf(
                "clean 1" to { EvalKeyboard.cleanNeighbourSlip(word, rng, slips = 1) },
                "clean 2" to { EvalKeyboard.cleanNeighbourSlip(word, rng, slips = 2) },
                "noisy" to { EvalKeyboard.noisySlip(word, rng, sigma = 0.55f) },
                "transpose" to { EvalKeyboard.transposition(word, rng) },
            )
            for ((kind, generate) in generators) {
                val (typed, taps) = generate() ?: continue
                // A typo that lands on a real word is never offered to the learner in the first place:
                // `shouldLearn` refuses anything the dictionary already knows.
                if (en.containsKey(typed)) continue
                if (!WordLearningGate.isLearnableForm(typed)) continue
                mustNotLearn.add(Case(typed, EvalKeyboard.decide(taps, typed, index, en), kind))
            }
        }

        println()
        println("=== #318 · word-learning entry test =====================================")
        println("must be learned:     ${mustLearn.size} correctly typed words unknown to en.json")
        println("must not be learned: ${mustNotLearn.size} mis-typed words unknown to en.json")
        println()

        sweep(
            mustLearn = mustLearn,
            mustNotLearn = mustNotLearn,
            rules = buildList {
                // The floor: remember anything the dictionary does not know. This is what a learner
                // without tap evidence can do, and the second column is why we do not ship it.
                add("no gate (learn all unknown)" to Rule { true })
                // "A correction exists at all", ignoring how far the fingers would have had to be off.
                // The intuitive rule, and the one that refuses to learn names.
                add(
                    "any correction blocks" to Rule { case ->
                        val d = case.decision
                        d == null || d.word.equals(case.typed, ignoreCase = true)
                    },
                )
                add("SHIPPED" to shipped)
                // What a physical keyboard gets: no taps, so only the edit-distance witness speaks.
                // Measured because DeX and Bluetooth keyboards take this path (issue #312).
                add("SHIPPED · no taps (hardware)" to shippedWithoutTaps)
                // For reference, what the auto-commit budgets would have done had they been reused here.
                // Kept in the table because "use the gate we already have" is the obvious first idea, and
                // this is the row that shows why it is wrong.
                for (strength in AutoCorrectStrength.entries) {
                    add(
                        "auto-commit budget · $strength" to Rule { case ->
                            val d = case.decision
                            d == null || d.word.equals(case.typed, ignoreCase = true) ||
                                d.freq < AutoCommitGate.MIN_FREQ ||
                                !AutoCommitGate.allows(d.cost, case.typed.length, strength)
                        },
                    )
                }
                // The tap evidence is blind to a transposition by construction — both keys were hit
                // dead-centre, only in the wrong order — so a second, non-spatial witness is needed. These
                // measure it alone and combined.
                for (t in listOf(128, 150, AutoCommitGate.MIN_FREQ, 200, 220)) {
                    add("edits1 near ≥ $t" to Rule { case -> nearest(case) < t })
                }
                for (t in listOf(128, 150, AutoCommitGate.MIN_FREQ, 200)) {
                    add(
                        "beam-any OR edits1 ≥ $t" to Rule { case ->
                            !beamHasCorrection(case) && nearest(case) < t
                        },
                    )
                }
                // The real knob. "Any correction blocks" is not a gate at all: a word the dictionary does
                // not know can never be decoded as itself, so the beam always answers with *something*,
                // and for a short word that something is always close by. What separates a name from a
                // slip is how far the fingers would have had to be off — the same quantity the
                // auto-commit gate uses, but with a larger budget, because here we want to be suspicious
                // rather than sure.
                for (b in listOf(0.5f, 0.8f, 1.2f, 1.6f, 2.0f, 3.0f)) {
                    add("beam ≤ %.1f/tap".format(b) to Rule { case -> !beamSlip(case, b) })
                }
                // …and the same, plus the edit-distance witness for the transpositions the taps cannot
                // see, applied only from a given length up: short words live in a neighbourhood so dense
                // that "one edit from something common" is true of nearly every string and says nothing.
                for (b in listOf(0.3f, 0.4f, 0.5f, 0.6f, 0.8f, 1.2f)) {
                    for (len in listOf(5, 6, 7)) {
                        add(
                            "beam ≤ %.1f + e1 len≥%d".format(b, len) to Rule { case ->
                                !beamSlip(case, b) && (case.typed.length < len || nearest(case) < 150)
                            },
                        )
                    }
                }
            },
        )

        // The shape that has to hold. Loose bounds on purpose: the table above is the evidence, and a
        // tight assertion on a sampled statistic fails for reasons nobody wants to debug at 2 a.m. What
        // must be true is that the feature works at all — it learns the words it exists for — and that
        // it is not a typo pump.
        fun pct(cases: List<Case>, rule: Rule) = cases.count { rule.learns(it) } * 100.0 / cases.size

        val learnedLong = pct(mustLearn.filter { it.kind == "long" }, shipped)
        val learnedShort = pct(mustLearn.filter { it.kind == "short" }, shipped)
        val typosKept = pct(mustNotLearn, shipped)

        assertTrue(learnedLong > 85.0, "long unknown words must be learned ($learnedLong %)")
        // The lower bar is not a lower ambition: a short word genuinely is harder to tell from a slip,
        // and the honest answer is that some names need the long-press (issue #241) instead.
        assertTrue(learnedShort > 50.0, "short unknown words — the names — must mostly be learned ($learnedShort %)")
        assertTrue(typosKept < 15.0, "the entry test must reject the great majority of typos ($typosKept %)")
        // And whatever does slip through still faces the ladder: three sightings before a word can
        // protect itself from correction, one sighting doing nothing at all.
        assertTrue(
            WordLearningGate.stageOf(1.0) == WordLearningGate.Stage.REMEMBERED,
            "one sighting must do nothing at all",
        )
    }

    /**
     * Prints every rule against both populations. There is no method here that prints only one of them —
     * that omission is the mistake this file exists to prevent.
     */
    private fun sweep(
        mustLearn: List<Case>,
        mustNotLearn: List<Case>,
        rules: List<Pair<String, Rule>>,
    ) {
        val kinds = mustNotLearn.map { it.kind }.distinct()
        val totals = kinds.associateWith { k -> mustNotLearn.count { it.kind == k } }
        val learnKinds = mustLearn.map { it.kind }.distinct()
        val learnTotals = learnKinds.associateWith { k -> mustLearn.count { it.kind == k } }

        val width = 28 + learnKinds.size * 10 + 11 + kinds.size * 10 + 13
        println(
            "%-28s %s %10s %s %12s".format(
                "rule",
                learnKinds.joinToString(" ") { "%9s".format("learn $it") },
                "LEARNED",
                kinds.joinToString(" ") { "%9s".format(it) },
                "typos kept",
            ),
        )
        println("-".repeat(width))
        for ((name, rule) in rules) {
            val perLearnKind = learnKinds.joinToString(" ") { k ->
                val got = mustLearn.count { it.kind == k && rule.learns(it) }
                "%8.1f%%".format(got * 100.0 / (learnTotals[k] ?: 1))
            }
            val learned = mustLearn.count { rule.learns(it) } * 100.0 / mustLearn.size
            val perKind = kinds.joinToString(" ") { k ->
                val kept = mustNotLearn.count { it.kind == k && rule.learns(it) }
                "%8.1f%%".format(kept * 100.0 / (totals[k] ?: 1))
            }
            val keptAll = mustNotLearn.count { rule.learns(it) } * 100.0 / mustNotLearn.size
            println("%-28s %s %9.1f%% %s %11.1f%%".format(name, perLearnKind, learned, perKind, keptAll))
        }
        println("-".repeat(width))
        println(
            "counts: " + (learnKinds.map { "learn $it=${learnTotals[it]}" } +
                kinds.map { "$it=${totals[it]}" }).joinToString(", "),
        )
        println()
        println("LEARNED = words we want remembered that are; typos kept = words we do NOT want remembered that are.")
        println()
    }
}
