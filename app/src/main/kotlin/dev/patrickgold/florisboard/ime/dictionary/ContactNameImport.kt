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

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Names from the address book, turned into personal-dictionary entries (issue #264).
 *
 * A keyboard that autocorrects a colleague's surname into something else is the complaint this
 * answers — but a keyboard asking for `READ_CONTACTS` is the single most suspicious thing a keyboard
 * can do, and that permission would then sit in every installation's Play listing, including all the
 * ones that never wanted the feature. So the address book is never opened here. Names arrive only
 * along two paths the user walks themselves: the system contact picker, which grants read access to
 * exactly the one contact they tapped and needs no permission at all, and a contacts file they
 * exported and chose in the file dialog.
 *
 * What comes in is written to the personal dictionary with no language attached, which the rest of
 * the keyboard already knows how to use: `LatinLanguageProvider` consults it in `isKnownWord` (so the
 * name is never autocorrected, and the spell checker stops underlining it), offers it as a completion
 * while typing, and — since issue #263 — indexes it for glide typing.
 */
object ContactNameImport {
    /** Everything that cannot be part of a name splits one: "Meyer-Landrut" and "O'Neill" survive. */
    private val SEPARATORS = Regex("[^\\p{L}\\p{M}'’-]+")

    /** Below this a "name" is an initial or debris; the dictionary is better off without it. */
    private const val MIN_TOKEN_LENGTH = 2

    /** An export of a whole address book is normal; flooding the glide index with it is not. */
    private const val MAX_NAMES = 1000

    /**
     * Dropped when they show up as a name part. Only ever seen in front of a real name, so nothing is
     * lost — and vCards do not need this at all, since their prefix component is not read to begin with.
     */
    private val TITLES = setOf(
        "dr", "prof", "mr", "mrs", "ms", "miss", "sir", "herr", "frau", "sr", "jr", "dipl", "ing", "med",
    )

    /**
     * The display name of the contact behind [uri], or null if it cannot be read.
     *
     * Deliberately only the display name, and deliberately only from the picked row: the picker's
     * temporary grant covers that URI, and going after the structured given/family fields would mean
     * querying the data table, which is what needs the permission this whole file exists to avoid.
     */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /** The usable name parts of a single contact's display name — "Dr. Ihor Bondur" → Ihor, Bondur. */
    fun tokensFromDisplayName(displayName: String?): List<String> = tokenize(listOf(displayName.orEmpty()))

    /**
     * The name parts in an exported contacts file (vCard 2.1 / 3.0 / 4.0).
     *
     * Only `N` and `FN` are read — no numbers, no addresses, no notes. `N` wins where a card has both,
     * because its components are already the split this needs, and because its prefix and suffix
     * components can simply be left where they are.
     */
    fun tokensFromVCard(text: String): List<String> {
        val fields = mutableListOf<String>()
        var structured: List<String>? = null
        var formatted: String? = null

        fun endCard() {
            val name = structured?.takeIf { parts -> parts.any { it.isNotBlank() } }
            if (name != null) {
                // Family, given, additional — indices 3 and 4 are the prefix ("Dr.") and suffix ("Jr.").
                fields.addAll(name.take(3))
            } else {
                formatted?.let { fields.add(it) }
            }
            structured = null
            formatted = null
        }

        for (line in unfold(text)) {
            val separator = line.indexOf(':')
            if (separator < 0) continue
            val head = line.substring(0, separator)
            val value = line.substring(separator + 1)
            val property = head.substringBefore(';').substringAfter('.').trim().uppercase()
            when (property) {
                "BEGIN" -> if (value.trim().equals("VCARD", ignoreCase = true)) endCard()
                "END" -> endCard()
                "N" -> structured = splitComponents(decodeValue(head, value))
                "FN" -> formatted = unescape(decodeValue(head, value))
            }
        }
        endCard()
        return tokenize(fields)
    }

    /**
     * Writes [tokens] to [dao] and returns those that were actually new, in order.
     *
     * `locale = null` is what the dictionary calls "all languages" — right for a name, which belongs to
     * a person rather than to the language the keyboard happens to be set to. The comparison is
     * case-insensitive because "Bondur" and "bondur" are the same word to everything downstream, and a
     * second row would only clutter the list.
     */
    fun addToDictionary(dao: UserDictionaryDao, tokens: List<String>): List<String> {
        val added = mutableListOf<String>()
        for (token in tokens) {
            val known = runCatching {
                dao.query(token).any { it.word.equals(token, ignoreCase = true) }
            }.getOrDefault(true)
            if (known) continue
            runCatching {
                dao.insert(
                    UserDictionaryEntry(
                        id = 0,
                        word = token,
                        freq = FREQUENCY_MAX,
                        locale = null,
                        shortcut = null,
                    ),
                )
            }.onSuccess { added.add(token) }
        }
        return added
    }

    /**
     * The name parts of [fields], de-duplicated case-insensitively and capped.
     *
     * A whole field is thrown away rather than split when it holds an e-mail address or has no letter
     * in it at all: contacts whose display name is `max@example.com` or a phone number are the most
     * common debris in any address book, and splitting one would put "max", "example" and "com" in the
     * user's dictionary.
     */
    private fun tokenize(fields: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = mutableListOf<String>()
        for (field in fields) {
            val trimmed = field.trim()
            if (trimmed.isEmpty() || trimmed.contains('@') || trimmed.none { it.isLetter() }) continue
            for (raw in trimmed.split(SEPARATORS)) {
                val token = raw.trim('-', '\'', '’')
                if (token.length < MIN_TOKEN_LENGTH) continue
                if (token.none { it.isLetter() }) continue
                val key = token.lowercase()
                if (key in TITLES) continue
                if (seen.add(key)) out.add(token)
                if (out.size >= MAX_NAMES) return out
            }
        }
        return out
    }

    /**
     * The logical lines of a vCard, joining the two ways a real export breaks one up: the folding of
     * long lines (the continuation starts with a space or tab) and quoted-printable's soft line break
     * (the line ends with `=`), which is what phones producing vCard 2.1 use for any name with an
     * umlaut in it.
     */
    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in text.lineSequence()) {
            val last = out.lastOrNull()
            when {
                last != null && last.endsWith("=") && last.contains(QUOTED_PRINTABLE, ignoreCase = true) ->
                    out[out.lastIndex] = last.dropLast(1) + raw
                last != null && (raw.startsWith(" ") || raw.startsWith("\t")) ->
                    out[out.lastIndex] = last + raw.substring(1)
                else -> out.add(raw)
            }
        }
        return out
    }

    /** Decodes a property value according to the `ENCODING` and `CHARSET` parameters in [head]. */
    private fun decodeValue(head: String, value: String): String {
        if (!head.contains(QUOTED_PRINTABLE, ignoreCase = true)) return value
        val charset = head.split(';')
            .firstOrNull { it.trim().startsWith("CHARSET=", ignoreCase = true) }
            ?.substringAfter('=')?.trim()
            ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
            ?: Charsets.UTF_8
        return decodeQuotedPrintable(value, charset)
    }

    private fun decodeQuotedPrintable(value: String, charset: Charset): String {
        val bytes = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val hex = if (c == '=' && i + 2 < value.length) value.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) {
                bytes.write(hex)
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(charset))
                i++
            }
        }
        return String(bytes.toByteArray(), charset)
    }

    /** Splits a structured value at its unescaped `;` and unescapes each component. */
    private fun splitComponents(value: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '\\' && i + 1 < value.length -> {
                    current.append(unescapeChar(value[i + 1]))
                    i += 2
                }
                c == ';' -> {
                    out.add(current.toString())
                    current.clear()
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        out.add(current.toString())
        return out
    }

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                out.append(unescapeChar(value[i + 1]))
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun unescapeChar(c: Char): Char = when (c) {
        'n', 'N' -> ' ' // a line break inside a name is a separator like any other
        else -> c
    }

    private const val QUOTED_PRINTABLE = "QUOTED-PRINTABLE"
}
