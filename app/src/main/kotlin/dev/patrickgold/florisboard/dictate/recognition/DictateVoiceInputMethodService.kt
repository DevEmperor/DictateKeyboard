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
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.ui.AudioReactiveCloudOrbView
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.onAccent

/**
 * A dedicated **voice input IME** (issue #67) — the mechanism AOSP-lineage keyboards (HeliBoard, OpenBoard,
 * …) use for their mic key: they `switchToShortcutIme()` to the selected voice input method. Once the user
 * enables Dictate voice input and picks it as their voice input method, tapping such a keyboard's mic hands
 * over to Dictate instead of Google.
 *
 * The input view is the same audio-reactive cloud orb + accent glow as the popup, on the app theme — tap
 * to send, auto-stops on silence. It records/transcribes via the shared [RecognitionSession], commits the
 * result straight into the field through the input connection, then switches back to the calling keyboard.
 *
 * It is buttonless right up until something goes wrong: a failure whose recording was kept stops the
 * hand-back and offers the same recovery the keyboard's error chip does (issue #409), because this is the
 * one surface where the user has no keyboard of ours to fall back to.
 */
class DictateVoiceInputMethodService : InputMethodService() {

    private val prefs by FlorisPreferenceStore

    private var orb: AudioReactiveCloudOrbView? = null
    private var statusView: TextView? = null
    private var hintView: TextView? = null
    private var actionsRow: LinearLayout? = null
    private var primaryAction: TextView? = null
    private var scope: CoroutineScope? = null
    private var session: RecognitionSession? = null
    private var committed = false

    override fun onCreateInputView(): View {
        val accentColor = runCatching { prefs.theme.accentColor.get() }
            .getOrDefault(ComposeColor(0xFF30B7E6))
        val accent = accentColor.toArgb()
        // The accent is the user's own colour, so the label on a surface filled with it is picked against
        // the fill rather than assumed to be white — the shared rule from lib/compose.
        val onAccent = accentColor.onAccent().toArgb()
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
        }.also { hintView = it }

