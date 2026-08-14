/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.ime.media.emoji.EmojiAnnotations
import dev.patrickgold.florisboard.ime.media.emoji.EmojiData
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSearchIndex
import dev.patrickgold.florisboard.lib.FlorisLocale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #274, checked through the packaged APK rather than the source tree.
 *
 * `EmojiSearchEngineTest` on the JVM already covers the ranking, but it reads the asset *files* off
 * disk. This one goes through `context.assets` — so it is the check that the annotation files are
 * actually packaged, that `AssetManager.list()` finds them, and that a subtype's locale resolves to the
 * right one. That last step is where Android's legacy language codes bite: a Hebrew subtype arrives as
 * `iw` and an Indonesian one as `in`, neither of which is a file name.
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.patrickgold.florisboard.EmojiSearchLanguagesTest
 */
@RunWith(AndroidJUnit4::class)
class EmojiSearchLanguagesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The search as the panel builds it, for a subtype whose primary locale is [language]. */
    private fun indexFor(language: String): EmojiSearchIndex = runBlocking {
        val locale = FlorisLocale.from(language)
        EmojiSearchIndex.build(
            data = EmojiData.get(context, EmojiData.RootPath),
            annotations = EmojiAnnotations.get(context, EmojiAnnotations.assetLanguage(locale)),
            fallbackAnnotations = EmojiAnnotations.get(context, EmojiAnnotations.FallbackLanguage),
            // Every emoji, rather than only those the emulator's font can draw: this test is about the
            // data reaching the device, not about what it can render.
            isSupported = { true },
        )
    }

    private fun EmojiSearchIndex.top(query: String, count: Int = 10) =
        search(query).take(count).map { it.emojis.first().value }

    @Test
    fun theReportedCaseWorksOnTheDevice() {
        // Issue #274 verbatim: a Hungarian layout, the word the reporter typed, the emojis GBoard found.
        val results = indexFor("hu").top("csók", count = 12)
        assertTrue("Hungarian \"csók\" found $results", "😘" in results && "💋" in results)
    }

    @Test
    fun everyLayoutFindsEmojisInItsOwnLanguage() {
        // Rather than hard-code vocabulary per language, each language is asked for emojis by the very
        // names its own file gives them — so the assertion is derived from the shipped data and stays
        // true when CLDR rewords something. One language per script family.
        for (language in listOf("hu", "de", "fr", "ru", "ar", "ja", "tr", "th", "zh", "he", "id")) {
            val annotations = runBlocking {
                EmojiAnnotations.get(context, EmojiAnnotations.assetLanguage(FlorisLocale.from(language)))
            }
            assertTrue("$language ships no annotations", annotations.isNotEmpty())
            val index = indexFor(language)
            val probes = annotations.entries.filter { it.value.name.isNotBlank() }.take(40).takeLast(8)
            for ((emoji, annotation) in probes) {
                val results = index.top(annotation.name, count = 20)
                assertTrue(
                    "$language: \"${annotation.name}\" did not find $emoji, got $results",
                    emoji in results,
                )
            }
        }
    }

    @Test
    fun englishWorksOnEveryLayout() {
        for (language in listOf("hu", "ja", "ar", "th", "ckb")) {
            val results = indexFor(language).top("unicorn", count = 5)
            assertTrue("$language: English fallback missing, got $results", "🦄" in results)
        }
    }

    @Test
    fun androidsLegacyLanguageCodesResolveToTheirFiles() {
        // What `Locale("he").language` actually returns on Android, and what the assets are called.
        assertEquals("he", EmojiAnnotations.assetLanguage(FlorisLocale.from("he")))
        assertEquals("id", EmojiAnnotations.assetLanguage(FlorisLocale.from("id")))
        runBlocking {
            for (language in listOf("he", "id")) {
                val annotations = EmojiAnnotations.get(context, language)
                assertTrue("$language has no annotations on the device", annotations.isNotEmpty())
            }
        }
    }

    @Test
    fun aLanguageWithoutAFileFallsBackInsteadOfFailing() {
        runBlocking {
            // Esperanto has no CLDR annotations, so nothing is shipped for it; asking must be empty
            // rather than throw, and the search must still work through English.
            assertEquals(emptyMap<String, Any>(), EmojiAnnotations.get(context, "eo"))
        }
        assertTrue("🦄" in indexFor("eo").top("unicorn", count = 5))
    }
}
