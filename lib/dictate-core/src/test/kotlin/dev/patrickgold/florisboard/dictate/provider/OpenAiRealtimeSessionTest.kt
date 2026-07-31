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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OpenAiRealtimeSessionTest {

    @Test
    fun liveTranscribeUsesCurrentMultilingualSessionShape() {
        val payload = buildOpenAiRealtimeSessionUpdate(
            RealtimeRequest(
                model = "gpt-live-transcribe",
                languages = listOf("en", "yue-HK", "zh-TW", "en-US"),
                prompt = "  A multilingual meeting.  ",
                keywords = listOf("ASD", "JUPAS", "ASD", "bad<keyword", "two\nlines"),
                delay = " MEDIUM ",
            )
        )

        val input = Json.parseToJsonElement(payload).jsonObject["session"]!!.jsonObject["audio"]!!
            .jsonObject["input"]!!.jsonObject
        val format = input["format"]!!.jsonObject
        val transcription = input["transcription"]!!.jsonObject

        assertEquals("audio/pcm", format["type"]!!.jsonPrimitive.content)
        assertEquals(24_000, format["rate"]!!.jsonPrimitive.content.toInt())
        assertEquals(JsonNull, input["turn_detection"])
        assertEquals("gpt-live-transcribe", transcription["model"]!!.jsonPrimitive.content)
        assertEquals("A multilingual meeting.", transcription["prompt"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("ASD", "JUPAS"),
            transcription["keywords"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("en", "yue", "zh-tw"),
            transcription["languages"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("medium", transcription["delay"]!!.jsonPrimitive.content)
        assertFalse("language" in transcription)
    }

    @Test
    fun legacyRealtimeModelKeepsSingularLanguageShape() {
        val payload = buildOpenAiRealtimeSessionUpdate(
            RealtimeRequest(
                model = "gpt-realtime-whisper",
                language = "yue-HK",
                languages = listOf("en", "zh-TW"),
                prompt = "Ignored by the legacy shape",
                keywords = listOf("Ignored"),
                delay = "low",
            )
        )

        val input = Json.parseToJsonElement(payload).jsonObject["session"]!!.jsonObject["audio"]!!
            .jsonObject["input"]!!.jsonObject
        val transcription = input["transcription"]!!.jsonObject

        assertEquals(JsonNull, input["turn_detection"])
        assertEquals("gpt-realtime-whisper", transcription["model"]!!.jsonPrimitive.content)
        assertEquals("yue-HK", transcription["language"]!!.jsonPrimitive.content)
        assertFalse("languages" in transcription)
        assertFalse("prompt" in transcription)
        assertFalse("keywords" in transcription)
        assertFalse("delay" in transcription)
    }

    @Test
    fun openAiDefaultsToRecommendedLiveTranscriptionModel() {
        assertEquals("gpt-live-transcribe", ProviderRegistry.OPENAI.defaultRealtimeModel)
        assertEquals(true, "gpt-transcribe" in ProviderRegistry.OPENAI.curatedTranscriptionModels)
        assertEquals(
            "gpt-live-transcribe",
            ProviderRegistry.OPENAI.curatedRealtimeModels.first(),
        )
        assertEquals(true, "gpt-realtime-whisper" in ProviderRegistry.OPENAI.curatedRealtimeModels)
    }
}
