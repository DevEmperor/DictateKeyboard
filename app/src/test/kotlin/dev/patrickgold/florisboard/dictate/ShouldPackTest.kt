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

import kotlin.test.Test
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

    /** OpenAI and Groq; every other provider reports 0, meaning "we do not know". */
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
    fun `an empty or tiny file is never packed`() {
        assertFalse(pack(0L))
        assertFalse(pack(0L, known))
        assertFalse(pack(1024L, known))
    }

    private fun assertEqualsDecision(a: Boolean, b: Boolean) = assertTrue(a == b, "decisions differ")
}
