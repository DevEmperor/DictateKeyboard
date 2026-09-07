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

import androidx.compose.runtime.Composable
import dev.patrickgold.florisboard.R
import org.florisboard.lib.compose.stringRes
import kotlin.math.roundToInt

/**
 * One line saying what the import is doing right now (issue #337).
 *
 * The screen it belongs to shows a single sentence over a progress bar, which is what a share screen
 * was before and what it is again: the point of the issue was never a checklist, it was that
 * "Preparing…" stood for four different jobs and told you nothing about which of them was running.
 * Naming the step fixes that on one line.
 *
 * The piece counter and the percentage ride along on the same line, in that order — which piece, then
 * how far into it — because both qualify the step rather than replacing it. The bar underneath shows
 * the same fraction; the number is here for the difference between "moving" and "moving this fast".
 */
@Composable
fun importStatusLine(
    progress: ImportProgress,
    fromVideo: Boolean,
    providerName: String,
    onDevice: Boolean,
): String {
    val label = when (progress.stage) {
        ImportStage.COPY -> stringRes(R.string.dictate__import_step_copy)
        // Same pass either way; what differs is why it is happening, and that is the part worth saying.
        ImportStage.PREPARE -> if (fromVideo) {
            stringRes(R.string.dictate__import_step_extract)
        } else {
            stringRes(R.string.dictate__import_step_prepare)
        }
        ImportStage.UPLOAD -> stringRes(R.string.dictate__import_step_upload)
        ImportStage.TRANSCRIBE -> if (onDevice || providerName.isBlank()) {
            stringRes(R.string.dictate__import_step_transcribe_local)
        } else {
            stringRes(R.string.dictate__import_step_transcribe, "provider" to providerName)
        }
        ImportStage.FINISH -> stringRes(R.string.dictate__import_step_finish)
    }
    val details = buildList {
        if (progress.partCount > 1) {
            add(
                stringRes(
                    R.string.dictate__import_step_part,
                    "current" to progress.part.toString(),
                    "total" to progress.partCount.toString(),
                )
            )
        }
        progress.fraction?.let {
            add(stringRes(R.string.dictate__import_step_percent, "v" to (it * 100).roundToInt().toString()))
        }
    }
    return if (details.isEmpty()) label else label + " · " + details.joinToString(" · ")
}
