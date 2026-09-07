/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.importer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When an import decodes and cuts before it sends (issue #337).
 *
 * The progress line names that step while it happens, so the answer has to match what the transcriber
 * actually does: announce it where nothing is decoded and the line lies about a step that takes no
 * time; miss it and the longest phase of a shared video goes unnamed, which is the complaint this
 * whole feature answers.
 */
class ImportStagesTest {

    private val twentyFiveMb = 25L * 1024 * 1024

    @Test
    fun `a small voice note has nothing to prepare`() {
        assertFalse(
            ImportStages.prepares(
                isVideo = false,
                sizeBytes = 40_000,
                uploadLimitBytes = twentyFiveMb,
                onDevice = false,
            )
        )
    }

    @Test
    fun `a video is always unpacked, however small it is`() {
        assertTrue(
            ImportStages.prepares(
                isVideo = true,
                sizeBytes = 200_000,
                uploadLimitBytes = twentyFiveMb,
                onDevice = false,
            )
        )
    }

    @Test
    fun `audio over the limit is cut`() {
        assertTrue(
            ImportStages.prepares(
                isVideo = false,
                sizeBytes = 80L * 1024 * 1024,
                uploadLimitBytes = twentyFiveMb,
                onDevice = false,
            )
        )
    }

    @Test
    fun `an unknown limit is not a licence to cut`() {
        // 0 means the provider never said, never "unlimited" — the same rule the transcriber follows.
        assertFalse(
            ImportStages.prepares(
                isVideo = false,
                sizeBytes = 500L * 1024 * 1024,
                uploadLimitBytes = 0L,
                onDevice = false,
            )
        )
    }

    @Test
    fun `on-device ignores a size limit, because it sends nothing`() {
        assertFalse(
            ImportStages.prepares(
                isVideo = false,
                sizeBytes = 500L * 1024 * 1024,
                uploadLimitBytes = twentyFiveMb,
                onDevice = true,
            )
        )
    }

    @Test
    fun `on-device still unpacks a video`() {
        assertTrue(
            ImportStages.prepares(
                isVideo = true,
                sizeBytes = 200_000,
                uploadLimitBytes = 0L,
                onDevice = true,
            )
        )
    }

    @Test
    fun `video is recognised by extension, whatever the case`() {
        assertTrue(ImportStages.looksLikeVideo("Clip.MP4"))
        assertTrue(ImportStages.looksLikeVideo("holiday.mkv"))
        assertFalse(ImportStages.looksLikeVideo("PTT-20260101-WA0001.opus"))
        assertFalse(ImportStages.looksLikeVideo("mp4"), "a name that is only an extension is not one")
        assertFalse(ImportStages.looksLikeVideo(""))
    }
}
