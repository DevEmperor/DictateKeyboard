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
 * Covers the beam decoder (issue #242). The interesting failure modes are all in the range narrowing —
 * off-by-one binary-search bounds and the packed `(lo, hi)` long — and in whether a tap that drifts towards
 * a neighbouring key actually changes the decoded word.
 */
class TouchBeamDecoderTest {

    // A miniature QWERTY in key-width units, matching how KeyProximityInfo normalizes a real layout.
    private companion object {
        const val ROW_HEIGHT = 1.15f
        val ROWS = listOf("qwertyuiop" to 0.0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)

        val LAYOUT: KeyProximityInfo.Layout = run {
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

        val WORDS = arrayOf("car", "cart", "cat", "cats", "held", "hello", "help", "jello")
        val INDEX = TouchBeamDecoder.PrefixIndex(WORDS)

        fun centreOf(ch: Char): Pair<Float, Float> {
            ROWS.forEachIndexed { rowIndex, (row, offset) ->
                val i = row.indexOf(ch)
                if (i >= 0) return (offset + i) to (rowIndex * ROW_HEIGHT)
            }
            error("no key for '$ch'")
        }

        /** Taps dead-centre on every character of [word]. */
        fun tapsFor(word: String): FloatArray {
            val out = FloatArray(word.length * 2)
            word.forEachIndexed { i, ch ->
                val (x, y) = centreOf(ch)
                out[i * 2] = x
                out[i * 2 + 1] = y
            }
            return out
        }

        fun decode(points: FloatArray, typed: String, maxResults: Int = 5) =
            TouchBeamDecoder.decode(points, typed, INDEX, LAYOUT, maxResults)
    }

    @Test
    fun narrowSelectsTheContiguousRangeForAPrefix() {
        // "car", "cart" share "car"; "cat", "cats" share "cat".
        val all = INDEX.narrow(0, WORDS.size, depth = 0, ch = 'c')
        assertEquals(0, (all ushr 32).toInt())
        assertEquals(4, (all and 0xFFFFFFFFL).toInt())

        val car = INDEX.narrow(0, 4, depth = 2, ch = 'r')
        assertEquals(0, (car ushr 32).toInt())
        assertEquals(2, (car and 0xFFFFFFFFL).toInt())

        val cat = INDEX.narrow(0, 4, depth = 2, ch = 't')
        assertEquals(2, (cat ushr 32).toInt())
        assertEquals(4, (cat and 0xFFFFFFFFL).toInt())
    }

    @Test
    fun narrowReportsNoMatchInsteadOfAnEmptyRange() {
        assertEquals(-1L, INDEX.narrow(0, WORDS.size, depth = 0, ch = 'z'))
        // "car" has no fourth character, so asking past its end must not match it.
        assertEquals(-1L, INDEX.narrow(0, 1, depth = 3, ch = 'x'))
    }

    @Test
    fun narrowSkipsWordsThatEndBeforeTheQueriedDepth() {
        // Within "cat", "cats" the shorter entry sorts first and has no character at depth 3.
        val packed = INDEX.narrow(2, 4, depth = 3, ch = 's')
        assertEquals(3, (packed ushr 32).toInt())
        assertEquals(4, (packed and 0xFFFFFFFFL).toInt())
    }

    @Test
    fun cleanTapsDecodeToTheTypedWord() {
        val result = decode(tapsFor("hello"), "hello")
        assertTrue(result.isNotEmpty(), "expected at least one candidate")
        assertEquals("hello", result.first().word)
        assertEquals(0.0f, result.first().cost, 1e-4f)
    }

    @Test
    fun aTapDriftingTowardsANeighbourDecodesToTheNeighbourWord() {
        // Start from "hello" but place the first tap almost on top of "j", which turns it into "jello".
        val points = tapsFor("hello")
        val (jx, jy) = centreOf('j')
        points[0] = jx
        points[1] = jy

        val result = decode(points, "hello")
        assertEquals("jello", result.first().word)
    }

    @Test
    fun aTapBetweenTwoKeysKeepsBothWordsButPrefersTheCloserOne() {
        val points = tapsFor("hello")
        val (hx, hy) = centreOf('h')
        val (jx, jy) = centreOf('j')
        // Slightly closer to 'h' than to 'j'.
        points[0] = hx + (jx - hx) * 0.45f
        points[1] = hy + (jy - hy) * 0.45f

        val result = decode(points, "hello")
        val words = result.map { it.word }
        assertTrue(words.contains("hello") && words.contains("jello"), "both readings should survive: $words")
        // The winning reading is by definition free: cost is the excess over the *nearest* key, and 'h' is
        // still the nearest. What the coordinate buys is how cheap the runner-up is.
        assertEquals("hello", result.first().word)
        assertEquals(0.0f, result.first().cost, 1e-4f)
        assertTrue(result.first().cost < result[1].cost)
    }

    @Test
    fun theCloserATapDriftsTheCheaperTheAlternativeReadingBecomes() {
        // This is the whole point of decoding from coordinates rather than from the resolved string: two
        // taps that both resolve to 'h' must not look identical to the decoder.
        fun jelloCostAt(fraction: Float): Float {
            val points = tapsFor("hello")
            val (hx, hy) = centreOf('h')
            val (jx, jy) = centreOf('j')
            points[0] = hx + (jx - hx) * fraction
            points[1] = hy + (jy - hy) * fraction
            return decode(points, "hello").first { it.word == "jello" }.cost
        }

        val deadCentre = jelloCostAt(0.0f)
        val drifting = jelloCostAt(0.45f)
        assertTrue(
            drifting < deadCentre,
            "a tap drifting towards 'j' must make \"jello\" cheaper ($drifting vs $deadCentre)",
        )
    }

    @Test
    fun deliberatelyChosenCharactersAreNotSecondGuessed() {
        // NaN marks a character picked from the long-press popup: only the typed character is allowed, so
        // even coordinates that would otherwise favour "jello" must still yield "hello".
        val points = tapsFor("hello")
        points[0] = Float.NaN
        points[1] = Float.NaN

        val result = decode(points, "hello")
        assertEquals(listOf("hello"), result.map { it.word })
        assertEquals(0.0f, result.first().cost, 1e-4f)
    }

    @Test
    fun noDictionaryWordOfThatLengthYieldsNothing() {
        // "hel" is a prefix of real words but is not itself one.
        assertTrue(decode(tapsFor("hel"), "hel").isEmpty())
    }

    @Test
    fun candidatesComeBackCheapestFirstAndAreCapped() {
        val points = tapsFor("cat")
        val result = decode(points, "cat", maxResults = 2)
        assertTrue(result.size <= 2)
        for (i in 1 until result.size) {
            assertTrue(result[i - 1].cost <= result[i].cost, "candidates must be ordered by cost")
        }
    }

    @Test
    fun mismatchedInputIsRejectedRatherThanDecodedWrongly() {
        // typed and points must describe the same number of characters
        assertTrue(decode(tapsFor("hello"), "help").isEmpty())
        assertTrue(TouchBeamDecoder.decode(FloatArray(0), "", INDEX, LAYOUT, 5).isEmpty())
    }
}
