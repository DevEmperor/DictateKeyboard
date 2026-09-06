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
import kotlin.test.assertTrue

/**
 * Where a run of characters ends — the rule that lets an address be one word (issue #318, round 3).
 *
 * Two halves, and they are tested against each other on purpose. [WordRun.continuesRun] is what the
 * input path asks going forwards; [WordRun.runStart] is what the composing region asks going backwards.
 * A keyboard whose halves disagree about where a word ends hands one half's work to the other — that is
 * how `top10` became `top0` (issue #311) — so the last test here types a string one character at a time
 * and checks that the backward walk arrives at the same answer.
 *
 * The other thing under test is everything that must *not* change. Prose is the overwhelming majority of
 * what anyone types, and a rule that glues sentences together to catch an address is a bad trade.
 */
class WordRunTest {

    // ── Going forwards: may this character be written without ending the word? ───────────────────

    @Test
    fun `the at sign opens an address and never closes a word`() {
        assertTrue(WordRun.continuesRun("jannis", '@'))
        // Nothing to attach it to yet.
        assertFalse(WordRun.continuesRun("", '@'))
        assertFalse(WordRun.continuesRun("-", '@'))
        // One per address. A second is somebody's typo or a different construction entirely.
        assertFalse(WordRun.continuesRun("jannis@example.com", '@'))
    }

    @Test
    fun `a dot stays inside a run that carries an at sign`() {
        assertTrue(WordRun.continuesRun("jannis@example", '.'))
        assertTrue(WordRun.continuesRun("jannis@example.co", '.'))
        assertTrue(WordRun.continuesRun("jannis@my", '-'))
    }

    @Test
    fun `a dot between sentences still ends the word`() {
        // The whole point of conditioning every rule on the run's shape.
        assertFalse(WordRun.continuesRun("Satz", '.'))
        assertFalse(WordRun.continuesRun("hello", '.'))
        assertFalse(WordRun.continuesRun("3", '.'))
        assertFalse(WordRun.continuesRun("z", '.'))
        // And a hyphen outside an address is still a word boundary, so German compounds are untouched.
        assertFalse(WordRun.continuesRun("well", '-'))
    }

    @Test
    fun `the web prefixes carry their own dots and slashes`() {
        assertTrue(WordRun.continuesRun("www", '.'))
        assertTrue(WordRun.continuesRun("www.example", '.'))
        assertTrue(WordRun.continuesRun("http", ':'))
        assertTrue(WordRun.continuesRun("http:", '/'))
        assertTrue(WordRun.continuesRun("http://x.com", '/'))
        // …and only they do.
        assertFalse(WordRun.continuesRun("example", '/'))
        assertFalse(WordRun.continuesRun("example", ':'))
    }

    @Test
    fun `underscore and plus are connectors, because they arrive before the at sign`() {
        // user123+tag@gmail.com types its `+` while the run is still just `user123`. Nothing at that
        // moment can know an address is coming, so these two cannot be conditioned on the shape.
        assertTrue(WordRun.continuesRun("user123", '+'))
        assertTrue(WordRun.continuesRun("user", '_'))
        assertFalse(WordRun.continuesRun("", '+'))
    }

    @Test
    fun `everything else still ends the word`() {
        for (char in listOf(',', ';', '!', '?', '"', '(', ')', '=', '*', '&', '।')) {
            assertFalse(WordRun.continuesRun("wort", char), "$char must end a word")
        }
    }

    // ── Going backwards: how far left does the run at the cursor reach? ──────────────────────────

    @Test
    fun `the composing region widens across a whole address`() {
        val text = "schreib an jannis@example.com"
        // What the break iterator would have found: the last token, `com`.
        assertEquals(text.indexOf("jannis"), WordRun.runStart(text, text.length - 3))
    }

    @Test
    fun `a run is found even where the break iterator sees no word at all`() {
        // Text ending on `@` or on a domain dot: ICU reports WORD_NONE, and without this the composing
        // region would be cleared in the middle of an address and the word thrown away.
        val afterAt = "jannis@"
        assertEquals(0, WordRun.runStart(afterAt, afterAt.length))
        val afterDot = "jannis@example."
        assertEquals(0, WordRun.runStart(afterDot, afterDot.length))
    }

