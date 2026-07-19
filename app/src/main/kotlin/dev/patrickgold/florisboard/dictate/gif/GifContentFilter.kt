/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.gif

/**
 * Safe-search level for GIF results. [apiValue] is the value sent to the backend's `content_filter`
 * query parameter (KLIPY supports `off`/`low`/`medium`/`high`). Defaults lean safe.
 */
enum class GifContentFilter(val apiValue: String) {
    OFF("off"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");
}
