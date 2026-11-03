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
import dev.patrickgold.florisboard.dictate.data.prompts.snippetBodyOf
import dev.patrickgold.florisboard.dictate.field.seamSeparator

/**
 * The post-transcription rewording chain, shared by the phone (`DictateController`, and the tethered
 * watch path `PhoneTranscriber`) and the Wear app (standalone path). Mirrors
 * `DictateController.postProcessTranscript`: optional auto-formatting first, then the user's auto-apply
 * prompts in order. Each step is best-effort — a failing step keeps the text so far so a dictation is
 * never lost. Lives in `:lib:dictate-core` so the watch can run the exact same chain when it transcribes
 * standalone (issue #130 auto-rewording on the watch).
 */
object DictateRewording {

    /**
     * One automatic prompt: an [instruction] for the model, which always gets the running text with it, or
     * a `[snippet]` ([snippetBodyOf]), which is appended as written, behind a space where two dictations
     * would get one ([seamSeparator]).
     */
    data class Prompt(val instruction: String)

    /**
     * Runs auto-formatting (when [autoFormatting]) and then [autoApplyPrompts] over [transcript], using
     * [client]'s chat model [chatModel]. [languageName] is the readable language for the auto-formatting
     * hint; [systemPrompt] is the rewording system prompt (be-precise / custom) appended to each prompt.
     * [reasoningEffort] goes out with every request, null omitting it — these paths have no reasoning
     * setting of their own, so callers pass [ProviderPreset.reasoningEffortWhenOff].
     * Returns the final text (or [transcript] unchanged when nothing applies / everything fails).
     */
    suspend fun apply(
        client: LlmProvider,
        chatModel: String,
        transcript: String,
        autoFormatting: Boolean,
        languageName: String?,
        systemPrompt: String?,
        autoApplyPrompts: List<Prompt>,
        reasoningEffort: String? = null,
    ): String {
        if (transcript.isBlank()) return transcript
        var text = transcript

        if (autoFormatting) {
            val formatPrompt = DictatePromptDefaults.buildAutoFormattingPrompt(languageName, text)
            text = runCatching {
                client.complete(ChatRequest.ofUser(chatModel, formatPrompt, reasoningEffort))
                    .text.trim().ifBlank { text }
            }.getOrDefault(text)
        }

        for (prompt in autoApplyPrompts) {
            if (prompt.instruction.isBlank()) continue
            val snippet = snippetBodyOf(prompt.instruction)
            if (snippet != null) {
                text += seamSeparator(text, snippet) + snippet
                continue
            }
            // Always the running text, as on the phone (see promptRequiresSelection): without it the model
            // answers the bare instruction, and that answer would replace the dictation. Framed as text, or a
            // dictated question gets answered instead (#469).
            val content = DictatePromptDefaults.buildRewordingRequest(prompt.instruction, systemPrompt, text)
            text = runCatching {
                val reply = client.complete(ChatRequest.ofUser(chatModel, content, reasoningEffort)).text
                DictatePromptDefaults.stripTextTags(reply).trim().ifBlank { text }
            }.getOrDefault(text)
        }
        return text
    }
}
