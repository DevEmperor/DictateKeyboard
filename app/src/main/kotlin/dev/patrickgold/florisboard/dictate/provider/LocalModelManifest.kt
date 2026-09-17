/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.patrickgold.florisboard.dictate.provider

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Cross-check a downloaded, SHA-verified manifest with the files that the installer verified.
 * Fetching this real integrity manifest also uses Hugging Face's normal model-download counting;
 * there are no usage pings, and installed models remain usable offline.
 */
internal fun verifyDownloadManifest(spec: LocalModelSpec, directory: File) {
    val manifestName = spec.verificationManifest ?: return
    val manifestFile = spec.files.single { it.destName == manifestName }
    val sourceBase = manifestFile.url.substringBeforeLast('/') + "/"
    val entries = Json.parseToJsonElement(File(directory, manifestName).readText())
        .jsonObject.getValue("files").jsonArray.map { it.jsonObject }
    for (file in spec.files.filter { it.url.startsWith(sourceBase) && it.destName != manifestName }) {
        val sourceName = file.url.removePrefix(sourceBase)
        val entry = entries.single { it.getValue("path").jsonPrimitive.content == sourceName }
        check(entry.getValue("bytes").jsonPrimitive.long == file.sizeBytes &&
            entry.getValue("sha256").jsonPrimitive.content == file.sha256) {
            "manifest mismatch for ${file.destName}"
        }
    }
}
