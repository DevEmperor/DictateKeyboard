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

import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey

/**
 * Live snapshot of the character keyboard's key geometry, for the autocorrect touch model (issue #242).
 * Updated from the rendered layout every time the CHARACTERS keyboard is shown — independent of glide
 * typing (the layout normally only feeds the glide classifier, which is off for many users).
 *
 * All coordinates are stored **normalized to key widths**, so everything downstream is resolution- and
 * DPI-independent: a distance of 1.0 means "one key width apart".
 *
 * Two consumers, deliberately different in what they know:
 *  - [normSqDistance] compares two *keys* and serves the legacy edit-distance ranking, which only ever sees
 *    the resolved character, not where the finger actually landed.
 *  - [snapshot] hands the beam decoder an immutable [Layout] so one decode sees one consistent geometry
 *    even if the keyboard re-lays-out underneath it.
 */
object KeyProximityInfo {

    /**
     * Immutable view of one keyboard layout, in key-width units. Primitive arrays throughout: this is a
     * per-keystroke hot path and the decoder queries it thousands of times per word.
     */
    class Layout(
        private val codes: IntArray,
        private val xs: FloatArray,
        private val ys: FloatArray,
    ) {
        val size: Int get() = codes.size

        fun codeAt(index: Int): Int = codes[index]

        /** Squared distance from the normalized point ([x], [y]) to the key at [index], in key-width². */
        fun sqDistance(index: Int, x: Float, y: Float): Float {
            val dx = xs[index] - x
            val dy = ys[index] - y
            return dx * dx + dy * dy
        }

        /** Squared distance between two keys of this layout, in key-width². */
        fun sqDistanceBetween(a: Int, b: Int): Float = sqDistance(a, xs[b], ys[b])

        /** Squared distance to the closest key of all — the reference cost for a likelihood ratio. */
        fun nearestSqDistance(x: Float, y: Float): Float {
            var best = Float.MAX_VALUE
            for (i in codes.indices) {
                val d = sqDistance(i, x, y)
                if (d < best) best = d
            }
            return if (best == Float.MAX_VALUE) 0f else best
        }

        /**
         * Writes the indices of the [out].size keys closest to ([x], [y]) into [out], nearest first, and
         * returns how many were written. Insertion into a tiny fixed array — with ~30 keys and a handful of
         * slots this beats allocating and sorting a list on every tap of every beam expansion.
         */
        fun nearestKeys(x: Float, y: Float, out: IntArray): Int {
            val want = minOf(out.size, codes.size)
            if (want == 0) return 0
            val dists = FloatArray(want) { Float.MAX_VALUE }
            var filled = 0
            for (i in codes.indices) {
                val d = sqDistance(i, x, y)
                if (filled < want || d < dists[want - 1]) {
                    var pos = if (filled < want) filled else want - 1
                    while (pos > 0 && dists[pos - 1] > d) {
                        dists[pos] = dists[pos - 1]
                        out[pos] = out[pos - 1]
                        pos--
                    }
                    dists[pos] = d
                    out[pos] = i
                    if (filled < want) filled++
                }
            }
            return filled
        }

        /** Index of the key for [char], or -1 — tries the exact code first, then the lowercase one. */
        fun indexOf(char: Char): Int {
            val code = char.code
            for (i in codes.indices) if (codes[i] == code) return i
            val lower = char.lowercaseChar().code
            if (lower != code) {
                for (i in codes.indices) if (codes[i] == lower) return i
            }
            return -1
        }
    }

    @Volatile
    private var layout: Layout? = null

    // Pixel width of one key in the currently rendered layout, for converting raw touch coordinates.
    @Volatile
    private var keyWidthPx: Float = 0f

    /** Update from the currently rendered character keys. Cheap; called on each layout of the letters view. */
    fun update(keys: List<TextKey>) {
        if (keys.isEmpty()) return
        var width = 0f
        for (k in keys) {
            val code = (k.data as? KeyData)?.code ?: continue
            if (code < 32) continue
            val w = k.visibleBounds.width
            if (w > 0f) {
                width = w
                break
            }
        }
        if (width <= 0f) return
        val codes = ArrayList<Int>(keys.size)
        val xs = ArrayList<Float>(keys.size)
        val ys = ArrayList<Float>(keys.size)
        for (k in keys) {
            val code = (k.data as? KeyData)?.code ?: continue
            if (code < 32) continue // skip control/action keys (space, shift, delete, …)
            val bounds = k.visibleBounds
            codes.add(code)
            xs.add(bounds.center.x / width)
            ys.add(bounds.center.y / width)
        }
        if (codes.isEmpty()) return
        keyWidthPx = width
        layout = Layout(codes.toIntArray(), xs.toFloatArray(), ys.toFloatArray())
    }

    /** True once a layout has been captured and distances can be computed. */
    val isReady: Boolean
        get() = layout != null && keyWidthPx > 0f

    /** Immutable geometry for one decode, or null if no layout has been rendered yet. */
    fun snapshot(): Layout? = layout

    /** Converts a raw touch coordinate in pixels to key-width units, or null if no layout is known. */
    fun normalize(xPx: Float, yPx: Float): FloatArray? {
        val w = keyWidthPx
        if (w <= 0f) return null
        return floatArrayOf(xPx / w, yPx / w)
    }

    /**
     * Squared physical distance between the keys for [a] and [b] in key-width² units (0 for the same char,
     * ~1 for horizontally adjacent keys). Returns null when either key is not in the current layout, so the
     * caller can fall back to a neutral cost.
     */
    fun normSqDistance(a: Char, b: Char): Float? {
        if (a == b) return 0f
        val l = layout ?: return null
        val ia = l.indexOf(a)
        if (ia < 0) return null
        val ib = l.indexOf(b)
        if (ib < 0) return null
        return l.sqDistanceBetween(ia, ib)
    }
}
