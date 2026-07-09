/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.stats

import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import java.time.LocalDate

/**
 * Lifetime dictation statistics (issue #142). All state lives in dedicated, never-auto-reset JetPref
 * prefs (see [FlorisPreferenceModel.Dictate]); this object is the single write path so the keyboard and
 * the floating button feed the same counters. Reads happen reactively in `DictateStatsScreen`.
 */
object DictateStats {

    /** Days shown by the activity chart / kept in the rolling per-day store. */
    const val DAILY_WINDOW_DAYS = 7

    /** Average phone typing speed (words per minute) — the baseline for the "time saved" estimate. */
    const val TYPING_WPM = 40.0

    /** A single day's word count, for the activity chart. */
    data class DayBar(val epochDay: Long, val words: Long)

    /** A once-only celebration: either a saved-time (minutes) or a dictation-count threshold. */
    data class Milestone(val kind: Kind, val value: Long) {
        enum class Kind { TIME_MINUTES, DICTATIONS }
    }

    // Milestones grow forever (no fixed list): saved time celebrates 1h, then every 5h (5h, 10h, 15h…);
    // dictation count celebrates every 100 (100, 200, 300…). No streak milestones by design.
    private const val TIME_FIRST_MIN = 60L   // 1h
    private const val TIME_STEP_MIN = 300L   // 5h
    private const val COUNT_STEP = 100L

    /** Estimated seconds saved versus typing the same words on a keyboard. */
    fun savedSeconds(words: Long, spokenSeconds: Long): Double =
        (words / TYPING_WPM * 60.0 - spokenSeconds).coerceAtLeast(0.0)

    /**
     * Records one successful dictation: bumps the counters, stamps first-use, extends the day streak and
     * folds today's word count into the rolling 7-day store. Blank output is ignored (nothing was typed).
     */
    suspend fun recordDictation(
        prefs: FlorisPreferenceModel,
        text: String,
        spokenSeconds: Long,
        nowMs: Long = System.currentTimeMillis(),
        today: Long = LocalDate.now().toEpochDay(),
    ) {
        if (text.isBlank()) return
        val d = prefs.dictate
        val words = wordCount(text).toLong()
        d.statsDictations.set(d.statsDictations.get() + 1)
        d.statsWords.set(d.statsWords.get() + words)
        d.statsChars.set(d.statsChars.get() + text.length)
        d.statsSpokenSeconds.set(d.statsSpokenSeconds.get() + spokenSeconds.coerceAtLeast(0L))
        if (d.statsFirstUseEpochMs.get() == 0L) d.statsFirstUseEpochMs.set(nowMs)
        updateStreak(d, today)
        addDailyWords(d, today, words)
        checkMilestones(d)
    }

    /**
     * Flags a newly-crossed saved-time or dictation-count milestone so the app can celebrate it once on
     * next open (never on the keyboard). No-op when the user disabled milestone celebrations. Streaks are
     * intentionally excluded.
     */
    private suspend fun checkMilestones(d: FlorisPreferenceModel.Dictate) {
        if (!d.statsMilestonesEnabled.get()) return
        val savedMin = (savedSeconds(d.statsWords.get(), d.statsSpokenSeconds.get()) / 60.0).toLong()
        // Highest reached threshold: 1h, then the largest multiple of 5h at/below the saved time.
        val timeMs = when {
            savedMin >= TIME_STEP_MIN -> (savedMin / TIME_STEP_MIN) * TIME_STEP_MIN
            savedMin >= TIME_FIRST_MIN -> TIME_FIRST_MIN
            else -> 0L
        }
        val countMs = (d.statsDictations.get() / COUNT_STEP) * COUNT_STEP
        if (timeMs > d.statsMilestoneTimeShown.get()) {
            d.statsMilestoneTimeShown.set(timeMs)
            d.statsPendingMilestone.set("time:$timeMs")
        }
        // Count is applied last so it wins the (rare) case where both cross on the same dictation.
        if (countMs > d.statsMilestoneCountShown.get()) {
            d.statsMilestoneCountShown.set(countMs)
            d.statsPendingMilestone.set("count:$countMs")
        }
    }

    /** Reads and clears the pending milestone (returns null when none / celebrations are off). */
    suspend fun consumePendingMilestone(prefs: FlorisPreferenceModel): Milestone? {
        val d = prefs.dictate
        val raw = d.statsPendingMilestone.get()
        if (raw.isNotEmpty()) d.statsPendingMilestone.set("")
        if (!d.statsMilestonesEnabled.get() || raw.isBlank()) return null
        val parts = raw.split(':')
        val kind = when (parts.getOrNull(0)) {
            "time" -> Milestone.Kind.TIME_MINUTES
            "count" -> Milestone.Kind.DICTATIONS
            else -> return null
        }
        val value = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return Milestone(kind, value)
    }

