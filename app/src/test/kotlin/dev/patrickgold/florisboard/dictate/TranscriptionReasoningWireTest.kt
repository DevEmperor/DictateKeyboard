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

import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a transcription goes out with for how much its model thinks (#477), on every path that asks. */
class TranscriptionReasoningWireTest {

    private val gemini = ProviderRegistry.GEMINI
    private fun account(transcription: String = "", chat: String = "") =
        ProviderAccount(providerId = gemini.id, transcriptionReasoning = transcription, chatReasoning = chat)

    @Test
    fun `a model with a default for transcriptions gets it unless the account says otherwise`() {
        assertEquals("none", transcriptionReasoningWire(account(), gemini, "gemini-4-carbon"))
        assertEquals("low", transcriptionReasoningWire(account(transcription = "low"), gemini, "gemini-4-carbon"))
        // The rewording's setting is not the transcription's.
        assertEquals("none", transcriptionReasoningWire(account(chat = "high"), gemini, "gemini-4-carbon"))
    }

    @Test
    fun `a model without one is sent the account's choice or nothing`() {
        assertNull(transcriptionReasoningWire(account(), gemini, "gemini-3.6-flash"))
        assertEquals("none", transcriptionReasoningWire(account(transcription = "none"), gemini, "gemini-3.6-flash"))
        assertNull(transcriptionReasoningWire(account(), gemini, "gemini-3.5-flash-lite"))
    }

    @Test
    fun `Google's speech model and every speech-to-text provider get no field`() {
        assertNull(transcriptionReasoningWire(account(transcription = "low"), gemini, "gemini-3.5-transcribe"))
        val openAi = ProviderAccount(providerId = "openai", transcriptionReasoning = "low")
        assertNull(transcriptionReasoningWire(openAi, ProviderRegistry.OPENAI, "gpt-transcribe"))
    }

    @Test
    fun `single call sends the one model's own setting, the rewording's`() {
        val both = account(transcription = "low", chat = "high")
        assertEquals("high", transcriptionReasoningWire(both, gemini, "gemini-3.6-flash", chatAudio = true))
        // Gemini has no rewording default of its own, so nothing goes out without a choice.
        assertNull(transcriptionReasoningWire(account(transcription = "low"), gemini, "gemini-3.6-flash", chatAudio = true))
    }
}
