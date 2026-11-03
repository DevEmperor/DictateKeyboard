/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app

/**
 * JSON kept in a preference, written so that the settings file gives it back unchanged (#463).
 *
 * JetPref stores a string on one line with `\`, line breaks and `"` escaped, but its reader undoes the
 * escapes one `replace` after another instead of in one pass: a backslash followed by `n` in the stored
 * text comes back as a backslash and a real line break. That is exactly how JSON writes a line break
 * inside a string (`\n`), so the value read back is no longer JSON, the preference fails to parse, and
 * its serializer falls back to empty. For the keyring that meant every provider account and key gone
 * after the next start because one account's name held a line break — while the load reported success.
 *
 * [encode] writes the escapes the reader damages as unicode escapes instead (`\u000a`, `\u000d`,
 * `\`): the same JSON to any parser, with no backslash left for JetPref to pair with an `n` or `r`.
 * [decode] repairs what earlier versions stored. The damage always leaves a backslash directly in front
 * of a raw line break, which JSON never contains otherwise, so turning that pair back into `\n` or `\r`
 * restores the text exactly — also when the backslash before the `n` was the user's own.
 */
object PreferenceJson {

    /** [json] as it should go into the settings file. */
    fun encode(json: String): String {
        if ('\\' !in json) return json
        val out = StringBuilder(json.length + 16)
        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                when (val next = json[i + 1]) {
                    'n' -> out.append("\\u000a")
                    'r' -> out.append("\\u000d")
                    '\\' -> out.append("\\u005c")
                    else -> out.append(c).append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** The JSON in [stored], as read back from the settings file. */
    fun decode(stored: String): String {
        if ('\n' !in stored && '\r' !in stored) return stored
        return stored.replace("\\\n", "\\n").replace("\\\r", "\\r")
    }
}