    /** Records that one AI rewording/prompt pass ran. */
    suspend fun recordRewording(prefs: FlorisPreferenceModel) {
        val d = prefs.dictate
        d.statsRewordings.set(d.statsRewordings.get() + 1)
    }

    /** Clears every statistic back to its default (user-triggered from the stats screen). */
    suspend fun reset(prefs: FlorisPreferenceModel) {
        val d = prefs.dictate
        d.statsDictations.set(0L)
        d.statsWords.set(0L)
        d.statsChars.set(0L)
        d.statsSpokenSeconds.set(0L)
        d.statsRewordings.set(0L)
        d.statsFirstUseEpochMs.set(0L)
        d.statsLastDayEpoch.set(0L)
        d.statsStreakCurrent.set(0)
        d.statsStreakBest.set(0)
        d.statsDaily.set("")
        // Clear milestone progress so they can be celebrated again, but keep the user's on/off choice.
        d.statsMilestoneTimeShown.set(0L)
        d.statsMilestoneCountShown.set(0L)
        d.statsPendingMilestone.set("")
    }

    /** Whitespace-delimited word count; CJK text (no spaces) collapses to a single token, which is fine. */
    fun wordCount(text: String): Int {
        var count = 0
        var inWord = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                count++
                inWord = true
            }
        }
        return count
    }

    /**
     * Words per day for the last [DAILY_WINDOW_DAYS] days, oldest → newest and aligned to [today] so the
     * chart always shows a fixed, gap-filled window (missing days = 0).
     */
    fun activity(raw: String, today: Long = LocalDate.now().toEpochDay()): List<DayBar> {
        val map = parseDaily(raw)
        return (0 until DAILY_WINDOW_DAYS).map { i ->
            val day = today - (DAILY_WINDOW_DAYS - 1 - i)
            DayBar(day, map[day] ?: 0L)
        }
    }

    private suspend fun updateStreak(d: FlorisPreferenceModel.Dictate, today: Long) {
        val last = d.statsLastDayEpoch.get()
        val current = when {
            last == today -> d.statsStreakCurrent.get().coerceAtLeast(1)
            last == today - 1L -> d.statsStreakCurrent.get() + 1
            else -> 1
        }
        d.statsStreakCurrent.set(current)
        if (current > d.statsStreakBest.get()) d.statsStreakBest.set(current)
        d.statsLastDayEpoch.set(today)
    }

    private suspend fun addDailyWords(d: FlorisPreferenceModel.Dictate, today: Long, words: Long) {
        val map = parseDaily(d.statsDaily.get()).toMutableMap()
        map[today] = (map[today] ?: 0L) + words
        val cutoff = today - (DAILY_WINDOW_DAYS - 1)
        d.statsDaily.set(serializeDaily(map.filterKeys { it >= cutoff }))
    }

    private fun parseDaily(raw: String): Map<Long, Long> {
        if (raw.isBlank()) return emptyMap()
        val out = mutableMapOf<Long, Long>()
        var entryStart = 0
        while (entryStart <= raw.length) {
            val entryEnd = raw.indexOf(';', startIndex = entryStart).let { if (it >= 0) it else raw.length }
            val separator = raw.indexOf(':', startIndex = entryStart).takeIf { it in entryStart + 1 until entryEnd }
            if (separator != null) {
                val day = parseLongOrNull(raw, entryStart, separator)
                val words = parseLongOrNull(raw, separator + 1, entryEnd)
                if (day != null && words != null) out[day] = words
            }
            if (entryEnd == raw.length) break
            entryStart = entryEnd + 1
        }
        return out
    }

    private fun parseLongOrNull(raw: String, start: Int, end: Int): Long? {
        if (start >= end) return null
        var i = start
        val negative = raw[i] == '-'
        if (negative) {
            i++
            if (i == end) return null
        }
        var value = 0L
        while (i < end) {
            val digit = raw[i] - '0'
            if (digit !in 0..9) return null
            if (value > (Long.MAX_VALUE - digit) / 10L) return null
            value = value * 10L + digit
            i++
        }
        return if (negative) -value else value
    }

    private fun serializeDaily(map: Map<Long, Long>): String =
        map.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value}" }
}
