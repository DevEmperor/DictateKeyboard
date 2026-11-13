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
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A downloaded word list replaced by one of the same size (#476: Croatian re-cased letter for letter) must
 * still be told apart from the old one, by the checksum recorded beside it.
 */
class GlideDictionaryChecksumTest {

    private val dir = createTempDirectory("glide").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun sha(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private val old = """{"marko":163}"""
    private val new = """{"Marko":163}"""

    private fun list(text: String) = File(dir, "hr.json").apply { writeText(text) }

    @Test
    fun `a list from before checksums passes on its size, as it always did`() {
        val file = list(old)
        assertTrue(GlideDictionaryManager.matches(file, file.length(), sha(new)))
    }

    @Test
    fun `a recorded checksum tells a same-size list apart`() {
        val file = list(old)
        GlideDictionaryManager.checksumFile(file).writeText(sha(old))
        assertEquals(old.length, new.length)
        assertFalse(GlideDictionaryManager.matches(file, new.length.toLong(), sha(new)))
        assertTrue(GlideDictionaryManager.matches(file, old.length.toLong(), sha(old)))
    }

    @Test
    fun `the size still has to match`() {
        val file = list(old)
        GlideDictionaryManager.checksumFile(file).writeText(sha(old))
        assertFalse(GlideDictionaryManager.matches(file, old.length + 1L, sha(old)))
    }

    @Test
    fun `an old list is checked once - recorded when it is the catalog's, left to be fetched when not`() {
        val file = list(old)
        assertFalse(GlideDictionaryManager.recordIfMatching(file, sha(new)))
        assertFalse(GlideDictionaryManager.checksumFile(file).exists())

        assertTrue(GlideDictionaryManager.recordIfMatching(file, sha(old).uppercase()))
        assertEquals(sha(old), GlideDictionaryManager.checksumFile(file).readText())
        assertTrue(GlideDictionaryManager.matches(file, file.length(), sha(old)))
    }
}
