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
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),

    // A user-entered value (issue #186), so providers with non-standard values (e.g. Ollama's "max"
    // /"none", or a value some models reject) can still be used. The actual wire string is stored
    // separately in the `rewordingReasoningEffortCustom` preference, not in [wire].
    CUSTOM(null);
}