        // Recovery buttons for a failed attempt (issue #409), hidden until there is one. The primary
        // button's label and job depend on which action the controller decided the failure offers, so
        // only its shape is built here.
        fun actionButton(filled: Boolean) = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(if (filled) onAccent else fgColor)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                if (filled) setColor(accent) else setStroke(dp(1), (fgColor and 0x00FFFFFF) or 0x40000000)
            }
        }

        val primary = actionButton(filled = true).also { primaryAction = it }
        val dismiss = actionButton(filled = false).apply {
            text = getString(R.string.dictate__action_dismiss)
            setOnClickListener {
                DictateController.discardRetainedAudio()
                leaveBackToCaller()
            }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(primary)
            addView(dismiss, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(10) })
        }.also { actionsRow = it }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bgColor)
            minimumHeight = dp(300)
            setPadding(dp(16), dp(20), dp(16), dp(40))
            // From Android 15 the IME window is edge to edge, so a 3- or 2-button navigation bar (~48 dp) sat on
            // "Tap to send" and the error buttons, which the fixed 40 dp only cleared for the gesture pill (#495).
            // The main keyboard pads with safeDrawingPadding(); this is the same for a plain view. Never less
            // than the old 40 dp, so a version that reports no inset looks as before.
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout(),
                )
                view.setPadding(
                    dp(16) + bars.left,
                    dp(20),
                    dp(16) + bars.right,
                    maxOf(dp(40), dp(20) + bars.bottom),
                )
                insets
            }
            addView(orbBox, LinearLayout.LayoutParams(dp(200), dp(200)))
            addView(status, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) })
            addView(hint, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) })
            addView(actions, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) })
            // Tap anywhere to stop + send (auto-stop on silence still applies). Only while we are still
            // capturing: once the request is out — or a failure is on screen with its own buttons — the
            // tap has nothing to send and must not reach the session.
            setOnClickListener {
                if (DictateController.state.value is DictateController.UiState.Recording) session?.stop()
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        committed = false
        showCapturing(R.string.dictate__voice_input_listening)
        val s = CoroutineScope(Dispatchers.Main + Job())
        scope = s
        session = RecognitionSession(applicationContext, host, sensitiveField = keepsOutOfHistory(info))
            .also { it.start() }
        s.launch { DictateController.audioLevel.collect { orb?.setLevel(it) } }
        s.launch { DictateController.state.collect { updateUi(it) } }
    }

    override fun onFinishInputView(finishing: Boolean) {
        endSession()
        super.onFinishInputView(finishing)
    }

    /**
     * Whether the caller's field is one the keyboard would keep out of the history (#383): a password or
     * PIN field, or an incognito one by the user's incognito setting. This is the one voice-input path
     * that sees its field, so it applies the keyboard's rule.
     */
    private fun keepsOutOfHistory(info: EditorInfo?): Boolean {
        if (info == null) return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        val incognito = when (prefs.suggestion.incognitoMode.get()) {
            IncognitoMode.FORCE_OFF -> false
            IncognitoMode.FORCE_ON -> true
            IncognitoMode.DYNAMIC_ON_OFF ->
                info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0 ||
                    prefs.suggestion.forceIncognitoModeFromDynamic.get()
        }
        return password || incognito
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

    /** Back to the plain listening/transcribing look: orb alive, hint visible, no recovery buttons. */
    private fun showCapturing(
        statusRes: Int,
        mode: AudioReactiveCloudOrbView.Mode = AudioReactiveCloudOrbView.Mode.LISTENING,
    ) {
        orb?.setMode(mode)
        statusView?.setText(statusRes)
        hintView?.visibility = View.VISIBLE
        actionsRow?.visibility = View.GONE
    }

    /**
     * Shows a failed attempt with the way out the keyboard's error chip offers for the same failure
     * (issue #409). Which one that is has already been decided by the controller — a retryable failure
     * offers the resend, one that resending cannot fix (too large, unsupported container) offers saving
     * the recording instead — so this reads [DictateController.UiState.Error.action] rather than judging
     * the error a second time and risking a different answer than the keyboard gives.
     *
     * Without this the failure was invisible: control went straight back to the calling keyboard, and the
     * recording — which *is* kept — was reachable only from the Dictate keyboard's history panel, which
     * is not where the user is standing at that moment.
     */
    private fun showFailure(error: DictateController.UiState.Error) {
        orb?.setMode(AudioReactiveCloudOrbView.Mode.ERROR)
        statusView?.text = error.message.ifBlank { getString(R.string.dictate__error_transcription_failed) }
        hintView?.visibility = View.GONE
        primaryAction?.apply {
            if (error.action == DictateController.ErrorAction.SAVE_AUDIO) {
                setText(R.string.dictate__action_save_audio)
                setOnClickListener {
                    DictateController.saveRetainedAudio(this@DictateVoiceInputMethodService)
                    leaveBackToCaller()
                }
            } else {
                setText(R.string.dictate__action_resend)
                setOnClickListener { retry() }
            }
        }
        actionsRow?.visibility = View.VISIBLE
    }

    /**
     * Sends the kept recording again. The finished session cannot be reused — it has already delivered
     * its terminal outcome and unregistered itself — so a fresh one carries the retry, and the view goes
     * straight to the transcribing look because there is nothing left to record.
     */
    private fun retry() {
        val next = RecognitionSession(applicationContext, host)
        if (!next.resend()) {
            // The audio went away under us (cleared, or the process was restarted): nothing to retry.
            leaveBackToCaller()
            return
        }
        session = next
        showCapturing(R.string.dictate__voice_input_transcribing, AudioReactiveCloudOrbView.Mode.THINKING)
    }

    private val host = object : RecognitionSession.Host {
        override fun onResults(text: String) {
            committed = true
            currentInputConnection?.commitText(text, 1)
            leaveBackToCaller()
        }

        override fun onError(code: Int) {
            // A failure with a kept recording stays on screen so it can be acted on; everything else
            // (no speech, a cancelled session) has nothing to offer and hands control back as before.
            val error = DictateController.state.value as? DictateController.UiState.Error
            val action = error?.action
            if (error != null && DictateController.hasRetainedAudio() &&
                (action == DictateController.ErrorAction.RESEND ||
                    action == DictateController.ErrorAction.SAVE_AUDIO)
            ) {
                showFailure(error)
            } else {
                leaveBackToCaller()
            }
        }
    }

    /** Hand control back to the keyboard that invoked us; fall back to just hiding if that fails. */
    private fun leaveBackToCaller() {
        if (!switchToPreviousInputMethod()) requestHideSelf(0)
    }
}
