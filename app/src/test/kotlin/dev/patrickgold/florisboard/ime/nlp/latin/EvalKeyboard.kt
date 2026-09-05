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
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The scaffolding both measuring stands share: a synthetic keyboard, the shipped dictionaries, and the
 * ways a finger goes wrong.
 *
 * It is scaffolding on purpose and nothing else. Every rule actually under measurement — the beam, the
 * scoring, the auto-commit gate, the learning gate — is called here from the *shipping* object, never
 * reimplemented. That distinction is the whole reason this file exists: the numbers behind #242 came
 * from a throwaway Python reimplementation, and a reimplementation is a copy that drifts. What may be
 * duplicated is a fake QWERTY; what may not is a decision.
 *
 * Used by [AutocorrectEvalTest] (may a correction be swapped in unasked?) and [LearningEvalTest] (may a
 * word be remembered?). The two ask opposite questions of the same evidence, which is exactly why they
 * must ask it of the same fixture.
 */
internal object EvalKeyboard {

    // ── Keyboard geometry ────────────────────────────────────────────────────────────────────────
    // In key-width units. Note that KeyProximityInfo.normalize divides *both* axes by the key width, so
    // the row pitch is greater than 1: a key one row up is further away than a key one column across,
    // and any rule expressed in these units has to live with that.

    const val ROW_HEIGHT = 1.15f
    val ROWS = listOf("qwertyuiop" to 0.0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)

    /** The dictionary frequency a correction needs before it may be auto-committed. */
    const val MIN_FREQ = AutoCommitGate.MIN_FREQ

    /** How many words the beam returns before scoring, mirroring `BEAM_CANDIDATES`. */
    const val BEAM_CANDIDATES = 12

    val layout: KeyProximityInfo.Layout = run {
        val codes = ArrayList<Int>()
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        ROWS.forEachIndexed { rowIndex, (row, offset) ->
            row.forEachIndexed { i, ch ->
                codes.add(ch.code)
                xs.add(offset + i)
                ys.add(rowIndex * ROW_HEIGHT)
            }
        }
        KeyProximityInfo.Layout(codes.toIntArray(), xs.toFloatArray(), ys.toFloatArray())
    }

    val centres: Map<Char, Pair<Float, Float>> = buildMap {
        ROWS.forEachIndexed { rowIndex, (row, offset) ->
            row.forEachIndexed { i, ch -> put(ch, (offset + i) to (rowIndex * ROW_HEIGHT)) }
        }
    }

    /** Keys within 1.3 key-widths — the ones a finger realistically lands on by mistake. */
    val neighbours: Map<Char, List<Char>> = centres.keys.associateWith { ch ->
        val (x, y) = centres.getValue(ch)
        centres.entries
            .filter { (other, p) ->
                other != ch && sqrt((p.first - x) * (p.first - x) + (p.second - y) * (p.second - y)) <= 1.3f
            }
            .map { it.key }
    }

    // ── Dictionaries ─────────────────────────────────────────────────────────────────────────────

    fun dictFile(name: String): File {
        // The working directory of a unit test is the module, but do not rely on it: walk up until the
        // assets are found, so this also runs from the repository root or an IDE run configuration.
        var dir = File(".").absoluteFile
        repeat(5) {
            val candidate = File(dir, "app/src/main/assets/ime/dict/$name")
            if (candidate.isFile) return candidate
            val here = File(dir, "src/main/assets/ime/dict/$name")
            if (here.isFile) return here
            dir = dir.parentFile ?: return@repeat
        }
        error("could not locate ime/dict/$name — run from the repository or the app module")
    }

    /** `{"word": 231, …}` into a map. Hand-parsed to keep the test off the serialization runtime. */
    fun readDict(name: String): Map<String, Int> {
        val text = dictFile(name).readText()
        val out = HashMap<String, Int>(70_000)
        var i = 0
        while (i < text.length) {
            val keyStart = text.indexOf('"', i)
            if (keyStart < 0) break
            val keyEnd = text.indexOf('"', keyStart + 1)
            if (keyEnd < 0) break
            val colon = text.indexOf(':', keyEnd + 1)
            if (colon < 0) break
            var end = colon + 1
            while (end < text.length && text[end] != ',' && text[end] != '}') end++
            val value = text.substring(colon + 1, end).trim().toIntOrNull()
            if (value != null) out[text.substring(keyStart + 1, keyEnd)] = value
            i = end + 1
        }
        return out
    }

