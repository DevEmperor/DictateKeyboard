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

/** The merge rule of [CorrectionReaders] on its own; `ReaderMergeEvalTest` measures what it is worth. */
class CorrectionReadersTest {

    @Test
    fun theStringReaderGetsTheLastSlotEvenWhenTheBeamFilledAll() {
        assertEquals(listOf("growl", "heidi", "hello"), CorrectionReaders.merge(listOf("growl", "heidi", "howl"), listOf("hello", "help"), 3))
    }

    @Test
    fun theBeamKeepsTheLead() {
        assertEquals("growl", CorrectionReaders.merge(listOf("growl"), listOf("hello", "help"), 3).first())
        assertEquals(listOf("growl"), CorrectionReaders.merge(listOf("growl", "heidi"), listOf("hello"), 1))
    }

    @Test
    fun emptySlotsAreFilledFromEitherReader() {
        assertEquals(listOf("growl", "hello", "help"), CorrectionReaders.merge(listOf("growl"), listOf("hello", "help"), 3))
        assertEquals(listOf("hello", "help"), CorrectionReaders.merge(emptyList(), listOf("hello", "help"), 3))
        assertEquals(listOf("growl", "heidi"), CorrectionReaders.merge(listOf("growl", "heidi"), emptyList(), 3))
    }

    @Test
    fun aWordBothReadersFoundIsOfferedOnceAndDoesNotCostTheBeamASlot() {
        assertEquals(listOf("a", "b", "c"), CorrectionReaders.merge(listOf("a", "b", "c"), listOf("a"), 3))
        assertEquals(listOf("a", "b", "x"), CorrectionReaders.merge(listOf("a", "b", "c"), listOf("b", "x"), 3))
    }
}
