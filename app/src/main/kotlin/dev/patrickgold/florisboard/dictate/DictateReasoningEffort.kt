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

import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.dictate.data.prompts.PromptModel
import dev.patrickgold.florisboard.dictate.provider.DictateRewording
import dev.patrickgold.florisboard.dictate.provider.ProviderPreset
import kotlinx.serialization.Serializable

/**
 * How much a reasoning model should "think" on rewording/command chat calls (issue #141), mapped to the
 * OpenAI-compatible `reasoning_effort` field. [OFF] omits the field entirely — the provider default is
 * used and non-reasoning models are unaffected. Applies only to chat/rewording, never to transcription.
 *
 * A prompt may override the global setting per-prompt (issue #155); [Serializable] so such an override
 * can ride along in exported/backed-up prompts.
 */
@Serializable
enum class DictateReasoningEffort(val wire: String?) {
    OFF(null),

    // "Off" only ever meant "say nothing", and saying nothing leaves a model that thinks by default
    // thinking, as Ollama's do (#464). Ollama turns "none" into think=false (its openai.go, read
    // 2026-11-03); gpt-6-luna takes it (2026-10-16), and so do Groq's qwen and Gemini's 3.x flash models
    // (asked 2026-11-03). Where it is refused, the client asks again: see OpenAiCompatibleClient.complete.
    NONE("none"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),

    // A user-entered value (issue #186), so providers with non-standard values (e.g. Ollama's "max"
    // /"none", or a value some models reject) can still be used. The actual wire string is stored
    // separately in the `rewordingReasoningEffortCustom` preference, not in [wire].
    CUSTOM(null);
}

/**
 * The `reasoning_effort` a rewording through [preset] with [model] goes out with; null leaves the field out.
 *
 * [override] is a prompt's own choice (#155) and wins over the global setting; a Custom value is read from
 * wherever the choice came from — the prompt's [overrideCustom] or the global one (#186). Off sends
 * nothing, unless the preset knows what its own default model runs at ([ProviderPreset.reasoningEffortWhenOff]).
 *
 * Every rewording path asks this, so a shared recording, the watch and the keyboard send the same thing.
 * The share screen and both watch paths used to send only the preset's value, whatever the user had set.
 */
fun rewordingReasoningWire(
    prefs: FlorisPreferenceModel,
    preset: ProviderPreset,
    model: String,
    override: DictateReasoningEffort? = null,
    overrideCustom: String? = null,
): String? = when (val effort = override ?: prefs.dictate.rewordingReasoningEffort.get()) {
    DictateReasoningEffort.CUSTOM -> {
        val custom = if (override == DictateReasoningEffort.CUSTOM) {
            overrideCustom
        } else {
            prefs.dictate.rewordingReasoningEffortCustom.get()
        }
        custom?.trim()?.ifBlank { null }
    }
    DictateReasoningEffort.OFF -> preset.reasoningEffortWhenOff(model)
    else -> effort.wire
}

/**
 * [prompt]'s own effort in the form [DictateRewording.Prompt.reasoningEffort] takes: null when the prompt
 * follows the global setting, blank when its own choice leaves the field out.
 */
fun promptReasoningWire(
    prefs: FlorisPreferenceModel,
    preset: ProviderPreset,
    model: String,
    prompt: PromptModel,
): String? = prompt.reasoningEffort?.let {
    rewordingReasoningWire(prefs, preset, model, it, prompt.reasoningEffortCustom).orEmpty()
}
