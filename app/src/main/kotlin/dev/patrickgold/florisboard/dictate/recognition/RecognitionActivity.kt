/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.recognition

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.apptheme.FlorisAppTheme
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.ui.AudioReactiveCloudOrbView

/**
 * The classic voice-input popup — `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` (issue #67). Many keyboards
 * and apps launch this intent for their mic button (rather than binding [DictateRecognitionService]), so
 * this covers the broad case. Styled like Dictate itself: the audio-reactive cloud orb over an accent
 * glow, on the app's theme. It records + transcribes through the shared [RecognitionSession] and returns
 * the text via [RecognizerIntent.EXTRA_RESULTS]; the caller inserts it.
 */
class RecognitionActivity : ComponentActivity() {

    private var session: RecognitionSession? = null
    private var orb: AudioReactiveCloudOrbView? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cloud = AudioReactiveCloudOrbView(this)
        orb = cloud
        session = RecognitionSession(applicationContext, host).also { it.start() }

        setContent {
            val prefs by FlorisPreferenceStore
            // Read once — theme/accent don't change during the few seconds this popup is open (and this
            // avoids the jetpref collectAsState clashing by name with the runtime one used for the flows).
            val theme = remember { prefs.other.settingsTheme.get() }
            val accent = remember { prefs.theme.accentColor.get() }
            FlorisAppTheme(theme) {
                RecognitionPopup(cloud, accent)
            }
        }
    }

    @Composable
    private fun RecognitionPopup(cloud: AudioReactiveCloudOrbView, accent: Color) {
        val state by DictateController.state.collectAsState()
        val level by DictateController.audioLevel.collectAsState()

        val statusRes = when (state) {
            is DictateController.UiState.Transcribing, is DictateController.UiState.Rewording ->
                R.string.dictate__voice_input_transcribing
            else -> R.string.dictate__voice_input_listening
        }
        val orbMode = when (state) {
            is DictateController.UiState.Transcribing, is DictateController.UiState.Rewording ->
                AudioReactiveCloudOrbView.Mode.THINKING
            is DictateController.UiState.Error -> AudioReactiveCloudOrbView.Mode.ERROR
            else -> AudioReactiveCloudOrbView.Mode.LISTENING
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { cancelAndFinish() },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 16.dp,
                modifier = Modifier
                    .padding(32.dp)
                    // Tap the card/orb to send now; auto-stop on silence still applies.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { session?.stop() },
            ) {
                Column(
                    modifier = Modifier.padding(start = 32.dp, top = 28.dp, end = 32.dp, bottom = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
                        // Accent halo behind the orb.
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(100.dp))
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(accent.copy(alpha = 0.35f), Color.Transparent),
                                    ),
                                ),
                        )
                        AndroidView(
                            factory = { cloud },
                            modifier = Modifier.size(150.dp),
                            update = {
                                it.setMode(orbMode)
                                it.setLevel(level)
                            },
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(statusRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.dictate__voice_input_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    private val host = object : RecognitionSession.Host {
        override fun onResults(text: String) {
            if (finished) return
            finished = true
            setResult(
                RESULT_OK,
                Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text)),
            )
            finish()
        }

        override fun onError(code: Int) {
            if (finished) return
            finished = true
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun cancelAndFinish() {
        if (finished) return
        finished = true
        session?.cancel()
        setResult(RESULT_CANCELED)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        cancelAndFinish()
    }

    override fun onDestroy() {
        if (!finished) session?.cancel()
        session = null
        orb?.stop()
        orb = null
        super.onDestroy()
    }
}
