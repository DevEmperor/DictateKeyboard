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

    // --- issue #239: a dot that only looks like a sentence end ------------------------------------

    test("does not break after a German abbreviation") {
        TranscriptParagraphs.split("wir kaufen Obst und Gemüse usw. Danach fahren wir heim.", 5) shouldBe
            "wir kaufen Obst und Gemüse usw. Danach fahren wir heim."
        TranscriptParagraphs.split("nimm bitte etwas mit wie z.B. Brot und Butter dazu.", 5) shouldBe
            "nimm bitte etwas mit wie z.B. Brot und Butter dazu."
    }

    test("does not break after an English abbreviation") {
        TranscriptParagraphs.split("this has plenty of words in it, e.g. bread and butter here.", 5) shouldBe
            "this has plenty of words in it, e.g. bread and butter here."
        TranscriptParagraphs.split("I had a conversation with Dr. Smith yesterday evening.", 5) shouldBe
            "I had a conversation with Dr. Smith yesterday evening."
    }

    test("abbreviations are matched case-insensitively and through leading punctuation") {
        TranscriptParagraphs.split("das gilt für alle Fälle (usw. und so weiter dann).", 5) shouldBe
            "das gilt für alle Fälle (usw. und so weiter dann)."
        TranscriptParagraphs.split("nimm etwas mit wie Z.B. Brot und Butter dazu.", 5) shouldBe
            "nimm etwas mit wie Z.B. Brot und Butter dazu."
    }

    test("does not break after an ordinal or a date") {
        TranscriptParagraphs.split("das Treffen ist am 29. Juli 2026 im großen Saal.", 5) shouldBe
            "das Treffen ist am 29. Juli 2026 im großen Saal."
        TranscriptParagraphs.split("er kam auf dem 3. Platz ins Ziel gestern.", 5) shouldBe
            "er kam auf dem 3. Platz ins Ziel gestern."
    }

    test("does not break between initials") {
        TranscriptParagraphs.split("we all read the famous author J. R. R. Tolkien last year.", 5) shouldBe
            "we all read the famous author J. R. R. Tolkien last year."
    }

    test("a lower-case single letter still ends a sentence") {
        // Guards the fixture style used above: only capitals are treated as initials.
        TranscriptParagraphs.split("a b c. d e f. g h i.", 3) shouldBe "a b c.\n\nd e f.\n\ng h i."
    }

    test("still breaks after a normal word that happens to follow an abbreviation") {
        TranscriptParagraphs.split("wir kaufen Obst usw. Danach fahren wir heim. Und dann schlafen wir.", 5) shouldBe
            "wir kaufen Obst usw. Danach fahren wir heim.\n\nUnd dann schlafen wir."
    }

    test("an ellipsis after an abbreviation is still a sentence end") {
        // Only a lone dot can be an abbreviation; a run of enders always breaks.
        TranscriptParagraphs.split("wir kaufen Obst und Gemüse usw... Danach fahren wir heim.", 5) shouldBe
            "wir kaufen Obst und Gemüse usw...\n\nDanach fahren wir heim."
    }

    test("blank or tiny input is returned unchanged") {
        TranscriptParagraphs.split("", 3) shouldBe ""
        TranscriptParagraphs.split(".", 3) shouldBe "."
    }
})
