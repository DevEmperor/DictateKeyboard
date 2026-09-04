/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.overlay

import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The per-app floating-button positions, as they are written to preferences (issue #323).
 *
 * "Remember position per app" used to hold only for as long as the accessibility service happened to be
 * alive — the map lived in the controller and nothing wrote it anywhere. A service restart, an app update
 * or a reboot silently emptied it, so the setting was true in the moment and false the next morning.
 *
 * Stored as a list rather than a map, in the order the positions were last used, which is what the
 * clipboard and sticker histories do and for the same reason: the order is the only sensible way to
 * decide what to drop once [MAX_ENTRIES] is reached. A map keyed by package name has no far end.
 */
@Serializable
data class BubbleAnchors(
    val entries: List<Entry> = emptyList(),
) {
    /** One app's remembered position. */
    @Serializable
    data class Entry(
        val pkg: String,
        val anchor: BubbleAnchor,
    )

    /** The entries as a lookup, keeping least-recently-used first so the controller can go on trimming. */
    fun toMap(): LinkedHashMap<String, BubbleAnchor> {
        val map = LinkedHashMap<String, BubbleAnchor>(entries.size)
        for (entry in entries) {
            map[entry.pkg] = entry.anchor
        }
        return map
    }

    object Serializer : PreferenceSerializer<BubbleAnchors> {
        override fun serialize(value: BubbleAnchors): String {
            return Json.encodeToString(value)
        }

        override fun deserialize(value: String): BubbleAnchors {
            return try {
                Json.decodeFromString(value)
            } catch (e: Throwable) {
                flogError { "Failed to deserialize BubbleAnchors: ${e.message}" }
                Empty
            }
        }
    }

    companion object {
        val Empty = BubbleAnchors()

        /**
         * How many apps keep a position. Nothing prunes this list on its own — an app the user opens once
         * and never again would otherwise sit in preferences forever — and a hundred is far past the
         * number of apps anyone dictates into.
         */
        const val MAX_ENTRIES = 100

        /**
         * Takes the [MAX_ENTRIES] most recently used positions from a map in least-recently-used order.
         *
         * Dropping from the front is the point: the entries the user is actually working with are at the
         * back, so a full list loses the app that has not been touched in longest.
         */
        fun of(positions: Map<String, BubbleAnchor>): BubbleAnchors {
            val all = positions.map { (pkg, anchor) -> Entry(pkg, anchor) }
            return BubbleAnchors(all.takeLast(MAX_ENTRIES))
        }
    }
}
