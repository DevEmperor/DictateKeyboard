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
import kotlin.test.assertTrue

/**
 * The `<lang>_case_mixed.txt` lists (#476): the words a dictation opening mid-sentence keeps the provider's
 * capital on, because they are a name or a common word about equally often.
 */
class CaseMixedListTest {

    private fun list(lang: String): List<String> {
        val name = "${lang}_case_mixed.txt"
        val file = listOf(File("app/src/main/assets/ime/dict/$name"), File("src/main/assets/ime/dict/$name"))
            .first { it.isFile }
        return file.readLines().filter { it.isNotBlank() }
    }

    @Test
    fun `every entry is one lowercase word, so the keyboard's lookup finds it`() {
        for (lang in listOf("en", "hr")) {
            val bad = list(lang).filter { it != it.lowercase() || it.any(Char::isWhitespace) }
            assertTrue(bad.isEmpty(), "$lang: $bad")
        }
    }

    @Test
    fun `Croatian keeps the capital on the words that are a place or a name as well as a word`() {
        // Reported on #476 against hr.json: river or Rijeka, port or Luka, the adjectives or the countries,
        // the assembly; and mario, which is also "cared" (nije mario).
        val mixed = list("hr").toSet()
        val missing = listOf("rijeka", "rijeci", "luka", "lana", "hrvatska", "hrvatskoj", "njemačka", "sabor", "mario")
            .filter { it !in mixed }
        assertTrue(missing.isEmpty(), "missing: $missing")
    }
}
