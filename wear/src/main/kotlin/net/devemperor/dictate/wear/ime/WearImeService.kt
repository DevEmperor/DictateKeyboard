/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.devemperor.dictate.wear.ime

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.devemperor.dictate.wear.R
import net.devemperor.dictate.wear.audio.WearAudioRecorder
import net.devemperor.dictate.wear.sync.WearSettingsStore
import net.devemperor.dictate.wear.sync.WearSyncClient
import net.devemperor.dictate.wear.transcribe.WearTranscription

/**
 * The Dictate Wear OS keyboard: a lightweight [InputMethodService] hosting a Jetpack Compose UI.
 *
 * This is NOT the FlorisBoard engine — on a watch we want a voice-first input with compact
 * number/emoji fallbacks, so the input view is a small Wear Compose surface.
 *
 * An IME window is not backed by a ComponentActivity, so we implement the ViewTree owners
 * ([LifecycleOwner], [ViewModelStoreOwner], [SavedStateRegistryOwner]) ourselves and attach them
 * to the [ComposeView]; otherwise Compose refuses to compose inside the input view.
 *
 * Transcription wiring (record -> provider/tether -> commit) is deferred to P0/P2 of the Wear
 * roadmap; for now [toggleDictation] flips the visual state so the input surface is testable.
 */
