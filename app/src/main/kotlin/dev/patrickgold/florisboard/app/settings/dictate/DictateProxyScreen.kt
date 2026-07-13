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
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.provider.DictateProxyType
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.datastore.ui.listPrefEntries
import org.florisboard.lib.compose.stringRes

/**
 * Network proxy settings. The configured proxy is applied globally to *every* outbound provider call –
 * transcription, rewording, model listing and the connection test – so all traffic to the configured AI
 * endpoints can be routed through it (roadmap section 5.6). Disabled by default; the host/port/auth
 * fields only take effect while the proxy toggle is on (see `dictateProxyConfig`).
 */
@Composable
fun DictateProxyScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__proxy_title)
    previewFieldVisible = true
    iconSpaceReserved = true

    val prefs by FlorisPreferenceStore

    content {
        val enabled by prefs.dictate.proxyEnabled.collectAsState()

        SwitchPreference(
            prefs.dictate.proxyEnabled,
            icon = Icons.Default.Lan,
            modifier = Modifier.settingsSearchAnchor("dictate__proxy_enabled_title"),
            title = stringRes(R.string.dictate__proxy_enabled_title),
            summary = stringRes(R.string.dictate__proxy_enabled_summary),
        )

        if (enabled) {
            PreferenceGroup(title = stringRes(R.string.dictate__proxy_server_group)) {
                ListPreference(
                    prefs.dictate.proxyType,
                    icon = Icons.Default.Router,
                    modifier = Modifier.settingsSearchAnchor("dictate__proxy_type_title"),
                    title = stringRes(R.string.dictate__proxy_type_title),
                    entries = listPrefEntries {
                        entry(
                            key = DictateProxyType.HTTP,
                            label = stringRes(R.string.dictate__proxy_type_http),
                        )
                        entry(
                            key = DictateProxyType.SOCKS5,
                            label = stringRes(R.string.dictate__proxy_type_socks5),
                        )
                    },
                )
                TextInputPreference(
                    pref = prefs.dictate.proxyHost,
                    icon = Icons.Default.Dns,
                    title = stringRes(R.string.dictate__proxy_host_title),
                    placeholder = stringRes(R.string.dictate__proxy_host_placeholder),
                )
                TextInputPreference(
                    pref = prefs.dictate.proxyPort,
                    icon = Icons.Default.Tag,
                    title = stringRes(R.string.dictate__proxy_port_title),
                    placeholder = stringRes(R.string.dictate__proxy_port_placeholder),
                )
            }

            PreferenceGroup(title = stringRes(R.string.dictate__proxy_auth_group)) {
                TextInputPreference(
                    pref = prefs.dictate.proxyUsername,
                    icon = Icons.Default.Person,
                    title = stringRes(R.string.dictate__proxy_username_title),
                )
                TextInputPreference(
                    pref = prefs.dictate.proxyPassword,
                    icon = Icons.Default.Lock,
                    title = stringRes(R.string.dictate__proxy_password_title),
                    isSecret = true,
                )
            }
        }

        PreferenceGroup(title = stringRes(R.string.dictate__security_group)) {
            SwitchPreference(
                prefs.dictate.trustUserCertificates,
                icon = Icons.Default.VerifiedUser,
                modifier = Modifier.settingsSearchAnchor("dictate__trust_user_certs_title"),
                title = stringRes(R.string.dictate__trust_user_certs_title),
                summary = stringRes(R.string.dictate__trust_user_certs_summary),
            )
        }
    }
}