    @Test
    fun `prose is left exactly where it was`() {
        val sentence = "Das war der Satz. Nächster"
        // `Satz.` is not a run, so the backward walk gives the break iterator's answer back unchanged.
        val nextStart = sentence.indexOf("Nächster")
        assertEquals(nextStart, WordRun.runStart(sentence, nextStart))
        // Cursor sitting right behind the full stop: still no run, so still no composing region.
        val ended = "Das war der Satz."
        assertEquals(ended.length, WordRun.runStart(ended, ended.length))
        // A domain without www or an at sign splits at the dot, exactly as before.
        val plain = "example.com"
        assertEquals(plain.length - 3, WordRun.runStart(plain, plain.length - 3))
        // Decimals and abbreviations too.
        assertEquals(2, WordRun.runStart("3.14", 2))
        assertEquals(2, WordRun.runStart("z.B", 2))
    }

    @Test
    fun `punctuation someone wrote on purpose keeps its own meaning`() {
        // A run starts on a letter or a digit. Otherwise the backward walk would swallow the markdown
        // underscore and the chat mention, and the engine would stop correcting the word behind them.
        val emphasis = "_wichtig"
        assertEquals(1, WordRun.runStart(emphasis, 1))
        val mention = "@jannis"
        assertEquals(1, WordRun.runStart(mention, 1))
        assertFalse(WordRun.isOneRun("_wichtig"))
        assertFalse(WordRun.isOneRun("@jannis"))
    }

    @Test
    fun `the walk stops at whitespace and at its own ceiling`() {
        val text = "mail an jannis@example.com"
        assertEquals(text.indexOf("jannis"), WordRun.runStart(text, text.length - 3))
        // Longer than anything that can be learned: left alone rather than scanned.
        val long = "a".repeat(WordRun.MAX_LENGTH + 10) + "@example.com"
        assertEquals(long.length - 3, WordRun.runStart(long, long.length - 3))
    }

    @Test
    fun `the two halves agree, character by character`() {
        // Typing the address forwards and asking the backward walk after every keystroke: the two must
        // describe the same word, or the keyboard corrects one half of an address while composing the
        // other (issue #311's failure mode, one issue later).
        for (address in listOf("jannis@example.com", "user123+tag@gmail.com", "www.example.com", "http://x.com/y")) {
            val typed = StringBuilder()
            for (char in address) {
                val continues = typed.isNotEmpty() && WordRun.continuesRun(typed.toString(), char)
                typed.append(char)
                if (typed.length > 1) {
                    assertTrue(continues, "$address broke apart at '$char' after \"$typed\"")
                }
                assertEquals(0, WordRun.runStart(typed, typed.length), "backward walk disagreed on \"$typed\"")
            }
        }
    }

    // ── The shape that may be learned verbatim ───────────────────────────────────────────────────

    @Test
    fun `an address needs a local part and a real domain`() {
        assertTrue(WordRun.isAddressLike("jannis@example.com"))
        assertTrue(WordRun.isAddressLike("user123+tag@gmail.com"))
        assertTrue(WordRun.isAddressLike("name+work@corp.co"))
        assertTrue(WordRun.isAddressLike("www.example.com"))
        assertTrue(WordRun.isAddressLike("http://x.com/y"))
    }

    @Test
    fun `a fragment is not an address`() {
        assertFalse(WordRun.isAddressLike("jannis@"))     // still being typed
        assertFalse(WordRun.isAddressLike("@example.com")) // no local part
        assertFalse(WordRun.isAddressLike("a@b"))          // no domain suffix
        assertFalse(WordRun.isAddressLike("a@b.c"))        // suffix too short to be one
        assertFalse(WordRun.isAddressLike("a@@b.com"))     // two at signs
        assertFalse(WordRun.isAddressLike("hallo"))
        assertFalse(WordRun.isAddressLike("www"))
    }

    @Test
    fun `the punctuation a sentence leaves behind comes off`() {
        assertEquals("jannis@example.com", WordRun.trimTrailingPunctuation("jannis@example.com."))
        assertEquals("jannis@example.com", WordRun.trimTrailingPunctuation("jannis@example.com!?"))
        // The dots that belong to the address are not at the end, so they stay.
        assertEquals("jannis@example.co.uk", WordRun.trimTrailingPunctuation("jannis@example.co.uk,"))
        assertEquals("hallo", WordRun.trimTrailingPunctuation("hallo"))
    }
}
