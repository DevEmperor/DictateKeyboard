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

import dev.patrickgold.florisboard.ime.text.key.KeyHintConfiguration
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.ime.text.keyboard.AutoTextKeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.lib.io.DefaultJsonConfig
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

    /**
     * Every language whose long-press defaults were set from its own word list, via
     * `tools/popup-main/measure.py`. Keys deliberately left without a default are absent: Latvian `o`
     * and `r` (ō and ŗ were dropped from the orthography), Vietnamese `a` (â against ă is a coin toss
     * on a thin corpus), English and Danish `i`, and everything in a language that simply does not
     * write the character on offer.
     */
    private val measuredDefaults = mapOf(
        "cs" to "a á, c č, d ď, e ě, i í, n ň, o ó, r ř, s š, t ť, u ů, y ý, z ž",
        "da" to "a æ, e é, o ø",
        "eo" to "c ĉ, g ĝ, h ĥ, j ĵ, q ŝ, s ŝ, u ŭ, w ĝ, x ĉ, y ŭ",
        "et" to "a ä, o õ, u ü",
        "fi" to "a ä, o ö",
        "hr" to "c č, d đ, s š, z ž",
        "is" to "a á, d ð, e é, i í, o ó, t þ, u ú, y ý",
        "lv" to "a ā, c č, e ē, g ģ, i ī, k ķ, l ļ, n ņ, s š, u ū, z ž",
        "nb" to "a å, o ø",
        "nn" to "a å, o ø",
        "pl" to "a ą, c ć, e ę, l ł, n ń, o ó, s ś, x ź, z ż",
        "ro" to "a ă, i î, s ș, t ț",
        "ru" to "е ё, ь ъ",
        "sk" to "a á, c č, d ď, e é, i í, l ľ, n ň, o ô, s š, t ť, u ú, y ý, z ž",
        "sl-SI" to "c č, s š, z ž",
        "sv" to "a ä, e é, o ö",
        "uk" to "і ї",
        "uk-cyr-ext" to "і ї",
        "vi-VN" to "d đ, e ê, o ô, u ư",
    ).mapValues { (_, spec) ->
        spec.split(", ").associate { it.split(" ").let { (key, char) -> key to char } }
    }

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
    fun `the key under the finger really is that character, through the app's own loader`() {
        // Not the JSON but the runtime answer: deserialized with the app's DefaultJsonConfig and asked
        // through PopupSet.getPopupKeys, exactly as TextKeyboardLayout does. `prioritized.first()` is
        // the key PopupUiController places at the press position, so this is the character a long press
        // inserts. Touch injection cannot be used to check this — neither Genymotion nor an instrumented
        // test delivers synthetic touches to the keyboard window.
        for (name in listOf("pt", "pt-BR")) {
            val json = File(mappingsDir, "$name.json").readText()
            val mapping: PopupMapping = DefaultJsonConfig.decodeFromString(json)
            val all = mapping[KeyVariation.ALL] ?: error("$name has no ALL variation")
            for ((key, expected) in portugueseDefaults) {
                val popupSet = all[key] ?: error("$name has no popup set for '$key'")
                val keys = popupSet.getPopupKeys(KeyHintConfiguration.HINTS_DISABLED)
                val underTheFinger = keys.prioritized.firstOrNull()
                assertEquals(
                    expected,
                    (underTheFinger as? TextKeyData)?.label ?: (underTheFinger as? AutoTextKeyData)?.label,
                    "$name: long-pressing '$key' and releasing",
                )
            }
        }
    }

    @Test
    fun `every measured language long-presses to the character its own words use most`() {
        for ((name, defaults) in measuredDefaults) {
            val mapping = mapping(name)
            for ((key, expected) in defaults) {
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
