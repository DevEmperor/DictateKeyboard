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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.dictate.DictatePromptsLayout
import dev.patrickgold.florisboard.dictate.DictateReasoningEffort
import dev.patrickgold.florisboard.dictate.data.prompts.DictatePromptDefaults
import dev.patrickgold.florisboard.dictate.data.prompts.PromptsDatabaseHelper
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.datastore.ui.listPrefEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.florisboard.lib.compose.stringRes

/**
 * Rewording (AI) settings: the master toggle, the dedicated chat provider/key/model, prompt
 * management and the system/auto-formatting options. The transcription provider is configured on the
 * main Dictate screen; this screen is purely about the rewording (GPT) side (roadmap section 4, P2).
 */
@Composable
fun DictateRewordingScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__rewording_title)
    previewFieldVisible = true
    iconSpaceReserved = true

    val prefs by FlorisPreferenceStore

    content {
        val navController = LocalNavController.current
        val context = LocalContext.current

        SwitchPreference(
            prefs.dictate.rewordingEnabled,
            icon = Icons.Default.AutoFixHigh,
            modifier = Modifier.settingsSearchAnchor("dictate__rewording_enabled_title"),
            title = stringRes(R.string.dictate__rewording_enabled_title),
            summary = stringRes(R.string.dictate__rewording_enabled_summary),
        )

        ListPreference(
            prefs.dictate.promptsLayout,
            icon = Icons.Default.ViewAgenda,
            modifier = Modifier.settingsSearchAnchor("dictate__prompts_layout_title"),
            title = stringRes(R.string.dictate__prompts_layout_title),
            entries = listPrefEntries {
                entry(
                    key = DictatePromptsLayout.PANEL,
                    label = stringRes(R.string.dictate__prompts_layout_panel_label),
                    description = stringRes(R.string.dictate__prompts_layout_panel_description),
                )
                entry(
                    key = DictatePromptsLayout.ROW,
                    label = stringRes(R.string.dictate__prompts_layout_row_label),
                    description = stringRes(R.string.dictate__prompts_layout_row_description),
                )
            },
            enabledIf = { prefs.dictate.rewordingEnabled isEqualTo true },
        )

        val promptCount by produceState(initialValue = -1) {
            value = withContext(Dispatchers.IO) {
                PromptsDatabaseHelper.getInstance(context).count()
            }
        }
        Preference(
            icon = Icons.Default.ListAlt,
            modifier = Modifier.settingsSearchAnchor("dictate__manage_prompts_title"),
            title = stringRes(R.string.dictate__manage_prompts_title),
            summary = if (promptCount < 0) {
                stringRes(R.string.dictate__manage_prompts_summary_loading)
            } else {
                stringRes(R.string.dictate__manage_prompts_summary, "count" to promptCount)
            },
            onClick = { navController.navigate(Routes.Settings.DictatePrompts()) },
        )

        SwitchPreference(
            prefs.dictate.autoFormattingEnabled,
            icon = Icons.Default.AutoFixHigh,
            modifier = Modifier.settingsSearchAnchor("dictate__auto_formatting_title"),
            title = stringRes(R.string.dictate__auto_formatting_title),
            summary = stringRes(R.string.dictate__auto_formatting_summary),
        )

        val reasoningScope = rememberCoroutineScope()
        val reasoningEffort by prefs.dictate.rewordingReasoningEffort.collectAsState()
        val reasoningCustom by prefs.dictate.rewordingReasoningEffortCustom.collectAsState()
        var reasoningDialogOpen by remember { mutableStateOf(false) }
        Preference(
            icon = Icons.Default.Bolt,
            modifier = Modifier.settingsSearchAnchor("dictate__reasoning_effort_title"),
            title = stringRes(R.string.dictate__reasoning_effort_title),
            summary = when (reasoningEffort) {
                DictateReasoningEffort.CUSTOM ->
                    reasoningCustom.ifBlank { stringRes(R.string.dictate__reasoning_effort_custom) }
                DictateReasoningEffort.OFF -> stringRes(R.string.dictate__reasoning_effort_off)
                DictateReasoningEffort.MINIMAL -> stringRes(R.string.dictate__reasoning_effort_minimal)
                DictateReasoningEffort.LOW -> stringRes(R.string.dictate__reasoning_effort_low)
                DictateReasoningEffort.MEDIUM -> stringRes(R.string.dictate__reasoning_effort_medium)
                DictateReasoningEffort.HIGH -> stringRes(R.string.dictate__reasoning_effort_high)
            },
            onClick = { reasoningDialogOpen = true },
            enabledIf = { prefs.dictate.rewordingEnabled isEqualTo true },
        )
        if (reasoningDialogOpen) {
            ReasoningEffortDialog(
                initialEffort = reasoningEffort,
                initialCustom = reasoningCustom,
                includeUseGlobal = false,
                onConfirm = { effort, custom ->
                    reasoningScope.launch {
                        prefs.dictate.rewordingReasoningEffort.set(effort ?: DictateReasoningEffort.OFF)
                        prefs.dictate.rewordingReasoningEffortCustom.set(custom)
                    }
                    reasoningDialogOpen = false
                },
                onDismiss = { reasoningDialogOpen = false },
            )
        }

        val systemSelection by prefs.dictate.systemPromptSelection.collectAsState()
        PromptSelectionPreference(
            pref = prefs.dictate.systemPromptSelection,
            icon = Icons.Default.Psychology,
            title = stringRes(R.string.dictate__system_prompt_title),
            entries = promptSelectionEntries(),
            infoTitle = stringRes(R.string.dictate__system_prompt_info_title),
            infoDescription = stringRes(R.string.dictate__system_prompt_info_description),
            infoPromptText = DictatePromptDefaults.REWORDING_BE_PRECISE,
        )
        if (systemSelection == DictatePromptDefaults.SELECTION_CUSTOM) {
            TextInputPreference(
                pref = prefs.dictate.systemPromptCustom,
                icon = Icons.Default.Edit,
                title = stringRes(R.string.dictate__system_prompt_custom_title),
                placeholder = stringRes(R.string.dictate__system_prompt_custom_placeholder),
                multiline = true,
            )
        }
    }
}
