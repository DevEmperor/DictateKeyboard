/*
 * Copyright (C) 2020-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.lib.util

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceModel

object AppVersionUtils {
    private fun getRawVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName!!
        } catch (e: Exception) {
            "undefined"
        }
    }

    /**
     * Whether this launch should announce the update — the changelog dialog in the app and the
     * "Dictate was updated" nudge in the Smartbar, which share this one gate.
     *
     * Only a *feature* release announces itself. A patch bump (x.y.Z) is a fix that was already
     * described where it was reported; a user who never noticed the bug has nothing to read, and
     * interrupting them to say a background repair happened is worse than saying nothing. Comparing
     * [VersionName.featureVersion] rather than the full version is what keeps 6.1.0 → 6.1.1 silent
     * while 6.1.x → 6.2.0 still speaks up. Note the mark itself stays untouched by a silent patch, so
     * the next feature release still lists everything since the last release the user actually read.
     */
    fun shouldShowChangelog(context: Context, prefs: FlorisPreferenceModel): Boolean {
        val installVersion =
            VersionName.fromString(prefs.internal.versionOnInstall.get()) ?: VersionName.DEFAULT
        val lastChangelogVersion =
            VersionName.fromString(prefs.internal.versionLastChangelog.get()) ?: VersionName.DEFAULT
        val currentVersion =
            VersionName.fromString(getRawVersionName(context)) ?: VersionName.DEFAULT

        return shouldShowChangelog(installVersion, lastChangelogVersion, currentVersion)
    }

    /** The rule itself, free of [Context] and preferences so it can be unit-tested. */
    internal fun shouldShowChangelog(
        installVersion: VersionName,
        lastChangelogVersion: VersionName,
        currentVersion: VersionName,
    ): Boolean {
        return lastChangelogVersion.featureVersion() < currentVersion.featureVersion() &&
            installVersion != currentVersion
    }

    /**
     * Records the version the app was first installed at, the version last used, and — for someone
     * who has only ever run the version they installed — how much of the What's-new material they can
     * be considered to have already been offered.
     *
     * Both "already shown" marks start at 0.0.0, which reads as *has seen no release ever*. For a user
     * who installed at 6.1.0 that is false in a way the next update pays for: nothing is shown on a
     * fresh install (there is nothing to catch up on), so the mark is never written, and the first
     * update afterwards replays every tour in the registry and a changelog listing every release since
     * 5.0. A patch meant to install unnoticed is exactly where that would be unmissable.
     *
     * Reading `versionLastUse` *before* it is overwritten is what makes this safe to apply to installs
     * that already exist: it distinguishes someone who has only ever run their install version — who
     * has genuinely missed nothing — from someone who has updated through releases without finishing a
     * tour, who may really be owed one and is left alone.
     */
    suspend fun updateVersionOnInstallAndLastUse(context: Context, prefs: FlorisPreferenceModel) {
        val current = getRawVersionName(context)
        val lastUse = prefs.internal.versionLastUse.get()
        if (prefs.internal.versionOnInstall.get() == VersionName.DEFAULT_RAW) {
            prefs.internal.versionOnInstall.set(current)
        }
        val installVersion = prefs.internal.versionOnInstall.get()
        if (hasMissedNothing(installVersion, lastUse)) {
            if (prefs.internal.versionLastWhatsNew.get() == VersionName.DEFAULT_RAW) {
                prefs.internal.versionLastWhatsNew.set(installVersion)
            }
            if (prefs.internal.versionLastChangelog.get() == VersionName.DEFAULT_RAW) {
                prefs.internal.versionLastChangelog.set(installVersion)
            }
        }
        prefs.internal.versionLastUse.set(current)
    }

    /**
     * Whether this install has never run a version other than the one it was installed at — in which
     * case an unwritten "already shown" mark means *nothing was ever due*, not *everything is owed*.
     *
     * [lastUseRaw] must be read before this launch overwrites it.
     */
    internal fun hasMissedNothing(installVersionRaw: String, lastUseRaw: String): Boolean =
        installVersionRaw != VersionName.DEFAULT_RAW &&
            (lastUseRaw == VersionName.DEFAULT_RAW || lastUseRaw == installVersionRaw)

    suspend fun updateVersionLastChangelog(context: Context, prefs: FlorisPreferenceModel) {
        prefs.internal.versionLastChangelog.set(getRawVersionName(context))
    }

    /** The parsed version of the currently installed build, or [VersionName.DEFAULT] when unparseable. */
    fun currentVersion(context: Context): VersionName =
        VersionName.fromString(getRawVersionName(context)) ?: VersionName.DEFAULT

    /** Remembers that the user has seen the "What's new" tour for the current version. */
    suspend fun updateVersionLastWhatsNew(context: Context, prefs: FlorisPreferenceModel) {
        prefs.internal.versionLastWhatsNew.set(getRawVersionName(context))
    }

    /**
     * Remembers that the user has seen every "What's new" tour up to and including [version]. Used when
     * more than one tour is queued (a user who skipped releases): after finishing an older tour we advance
     * the high-water mark to it, so quitting mid-queue still resumes at the next unseen tour on relaunch.
     */
    suspend fun markWhatsNewSeen(context: Context, prefs: FlorisPreferenceModel, version: VersionName) {
        prefs.internal.versionLastWhatsNew.set(version.toString())
    }

    /**
     * The ascending list of tour versions from [candidates] that should auto-show now: only on a real
     * update (not a fresh install), only those newer than the last-seen high-water mark, and only those the
     * current build has actually reached. Empty means nothing auto-shows (fall back to the changelog).
     */
    fun pendingTourVersions(
        context: Context,
        prefs: FlorisPreferenceModel,
        candidates: List<VersionName>,
    ): List<VersionName> {
        val installVersion =
            VersionName.fromString(prefs.internal.versionOnInstall.get()) ?: VersionName.DEFAULT
        val lastWhatsNew =
            VersionName.fromString(prefs.internal.versionLastWhatsNew.get()) ?: VersionName.DEFAULT
        return pendingTourVersions(installVersion, lastWhatsNew, currentVersion(context), candidates)
    }

    /** The rule itself, free of [Context] and preferences so it can be unit-tested. */
    internal fun pendingTourVersions(
        installVersion: VersionName,
        lastWhatsNew: VersionName,
        current: VersionName,
        candidates: List<VersionName>,
    ): List<VersionName> {
        if (installVersion == current) return emptyList() // fresh install → setup flow, not what's-new
        return candidates
            .filter { it > lastWhatsNew && !(current < it) }
            .sortedWith { a, b -> a.compareTo(b) }
    }
}

