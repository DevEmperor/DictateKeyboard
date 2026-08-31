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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Covers the dictionary key folding (issue #265).
 *
 * French and Arabic words must reach their entries from the spellings people can easily type, while every
 * other language that already ships a dictionary must fold exactly the way it did before — a change there
 * would silently re-key its word list.
 */
class DictFoldTest {

    // --- the languages that must not change -------------------------------------------------------

    @Test
    fun `other languages still fold to plain lowercase`() {
        for (lang in listOf("en", "de", "tr", "vi", "ru", "el", "he")) {
            for (word in listOf("Baum", "STRASSE", "don't", "well-known", "Ärmel", "İstanbul", "мир", "Ελλάδα")) {
                assertEquals(word.lowercase(), DictFold.foldKey(lang, word), "$lang / $word")
            }
        }
    }

    @Test
    fun `only french and arabic script languages fold non-trivially`() {
        for (lang in listOf("ar", "fa", "ur", "ckb")) {
            assertTrue(DictFold.hasNonTrivialFold(lang), lang)
        }
        assertTrue(DictFold.hasNonTrivialFold("fr"))
        for (lang in listOf("en", "de", "he", "fa-IR", "", "hi", "bn", "ta")) {
            assertFalse(DictFold.hasNonTrivialFold(lang), lang)
        }
    }

    // --- french: base-letter typing reaches accented spellings -----------------------------------

    @Test
    fun `french accents fold to their base letters`() {
        assertEquals("hote", DictFold.foldFrench("hôte"))
        assertEquals("garcon", DictFold.foldFrench("garçon"))
        assertEquals("eleve", DictFold.foldFrench("élève"))
        assertEquals("deja", DictFold.foldFrench("de\u0301ja"))
    }

    @Test
    fun `french unaccented prefixes match accented dictionary words`() {
        val prefix = DictFold.foldKey("fr", "ho")
        assertTrue(DictFold.foldKey("fr", "hôte").startsWith(prefix))
        assertTrue(DictFold.foldKey("fr", "hôtel").startsWith(prefix))
    }

    @Test
    fun `french ligatures fold to their keyboard spelling`() {
        assertEquals("oeuvre", DictFold.foldFrench("œuvre"))
        assertEquals("aether", DictFold.foldFrench("æther"))
    }

    /**
     * The property the spelling restoration stands on, and the reason it must stay true.
     *
     * `isKnownWord` answers on the fold key, so once French folds, `hote` reads as a known word and the
     * correction path — gated on `!isKnown` — never sees it. What puts `hôte` back is the restoration
     * block, and that block finds it by exactly this equality. Break it and the accent silently stops
     * being offered, with nothing failing anywhere else.
     */
    @Test
    fun `an unaccented french spelling reaches its accented entry`() {
        for ((typed, stored) in listOf(
            "hote" to "hôte",
            "eleve" to "élève",
            "garcon" to "garçon",
            "ca" to "ça",
            "oeuvre" to "œuvre",
        )) {
            assertEquals(DictFold.foldKey("fr", stored), DictFold.foldKey("fr", typed), "$typed / $stored")
        }
    }

    // --- arabic: marks that carry no meaning ------------------------------------------------------

    @Test
    fun `vowel points fold away`() {
        assertEquals("مدرسة", DictFold.foldArabic("مَدْرَسَة"))
        assertEquals("كتاب", DictFold.foldArabic("كِتَاب"))
    }

    @Test
    fun `tatweel folds away`() {
        assertEquals("من", DictFold.foldArabic("مــن"))
        assertEquals("لا", DictFold.foldArabic("لـا"))
    }

    @Test
    fun `zero width joiners fold away`() {
        // Persian writes compounds with a ZWNJ; the dictionary stores the joined form.
        assertEquals("ميروم", DictFold.foldArabic("می‌روم"))
    }

    // --- arabic: letter forms people type interchangeably -----------------------------------------

    @Test
    fun `all alef forms fold together`() {
        val folded = listOf("أن", "إن", "آن", "ان", "ٱن").map { DictFold.foldArabic(it) }
        assertEquals(1, folded.toSet().size, "expected one key, got $folded")
        assertEquals("ان", folded.first())
    }

    @Test
    fun `alef maqsura folds onto yeh`() {
        assertEquals(DictFold.foldArabic("على"), DictFold.foldArabic("علي"))
        assertEquals(DictFold.foldArabic("اخى"), DictFold.foldArabic("أخي"))
    }

    @Test
    fun `persian and urdu letter forms fold onto their arabic equivalents`() {
        assertEquals(DictFold.foldArabic("ی"), DictFold.foldArabic("ي"))
        assertEquals(DictFold.foldArabic("ک"), DictFold.foldArabic("ك"))
        assertEquals(DictFold.foldArabic("ہ"), DictFold.foldArabic("ه"))
    }

    @Test
    fun `eastern and persian digits fold onto ascii`() {
        assertEquals("2024", DictFold.foldArabic("٢٠٢٤"))
        assertEquals("2024", DictFold.foldArabic("۲۰۲۴"))
    }

    /**
     * The rule we deliberately do not have. ة and ه are a real distinction in Arabic (عليه "on him"
     * against علية "upper room"); folding them together buys 453 corrections and merges 1,439 pairs of
     * genuinely different words. If someone adds the rule later, this test is where they will notice.
     */
    @Test
    fun `ta marbuta stays distinct from heh`() {
        assertNotEquals(DictFold.foldArabic("عليه"), DictFold.foldArabic("علية"))
    }

    @Test
    fun `embedded latin is still lowercased`() {
        assertEquals("wifi", DictFold.foldArabic("WiFi"))
        assertEquals("الـwifi".let { DictFold.foldArabic(it) }, DictFold.foldArabic("الWiFi"))
    }

    @Test
    fun `folding is idempotent`() {
        for (word in listOf("مَدْرَسَة", "أن", "می‌روم", "٢٠٢٤", "WiFi", "عليه")) {
            val once = DictFold.foldArabic(word)
            assertEquals(once, DictFold.foldArabic(once), word)
        }
    }

    // --- word characters across scripts -----------------------------------------------------------

    @Test
    fun `combining vowel signs count as word characters`() {
        // Devanagari, Bengali and Tamil write vowels as combining marks; a scan that stops at the first
        // non-letter would cut every one of these words short.
        for (word in listOf("किताब", "नमस्ते", "क्या", "বাংলা", "আমি", "தமிழ்", "புத்தகம்", "مَدْرَسَة")) {
            assertTrue(word.all { DictFold.isWordChar(it) }, word)
        }
    }

    @Test
    fun `whole indic words survive a backwards scan`() {
        // Exactly what previousWordOf does to find the bigram context.
        assertEquals("किताब", "मेरी किताब".takeLastWhile { DictFold.isWordChar(it) })
        assertEquals("தமிழ்", "என் தமிழ்".takeLastWhile { DictFold.isWordChar(it) })
    }

    @Test
    fun `digits and punctuation are not word characters`() {
        for (ch in listOf('1', '٢', '.', ',', ' ', '!', '\'', '-')) {
            assertFalse(DictFold.isWordChar(ch), ch.toString())
        }
    }
}
