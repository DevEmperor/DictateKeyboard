/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.dictate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.data.stats.DictateStats
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.compose.SwitchRow
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import org.florisboard.lib.compose.stringRes

/**
 * Lifetime dictation statistics (issue #142): a visual dashboard rather than a preference list — a hero
 * "time saved" card, a grid of stat cards, a 7-day activity chart, a day-streak, the tracking-since date
 * and a reset action. Everything reads reactively from the [DictateStats] prefs and updates live.
 */
@Composable
fun DictateStatsScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__stats_title)
    previewFieldVisible = false
    iconSpaceReserved = false

    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()

    content {
        val dictations by prefs.dictate.statsDictations.collectAsState()
        val words by prefs.dictate.statsWords.collectAsState()
        val chars by prefs.dictate.statsChars.collectAsState()
        val spokenSeconds by prefs.dictate.statsSpokenSeconds.collectAsState()
        val rewordings by prefs.dictate.statsRewordings.collectAsState()
        val firstUse by prefs.dictate.statsFirstUseEpochMs.collectAsState()
        val streakCurrent by prefs.dictate.statsStreakCurrent.collectAsState()
        val streakBest by prefs.dictate.statsStreakBest.collectAsState()
        val daily by prefs.dictate.statsDaily.collectAsState()
        val milestonesEnabled by prefs.dictate.statsMilestonesEnabled.collectAsState()
        val context = LocalContext.current

        var showResetDialog by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (dictations <= 0L) {
                EmptyState()
                return@Column
            }

            // --- Hero: estimated time saved vs typing ---
            val savedSeconds = DictateStats.savedSeconds(words, spokenSeconds)
            val savedText = formatDuration(savedSeconds.toLong())
            HeroCard(savedText = savedText)

            // --- Stat grid (two columns) ---
            val avgWords = if (dictations > 0L) words.toDouble() / dictations else 0.0
            val paceWpm = if (spokenSeconds > 0L) words / (spokenSeconds / 60.0) else 0.0
            val cards = listOf(
                StatItem(Icons.Default.Mic, formatCount(dictations), stringRes(R.string.dictate__stats_dictations)),
                StatItem(Icons.Default.Notes, formatCount(words), stringRes(R.string.dictate__stats_words)),
                StatItem(Icons.Default.Timer, formatDuration(spokenSeconds), stringRes(R.string.dictate__stats_spoken)),
                StatItem(Icons.Default.Notes, formatDecimal(avgWords), stringRes(R.string.dictate__stats_avg_words)),
                StatItem(Icons.Default.Speed, "${formatDecimal(paceWpm)} ${stringRes(R.string.dictate__stats_pace_unit)}", stringRes(R.string.dictate__stats_pace)),
                StatItem(Icons.Default.Tag, formatCount(chars), stringRes(R.string.dictate__stats_chars)),
                StatItem(Icons.Default.AutoFixHigh, formatCount(rewordings), stringRes(R.string.dictate__stats_rewordings)),
                StatItem(
                    Icons.Default.LocalFireDepartment,
                    formatCount(streakCurrent.toLong()),
                    stringRes(R.string.dictate__stats_streak),
                    trailing = stringRes(R.string.dictate__stats_streak_best).format(streakBest),
                ),
            )
            cards.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    pair.forEach { item ->
                        StatCard(item, modifier = Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            // --- 7-day activity chart ---
            ActivityCard(bars = DictateStats.activity(daily))

            // --- Share (only here, with a Play Store link so friends can find Dictate) ---
            val shareBody = stringRes(R.string.dictate__stats_share_text)
                .format(savedText, formatCount(dictations), formatCount(words))
            FilledTonalButton(
                onClick = { shareStats(context, "$shareBody\n\n$PLAY_STORE_URL") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  " + stringRes(R.string.dictate__stats_share))
            }

            // --- Milestone celebrations toggle (opt-out, lives on this page) ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                SwitchRow(
                    title = stringRes(R.string.dictate__stats_milestones_title),
                    summary = stringRes(R.string.dictate__stats_milestones_summary),
                    icon = Icons.Default.Celebration,
                    checked = milestonesEnabled,
                    onCheckedChange = { scope.launch { prefs.dictate.statsMilestonesEnabled.set(it) } },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            // --- Tracking-since + reset ---
            if (firstUse > 0L) {
                Text(
                    text = stringRes(R.string.dictate__stats_since).format(formatDate(firstUse)),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            TextButton(
                onClick = { showResetDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringRes(R.string.dictate__stats_reset), color = MaterialTheme.colorScheme.error)
            }
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text(stringRes(R.string.dictate__stats_reset_confirm_title)) },
                text = { Text(stringRes(R.string.dictate__stats_reset_confirm_msg)) },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch { DictateStats.reset(prefs) }
                        showResetDialog = false
                    }) {
                        Text(stringRes(R.string.dictate__stats_reset), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) {
                        Text(stringRes(android.R.string.cancel))
                    }
                },
            )
        }
    }
}

private data class StatItem(
    val icon: ImageVector,
    val value: String,
    val label: String,
    /** Optional small text shown inline after [value] on the same line (e.g. the best-streak), so a card
     * with extra info stays the same height as the others. */
    val trailing: String? = null,
)

@Composable
private fun HeroCard(savedText: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    stringRes(R.string.dictate__stats_time_saved),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(savedText, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(
                stringRes(R.string.dictate__stats_time_saved_sub),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatCard(item: StatItem, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                item.icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(item.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (item.trailing != null) {
                    Text(
                        "  ${item.trailing}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            Text(
                item.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActivityCard(bars: List<DictateStats.DayBar>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringRes(R.string.dictate__stats_activity),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val max = bars.maxOfOrNull { it.words }?.coerceAtLeast(1L) ?: 1L
            Row(
                modifier = Modifier.fillMaxWidth().height(84.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                bars.forEach { bar ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val frac = bar.words.toFloat() / max
                        val barHeight = if (bar.words <= 0L) 3.dp else (BAR_MAX_HEIGHT * frac).coerceAtLeast(8.dp)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(barHeight)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(
                                    if (bar.words > 0L) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                ),
                        )
                        Text(
                            weekdayNarrow(bar.epochDay),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            stringRes(R.string.dictate__stats_empty),
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// --- helpers --------------------------------------------------------------------------------------

private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=net.devemperor.dictate"
private val BAR_MAX_HEIGHT = 64.dp

private fun shareStats(context: android.content.Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun formatCount(value: Long): String = NumberFormat.getIntegerInstance().format(value)

private fun formatDecimal(value: Double): String =
    NumberFormat.getInstance().apply { maximumFractionDigits = if (value < 100) 1 else 0 }.format(value)

/** Compact duration: `3h 12m`, `12m`, `45s`. */
private fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> if (m > 0) "${h}h ${m}m" else "${h}h"
        m > 0 -> "${m}m"
        else -> "${sec}s"
    }
}

private fun formatDate(epochMs: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))

private fun weekdayNarrow(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
