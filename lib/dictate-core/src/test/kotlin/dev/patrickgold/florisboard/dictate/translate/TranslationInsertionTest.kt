/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.translate

import dev.patrickgold.florisboard.dictate.translate.TranslationInsertion.Edit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Keeping the live translation in the field without ever deleting text that is not ours (issue #424). */
class TranslationInsertionTest : FunSpec({

    /** Applies [edit] to [before] the way the editor does: delete before the cursor, then write. */
    fun apply(before: String, edit: Edit) = before.dropLast(edit.deleteBefore) + edit.text

    test("the first result goes where the cursor is, a space away from the word in front of it") {
        TranslationInsertion.edit("", null, "Hello") shouldBe Edit(0, "Hello")
        TranslationInsertion.edit("Hi", null, "Hello") shouldBe Edit(0, " Hello")
        TranslationInsertion.edit("Hi ", null, "Hello") shouldBe Edit(0, "Hello")
        TranslationInsertion.edit("Hi\n", null, "Hello") shouldBe Edit(0, "Hello")
    }

    test("a new result replaces the previous one in one edit, separator and all") {
        val field = "Hi"
        val first = TranslationInsertion.edit(field, null, "I'm")
        val afterFirst = apply(field, first)
        afterFirst shouldBe "Hi I'm"
        val second = TranslationInsertion.edit(afterFirst, first.text, "I'm coming")
        second shouldBe Edit(4, " I'm coming")
        apply(afterFirst, second) shouldBe "Hi I'm coming"
    }

    test("emptying the query takes the result back out, and nothing else") {
        TranslationInsertion.edit("Hi I'm coming", " I'm coming", "") shouldBe Edit(11, "")
        TranslationInsertion.edit("Hi", null, "") shouldBe Edit(0, "")
    }

    test("text the field no longer ends with is left alone") {
        // The user moved the cursor, or the app rewrote what was there: deleting eleven characters now
        // would eat the user's own words, so the new result starts where the cursor is instead.
        TranslationInsertion.edit("Hi I'm coming soon", " I'm coming", "I'll be") shouldBe Edit(0, " I'll be")
        TranslationInsertion.edit("Somewhere else", " I'm coming", "") shouldBe Edit(0, "")
    }
})
