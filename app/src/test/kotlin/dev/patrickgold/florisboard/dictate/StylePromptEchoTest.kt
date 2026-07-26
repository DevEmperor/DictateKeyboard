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

import dev.patrickgold.florisboard.dictate.data.prompts.DictatePromptDefaults
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StylePromptEchoTest : FunSpec({

    val en = DictatePromptDefaults.PUNCTUATION_CAPITALIZATION // "Hello. Thank you very much."

    test("an exact echo of the style prompt is detected (issue #77)") {
        DictatePromptDefaults.looksLikeStylePromptEcho(en, en) shouldBe true
    }

    test("an echo is detected regardless of case and punctuation") {
        DictatePromptDefaults.looksLikeStylePromptEcho("hello thank you very much", en) shouldBe true
        DictatePromptDefaults.looksLikeStylePromptEcho("HELLO, THANK YOU VERY MUCH!", en) shouldBe true
    }

    test("the notorious old default is caught when it was the sent prompt") {
        val old = "This sentence has capitalization and punctuation."
        DictatePromptDefaults.looksLikeStylePromptEcho(old, old) shouldBe true
    }

    test("works for a non-English prompt (compared against the sent sentence)") {
        val de = DictatePromptDefaults.punctuationPromptFor("de")
        DictatePromptDefaults.looksLikeStylePromptEcho(de, de) shouldBe true
    }

    test("an echo with a little trailing junk still counts") {
        DictatePromptDefaults.looksLikeStylePromptEcho("Hello. Thank you very much. Okay", en) shouldBe true
    }

    test("a genuine transcript that merely shares a few words is NOT dropped") {
        DictatePromptDefaults.looksLikeStylePromptEcho(
            "Thank you for the update on the project timeline.", en,
        ) shouldBe false
        // A real short "thank you" dictation must survive.
        DictatePromptDefaults.looksLikeStylePromptEcho("Thank you.", en) shouldBe false
    }

    test("empty / blank / null inputs are never treated as an echo") {
        DictatePromptDefaults.looksLikeStylePromptEcho("", en) shouldBe false
        DictatePromptDefaults.looksLikeStylePromptEcho(en, null) shouldBe false
        DictatePromptDefaults.looksLikeStylePromptEcho(en, "") shouldBe false
        DictatePromptDefaults.looksLikeStylePromptEcho("   ", en) shouldBe false
    }
})
