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

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor
import dev.patrickgold.florisboard.dictate.provider.LocalModelManager
import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderListing
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.Preference
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes

/**
 * Every provider the user has not set up yet, behind "Add a provider" on the providers screen.
 *
 * The providers screen lists only the user's own (see [ProviderListing.isListed]); this is where the other
 * twenty-odd presets went, together with a server of one's own. A search field and a row of filter chips
 * take the place of scrolling, because what people arrive looking for is either a name they already know
 * or a property — "something that transcribes in the EU" — and both are one tap or a few letters here.
 *
 * Choosing a row opens the same editor the providers screen uses. Once the provider is set up, the screen
 * returns to the providers list, where it now stands; closing the editor without a key stays here, so a
 * look at one provider does not cost the search.
 */
@Composable
fun DictateAddProviderScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__providers_add)
    previewFieldVisible = false
    iconSpaceReserved = true

    val prefs by FlorisPreferenceStore

    content {
        val navController = LocalNavController.current
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val accounts by prefs.dictate.providerAccounts.collectAsState()
        val transcriptionId by prefs.dictate.transcriptionProviderId.collectAsState()
        val rewordingId by prefs.dictate.rewordingProviderId.collectAsState()
        val isInstalled: (String) -> Boolean = { LocalModelManager.isInstalled(context, it) }

        var query by rememberSaveable { mutableStateOf("") }
        var filters by remember { mutableStateOf(emptySet<ProviderListing.Filter>()) }
        var editingId by remember { mutableStateOf<String?>(null) }

        val activeIds = setOf(transcriptionId, rewordingId)
        val shown = ProviderRegistry.presets
            .filterNot { ProviderListing.isListed(it, accounts, activeIds, isInstalled) }
            .filter { ProviderListing.matches(it, query, filters) }

        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text(stringRes(R.string.dictate__providers_search)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderListing.Filter.entries.forEach { filter ->
                val selected = filter in filters
                FilterChip(
                    selected = selected,
                    onClick = { filters = if (selected) filters - filter else filters + filter },
                    label = {
                        Text(
                            stringRes(
                                when (filter) {
                                    ProviderListing.Filter.TRANSCRIPTION -> R.string.dictate__providers_cap_stt
                                    ProviderListing.Filter.REWORDING -> R.string.dictate__providers_cap_chat
                                    ProviderListing.Filter.REALTIME -> R.string.dictate__realtime_title
                                    ProviderListing.Filter.EU -> R.string.dictate__providers_filter_eu
                                },
                            ),
                        )
                    },
                )
            }
        }

        shown.forEach { preset ->
            Preference(
                icon = providerIcon(preset.id),
                title = preset.displayName,
                summary = providerCapabilities(preset),
                onClick = { editingId = preset.id },
            )
        }
        if (shown.isEmpty() && (query.isNotBlank() || filters.isNotEmpty())) {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                text = stringRes(R.string.dictate__providers_add_none),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Outside the search and the filters on purpose: a server of one's own can be anything, so no
        // name or property could rule it out.
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Preference(
            icon = Icons.Default.Dns,
            modifier = Modifier.settingsSearchAnchor("dictate__providers_add_custom"),
            title = stringRes(R.string.dictate__providers_add_custom),
            summary = stringRes(R.string.dictate__providers_add_custom_summary),
            onClick = { editingId = ProviderAccount.newCustomId() },
        )

        editingId?.let { id ->
            val preset = ProviderRegistry.byId(id)
            ProviderEditorDialog(
                preset = preset,
                account = accounts.getOrEmpty(id),
                onDismiss = { editingId = null },
                onSave = { updated, _ ->
                    val next = accounts.put(updated)
                    scope.launch { prefs.dictate.providerAccounts.set(next) }
                    editingId = null
                    // A server of one's own is listed as soon as it exists; a preset once it can be used.
                    if (preset == null || ProviderListing.isSetUp(preset, next, isInstalled)) {
                        navController.popBackStack()
                    }
                },
                // Nothing to delete: a server being added here does not exist until it is saved.
                onDelete = null,
            )
        }
    }
}
