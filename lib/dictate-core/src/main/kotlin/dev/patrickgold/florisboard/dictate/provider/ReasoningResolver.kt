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

/**
 * How much a prompt asks the model to think, in no provider's words (decided 2026-11-03).
 *
 * A prompt outlives the provider it was written for (the community library, export, a switch from OpenAI to
 * Groq), so it cannot hold `xhigh` or `none`: one provider's word means nothing to the next. It holds a level,
 * and [ReasoningResolver] turns that into the word the active model takes.
 */
enum class PromptReasoning {
    /** Whatever the rewording account is set to. */
    ACCOUNT,
    NONE,
    LITTLE,
    MUCH;

    companion object {
        /**
         * A stored value, including what prompts stored before the levels existed: the provider words of the
         * old per-prompt override (#155). NONE stays NONE, MINIMAL and LOW become LITTLE, MEDIUM and HIGH become
         * MUCH. OFF, CUSTOM and anything unknown follow the account, CUSTOM because a word typed for one
         * provider is exactly what a level replaces.
         */
        fun fromStored(name: String?): PromptReasoning = when (name?.trim()?.uppercase()) {
            "NONE" -> NONE
            "LITTLE", "MINIMAL", "LOW" -> LITTLE
            "MUCH", "MEDIUM", "HIGH" -> MUCH
            else -> ACCOUNT
        }
    }
}

/** What goes out as `reasoning_effort` for one rewording. */
object ReasoningResolver {

    /**
     * The value to send, null for no field.
     *
     * - [spec] is the catalog's entry for the model, null when the catalog does not know it. A known model
     *   without reasoning gets no field, whatever was asked.
     * - [accountValue] is the account's own choice in the provider's words, blank for its default. A value
     *   the model does not list (the model was changed since) counts as blank. Blank sends the catalog's
     *   [ReasoningSpec.sentByDefault] where it has one (gpt-6-luna: none), else nothing.
     *
     * A level resolves against the model's own words: NONE to "none", or the least it takes; LITTLE to its
     * lowest above none; MUCH to "high", or its highest below xhigh and max, which cost several times as much.
     * A model the catalog does not know gets the common word (none, low, high). Ollama, vLLM and llama.cpp
     * all read `reasoning_effort` and switch thinking off at "none" (their docs, read 2026-11-04), so servers
     * of the user's own need no other field. A model that refuses a word is asked once more by the client,
     * with the nearest word its refusal names or without the field.
     */
    fun wire(spec: ModelSpec?, accountValue: String, level: PromptReasoning): String? {
        if (spec != null && spec.reasoning == null) return null
        val values = spec?.reasoning?.values
        return when (level) {
            PromptReasoning.ACCOUNT -> accountValue.trim().ifBlank { null }?.takeIf { values == null || it in values }
                ?: spec?.reasoning?.sentByDefault
            PromptReasoning.NONE -> when {
                values == null -> "none"
                "none" in values -> "none"
                else -> values.first()
            }
            PromptReasoning.LITTLE -> when {
                values == null -> "low"
                else -> values.firstOrNull { it != "none" } ?: values.first()
            }
            PromptReasoning.MUCH -> when {
                values == null -> "high"
                "high" in values -> "high"
                else -> values.lastOrNull { it != "xhigh" && it != "max" } ?: values.last()
            }
        }
    }
}
