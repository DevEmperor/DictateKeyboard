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

/**
 * The frequency-ranked word list, also sorted by spelling, so the words that start with a prefix can be
 * found without reading the whole list (issue #381).
 *
 * The completion walk in `suggest()` reads the ranked list from the most frequent word down and stops once
 * the strip is full. For a common prefix that ends after a few dozen words. For a rare one — any prefix
 * with a slip in it — nothing matches and the walk reads all ~64,000 German words on every keystroke: up to
 * 29 ms on a Galaxy A55 release build. This finds the same words, in the same order, by binary search.
 *
 * Nothing is stored but one `IntArray` of ranks: the order compares the words themselves.
 *
 * @param words the ranked list, most frequent first.
 * @param keys the words' fold keys, aligned with [words], when the walk compares those instead ([DictFold]
 *   for Arabic script and French); null when it compares `startsWith(prefix, ignoreCase = true)`.
 */
internal class PrefixOrder(private val words: List<String>, private val keys: List<String>?) {

    private val byKey: IntArray = words.indices.sortedWith { a, b -> compare(keyOf(a), keyOf(b)) }.toIntArray()

    private fun keyOf(rank: Int): String = keys?.get(rank) ?: words[rank]

    /**
     * One character as a case-insensitive comparison sees it. `startsWith(…, ignoreCase = true)` counts two
     * characters equal when their upper cases, lowered again, are — so ordering by exactly that makes a
     * range of this order the set the walk would have matched, character for character. Only used when
     * there are no fold keys: those are compared as they are.
     */
    private fun unit(c: Char): Char = if (keys == null) c.uppercaseChar().lowercaseChar() else c

    private fun compare(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        for (i in 0 until n) {
            val d = unit(a[i]).compareTo(unit(b[i]))
            if (d != 0) return d
        }
        return a.length - b.length
    }

    private fun startsWith(key: String, prefix: String): Boolean {
        if (key.length < prefix.length) return false
        for (i in prefix.indices) if (unit(key[i]) != unit(prefix[i])) return false
        return true
    }

    /**
     * The ranks of every word starting with [prefix] (compared like the walk compares it), most frequent
     * first — or null when there are more than [limit]. A prefix that common is better served by the walk
     * itself, which finds its first few matches among the first few hundred words anyway.
     */
    fun ranksStartingWith(prefix: String, limit: Int): IntArray? {
        // The first entry whose key is not smaller than the prefix: every match follows it contiguously.
        var lo = 0
        var hi = byKey.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compare(keyOf(byKey[mid]), prefix) < 0) lo = mid + 1 else hi = mid
        }
        var end = lo
        while (end < byKey.size && startsWith(keyOf(byKey[end]), prefix)) {
            if (end - lo >= limit) return null
            end++
        }
        return byKey.copyOfRange(lo, end).apply { sort() }
    }
}
