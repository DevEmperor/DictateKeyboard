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
 * A word-context table — `"w1 w2" -> count` or `"w1 w2 w3" -> count` — held as one flat blob and
 * binary-searched (issue #334).
 *
 * ### Why not a HashMap
 *
 * The bigram table used to be a `HashMap<String, Long>`, which is the obvious thing and was fine at
 * 60,000 entries. Two things stop being fine when the tables grow to the sizes the measurement chose
 * (150k bigrams + 100k trigrams per language):
 *
 * * **Memory.** Every entry costs a `String` object, a boxed `Long` and a hash node — on the order of
 *   150 bytes for a key of twenty characters, so a quarter of a million entries is tens of megabytes
 *   of heap inside an input method. Here the keys stay in the bytes they were read as, and the only
 *   per-entry cost is three ints: about 7 MB for both tables, roughly what the old 60k bigram map
 *   alone occupied, for five times the data.
 * * **Prefix lookup.** Next-word prediction needs "every continuation of these words", and against a
 *   map that is a scan of all 60,000 keys on every prediction point. Sorted, it is a binary search
 *   for the range and a walk of the handful of entries inside it.
 *
 * ### The blob
 *
 * [blob] is the file's own UTF-8 bytes, unmodified: `key\tcount\n` per line. [lineStart] holds n+1
 * offsets and [keyEnd] the offset of each line's tab, so a key is `blob[lineStart[i] until keyEnd[i]]`
 * and needs no object until somebody asks for it.
 *
 * **Comparison is on UTF-8 bytes, not on `String`.** That is not an implementation detail: UTF-8 byte
 * order is code-point order, which is what the generator's Python sort produces, while Kotlin's
 * `String.compareTo` compares UTF-16 code units and disagrees above the BMP. Comparing the bytes is
 * what makes "the file arrives sorted" true rather than nearly true.
 *
 * Files are written key-sorted by `tools/glide-dict/ngramcount.py`, so loading is a linear scan. A
 * file that is *not* sorted — one of the old count-ordered bigram files still on a device, or any
 * file whose keys were folded on the way in ([DictFold.foldKey] can reorder and even collide them) —
 * is detected during that scan and sorted then, so correctness never depends on the file being right.
 */
class NgramIndex private constructor(
    private val blob: ByteArray,
    private val lineStart: IntArray,
    private val keyEnd: IntArray,
    private val counts: IntArray,
    val size: Int,
) {
    val isEmpty: Boolean get() = size == 0

    /** How often [key] was seen, or 0 if the table does not have it. */
    fun countOf(key: String): Long {
        if (size == 0) return 0L
        val needle = key.toByteArray(Charsets.UTF_8)
        val at = search(needle, needle.size)
        return if (at >= 0) counts[at].toLong() else 0L
    }

    /**
     * The continuations of [prefix] that were seen most often — the last word of every key that
     * begins with `"$prefix "` — at most [max] of them, commonest first.
     *
     * The range for a prefix is contiguous because the table is sorted, so this touches only the
     * entries that share it. They are in key order inside that range, not count order, which is why
     * the whole range is walked: it is a few dozen entries for a common word and one or two for the
     * rest, against 60,000 for the scan this replaces.
     */
    fun topContinuations(prefix: String, max: Int): List<String> {
        if (size == 0 || max <= 0) return emptyList()
        val needle = "$prefix ".toByteArray(Charsets.UTF_8)
        val lo = lowerBound(needle)
        if (lo >= size || !startsWith(lo, needle)) return emptyList()

        // Selection sort over the range, kept to [max] because max is 3: a full sort of the range
        // would cost more than the walk it saves.
        val bestAt = IntArray(max)
        val bestCount = IntArray(max)
        var found = 0
        var i = lo
        while (i < size && startsWith(i, needle)) {
            val c = counts[i]
            if (found < max || c > bestCount[found - 1]) {
                var slot = if (found < max) found++ else max - 1
                while (slot > 0 && bestCount[slot - 1] < c) {
                    bestCount[slot] = bestCount[slot - 1]
                    bestAt[slot] = bestAt[slot - 1]
                    slot--
                }
                bestCount[slot] = c
                bestAt[slot] = i
            }
            i++
        }
        return (0 until found).map {
            val entry = bestAt[it]
            val from = lineStart[entry] + needle.size
            String(blob, from, keyEnd[entry] - from, Charsets.UTF_8)
        }
    }

    /** Index of [needle], or -1. */
    private fun search(needle: ByteArray, len: Int): Int {
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compareKey(mid, needle, len)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** The first entry whose key is not less than [needle]. */
    private fun lowerBound(needle: ByteArray): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareKey(mid, needle, needle.size) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Lexicographic comparison of entry [i]'s key against the first [len] bytes of [needle]. */
    private fun compareKey(i: Int, needle: ByteArray, len: Int): Int {
        var a = lineStart[i]
        val aEnd = keyEnd[i]
        var b = 0
        while (a < aEnd && b < len) {
            // Bytes are signed in Kotlin and UTF-8 continuation bytes are negative, so an unsigned
            // comparison is required for the order to be code-point order above ASCII.
            val d = (blob[a].toInt() and 0xFF) - (needle[b].toInt() and 0xFF)
            if (d != 0) return d
            a++; b++
        }
        return (aEnd - lineStart[i]) - len
    }

    private fun startsWith(i: Int, needle: ByteArray): Boolean {
        if (keyEnd[i] - lineStart[i] < needle.size) return false
        var a = lineStart[i]
        for (b in needle.indices) {
            if (blob[a] != needle[b]) return false
            a++
        }
        return true
    }

    companion object {
        val EMPTY = NgramIndex(ByteArray(0), IntArray(1), IntArray(0), IntArray(0), 0)

        /**
         * Parse a `key\tcount\n` table. [fold] is applied to every key when the language needs it
         * ([DictFold.hasNonTrivialFold]); folding can reorder keys and make two of them equal, so the
         * result is re-sorted and merged whenever the scan finds it out of order.
         */
        fun parse(text: String, fold: ((String) -> String)? = null): NgramIndex {
            if (text.isEmpty()) return EMPTY
            // Every key is encoded exactly once, here, and the encoded form is what gets compared,
            // sorted and written. Encoding inside the comparator instead would allocate two arrays
            // per comparison, which a sort of a quarter of a million keys does millions of times.
            val keys = ArrayList<ByteArray>(text.length / 24)
            val values = ArrayList<Int>(text.length / 24)
            var sorted = true
            text.lineSequence().forEach { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEach
                val count = line.substring(tab + 1).toIntOrNull() ?: return@forEach
                val key = line.substring(0, tab).let { if (fold != null) fold(it) else it }
                if (key.isEmpty()) return@forEach
                val encoded = key.toByteArray(Charsets.UTF_8)
                if (keys.isNotEmpty() && compareBytes(keys[keys.size - 1], encoded) > 0) sorted = false
                keys.add(encoded)
                values.add(count)
            }
            if (keys.isEmpty()) return EMPTY
            val order = if (sorted) keys.indices.toList()
            else keys.indices.sortedWith { x, y -> compareBytes(keys[x], keys[y]) }

            val n = keys.size
            val lineStart = IntArray(n + 1)
            val keyEnd = IntArray(n)
            val counts = IntArray(n)
            var blob = ByteArray(text.length.coerceAtLeast(16))
            var out = 0
            var written = 0
            for (idx in order) {
                val encoded = keys[idx]
                // Folding can collide two spellings onto one key, and a collision has to add the
                // counts rather than let whichever came last silently replace the other. They are
                // adjacent because the sequence is sorted by the time it gets here.
                if (written > 0 &&
                    keyEnd[written - 1] - lineStart[written - 1] == encoded.size &&
                    regionEquals(blob, lineStart[written - 1], encoded)
                ) {
                    counts[written - 1] += values[idx]
                    continue
                }
                if (out + encoded.size > blob.size) blob = blob.copyOf(maxOf(blob.size * 2, out + encoded.size))
                encoded.copyInto(blob, out)
                lineStart[written] = out
                out += encoded.size
                keyEnd[written] = out
                counts[written] = values[idx]
                written++
            }
            lineStart[written] = out
            return NgramIndex(blob.copyOf(out), lineStart, keyEnd, counts, written)
        }

        private fun compareBytes(x: ByteArray, y: ByteArray): Int {
            var i = 0
            while (i < x.size && i < y.size) {
                // Bytes are signed in Kotlin and UTF-8 continuation bytes are negative, so the
                // comparison has to be unsigned for the order to be code-point order above ASCII.
                val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
                if (d != 0) return d
                i++
            }
            return x.size - y.size
        }

        private fun regionEquals(blob: ByteArray, start: Int, encoded: ByteArray): Boolean {
            for (i in encoded.indices) if (blob[start + i] != encoded[i]) return false
            return true
        }
    }
}
