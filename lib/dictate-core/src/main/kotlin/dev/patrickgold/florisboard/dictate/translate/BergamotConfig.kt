/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.translate

import dev.patrickgold.florisboard.dictate.translate.TranslationModelFile.Role

/**
 * The Marian/Bergamot configuration that loads one installed [TranslationDirection] (issue #424).
 *
 * The decoder settings are the ones Firefox uses for exactly these models
 * (`translations-engine.worker.mjs` in mozilla/translations): greedy search, int8 GEMM, the lexical
 * shortlist — tuned for speed on a CPU, which is also what a phone wants.
 */
object BergamotConfig {
    /** [path] maps a file of the direction to where it is installed. */
    fun yaml(direction: TranslationDirection, path: (TranslationModelFile) -> String): String {
        fun required(role: Role) = checkNotNull(direction.file(role)) { "${direction.id} has no $role" }
        val vocabs = direction.file(Role.VOCAB)?.let { listOf(it, it) }
            ?: listOf(required(Role.SOURCE_VOCAB), required(Role.TARGET_VOCAB))
        return buildString {
            appendLine("models:")
            appendLine("  - ${quoted(path(required(Role.MODEL)))}")
            appendLine("vocabs:")
            vocabs.forEach { appendLine("  - ${quoted(path(it))}") }
            appendLine("shortlist:")
            appendLine("  - ${quoted(path(required(Role.SHORTLIST)))}")
            appendLine("  - false")
            for ((key, value) in DECODER) appendLine("$key: $value")
        }
    }

    private val DECODER = listOf(
        "beam-size" to "1",
        "normalize" to "1.0",
        "word-penalty" to "0",
        "max-length-break" to "128",
        "mini-batch-words" to "1024",
        "workspace" to "128",
        "max-length-factor" to "2.0",
        "skip-cost" to "true",
        "cpu-threads" to "0",
        "quiet" to "true",
        "quiet-translation" to "true",
        "gemm-precision" to "int8shiftAlphaAll",
        "alignment" to "soft",
    )

    private fun quoted(path: String) = "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
