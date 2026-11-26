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
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

class DictateAutoFormattingTest : FunSpec({
    context("englishNameFor maps codes to readable hints") {
        withData(
            "de" to "German",
            "en" to "English",
            "zh-CN" to "Mandarin (CN)",
        ) { (code, expected) ->
            DictateLanguages.englishNameFor(code) shouldBe expected
        }
    }

    context("englishNameFor returns null where the model should hear \"unknown\"") {
        withData(nameFn = { "code=<$it>" }, DictateLanguages.DETECT, "", "xx", "not-a-code") { code ->
            DictateLanguages.englishNameFor(code) shouldBe null
        }
    }

    test("englishNameFor handles a null code") {
        DictateLanguages.englishNameFor(null) shouldBe null
    }

    test("buildAutoFormattingPrompt embeds the rules, the language hint, then the transcript") {
        val prompt = DictatePromptDefaults.buildAutoFormattingPrompt("German", "Hallo Welt")

        prompt shouldStartWith DictatePromptDefaults.AUTO_FORMATTING_PROMPT
        prompt shouldContain "\n\nLanguage hint: German\n\n"
        prompt shouldContain "Transcript:\nHallo Welt"
        // The transcript must be the very tail so the model sees it last.
        prompt.endsWith("Transcript:\nHallo Welt") shouldBe true
        // Without an instruction of the user's own, the request is exactly what it was before #497.
        prompt shouldNotContain DictatePromptDefaults.USER_INSTRUCTION_LEAD
    }

    test("the user's own rewording instruction goes into auto-formatting, after the rules (#497)") {
        val prompt = DictatePromptDefaults.buildAutoFormattingPrompt("English", "I have three apples", "Write numbers as digits.")

        prompt shouldStartWith DictatePromptDefaults.AUTO_FORMATTING_PROMPT
        prompt shouldContain "\n\n${DictatePromptDefaults.USER_INSTRUCTION_LEAD}\nWrite numbers as digits.\n\nLanguage hint: English"
        prompt.endsWith("Transcript:\nI have three apples") shouldBe true
        // A model that echoes the request is still caught by the guard.
        DictatePromptDefaults.looksLikeAutoFormattingPrompt(DictatePromptDefaults.USER_INSTRUCTION_LEAD) shouldBe true
    }

    test("only an instruction the user wrote counts, not the built-in one or none (#497)") {
        DictatePromptDefaults.userInstructionOf("Write numbers as digits.") shouldBe "Write numbers as digits."
        DictatePromptDefaults.userInstructionOf(DictatePromptDefaults.REWORDING_BE_PRECISE) shouldBe null
        DictatePromptDefaults.userInstructionOf("  ") shouldBe null
        DictatePromptDefaults.userInstructionOf(null) shouldBe null
    }

    context("hasNoWords keeps wordless transcripts away from the model") {
        // Not blank, so every emptiness check lets them through — and there is nothing in them to format.
        withData(nameFn = { "transcript=<$it>" }, "...", "—", "?!", " . . . ", "\u00b7\u00b7\u00b7", "-") { t ->
            DictatePromptDefaults.hasNoWords(t) shouldBe true
        }
    }

    context("hasNoWords lets anything with a word through") {
        withData(nameFn = { "transcript=<$it>" }, "Hello", "7", "\u00e4hm", "uh", "a.", "3.", "\u4f60\u597d") { t ->
            DictatePromptDefaults.hasNoWords(t) shouldBe false
        }
    }

    test("looksLikeAutoFormattingPrompt flags an echoed prompt but not real transcripts (#124)") {
        // A verbatim echo of the prompt / its wrapper is detected...
        DictatePromptDefaults.looksLikeAutoFormattingPrompt(
            DictatePromptDefaults.buildAutoFormattingPrompt("German", "anything"),
        ) shouldBe true
        DictatePromptDefaults.looksLikeAutoFormattingPrompt(DictatePromptDefaults.AUTO_FORMATTING_PROMPT) shouldBe true
        // ...while genuine formatted output is not.
        DictatePromptDefaults.looksLikeAutoFormattingPrompt("Hello, how are you?") shouldBe false
        DictatePromptDefaults.looksLikeAutoFormattingPrompt("") shouldBe false
    }

    context("buildAutoFormattingPrompt falls back to \"unknown\" for a missing language") {
        withData(nameFn = { "name=<${it ?: "null"}>" }, null, "", "   ") { languageName ->
            DictatePromptDefaults.buildAutoFormattingPrompt(languageName, "text")
                .shouldContain("Language hint: unknown")
        }
    }

    context("appendCustomWords primes the transcription prompt with user vocabulary (roadmap 11.12)") {
        test("appends a comma/newline list onto the base style prompt, trimming blanks") {
            DictatePromptDefaults.appendCustomWords(
                "This sentence has capitalization and punctuation.",
                "FlorisBoard, Kubernetes\n Renée ,, ",
            ) shouldBe "This sentence has capitalization and punctuation. FlorisBoard, Kubernetes, Renée"
        }
        test("returns just the glossary when there is no usable base prompt") {
            DictatePromptDefaults.appendCustomWords(null, "Acme, Inc") shouldBe "Acme, Inc"
            DictatePromptDefaults.appendCustomWords("   ", "Acme") shouldBe "Acme"
        }
        test("returns the cleaned base unchanged when no words are given") {
            DictatePromptDefaults.appendCustomWords("Base.", "  ,, \n ") shouldBe "Base."
        }
        test("returns null when both base and words are empty") {
            DictatePromptDefaults.appendCustomWords(null, null) shouldBe null
        }
    }
})
