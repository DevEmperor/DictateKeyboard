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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.core.SubtypeLayoutMap
import dev.patrickgold.florisboard.ime.core.SubtypeNlpProviderMap
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.nlp.latin.GlideDictionaryCatalog
import dev.patrickgold.florisboard.ime.nlp.latin.GlideDictionaryManager
import dev.patrickgold.florisboard.ime.nlp.latin.LatinLanguageProvider
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #265, checked against the shipping engine rather than by reasoning about it.
 *
 * Calls [LatinLanguageProvider.suggest] and [LatinLanguageProvider.spell] the way the keyboard does, with
 * the dictionaries downloaded from the release exactly as a user's device would fetch them, and asserts
 * what lands in the suggestion strip. Needs the network on first run (~1.4 MB per language) and is not
 * part of any automated suite.
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.patrickgold.florisboard.DictionaryLanguagesTest
 */
@RunWith(AndroidJUnit4::class)
class DictionaryLanguagesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val provider = LatinLanguageProvider(context)

    private fun subtypeFor(language: String, layout: String): Subtype = Subtype(
        id = language.hashCode().toLong(),
        primaryLocale = FlorisLocale.from(language),
        secondaryLocales = emptyList(),
        nlpProviders = SubtypeNlpProviderMap(),
        composer = ExtensionComponentName("org.florisboard.composers", "appender"),
        currencySet = ExtensionComponentName("org.florisboard.currencysets", "dollar"),
        punctuationRule = ExtensionComponentName("org.florisboard.localization", "default"),
        popupMapping = ExtensionComponentName("org.florisboard.localization", language),
        layoutMap = SubtypeLayoutMap(
            characters = ExtensionComponentName("org.florisboard.layouts", layout),
        ),
    )

    /** An editor holding [before] with [word] being composed at the end of it. */
    private fun composing(word: String, before: String = ""): EditorContent {
        val text = before + word
        return EditorContent(
            text = text,
            offset = 0,
            localSelection = EditorRange(text.length, text.length),
            localComposing = EditorRange(before.length, text.length),
            localCurrentWord = EditorRange(before.length, text.length),
        )
    }

    /** Downloads [lang]'s dictionary if it is not already there, and waits for it to land. */
    private fun ensureDictionary(lang: String) {
        if (GlideDictionaryManager.isInstalled(context, lang)) return
        GlideDictionaryManager.ensureDownloaded(context, lang)
        val spec = GlideDictionaryCatalog.forLang(lang)
        requireNotNull(spec) { "no catalog entry for $lang" }
        val deadline = System.currentTimeMillis() + DOWNLOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (GlideDictionaryManager.isInstalled(context, lang)) {
                Log.i(TAG, "$lang dictionary installed (${spec.sizeBytes} bytes)")
                return
            }
            Thread.sleep(500)
        }
        throw AssertionError("$lang dictionary did not download within ${DOWNLOAD_TIMEOUT_MS}ms")
    }

    private fun suggestionsFor(subtype: Subtype, word: String, before: String = ""): List<String> =
        runBlocking {
            provider.preload(subtype)
            provider.suggest(
                subtype = subtype,
                content = composing(word, before),
                maxCandidateCount = 8,
                allowPossiblyOffensive = true,
                isPrivateSession = false,
            ).map { it.text.toString() }
        }

    private fun autoCommitted(subtype: Subtype, word: String): String? = runBlocking {
        provider.preload(subtype)
        provider.suggest(
            subtype = subtype,
            content = composing(word),
            maxCandidateCount = 8,
            allowPossiblyOffensive = true,
            isPrivateSession = false,
        ).firstOrNull { it.isEligibleForAutoCommit }?.text?.toString()
    }

    // --- Arabic: the review that prompted the issue -----------------------------------------------

    @Test
    fun arabicRestoresTheSpellingTheDictionaryHolds() {
        ensureDictionary("ar")
        val ar = subtypeFor("ar", "arabic")
        // Left of the arrow is what a writer types with only ا and ي on the base keyboard; right is what
        // the dictionary holds, and what the strip must lead with.
        val cases = mapOf(
            "ان" to "أن",
            "الى" to "إلى",
            "فى" to "في",
            "اخى" to "أخي",
            "انا" to "أنا",
            "الان" to "الآن",
        )
        val failures = mutableListOf<String>()
        for ((typed, expected) in cases) {
            val out = suggestionsFor(ar, typed)
            Log.i(TAG, "ar  $typed -> $out")
            if (!out.contains(expected)) failures += "$typed: expected $expected in $out"
            // The typed form stays reachable so the restoration can be refused (issue #150).
            if (!out.contains(typed)) failures += "$typed: typed form missing from $out"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun arabicOffersEverySpellingThatSharesAKey() {
        ensureDictionary("ar")
        val out = suggestionsFor(subtypeFor("ar", "arabic"), "ان")
        Log.i(TAG, "ar  ان -> $out")
        // أن، إن and آن are three different words that fold to the same key; all three belong in the strip.
        for (form in listOf("أن", "إن", "آن")) {
            assertTrue("$form missing from $out", out.contains(form))
        }
        assertTrue("أن should lead: $out", out.indexOf("أن") < out.indexOf("إن"))
    }

    @Test
    fun arabicLeavesCorrectSpellingsAlone() {
        ensureDictionary("ar")
        val ar = subtypeFor("ar", "arabic")
        for (word in listOf("على", "مدرسة", "قهوة", "أن")) {
            val committed = autoCommitted(ar, word)
            Log.i(TAG, "ar  $word -> auto-commit ${committed ?: "(none)"}")
            assertTrue("$word must not be replaced, got $committed", committed == null || committed == word)
        }
    }

    @Test
    fun arabicAutoCommitsTheCommonSpelling() {
        ensureDictionary("ar")
        assertEquals("أن", autoCommitted(subtypeFor("ar", "arabic"), "ان"))
        assertEquals("في", autoCommitted(subtypeFor("ar", "arabic"), "فى"))
    }

    @Test
    fun arabicCompletesFromABareSpelling() {
        ensureDictionary("ar")
        // انت is typed without the hamza; the completions must still be found (أنت, أنتم, …).
        val out = suggestionsFor(subtypeFor("ar", "arabic"), "انت")
        Log.i(TAG, "ar  انت -> $out")
        assertTrue("no completion of انت in $out", out.any { it.length > 3 && it.startsWith("أنت") })
    }

    @Test
    fun arabicIsNotSpelledAgainstEnglish() {
        ensureDictionary("ar")
        val ar = subtypeFor("ar", "arabic")
        for (word in listOf("مدرسة", "كتاب", "قهوة", "على")) {
            val result = runBlocking { provider.spell(ar, word, emptyList(), emptyList(), 4, true, false) }
            Log.i(TAG, "ar  spell($word) -> valid=${result.isValidWord}")
            assertTrue("$word flagged as a typo", result.isValidWord)
        }
    }

    // --- combining marks: the scripts the generator used to discard --------------------------------

    @Test
    fun hindiKeepsWholeWordsTogether() {
        ensureDictionary("hi")
        val hi = subtypeFor("hi", "hindi_in")
        // किताब carries a vowel sign (Mc); a word scan that stops at the first non-letter cuts it short.
        val out = suggestionsFor(hi, "किता")
        Log.i(TAG, "hi  किता -> $out")
        assertTrue("no completion of किता in $out", out.any { it.startsWith("किता") && it.length > 4 })
        val result = runBlocking { provider.spell(hi, "किताब", emptyList(), emptyList(), 4, true, false) }
        assertTrue("किताब flagged as a typo", result.isValidWord)
    }

    @Test
    fun tamilAndBengaliAreKnownToThemselves() {
        for ((lang, layout, word) in listOf(
            Triple("ta", "tamil", "ஒரு"),
            Triple("bn", "bengali_bd", "আমার"),
        )) {
            ensureDictionary(lang)
            val subtype = subtypeFor(lang, layout)
            val result = runBlocking { provider.spell(subtype, word, emptyList(), emptyList(), 4, true, false) }
            Log.i(TAG, "$lang spell($word) -> valid=${result.isValidWord}")
            assertTrue("$lang: $word flagged as a typo", result.isValidWord)
        }
    }

    // --- no dictionary means no dictionary ---------------------------------------------------------

    @Test
    fun aLanguageWithoutAWordListGetsNothingRatherThanEnglish() {
        // Azerbaijani has a subtype and no dictionary anywhere; it used to be answered in English.
        val az = subtypeFor("az", "qwerty")
        val out = suggestionsFor(az, "salam")
        Log.i(TAG, "az  salam -> $out")
        assertTrue("expected no suggestions, got $out", out.isEmpty())

        // "hello" is an English word — proof the English dictionary is not being consulted.
        val english = suggestionsFor(az, "hell")
        Log.i(TAG, "az  hell -> $english")
        assertTrue("English fallback is still answering: $english", english.isEmpty())

        val result = runBlocking { provider.spell(az, "salam", emptyList(), emptyList(), 4, true, false) }
        Log.i(TAG, "az  spell(salam) -> valid=${result.isValidWord} typo=${result.isTypo}")
        assertTrue("Azerbaijani must not be underlined as a typo", !result.isTypo)
    }

    @Test
    fun englishStillWorks() {
        // The regression guard: none of the above may have disturbed the bundled language.
        val en = subtypeFor("en", "qwerty")
        val out = suggestionsFor(en, "hell")
        Log.i(TAG, "en  hell -> $out")
        assertTrue("expected English completions, got $out", out.any { it.equals("hello", true) })

        // A one-key slip, not a famous misspelling: the bundled en.json is the upstream FlorisBoard word
        // list and it *contains* teh, recieve, seperate, definately, thier and wich, so the engine
        // deliberately leaves those alone — a word in the dictionary is never corrected. (That is the same
        // defect the Hunspell filter keeps out of the languages this issue added; worth its own issue.)
        val corrections = suggestionsFor(en, "morninh")
        Log.i(TAG, "en  morninh -> $corrections")
        assertTrue("expected morning offered for morninh, got $corrections", corrections.contains("morning"))
    }

    // --- French elisions (issue #212 follow-up) ----------------------------------------------------
    //
    // The rule itself is measured by FrenchElisionEvalTest; these four check that the engine is wired to
    // it — that the corpus really reaches [LatinLanguageProvider.suggest] on a device, which no unit test
    // can say. They need the bigram file as well as the word list, so they wait for both.

    /** Waits for [lang]'s context table too — the elision evidence lives in it. */
    private fun ensureContext(lang: String) {
        ensureDictionary(lang)
        val deadline = System.currentTimeMillis() + DOWNLOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (GlideDictionaryManager.bigramInstalled(context, lang)) return
            Thread.sleep(500)
        }
        throw AssertionError("$lang bigram table did not download within ${DOWNLOAD_TIMEOUT_MS}ms")
    }

    /**
     * Fails unless [subtype] really reaches the French dictionary.
     *
     * A subtype built the wrong way gets no dictionary at all and an empty strip, which reads as "nothing
     * was invented" and passes the test below without exercising a line of it. That is not hypothetical:
     * [FlorisLocale.from] takes a *language*, so `from("fr-CA")` yields the language `fr-ca` and no
     * dictionary — `fromTag` is the one that parses a tag.
     */
    private fun assertDictionaryLive(subtype: Subtype) {
        val out = suggestionsFor(subtype, "bonjou")
        assertTrue("no French dictionary behind this subtype: bonjou -> $out", out.contains("bonjour"))
    }

    private fun apostropheFormsIn(suggestions: List<String>): List<String> =
        suggestions.filter { it.any { ch -> ch == '\'' || ch == '’' } }

    @Test
    fun frenchRestoresElisionsTheWordListDoesNotHold() {
        ensureContext("fr")
        val fr = subtypeFor("fr", "azerty")
        // Right of the arrow is what the corpus says people write. None of these is in fr.json except
        // aujourd'hui, and "jaime" is the case that only works because the evidence is the corpus: the
        // word list holds the name Jaime, so asking it whether the typed form is a word says yes.
        val cases = mapOf(
            "jaime" to "j'aime",
            "jai" to "j'ai",
            "cest" to "c'est",
            "daccord" to "d'accord",
            "quon" to "qu'on",
            "sil" to "s'il",
            "aujourdhui" to "aujourd'hui",
            // h muet: the word list cannot tell it from h aspiré, the corpus can.
            "lhomme" to "l'homme",
        )
        for ((typed, expected) in cases) {
            val committed = autoCommitted(fr, typed)?.replace('’', '\'')
            Log.i(TAG, "fr  $typed -> auto-commit ${committed ?: "(none)"}")
            assertEquals("$typed must restore its apostrophe", expected, committed)
        }
    }

    @Test
    fun frenchNeverInventsAnElisionForAnOrdinaryWord() {
        ensureContext("fr")
        val fr = subtypeFor("fr", "azerty")
        assertDictionaryLive(fr)
        // Every one of these splits into a prefix and a word the dictionary knows — non→n'+on,
        // test→t'+est, savoir→s'+avoir, Meaux→m'+eaux — and none is a thing French writes. They must not
        // be committed, and must not reach the strip at all: a nonsense suggestion in the first cell is
        // the everyday cost this feature could have had.
        for (word in listOf("non", "mon", "son", "ton", "test", "savoir", "centre", "thé", "Meaux", "lhéros")) {
            val suggestions = suggestionsFor(fr, word)
            val apostrophes = apostropheFormsIn(suggestions)
            Log.i(TAG, "fr  $word -> $suggestions")
            assertTrue("$word must not be offered an apostrophe form, got $apostrophes", apostrophes.isEmpty())
        }
    }

    @Test
    fun frenchOffersAnAmbiguousElisionWithoutTakingIt() {
        ensureContext("fr")
        val fr = subtypeFor("fr", "azerty")
        // Real words whose elision is also real. Both readings belong in the strip; choosing between them
        // is the writer's, so the typed word leads and nothing is committed.
        for ((word, elision) in mapOf("quelle" to "qu'elle", "lune" to "l'une", "lame" to "l'âme")) {
            val suggestions = suggestionsFor(fr, word)
            Log.i(TAG, "fr  $word -> $suggestions  auto-commit ${autoCommitted(fr, word) ?: "(none)"}")
            assertEquals("$word must lead its own strip", word, suggestions.firstOrNull())
            assertTrue(
                "$elision must still be offered for $word, got $suggestions",
                suggestions.any { it.replace('’', '\'') == elision },
            )
            assertTrue("$word must not be replaced", autoCommitted(fr, word).let { it == null || it == word })
        }
    }

    @Test
    fun frenchCountsAnElisionAsCorrectlySpelled() {
        ensureContext("fr")
        val fr = subtypeFor("fr", "azerty")
        // The other half of the same evidence: what the restoration is willing to commit, the spell
        // checker has to accept. Only c'est is in the word list, so without the corpus the rest underline.
        for (word in listOf("c'est", "d'une", "qu'il", "l'homme", "aujourd'hui")) {
            val result = runBlocking { provider.spell(fr, word, emptyList(), emptyList(), 4, true, false) }
            Log.i(TAG, "fr  spell($word) -> valid=${result.isValidWord}")
            assertTrue("$word must not be flagged as a typo", result.isValidWord)
        }
    }

    private companion object {
        const val TAG = "DictLangTest"
        const val DOWNLOAD_TIMEOUT_MS = 120_000L
    }
}
