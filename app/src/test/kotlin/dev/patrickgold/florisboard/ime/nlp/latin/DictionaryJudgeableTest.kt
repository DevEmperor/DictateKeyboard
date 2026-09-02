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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the rule that keeps autocorrect away from words carrying a digit (issue #309).
 *
 * The report was that typing a letter and two digits loses the first digit — `top10` arriving as `top0`.
 * The digit is not what breaks; the *word* is. ICU keeps `top1` in one piece, so that is what the
 * suggestion engine is handed, and `top` is one deletion away from it and frequent enough to be swapped
 * in without asking. Typing the second digit ends the word and collects the swap.
 *
 * Two halves here, and the second is the one that matters in a year:
 *  - the predicate itself, which is small enough to read;
 *  - the shipped English dictionary, asked whether the trap is really there. A predicate test alone would
 *    keep passing if the dictionary ever stopped containing the words that make this dangerous, and would
 *    then be guarding nothing while still reporting green.
 */
class DictionaryJudgeableTest {

    private fun judgeable(word: String) = LatinLanguageProvider.isDictionaryJudgeable(word)

    // ── The rule ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a word carrying a digit is not judged`() {
        // Versions, model numbers, codes. All of them deliberate, none of them misspellings.
        assertFalse(judgeable("top1"))
        assertFalse(judgeable("covid19"))
        assertFalse(judgeable("mp3"))
        assertFalse(judgeable("3d"))
        assertFalse(judgeable("a1b2"))
    }

    @Test
    fun `digits count wherever they are written`() {
        // Char.isDigit is Unicode-wide, so the eastern digits the Arabic subtype types are covered too —
        // and they have to be, because DictFold maps them onto ASCII before any lookup happens.
        assertFalse(judgeable("موديل٣"))
        assertFalse(judgeable("स्तर२"))
    }

    @Test
    fun `a plain number is not judged either`() {
        // Already refused by spell()'s length rule at one character; from two on, this is what refuses it.
        assertFalse(judgeable("12"))
        assertFalse(judgeable("2026"))
    }

    @Test
    fun `ordinary words still are`() {
        assertTrue(judgeable("top"))
        assertTrue(judgeable("teh"))
        assertTrue(judgeable("Straße"))
        assertTrue(judgeable("hôte"))
        assertTrue(judgeable("أن"))
    }

    @Test
    fun `an apostrophe is part of the word, not a digit`() {
        // dont → don't is a fix worth making (issue #212), so the rule is about digits and nothing else.
        assertTrue(judgeable("don't"))
        assertTrue(judgeable("I'm"))
    }

    // ── The trap this rule exists to disarm ──────────────────────────────────────────────────────

    @Test
    fun `every one of these would have lost its digit`() {
        val freq = readDict("en.json")
        // Deliberately words anybody might type. For each, deleting the trailing digit — one of the edits
        // the corrector generates — lands on a dictionary word frequent enough to be committed silently,
        // because nothing in the dictionary begins with the digit form and the classic gate then allows it.
        for (typed in listOf("top1", "day1", "test1", "covid1", "page2", "level1", "book1", "part2", "team1", "time1")) {
            val base = typed.dropLast(1)
            val f = freq[base]
            assertTrue(f != null && f >= MIN_FREQ, "$base is no longer a frequent dictionary word (freq=$f)")
            assertFalse(judgeable(typed), "$typed must never be judged against the dictionary")
        }
    }

    private companion object {
        /** `AUTOCORRECT_MIN_FREQ`: what a correction needs before it may be swapped in without asking. */
        const val MIN_FREQ = 170

        /**
         * The shipped dictionary, found by walking up from wherever the test was started — the working
         * directory is the module under Gradle and the repository root in some IDE run configurations.
         * Same approach as [AutocorrectEvalTest], which measures against the same file.
         */
        fun dictFile(name: String): File {
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
    }
}
