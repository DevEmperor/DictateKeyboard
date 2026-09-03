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
import dev.patrickgold.florisboard.dictate.provider.chatModelFor
import dev.patrickgold.florisboard.dictate.provider.chatModelIsPresetDefault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which model a rewording actually goes out with (issue #313).
 *
 * The reporter asked for exactly this test, in these words: *"changing the model in the UI actually
 * changes the model used by the network request."* Two ways it did not.
 *
 * With single-call multimodal on, the settings dialog hides the rewording field and relabels the
 * remaining one "Transcription & rewording model" — so the user is told one model does both, while
 * `chatModel` stayed blank forever and every rewording went to the preset default. And when that
 * default is a model the provider has retired, which is how this was noticed, the request fails naming
 * a model the user never chose.
 */
class ChatModelForTest {

    private val gemini = ProviderRegistry.GEMINI
    private val groq = ProviderRegistry.GROQ

    private fun account(
        chat: String = "",
        transcription: String = "",
        singleCall: Boolean = false,
    ) = ProviderAccount(
        providerId = "gemini",
        chatModel = chat,
        transcriptionModel = transcription,
        transcriptionViaChat = singleCall,
    )

    @Test
    fun `an explicit choice is used verbatim`() {
        assertEquals("gemini-3.6-flash", chatModelFor(account(chat = "gemini-3.6-flash"), gemini))
        // Including one the provider has since retired: the error then names the model the user picked,
        // which is the honest outcome and what the reporter asked for.
        assertEquals("gemini-2.5-flash", chatModelFor(account(chat = "gemini-2.5-flash"), gemini))
    }

    @Test
    fun `an explicit choice wins over the shared field`() {
        val a = account(chat = "gemini-3.5-flash", transcription = "gemini-3.6-flash", singleCall = true)
        assertEquals("gemini-3.5-flash", chatModelFor(a, gemini))
    }

    @Test
    fun `single-call means the visible model rewords too`() {
        // The bug: this used to return the preset default, while the dialog said this model did both.
        val a = account(transcription = "gemini-3.6-flash", singleCall = true)
        assertEquals("gemini-3.6-flash", chatModelFor(a, gemini))
    }

    @Test
    fun `a dedicated speech-to-text model cannot reword`() {
        // Transcribe models answer on their own endpoint and have no chat surface, so single-call does not
        // run for them either — the same condition the dictation path applies.
        val a = account(transcription = "gemini-3.5-transcribe", singleCall = true)
        assertEquals(gemini.defaultChatModel, chatModelFor(a, gemini))
    }

    @Test
    fun `an empty transcription field means the preset default, and it is judged the same way`() {
        // Since the dialog stopped filling its fields in, this is the ordinary state — and the answer has
        // to be about the model that will run, not about the empty box. Gemini's default transcription
        // model is a transcribe model, so nothing is shared and rewording keeps the chat default.
        assertEquals(gemini.defaultChatModel, chatModelFor(account(singleCall = true), gemini))
        // Same for Groq, whose default is Whisper: this is what the Gemini-only check used to miss, and
        // it would have handed rewording to a model with no chat endpoint at all.
        assertEquals(groq.defaultChatModel, chatModelFor(account(singleCall = true), groq))
    }

    @Test
    fun `the hint about a built-in default fires exactly when nobody chose the model`() {
        // Nothing chosen: whatever runs came from the preset.
        assertTrue(chatModelIsPresetDefault(account(), gemini))
        assertTrue(chatModelIsPresetDefault(account(singleCall = true), gemini))
        // A rewording model the user picked needs no explanation of where it came from.
        assertFalse(chatModelIsPresetDefault(account(chat = "gemini-3.6-flash"), gemini))
        // Nor does a transcription model they picked that single-call then shares with rewording.
        assertFalse(
            chatModelIsPresetDefault(account(transcription = "gemini-3.6-flash", singleCall = true), gemini),
        )
        // But if that pick cannot reword, what runs is the preset default again.
        assertTrue(
            chatModelIsPresetDefault(account(transcription = "gemini-3.5-transcribe", singleCall = true), gemini),
        )
    }

    @Test
    fun `dedicated speech-to-text models are recognised across providers`() {
        // Every default our presets carry, so this fails if one is renamed into something unrecognisable.
        for (model in listOf(
            "gemini-3.5-transcribe", "models/gemini-3.5-transcribe", "gpt-transcribe",
            "gpt-4o-mini-transcribe", "whisper-large-v3-turbo", "openai/whisper-large-v3", "scribe_v2",
        )) {
            assertTrue(ProviderRegistry.isDedicatedTranscriptionModel(model), model)
        }
        // Chat models, which must stay usable for single-call.
        for (model in listOf(
            "gemini-3.6-flash", "openai/gpt-oss-120b", "gpt-4o-mini", "qwen/qwen3.8-27b",
        )) {
            assertFalse(ProviderRegistry.isDedicatedTranscriptionModel(model), model)
        }
    }

    @Test
    fun `without single-call the transcription model is none of rewording's business`() {
        val a = account(transcription = "whisper-large-v3-turbo", singleCall = false)
        assertEquals(groq.defaultChatModel, chatModelFor(a, groq))
    }

    @Test
    fun `nothing chosen at all falls back to the preset`() {
        assertEquals(gemini.defaultChatModel, chatModelFor(account(), gemini))
        assertEquals(groq.defaultChatModel, chatModelFor(account(), groq))
    }

    @Test
    fun `a provider without a chat default falls back to the caller's`() {
        // The watch passes "" so an unconfigured rewording provider reports nothing rather than a model
        // the phone never intended to use.
        val soniox = ProviderRegistry.SONIOX
        assertEquals("", chatModelFor(account(), soniox, fallback = ""))
        assertEquals("gpt-4o-mini", chatModelFor(account(), soniox))
    }
}
