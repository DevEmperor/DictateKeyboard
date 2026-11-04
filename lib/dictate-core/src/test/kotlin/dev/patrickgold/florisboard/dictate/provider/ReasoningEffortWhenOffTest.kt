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
 * What goes out as `reasoning_effort` while the account names no value. The catalog speaks for one model at
 * a time: Luna is sent none, and every other model keeps the provider's default, as it always has.
 */
class ReasoningEffortWhenOffTest : FunSpec({

    fun byDefault(provider: String, model: String) =
        ReasoningResolver.wire(ModelCatalog.spec(provider, model), "", PromptReasoning.ACCOUNT)

    test("OpenAI's default model runs without thinking") {
        ProviderRegistry.OPENAI.defaultChatModel shouldBe "gpt-6-luna"
        byDefault("openai", "gpt-6-luna") shouldBe "none"
    }

    test("another model keeps the provider's default") {
        // gpt-6.1-sol refuses "none" outright (measured 2026-10-16), so this is not just caution.
        byDefault("openai", "gpt-6.1-sol") shouldBe null
        byDefault("openai", "gpt-4o-mini") shouldBe null
    }

    test("the same model elsewhere, or on a server of the user's own, is sent nothing") {
        byDefault("openrouter", "openai/gpt-6-luna") shouldBe null
        ReasoningResolver.wire(null, "", PromptReasoning.ACCOUNT) shouldBe null
    }
})
