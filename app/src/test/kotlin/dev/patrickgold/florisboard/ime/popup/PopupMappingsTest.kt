/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.popup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The long-press defaults of the shipped popup mappings (issue #279).
 *
 * A key's popup only puts a chosen character under the finger when the mapping declares it as `main`:
 * [PopupSet] hands it to `PopupKeys.prioritized`, and `PopupUiController.extend` places the first
 * prioritized key at the press position. Without a `main` the character under the finger is simply
 * `relevant[initUiIndex]` — the entry whose *list position* happens to line up with where the key sits
 * on the keyboard. That is how Portuguese ended up inserting `ê` for a long-pressed `E`.
 *
 * These checks read the asset files straight off disk, since the defect was in the data rather than in
 * any code path a test could otherwise reach.
 */
class PopupMappingsTest {

    private val mappingsDir: File by lazy {
        // Gradle runs unit tests from the module directory, but that has moved before; walk up until
        // the assets turn up rather than depend on it.
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val suffix = "src/main/assets/ime/keyboard/org.florisboard.localization/popupMappings"
        while (dir != null) {
            File(dir, "app/$suffix").takeIf { it.isDirectory }?.let { return@lazy it }
            File(dir, suffix).takeIf { it.isDirectory }?.let { return@lazy it }
            dir = dir.parentFile
        }
        error("popup mappings not found from ${System.getProperty("user.dir")}")
    }

    private fun mapping(name: String): JsonObject {
        val text = File(mappingsDir, "$name.json").readText()
        return Json.parseToJsonElement(text).jsonObject["all"]!!.jsonObject
    }

    private fun mainLabelOf(mapping: JsonObject, key: String): String? =
        mapping[key]?.jsonObject?.get("main")?.jsonObject?.get("label")?.jsonPrimitive?.content

    /** What a long press must insert in Portuguese — the acute accent, plus the cedilla on `c`. */
    private val portugueseDefaults = mapOf(
        "a" to "á", "c" to "ç", "e" to "é", "i" to "í", "o" to "ó", "u" to "ú",
    )

    @Test
    fun `portuguese long-press defaults are the accented characters the language actually uses`() {
        for (name in listOf("pt", "pt-BR")) {
            val mapping = mapping(name)
            for ((key, expected) in portugueseDefaults) {
                assertEquals(expected, mainLabelOf(mapping, key), "$name: long-pressing '$key'")
            }
        }
    }

    @Test
    fun `a main character is never repeated among the relevant ones`() {
        // The fix moves a character out of `relevant` into `main`. Copying it instead would leave it in
        // the popup twice, which is easy to miss by eye and impossible to miss here. Holds for every
        // shipped mapping, not just the Portuguese ones — this check found a pre-existing one in
        // rue.json, where the `і` key offered ѣ twice and labelled one of them `î`.
        for (file in mappingsDir.listFiles().orEmpty().filter { it.name.endsWith(".json") }) {
            val all = Json.parseToJsonElement(file.readText()).jsonObject["all"]?.jsonObject ?: continue
            for ((key, value) in all) {
                val entry = value as? JsonObject ?: continue
                val main = entry["main"]?.jsonObject ?: continue
                val mainCode = main["code"]?.jsonPrimitive?.intOrNullSafe() ?: continue
                val relevantCodes = entry["relevant"]?.jsonArray.orEmpty()
                    .mapNotNull { (it as? JsonObject)?.get("code")?.jsonPrimitive?.intOrNullSafe() }
                assertTrue(
                    mainCode !in relevantCodes,
                    "${file.name}: '$key' lists its main character (code $mainCode) in relevant too",
                )
            }
        }
    }

    @Test
    fun `every shipped mapping is readable`() {
        val files = mappingsDir.listFiles().orEmpty().filter { it.name.endsWith(".json") }
        assertTrue(files.size > 40, "only found ${files.size} popup mappings")
        for (file in files) {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            // A mapping may override only the URI-mode popups and carry no "all" at all — de-DE-neobone
            // does exactly that.
            assertTrue(
                "all" in root || "uri" in root,
                "${file.name} has neither an \"all\" nor a \"uri\" section",
            )
        }
    }
}

private fun JsonPrimitive.intOrNullSafe(): Int? = content.toIntOrNull()

private fun kotlinx.serialization.json.JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
    this ?: emptyList()
