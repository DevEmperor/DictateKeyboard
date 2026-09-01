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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What an update is allowed to say for itself.
 *
 * Three surfaces read this bookkeeping: the changelog dialog in the app, the "Dictate was updated"
 * nudge in the Smartbar (both through [AppVersionUtils.shouldShowChangelog]), and the full-screen
 * What's-new tour ([AppVersionUtils.pendingTourVersions]). The rule that ties them together is that a
 * patch release is a repair and stays quiet, while a feature release introduces itself.
 *
 * The version arithmetic behind that is easy to get subtly wrong in a way nobody notices until a
 * release day, which is what these cases are here to prevent.
 */
class UpdateAnnouncementTest {

    private fun v(raw: String) = VersionName.fromString(raw)!!

    private val tours = listOf(v("5.0.0"), v("5.1.0"), v("5.2.0"), v("5.3.0"), v("6.0.0"), v("6.1.0"))

    // --- the changelog dialog and the Smartbar nudge ------------------------------------------------

    @Test
    fun `a feature release announces itself`() {
        assertTrue(
            AppVersionUtils.shouldShowChangelog(
                installVersion = v("5.3.0"), lastChangelogVersion = v("6.0.0"), currentVersion = v("6.1.0"),
            )
        )
    }

    /** The reason this rule exists: a background bugfix has nothing to tell the user. */
    @Test
    fun `a patch release stays quiet`() {
        assertFalse(
            AppVersionUtils.shouldShowChangelog(
                installVersion = v("6.0.0"), lastChangelogVersion = v("6.1.0"), currentVersion = v("6.1.1"),
            )
        )
    }

    /**
     * Silence must not swallow the news. Someone who skipped 6.1.0 and lands on 6.1.1 has still not
     * read anything about 6.1, so the patch that is silent for everyone else speaks to them.
     */
    @Test
    fun `skipping the feature release and landing on its patch still announces`() {
        assertTrue(
            AppVersionUtils.shouldShowChangelog(
                installVersion = v("5.3.0"), lastChangelogVersion = v("6.0.0"), currentVersion = v("6.1.1"),
            )
        )
    }

    @Test
    fun `a fresh install announces nothing`() {
        assertFalse(
            AppVersionUtils.shouldShowChangelog(
                installVersion = v("6.1.1"), lastChangelogVersion = v("6.1.1"), currentVersion = v("6.1.1"),
            )
        )
    }

    @Test
    fun `a downgrade announces nothing`() {
        assertFalse(
            AppVersionUtils.shouldShowChangelog(
                installVersion = v("5.0.0"), lastChangelogVersion = v("6.1.0"), currentVersion = v("6.0.0"),
            )
        )
    }

    // --- the What's-new tour ------------------------------------------------------------------------

    @Test
    fun `only the tours newer than the last one seen are queued`() {
        val queued = AppVersionUtils.pendingTourVersions(
            installVersion = v("5.0.0"), lastWhatsNew = v("5.3.0"), current = v("6.1.0"), candidates = tours,
        )
        assertEquals(listOf(v("6.0.0"), v("6.1.0")), queued)
    }

    @Test
    fun `a patch release queues no tour of its own`() {
        val queued = AppVersionUtils.pendingTourVersions(
            installVersion = v("6.0.0"), lastWhatsNew = v("6.1.0"), current = v("6.1.1"), candidates = tours,
        )
        assertEquals(emptyList(), queued)
    }

    /**
     * The case that made the seeding necessary. Someone who installed 6.1.0 yesterday has an untouched
     * high-water mark — nothing is shown on a fresh install, so nothing ever wrote it — and the
     * *silent* 6.1.1 patch would greet them with all six tours in a row, the single most visible thing
     * a quiet patch could do.
     *
     * [AppVersionUtils.updateVersionOnInstallAndLastUse] writes the mark on launch, which is what turns
     * the first argument pair below into the second.
     */
    @Test
    fun `a fresh installer is not made to catch up on every past tour`() {
        val unseeded = AppVersionUtils.pendingTourVersions(
            installVersion = v("6.1.0"), lastWhatsNew = VersionName.DEFAULT, current = v("6.1.1"), candidates = tours,
        )
        assertEquals(tours, unseeded, "an unseeded mark really does replay everything")

        val seeded = AppVersionUtils.pendingTourVersions(
            installVersion = v("6.1.0"), lastWhatsNew = v("6.1.0"), current = v("6.1.1"), candidates = tours,
        )
        assertEquals(emptyList(), seeded)
    }

    // --- who counts as having missed nothing ---------------------------------------------------------

    @Test
    fun `a brand new install has missed nothing`() {
        assertTrue(AppVersionUtils.hasMissedNothing("6.1.1", VersionName.DEFAULT_RAW))
    }

    /**
     * The reason the seeding is applied on every launch and not only at install time: the users it has
     * to reach installed 6.1.0 *before* the code existed, so their mark is already sitting at the
     * default and nothing would ever come along to write it.
     */
    @Test
    fun `an install that has only ever run its own version has missed nothing`() {
        assertTrue(AppVersionUtils.hasMissedNothing("6.1.0", "6.1.0"))
    }

    /** Someone who has updated through releases may genuinely be owed a tour; leave their mark alone. */
    @Test
    fun `an install that has run later versions is left alone`() {
        assertFalse(AppVersionUtils.hasMissedNothing("5.0.0", "6.0.0"))
    }

    @Test
    fun `an unknown install version claims nothing`() {
        assertFalse(AppVersionUtils.hasMissedNothing(VersionName.DEFAULT_RAW, VersionName.DEFAULT_RAW))
    }

    @Test
    fun `a fresh install queues nothing at all`() {
        val queued = AppVersionUtils.pendingTourVersions(
            installVersion = v("6.1.1"), lastWhatsNew = VersionName.DEFAULT, current = v("6.1.1"), candidates = tours,
        )
        assertEquals(emptyList(), queued)
    }

    /** A tour must never run ahead of the build it describes. */
    @Test
    fun `tours newer than the running build are not queued`() {
        val queued = AppVersionUtils.pendingTourVersions(
            installVersion = v("5.0.0"), lastWhatsNew = v("5.1.0"), current = v("5.3.0"), candidates = tours,
        )
        assertEquals(listOf(v("5.2.0"), v("5.3.0")), queued)
    }

    // --- the version arithmetic itself ---------------------------------------------------------------

    @Test
    fun `a feature version drops the patch level`() {
        assertEquals(v("6.1.0"), v("6.1.1").featureVersion())
        assertEquals(v("6.1.0"), v("6.1.0").featureVersion())
        assertEquals(v("6.0.0"), v("6.0.9").featureVersion())
    }
}
