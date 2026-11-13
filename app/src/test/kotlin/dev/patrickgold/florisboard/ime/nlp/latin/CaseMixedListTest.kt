/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `<lang>_case_mixed.txt` lists (#476, #480): the words a dictation opening mid-sentence keeps the provider's
 * capital on, because they are a name or a common word about equally often, or because the word list writes
 * a name small.
 */
class CaseMixedListTest {

    private val dir = listOf(File("app/src/main/assets/ime/dict"), File("src/main/assets/ime/dict")).first { it.isDirectory }

    private fun list(lang: String): Set<String> =
        File(dir, "${lang}_case_mixed.txt").readLines().filter { it.isNotBlank() }.toSet()

    @Test
    fun `every entry is one lowercase word, so the keyboard's lookup finds it`() {
        val files = dir.listFiles { f -> f.name.endsWith("_case_mixed.txt") }!!.sortedBy { it.name }
        assertEquals(17, files.size, files.joinToString { it.name })
        for (file in files) {
            val bad = file.readLines().filter { it.isNotBlank() && (it != it.lowercase() || it.any(Char::isWhitespace)) }
            assertTrue(bad.isEmpty(), "${file.name}: $bad")
        }
    }

    @Test
    fun `the names and places each list wrote small keep the speech service's capital`() {
        // The probes of #476 (Croatian, reported) and #480 (one sample per language): the country, cities,
        // first names and a holiday the language capitalises, each written small by its word list.
        val probes = mapOf(
            "hr" to "rijeka rijeci luka lana hrvatska hrvatskoj njemačka sabor mario",
            "pt" to "joão maria pedro miguel lisboa brasil porto",
            "es" to "maría juan lucía navidad valencia",
            "da" to "danmark søren jens mads europa",
            "ro" to "românia maria elena crăciun ion",
            "id" to "indonesia bandung agus budi dewi",
            "lt" to "jonas rūta tomas kalėdos",
            "nn" to "oslo ola europa",
            "it" to "marco paolo natale",
            "eo" to "parizo londono berlino johano ludoviko esperanto",
            "fr" to "paris pierre marie jean noël",
            "pl" to "polska warszawa",
            "sl" to "marko luka",
            "nl" to "jan kerstmis",
            "ru" to "иван рождество",
            // German nouns its list writes small because they are verbs or adverbs as well.
            "de" to "essen leben zeit liebe angst frage ende schule weihnachten",
        )
        for ((lang, words) in probes) {
            val missing = words.split(" ").filter { it !in list(lang) }
            assertTrue(missing.isEmpty(), "$lang missing: $missing")
        }
    }
}
