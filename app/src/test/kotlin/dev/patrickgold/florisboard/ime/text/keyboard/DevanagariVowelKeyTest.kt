/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.text.keyboard

import android.content.Context
import dev.patrickgold.florisboard.ime.core.DisplayLanguageNamesIn
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.Keyboard
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.keyboard.KeyboardState
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The face a Devanagari vowel key wears, and the text it sends while wearing it (issue #315).
 *
 * Two things have to stay apart here, because they came apart once already: what the key *shows* and
 * what the key *writes*. The composed faces show a whole syllable but write only the vowel sign — and
 * the inherent अ shows a syllable and writes nothing at all, because the consonant it displays is
 * already in the text.
 */
class DevanagariVowelKeyTest {

    private val a = DevanagariVowelKeyData(code = 0x0905, inherent = true, label = "अ")
    private val aa = DevanagariVowelKeyData(code = 0x0906, matra = 0x093E, label = "आ")
    private val i = DevanagariVowelKeyData(code = 0x0907, matra = 0x093F, label = "इ")
    private val vocalicR = DevanagariVowelKeyData(code = 0x090B, matra = 0x0943, label = "ऋ")

    private fun computed(key: DevanagariVowelKeyData, base: String): KeyData {
        return key.compute(BaseOnlyEvaluator(base))
    }

    @Test
    fun `with nothing pending every vowel key is its own independent vowel`() {
        for (key in listOf(a, aa, i, vocalicR)) {
            val computed = computed(key, DevanagariBase.NONE)
            assertIs<TextKeyData>(computed)
            assertEquals(key.label, computed.label)
            assertEquals(key.code, computed.code)
            assertEquals(key.label, computed.asString(isForDisplay = false))
        }
    }

    @Test
    fun `with a consonant pending the row previews the syllable but sends only the sign`() {
        val computed = computed(aa, "क")
        assertEquals("का", computed.label)
        assertEquals(0x093E, computed.code)
        assertEquals("ा", computed.asString(isForDisplay = false))
        // No dotted circle: the label already carries a real base for the sign to hang on.
        assertEquals("का", computed.asString(isForDisplay = true))
    }

    @Test
    fun `the vocalic R key joins the adaptive row`() {
        assertEquals("कृ", computed(vocalicR, "क").label)
        assertEquals("गृ", computed(vocalicR, "ग").label)
        assertEquals("मृ", computed(vocalicR, "म").label)
        assertEquals(0x0943, computed(vocalicR, "क").code)
    }

    @Test
    fun `the inherent vowel shows the pending consonant and writes nothing`() {
        // The consonant is already in the text. Writing it again would turn क into कक.
        val computed = computed(a, "क")
        assertIs<ComposedMatraKeyData>(computed)
        assertEquals("क", computed.label)
        // Not KeyCode.NOOP: that one draws a cross over the label, which on a device turned the key
        // into a struck-out क.
        assertEquals(KeyCode.PREVIEW_ONLY, computed.code)
        assertEquals("", computed.asString(isForDisplay = false))
    }

    @Test
    fun `the preview keeps a nukta instead of previewing the bare consonant`() {
        // क + nukta. The text will read क़ा, so the key may not promise का.
        assertEquals("क़ा", computed(aa, "क़").label)
        assertEquals("क़", computed(a, "क़").label)
        assertEquals("\u0958", computed(a, "\u0958").label) // precomposed क़, one code point
    }

    @Test
    fun `a vowel key without a matra and without the inherent flag never changes`() {
        // The old meaning of matra = 0 has to survive the arrival of `inherent`, or any future
        // sign-less vowel key silently turns into a no-op.
        val static = DevanagariVowelKeyData(code = 0x0905, label = "अ")
        val computed = computed(static, "क")
        assertIs<TextKeyData>(computed)
        assertEquals("अ", computed.label)
        assertEquals(0x0905, computed.code)
    }
}

/**
 * The smallest evaluator that answers the one question [DevanagariVowelKeyData.compute] asks. Everything
 * else throws, so a future dependency on more state shows up as a failing test rather than as a silent
 * default.
 */
private class BaseOnlyEvaluator(override val devanagariBase: String) : ComputingEvaluator {
    override val version: Int get() = -1
    override val keyboard: Keyboard get() = throw NotImplementedError()
    override val editorInfo: FlorisEditorInfo get() = throw NotImplementedError()
    override val state: KeyboardState get() = throw NotImplementedError()
    override val subtype: Subtype get() = throw NotImplementedError()

    override fun context(): Context? = null
    override fun displayLanguageNamesIn() = DisplayLanguageNamesIn.NATIVE_LOCALE
    override fun evaluateEnabled(data: KeyData): Boolean = true
    override fun evaluateVisible(data: KeyData): Boolean = true
    override fun isSlot(data: KeyData): Boolean = false
    override fun slotData(data: KeyData): KeyData? = null
}
