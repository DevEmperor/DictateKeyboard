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

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import kotlinx.coroutines.delay
import org.florisboard.lib.compose.stringRes

/**
 * Playing back the audio behind a transcript, in one place.
 *
 * Both the history's detail dialog and the import screen (#301) let you listen while reading. The
 * part worth sharing is not the row of buttons but the [MediaPlayer] itself: it has to be paused and
 * resumed without losing its place, seekable, released when the screen goes away, and polled while
 * it runs — a second hand-rolled copy of that is a second place to leak a player.
 *
 * Pause is not stop. The first version released the player on every tap, which meant "pause" threw
 * away the position and the duration with it; this one keeps the player prepared and only lets go on
 * [stop], which the composition calls when it leaves.
 */
class AudioPlayerState internal constructor(
    private val path: String?,
    private val onMissing: () -> Unit,
) {
    private var player: MediaPlayer? = null

    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0)
        private set
    var durationMs by mutableStateOf(0)
        private set

    /** Where the bar sits: the playhead, or the user's finger while they are dragging it. */
    var scrubbing by mutableStateOf<Float?>(null)
        private set

    /** 0..1 for a progress ring or bar; the drag position wins while there is one. */
    val progress: Float
        get() = scrubbing ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /**
     * The time to put on screen: while the bar is being dragged, **where it would resume on
     * release** rather than where the playhead still happens to be. A clock that stands still under
     * a moving finger is worse than no clock — it says the drag is doing nothing.
     */
    val displayedMs: Int
        get() = scrubbing?.let { (durationMs * it).toInt() } ?: positionMs

    /** True once the file has been opened, which is what makes a duration available to show. */
    val ready: Boolean
        get() = player != null

    /**
     * Opens the file without starting it, so the length and the bar are right before the first tap.
     * Returns false when the audio cannot be opened at all.
     */
    private fun prepare(): Boolean {
        player?.let { return true }
        val source = path ?: return false
        val opened = runCatching {
            MediaPlayer().apply {
                setDataSource(source)
                prepare()
            }
        }.getOrNull() ?: return false
        opened.setOnCompletionListener {
            playing = false
            // Back to the start, the way every audio player behaves at the end of a track.
            runCatching { opened.seekTo(0) }
            positionMs = 0
        }
        player = opened
        durationMs = runCatching { opened.duration }.getOrDefault(0)
        return true
    }

    /** Opens the file eagerly so the duration shows before anything is played. */
    internal fun preload() {
        if (player == null && path != null) prepare()
    }

    fun toggle() {
        if (playing) {
            pause()
            return
        }
        if (!prepare()) {
            // Pruned, never kept, or a format this device will not open. Saying so beats a button
            // that does nothing.
            onMissing()
            return
        }
        runCatching { player?.start() }
        playing = player?.isPlaying ?: false
    }

    fun pause() {
        runCatching { player?.pause() }
        playing = false
        poll()
    }

    /** Jumps to [fraction] of the track (0..1), whether or not it is currently playing. */
    fun seekTo(fraction: Float) {
        if (!prepare()) return
        val target = (durationMs * fraction.coerceIn(0f, 1f)).toInt()
        runCatching { player?.seekTo(target) }
        positionMs = target
    }

    /** The bar follows the finger while it is down; the playhead takes over again on release. */
    fun scrubTo(fraction: Float) {
        scrubbing = fraction.coerceIn(0f, 1f)
    }

    fun scrubFinished() {
        scrubbing?.let { seekTo(it) }
        scrubbing = null
    }

    internal fun poll() {
        val p = player ?: return
        positionMs = runCatching { p.currentPosition }.getOrDefault(positionMs)
        if (durationMs <= 0) durationMs = runCatching { p.duration }.getOrDefault(0)
    }

    /** Releases the player. Called when the composition leaves — pausing does not come through here. */
    fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        playing = false
        positionMs = 0
    }
}

/** A player bound to this composition: released when it leaves, and rebuilt when [path] changes. */
@Composable
fun rememberAudioPlayer(
    path: String?,
    // Opening the file up front costs one decode and buys a duration to display; a screen that only
    // needs a play button can skip it.
    preload: Boolean = false,
    onMissing: () -> Unit = {},
): AudioPlayerState {
    val state = remember(path) { AudioPlayerState(path, onMissing) }
    DisposableEffect(state) { onDispose { state.stop() } }
    LaunchedEffect(state) { if (preload) state.preload() }
    LaunchedEffect(state.playing) {
        while (state.playing) {
            state.poll()
            delay(120)
        }
    }
    return state
}

/** mm:ss, the only format a voice message ever needs. */
fun formatPlaybackTime(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * A plain audio player: play/pause, a bar you can drag, and the position against the length.
 *
 * Takes the state rather than making it, because the caller may need it to outlive this row. The
 * import screen swaps its whole layout between portrait and landscape, and a player created in here
 * would be disposed — and released mid-playback — every time the phone turns.
 *
 * The history dialog builds its own row instead: that one also carries export, share and pin.
 */
@Composable
fun AudioPlaybackRow(player: AudioPlayerState, modifier: Modifier = Modifier) {
    Row(
        // Room above and below: the row sits between the header and the transcript, and without it
        // the slider's touch target runs straight into both.
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = { player.toggle() }, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringRes(R.string.dictate__history_play),
                modifier = Modifier.size(26.dp),
            )
        }
        Slider(
            modifier = Modifier.weight(1f),
            value = player.progress,
            onValueChange = { player.scrubTo(it) },
            onValueChangeFinished = { player.scrubFinished() },
            enabled = player.ready,
        )
        Text(
            text = formatPlaybackTime(player.displayedMs) + " / " + formatPlaybackTime(player.durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
