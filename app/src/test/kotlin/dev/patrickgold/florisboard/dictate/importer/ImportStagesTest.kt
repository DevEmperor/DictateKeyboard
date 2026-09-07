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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which steps an import promises the user, and when (issue #337).
 *
 * The list is shown before any of it runs, so it is a promise: a row that never becomes active leaves
 * the screen looking stuck at the step before it, and a step that runs without a row makes the whole
 * list go quiet while something is clearly happening. Both failures are the exact complaint this
 * feature answers, which is why the conditions are pinned down here rather than only in the
 * transcriber.
 */
class ImportStagesTest {

    private val twentyFiveMb = 25L * 1024 * 1024

    @Test
    fun `a small voice note has nothing to prepare`() {
        val plan = ImportStages.plan(
            isVideo = false,
            sizeBytes = 40_000,
            uploadLimitBytes = twentyFiveMb,
            onDevice = false,
        )
        assertEquals(
            listOf(ImportStage.COPY, ImportStage.UPLOAD, ImportStage.TRANSCRIBE, ImportStage.FINISH),
            plan.stages,
        )
        assertFalse(plan.fromVideo)
    }

    @Test
    fun `a video is always unpacked, however small it is`() {
        val plan = ImportStages.plan(
            isVideo = true,
            sizeBytes = 200_000,
            uploadLimitBytes = twentyFiveMb,
            onDevice = false,
        )
        assertTrue(ImportStage.PREPARE in plan.stages, "a video has to be decoded before anything is sent")
        assertTrue(plan.fromVideo, "the wording has to say it is a video being unpacked")
    }

    @Test
    fun `audio over the limit is cut, and says so without mentioning video`() {
        val plan = ImportStages.plan(
            isVideo = false,
            sizeBytes = 80L * 1024 * 1024,
            uploadLimitBytes = twentyFiveMb,
            onDevice = false,
        )
        assertTrue(ImportStage.PREPARE in plan.stages)
        assertFalse(plan.fromVideo)
    }

    @Test
    fun `an unknown limit is not a licence to cut`() {
        // 0 means the provider never said, never "unlimited" — the same rule the transcriber follows.
        val plan = ImportStages.plan(
            isVideo = false,
            sizeBytes = 500L * 1024 * 1024,
            uploadLimitBytes = 0L,
            onDevice = false,
        )
        assertFalse(ImportStage.PREPARE in plan.stages)
    }

    @Test
    fun `on-device never uploads`() {
        val plan = ImportStages.plan(
            isVideo = false,
            sizeBytes = 500L * 1024 * 1024,
            uploadLimitBytes = twentyFiveMb,
            onDevice = true,
        )
        assertFalse(ImportStage.UPLOAD in plan.stages, "nothing leaves the phone")
        assertFalse(
            ImportStage.PREPARE in plan.stages,
            "a size limit is an upload limit, so it means nothing where there is no upload",
        )
    }

    @Test
    fun `on-device still unpacks a video`() {
        val plan = ImportStages.plan(
            isVideo = true,
            sizeBytes = 200_000,
            uploadLimitBytes = 0L,
            onDevice = true,
        )
        assertEquals(
            listOf(ImportStage.COPY, ImportStage.PREPARE, ImportStage.TRANSCRIBE, ImportStage.FINISH),
            plan.stages,
        )
    }

    @Test
    fun `every plan starts with the copy and ends with the history entry`() {
        for (isVideo in listOf(true, false)) {
            for (onDevice in listOf(true, false)) {
                val stages = ImportStages.plan(isVideo, 90L * 1024 * 1024, twentyFiveMb, onDevice).stages
                assertEquals(ImportStage.COPY, stages.first())
                assertEquals(ImportStage.FINISH, stages.last())
            }
        }
    }

    @Test
    fun `a stage is done, running or still to come`() {
        assertEquals(StageState.DONE, ImportStages.stateOf(ImportStage.COPY, ImportStage.UPLOAD))
        assertEquals(StageState.ACTIVE, ImportStages.stateOf(ImportStage.UPLOAD, ImportStage.UPLOAD))
        assertEquals(StageState.PENDING, ImportStages.stateOf(ImportStage.FINISH, ImportStage.UPLOAD))
    }

    @Test
    fun `the second piece puts the upload back in front of the transcription`() {
        // Deliberate: with several pieces the run really does go back to uploading, and the piece
        // counter beside it is what makes that read as progress instead of a stutter.
        assertEquals(StageState.PENDING, ImportStages.stateOf(ImportStage.TRANSCRIBE, ImportStage.UPLOAD))
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
