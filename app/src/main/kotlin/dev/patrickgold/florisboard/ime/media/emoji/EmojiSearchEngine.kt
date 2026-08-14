/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.media.emoji

import java.text.Normalizer

/**
 * Ranks emojis against a typed query (issue #274).
 *
 * Deliberately free of Android and Compose so the ranking can be tested on the JVM against the real
 * asset files — the previous matcher lived inside the search panel's composable and could only be
 * judged by looking at a phone, which is how it shipped scoring nothing at all in 49 of 55 keyboard
 * languages.
 *
 * The index is built **once** per language and font state and then re-used for every keystroke. The
 * old code re-ran an `EmojiCompat` lookup and a native `Paint.hasGlyph()` call over ~3700 emojis for
 * every single character typed.
 *
 * Two annotation sets feed one index: the user's own language and English, the latter at a discount
 * ([FallbackWeight]). So a Hungarian layout finds 😘 for both `csók` and `kiss`, and the Hungarian
 * term wins when they collide.
 */
class EmojiSearchIndex private constructor(private val entries: List<Entry>) {

    /** One searchable emoji: its set (for skin-tone display) and its normalized search terms. */
    private class Entry(val set: EmojiSet, val order: Int, val terms: List<Term>)

    private class Term(val text: String, val isName: Boolean, val languageWeight: Double)

    /**
     * The best-matching emojis for [query], strongest first, at most [limit] of them. A blank query
     * or one that matches nothing yields an empty list.
     */
    fun search(query: String, limit: Int = DefaultLimit): List<EmojiSet> {
        val tokens = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Entry, Double>>(64)
        for (entry in entries) {
            val score = scoreEntry(entry, tokens)
            if (score > 0.0) scored.add(entry to score)
        }
        // Ties break on the inventory order, which is the CLDR/Unicode order: without it the result
        // strip would reshuffle between two runs of the same query.
        scored.sortWith(compareByDescending<Pair<Entry, Double>> { it.second }.thenBy { it.first.order })
        return scored.take(limit).map { it.first.set }
    }

    /**
     * Every token has to find a home somewhere in the entry — `smiling face` must not match everything
     * that is merely a face — and the entry's score is the average of the tokens' best hits.
     */
    private fun scoreEntry(entry: Entry, tokens: List<String>): Double {
        var total = 0.0
        for (token in tokens) {
            var best = 0.0
            for (term in entry.terms) {
                val score = scoreTerm(term.text, token)
                if (score <= 0.0) continue
                val weighted = score * term.languageWeight * (if (term.isName) 1.0 else KeywordWeight)
                if (weighted > best) best = weighted
            }
            if (best == 0.0) return 0.0
            total += best
        }
        return total / tokens.size
    }

    companion object {
        const val DefaultLimit = 90

        /** English terms rank below the user's own language, but still rank. */
        private const val FallbackWeight = 0.75

        /** A hit in the name beats the same hit in a keyword. */
        private const val KeywordWeight = 0.85

        /**
         * Builds the index over every emoji of [data] that the device can actually draw ([isSupported]),
         * annotated with [annotations] and, on top, [fallbackAnnotations] (English) for the emojis the
         * user's language does not name.
         */
        fun build(
            data: EmojiData,
            annotations: Map<String, EmojiAnnotation>,
            fallbackAnnotations: Map<String, EmojiAnnotation>,
            isSupported: (String) -> Boolean,
        ): EmojiSearchIndex {
            val entries = ArrayList<Entry>(2048)
            var order = 0
            for (sets in data.byCategory.values) {
                for (set in sets) {
                    val base = set.emojis.first()
                    val position = order++
                    if (!isSupported(base.value)) continue
                    val terms = ArrayList<Term>(16)
                    collectTerms(terms, annotations[base.value], 1.0)
                    collectTerms(terms, fallbackAnnotations[base.value], FallbackWeight)
                    if (terms.isEmpty()) continue
                    entries.add(Entry(set, position, terms))
                }
            }
            return EmojiSearchIndex(entries)
        }

        private fun collectTerms(into: MutableList<Term>, annotation: EmojiAnnotation?, weight: Double) {
            if (annotation == null) return
            val name = normalize(annotation.name)
            if (name.isNotEmpty()) into.add(Term(name, isName = true, languageWeight = weight))
            for (keyword in annotation.keywords) {
                val text = normalize(keyword)
                if (text.isNotEmpty() && text != name) {
                    into.add(Term(text, isName = false, languageWeight = weight))
                }
            }
        }

        /**
         * Lower-cases, collapses whitespace and drops non-spacing marks, so `csok` finds `csók` and
         * `grun` finds `grün`. Only category Mn is removed: the spacing marks of the Indic scripts
         * (category Mc) carry a vowel and are part of the word. Both the query and the indexed terms
         * pass through here, so whatever this folds together stays mutually findable.
         */
        fun normalize(text: String): String {
            val lowered = text.lowercase()
            val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
            val builder = StringBuilder(decomposed.length)
            var lastWasSpace = true
            for (char in decomposed) {
                when {
                    char.category == CharCategory.NON_SPACING_MARK -> Unit
                    char.isWhitespace() -> {
                        if (!lastWasSpace) {
                            builder.append(' ')
                            lastWasSpace = true
                        }
                    }
                    else -> {
                        builder.append(char)
                        lastWasSpace = false
                    }
                }
            }
            return builder.toString().trimEnd()
        }

        /**
         * How well a single query token matches one term, on a 0..1 scale. The tiers matter more than
         * their exact values: a plain `contains` (all the old matcher did) ranks `face with tears of
         * joy` above `joy` for the query `joy`, which is the wrong way round.
         */
        internal fun scoreTerm(term: String, token: String): Double {
            if (token.isEmpty() || term.isEmpty()) return 0.0
            val ratio = token.length.toDouble() / term.length.toDouble()
            return when {
                term == token -> 1.0
                term.startsWith(token) -> 0.8 + 0.2 * ratio
                startsAWord(term, token) -> 0.6 + 0.2 * ratio
                term.contains(token) -> 0.3 + 0.2 * ratio
                else -> 0.0
            }
        }

        /** True when [token] begins a word inside [term], e.g. `tear` in `face with tears of joy`. */
        private fun startsAWord(term: String, token: String): Boolean {
            var index = term.indexOf(' ')
            while (index >= 0) {
                if (term.startsWith(token, index + 1)) return true
                index = term.indexOf(' ', index + 1)
            }
            return false
        }
    }
}
