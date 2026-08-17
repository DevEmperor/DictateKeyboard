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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Invariants of the on-device model catalog.
 *
 * These matter because the catalog is now what decides whether a model counts as installed and which
 * recognizer gets built for it. A spec whose declared files do not match its kind does not fail here —
 * it fails natively, on a phone, after a several-hundred-megabyte download.
 */
class LocalModelCatalogTest {

    private val encoder = LocalTranscriptionProvider.ENCODER
    private val decoder = LocalTranscriptionProvider.DECODER
    private val joiner = LocalTranscriptionProvider.JOINER
    private val tokens = LocalTranscriptionProvider.TOKENS
    private val model = LocalTranscriptionProvider.MODEL
    private val vad = LocalTranscriptionProvider.VAD

    private fun names(spec: LocalModelSpec) = spec.files.map { it.destName }

    @Test
    fun `every model declares its tokens and nothing twice`() {
        for (spec in LocalModelCatalog.all + LocalModelCatalog.SMART_TURN) {
            val destNames = names(spec)
            assertEquals(
                destNames.distinct(), destNames,
                "${spec.id} declares the same destination file more than once",
            )
        }
        for (spec in LocalModelCatalog.all) {
            assertTrue(tokens in names(spec), "${spec.id} has no tokens file")
        }
    }

    @Test
    fun `ids are unique and usable as directory names`() {
        val ids = LocalModelCatalog.all.map { it.id }
        assertEquals(ids.distinct(), ids, "duplicate model id in the catalog")
        for (id in ids) {
            assertTrue(id.isNotBlank() && '/' !in id && '\\' !in id, "'$id' is not a usable directory name")
        }
    }

    @Test
    fun `the file shape matches the recognizer the model asks for`() {
        for (spec in LocalModelCatalog.all) {
            val files = names(spec)
            when (spec.kind) {
                LocalModelKind.WHISPER, LocalModelKind.CANARY -> {
                    assertTrue(encoder in files && decoder in files, "${spec.id} needs an encoder and a decoder")
                    assertTrue(model !in files, "${spec.id} is not a single-file model")
                }
                LocalModelKind.NEMO_TRANSDUCER -> {
                    assertTrue(
                        encoder in files && decoder in files && joiner in files,
                        "${spec.id} is a transducer and needs encoder, decoder and joiner",
                    )
                }
                // SenseVoice (#262): one non-autoregressive file, no encoder/decoder pair at all.
                LocalModelKind.SENSE_VOICE -> {
                    assertTrue(model in files, "${spec.id} needs its single model file")
                    assertTrue(
                        encoder !in files && decoder !in files && joiner !in files,
                        "${spec.id} should not declare encoder/decoder/joiner",
                    )
                }
            }
        }
    }

    @Test
    fun `streaming models bring no VAD and offline ones do`() {
        for (spec in LocalModelCatalog.streaming) {
            // They detect speech pauses themselves (endpointing), so a VAD companion would be dead weight.
            assertTrue(vad !in names(spec), "${spec.id} streams and does not need the VAD")
            assertTrue(spec.kind == LocalModelKind.NEMO_TRANSDUCER, "${spec.id} streams but is not a transducer")
        }
        for (spec in LocalModelCatalog.batchOnly) {
            // Without it, anything past one model window is silently cut off.
            assertTrue(vad in names(spec), "${spec.id} transcribes in one shot and needs the VAD to segment")
        }
    }

    @Test
    fun `the chinese-capable model is offered and points at the original`() {
        val spec = assertNotNull(LocalModelCatalog.byId("sense-voice-small"), "SenseVoice is missing (#262)")
        assertTrue(spec in LocalModelCatalog.all, "SenseVoice is not offered in the picker")
        // The 2025-09-09 export under a near-identical name is a Cantonese fine-tune, not a newer
        // version — pinning the hash here is what keeps that mistake from creeping back in.
        val modelFile = spec.files.first { it.destName == model }
        assertTrue(
            modelFile.sha256 == "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
            "SenseVoice model file is not the 2024-07-17 FunAudioLLM export",
        )
    }

    /**
     * The wizard's shortlist (#273). What is being protected here is not the exact pair but the property
     * that makes the offer safe to accept blindly: during setup the input language is still on
     * auto-detect, so a model that has to be *told* its language would be handed the wrong one.
     */
    @Test
    fun `the onboarding shortlist only offers models that find the language themselves`() {
        val languages = listOf("de", "en", "ru", "zh", "yue", "ja", "ko", "fr", "th", "", "de-DE", "zh_CN")
        for (language in languages) {
            val picks = LocalModelCatalog.onboardingPicks(language)
            assertEquals(2, picks.size, "'$language' should be offered exactly two models")
            assertEquals(picks.distinct(), picks, "'$language' offers the same model twice")
            for (spec in picks) {
                assertTrue(spec in LocalModelCatalog.all, "${spec.id} is not in the catalog")
                assertTrue(
                    !spec.isStreaming,
                    "${spec.id} is a live model and only works with real-time transcription switched on",
                )
                // Canary is told its language and would default to English (LocalTranscriptionProvider).
                assertTrue(
                    spec.kind != LocalModelKind.CANARY,
                    "${spec.id} must be told its language, which setup has not asked for yet",
                )
            }
        }
    }

    @Test
    fun `the onboarding shortlist prefers the specialized model where one exists`() {
        assertEquals("sense-voice-small", LocalModelCatalog.onboardingPicks("zh").first().id)
        assertEquals("sense-voice-small", LocalModelCatalog.onboardingPicks("ja").first().id)
        assertEquals("gigaam-v2-ru", LocalModelCatalog.onboardingPicks("ru").first().id)
        // German gets the specialized one as the bigger alternative, not as the default: it is 670 MB.
        assertEquals("parakeet-primeline-de", LocalModelCatalog.onboardingPicks("de")[1].id)
        assertEquals("whisper-base.en", LocalModelCatalog.onboardingPicks("en").first().id)
        assertEquals("whisper-base", LocalModelCatalog.onboardingPicks("fr").first().id)
        // A region or script suffix must not fall through to the default.
        assertEquals(
            LocalModelCatalog.onboardingPicks("zh"),
            LocalModelCatalog.onboardingPicks("zh-Hans-CN"),
        )
    }

    @Test
    fun `every downloadable file is verifiable`() {
        for (spec in LocalModelCatalog.all + LocalModelCatalog.SMART_TURN) {
            for (file in spec.files) {
                assertTrue(file.sizeBytes > 0, "${spec.id}/${file.destName} has no size to check against")
                assertTrue(
                    file.sha256?.length == 64,
                    "${spec.id}/${file.destName} has no usable SHA-256",
                )
                assertTrue(file.url.startsWith("https://"), "${spec.id}/${file.destName} is not fetched over https")
            }
        }
    }
}
