/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp.han

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Chinese subtypes and the language packs that make them work (issue #262).
 *
 * A Chinese subtype is only a QWERTY layout until a language pack supplies the table its variant names —
 * `zh-CN-pinyin` reads a table called `pinyin`, `zh-CN-zhengma` one called `zhengma`. Nothing in the app
 * checks that a subtype's table actually exists, so getting the variant wrong produces a keyboard that
 * types nothing at all and says nothing about why. That is what these tests are for.
 */
class ChineseSubtypesTest {

    private val assetsDir: File by lazy {
        // Run from the repo root or from app/, depending on how Gradle was invoked.
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val suffix = "src/main/assets/ime"
        while (dir != null) {
            File(dir, "app/$suffix").takeIf { it.isDirectory }?.let { return@lazy it }
            File(dir, suffix).takeIf { it.isDirectory }?.let { return@lazy it }
            dir = dir.parentFile
        }
        error("could not locate app/src/main/assets/ime")
    }

    private fun json(path: String) =
        Json.parseToJsonElement(File(assetsDir, path).readText()).jsonObject

    /** languageTag -> preset, for every subtype preset whose suggestions come from the Han provider. */
    private val hanPresets: Map<String, kotlinx.serialization.json.JsonObject> by lazy {
        json("keyboard/org.florisboard.localization/extension.json")["subtypePresets"]!!.jsonArray
            .map { it.jsonObject }
            .filter {
                it["nlpProviders"]?.jsonObject?.get("suggestion")?.jsonPrimitive?.content ==
                    HanShapeBasedLanguageProvider.ProviderId
            }
            .associateBy { it["languageTag"]!!.jsonPrimitive.content }
    }

    /** The variant of a language tag: the table name the provider will query. */
    private fun variantOf(languageTag: String) = languageTag.split('-', '_').drop(2).joinToString("_")

    /** Item ids declared by the bundled shape-based pack. */
    private val bundledItems: Set<String> by lazy {
        json("languagepack/org.florisboard.hanshapebasedbasicpack/extension.json")["items"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
            .toSet()
    }

    @Test
    fun `pinyin is offered at all`() {
        // The whole complaint in #262: the only Chinese input method was Zhengma, a shape-based method
        // used by a small minority, while pinyin — what almost everyone types with — was missing.
        assertTrue("zh-CN-pinyin" in hanPresets, "there is no Chinese Pinyin subtype")
        assertEquals("pinyin", variantOf("zh-CN-pinyin"), "the variant is the table name; it must be 'pinyin'")
    }

    @Test
    fun `every bundled input method can actually be selected`() {
        // The pack ships three tables but only Zhengma ever had a subtype preset, so the two Taiwanese
        // methods were dead weight in the APK that no user could reach.
        for (item in bundledItems) {
            val tag = item.replace('_', '-')
            assertTrue(tag in hanPresets, "the pack ships '$item' but no subtype preset selects it")
        }
    }

    @Test
    fun `each han subtype names a table some pack provides`() {
        val downloadable = setOf("zh_CN_pinyin")  // PinyinPackManager fetches this one on demand
        for (tag in hanPresets.keys) {
            val item = tag.replace('-', '_')
            assertTrue(
                item in bundledItems || item in downloadable,
                "subtype '$tag' has no language pack, so it would type nothing",
            )
        }
    }

    @Test
    fun `the pinyin pack is the one the subtype asks for`() {
        // The manager keys off the locale tag; a mismatch here is a keyboard that silently never installs
        // its table.
        assertTrue(
            PinyinPackManager.handles(dev.patrickgold.florisboard.lib.FlorisLocale.fromTag("zh-CN-pinyin")),
            "PinyinPackManager does not recognise the subtype it exists for",
        )
        assertTrue(
            !PinyinPackManager.handles(dev.patrickgold.florisboard.lib.FlorisLocale.fromTag("zh-CN-zhengma")),
            "PinyinPackManager should not claim the bundled Zhengma subtype",
        )
    }

    @Test
    fun `the chinese subtypes agree on layout and popups`() {
        // They all type latin letters into a CJK candidate flow, so any of them getting a non-qwerty
        // layout or the latin popup mapping would be a copy-paste slip rather than a decision.
        for ((tag, preset) in hanPresets) {
            val preferred = preset["preferred"]!!.jsonObject
            assertEquals(
                "org.florisboard.layouts:qwerty", preferred["characters"]!!.jsonPrimitive.content,
                "$tag does not use the qwerty layout",
            )
            assertEquals(
                "org.florisboard.localization:cjk", preset["popupMapping"]!!.jsonPrimitive.content,
                "$tag does not use the cjk popup mapping",
            )
        }
    }
}
