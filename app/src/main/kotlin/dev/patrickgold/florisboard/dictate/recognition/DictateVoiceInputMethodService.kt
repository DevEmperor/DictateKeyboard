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

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.ui.AudioReactiveCloudOrbView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * A dedicated **voice input IME** (issue #67) — the mechanism AOSP-lineage keyboards (HeliBoard, OpenBoard,
 * …) use for their mic key: they `switchToShortcutIme()` to the selected voice input method. Once the user
 * enables Dictate voice input and picks it as their voice input method, tapping such a keyboard's mic hands
 * over to Dictate instead of Google.
 *
 * The input view is the same audio-reactive cloud orb + accent glow as the popup, on the app theme — no
 * buttons, tap to send, auto-stops on silence. It records/transcribes via the shared [RecognitionSession],
 * commits the result straight into the field through the input connection, then switches back to the
 * calling keyboard.
 */
class DictateVoiceInputMethodService : InputMethodService() {

    private val prefs by FlorisPreferenceStore

    private var orb: AudioReactiveCloudOrbView? = null
    private var statusView: TextView? = null
    private var scope: CoroutineScope? = null
    private var session: RecognitionSession? = null
    private var committed = false

    override fun onCreateInputView(): View {
        val accent = runCatching { prefs.theme.accentColor.get().toArgb() }
            .getOrDefault(0xFF30B7E6.toInt())
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (dark) 0xFF1B1B1F.toInt() else 0xFFF5F5F8.toInt()
        val fgColor = if (dark) 0xFFECECEC.toInt() else 0xFF1A1A1A.toInt()

        fun dp(v: Int) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
        ).toInt()

        val orbView = AudioReactiveCloudOrbView(this).also { orb = it }
        orbView.setMode(AudioReactiveCloudOrbView.Mode.LISTENING)

        val glow = View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(105).toFloat()
                colors = intArrayOf((accent and 0x00FFFFFF) or 0x59000000, Color.TRANSPARENT)
            }
        }

        val orbBox = FrameLayout(this).apply {
            addView(glow, FrameLayout.LayoutParams(dp(200), dp(200), Gravity.CENTER))
            addView(orbView, FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER))
        }

        val status = TextView(this).apply {
            text = getString(R.string.dictate__voice_input_listening)
            setTextColor(fgColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }.also { statusView = it }

        val hint = TextView(this).apply {
            text = getString(R.string.dictate__voice_input_hint)
            setTextColor((fgColor and 0x00FFFFFF) or 0x80000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bgColor)
            minimumHeight = dp(300)
            setPadding(dp(16), dp(20), dp(16), dp(40))
            addView(orbBox, LinearLayout.LayoutParams(dp(200), dp(200)))
            addView(status, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) })
            addView(hint, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) })
            // Tap anywhere to stop + send (auto-stop on silence still applies).
            setOnClickListener { session?.stop() }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        committed = false
        orb?.setMode(AudioReactiveCloudOrbView.Mode.LISTENING)
        statusView?.setText(R.string.dictate__voice_input_listening)
        val s = CoroutineScope(Dispatchers.Main + Job())
        scope = s
        session = RecognitionSession(applicationContext, host).also { it.start() }
        s.launch { DictateController.audioLevel.collect { orb?.setLevel(it) } }
        s.launch { DictateController.state.collect { updateUi(it) } }
    }

    override fun onFinishInputView(finishing: Boolean) {
        endSession()
        super.onFinishInputView(finishing)
    }

    override fun onDestroy() {
        endSession()
        super.onDestroy()
    }

    private fun endSession() {
        scope?.cancel()
        scope = null
        if (!committed) session?.cancel()
        session = null
        orb?.stop()
    }

    private fun updateUi(state: DictateController.UiState) {
        when (state) {
            is DictateController.UiState.Transcribing, is DictateController.UiState.Rewording -> {
                orb?.setMode(AudioReactiveCloudOrbView.Mode.THINKING)
                statusView?.setText(R.string.dictate__voice_input_transcribing)
            }
            is DictateController.UiState.Error -> orb?.setMode(AudioReactiveCloudOrbView.Mode.ERROR)
            else -> orb?.setMode(AudioReactiveCloudOrbView.Mode.LISTENING)
        }
    }

    private val host = object : RecognitionSession.Host {
        override fun onResults(text: String) {
            committed = true
            currentInputConnection?.commitText(text, 1)
            leaveBackToCaller()
        }

        override fun onError(code: Int) {
            leaveBackToCaller()
        }
    }

    /** Hand control back to the keyboard that invoked us; fall back to just hiding if that fails. */
    private fun leaveBackToCaller() {
        if (!switchToPreviousInputMethod()) requestHideSelf(0)
    }
}
