/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.dictionary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What may become a dictionary entry when names are imported from contacts (issue #264).
 *
 * The interesting half of this feature is what it *refuses*: an address book is full of entries whose
 * name is an e-mail address, a phone number or a title, and every one of those would end up protected
 * from autocorrect and offered as a suggestion for the rest of time.
 */
class ContactNameImportTest {

    // --- display names (the contact picker) ------------------------------------------------------

    @Test
    fun `a full name becomes one entry per name part`() {
        assertEquals(listOf("Ihor", "Bondur"), ContactNameImport.tokensFromDisplayName("Ihor Bondur"))
    }

    @Test
    fun `a title in front of the name is not a name`() {
        assertEquals(listOf("Ihor", "Bondur"), ContactNameImport.tokensFromDisplayName("Dr. Ihor Bondur"))
    }

    @Test
    fun `hyphens and apostrophes hold a name together`() {
        assertEquals(
            listOf("Meyer-Landrut", "O'Neill", "D’Angelo"),
            ContactNameImport.tokensFromDisplayName("Meyer-Landrut O'Neill D’Angelo"),
        )
    }

    @Test
    fun `an initial is too short to be worth knowing`() {
        assertEquals(listOf("Jannis", "Zahn"), ContactNameImport.tokensFromDisplayName("Jannis P. Zahn"))
    }

    @Test
    fun `a contact that is only an e-mail address contributes nothing`() {
        assertEquals(emptyList<String>(), ContactNameImport.tokensFromDisplayName("max@example.com"))
    }

    @Test
    fun `a contact that is only a phone number contributes nothing`() {
        assertEquals(emptyList<String>(), ContactNameImport.tokensFromDisplayName("+49 170 1234567"))
        assertEquals(emptyList<String>(), ContactNameImport.tokensFromDisplayName(null))
        assertEquals(emptyList<String>(), ContactNameImport.tokensFromDisplayName("   "))
    }

    @Test
    fun `the same name part is not offered twice`() {
        assertEquals(listOf("Müller"), ContactNameImport.tokensFromDisplayName("Müller müller"))
    }

    // --- vCard files (the contacts export) -------------------------------------------------------

    @Test
    fun `the structured name wins over the formatted one, without its title`() {
        val vcard = """
            BEGIN:VCARD
            VERSION:3.0
            N:Bondur;Ihor;Petro;Dr.;Jr.
            FN:Dr. Ihor Bondur Jr.
            TEL;TYPE=CELL:+49 170 1234567
            EMAIL:ihor@example.com
            END:VCARD
        """.trimIndent()

        assertEquals(listOf("Bondur", "Ihor", "Petro"), ContactNameImport.tokensFromVCard(vcard))
    }

    @Test
    fun `a card with only a formatted name still yields its parts`() {
        val vcard = "BEGIN:VCARD\r\nVERSION:2.1\r\nFN:Ada Lovelace\r\nEND:VCARD\r\n"

        assertEquals(listOf("Ada", "Lovelace"), ContactNameImport.tokensFromVCard(vcard))
    }

    @Test
    fun `every card in the file is read`() {
        val vcard = """
            BEGIN:VCARD
            N:Lovelace;Ada;;;
            END:VCARD
            BEGIN:VCARD
            N:Hopper;Grace;;;
            END:VCARD
        """.trimIndent()

        assertEquals(listOf("Lovelace", "Ada", "Hopper", "Grace"), ContactNameImport.tokensFromVCard(vcard))
    }

    @Test
    fun `quoted-printable names arrive with their umlauts intact`() {
        // What a phone writes when exporting vCard 2.1 — without decoding, this reads "J=C3=BCrgen".
        val vcard = """
            BEGIN:VCARD
            VERSION:2.1
            N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:M=C3=BCller;J=C3=BCrgen;;;
            END:VCARD
        """.trimIndent()

        assertEquals(listOf("Müller", "Jürgen"), ContactNameImport.tokensFromVCard(vcard))
    }

    @Test
    fun `a quoted-printable value broken across lines is put back together`() {
        val vcard = "BEGIN:VCARD\nFN;ENCODING=QUOTED-PRINTABLE:Ada Love=\nlace\nEND:VCARD"

        assertEquals(listOf("Ada", "Lovelace"), ContactNameImport.tokensFromVCard(vcard))
    }

    @Test
    fun `a folded line is put back together`() {
        val vcard = "BEGIN:VCARD\nFN:Ada Byron\n  Lovelace\nEND:VCARD"

        assertEquals(listOf("Ada", "Byron", "Lovelace"), ContactNameImport.tokensFromVCard(vcard))
    }

    @Test
    fun `escaped separators stay part of the name`() {
        val vcard = "BEGIN:VCARD\nN:Bondur\\, Jr.;Ihor;;;\nEND:VCARD"

        assertEquals(listOf("Bondur", "Ihor"), ContactNameImport.tokensFromVCard(vcard))
    }

    @Test
    fun `a group prefix in front of a property does not hide it`() {
        val vcard = "BEGIN:VCARD\nitem1.FN:Ada Lovelace\nEND:VCARD"

        assertEquals(listOf("Ada", "Lovelace"), ContactNameImport.tokensFromVCard(vcard))
    }

    @Test
    fun `a card whose name is an e-mail address contributes nothing`() {
        val vcard = "BEGIN:VCARD\nFN:max@example.com\nEMAIL:max@example.com\nEND:VCARD"

        assertEquals(emptyList<String>(), ContactNameImport.tokensFromVCard(vcard))
    }

    @Test
    fun `a file that is not a vCard at all yields nothing`() {
        assertEquals(emptyList<String>(), ContactNameImport.tokensFromVCard("just some text\nwithout properties"))
    }

    @Test
    fun `a whole address book is capped rather than flooding the glide index`() {
        val vcard = buildString {
            // Letters only: a digit would split the token and every card would collapse onto the same name.
            for (i in 1..1500) {
                val suffix = i.toString().map { 'a' + (it - '0') }.joinToString("")
                append("BEGIN:VCARD\nN:Nachname$suffix;Vorname$suffix;;;\nEND:VCARD\n")
            }
        }

        val names = ContactNameImport.tokensFromVCard(vcard)
        assertEquals(1000, names.size)
        assertTrue(names.first().startsWith("Nachname"))
    }
}
