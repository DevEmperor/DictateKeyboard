/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class TranscriptParagraphsTest : FunSpec({

    test("minWords <= 0 is off and returns the text unchanged") {
        val text = "First sentence. Second sentence. Third sentence."
        TranscriptParagraphs.split(text, 0) shouldBe text
        TranscriptParagraphs.split(text, -5) shouldBe text
    }

    test("breaks at the next sentence end once the word threshold is reached") {
        // "one two three." = 3 words, threshold 3 → break after it, before "Next".
        TranscriptParagraphs.split("one two three. Next sentence here.", 3) shouldBe
            "one two three.\n\nNext sentence here."
    }

    test("never breaks mid-sentence — waits for the next sentence end") {
        // Threshold reached mid-sentence, but the break only happens at the following period.
        TranscriptParagraphs.split("alpha beta gamma delta epsilon zeta. done.", 3) shouldBe
            "alpha beta gamma delta epsilon zeta.\n\ndone."
    }

    test("never breaks at the very end of the text") {
        TranscriptParagraphs.split("one two three.", 3) shouldBe "one two three."
        TranscriptParagraphs.split("one two three.   ", 3) shouldBe "one two three.   "
    }

    test("an existing newline restarts the word count") {
        // The newline resets the counter, so the two words after it don't reach the threshold of 3.
        val text = "one two three\nfour five. six."
        TranscriptParagraphs.split(text, 3) shouldBe text
    }

    test("contractions and hyphenated words count as a single word") {
        // "I don't well-known." = 3 words (I / don't / well-known) → break after it.
        TranscriptParagraphs.split("I don't well-known. yes it is.", 3) shouldBe
            "I don't well-known.\n\nyes it is."
    }

    test("does not break inside a decimal or abbreviation (no space after the dot)") {
        TranscriptParagraphs.split("it costs 3.14 dollars today. thanks.", 3) shouldNotContain "3.\n\n14"
        TranscriptParagraphs.split("see e.g. this example here. ok.", 3) shouldNotContain "e.\n\ng"
    }

    test("consumes trailing quotes/brackets and the whole punctuation run before breaking") {
        TranscriptParagraphs.split("he said \"go home now!\" then left today.", 4) shouldBe
            "he said \"go home now!\"\n\nthen left today."
        TranscriptParagraphs.split("wait for it... here it comes now.", 3) shouldBe
            "wait for it...\n\nhere it comes now."
    }

    test("multiple breaks across a long transcript") {
        val text = "a b c. d e f. g h i."
        // threshold 3: break after each 3-word sentence except the last.
        TranscriptParagraphs.split(text, 3) shouldBe "a b c.\n\nd e f.\n\ng h i."
    }

    test("blank or tiny input is returned unchanged") {
        TranscriptParagraphs.split("", 3) shouldBe ""
        TranscriptParagraphs.split(".", 3) shouldBe "."
    }
})
