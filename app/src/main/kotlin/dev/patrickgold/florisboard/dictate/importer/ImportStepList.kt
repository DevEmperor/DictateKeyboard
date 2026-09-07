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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import org.florisboard.lib.compose.stringRes
import kotlin.math.roundToInt

/**
 * The steps of a running import, listed from the start and ticked off as they pass (issue #337).
 *
 * The list is the whole idea: a shared video goes through four or five different jobs before its text
 * appears, and naming only the one at the front — "Preparing…" for as long as a decode takes — made a
 * working app look like a stuck one. Which rows appear is settled before anything runs
 * ([ImportStages.plan]), so nothing is added or removed while the user is watching; a step that
 * cannot measure itself simply shows an indeterminate bar rather than a number nobody could stand
 * behind.
 */
@Composable
fun ImportStepList(
    plan: ImportPlan,
    progress: ImportProgress,
    providerName: String,
    onDevice: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (stage in plan.stages) {
            val state = ImportStages.stateOf(stage, progress.stage)
            StepRow(
                label = labelFor(stage, plan.fromVideo, providerName, onDevice),
                detail = if (state == StageState.ACTIVE) detailFor(progress) else null,
                state = state,
            )
            // Only under the step that is running, and only there: a bar on a finished row would keep
            // claiming work that is over.
            if (state == StageState.ACTIVE) {
                val fraction = progress.fraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(start = 36.dp, top = 2.dp, bottom = 2.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(start = 36.dp, top = 2.dp, bottom = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StepRow(label: String, detail: String?, state: StageState) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (state) {
                StageState.DONE -> Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                StageState.ACTIVE -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                StageState.PENDING -> Icon(
                    imageVector = Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = PENDING_ALPHA),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            // The running step is the one being read; the ones still to come are there to be counted,
            // not read, so they step back rather than compete with it.
            color = when (state) {
                StageState.ACTIVE -> MaterialTheme.colorScheme.onSurface
                StageState.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
                StageState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = PENDING_ALPHA)
            },
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun labelFor(
    stage: ImportStage,
    fromVideo: Boolean,
    providerName: String,
    onDevice: Boolean,
): String = when (stage) {
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

/** The piece counter and the percentage, whichever of them this moment actually has. */
@Composable
private fun detailFor(progress: ImportProgress): String? {
    val parts = buildList {
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
    return parts.joinToString(" · ").ifBlank { null }
}

private const val PENDING_ALPHA = 0.45f
