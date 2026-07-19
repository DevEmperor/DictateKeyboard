/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.devemperor.dictate.wear.emoji

import android.content.Context

/** A categorized emoji set for the watch picker (#106). */
data class WearEmojiCategory(
    val id: String,
    val label: String,
    val emojis: List<String>,
)

/**
 * Loads the emoji set bundled with the watch app from `assets/emoji/root.txt` — the same CLDR-derived
 * data the phone keyboard ships, but parsed down to just the glyph + category (no names/keywords; the
 * watch picker doesn't search). Parsed once and cached.
 *
 * File format (per line): `<emoji>;<name>;<keywords>` under `[category_id]` section headers.
 */
object WearEmojiData {

    private const val ASSET_PATH = "emoji/root.txt"

    private val LABELS = mapOf(
        "smileys_emotion" to "😀",
        "people_body" to "🧑",
        "animals_nature" to "🐶",
        "food_drink" to "🍎",
        "travel_places" to "✈️",
        "activities" to "⚽",
        "objects" to "💡",
        "symbols" to "❤️",
        "flags" to "🏁",
    )

    @Volatile
    private var cached: List<WearEmojiCategory>? = null

    fun categories(context: Context): List<WearEmojiCategory> {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: load(context).also { cached = it }
        }
    }

    private fun load(context: Context): List<WearEmojiCategory> {
        val byCategory = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        context.assets.open(ASSET_PATH).bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                when {
                    line.isEmpty() || line.startsWith("#") -> Unit
                    line.startsWith("[") && line.endsWith("]") -> {
                        val id = line.substring(1, line.length - 1)
                        current = byCategory.getOrPut(id) { mutableListOf() }
                    }
                    else -> {
                        val glyph = line.substringBefore(';')
                        if (glyph.isNotEmpty()) current?.add(glyph)
                    }
                }
            }
        }
        return byCategory.map { (id, emojis) ->
            WearEmojiCategory(id = id, label = LABELS[id] ?: "•", emojis = emojis)
        }
    }
}
