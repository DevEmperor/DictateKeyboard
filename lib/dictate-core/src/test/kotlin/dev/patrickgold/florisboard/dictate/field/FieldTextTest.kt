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
import io.kotest.matchers.shouldBe

/** The keyboard's own fields behaving like text fields (issue #424). */
class FieldTextTest : FunSpec({

    test("typing goes in at the cursor, and over a marked stretch replaces it") {
        FieldText("Hllo", cursor = 1).insert("a") shouldBe FieldText("Hallo", 2)
        FieldText("Hallo Welt", cursor = 10, selection = 6..10).insert("du") shouldBe FieldText("Hallo du", 8)
        FieldText("abc").selectAll().insert("x") shouldBe FieldText("x", 1)
    }

    test("the word at the cursor is the run of letters it stands in or right after") {
        FieldText("Ich komme heute", cursor = 15).wordRange() shouldBe 10..15
        FieldText("Ich komme heute", cursor = 6).wordRange() shouldBe 4..9
        FieldText("Ich komme ", cursor = 10).wordRange() shouldBe 10..10
        FieldText("Hallo, Welt", cursor = 5).wordRange() shouldBe 0..5
        FieldText("don't", cursor = 5).wordRange() shouldBe 0..5
        FieldText("", cursor = 0).wordRange() shouldBe 0..0
    }

    test("a suggestion replaces the word and leaves the cursor after one space") {
        FieldText("Ich komm", cursor = 8).replaceWord("komme") shouldBe FieldText("Ich komme ", 10)
        // Mid-text: the existing space is reused, not doubled.
        FieldText("Ich komm heute", cursor = 8).replaceWord("komme") shouldBe FieldText("Ich komme heute", 10)
        // Between words, a prediction is inserted.
        FieldText("Ich ", cursor = 4).replaceWord("komme") shouldBe FieldText("Ich komme ", 10)
        // A punctuation mark is not part of the word: it stays, and no space is pushed in front of it.
        FieldText("hallo, du", cursor = 3).replaceWord("Hallo") shouldBe FieldText("Hallo, du", 5)
        // Devanagari vowel signs are marks, not letters, and belong to the word.
        FieldText("नमस्त", cursor = 5).wordRange() shouldBe 0..5
    }

    test("a dictation preview replaces only the tail in front of the cursor") {
        FieldText("Hallo Wel", cursor = 9).replaceBeforeCursor(3, "Welt") shouldBe FieldText("Hallo Welt", 10)
        FieldText("abc", cursor = 1).replaceBeforeCursor(5, "x") shouldBe FieldText("xbc", 1)
    }

    test("an autocorrection is undone by the next backspace, and only while the field still shows it") {
        val correction = FieldAutoCorrection(start = 4, original = "komem", corrected = "komme")
        correction.undo(FieldText("Ich komme ", 10)) shouldBe FieldText("Ich komem", 9)
        // Something was typed since.
        correction.undo(FieldText("Ich komme h", 11)) shouldBe null
        // The cursor moved away.
        correction.undo(FieldText("Ich komme ", 3)) shouldBe null
    }
})
