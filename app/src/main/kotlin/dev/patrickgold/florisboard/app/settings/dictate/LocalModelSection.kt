/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.dictate

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.dictate.provider.LocalModelCatalog
import dev.patrickgold.florisboard.dictate.provider.LocalModelDownloads
import dev.patrickgold.florisboard.dictate.provider.LocalModelManager
import dev.patrickgold.florisboard.dictate.provider.LocalModelSpec
import org.florisboard.lib.compose.stringRes

/**
 * Provider-editor body for the on-device (offline) transcription provider (issue #104). Instead of an
 * API key + remote model picker, it lists the downloadable Whisper models with install / delete /
 * cancel actions and a live download progress bar, and lets the user pick which installed model is
 * active. The active model id is reported via [onActiveModelChange] and persisted by the caller when the
 * dialog is confirmed; installs/deletes take effect immediately on disk.
 */
@Composable
fun LocalModelSection(
    activeModelId: String,
    onActiveModelChange: (String) -> Unit,
) {
    val context = LocalContext.current

    // Downloads run app-scoped (issue #207) so they survive this dialog closing / the app being left; the
    // installed set is recomputed on a local delete tick and whenever a background download finishes.
    var refreshTick by remember { mutableStateOf(0) }
    val installedTick by LocalModelDownloads.installedTick.collectAsState()
    val installed = remember(refreshTick, installedTick) { LocalModelManager.installedIds(context).toSet() }
    val downloads by LocalModelDownloads.state.collectAsState()
    var pendingDelete by remember { mutableStateOf<LocalModelSpec?>(null) }

    val downloadFailed = stringRes(R.string.dictate__local_model_download_failed)
    val backgroundHint = stringRes(R.string.dictate__local_model_download_background)

    // When a background download finishes (tick increments) and nothing usable is active, adopt the freshly
    // installed model. The initial tick (0) is skipped so merely opening the dialog never changes the pick.
    LaunchedEffect(installedTick) {
        if (installedTick > 0) {
            val ids = LocalModelManager.installedIds(context)
            if (activeModelId !in ids) ids.firstOrNull()?.let { onActiveModelChange(it) }
        }
    }

    Column {
        Text(
            text = stringRes(R.string.dictate__local_models_header),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        LocalModelCatalog.all.forEach { spec ->
            val dl = downloads[spec.id]
            ModelRow(
                spec = spec,
                isInstalled = spec.id in installed,
                isActive = spec.id == activeModelId,
                downloadPercent = dl?.takeIf { it.error == null }?.percent,
                error = if (dl?.error != null) downloadFailed else null,
                onSelect = { if (spec.id in installed) onActiveModelChange(spec.id) },
                onInstall = {
                    LocalModelDownloads.clearError(spec.id)
                    LocalModelDownloads.start(context, spec)
                    // Tell the user right away that they can leave — it keeps going in the background.
                    Toast.makeText(context, backgroundHint, Toast.LENGTH_SHORT).show()
                },
                onCancel = { LocalModelDownloads.cancel(spec.id) },
                onDelete = { pendingDelete = spec },
            )
        }
    }

    pendingDelete?.let { spec ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(spec.displayName) },
            text = {
                Text(
                    stringRes(R.string.dictate__local_model_delete_confirm).replace("{model}", spec.displayName),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    LocalModelManager.delete(context, spec.id)
                    if (activeModelId == spec.id) onActiveModelChange("")
                    refreshTick++
                    pendingDelete = null
                }) { Text(stringRes(R.string.dictate__local_model_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringRes(R.string.action__cancel))
                }
            },
        )
    }
}

@Composable
private fun ModelRow(
    spec: LocalModelSpec,
    isInstalled: Boolean,
    isActive: Boolean,
    downloadPercent: Int?,
    error: String?,
    onSelect: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val downloading = downloadPercent != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = isActive,
            enabled = isInstalled && !downloading,
            onClick = onSelect,
        )
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = spec.displayName, style = MaterialTheme.typography.titleSmall)
            val status = when {
                downloading -> stringRes(R.string.dictate__local_model_downloading)
                    .replace("{percent}", downloadPercent.toString())
                isActive -> stringRes(R.string.dictate__local_model_status_active)
                isInstalled -> stringRes(R.string.dictate__local_model_status_installed)
                else -> spec.description
            }
            Text(
                text = error ?: status,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (downloading) {
                LinearProgressIndicator(
                    progress = { (downloadPercent ?: 0) / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        // Icon-only actions (keep the row compact); labels live on as the accessibility descriptions.
        when {
            downloading -> IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringRes(R.string.dictate__local_model_action_cancel),
                )
            }
            isInstalled -> IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringRes(R.string.dictate__local_model_action_delete),
                )
            }
            else -> IconButton(onClick = onInstall) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = stringRes(R.string.dictate__local_model_action_install),
                )
            }
        }
    }
}