data class VersionName(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val extraName: String? = null,
    val extraValue: Int? = null
) {
    companion object {
        val DEFAULT: VersionName = VersionName(0, 0, 0)
        const val DEFAULT_RAW: String = "0.0.0"

        /**
         * [raw] with the patch level dropped ("6.1.1" → "6.1.0"), for preferences that remember "the
         * user has already been shown this once per release". Keying those on the full version would
         * make a patch release re-show them, which is the opposite of what a patch is for.
         *
         * A version name that cannot be parsed (debug builds carry a suffix) is returned unchanged, so
         * it simply keeps a bucket of its own.
         */
        fun featureVersionOf(raw: String): String = fromString(raw)?.featureVersion()?.toString() ?: raw

        fun fromString(raw: String): VersionName? {
            if (raw.matches("""[0-9]+[.][0-9]+[.][0-9]+""".toRegex())) {
                val list = raw.split(".").map { it.toInt() }
                if (list.size == 3) {
                    return VersionName(list[0], list[1], list[2])
                }
            } else if (raw.matches("""[0-9]+[.][0-9]+[.][0-9]+[-][0-9]+""".toRegex())) {
                val list = raw.split(".").map { it.toInt() }
                if (list.size == 4) {
                    return VersionName(list[0], list[1], list[2], null, list[3])
                }
            } else if (raw.matches("""[0-9]+[.][0-9]+[.][0-9]+[-][a-zA-Z]+""".toRegex())) {
                val list = raw.split(".")
                if (list.size == 4) {
                    return VersionName(
                        list[0].toInt(), list[1].toInt(), list[2].toInt(),
                        list[3], null
                    )
                }
            } else if (raw.matches("""[0-9]+[.][0-9]+[.][0-9]+[-][a-zA-Z]+[0-9]+""".toRegex())) {
                val list = raw.split(".")
                if (list.size == 4) {
                    val extraName = list[3].split("""[0-9]+""".toRegex())[0]
                    val extraValue = list[3].split("""[a-zA-Z]+""".toRegex())[1].toInt()
                    return VersionName(
                        list[0].toInt(), list[1].toInt(), list[2].toInt(),
                        extraName, extraValue
                    )
                }
            }
            return null
        }
    }

    /**
     * This version with the patch level (and any pre-release suffix) dropped — 6.1.1 and 6.1.0 both
     * become 6.1.0. Two builds that agree here carry the same features, which is what decides whether
     * an update has anything to announce; see [AppVersionUtils.shouldShowChangelog].
     */
    fun featureVersion(): VersionName = VersionName(major, minor, 0)

    override fun toString(): String {
        val mmp = "$major.$minor.$patch"
        return if (extraName != null || extraValue != null) {
            val extraName = extraName ?: ""
            val extraValue = extraValue?.toString() ?: ""
            "$mmp.$extraName$extraValue"
        } else {
            mmp
        }
    }

    operator fun compareTo(other: VersionName): Int {
        if (major != other.major) {
            return major.compareTo(other.major)
        } else if (minor != other.minor) {
            return minor.compareTo(other.minor)
        } else if (patch != other.patch) {
            return patch.compareTo(other.patch)
        } else if (extraValue != null && other.extraValue != null) {
            if (extraValue != other.extraValue) {
                return extraValue.compareTo(other.extraValue)
            }
        }
        return 0
    }
}
