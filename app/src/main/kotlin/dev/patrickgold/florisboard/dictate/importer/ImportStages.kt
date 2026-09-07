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
 * They exist to be *named* on screen while they happen — one line at a time, not a checklist. Before
 * this the whole run up to the upload was called "Preparing…", which made the longest phase of a
 * shared video look like a hang.
 */
enum class ImportStage {
    /** Out of the temporary share grant and into our own cache. */
    COPY,

    /** Decode, run the VAD over it, cut it into pieces: whatever [ImportTranscriber] does before sending. */
    PREPARE,

    /** The bytes going up. Never reported for the on-device engine, where nothing leaves the phone. */
    UPLOAD,

    /** Waiting for the provider (or the local engine) to answer. */
    TRANSCRIBE,

    /** The history entry, with its copy of the audio. */
    FINISH,
}

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
 * The two questions the progress line has to answer before it can name a step (issue #337).
 *
 * Kept apart from [ImportTranscriber] and free of Android so the answers can be tested: whether there
 * is a video to unpack, and whether anything is decoded and cut at all — the latter being the exact
 * condition in `ImportTranscriber.split`, not a second guess at it.
 */
object ImportStages {

    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi")

    /** Whether [fileName] names a video container, which always has to be unpacked before sending. */
    fun looksLikeVideo(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    /**
     * Whether a file of [sizeBytes] going to a provider capped at [uploadLimitBytes] gets decoded and
     * cut before it is sent — the [ImportStage.PREPARE] step.
     *
     * [uploadLimitBytes] of 0 means the cap is **unknown**, never "unlimited", which is why an unknown
     * limit alone is not a reason to cut anything. On-device sends nothing, so a size limit means
     * nothing there either — but a video still has to be unpacked before the engine can read it.
     */
    fun prepares(
        isVideo: Boolean,
        sizeBytes: Long,
        uploadLimitBytes: Long,
        onDevice: Boolean,
    ): Boolean {
        val overLimit = uploadLimitBytes > 0L && sizeBytes > uploadLimitBytes
        return isVideo || (overLimit && !onDevice)
    }
}