    /** Only words the synthetic layout can actually produce: plain a–z, at least three long. */
    fun typeable(word: String): Boolean =
        word.length >= 3 && word.all { it in centres }

    // ── Tap generation ───────────────────────────────────────────────────────────────────────────

    /** Dead-centre on every key: what a careful typist produces, and what a known word looks like. */
    fun tapsFor(word: String): FloatArray {
        val out = FloatArray(word.length * 2)
        word.forEachIndexed { i, ch ->
            val (x, y) = centres.getValue(ch)
            out[i * 2] = x
            out[i * 2 + 1] = y
        }
        return out
    }

    /** The case #295 is about: the finger lands dead-centre, on the wrong key. */
    fun cleanNeighbourSlip(word: String, rng: Random, slips: Int): Pair<String, FloatArray>? {
        val chars = word.toCharArray()
        val positions = word.indices.shuffled(rng).take(slips)
        for (p in positions) {
            val options = neighbours[chars[p]].orEmpty()
            if (options.isEmpty()) return null
            chars[p] = options[rng.nextInt(options.size)]
        }
        val typed = String(chars)
        return if (typed == word) null else typed to tapsFor(typed)
    }

    /** The case #242 measured: the finger is off-centre enough to land on the neighbouring key. */
    fun noisySlip(word: String, rng: Random, sigma: Float): Pair<String, FloatArray>? {
        val taps = FloatArray(word.length * 2)
        val typed = StringBuilder(word.length)
        word.forEachIndexed { i, ch ->
            val (cx, cy) = centres.getValue(ch)
            val x = cx + (rng.nextDouble() * 2 - 1).toFloat() * sigma
            val y = cy + (rng.nextDouble() * 2 - 1).toFloat() * sigma
            taps[i * 2] = x
            taps[i * 2 + 1] = y
            // What the keyboard would have resolved this tap to.
            var best = ch
            var bestDist = Float.MAX_VALUE
            for ((c, p) in centres) {
                val d = (p.first - x) * (p.first - x) + (p.second - y) * (p.second - y)
                if (d < bestDist) {
                    bestDist = d
                    best = c
                }
            }
            typed.append(best)
        }
        val result = typed.toString()
        return if (result == word) null else result to taps
    }

    /** Two adjacent characters swapped: the fingers were right, the order was not. */
    fun transposition(word: String, rng: Random): Pair<String, FloatArray>? {
        if (word.length < 4) return null
        val at = rng.nextInt(word.length - 1)
        if (word[at] == word[at + 1]) return null
        val chars = word.toCharArray()
        val t = chars[at]; chars[at] = chars[at + 1]; chars[at + 1] = t
        val typed = String(chars)
        return typed to tapsFor(typed)
    }

    // ── The decoder under test ───────────────────────────────────────────────────────────────────

    data class Decision(val word: String, val cost: Float, val freq: Int, val taps: Int)

    /** What the beam decides, scored exactly as the provider scores it. Null when it has no opinion. */
    fun decide(
        taps: FloatArray,
        typed: String,
        index: TouchBeamDecoder.PrefixIndex,
        freq: Map<String, Int>,
    ): Decision? {
        val beam = TouchBeamDecoder.decode(taps, typed, index, layout, BEAM_CANDIDATES)
        if (beam.isEmpty()) return null
        var bestWord: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var bestCost = 0f
        for (candidate in beam) {
            val f = freq[candidate.word] ?: continue
            val score = TouchScoring.score(f, candidate.cost, 0.0)
            if (score > bestScore) {
                bestScore = score
                bestWord = candidate.word
                bestCost = candidate.cost
            }
        }
        val word = bestWord ?: return null
        return Decision(word = word, cost = bestCost, freq = freq[word] ?: 0, taps = typed.length)
    }
}
