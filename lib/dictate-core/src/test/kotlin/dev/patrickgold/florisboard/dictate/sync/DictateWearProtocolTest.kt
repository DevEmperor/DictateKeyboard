/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.sync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The watch and the phone update separately (#363), so each side has to understand the other at the
 * previous protocol as well as the current one.
 */
class DictateWearProtocolTest : FunSpec({

    test("a named request carries its id; the old fixed path is a request without one") {
        val id = "3f1c2a9e-0b7d-4c55-9a51-6e2f0d8b7a10"
        DictateWearProtocol.requestIdOf(DictateWearProtocol.requestPath(id)) shouldBe id
        DictateWearProtocol.requestIdOf(DictateWearProtocol.PATH_TRANSCRIBE_REQUEST) shouldBe ""
    }

    test("nothing else is taken for a request or a cancel") {
        DictateWearProtocol.requestIdOf(DictateWearProtocol.PATH_TRANSCRIBE_REQUEST + "/") shouldBe null
        DictateWearProtocol.requestIdOf(DictateWearProtocol.PATH_TRANSCRIBE_REQUEST + "s/abc") shouldBe null
        DictateWearProtocol.requestIdOf(DictateWearProtocol.PATH_TRANSCRIBE_REQUEST + "/a/b") shouldBe null
        DictateWearProtocol.requestIdOf(DictateWearProtocol.PATH_SYNC_REQUEST) shouldBe null
        DictateWearProtocol.cancelledIdOf(DictateWearProtocol.cancelPath("abc")) shouldBe "abc"
        DictateWearProtocol.cancelledIdOf(DictateWearProtocol.responsePath("abc")) shouldBe null
    }

    test("an answer for one request is not on the path of another") {
        (DictateWearProtocol.responsePath("a") == DictateWearProtocol.responsePath("b")) shouldBe false
        (DictateWearProtocol.responsePath("a") == DictateWearProtocol.PATH_TRANSCRIBE_RESPONSE) shouldBe false
    }

    test("a snapshot from a phone that predates the fields reads as protocol 0 with no timeout") {
        val old = """{"transcriptionProviderId":"soniox","model":"stt-async-v5"}"""
        val settings = DictateSyncedSettings.decode(old)!!
        settings.tetherProtocol shouldBe 0
        settings.requestTimeoutSeconds shouldBe 0
    }

    test("a watch that predates the fields still reads a new snapshot") {
        val new = DictateSyncedSettings(requestTimeoutSeconds = 45, tetherProtocol = DictateWearProtocol.TETHER_PROTOCOL)
        val decoded = DictateSyncedSettings.decode(new.encode())!!
        decoded.requestTimeoutSeconds shouldBe 45
        decoded.tetherProtocol shouldBe DictateWearProtocol.TETHER_PROTOCOL
        // The old watch's schema without the two fields: unknown keys are ignored, not an error.
        DictateWearProtocol.json.decodeFromString(OldSettings.serializer(), new.encode()).model shouldBe ""
    }

    test("the rewording effort travels to the watch, and a phone without it leaves the field out") {
        val new = DictateSyncedSettings(chatModel = "gpt-6-luna", rewordingReasoningEffort = "none")
        DictateSyncedSettings.decode(new.encode())!!.rewordingReasoningEffort shouldBe "none"
        val old = """{"transcriptionProviderId":"openai","chatModel":"gpt-6-luna"}"""
        DictateSyncedSettings.decode(old)!!.rewordingReasoningEffort shouldBe null
        DictateWearProtocol.json.decodeFromString(OldSettings.serializer(), new.encode()).model shouldBe ""
    }

    test("the transcription effort travels to the watch, and an older watch reads the settings regardless (#477)") {
        val new = DictateSyncedSettings(model = "gemini-3.8-flash", transcriptionReasoningEffort = "none")
        DictateSyncedSettings.decode(new.encode())!!.transcriptionReasoningEffort shouldBe "none"
        val old = """{"transcriptionProviderId":"gemini","model":"gemini-3.8-flash"}"""
        DictateSyncedSettings.decode(old)!!.transcriptionReasoningEffort shouldBe null
        DictateWearProtocol.json.decodeFromString(OldSettings.serializer(), new.encode()).model shouldBe "gemini-3.8-flash"
    }
})

@kotlinx.serialization.Serializable
private data class OldSettings(val transcriptionProviderId: String = "openai", val model: String = "")
