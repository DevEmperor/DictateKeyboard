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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
 * part worth sharing is not the row of buttons but the [MediaPlayer] itself: it has to be stopped on
 * completion, released when the screen goes away, and polled while it runs — and a second
 * hand-rolled copy of that is a second place to leak a player.
 */
class AudioPlayerState internal constructor(
    private val path: String?,
    private val onMissing: () -> Unit,
) {
    private var player: MediaPlayer? = null

    var playing by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0f)
        private set

    internal fun poll() {
        val p = player ?: return
        val dur = runCatching { p.duration }.getOrDefault(0)
        val pos = runCatching { p.currentPosition }.getOrDefault(0)
        progress = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    }

    internal fun clearProgress() {
        if (!playing) progress = 0f
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        playing = false
    }

    fun toggle() {
        if (playing) {
            stop()
            return
        }
        val source = path ?: return onMissing()
        val started = runCatching {
            MediaPlayer().apply {
                setDataSource(source)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
        }.getOrNull()
        if (started == null) {
            // Pruned, never kept, or a format this device will not open. Saying so beats a button
            // that does nothing.
            onMissing()
            return
        }
        player = started
        playing = true
    }
}

/** A player bound to this composition: released when it leaves, and rebuilt when [path] changes. */
@Composable
fun rememberAudioPlayer(path: String?, onMissing: () -> Unit = {}): AudioPlayerState {
    val state = remember(path) { AudioPlayerState(path, onMissing) }
    DisposableEffect(state) { onDispose { state.stop() } }
    LaunchedEffect(state.playing) {
        while (state.playing) {
            state.poll()
            delay(120)
        }
        state.clearProgress()
    }
    return state
}

/**
 * Play/stop with a progress ring and a bar — the whole playback control for a screen that has
 * nothing else to put in the row. The history dialog builds its own around export, share and pin.
 */
@Composable
fun AudioPlaybackRow(path: String) {
    val context = LocalContext.current
    val player = rememberAudioPlayer(path) {
        Toast.makeText(context, R.string.dictate__history_audio_missing, Toast.LENGTH_SHORT).show()
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (player.playing) {
                CircularProgressIndicator(
                    progress = { player.progress },
                    modifier = Modifier.size(40.dp),
                    strokeWidth = 2.dp,
                )
            }
            IconButton(onClick = { player.toggle() }, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = if (player.playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = stringRes(R.string.dictate__history_play),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        if (player.playing) {
            LinearProgressIndicator(progress = { player.progress }, modifier = Modifier.weight(1f))
        } else {
            Text(
                modifier = Modifier.weight(1f),
                text = stringRes(R.string.dictate__history_play),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
