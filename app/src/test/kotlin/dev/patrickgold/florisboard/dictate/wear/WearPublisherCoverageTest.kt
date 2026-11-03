/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.wear

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The watch gets a fresh copy of the phone's settings only when [DictateWearPublisher] hears that one of
 * them changed, and it hears only about the preferences on its list. That list is written by hand, far
 * from the code that decides what the watch gets, and it fell behind once already: the rewording fields
 * were added to the watch's settings (#130) and never to the list. So the two are compared here, by
 * the source: every `prefs.<group>.<name>` the resolver reads must be one the publisher watches.
 *
 * Reading the source is crude, but the alternative is no check at all. JetPref has no "any preference
 * changed" signal to subscribe to instead, and watching all of them would republish on every dictation.
 */
class WearPublisherCoverageTest {

    private val dictateDir: File by lazy {
        // Run from the repo root or from app/, depending on how Gradle was invoked.
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val suffix = "src/main/kotlin/dev/patrickgold/florisboard/dictate"
        while (dir != null) {
            File(dir, "app/$suffix").takeIf { it.isDirectory }?.let { return@lazy it }
            File(dir, suffix).takeIf { it.isDirectory }?.let { return@lazy it }
            dir = dir.parentFile
        }
        error("dictate sources not found from ${System.getProperty("user.dir")}")
    }

    private fun source(path: String) = File(dictateDir, path).readText()

    private fun prefsReadIn(text: String): Set<String> =
        Regex("""\bprefs\.(\w+)\.(\w+)""").findAll(text).map { "${it.groupValues[1]}.${it.groupValues[2]}" }.toSet()

    // The resolver and the functions it hands `prefs` to.
    private val resolverSources = listOf(
        "wear/PhoneWearSettingsResolver.kt",
        "DictateReasoningEffort.kt",
    )

    private val published by lazy { source("wear/DictateWearPublisher.kt") }

    @Test
    fun `every preference the watch's settings are made of republishes them when it changes`() {
        val read = resolverSources.flatMap { prefsReadIn(source(it)) }.toSet()
        // A guard against the regex finding nothing and so passing on nothing.
        assertTrue("dictate.rewordingProviderId" in read, "the resolver's reads were not found: $read")
        assertEquals(emptySet(), read - prefsReadIn(published), "read for the watch but never watched")
    }

    @Test
    fun `an edit of the prompts republishes, since the watch carries the automatic ones`() {
        assertTrue("PromptsDatabaseHelper" in source("wear/PhoneWearSettingsResolver.kt"))
        assertTrue("PromptsDatabaseHelper.getInstance(context).revision" in published)
    }
}
