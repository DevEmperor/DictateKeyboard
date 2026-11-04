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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * From an account's choice and a prompt's level to the word a model takes, against the catalog as measured on
 * 2026-11-04.
 */
class ReasoningResolverTest : FunSpec({

    fun spec(provider: String, model: String) = ModelCatalog.spec(provider, model)!!
    val luna = spec("openai", "gpt-6-luna")
    val sol = spec("openai", "gpt-6.1-sol")
    val gptOss = spec("groq", "openai/gpt-oss-120b")
    val mistral = spec("mistral", "mistral-small-latest")
    val ovhQwen = spec("ovhcloud", "Qwen3.8-27B")
    val claude = spec("anthropic", "claude-sonnet-5-5")

    test("the account's choice goes out as it is, when the model takes it") {
        ReasoningResolver.wire(sol, "xhigh", PromptReasoning.ACCOUNT) shouldBe "xhigh"
        ReasoningResolver.wire(gptOss, "low", PromptReasoning.ACCOUNT) shouldBe "low"
    }

    test("no choice is the app's default where it has one, else no field") {
        ReasoningResolver.wire(luna, "", PromptReasoning.ACCOUNT) shouldBe "none"
        ReasoningResolver.wire(gptOss, "", PromptReasoning.ACCOUNT) shouldBe null
    }

    test("a choice the model does not take, left from another model, counts as none made") {
        // "none" chosen for Luna, then the model switched to Sol, which refuses it.
        ReasoningResolver.wire(sol, "none", PromptReasoning.ACCOUNT) shouldBe null
    }

    test("a model without a reasoning setting gets no field, whatever is asked") {
        for (level in PromptReasoning.entries) {
            ReasoningResolver.wire(claude, "high", level) shouldBe null
        }
    }

    test("a level becomes each model's own word") {
        ReasoningResolver.wire(luna, "", PromptReasoning.NONE) shouldBe "none"
        // Sol does not take none; its least is low.
        ReasoningResolver.wire(sol, "", PromptReasoning.NONE) shouldBe "low"
        ReasoningResolver.wire(gptOss, "", PromptReasoning.NONE) shouldBe "low"
        ReasoningResolver.wire(luna, "", PromptReasoning.LITTLE) shouldBe "low"
        ReasoningResolver.wire(gptOss, "", PromptReasoning.MUCH) shouldBe "high"
        // Mistral names only none and high.
        ReasoningResolver.wire(mistral, "", PromptReasoning.LITTLE) shouldBe "high"
        // OVH's Qwen has no high; xhigh is several times the cost, so much is medium there.
        ReasoningResolver.wire(ovhQwen, "", PromptReasoning.MUCH) shouldBe "medium"
    }

    test("a level overrides the account's own choice") {
        ReasoningResolver.wire(sol, "xhigh", PromptReasoning.NONE) shouldBe "low"
    }

    test("a model the catalog does not know gets the account's word verbatim and the common word for a level") {
        ReasoningResolver.wire(null, "max", PromptReasoning.ACCOUNT) shouldBe "max"
        ReasoningResolver.wire(null, "", PromptReasoning.ACCOUNT) shouldBe null
        ReasoningResolver.wire(null, "", PromptReasoning.NONE) shouldBe "none"
        ReasoningResolver.wire(null, "", PromptReasoning.LITTLE) shouldBe "low"
        ReasoningResolver.wire(null, "", PromptReasoning.MUCH) shouldBe "high"
    }

    test("what prompts stored before the levels existed still means what it meant") {
        PromptReasoning.fromStored("NONE") shouldBe PromptReasoning.NONE
        PromptReasoning.fromStored("MINIMAL") shouldBe PromptReasoning.LITTLE
        PromptReasoning.fromStored("LOW") shouldBe PromptReasoning.LITTLE
        PromptReasoning.fromStored("MEDIUM") shouldBe PromptReasoning.MUCH
        PromptReasoning.fromStored("HIGH") shouldBe PromptReasoning.MUCH
        PromptReasoning.fromStored("OFF") shouldBe PromptReasoning.ACCOUNT
        PromptReasoning.fromStored("CUSTOM") shouldBe PromptReasoning.ACCOUNT
        PromptReasoning.fromStored(null) shouldBe PromptReasoning.ACCOUNT
        PromptReasoning.fromStored("MUCH") shouldBe PromptReasoning.MUCH
    }
})
