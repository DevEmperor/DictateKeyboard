/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.field

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

/**
 * The case of a dictation's first letter against the text in front of the cursor (#476). The dictionary
 * stands in for the keyboard's: it writes these words small, and knows nothing else — so `Bahnhof`,
 * `Tuesday` and `I` are, as far as it can tell, written with a capital or unknown, and keep it.
 */
class SeamCaseTest : FunSpec({

    val small = setOf("again", "gestern", "a", "the", "und", "morgen", "don't", "well-known", "danach", "später")
    val writtenSmall: (String) -> Boolean = { it.lowercase() in small }

    /** The dictation [piece] as it lands after [before]. */
    fun land(before: String?, piece: String) = applySeamCase(piece, seamCaseOf(before), writtenSmall)

    context("what the text in front of the cursor says") {
        withData(
            nameFn = { (before, case) -> "${before?.let { "\"${it.replace("\n", "\\n")}\"" } ?: "unread"} → $case" },
            // Nothing to go by
            null to SeamCase.AS_WRITTEN,
            // An empty field, or a fresh line
            "" to SeamCase.SENTENCE_START,
            "   " to SeamCase.SENTENCE_START,
            "Einkaufsliste\n" to SeamCase.SENTENCE_START,
            "Einkaufsliste\n  " to SeamCase.SENTENCE_START,
            "Einkaufsliste\r\n" to SeamCase.SENTENCE_START,
            // A sentence going on
            "We met" to SeamCase.MID_SENTENCE,
            "We met " to SeamCase.MID_SENTENCE,
            "um 10 " to SeamCase.MID_SENTENCE,
            "Hallo, " to SeamCase.MID_SENTENCE,
            "eins; " to SeamCase.MID_SENTENCE,
            "und dann – " to SeamCase.MID_SENTENCE,
            "and then — " to SeamCase.MID_SENTENCE,
            "Schöne Grüße aus Köln " to SeamCase.MID_SENTENCE,
            // A sentence ended
            "Done." to SeamCase.SENTENCE_START,
            "Done. " to SeamCase.SENTENCE_START,
            "Really? " to SeamCase.SENTENCE_START,
            "Wirklich?! " to SeamCase.SENTENCE_START,
            "Wow! " to SeamCase.SENTENCE_START,
            "It ends in a. " to SeamCase.SENTENCE_START,
            "终于。" to SeamCase.SENTENCE_START,
            // Dots that end nothing for certain
            "Wait... " to SeamCase.AS_WRITTEN,
            "Wait… " to SeamCase.AS_WRITTEN,
            "Termin bei Dr. " to SeamCase.AS_WRITTEN,
            "(Dr. " to SeamCase.AS_WRITTEN,
            "Obst, z.B. " to SeamCase.AS_WRITTEN,
            "Obst, z. B. " to SeamCase.AS_WRITTEN,
            "fruit, e.g. " to SeamCase.AS_WRITTEN,
            "Äpfel, Birnen usw. " to SeamCase.AS_WRITTEN,
            "apples, pears etc. " to SeamCase.AS_WRITTEN,
            "am 5. " to SeamCase.AS_WRITTEN,
            "am 11.11. " to SeamCase.AS_WRITTEN,
            "in the U.S. " to SeamCase.AS_WRITTEN,
            "J. R. R. " to SeamCase.AS_WRITTEN,
            "um 9 a.m. " to SeamCase.AS_WRITTEN,
            // Marks that open something, or might
            "Note: " to SeamCase.AS_WRITTEN,
            "Er sagte „" to SeamCase.AS_WRITTEN,
            "(" to SeamCase.AS_WRITTEN,
            "He said \"Hi.\" " to SeamCase.AS_WRITTEN,
            "see (below) " to SeamCase.AS_WRITTEN,
            "¿" to SeamCase.AS_WRITTEN,
        ) { (before, case) -> seamCaseOf(before) shouldBe case }
    }

    context("whole dictations landing in running text") {
        withData(
            nameFn = { (before, piece, out) -> "\"$before\" + \"$piece\" → \"$out\"" },
            // The issue's own example: cursor after "met" in "We met on Tuesday."
            Triple("We met", "Again", "again"),
            Triple("We met ", "Again on Tuesday.", "again on Tuesday."),
            Triple("Ich habe ", "Gestern angerufen.", "gestern angerufen."),
            Triple("Brot, Milch ", "Und Eier", "und Eier"),
            Triple("Look at ", "The list", "the list"),
            Triple("This is ", "A test.", "a test."),
            Triple("Hallo, ", "Danach gehen wir.", "danach gehen wir."),
            Triple("Treffen wir uns ", "Morgen?", "morgen?"),
            Triple("Bitte ", "Don't wait.", "don't wait."),
            Triple("It is ", "Well-known.", "well-known."),
            // Nouns, names, I and the unknown keep their capital
            Triple("Wir treffen uns am ", "Bahnhof um 10.", "Bahnhof um 10."),
            Triple("See you on ", "Tuesday.", "Tuesday."),
            Triple("Yesterday ", "I went home.", "I went home."),
            Triple("Gestern war ", "Anna da.", "Anna da."),
            Triple("We met at ", "Zoxtrel.", "Zoxtrel."),
            // Capitals of the word's own, and addresses
            Triple("New rules from the ", "EU apply.", "EU apply."),
            Triple("My new ", "iPhone works.", "iPhone works."),
            Triple("Write to ", "Anna@example.com", "Anna@example.com"),
            Triple("Go to ", "Www.example.com", "Www.example.com"),
            // A new sentence capitalises what the provider left small
            Triple("Das war gut. ", "danach gingen wir heim.", "Danach gingen wir heim."),
            Triple("Fertig! ", "über alles andere reden wir morgen.", "Über alles andere reden wir morgen."),
            Triple("", "einkaufen gehen", "Einkaufen gehen"),
            Triple("Liste:\n", "milch", "Milch"),
            Triple("Done. ", "iphone works.", "Iphone works."),
            Triple("Done. ", "www.example.com", "www.example.com"),
            Triple("Done. ", "Already capital.", "Already capital."),
            // Left as written
            Triple("Wait... ", "Again", "Again"),
            Triple("Wait... ", "again", "again"),
            Triple("Obst, z.B. ", "Äpfel", "Äpfel"),
            Triple("Obst, z.B. ", "äpfel", "äpfel"),
            Triple("Note: ", "Again", "Again"),
            Triple("Note: ", "again", "again"),
            Triple("am 5. ", "Mai", "Mai"),
            // Not opening with a letter
            Triple("We met ", "\"Again\"", "\"Again\""),
            Triple("We met ", "10 times", "10 times"),
            Triple("Done. ", "„hallo“", "„hallo“"),
        ) { (before, piece, out) -> land(before, piece) shouldBe out }
    }

    test("an unread field changes nothing in either direction") {
        land(null, "Again") shouldBe "Again"
        land(null, "again") shouldBe "again"
    }

    test("without a dictionary, a capital in the middle of a sentence stays") {
        applySeamCase("Again", SeamCase.MID_SENTENCE) { false } shouldBe "Again"
        applySeamCase("Gestern", SeamCase.MID_SENTENCE) { false } shouldBe "Gestern"
    }

    test("only the first letter changes, the rest of the dictation is left as the provider wrote it") {
        land("We met ", "Again. Then We left.") shouldBe "again. Then We left."
        land("Das war gut. ", "danach SEHR gut") shouldBe "Danach SEHR gut"
    }

    test("an empty dictation stays empty") {
        land("We met ", "") shouldBe ""
        land("", "") shouldBe ""
    }

    context("whether the sentence goes on behind the cursor") {
        withData(
            nameFn = { (after, goesOn) -> "${after?.let { "\"${it.replace("\n", "\\n")}\"" } ?: "unread"} → $goesOn" },
            " on Tuesday." to true,
            "on Tuesday." to true,
            " 3 times." to true,
            ", then we left." to true,
            "; danach" to true,
            "." to true,
            "?" to true,
            ")" to true,
            " – und dann" to true,
            // A capital is a noun, a name or a new sentence: nothing to go by
            " Anna gesehen." to false,
            " Then we left." to false,
            // The sentence ends here
            "" to false,
            "   " to false,
            "\nNext line" to false,
            " \nnext line" to false,
            null to false,
            // Quotes may open or close
            " \"so\"" to false,
        ) { (after, goesOn) -> continuesSentence(after) shouldBe goesOn }
    }

    test("a capital behind the cursor goes on only from inside a sentence") {
        // "Ich habe | Anna gesehen.": the sentence began before the cursor and ends after "gesehen".
        continuesSentence(" Anna gesehen.", seamCaseOf("Ich habe")) shouldBe true
        continuesSentence("Bahnhof gewartet.", seamCaseOf("Wir haben am ")) shouldBe true
        // After a sentence end, or nothing to go by: the capital may start the next sentence.
        continuesSentence(" Then we left.", seamCaseOf("We met. ")) shouldBe false
        continuesSentence(" Anna kam.", seamCaseOf("")) shouldBe false
        continuesSentence(" Anna kam.", seamCaseOf(null)) shouldBe false
        continuesSentence(" Anna kam.", seamCaseOf("z.B. ")) shouldBe false
        // Small letters go on from anywhere, a new line from nowhere.
        continuesSentence(" on Tuesday.", seamCaseOf("We met. ")) shouldBe true
        continuesSentence("\nAnna", seamCaseOf("Ich habe")) shouldBe false
    }

    context("the full stop of a dictation the sentence goes on behind") {
        withData(
            nameFn = { (piece, out) -> "\"$piece\" → \"$out\"" },
            "again." to "again",
            "gestern angerufen." to "gestern angerufen",
            "Wir waren da. Dann gestern." to "Wir waren da. Dann gestern",
            "at 5." to "at 5",
            // Kept: what the speaker may have meant, and dots that belong to a word
            "again?" to "again?",
            "again!" to "again!",
            "and so on..." to "and so on...",
            "and so on…" to "and so on…",
            "apples, pears etc." to "apples, pears etc.",
            "in the U.S." to "in the U.S.",
            "Plan B." to "Plan B.",
            "Obst usw." to "Obst usw.",
            "." to ".",
            "" to "",
        ) { (piece, out) -> dropSeamFullStop(piece) shouldBe out }
    }
})
