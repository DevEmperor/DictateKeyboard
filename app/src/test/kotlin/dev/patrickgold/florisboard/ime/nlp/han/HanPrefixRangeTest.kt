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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The prefix range that replaced `code LIKE ? || '%'` in the Chinese suggestion lookup (issue #262).
 *
 * The change is only safe if the range selects *exactly* the rows LIKE would. That is the property
 * these tests check, over the real key-code alphabets the shipped language packs declare — the range
 * has to be equivalent for the input that actually reaches it, not for arbitrary Unicode.
 */
class HanPrefixRangeTest {

    /** The `hanShapeBasedKeyCode` sets from org.florisboard.hanshapebasedbasicpack's extension.json. */
    private val zhengma = "abcdefghijklmnopqrstuvwxyz"
    private val boshiamy = ",.'abcdefghijklmnopqrstuvwxyz[]"
    private val cangjieLarge = "abcdefghijklmnopqrstuvwxyz&"

    private fun upper(prefix: String) = HanShapeBasedLanguageProvider.prefixUpperBound(prefix)

    /** What the SQL `code >= ? AND code < ?` does, in Kotlin. */
    private fun inRange(code: String, prefix: String): Boolean {
        val bound = upper(prefix) ?: error("no bound for '$prefix'")
        return code >= prefix && code < bound
    }

    @Test
    fun `the range selects exactly what LIKE would`() {
        // Every prefix of length 1 and 2 over each alphabet, against a corpus built from the same
        // alphabet: membership in the range must agree with startsWith on every single pair.
        for (alphabet in listOf(zhengma, boshiamy, cangjieLarge)) {
            val codes = buildList {
                for (a in alphabet) {
                    add(a.toString())
                    for (b in alphabet) {
                        add("$a$b")
                        add("$a$b${alphabet.first()}")
                        add("$a$b${alphabet.last()}")
                    }
                }
            }
            val prefixes = buildList {
                for (a in alphabet) {
                    add(a.toString())
                    for (b in alphabet) add("$a$b")
                }
            }
            for (prefix in prefixes) {
                for (code in codes) {
                    assertEquals(
                        code.startsWith(prefix), inRange(code, prefix),
                        "prefix='$prefix' code='$code' bound='${upper(prefix)}'",
                    )
                }
            }
        }
    }

    @Test
    fun `the bound increments the last character and nothing else`() {
        assertEquals("b", upper("a"))
        assertEquals("nj", upper("ni"))
        assertEquals("nihap", upper("nihao"))
        // The boshiamy alphabet reaches outside a-z; the apostrophe and the brackets must work too.
        assertEquals("(", upper("'"))
        assertEquals("a\\", upper("a["))
        assertEquals("'", upper("&"))
    }

    @Test
    fun `a prefix that has no bound falls back to LIKE`() {
        // The caller checks for null and keeps the old query in that case, so these must not throw.
        assertNull(upper(""))
        assertNull(upper("a\uD83D"), "a lone high surrogate has no safe successor")
        assertNull(upper("￿"))
    }

    @Test
    fun `the bound sorts above every string it excludes`() {
        // The bound must be strictly greater than the prefix itself, or the range would be empty and
        // typing would silently stop producing candidates.
        for (prefix in listOf("a", "ni", "zhong", ",", "&", "[")) {
            val bound = upper(prefix)!!
            assertTrue(bound > prefix, "bound '$bound' does not sort above prefix '$prefix'")
        }
    }
}
