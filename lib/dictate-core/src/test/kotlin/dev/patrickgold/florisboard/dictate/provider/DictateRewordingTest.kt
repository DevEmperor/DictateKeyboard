/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.provider

import dev.patrickgold.florisboard.dictate.data.prompts.DictatePromptDefaults
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith

/**
 * The automatic chain the watch and the tethered path run. The model here answers the way a real one did
 * in the e-mail report: with the text it was given, or, given none, by asking for it.
 */
class DictateRewordingTest : FunSpec({

    class AskingModel : LlmProvider {
        val sent = mutableListOf<String>()
        val efforts = mutableListOf<String?>()

        override suspend fun complete(request: ChatRequest): ChatResult {
            val content = request.messages.single().content
            sent += content
            efforts += request.reasoningEffort
            val text = content.substringAfter("<text>\n", missingDelimiterValue = "").substringBefore("\n</text>")
            val reply = if (text.startsWith("Hello")) {
                "Reworded: $text"
            } else {
                "I'm ready to rewrite dictated text according to your specifications. Please write the text."
            }
            return ChatResult(reply, usage = null)
        }

        override suspend fun listModels(): List<ModelInfo> = emptyList()
    }

    suspend fun run(model: AskingModel, vararg prompts: String) = DictateRewording.apply(
        client = model,
        chatModel = "mock-chat",
        transcript = "Hello, see you tomorrow.",
        autoFormatting = false,
        languageName = null,
        systemPrompt = "Be accurate with your output.",
        autoApplyPrompts = prompts.map { DictateRewording.Prompt(it) },
    )

    test("an automatic prompt always gets the dictation, after the instruction and the system prompt") {
        val model = AskingModel()
        run(model, "Rewrite the dictated text.") shouldBe "Reworded: Hello, see you tomorrow."
        model.sent.single() shouldBe "Rewrite the dictated text.\n\nBe accurate with your output.\n\n" +
            DictatePromptDefaults.REWORDING_TEXT_FRAME + "\n\n<text>\nHello, see you tomorrow.\n</text>"
    }

    test("an automatic snippet is appended without a request, and the next prompt works on it") {
        val model = AskingModel()
        run(model, "[Best regards,]", "Rewrite the dictated text.") shouldBe
            "Reworded: Hello, see you tomorrow. Best regards,"
        model.sent shouldHaveSize 1
        model.sent.single() shouldEndWith "<text>\nHello, see you tomorrow. Best regards,\n</text>"
    }

    // #469: a model that copies the frame back must not put the tags into the field.
    test("tags a model copies back are taken off") {
        val echoing = object : LlmProvider {
            override suspend fun complete(request: ChatRequest) = ChatResult("<text>\nHello there.\n</text>", usage = null)
            override suspend fun listModels(): List<ModelInfo> = emptyList()
        }
        DictateRewording.apply(
            client = echoing,
            chatModel = "mock-chat",
            transcript = "hello there",
            autoFormatting = false,
            languageName = null,
            systemPrompt = null,
            autoApplyPrompts = listOf(DictateRewording.Prompt("Fix the punctuation.")),
        ) shouldBe "Hello there."
    }

    test("a snippet last is appended to the reworded text") {
        val model = AskingModel()
        run(model, "Rewrite the dictated text.", "[Best regards,]") shouldBe
            "Reworded: Hello, see you tomorrow. Best regards,"
    }

    test("a snippet that brings its own spacing or mark gets no space added") {
        val model = AskingModel()
        run(model, "[\nBest regards,]") shouldBe "Hello, see you tomorrow.\nBest regards,"
        run(model, "[, right?]") shouldBe "Hello, see you tomorrow., right?"
        model.sent shouldHaveSize 0
    }

    // These paths have no reasoning setting of their own; without the effort, OpenAI's default model would
    // think at medium on every one of their requests.
    test("every request of the chain carries the reasoning effort it was given") {
        val model = AskingModel()
        DictateRewording.apply(
            client = model,
            chatModel = "gpt-6-luna",
            transcript = "Hello, see you tomorrow.",
            autoFormatting = true,
            languageName = "English",
            systemPrompt = null,
            autoApplyPrompts = listOf(DictateRewording.Prompt("Rewrite the dictated text.")),
            reasoningEffort = "none",
        )
        model.efforts shouldBe listOf("none", "none")
        run(model, "Rewrite the dictated text.")
        model.efforts.last() shouldBe null
    }
})
