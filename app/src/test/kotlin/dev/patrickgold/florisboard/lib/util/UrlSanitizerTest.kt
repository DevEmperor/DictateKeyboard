/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.lib.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the paste-time link cleaner takes and, more importantly, what it refuses to take (issue #329).
 *
 * Most of these are "unchanged" assertions on purpose. A parameter left in is a small privacy loss the
 * user probably never notices; a parameter wrongly removed is a broken link they only learn about from
 * whoever they sent it to. The tests are weighted the way the risk is.
 */
class UrlSanitizerTest {

    private fun clean(text: String) = UrlSanitizer.clean(text)

    private fun assertUnchanged(text: String) = assertEquals(text, clean(text))

    // ── The cases from the report ────────────────────────────────────────────────────────────────

    @Test
    fun `strips the share id from a youtu_be link`() {
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ",
            clean("https://youtu.be/dQw4w9WgXcQ?si=AbCdEf12"),
        )
    }

    @Test
    fun `keeps the youtube timestamp while dropping the share id`() {
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ?t=42",
            clean("https://youtu.be/dQw4w9WgXcQ?si=AbCdEf12&t=42"),
        )
    }

    @Test
    fun `strips both x session parameters`() {
        assertEquals(
            "https://x.com/someone/status/1234567890",
            clean("https://x.com/someone/status/1234567890?s=20&t=aBcDeF"),
        )
    }

    @Test
    fun `strips instagram share ids`() {
        assertEquals(
            "https://www.instagram.com/p/Cabcdef/",
            clean("https://www.instagram.com/p/Cabcdef/?igsh=MTIzNDU2"),
        )
    }

    @Test
    fun `strips every utm parameter and keeps the rest`() {
        assertEquals(
            "https://example.com/artikel?id=7",
            clean("https://example.com/artikel?utm_source=newsletter&id=7&utm_medium=email"),
        )
    }

    @Test
    fun `drops the question mark when nothing is left`() {
        assertEquals(
            "https://example.com/artikel",
            clean("https://example.com/artikel?utm_source=newsletter&fbclid=abc"),
        )
    }

    // ── What must survive ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a youtube timestamp alone is never touched`() {
        assertUnchanged("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=90")
    }

    @Test
    fun `a facebook cdn image keeps the parameters that make it load`() {
        assertUnchanged("https://scontent.xx.fbcdn.net/v/t39.jpg?_nc_cat=1&_nc_ohc=abc&oh=00_AT&oe=12")
    }

    @Test
    fun `a link without a query is never touched`() {
        assertUnchanged("https://example.com/pfad/zur/seite")
    }

    @Test
    fun `the path is never touched even when it names a tracking parameter`() {
        assertUnchanged("https://example.com/utm_source/si?id=1")
    }

    @Test
    fun `the fragment survives the cleaning`() {
        assertEquals(
            "https://example.com/doc#abschnitt-3",
            clean("https://example.com/doc?utm_source=x#abschnitt-3"),
        )
    }

    @Test
    fun `the fragment survives alongside a kept parameter`() {
        assertEquals(
            "https://example.com/doc?id=1#abschnitt-3",
            clean("https://example.com/doc?utm_source=x&id=1#abschnitt-3"),
        )
    }

    @Test
    fun `plain prose is never touched`() {
        assertUnchanged("Ist das wirklich so? Ja, laut Abschnitt 3.2 schon.")
    }

    @Test
    fun `a bare hostname in prose is never touched`() {
        assertUnchanged("Schau bei example.com nach, ob das stimmt?")
    }

    @Test
    fun `text over the length cap is handed back unchanged`() {
        val long = "https://example.com/x?utm_source=a " + "wort ".repeat(1_000)
        assertUnchanged(long)
    }

    // ── Prose around links ───────────────────────────────────────────────────────────────────────

    @Test
    fun `only the links change and the words between them stay exact`() {
        assertEquals(
            "Schau mal: https://a.de/?id=2 und https://b.de/seite — beide gut!",
            clean("Schau mal: https://a.de/?utm_source=x&id=2 und https://b.de/seite?si=abc — beide gut!"),
        )
    }

    @Test
    fun `sentence punctuation after a link is left where it is`() {
        assertEquals(
            "Siehe https://a.de/seite.",
            clean("Siehe https://a.de/seite?utm_source=x."),
        )
    }

    @Test
    fun `a link in brackets keeps its bracket`() {
        assertEquals(
            "(siehe https://a.de/seite)",
            clean("(siehe https://a.de/seite?fbclid=abc)"),
        )
    }

    @Test
    fun `a bracket that belongs to the link is kept inside it`() {
        assertEquals(
            "https://de.wikipedia.org/wiki/Golf_(Sport)?x=1",
            clean("https://de.wikipedia.org/wiki/Golf_(Sport)?x=1&utm_source=a"),
        )
    }

    @Test
    fun `a www link without a scheme is cleaned too`() {
        assertEquals(
            "www.example.com/seite",
            clean("www.example.com/seite?utm_campaign=sommer"),
        )
    }

    // ── Odd shapes ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `parameter names are matched regardless of case`() {
        assertEquals("https://example.com/x", clean("https://example.com/x?UTM_Source=a&FBCLID=b"))
    }

    @Test
    fun `a parameter without a value is still recognised`() {
        assertEquals("https://example.com/x?id=1", clean("https://example.com/x?fbclid&id=1"))
    }

    @Test
    fun `an empty query is left alone`() {
        assertUnchanged("https://example.com/x?")
    }

    @Test
    fun `a stray question mark in prose is left alone`() {
        assertUnchanged("Wirklich? Ja.")
    }

    @Test
    fun `an empty string comes back empty`() {
        assertEquals("", clean(""))
    }
}
