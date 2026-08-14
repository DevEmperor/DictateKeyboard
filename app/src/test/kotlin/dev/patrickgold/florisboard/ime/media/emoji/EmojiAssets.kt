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

import java.io.File

/**
 * Reads the *shipped* emoji assets straight off disk, so the emoji tests judge the files that actually
 * go into the APK rather than a fixture that agrees with them.
 *
 * That distinction is the whole point here: issue #274 was not a logic error but a data one — the
 * matcher was fine and the files it was handed carried no text at all.
 */
object EmojiAssets {
    private val root: File by lazy {
        // Gradle runs unit tests with the module directory as the working directory, but that has moved
        // before; walk up until the assets turn up rather than depend on it.
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/assets/ime/media/emoji")
            if (candidate.isDirectory) return@lazy candidate
            val here = File(dir, "src/main/assets/ime/media/emoji")
            if (here.isDirectory) return@lazy here
            dir = dir.parentFile
        }
        error("emoji assets not found from ${System.getProperty("user.dir")}")
    }

    /** The language codes with a shipped annotation file. */
    val languages: List<String> by lazy {
        File(root, "annotations").listFiles().orEmpty()
            .filter { it.name.endsWith(".txt") }
            .map { it.name.removeSuffix(".txt") }
            .sorted()
    }

    val inventory: EmojiData by lazy {
        File(root, "root.txt").useLines { EmojiData.parse(it) }
    }

    /** Every base emoji of the inventory, in file order. */
    val inventoryValues: List<String> by lazy {
        inventory.byCategory.values.flatten().map { it.emojis.first().value }
    }

    fun annotations(language: String): Map<String, EmojiAnnotation> {
        return File(root, "annotations/$language.txt").useLines { EmojiAnnotations.parse(it) }
    }

    /** An index over the full inventory — no font check, since a JVM test has no emoji font to ask. */
    fun index(language: String): EmojiSearchIndex {
        return EmojiSearchIndex.build(
            data = inventory,
            annotations = annotations(language),
            fallbackAnnotations = annotations(EmojiAnnotations.FallbackLanguage),
            isSupported = { true },
        )
    }

    /** The top [count] results for [query], as plain emoji strings. */
    fun EmojiSearchIndex.top(query: String, count: Int = 10): List<String> {
        return search(query).take(count).map { it.emojis.first().value }
    }
}
