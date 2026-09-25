/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.translation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.settings.dictate.modelSizeLabel
import dev.patrickgold.florisboard.dictate.translate.TranslationCatalog
import dev.patrickgold.florisboard.dictate.translate.TranslationLanguage
import dev.patrickgold.florisboard.dictate.translate.TranslationLanguageNames
import dev.patrickgold.florisboard.dictate.translate.TranslationModelManager
import dev.patrickgold.florisboard.dictate.translate.translationFlagOf
import dev.patrickgold.florisboard.lib.compose.FlorisConfirmDeleteDialog
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import org.florisboard.lib.compose.stringRes

/**
 * The on-device translator's languages (issue #424): what is on the phone and what it takes, what can
 * be added and what that costs.
 *
 * Each row says its size before anything is downloaded, both numbers: the download, which is what a
 * metered connection pays, and the space on the phone, which is what stays — the files are packed for
 * the trip and are about half as large again unpacked. Once a language is installed only the second
 * one is still true, and the downloaded group's title adds them up.
 *
 * English is listed but never downloaded: every language's pack carries the directions to and from it,
 * and a pair without English on either side runs through it.
 */
@Composable
fun TranslationScreen() = FlorisScreen {
    title = stringRes(R.string.settings__translation__title)
    previewFieldVisible = true
    iconSpaceReserved = false

    val context = LocalContext.current

    content {
        val installed by TranslationModelManager.installed(context).collectAsState()
        val downloads by TranslationModelManager.downloads.collectAsState()
        val failures by TranslationModelManager.failures.collectAsState()
        var confirmDelete by remember { mutableStateOf<TranslationLanguage?>(null) }

        Text(
            text = stringRes(R.string.translate__settings_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        val installedLanguages = TranslationLanguageNames.sorted(installed).mapNotNull(TranslationCatalog::language)
        val usedBytes = installedLanguages.sumOf { it.installedBytes }
        val installedTitle = stringRes(R.string.translate__group_installed)
        PreferenceGroup(
            title = if (usedBytes > 0) "$installedTitle · ${modelSizeLabel(usedBytes)}" else installedTitle,
        ) {
            LanguageRow(
                code = TranslationCatalog.ENGLISH,
                name = TranslationLanguageNames.of(TranslationCatalog.ENGLISH),
                subtitle = stringRes(R.string.translate__english_included),
            )
            for (language in installedLanguages) {
                LanguageRow(
                    code = language.code,
                    name = TranslationLanguageNames.of(language.code),
                    subtitle = stringRes(R.string.translate__size_installed, "size" to modelSizeLabel(language.installedBytes)),
                    action = RowAction.DELETE,
                    onAction = { confirmDelete = language },
                )
            }
        }

        val available = TranslationLanguageNames.sorted(TranslationCatalog.languages.map { it.code } - installed)
            .mapNotNull(TranslationCatalog::language)
        PreferenceGroup(title = stringRes(R.string.translate__group_available)) {
            for (language in available) {
                val progress = downloads[language.code]
                val failed = failures[language.code] != null
                val subtitle = when {
                    progress != null -> stringRes(R.string.dictate__local_model_downloading)
                        .replace("{percent}", percent(progress).toString())
                    failed -> stringRes(R.string.dictate__local_model_download_failed)
                    else -> stringRes(
                        R.string.translate__size_download,
                        "download" to modelSizeLabel(language.downloadBytes),
                        "size" to modelSizeLabel(language.installedBytes),
                    )
                }
                LanguageRow(
                    code = language.code,
                    name = TranslationLanguageNames.of(language.code),
                    subtitle = subtitle,
                    isError = failed && progress == null,
                    progress = progress?.let { percent(it) / 100f },
                    action = if (progress != null) RowAction.CANCEL else RowAction.DOWNLOAD,
                    onAction = {
                        if (progress != null) {
                            TranslationModelManager.cancel(language)
                        } else {
                            TranslationModelManager.download(context, language)
                        }
                    },
                )
            }
        }

        confirmDelete?.let { language ->
            FlorisConfirmDeleteDialog(
                onConfirm = {
                    TranslationModelManager.delete(context, language)
                    confirmDelete = null
                },
                onDismiss = { confirmDelete = null },
                what = TranslationLanguageNames.of(language.code),
            )
        }
    }
}

private enum class RowAction { DOWNLOAD, CANCEL, DELETE }

private fun percent(progress: TranslationModelManager.Progress): Int =
    if (progress.total <= 0) 0 else (progress.downloaded * 100 / progress.total).toInt().coerceIn(0, 100)

/**
 * One language: its name, one line of what it costs or what is happening to it, and at most one action.
 * Two controls in a row was what broke the on-device model picker's names across lines; one is enough
 * here too, since a language is only ever downloadable, downloading or installed.
 */
@Composable
private fun LanguageRow(
    code: String,
    name: String,
    subtitle: String,
    isError: Boolean = false,
    progress: Float? = null,
    action: RowAction? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LanguageFlag(code)
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        when (action) {
            RowAction.DOWNLOAD -> IconButton(onClick = onAction) {
                Icon(Icons.Default.Download, contentDescription = stringRes(R.string.dictate__local_model_action_install))
            }
            RowAction.CANCEL -> IconButton(onClick = onAction) {
                Icon(Icons.Default.Close, contentDescription = stringRes(R.string.dictate__local_model_action_cancel))
            }
            RowAction.DELETE -> IconButton(onClick = onAction) {
                Icon(Icons.Default.Delete, contentDescription = stringRes(R.string.dictate__local_model_action_delete))
            }
            null -> Unit
        }
    }
}

/**
 * The flag in front of a language, in a fixed slot so every name starts on the same line. A language
 * with no flag of its own shows its code in a small badge of the same size instead (see
 * [dev.patrickgold.florisboard.dictate.translate.TranslationFlags]).
 */
@Composable
private fun LanguageFlag(code: String) {
    val flag = remember(code) { translationFlagOf(code) }
    Box(
        modifier = Modifier
            .padding(end = 16.dp)
            .size(width = 32.dp, height = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (flag != null) {
            Text(text = flag, fontSize = 22.sp)
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    text = code.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
