/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.devemperor.dictate.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import dev.patrickgold.florisboard.dictate.sync.DictateSyncedSettings
import net.devemperor.dictate.wear.sync.WearSettingsStore

/**
 * Wear MaterialTheme tinted with the phone's accent color (synced via [DictateSyncedSettings]) so the
 * watch keyboard and settings look like the phone app. The surface stays dark — the right choice for an
 * OLED watch face — while the accent drives the primary/active surfaces (record button, selected tab).
 */
@Composable
fun WearDictateTheme(
    accent: Color = LocalSyncedAccent(),
    content: @Composable () -> Unit,
) {
    // Pick a legible "on accent" so text/icons on accent-filled surfaces stay readable for any hue.
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    val colors = remember(accent, onAccent) {
        Colors(
            primary = accent,
            primaryVariant = accent.copy(alpha = 0.85f),
            secondary = accent,
            secondaryVariant = accent.copy(alpha = 0.7f),
            background = Color.Black,
            surface = Color(0xFF1C1F24),
            onPrimary = onAccent,
            onSecondary = onAccent,
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFBFC6CF),
        )
    }
    MaterialTheme(colors = colors, content = content)
}

/** The current accent color from the synced phone settings, recomposing when a fresh sync lands. */
@Composable
fun LocalSyncedAccent(): Color {
    val settings by WearSettingsStore.settings.collectAsState()
    return Color(settings.accentColorArgb)
}
