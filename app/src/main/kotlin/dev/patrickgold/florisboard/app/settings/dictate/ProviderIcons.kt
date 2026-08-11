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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import dev.patrickgold.florisboard.R

/**
 * The mark to show beside a provider, so its row can be found by logo rather than read.
 *
 * Monochrome outlines, drawn in whatever colour the row uses like any other icon here. Each is the
 * provider's own mark, used to identify the service it configures; the trademarks belong to their owners
 * and are credited on the attributions screen.
 *
 * Anything without a mark here — a custom endpoint, say — falls back to the generic cloud this list used
 * throughout before.
 */
@Composable
fun providerIcon(providerId: String): ImageVector {
    val drawable = when (providerId) {
        // Dictate Cloud gets its own mark rather than the generic cloud, so it reads as a service
        // in its own right wherever it appears — onboarding, provider list, Smartbar.
        "cloud" -> R.drawable.ic_dictate_cloud
        "openai" -> R.drawable.ic_provider_openai
        "groq" -> R.drawable.ic_provider_groq
        "openrouter" -> R.drawable.ic_provider_openrouter
        "gemini" -> R.drawable.ic_provider_gemini
        "anthropic" -> R.drawable.ic_provider_anthropic
        "together" -> R.drawable.ic_provider_together
        "deepinfra" -> R.drawable.ic_provider_deepinfra
        "mistral" -> R.drawable.ic_provider_mistral
        "elevenlabs" -> R.drawable.ic_provider_elevenlabs
        "deepgram" -> R.drawable.ic_provider_deepgram
        "assemblyai" -> R.drawable.ic_provider_assemblyai
        "soniox" -> R.drawable.ic_provider_soniox
        "xai" -> R.drawable.ic_provider_xai
        "deepseek" -> R.drawable.ic_provider_deepseek
        "ollama" -> R.drawable.ic_provider_ollama
        // Nothing runs anywhere but here, so the phone is the mark.
        "local" -> return Icons.Default.PhoneAndroid
        else -> return Icons.Default.Cloud
    }
    return ImageVector.vectorResource(drawable)
}