class WearImeService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    private val dictationState = mutableStateOf(WearDictationState.IDLE)
    private val recordingInfo = mutableStateOf(WearRecordingInfo())
    // Short, human-readable reason shown on the voice page when a dictation fails, so problems are
    // diagnosable on the wrist without a logcat round-trip.
    private val errorMessage = mutableStateOf<String?>(null)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val recorder by lazy { WearAudioRecorder(applicationContext) }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        savedStateRegistryController.performRestore(null)
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        // Warm the cached settings synced from the phone so the voice page is ready to transcribe.
        WearSettingsStore.load(applicationContext)
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Opening the keyboard is a good moment to reconcile with the phone: read the latest replicated
        // settings DataItem (accent/provider/key/prompt) and nudge a republish. The cached copy is used
        // meanwhile so input never blocks on the sync.
        scope.launch {
            runCatching { WearSyncClient.requestSettingsSync(applicationContext) }
            runCatching { WearSyncClient.fetchPublishedSettings(applicationContext) }
                .getOrNull()?.let { WearSettingsStore.save(applicationContext, it) }
        }
    }

    override fun onCreateInputView(): View {
        // The input view becomes visible; drive the lifecycle to RESUMED so Compose runs.
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        // Compose resolves its window recomposer from the IME window's decor view (the root), not from
        // our ComposeView — and the IME wraps our view in its own parentPanel. So the ViewTree owners
        // MUST be set on the decor view, otherwise AbstractComposeView.onAttachedToWindow crashes with
        // "ViewTreeLifecycleOwner not found". We set them on both to be safe.
        window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }

        return ComposeView(this).apply {
            // Opaque host so the input view never lets the app behind show through (uniform background).
            setBackgroundColor(android.graphics.Color.BLACK)
            setViewTreeLifecycleOwner(this@WearImeService)
            setViewTreeViewModelStoreOwner(this@WearImeService)
            setViewTreeSavedStateRegistryOwner(this@WearImeService)
            setContent {
                WearKeyboard(
                    actions = actions,
                    dictationState = dictationState.value,
                    recordingInfo = recordingInfo.value,
                    errorMessage = errorMessage.value,
                    peakProvider = { recorder.maxAmplitude() },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        if (recorder.isRecording) recorder.cancel()
        scope.cancel()
    }

    private val actions = WearImeActions(
        commitText = { text -> ic()?.commitText(text, 1) },
        deleteBackward = { ic()?.deleteSurroundingText(1, 0) },
        performEnter = { ic()?.commitText("\n", 1) },
        toggleDictation = { toggleDictation() },
        togglePause = { togglePause() },
        cancelDictation = { cancelDictation() },
        dismiss = { requestHideSelf(0) },
    )

    private fun ic(): InputConnection? = currentInputConnection

    /**
     * Voice-page record button. Tap once to start recording, tap again to stop; on stop the audio is
     * transcribed (tethered via the phone when reachable, else standalone) and committed at the cursor.
     */
    private fun toggleDictation() {
        when (dictationState.value) {
            WearDictationState.RECORDING -> stopAndTranscribe()
            // Ignore taps while transcription/rewording is in flight.
            WearDictationState.TRANSCRIBING, WearDictationState.REWORDING -> Unit
            else -> startRecording()
        }
    }

    private fun startRecording() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            // The IME can't request permissions; the settings activity grants RECORD_AUDIO.
            fail(getString(R.string.wear_err_mic_grant))
            return
        }
        val result = runCatching { recorder.start() }
        if (result.isSuccess) {
            errorMessage.value = null
            recordingInfo.value = WearRecordingInfo(startedAtMs = SystemClock.elapsedRealtime())
            dictationState.value = WearDictationState.RECORDING
            WearHaptics.short(this) // recording started (#166)
        } else {
            fail(result.exceptionOrNull()?.shortReason() ?: getString(R.string.wear_err_mic_unavailable))
        }
    }

    /** Pause or resume the in-progress recording, accumulating elapsed time across segments. */
    private fun togglePause() {
        val info = recordingInfo.value
        if (dictationState.value != WearDictationState.RECORDING) return
        if (info.paused) {
            recorder.resume()
            recordingInfo.value = info.copy(startedAtMs = SystemClock.elapsedRealtime(), paused = false)
        } else {
            recorder.pause()
            val segment = SystemClock.elapsedRealtime() - info.startedAtMs
            recordingInfo.value = info.copy(accumulatedMs = info.accumulatedMs + segment, paused = true)
        }
    }

    /** Discard an in-progress recording without transcribing. */
    private fun cancelDictation() {
        if (recorder.isRecording) recorder.cancel()
        recordingInfo.value = WearRecordingInfo()
        dictationState.value = WearDictationState.IDLE
    }

    private fun stopAndTranscribe() {
        // Show the spinner immediately; do the WAV encode (recorder.stop) AND the network call off the
        // main thread so the IME never blocks long enough to trigger an ANR ("Dictate isn't responding").
        dictationState.value = WearDictationState.TRANSCRIBING
        WearHaptics.short(this) // recording stopped (#166)
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val audio = recorder.stop()
                    try {
                        WearTranscription.transcribe(
                            applicationContext,
                            audio,
                            // Fired when the watch starts standalone rewording → show "Rewording…". The
                            // transcript is ready at this point, so buzz "transcription done" (#166).
                            onRewording = {
                                scope.launch {
                                    dictationState.value = WearDictationState.REWORDING
                                    WearHaptics.double(applicationContext)
                                }
                            },
                        )
                    } finally {
                        audio.delete()
                    }
                }
            }
            val text = outcome.getOrNull()
            when {
                outcome.isFailure -> {
                    val e = outcome.exceptionOrNull()
                    Log.e(TAG, "Dictation failed", e)
                    fail(e?.shortReason() ?: getString(R.string.wear_err_transcribe_failed))
                }
                text.isNullOrBlank() -> fail(getString(R.string.wear_err_no_speech))
                else -> {
                    // Final buzz (#166): a longer one if a rewording just finished, else the double for a
                    // plain transcription (the double for the rewording case already fired at its start).
                    if (dictationState.value == WearDictationState.REWORDING) {
                        WearHaptics.medium(applicationContext)
                    } else {
                        WearHaptics.double(applicationContext)
                    }
                    ic()?.commitText(text, 1)
                    recordingInfo.value = WearRecordingInfo()
                    dictationState.value = WearDictationState.IDLE
                    // Dictation is the primary action — once the text is in, get out of the way so the
                    // user sees their field again instead of a keyboard stuck open over it.
                    requestHideSelf(0)
                }
            }
        }
    }

    private fun fail(reason: String) {
        errorMessage.value = reason
        recordingInfo.value = WearRecordingInfo()
        dictationState.value = WearDictationState.ERROR
    }

    /** A compact, user-facing reason from a thrown error (class name fallback when there's no message). */
    private fun Throwable.shortReason(): String =
        (message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: getString(R.string.wear_err_unknown)).take(80)

    private companion object {
        const val TAG = "WearIme"
    }
}
