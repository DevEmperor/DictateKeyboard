/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.dictate

import dev.patrickgold.florisboard.dictate.provider.ProviderPreset
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which ids the model picker offers. The live lists below are excerpts of what the providers answered on
 * 2026-11-03: ids the model catalog knows go where it says, the rest by the name rules.
 */
class PickerCandidatesTest {

    private fun offered(preset: ProviderPreset, kind: ModelKind, live: List<String>) =
        pickerCandidates(preset, kind, live, emptyList(), emptyList(), current = "", query = "")

    @Test
    fun `a streaming-only model is not offered for a recording`() {
        val live = listOf("gpt-transcribe", "gpt-live-transcribe", "gpt-realtime-whisper", "whisper-1", "gpt-realtime-mini")
        val batch = offered(ProviderRegistry.OPENAI, ModelKind.TRANSCRIPTION, live)
        // The catalog knows these two as streaming-only; their names say "transcribe" and "whisper".
        assertFalse("gpt-live-transcribe" in batch)
        assertFalse("gpt-realtime-whisper" in batch)
        // A model the catalog does not curate is still found by its name, as before.
        assertTrue("whisper-1" in batch)
        assertTrue("gpt-transcribe" in batch)
    }

    @Test
    fun `the name rules keep streaming and voice models out of the batch list too`() {
        val live = listOf("voxtral-mini-transcribe-3", "voxtral-mini-transcribe-realtime-3", "voxtral-mini-tts-3", "mistral-small-latest")
        val batch = offered(ProviderRegistry.MISTRAL, ModelKind.TRANSCRIPTION, live)
        assertTrue("voxtral-mini-transcribe-3" in batch)
        assertFalse("voxtral-mini-transcribe-realtime-3" in batch)
        assertFalse("voxtral-mini-tts-3" in batch)
        assertFalse("mistral-small-latest" in batch)
    }

    @Test
    fun `the catalog's chat models and the name rules' come in that order`() {
        val chat = offered(ProviderRegistry.GROQ, ModelKind.CHAT, listOf("allam-2-7b", "whisper-large-v3"))
        assertEquals(ProviderRegistry.GROQ.curatedChatModels, chat.take(ProviderRegistry.GROQ.curatedChatModels.size))
        assertTrue("allam-2-7b" in chat)
        assertFalse("whisper-large-v3" in chat)
    }

    @Test
    fun `Gemini's transcription list still takes its chat models it does not know yet`() {
        val stt = offered(ProviderRegistry.GEMINI, ModelKind.TRANSCRIPTION, listOf("gemini-4-argon", "gemini-3.5-transcribe-live"))
        assertTrue("gemini-4-argon" in stt)
        // Known as streaming-only, so not for a recording, although Gemini's rule would take it.
        assertFalse("gemini-3.5-transcribe-live" in stt)
    }

    @Test
    fun `the current choice is always offered, whatever it is`() {
        val stt = pickerCandidates(
            ProviderRegistry.OPENAI, ModelKind.TRANSCRIPTION, emptyList(), emptyList(), emptyList(),
            current = "gpt-realtime-whisper", query = "",
        )
        assertTrue("gpt-realtime-whisper" in stt)
    }
}
