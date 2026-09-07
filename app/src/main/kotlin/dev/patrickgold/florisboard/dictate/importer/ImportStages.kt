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

/**
 * The stages of one import, in the order they run (issue #337).
 *
 * The declaration order **is** the running order — [ImportStages.stateOf] compares ordinals to decide
 * what is already done — so nothing may be inserted here without checking that it really happens at
 * that point.
 */
enum class ImportStage {
    /** Out of the temporary share grant and into our own cache. */
    COPY,

    /** Decode, run the VAD over it, cut it into pieces: whatever [ImportTranscriber] does before sending. */
    PREPARE,

    /** The bytes going up. Absent entirely for the on-device engine, where nothing leaves the phone. */
    UPLOAD,

    /** Waiting for the provider (or the local engine) to answer. */
    TRANSCRIBE,

    /** The history entry, with its copy of the audio. */
    FINISH,
}

/** How a stage stands relative to the one currently running. */
enum class StageState { PENDING, ACTIVE, DONE }

/**
 * Where an import currently is.
 *
 * [fraction] is `null` wherever the step cannot honestly measure itself — a decode has no byte count
 * to count against, and a provider thinking about a file reports nothing at all. A missing number
 * means an indeterminate bar, never a made-up one.
 */
data class ImportProgress(
    val stage: ImportStage,
    val fraction: Float? = null,
    /** 1-based piece being worked on, or 0 when the file is not split. */
    val part: Int = 0,
    val partCount: Int = 0,
)

/**
 * The list of stages an import will really go through, decided **before** it starts.
 *
 * [fromVideo] only picks the wording for [ImportStage.PREPARE]: the same decode-and-cut pass is
 * "getting the sound out of the video" for a shared clip and "getting the audio ready" for an
 * oversized recording, and saying which one is the whole point of showing the step at all.
 */
data class ImportPlan(
    val stages: List<ImportStage>,
    val fromVideo: Boolean,
)

/**
 * Which steps an import takes, and which of them are already behind it (issue #337).
 *
 * Kept apart from [ImportTranscriber] and free of Android so the answer can be tested: the screen
 * promises this list to the user the moment the file arrives, so it has to match what the transcriber
 * actually does — the conditions below are the ones in `ImportTranscriber.split`, not a second guess
 * at them.
 */
object ImportStages {

    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi")

    /** Whether [fileName] names a video container, which always has to be unpacked before sending. */
    fun looksLikeVideo(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    /**
     * The stages for a file of [sizeBytes] going to a provider capped at [uploadLimitBytes].
     *
     * [uploadLimitBytes] of 0 means the cap is **unknown**, never "unlimited" — the same rule the
     * transcriber follows, and the reason an unknown limit alone never puts the cutting step on the
     * list. On-device skips the upload, and skips the cutting too unless the file is a video, which
     * has to be unpacked whether or not anything is ever sent.
     */
    fun plan(
        isVideo: Boolean,
        sizeBytes: Long,
        uploadLimitBytes: Long,
        onDevice: Boolean,
    ): ImportPlan {
        val overLimit = uploadLimitBytes > 0L && sizeBytes > uploadLimitBytes
        val prepares = isVideo || (overLimit && !onDevice)
        val stages = buildList {
            add(ImportStage.COPY)
            if (prepares) add(ImportStage.PREPARE)
            if (!onDevice) add(ImportStage.UPLOAD)
            add(ImportStage.TRANSCRIBE)
            add(ImportStage.FINISH)
        }
        return ImportPlan(stages = stages, fromVideo = isVideo)
    }

    /**
     * [stage] seen from [current].
     *
     * With several pieces the run goes upload → transcribe → upload again, so a stage that was done a
     * moment ago legitimately becomes pending again. That is not a glitch to smooth over: the piece
     * counter next to it says which round this is.
     */
    fun stateOf(stage: ImportStage, current: ImportStage): StageState = when {
        stage.ordinal < current.ordinal -> StageState.DONE
        stage == current -> StageState.ACTIVE
        else -> StageState.PENDING
    }
}
