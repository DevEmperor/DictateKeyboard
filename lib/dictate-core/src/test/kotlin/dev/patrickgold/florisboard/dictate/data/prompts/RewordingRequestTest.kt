/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.prompts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/** The one shape every rewording request with text goes out in (#469). */
class RewordingRequestTest : FunSpec({

    val frame = DictatePromptDefaults.REWORDING_TEXT_FRAME

    test("instruction, system prompt, then the text framed and tagged") {
        DictatePromptDefaults.buildRewordingRequest("Fix the grammar.", "Be accurate.", "Where are you?") shouldBe
            "Fix the grammar.\n\nBe accurate.\n\n$frame\n\n<text>\nWhere are you?\n</text>"
    }

    test("the frame does not depend on the system prompt") {
        DictatePromptDefaults.buildRewordingRequest("Fix the grammar.", null, "Where are you?") shouldBe
            "Fix the grammar.\n\n$frame\n\n<text>\nWhere are you?\n</text>"
        DictatePromptDefaults.buildRewordingRequest("Fix the grammar.", "  ", "Where are you?") shouldBe
            "Fix the grammar.\n\n$frame\n\n<text>\nWhere are you?\n</text>"
    }

    test("a request without text has nothing to frame") {
        DictatePromptDefaults.buildRewordingRequest("Write a quote of the day.", "Be accurate.", null) shouldBe
            "Write a quote of the day.\n\nBe accurate."
        DictatePromptDefaults.buildRewordingRequest("Write a quote of the day.", null, " ") shouldBe
            "Write a quote of the day."
    }

    test("a multi-line text keeps its lines inside the tags") {
        val request = DictatePromptDefaults.buildRewordingRequest("Make a list.", null, "milk\neggs")
        request shouldBe "Make a list.\n\n$frame\n\n<text>\nmilk\neggs\n</text>"
    }

    test("tags a model copies back are taken off, and nothing else is") {
        DictatePromptDefaults.stripTextTags("<text>\nWhere are you?\n</text>") shouldBe "Where are you?"
        DictatePromptDefaults.stripTextTags("  <text>Where are you?</text>\n") shouldBe "Where are you?"
        DictatePromptDefaults.stripTextTags("Where are you?") shouldBe "Where are you?"
        DictatePromptDefaults.stripTextTags("Use <text> for the body.") shouldBe "Use <text> for the body."
        DictatePromptDefaults.stripTextTags("<text>\nOnly the start") shouldBe "<text>\nOnly the start"
    }

    test("the frame names the tags it asks to leave out") {
        frame shouldNotContain "\n"
        (frame.contains("<text>") && frame.contains("</text>")) shouldBe true
    }
})
