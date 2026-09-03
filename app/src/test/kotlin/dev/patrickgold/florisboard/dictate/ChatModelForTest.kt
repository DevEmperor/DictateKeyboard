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
    fun `the merged field wins over a rewording model the fold has hidden`() {
        // Turning single-call on folds the rewording field away, so whatever it still holds is a value the
        // user can no longer see or edit. What is on screen has to be what runs, or the dialog is lying
        // again — which is what #313 was.
        val a = account(chat = "gemini-3.5-flash", transcription = "gemini-3.6-flash", singleCall = true)
        assertEquals("gemini-3.6-flash", chatModelFor(a, gemini))
    }

    @Test
    fun `single-call means the visible model rewords too`() {
        // The bug: this used to return the preset default, while the dialog said this model did both.
        val a = account(transcription = "gemini-3.6-flash", singleCall = true)
        assertEquals("gemini-3.6-flash", chatModelFor(a, gemini))
    }

    @Test
    fun `what the user typed is used, even where the app would have known better`() {
        // A speech-to-text model in the merged field cannot reword, and it is used anyway: the app has no
        // reliable way to tell those apart, and the version that tried refused to fold the settings fields
        // together and explained nothing. The failure then names the model that was chosen, which is the
        // outcome both #313 and its reporter asked for.
        val a = account(transcription = "gemini-3.5-transcribe", singleCall = true)
        assertEquals("gemini-3.5-transcribe", chatModelFor(a, gemini))
    }

    @Test
    fun `an empty merged field is not a choice, so each side keeps its own default`() {
        // The ordinary state since the dialog stopped filling its fields in. Sharing the *transcription*
        // default here would mean a fresh Gemini account rewording with gemini-3.5-transcribe and failing
        // until the user typed something.
        assertEquals(gemini.defaultChatModel, chatModelFor(account(singleCall = true), gemini))
        assertEquals(groq.defaultChatModel, chatModelFor(account(singleCall = true), groq))
    }

    @Test
    fun `the hint about a built-in default fires exactly when nobody chose the model`() {
        // Nothing chosen: whatever runs came from the preset.
        assertTrue(chatModelIsPresetDefault(account(), gemini))
        assertTrue(chatModelIsPresetDefault(account(singleCall = true), gemini))
        // A rewording model the user picked needs no explanation of where it came from.
        assertFalse(chatModelIsPresetDefault(account(chat = "gemini-3.6-flash"), gemini))
        // Nor does one handed over by the merged field.
        assertFalse(
            chatModelIsPresetDefault(account(transcription = "gemini-3.6-flash", singleCall = true), gemini),
        )
    }

    @Test
    fun `the transcription model stays out of it while the fields are separate`() {
        // Switch off means two fields, and the one the user is not looking at has no say.
        val a = account(transcription = "gemini-3.6-flash", singleCall = false)
        assertEquals(gemini.defaultChatModel, chatModelFor(a, gemini))
        assertTrue(chatModelIsPresetDefault(a, gemini))
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
