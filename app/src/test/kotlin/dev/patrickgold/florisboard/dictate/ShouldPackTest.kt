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

import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a recording is packed into AAC before upload (issue #281).
 *
 * Recording is 16 kHz mono 16-bit WAV, so the arithmetic below reads in seconds: 32 kB per second.
 * The rule has to hold two things apart — packing is *worth it* on a long dictation, and *necessary*
 * once the provider would refuse the WAV outright.
 */
class ShouldPackTest {

    private val kbPerSecond = 32L * 1024
    private fun seconds(n: Int) = n * kbPerSecond
    private fun mib(n: Int) = n * 1024L * 1024L

    /** OpenAI, Groq and OpenRouter; every other provider reports 0, meaning "we do not know". */
    private val known = mib(25)
    private val unknown = 0L

    private fun pack(bytes: Long, limit: Long = unknown) = DictateController.shouldPack(bytes, limit)

    @Test
    fun `short dictations are left exactly as they were`() {
        // Measured on a device: 2.5 s cost 113 ms to pack and saved 58 kB, which is 15 ms of upload on
        // a fast connection. The common path must not pay for a feature aimed at long recordings.
        assertFalse(pack(seconds(3)))
        assertFalse(pack(seconds(30)))
        assertFalse(pack(seconds(60 * 5)))
        assertFalse(pack(seconds(60 * 8)))
    }

    @Test
    fun `a long dictation is packed well before the limit is in sight`() {
        assertTrue(pack(mib(16) + 1))
        assertTrue(pack(seconds(60 * 9)))
        assertTrue(pack(seconds(60 * 14)))
    }

    @Test
    fun `the reported case is packed`() {
        // 14 minutes: 26.9 MB of WAV against a 25 MiB limit — refused outright before this existed.
        assertTrue(pack(seconds(60 * 14), known))
    }

    @Test
    fun `a known limit brings the decision forward, never postpones it`() {
        // Three quarters of 25 MiB is 18.75 MiB, above the 16 MiB rule, so the general threshold still
        // decides for these two — the limit clause may only ever add cases.
        assertTrue(pack(mib(17), known))
        assertFalse(pack(mib(10), known))
        // A provider with a smaller limit is caught by it, though: three quarters of 8 MiB is 6.
        assertTrue(pack(mib(7), mib(8)))
        assertFalse(pack(mib(5), mib(8)))
    }

    @Test
    fun `an unknown limit is not read as permission`() {
        // 0 means "no figure documented", not "no ceiling". A big recording is packed either way.
        assertTrue(pack(mib(20), unknown))
        assertEqualsDecision(pack(mib(20), unknown), pack(mib(20), known))
    }

    @Test
    fun `gemini is the provider whose own limit decides before the general rule`() {
        // Gemini caps the whole request at 20 MB and carries the audio base64-inline, so the audio may
        // not pass ~15 MB — under the 16 MiB rule. Three quarters of that is 11.25 MiB, about six
        // minutes of speech, and packing has to start there rather than at nine.
        val gemini = ProviderRegistry.maxUploadBytes("gemini")
        assertTrue(gemini in 1 until mib(16), "gemini limit $gemini is not below the general threshold")
        assertTrue(pack(mib(12), gemini))
        assertFalse(pack(mib(12)), "without a limit this size is left alone")
    }

    @Test
    fun `providers with room to spare never trip the limit clause`() {
        // Their ceilings are in gigabytes, so only the general threshold can ever decide for them.
        for (id in listOf("elevenlabs", "deepgram", "assemblyai")) {
            val limit = ProviderRegistry.maxUploadBytes(id)
            assertTrue(limit > mib(1024), "$id should have a documented ceiling far above a dictation")
            assertFalse(pack(mib(10), limit), "$id packed a 10 MiB recording it had no trouble with")
            assertTrue(pack(mib(20), limit), "$id must still follow the general threshold")
        }
    }

    @Test
    fun `the provider reachable from mainland china carries its own ceiling`() {
        // SiliconFlow (#262) documents 50 MB. Three quarters of that is 37.5 MiB, well above the general
        // threshold, so the 16 MiB rule keeps deciding — the limit clause may only ever add cases.
        val siliconflow = ProviderRegistry.maxUploadBytes("siliconflow")
        assertTrue(siliconflow == mib(50), "expected a documented 50 MiB ceiling, got $siliconflow")
        assertFalse(pack(mib(10), siliconflow))
        assertTrue(pack(mib(20), siliconflow))
    }

    @Test
    fun `openrouter carries a ceiling at all, which is the half that was missing`() {
        // #321: OpenRouter documents 25 MB for a multipart upload, and the table simply did not know it.
        // Packing was never the part that suffered — three quarters of 25 MiB is above the general
        // threshold, so the 16 MiB rule keeps deciding here as it does for OpenAI. What suffered is
        // every caller that asks "is there a figure at all": the import screen's size check and the
        // splitter both read 0 as "nothing to check against", so a shared recording went out whole and
        // came back refused. A number, any number, is what turns those two back on.
        val openrouter = ProviderRegistry.maxUploadBytes("openrouter")
        assertTrue(openrouter > 0L, "openrouter must document a ceiling, not report 0 = unknown")
        assertEquals(mib(25), openrouter)
        assertFalse(pack(mib(10), openrouter))
        assertTrue(pack(mib(20), openrouter))
    }

    @Test
    fun `a provider without a documented size stays unknown rather than unlimited`() {
        // Mistral and Soniox document a duration, not a size. Guessing bytes from hours would be
        // inventing a number; 0 keeps the general threshold in charge.
        for (id in listOf("mistral", "soniox")) {
            assertTrue(ProviderRegistry.maxUploadBytes(id) == 0L, "$id should report an unknown limit")
        }
        assertTrue(pack(mib(20), 0L))
        assertFalse(pack(mib(10), 0L))
    }

    @Test
    fun `an empty or tiny file is never packed`() {
        assertFalse(pack(0L))
        assertFalse(pack(0L, known))
        assertFalse(pack(1024L, known))
    }

    private fun assertEqualsDecision(a: Boolean, b: Boolean) = assertTrue(a == b, "decisions differ")
}
