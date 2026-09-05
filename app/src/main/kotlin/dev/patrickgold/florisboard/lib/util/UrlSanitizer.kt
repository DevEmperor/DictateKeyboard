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

/**
 * Removes tracking parameters from links on their way into the editor (issue #329).
 *
 * The whole design is built around one asymmetry: leaving a tracking parameter in costs the user a bit
 * of privacy they may not have noticed anyway, while removing the wrong parameter hands them a link
 * that does not work — and they will not find out until the person they sent it to says so. So every
 * rule here errs towards leaving things alone:
 *
 * * **A fixed list of names, never a shape.** "Looks like tracking" would eventually eat a session
 *   token or an ID that happens to be long and random.
 * * **Only the query.** Never the scheme, the host, the path or the fragment. A short link stays a
 *   short link; nothing is resolved, because resolving would mean sending the user's link somewhere.
 * * **Never re-encoded.** The query is split on `&` and `=` as text and reassembled from the original
 *   substrings, so a link that survives untouched comes out byte for byte as it went in.
 * * **On any doubt, the original.** Every failure path returns the input unchanged.
 *
 * Two entries earn their keep by being *absent*: `_nc_*` on Facebook's CDN and `t` on YouTube look like
 * tracking and are not — the first is what makes the image load, the second is the timestamp somebody
 * deliberately dragged to before sharing.
 */
object UrlSanitizer {

    /**
     * Longer than this and the text is not a shared link any more, it is a document. Pasting must never
     * be the slow step, and nobody is sanitising a novel.
     */
    private const val MAX_LENGTH = 4_000

    /**
     * An explicit scheme (or `www.`) is required, so prose that merely contains a dot — a file name, an
     * abbreviation, a version number — is never mistaken for a link and rewritten.
     */
    private val UrlRegex = Regex("""(?:https?://|www\.)[^\s<>"' ]+""", RegexOption.IGNORE_CASE)

    /** Trailing characters that belong to the sentence rather than to the link. */
    private const val TRAILING = ".,;:!\"'»)]}"

    /** Dropped on every host. */
    private val GLOBAL_NAMES = setOf(
        // Ad networks and click IDs
        "fbclid", "gclid", "gclsrc", "gbraid", "wbraid", "dclid", "msclkid", "yclid", "ttclid", "twclid",
        // Mail campaign IDs
        "mc_cid", "mc_eid", "vero_id", "vero_conv",
        // Share IDs handed out by the share sheet of the app the link came from
        "si", "igshid", "igsh", "share_app_id", "is_from_webapp", "sender_device", "sender_web_id",
        "_branch_match_id", "ref_src", "ref_url",
        // Marketplace session tracking
        "spm", "scm",
    )

    /** Dropped only on the hosts that use them for tracking, because elsewhere they carry meaning. */
    private val HOST_NAMES = mapOf(
        // On X, s= and t= identify the sharing session. On YouTube, t= is the timestamp.
        "x.com" to setOf("s", "t"),
        "twitter.com" to setOf("s", "t"),
        "youtube.com" to setOf("feature"),
        "youtu.be" to setOf("feature"),
    )

    private const val UTM_PREFIX = "utm_"

    /**
     * Returns [text] with the tracking parameters of every link in it removed, and everything else —
     * including the prose around the links — untouched.
     */
    fun clean(text: String): String {
        if (text.isEmpty() || text.length > MAX_LENGTH || '?' !in text) return text
        return runCatching { cleanUnguarded(text) }.getOrDefault(text)
    }

    private fun cleanUnguarded(text: String): String {
        var out: StringBuilder? = null
        var copiedUpTo = 0
        for (match in UrlRegex.findAll(text)) {
            val end = trimTrailing(text, match.range.first, match.range.last + 1)
            val url = text.substring(match.range.first, end)
            val cleaned = cleanUrl(url) ?: continue
            val builder = out ?: StringBuilder(text.length).also { out = it }
            builder.append(text, copiedUpTo, match.range.first)
            builder.append(cleaned)
            copiedUpTo = end
        }
        val builder = out ?: return text
        builder.append(text, copiedUpTo, text.length)
        return builder.toString()
    }

    /**
     * Walks back over punctuation that ends the sentence rather than the link, so `(see https://a.de/?x=1).`
     * does not carry `).` into the query. A closing bracket only counts as trailing when the link has no
     * matching opening one — plenty of real URLs contain balanced brackets.
     */
    private fun trimTrailing(text: String, start: Int, endExclusive: Int): Int {
        var end = endExclusive
        while (end > start) {
            val c = text[end - 1]
            if (c !in TRAILING) break
            val opener = when (c) {
                ')' -> '('
                ']' -> '['
                '}' -> '{'
                else -> null
            }
            if (opener != null && text.substring(start, end).contains(opener)) break
            end--
        }
        return end
    }

    /** The cleaned link, or null when there is nothing to do (which keeps the original in place). */
    private fun cleanUrl(url: String): String? {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return null
        val fragmentStart = url.indexOf('#', queryStart).let { if (it < 0) url.length else it }
        val query = url.substring(queryStart + 1, fragmentStart)
        if (query.isEmpty()) return null

        val host = hostOf(url) ?: return null
        val hostNames = HOST_NAMES[host].orEmpty()

        val pairs = query.split('&').filter { it.isNotEmpty() }
        val kept = pairs.filter { pair ->
            val name = pair.substringBefore('=').lowercase()
            !(name.startsWith(UTM_PREFIX) || name in GLOBAL_NAMES || name in hostNames)
        }
        // Nothing matched: hand back null so the original substring stays in place untouched, rather
        // than an identical-looking rebuild that could differ in some detail nobody thought about.
        if (kept.size == pairs.size) return null

        return buildString {
            append(url, 0, queryStart)
            if (kept.isNotEmpty()) {
                append('?')
                kept.joinTo(this, "&")
            }
            append(url, fragmentStart, url.length)
        }
    }

    /**
     * The registrable-looking host, lowercased and without `www.`/`m.`, or null when the link has no
     * recognisable authority. Deliberately simple: it decides only which of the two small host-specific
     * lists applies, never whether the link is valid.
     */
    private fun hostOf(url: String): String? {
        val afterScheme = url.indexOf("://").let { if (it < 0) 0 else it + 3 }
        var end = url.length
        for (i in afterScheme until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') {
                end = i
                break
            }
        }
        var host = url.substring(afterScheme, end)
        host = host.substringAfterLast('@').substringBefore(':').lowercase()
        if (host.isEmpty()) return null
        return host.removePrefix("www.").removePrefix("m.")
    }
}
