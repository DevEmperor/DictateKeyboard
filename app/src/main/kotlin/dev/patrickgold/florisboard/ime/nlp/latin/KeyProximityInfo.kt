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
 * Live snapshot of the character keyboard's key geometry, for the autocorrect proximity ("touch") model
 * (issue: better autocorrect / Tier 1): weight a typo edit by how physically close the two keys are, so a
 * fat-finger substitution of an adjacent key ranks above a far one. Updated from the rendered layout every
 * time the CHARACTERS keyboard is shown — independent of glide typing (the layout normally only feeds the
 * glide classifier, which is off for many users).
 *
 * Keys are stored by their base char code → pixel center; distances are normalized to key-widths so the
 * model is resolution/DPI independent.
 */
object KeyProximityInfo {
    @Volatile private var centers: Map<Int, FloatArray> = emptyMap() // char code -> [x, y]
    @Volatile private var keyWidth: Float = 0f

    /** Update from the currently rendered character keys. Cheap; called on each layout of the letters view. */
    fun update(keys: List<TextKey>) {
        if (keys.isEmpty()) return
        val map = HashMap<Int, FloatArray>(keys.size)
        var width = 0f
        for (k in keys) {
            val code = (k.data as? KeyData)?.code ?: continue
            if (code < 32) continue // skip control/action keys (space, shift, delete, …)
            val bounds = k.visibleBounds
            map[code] = floatArrayOf(bounds.center.x, bounds.center.y)
            if (width == 0f) width = bounds.width
        }
        centers = map
        keyWidth = width
    }

    /** True once a layout has been captured and distances can be computed. */
    val isReady: Boolean
        get() = keyWidth > 0f && centers.isNotEmpty()

    /**
     * Squared physical distance between the keys for [a] and [b] in key-width² units (0 for the same char,
     * ~1 for horizontally adjacent keys). Returns null when either key is not in the current layout, so the
     * caller can fall back to a neutral cost.
     */
    fun normSqDistance(a: Char, b: Char): Float? {
        if (a == b) return 0f
        val w = keyWidth
        if (w <= 0f) return null
        val pa = centers[a.code] ?: centers[a.lowercaseChar().code] ?: return null
        val pb = centers[b.code] ?: centers[b.lowercaseChar().code] ?: return null
        val dx = (pa[0] - pb[0]) / w
        val dy = (pa[1] - pb[1]) / w
        return dx * dx + dy * dy
    }
}
