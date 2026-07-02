/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.SystemClock
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisAppActivity
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.audio.AudioConcat
import dev.patrickgold.florisboard.dictate.audio.BluetoothMicRouter
import dev.patrickgold.florisboard.dictate.audio.RecordingController
import dev.patrickgold.florisboard.dictate.data.prompts.DictatePromptDefaults
import dev.patrickgold.florisboard.dictate.data.prompts.PromptModel
import dev.patrickgold.florisboard.dictate.data.prompts.PromptsDatabaseHelper
import dev.patrickgold.florisboard.dictate.data.stats.DictateStats
import dev.patrickgold.florisboard.dictate.provider.ChatRequest
import dev.patrickgold.florisboard.dictate.provider.DictateApiException
import dev.patrickgold.florisboard.dictate.provider.LocalModelManager
import dev.patrickgold.florisboard.dictate.provider.LocalTranscriptionProvider
import dev.patrickgold.florisboard.dictate.provider.OpenAiCompatibleClient
import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderPreset
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.dictate.provider.TranscriptionApi
import dev.patrickgold.florisboard.dictate.provider.TranscriptionRequest
import dev.patrickgold.florisboard.dictate.overlay.AccessibilitySink
import dev.patrickgold.florisboard.lib.util.AppVersionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat

/**
 * Orchestrates the dictation flow that fuses the recording, the provider layer and the editor: tap
 * to record, tap again to transcribe the audio and commit the result into the focused text field.
 *
 * Provider, API key and model are read from the unified JetPref store (`prefs.dictate`), which is
 * seeded once from the legacy Dictate settings on first run (see [dev.patrickgold.florisboard.
 * dictate.data.prefs.DictateLegacyMigrator]) and is editable in the in-app Dictate settings screen.
 *
 * Ported comfort features (all toggleable in the Dictate settings): pause/resume, cancel, audio
 * focus (pause other apps while recording), optional Bluetooth-SCO mic and a transcription retry
 * with a visible indicator.
 *
 * Rewording (GPT) is wired in: [applyPrompt] runs a prompt on the selection/cursor, [startLivePrompt]
 * sends a spoken instruction to the model, and every transcription runs through [postProcessTranscript]
 * (auto-formatting + auto-apply prompts). The prompt chips that drive these come later (UI phase).
 *
 * Not yet ported from the legacy service (later refinement): usage tracking.
 */
object DictateController {

    sealed interface UiState {
        data object Idle : UiState
        data class Recording(
            /** [SystemClock.elapsedRealtime] when the current (running) segment started. */
            val startedAtMs: Long,
            /** Elapsed time accumulated across previous, already-finished segments (before pauses). */
            val accumulatedMs: Long = 0L,
            val paused: Boolean = false,
        ) : UiState
        /** [attempt] is 1 for the first try, 2/3/… while retrying after a transient failure. */
        data class Transcribing(val attempt: Int = 1) : UiState
        /** A rewording/GPT request is in flight (manual prompt, auto-apply, auto-format or live). */
        data class Rewording(val label: String) : UiState
        /**
         * A failed transcription/rewording (roadmap 1.12). [message] is the short, localized headline
         * (derived from [kind]); [detail] is the raw provider text shown when the user taps the chip;
         * [action] is the contextual button offered (resend the kept audio, open settings, or none).
         */
        data class Error(
            val message: String,
            val kind: DictateApiException.Kind? = null,
            val action: ErrorAction = ErrorAction.NONE,
            val detail: String? = null,
        ) : UiState
        /**
         * Offer to send a recording that was interrupted because the keyboard closed mid-recording: the
         * audio was finalized and persisted, so on the next keyboard open this neutral (non-error) chip
         * offers to transcribe it or discard it. [seconds] is the captured length, shown for context.
         */
        data class Interrupted(val seconds: Long) : UiState
        /**
         * A one-time Smartbar nudge (roadmap 9.7/9.8). [message] overrides the kind's static text for
         * nudges whose text is dynamic (the [PromoKind.MILESTONE] celebration, issue #142).
         */
        data class Promo(val kind: PromoKind, val message: String? = null) : UiState
    }

    /** Why an audio file is being kept for a one-tap re-send (drives the unified resend chip copy/tint). */
    enum class RetainReason {
        /** A transcription/rewording failed; the kept audio can be retried (in-memory, cache file). */
        FAILED,

        /** The keyboard closed mid-recording; the finalized audio was persisted to survive process death. */
        INTERRUPTED,
    }

    /** The contextual action a [UiState.Error] offers (see roadmap 1.12 keyboard design). */
    enum class ErrorAction {
        /** No action; the chip auto-clears after a moment. */
        NONE,

        /** Retry the same kept audio (transient failures with retained audio, roadmap 10.3). */
        RESEND,

        /** Open the Dictate provider settings (fixable errors like an invalid/missing API key). */
        OPEN_SETTINGS,

        /**
         * Export the kept recording to Downloads (issue #144): offered when transcription fails for a
         * reason that resending can't fix (too large / unsupported format) so a long recording isn't lost.
         */
        SAVE_AUDIO,
    }

    /**
     * Which one-time nudge is being shown. RATE/DONATE are usage-gated (see [maybePromptForReview]);
     * CHANGELOG is shown right after an app update (see [maybePromptChangelog]) and opens the in-app
     * "What's new" dialog instead of a web page.
     */
    enum class PromoKind { RATE, DONATE, CHANGELOG, FLOATING_BUTTON, MILESTONE }

    /**
     * Where the active dictation's output goes: the keyboard editor ([OutputTarget.IME]) or the
     * accessibility-injected field of the floating button ([OutputTarget.OVERLAY], issue #88). Set when a
     * dictation starts (the mic-tap entry points carry their source); the two never drive concurrently.
     */
    enum class OutputTarget { IME, OVERLAY }

    /**
     * Temporary debug switch to preview the "Dictate was updated" Smartbar nudge. When true, the nudge
     * is offered on every keyboard open (the real version gate never triggers on debug builds, whose
     * version name carries an unparseable suffix). MUST be false for any committed/shipped build.
     */
    private const val DEBUG_FORCE_CHANGELOG_NUDGE = false

    /** Forces the floating-button spotlight regardless of gates (testing only). MUST be false for shipped builds. */
    private const val DEBUG_FORCE_FB_SPOTLIGHT = false

    private val prefs by FlorisPreferenceStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _prompts = MutableStateFlow<List<PromptModel>>(emptyList())
    /** The user's saved prompts (shared `prompts.db`), refreshed via [refreshPrompts]; drives the Smartbar prompt chips. */
    val prompts: StateFlow<List<PromptModel>> = _prompts.asStateFlow()

    private val _pendingPrompts = MutableStateFlow<List<PromptModel>>(emptyList())
    /**
     * Prompts queued by tapping the always-on prompt row while recording (ROW layout). They are applied
     * in tap order to the finished transcript before it is committed (see [applyPendingPrompts]); the UI
     * highlights every queued prompt in the accent color. Empty whenever no recording queue is active.
     */
    val pendingPrompts: StateFlow<List<PromptModel>> = _pendingPrompts.asStateFlow()

    private var recorder: RecordingController? = null
    private var startJob: Job? = null

    /** Peak mic amplitude (0..32767) since the previous call, or 0 when idle. Drives the overlay waveform. */
    fun currentAmplitude(): Int = recorder?.maxAmplitude() ?: 0

    /** The in-flight transcription coroutine, cancellable via the stop button (see [cancelTranscription]). */
    private var transcribeJob: Job? = null

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var btRouter: BluetoothMicRouter? = null

    /** When true, the next finished recording is fed to the rewording model instead of committed. */
    private var livePromptArmed = false

    /** Output destination of the in-flight dictation; see [OutputTarget]. Reset to IME when idle. */
    private var outputTarget = OutputTarget.IME

    /**
     * A single audio file kept for a one-tap re-send, used by both the error-resend chip and the
     * interrupted-recording chip (unified resend path). [reason] distinguishes a failed transcription
     * (kept in cache, in-memory only) from a recording interrupted by the keyboard closing (finalized
     * and persisted to filesDir, mirrored by the `interruptedAudio*` prefs so it survives process death).
     */
    private data class RetainedAudio(
        val file: File,
        val reason: RetainReason,
        val wasLive: Boolean,
        val seconds: Long,
    )

    /** The currently kept audio (failed or interrupted), or null when there is nothing to re-send. */
    private var retained: RetainedAudio? = null

    /**
     * A previously captured audio segment to prepend to the next finished recording, set when the user
     * chooses to *continue* an interrupted recording (see [continueInterruptedRecording]). The new
     * segment is recorded normally and the two are merged ([AudioConcat]) before transcription. Null
     * unless a continuation is in progress.
     */
    private var carryOverAudio: File? = null

    /** Recorded seconds of [carryOverAudio], so the continued recording's total length stays correct. */
    private var carryOverSeconds = 0L

    /** Cache file name for the merged audio when a continued interrupted recording is stitched together. */
    private const val MERGED_AUDIO_NAME = "dictate_merged.wav"

    /** Cumulative recorded audio (seconds) after which the rate / donate nudges appear (roadmap 9.7/9.8). */
    private const val RATE_THRESHOLD_SECONDS = 180L   // 3 min
    private const val DONATE_THRESHOLD_SECONDS = 300L // 5 min (user choice; legacy used 10 min)

    /**
     * Single entry point for the mic button: starts recording, or stops and transcribes. [target]
     * selects where the finished text goes — the keyboard editor for the in-keyboard mic (default), or
     * the accessibility-injected field for the floating button (issue #88). It is latched when a fresh
     * recording starts, so the stop tap from the same source uses the same destination.
     */
    fun onMicClick(context: Context, target: OutputTarget = OutputTarget.IME) {
        when (_state.value) {
            is UiState.Recording -> stopAndTranscribe(context)
            // Tapping the mic while transcribing aborts it (the button shows a stop icon, see the
            // ComputingEvaluator). Rewording stays a no-op.
            is UiState.Transcribing -> cancelTranscription()
            is UiState.Rewording -> Unit
            else -> {
                outputTarget = target
                startRecording(context)
            }
        }
    }

    /**
     * Toggles a prompt in the recording-time queue (ROW layout): while a recording/transcription is in
     * flight, tapping a prompt chip enqueues it (or removes it if already queued) instead of applying it
     * immediately. The queue is applied in tap order to the finished transcript (see [applyPendingPrompts]).
     * No-op outside the recording/transcribing states or for non-persisted prompts.
     */
    fun togglePendingPrompt(prompt: PromptModel) {
        if (_state.value !is UiState.Recording && _state.value !is UiState.Transcribing) return
        if (!prompt.isPersisted()) return
        val current = _pendingPrompts.value
        _pendingPrompts.value = if (current.any { it.id == prompt.id }) {
            current.filterNot { it.id == prompt.id }
        } else {
            current + prompt
        }
    }

    /** Toggles pause/resume of the in-progress recording. No-op outside the recording state. */
    fun togglePause() {
        val current = _state.value as? UiState.Recording ?: return
        val rec = recorder ?: return
        if (current.paused) {
            rec.resume()
            _state.value = current.copy(startedAtMs = SystemClock.elapsedRealtime(), paused = false)
        } else {
            rec.pause()
            val segment = SystemClock.elapsedRealtime() - current.startedAtMs
            _state.value = current.copy(accumulatedMs = current.accumulatedMs + segment, paused = true)
        }
    }

    /** The active dictation language (defaults to auto-detect); read live from the JetPref store. */
    fun activeLanguage(): DictateLanguage = DictateLanguages.of(prefs.dictate.activeInputLanguage.get())

    /** Advances the active language to the next entry in the user's selected subset (no-op if ≤1). */
    fun cycleLanguage() {
        val selection = DictateLanguages.parseSelection(prefs.dictate.inputLanguages.get())
        if (selection.size <= 1) return
        val currentCode = prefs.dictate.activeInputLanguage.get()
        val idx = selection.indexOfFirst { it.code == currentCode }
        val next = selection[(idx + 1) % selection.size] // idx == -1 (unknown) → starts at index 0
        scope.launch { prefs.dictate.activeInputLanguage.set(next.code) }
    }

    /** Sets the active dictation language explicitly (from the recording bar's language picker). */
    fun setLanguage(code: String) {
        scope.launch { prefs.dictate.activeInputLanguage.set(code) }
    }

    /**
     * Reloads the prompt list from the shared `prompts.db` into [prompts]. Cheap and idempotent;
     * called when the keyboard (re-)appears so the chip strip reflects edits made in the settings.
     */
    fun refreshPrompts(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            _prompts.value = withContext(Dispatchers.IO) { promptsDb(appContext).getAll() }
        }
    }

    /** Clears a transient error back to idle (the Smartbar UI calls this after showing it briefly). */
    fun clearError() {
        if (_state.value is UiState.Error) _state.value = UiState.Idle
    }

    /**
     * Opens the Dictate provider settings from the keyboard, used by the "fixable" errors (e.g. an
     * invalid or missing API key, roadmap 1.12). Launched as a new task since an IME has no activity of
     * its own; clears the error afterwards so the Smartbar returns to normal.
     */
    fun openProviderSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("ui://florisboard/settings/dictate/providers"))
                    // BROWSABLE is required: FlorisAppActivity.onNewIntent only routes a VIEW intent to the
                    // nav-graph deep-link handler when it carries this category, otherwise it treats the
                    // intent as an extension-import and lands on the wrong screen.
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        clearError()
    }

    /** Localized one-line headline for an API error [kind] (roadmap 1.12 specific error messages). */
    private fun errorMessageRes(kind: DictateApiException.Kind): Int = when (kind) {
        DictateApiException.Kind.INVALID_API_KEY -> R.string.dictate__error_invalid_api_key
        DictateApiException.Kind.QUOTA_EXCEEDED -> R.string.dictate__error_quota_exceeded
        DictateApiException.Kind.CONTENT_SIZE_LIMIT -> R.string.dictate__error_content_size_limit
        DictateApiException.Kind.FORMAT_NOT_SUPPORTED -> R.string.dictate__error_format_not_supported
        DictateApiException.Kind.TIMEOUT -> R.string.dictate__error_timeout
        DictateApiException.Kind.NETWORK -> R.string.dictate__error_network
        DictateApiException.Kind.SERVER_ERROR -> R.string.dictate__error_server
        DictateApiException.Kind.UNKNOWN -> R.string.dictate__error_unknown
    }

    /**
     * Builds an [UiState.Error] from an API exception: a localized headline (per [DictateApiException.Kind]),
     * the raw provider text kept as the tappable detail, and the contextual action — resend the kept audio
     * for retryable failures, open settings for a bad/missing key, otherwise none.
     */
    /** Failures where resending is pointless but the recording is worth saving instead (issue #144). */
    private val EXPORTABLE_ERROR_KINDS = setOf(
        DictateApiException.Kind.CONTENT_SIZE_LIMIT,
        DictateApiException.Kind.FORMAT_NOT_SUPPORTED,
    )

    private fun apiError(e: DictateApiException, context: Context, canResend: Boolean): UiState.Error {
        val action = when {
            canResend && e.kind in EXPORTABLE_ERROR_KINDS -> ErrorAction.SAVE_AUDIO
            canResend && e.kind.isRetryable -> ErrorAction.RESEND
            e.kind == DictateApiException.Kind.INVALID_API_KEY -> ErrorAction.OPEN_SETTINGS
            else -> ErrorAction.NONE
        }
        return UiState.Error(
            message = context.getString(errorMessageRes(e.kind)),
            kind = e.kind,
            action = action,
            detail = e.message?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Declines the kept audio (whether from a failed transcription or an interrupted recording): drops
     * the audio and returns the Smartbar to idle. Shared dismiss (✗) for both resend chips.
     */
    fun dismissRetainedAudio() {
        discardRetainedAudio()
        if (_state.value is UiState.Error || _state.value is UiState.Interrupted) {
            _state.value = UiState.Idle
        }
    }

    /** Aborts an in-progress recording and returns to idle (cancel button / leaving the keyboard). */
    fun cancelRecording() {
        startJob?.cancel()
        startJob = null
        recorder?.cancel()
        recorder = null
        cleanupAudioRouting()
        livePromptArmed = false
        _pendingPrompts.value = emptyList()
        // Cancelling a continued recording also throws away the carried-over interrupted segment.
        discardCarryOver()
        if (_state.value is UiState.Recording) {
            _state.value = UiState.Idle
        }
    }

    /** Kept for the legacy in-keyboard panel; identical to [cancelRecording]. */
    fun abortRecording() = cancelRecording()

    /**
     * Aborts an in-flight transcription (stop button shown on the mic while transcribing). Cancels the
     * network coroutine, drops the audio (handled in the job's finally) and returns to idle. No-op
     * outside the transcribing state, so a tap can never interrupt a rewording request.
     */
    fun cancelTranscription() {
        if (_state.value !is UiState.Transcribing) return
        transcribeJob?.cancel()
        transcribeJob = null
        _pendingPrompts.value = emptyList()
        _state.value = UiState.Idle
    }

    /**
     * Starts a recording. [seedAccumulatedMs] pre-fills the elapsed timer (and the credited length) with
     * already-captured audio when continuing an interrupted recording, so the bar shows the running
     * total; it is 0 for a normal recording.
     */
    private fun startRecording(context: Context, seedAccumulatedMs: Long = 0L) {
        if (_state.value is UiState.Recording) return
        // Starting a fresh recording supersedes any kept audio (a failed retry or an interrupted
        // recording the user chose not to send), so drop it instead of leaving a stale offer behind.
        // A continuation keeps its carry-over (seeded above), so only drop it for a normal start.
        if (seedAccumulatedMs == 0L) {
            discardRetainedAudio()
            discardCarryOver()
        }
        val appContext = context.applicationContext
        startJob = scope.launch {
            try {
                requestAudioFocusIfEnabled(appContext)
                val audioSource = setupBluetoothIfEnabled(appContext)
                recorder = RecordingController(appContext).also { it.start(audioSource) }
                _state.value = UiState.Recording(SystemClock.elapsedRealtime(), accumulatedMs = seedAccumulatedMs)
            } catch (t: Throwable) {
                recorder = null
                cleanupAudioRouting()
                _state.value = UiState.Error(
                    // Most common cause is the missing RECORD_AUDIO permission (granted in onboarding).
                    appContext.getString(R.string.dictate__error_recording_failed, t.message ?: ""),
                )
            }
        }
    }

    private fun stopAndTranscribe(context: Context) {
        val activeRecorder = recorder
        recorder = null
        // Capture the recorded length before leaving the Recording state, to credit the usage counter
        // that gates the rate/donate nudges (roadmap 9.7/9.8). Includes any carried-over seconds.
        val recordedSeconds = recordedSecondsOf(_state.value)
        val audioFile = activeRecorder?.stop()
        cleanupAudioRouting()
        val carry = carryOverAudio
        carryOverAudio = null
        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0L) {
            // The new segment is unusable. If we were continuing an interrupted recording, fall back to
            // transcribing the carried-over segment alone rather than losing it.
            if (carry != null && carry.exists() && carry.length() > 0L) {
                scope.launch { clearInterruptedAudioPref() }
                transcribe(context, carry, carryOverSeconds)
            } else {
                carry?.delete()
                _state.value = UiState.Error(context.getString(R.string.dictate__error_no_audio))
            }
            return
        }
        if (carry == null) {
            transcribe(context, audioFile, recordedSeconds)
            return
        }
        // Continuation: stitch the carried-over segment and the new one into a single audio so the whole
        // dictation is transcribed as one. The interrupted marker was already claimed when continuing.
        scope.launch { clearInterruptedAudioPref() }
        val merged = File(context.applicationContext.cacheDir, MERGED_AUDIO_NAME)
        val ok = AudioConcat.concat(listOf(carry, audioFile), merged)
        carry.delete()
        if (ok && merged.exists() && merged.length() > 0L) {
            audioFile.delete()
            transcribe(context, merged, recordedSeconds)
        } else {
            // Merge failed (rare): transcribe at least the newly recorded segment.
            merged.delete()
            transcribe(context, audioFile, recordedSeconds)
        }
    }

    /** Elapsed recorded seconds of a [UiState.Recording] (running + accumulated), else 0. */
    private fun recordedSecondsOf(state: UiState): Long {
        val rec = state as? UiState.Recording ?: return 0L
        val running = if (rec.paused) 0L else SystemClock.elapsedRealtime() - rec.startedAtMs
        return ((rec.accumulatedMs + running) / 1000L).coerceAtLeast(0L)
    }

    /**
     * Long-press entry point for the mic: hands off to [FileTranscriptionActivity] so the user can
     * pick an existing audio/video file to transcribe instead of recording. The activity stashes the
     * picked file and a pref; [consumePendingFileTranscription] finishes the job once the keyboard
     * regains focus. No-op unless we are idle.
     */
    fun startFileTranscription(context: Context) {
        if (_state.value !is UiState.Idle) return
        val intent = Intent(context, FileTranscriptionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Cache directory where [FileTranscriptionActivity] drops a picked file for the IME to pick up.
     * A dedicated directory keeps the handoff file-based (survives the IME process being killed while
     * the file picker is foreground) and unambiguous.
     */
    fun pendingTranscriptionDir(context: Context): File = File(context.cacheDir, "dictate_pending")

    /**
     * Called by the IME when the keyboard (re-)appears on a field: if [FileTranscriptionActivity]
     * stashed a picked file, transcribe it now and commit into the focused field. Returns true if a
     * transcription was started, so the caller can skip instant-recording.
     *
     * Safe to call from multiple lifecycle hooks: the pending file is *claimed* (moved out of the
     * pending dir) before transcription starts, so a second call finds nothing and is a no-op.
     */
    fun consumePendingFileTranscription(context: Context): Boolean {
        if (_state.value !is UiState.Idle) return false
        val pending = pendingTranscriptionDir(context).listFiles()?.firstOrNull { it.isFile && it.length() > 0L }
            ?: return false
        // Claim it: move out of the pending dir so it cannot be picked up twice, then clean the dir.
        val claimed = File(context.cacheDir, "dictate_import_${pending.name}")
        claimed.delete()
        if (!pending.renameTo(claimed)) {
            pending.copyTo(claimed, overwrite = true)
            pending.delete()
        }
        pendingTranscriptionDir(context).deleteRecursively()
        if (!claimed.exists() || claimed.length() == 0L) return false
        transcribe(context, claimed)
        return true
    }

    /**
     * Shared transcription path for both recorded and picked audio: resolves provider/key/model,
     * uploads [audioFile], commits the result and deletes the file afterwards.
     */
    private fun transcribe(context: Context, audioFile: File, recordedSeconds: Long = 0L) {
        val account = transcriptionAccount()
        val apiKey = account.apiKey
        if (apiKey.isBlank() && requiresKey(account)) {
            _state.value = UiState.Error(
                message = context.getString(R.string.dictate__error_no_api_key),
                kind = DictateApiException.Kind.INVALID_API_KEY,
                action = ErrorAction.OPEN_SETTINGS,
            )
            audioFile.delete()
            return
        }

        val preset = presetFor(account)
        val model = account.transcriptionModel.takeIf { it.isNotBlank() }
            ?: preset.defaultTranscriptionModel
            ?: "gpt-4o-mini-transcribe"

        // On-device (#104): guide the user to download a model instead of failing mid-transcription.
        if (preset.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE &&
            !LocalModelManager.isInstalled(context.applicationContext, model)
        ) {
            _state.value = UiState.Error(
                message = context.getString(R.string.dictate__local_model_not_installed_error),
                kind = DictateApiException.Kind.UNKNOWN,
                action = ErrorAction.OPEN_SETTINGS,
            )
            audioFile.delete()
            return
        }

        _state.value = UiState.Transcribing()
        val appContext = context.applicationContext
        // Live prompt is consumed by this transcription only (the next recording is normal again).
        val live = livePromptArmed
        livePromptArmed = false
        transcribeJob = scope.launch {
            var keepAudio = false
            try {
                // Single-call multimodal (issue #130): one chat/completions+input_audio request transcribes
                // and formats together (cloud chat models only, never the on-device engine).
                val chatAudio = account.transcriptionViaChat &&
                    preset.transcriptionApi != TranscriptionApi.LOCAL_ONDEVICE
                val request = TranscriptionRequest(
                    audioFile = audioFile,
                    model = model,
                    // Null for "detect" so the provider auto-detects; otherwise the chosen code. For the
                    // chat-audio path the language goes into the instruction (readable name) instead.
                    language = if (chatAudio) null else prefs.dictate.activeInputLanguage.get()
                        .takeIf { it != DictateLanguages.DETECT },
                    // Non-chat: style/punctuation prompt biases recognition (roadmap 2.4 / 4.11).
                    // Chat-audio: the full instruction (language + style + all auto-formatting) in one go.
                    prompt = if (chatAudio) buildChatAudioInstruction(appContext) else transcriptionStylePrompt(),
                )
                val result = if (preset.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE) {
                    // On-device (issue #104): no HTTP client, no key; transcribe locally via sherpa-onnx.
                    LocalTranscriptionProvider(LocalTranscriptionProvider.modelDir(appContext, model))
                        .transcribe(request)
                } else {
                    try {
                        OpenAiCompatibleClient.from(
                            preset, apiKey,
                            baseUrlOverride = baseUrlOverrideFor(account),
                            proxy = prefs.dictate.dictateProxyConfig(),
                            // Single-call multimodal (issue #130): route audio through chat/completions.
                            useChatAudio = chatAudio,
                            trustUserCerts = prefs.dictate.trustUserCertificates.get(),
                        ).transcribe(
                            request,
                            onRetry = { attempt -> _state.value = UiState.Transcribing(attempt) },
                        )
                    } catch (e: DictateApiException) {
                        // Offline fallback (#104): the cloud call failed because we're offline (after its
                        // retries) — transcribe on-device with the downloaded model instead of erroring.
                        val fallback = localFallbackProvider(appContext, preset, e) ?: throw e
                        _state.value = UiState.Transcribing()
                        fallback.transcribe(request)
                    }
                }
                val finalText = if (live) {
                    // The spoken transcript is an instruction; send it to GPT (optionally operating
                    // on the current selection) and insert the answer instead of the transcript.
                    _pendingPrompts.value = emptyList() // a live prompt ignores any queued prompts
                    _state.value = UiState.Rewording(appContext.getString(R.string.dictate__status_rewording))
                    val selection = sink(appContext).selectedText().takeIf { it.isNotEmpty() }
                    requestReword(result.text, selection)
                } else {
                    // Normal dictation: auto-formatting + auto-apply prompts, then the prompts the user
                    // queued by tapping the prompt row while recording, in tap order; then commit.
                    // Single-call multimodal (issue #130) already returns finished, formatted text in one
                    // request (the auto-formatting/auto-apply prompts were folded into the instruction), so
                    // the separate rewording pass is skipped; explicitly queued prompts still apply.
                    val processed = if (chatAudio) {
                        result.text
                    } else {
                        postProcessTranscript(appContext, result.text)
                    }
                    applyPendingPrompts(appContext, processed)
                }
                // Deterministic find-and-replace dictionary (issue #129), applied to the finished text
                // right before it is inserted — independent of the AI rewording stage, so it is exact
                // and costs no tokens. No-op when the user has no mappings configured.
                val outputText = prefs.dictate.customMappings.get().apply(finalText)
                // Output behavior (roadmap 10.1/10.2): instant or typed, then optional auto-enter.
                commitOutput(appContext, outputText)
                // Keep the committed text as the re-insert safety net (issue #111), so it can be
                // recovered if the field is later cleared (rotation / context switch / host refresh).
                rememberLastDictation(outputText)
                // Lifetime statistics (issue #142): count this dictation, its words and spoken time.
                DictateStats.recordDictation(prefs, outputText, recordedSeconds)
                discardRetainedAudio()
                // Credit recorded audio towards the rate/donate gating (roadmap 9.7/9.8).
                if (recordedSeconds > 0L) creditAudioSeconds(recordedSeconds)
                _state.value = UiState.Idle
                // A positive milestone celebration (issue #142) takes precedence over the rate/donate
                // nudge, but only on the keyboard: an overlay dictation leaves it pending so it is shown
                // (not silently consumed) the next time the keyboard is used.
                if (outputTarget != OutputTarget.IME || !showMilestoneNudge(appContext)) {
                    // Re-assert the rate/donate nudge: it has no auto-timeout and stays until the user
                    // accepts/declines, so if recording temporarily replaced a pending nudge, bring it back.
                    maybePromptForReview()
                }
            } catch (c: CancellationException) {
                // User aborted via the stop button: discard quietly (state set by cancelTranscription),
                // never show an error. The audio is dropped in the finally block.
                throw c
            } catch (e: DictateApiException) {
                _pendingPrompts.value = emptyList()
                // Exportable failures (too large / bad format) keep the audio regardless of the resend
                // pref, so it can be saved instead of lost (issue #144).
                keepAudio = retainFailedAudio(audioFile, live, recordedSeconds, force = e.kind in EXPORTABLE_ERROR_KINDS)
                _state.value = apiError(e, appContext, canResend = keepAudio)
            } catch (t: Throwable) {
                _pendingPrompts.value = emptyList()
                keepAudio = retainFailedAudio(audioFile, live, recordedSeconds)
                _state.value = UiState.Error(
                    message = appContext.getString(R.string.dictate__error_unknown),
                    kind = DictateApiException.Kind.UNKNOWN,
                    action = if (keepAudio) ErrorAction.RESEND else ErrorAction.NONE,
                    detail = t.message?.takeIf { it.isNotBlank() },
                )
            } finally {
                if (!keepAudio) audioFile.delete()
            }
        }
    }

    // --- Output behavior + resend (roadmap section 10) ------------------------------------------

    /**
     * Resolves the output sink for the current dictation: where the finished text is written and how the
     * focused field is read. The keyboard's own editor ([ImeDictationSink]) for in-keyboard dictation, or
     * an accessibility-backed sink ([dev.patrickgold.florisboard.dictate.overlay.AccessibilitySink]) when
     * the dictation was started from the floating button (issue #88) and must inject into another app.
     * This single seam keeps the rest of the engine editor-agnostic.
     */
    private fun sink(context: Context): DictationSink = when (outputTarget) {
        OutputTarget.IME -> ImeDictationSink(context)
        OutputTarget.OVERLAY -> AccessibilitySink()
    }

    /**
     * Commits [text] into the focused field honoring the output prefs: either all at once
     * ([prefs.dictate.instantOutput]) or "typed" character by character at the configured speed, then
     * an optional auto-enter (10.1). Runs on the caller's (Main) coroutine, so the typewriter delay
     * suspends rather than blocks.
     */
    private suspend fun commitOutput(context: Context, text: String) {
        val sink = sink(context)
        if (prefs.dictate.instantOutput.get()) {
            sink.commitText(text)
        } else if (text.isNotEmpty()) {
            val perChar = perCharDelayMs(prefs.dictate.outputSpeed.get())
            text.forEach { ch ->
                sink.commitText(ch.toString())
                delay(perChar)
            }
        }
        // No auto-enter on an empty result (e.g. silence): don't fire a stray newline into the field (#124).
        if (prefs.dictate.autoEnter.get() && text.isNotEmpty()) {
            sink.performEnter()
        }
    }

    /** Per-character delay for the typewriter output: speed 1 → 100 ms … 5 → 20 ms … 10 → 10 ms (legacy mapping). */
    private fun perCharDelayMs(speed: Int): Long = (100L / speed.coerceIn(1, 10)).coerceAtLeast(1L)

    // --- Retained audio + unified resend (failed transcription / interrupted recording) ---------

    /**
     * Retains [audioFile] after a failed transcription so the error chip's resend button can retry it,
     * if the resend button is enabled and the file is usable; returns true when kept. The file stays in
     * the cache (a transient failure does not need to survive process death). Any previously kept audio
     * is discarded first.
     */
    private fun retainFailedAudio(
        audioFile: File,
        wasLive: Boolean,
        recordedSeconds: Long,
        // Keep even when the resend button is off — used for exportable failures so the recording can be
        // saved (issue #144); otherwise retention is gated on the resend-button preference.
        force: Boolean = false,
    ): Boolean {
        if (!force && !prefs.dictate.resendButton.get()) return false
        if (!audioFile.exists() || audioFile.length() == 0L) return false
        if (retained?.file != audioFile) discardRetainedAudio()
        retained = RetainedAudio(audioFile, RetainReason.FAILED, wasLive, recordedSeconds)
        return true
    }

    /** Deletes the kept audio (if any), forgets it, and clears the persisted interrupted-audio marker. */
    fun discardRetainedAudio() {
        retained?.file?.takeIf { it.exists() }?.delete()
        retained = null
        scope.launch { clearInterruptedAudioPref() }
    }

    /**
     * Re-sends the currently kept audio — used by *both* the error-resend chip and the interrupted-
     * recording chip (unified path). Repeats the original mode (a kept live-prompt resends as a live
     * prompt) and re-credits the recorded seconds towards the nudges. No-op unless we are idle/showing
     * one of the resend chips and a usable file exists. Interrupted audio is claimed (its persisted
     * marker cleared) up front, so a crash mid-transcription cannot re-offer the same recording.
     */
    fun sendRetainedAudio(context: Context) {
        if (_state.value !is UiState.Error && _state.value !is UiState.Interrupted &&
            _state.value !is UiState.Idle
        ) return
        val r = retained
        if (r == null || !r.file.exists() || r.file.length() == 0L) {
            discardRetainedAudio()
            _state.value = UiState.Idle
            return
        }
        if (r.reason == RetainReason.INTERRUPTED) scope.launch { clearInterruptedAudioPref() }
        livePromptArmed = r.wasLive
        transcribe(context, r.file, r.seconds)
    }

    /**
     * Exports the kept recording to the public Downloads folder (issue #144), so a dictation that failed
     * for a non-retryable reason (too large / unsupported format) can be recovered instead of lost. On
     * success the audio is dropped and a toast confirms the file name; on failure it is kept so the user
     * can try again. No-op unless a usable kept file exists.
     */
    fun saveRetainedAudio(context: Context) {
        val r = retained
        if (r == null || !r.file.exists() || r.file.length() == 0L) {
            discardRetainedAudio()
            _state.value = UiState.Idle
            return
        }
        val appContext = context.applicationContext
        val src = r.file
        scope.launch {
            val savedName = withContext(Dispatchers.IO) { exportAudioToDownloads(appContext, src) }
            val message = if (savedName != null) {
                appContext.getString(R.string.dictate__audio_saved, savedName)
            } else {
                appContext.getString(R.string.dictate__audio_save_failed)
            }
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            // Keep the audio on failure so the user can retry the export; drop it once safely saved.
            if (savedName != null) discardRetainedAudio()
            _state.value = UiState.Idle
        }
    }

    /** Copies [src] into Downloads/Dictate as a WAV via MediaStore (API 29+) or the public dir. Returns the file name, or null on failure. */
    private fun exportAudioToDownloads(context: Context, src: File): String? = runCatching {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val name = "dictate-recording-$stamp.wav"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Dictate")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: return null
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Dictate")
            dir.mkdirs()
            src.inputStream().use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
        }
        name
    }.getOrNull()

    /**
     * Continues an interrupted recording instead of sending it: the kept audio becomes a carry-over
     * segment and a fresh recording starts, with the timer seeded so it shows the running total. When the
     * user finally stops, the carry-over and the new segment are merged into one audio and transcribed
     * together (see [stopAndTranscribe]); if the keyboard closes again first, both are merged back into
     * the persisted interrupted file (see [stashRecordingOnHide]). No-op unless the interrupted chip is
     * showing with a usable file.
     */
    fun continueInterruptedRecording(context: Context) {
        if (_state.value !is UiState.Interrupted) return
        val r = retained
        if (r == null || r.reason != RetainReason.INTERRUPTED || !r.file.exists() || r.file.length() == 0L) {
            discardRetainedAudio()
            _state.value = UiState.Idle
            return
        }
        // Claim the interrupted audio as the carry-over and clear the offer/marker, then record on top.
        retained = null
        carryOverAudio = r.file
        carryOverSeconds = r.seconds
        scope.launch { clearInterruptedAudioPref() }
        _state.value = UiState.Idle
        livePromptArmed = r.wasLive
        // coerceAtLeast(1) keeps the "continuation" path (non-zero seed) so the carry-over is not dropped
        // by the normal-start cleanup, even for a sub-second carried-over segment.
        startRecording(context, seedAccumulatedMs = (r.seconds * 1000L).coerceAtLeast(1L))
    }

    /** Deletes the carry-over recording segment (if any) and forgets it. */
    private fun discardCarryOver() {
        carryOverAudio?.takeIf { it.exists() }?.delete()
        carryOverAudio = null
        carryOverSeconds = 0L
    }

    // --- Interrupted recording (keyboard closed mid-recording) ----------------------------------

    /** Stable on-disk location for an interrupted recording: in filesDir so it survives the cache wipe. */
    private fun interruptedAudioFile(context: Context): File =
        File(context.applicationContext.filesDir, "dictate_interrupted.wav")

    /**
     * Called when the keyboard window is hidden (see [FlorisImeService.onWindowHidden]). If a recording
     * is in progress it is *finalized and kept* instead of discarded: the audio is stopped cleanly (so
     * the WAV is valid even if the recorder/process is destroyed afterwards) and moved to [interruptedAudioFile],
     * with its metadata mirrored to prefs. The next keyboard open then offers to send it (see
     * [maybeOfferInterruptedRecording]). Outside the recording state this falls back to the normal
     * teardown ([cancelRecording]).
     */
    fun stashRecordingOnHide(context: Context) {
        val current = _state.value
        val activeRecorder = recorder
        if (current !is UiState.Recording || activeRecorder == null) {
            // Not actively recording (e.g. a start that never got going): use the normal teardown.
            cancelRecording()
            return
        }
        recorder = null
        val seconds = recordedSecondsOf(current)
        val wasLive = livePromptArmed
        val audioFile = activeRecorder.stop()
        cleanupAudioRouting()
        livePromptArmed = false
        _pendingPrompts.value = emptyList()
        _state.value = UiState.Idle

        // Interrupted-recording recovery is disabled while instant recording is on (issue #120): every
        // keyboard open auto-starts a recording, so a stashed segment would only block the next open.
        // Discard the finalized audio instead of keeping it (the user is told about this trade-off when
        // enabling instant recording, and the whole feature only applies with instant recording off).
        if (prefs.dictate.instantRecording.get()) {
            audioFile?.takeIf { it.exists() }?.delete()
            discardCarryOver()
            scope.launch { clearInterruptedAudioPref() }
            return
        }

        val carry = carryOverAudio
        carryOverAudio = null
        val dest = interruptedAudioFile(context)
        val newValid = audioFile != null && audioFile.exists() && audioFile.length() > 0L
        // Resolve the audio to keep (and its length). dest == carry for a continuation, so when both
        // segments are present they are merged into a cache temp first and then moved onto dest.
        val keptSeconds: Long? = when {
            carry != null && newValid -> {
                val merged = File(context.applicationContext.cacheDir, MERGED_AUDIO_NAME)
                val ok = AudioConcat.concat(listOf(carry, audioFile!!), merged)
                if (ok && merged.exists() && merged.length() > 0L) {
                    carry.delete()
                    audioFile.delete()
                    runCatching {
                        dest.delete()
                        if (!merged.renameTo(dest)) {
                            merged.copyTo(dest, overwrite = true)
                            merged.delete()
                        }
                    }
                    if (dest.exists() && dest.length() > 0L) seconds else null
                } else {
                    // Merge failed (rare): keep the carry-over (already at dest), drop the new segment.
                    merged.delete()
                    audioFile.delete()
                    if (carry.exists() && carry.length() > 0L) carryOverSeconds else null
                }
            }
            // Continuation, but the new segment is unusable: keep the carried-over segment alone.
            carry != null -> if (carry.exists() && carry.length() > 0L) carryOverSeconds else null
            // Plain recording interrupted: move the finalized segment out of the cache into filesDir.
            newValid -> {
                runCatching {
                    dest.parentFile?.mkdirs()
                    dest.delete()
                    if (!audioFile!!.renameTo(dest)) {
                        audioFile.copyTo(dest, overwrite = true)
                        audioFile.delete()
                    }
                }
                if (dest.exists() && dest.length() > 0L) seconds else null
            }
            else -> null
        }
        carryOverSeconds = 0L
        if (keptSeconds == null) {
            // Nothing usable was kept; make sure no stale offer remains.
            scope.launch { clearInterruptedAudioPref() }
            return
        }
        // Persist the marker + metadata so the offer can be restored even after a process death.
        scope.launch {
            prefs.dictate.interruptedAudioSeconds.set(keptSeconds)
            prefs.dictate.interruptedAudioLive.set(wasLive)
            prefs.dictate.interruptedAudioPending.set(true)
        }
    }

    /**
     * On keyboard open, restores the "recording interrupted — send it?" offer if an interrupted audio
     * file is waiting. Returns true when the offer is now shown, so the caller can skip instant-recording.
     * No-op unless idle. A stale marker without a usable file is cleared.
     */
    fun maybeOfferInterruptedRecording(context: Context): Boolean {
        if (_state.value !is UiState.Idle) return false
        if (!prefs.dictate.interruptedAudioPending.get()) return false
        val file = interruptedAudioFile(context)
        if (!file.exists() || file.length() == 0L) {
            scope.launch { clearInterruptedAudioPref() }
            return false
        }
        val seconds = prefs.dictate.interruptedAudioSeconds.get()
        retained = RetainedAudio(file, RetainReason.INTERRUPTED, prefs.dictate.interruptedAudioLive.get(), seconds)
        _state.value = UiState.Interrupted(seconds)
        return true
    }

    /** Clears the persisted interrupted-audio marker (best-effort; the file itself is handled separately). */
    private suspend fun clearInterruptedAudioPref() {
        if (prefs.dictate.interruptedAudioPending.get()) {
            prefs.dictate.interruptedAudioPending.set(false)
        }
    }

    // --- Re-insert last dictation (issue #111) --------------------------------------------------

    /**
     * Persists [text] as the last successful dictation so the "Re-insert last dictation" Smartbar action
     * can recover it after the field is cleared (rotation, context switch, host app refreshing its
     * state). No-op when the feature is off or the text is blank. Held until the next successful
     * dictation overwrites it; stored to a pref so it survives the IME process being killed.
     */
    private suspend fun rememberLastDictation(text: String) {
        if (!prefs.dictate.rememberLastDictation.get() || text.isBlank()) return
        prefs.dictate.lastDictation.set(text)
    }

    /**
     * Whether a re-insertable last dictation exists (feature enabled and a non-empty cache). Read
     * synchronously by the Smartbar action's enabled-state evaluation, so the button greys out when
     * there is nothing to re-insert.
     */
    fun hasLastDictation(): Boolean =
        prefs.dictate.rememberLastDictation.get() && prefs.dictate.lastDictation.get().isNotEmpty()

    /**
     * Re-inserts the last successful dictation into the focused field (issue #111). The cached text is
     * committed verbatim (no auto-formatting/auto-enter, which already ran on the original) and is kept,
     * so it can be re-inserted repeatedly until the next dictation replaces it. No-op while a
     * recording/transcription/rewording is in flight, or when there is nothing cached.
     */
    fun reinsertLastDictation(context: Context) {
        if (_state.value is UiState.Recording || _state.value is UiState.Transcribing ||
            _state.value is UiState.Rewording
        ) return
        if (!prefs.dictate.rememberLastDictation.get()) return
        val text = prefs.dictate.lastDictation.get()
        if (text.isEmpty()) return
        sink(context).commitText(text)
        clearError()
    }

    /**
     * Undo (issue #133): removes the last successful dictation from the focused field again — the
     * inverse of [reinsertLastDictation]. Used by the floating button's optional undo control, so it
     * outputs through the overlay sink. No-op while a recording/transcription/rewording is in flight,
     * or when nothing is cached. On success the cache is cleared so a second tap can't delete unrelated
     * text. Returns true when the field accepted the removal.
     */
    fun undoLastDictation(context: Context): Boolean {
        if (_state.value is UiState.Recording || _state.value is UiState.Transcribing ||
            _state.value is UiState.Rewording
        ) return false
        if (!prefs.dictate.rememberLastDictation.get()) return false
        val text = prefs.dictate.lastDictation.get()
        if (text.isEmpty()) return false
        outputTarget = OutputTarget.OVERLAY
        if (!sink(context).deleteLastText(text)) return false
        scope.launch { prefs.dictate.lastDictation.set("") }
        clearError()
        return true
    }

    // --- Rate / Donate nudges (roadmap 9.7/9.8) -------------------------------------------------

    /**
     * Shows a one-time rate or donate nudge in the Smartbar once the user has accumulated enough
     * transcribed audio, mirroring the legacy app: rate after [RATE_THRESHOLD_SECONDS], donate after
     * [DONATE_THRESHOLD_SECONDS]. Each is shown until acted on (a flag is then set); accepting or
     * declining donate also marks rate as done, so a donor is never asked to rate. No-op unless idle.
     * Called when the keyboard appears so it never interrupts an in-flight recording/transcription.
     */
    fun maybePromptForReview() {
        if (_state.value !is UiState.Idle) return
        val total = prefs.dictate.totalAudioSeconds.get()
        val kind = when {
            total > DONATE_THRESHOLD_SECONDS && !prefs.dictate.hasDonated.get() -> PromoKind.DONATE
            total > RATE_THRESHOLD_SECONDS && total <= DONATE_THRESHOLD_SECONDS && !prefs.dictate.hasRated.get() -> PromoKind.RATE
            else -> return
        }
        _state.value = UiState.Promo(kind)
    }

    /**
     * If a saved-time / dictation-count milestone is pending (issue #142), shows it as a one-time Smartbar
     * celebration and returns true (consuming the pending marker). Returns false — leaving anything pending
     * intact — when idle-state is not held or nothing is pending / celebrations are off.
     */
    private suspend fun showMilestoneNudge(context: Context): Boolean {
        if (_state.value !is UiState.Idle) return false
        val milestone = DictateStats.consumePendingMilestone(prefs) ?: return false
        _state.value = UiState.Promo(PromoKind.MILESTONE, message = milestoneMessage(context, milestone))
        return true
    }

    /** Short, single-line celebration text for the milestone nudge (kept compact for the Smartbar). */
    private fun milestoneMessage(context: Context, milestone: DictateStats.Milestone): String = when (milestone.kind) {
        DictateStats.Milestone.Kind.TIME_MINUTES ->
            context.getString(R.string.dictate__promo_milestone_time, "${milestone.value / 60}h")
        DictateStats.Milestone.Kind.DICTATIONS ->
            context.getString(R.string.dictate__promo_milestone_count, NumberFormat.getIntegerInstance().format(milestone.value))
    }

    /**
     * Shows a one-time "Dictate was updated" nudge in the Smartbar right after an app update, so users
     * who rarely open the settings still learn about new versions and can jump straight to the changelog.
     * Tapping it opens the app, where the "What's new" dialog appears (it shares the same
     * [AppVersionUtils.shouldShowChangelog] gate). A dedicated per-version flag
     * ([dev.patrickgold.florisboard.app.AppPrefs.Dictate.changelogNudgeVersion]) keeps the keyboard nudge
     * from reappearing without suppressing the in-app dialog, and vice versa. No-op unless idle.
     */
    fun maybePromptChangelog(context: Context) {
        if (_state.value !is UiState.Idle) return
        if (!DEBUG_FORCE_CHANGELOG_NUDGE) {
            if (!AppVersionUtils.shouldShowChangelog(context, prefs)) return
            if (prefs.dictate.changelogNudgeVersion.get() == BuildConfig.VERSION_NAME) return
        }
        _state.value = UiState.Promo(PromoKind.CHANGELOG)
    }

    /**
     * Shows a one-time Smartbar spotlight for the floating dictation button to users who have not enabled
     * it yet, so existing users discover the feature. Tapping it deep-links straight to the floating-button
     * settings screen (where the accessibility opt-in + disclosure live — it is never auto-enabled).
     * Gated by a per-version flag; skipped once the user has enabled it or opened its screen. No-op unless idle.
     */
    fun maybePromptFloatingButton(context: Context) {
        if (_state.value !is UiState.Idle) return
        if (!DEBUG_FORCE_FB_SPOTLIGHT) {
            if (prefs.dictate.floatingButtonEnabled.get() || prefs.dictate.floatingButtonHintSeen.get()) return
            if (prefs.dictate.floatingButtonSpotlightVersion.get() == BuildConfig.VERSION_NAME) return
        }
        _state.value = UiState.Promo(PromoKind.FLOATING_BUTTON)
    }

    /**
     * Acts on the active promo and marks it done: RATE/DONATE open the Play Store / PayPal page,
     * CHANGELOG opens the app (which then shows the "What's new" dialog), FLOATING_BUTTON deep-links to its
     * settings screen. No-op otherwise.
     */
    fun acceptPromo(context: Context) {
        val kind = (_state.value as? UiState.Promo)?.kind ?: return
        runCatching {
            val intent = when (kind) {
                PromoKind.RATE -> Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=net.devemperor.dictate"))
                PromoKind.DONATE -> Intent(Intent.ACTION_VIEW, Uri.parse("https://paypal.me/DevEmperor"))
                PromoKind.CHANGELOG -> Intent(context, FlorisAppActivity::class.java)
                PromoKind.FLOATING_BUTTON -> Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("ui://florisboard/settings/dictate/floating-button"),
                    context,
                    FlorisAppActivity::class.java,
                ).addCategory(Intent.CATEGORY_BROWSABLE)
                PromoKind.MILESTONE -> Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("ui://florisboard/settings/dictate/stats"),
                    context,
                    FlorisAppActivity::class.java,
                ).addCategory(Intent.CATEGORY_BROWSABLE)
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        markPromoDone(kind)
        _state.value = UiState.Idle
    }

    /** Dismisses the active promo without opening anything, but still marks it done. No-op otherwise. */
    fun declinePromo() {
        val kind = (_state.value as? UiState.Promo)?.kind ?: return
        markPromoDone(kind)
        _state.value = UiState.Idle
    }

    /** Persists the "handled" flags: declining/accepting donate also marks rate as done. */
    private fun markPromoDone(kind: PromoKind) {
        scope.launch {
            when (kind) {
                PromoKind.RATE -> prefs.dictate.hasRated.set(true)
                PromoKind.DONATE -> {
                    prefs.dictate.hasDonated.set(true)
                    prefs.dictate.hasRated.set(true)
                }
                // Remember this version so the keyboard nudge shows only once per update. The in-app
                // dialog stays governed by versionLastChangelog, so tapping/dismissing here never hides it.
                PromoKind.CHANGELOG -> prefs.dictate.changelogNudgeVersion.set(BuildConfig.VERSION_NAME)
                // Show the floating-button spotlight only once per version.
                PromoKind.FLOATING_BUTTON -> prefs.dictate.floatingButtonSpotlightVersion.set(BuildConfig.VERSION_NAME)
                // The milestone was already consumed when shown; nothing further to persist.
                PromoKind.MILESTONE -> Unit
            }
        }
    }

    /**
     * Adds [seconds] to the cumulative audio counter that gates the nudges. Suspends until the new value
     * is written to the in-memory cache, so a [maybePromptForReview] called right after sees the update.
     */
    private suspend fun creditAudioSeconds(seconds: Long) {
        prefs.dictate.totalAudioSeconds.set(prefs.dictate.totalAudioSeconds.get() + seconds)
    }

    // --- Rewording / GPT engine (roadmap section 4) ---------------------------------------------

    /**
     * Applies a rewording [prompt] and commits the result. Used by the prompt chips (Phase 3):
     *  - a `[snippet]` prompt (text wrapped in brackets) is inserted literally, no API call;
     *  - a `requiresSelection` prompt operates on [selectionOverride] (or the current selection) and
     *    replaces it with the reworded result;
     *  - a free prompt generates from the instruction alone and inserts at the cursor.
     * No-op unless idle (or recovering from a transient error).
     */
    fun applyPrompt(
        context: Context,
        prompt: PromptModel,
        selectionOverride: String? = null,
        target: OutputTarget? = null,
    ) {
        if (_state.value !is UiState.Idle && _state.value !is UiState.Error) return
        // The floating overlay passes OVERLAY so the result is injected into the focused field via the
        // accessibility sink rather than the keyboard's editor.
        if (target != null) outputTarget = target
        val appContext = context.applicationContext
        val sink = sink(appContext)
        val raw = prompt.prompt.orEmpty()

        // Snippet shortcut: text wrapped in [...] is inserted literally (no network call).
        if (raw.length >= 2 && raw.startsWith("[") && raw.endsWith("]")) {
            sink.commitText(raw.substring(1, raw.length - 1))
            return
        }

        val input: String? = when {
            selectionOverride != null -> selectionOverride
            !prompt.requiresSelection -> null
            else -> {
                val selected = sink.selectedText()
                if (selected.isNotEmpty()) {
                    selected
                } else {
                    // Nothing selected: select the whole field (so the user sees what gets reworded)
                    // and operate on its full text. The reworded result then replaces the now-selected
                    // content via commitText. Matches the "tap a prompt with no selection" flow.
                    val whole = sink.fullText()
                    if (whole.isBlank()) return // empty field – nothing to operate on
                    sink.selectAll()
                    whole
                }
            }
        }

        if (rewordingApiKey().isBlank()) {
            _state.value = UiState.Error(
                message = appContext.getString(R.string.dictate__error_no_api_key),
                kind = DictateApiException.Kind.INVALID_API_KEY,
                action = ErrorAction.OPEN_SETTINGS,
            )
            return
        }

        _state.value = UiState.Rewording(prompt.name ?: appContext.getString(R.string.dictate__status_rewording))
        scope.launch {
            try {
                val text = requestReword(raw, input)
                // commitText replaces the active selection if any, else inserts at the cursor.
                sink.commitText(text)
                _state.value = UiState.Idle
            } catch (e: DictateApiException) {
                _state.value = apiError(e, appContext, canResend = false)
            } catch (t: Throwable) {
                _state.value = UiState.Error(
                    message = appContext.getString(R.string.dictate__error_rewording_failed),
                    kind = DictateApiException.Kind.UNKNOWN,
                    detail = t.message?.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    /**
     * Starts (or stops) a *live prompt* recording: the spoken transcript is sent to the rewording
     * model as an instruction instead of being inserted verbatim. Toggles like the mic button.
     */
    fun startLivePrompt(context: Context) {
        when (_state.value) {
            is UiState.Recording -> {
                livePromptArmed = true
                stopAndTranscribe(context)
            }
            is UiState.Transcribing, is UiState.Rewording -> Unit
            else -> {
                livePromptArmed = true
                startRecording(context)
            }
        }
    }

    /**
     * Runs the post-transcription rewording chain on [transcript]: optional auto-formatting, then the
     * user's auto-apply prompts in order. Each step is best-effort – a failing step keeps the text so
     * far so the user never loses their dictation. Returns the text to commit.
     */
    private suspend fun postProcessTranscript(context: Context, transcript: String): String {
        if (!prefs.dictate.rewordingEnabled.get() || transcript.isBlank()) return transcript
        // No rewording key (not even a shared transcription one) → nothing here can run; return the raw
        // transcript instead of flashing "Formatting…" and looping through doomed throw/catch calls.
        if (rewordingApiKey().isBlank()) return transcript
        var text = transcript

        // 1) Auto-formatting (spoken cues → Markdown). Low-level prompt, no be-precise suffix.
        if (prefs.dictate.autoFormattingEnabled.get()) {
            _state.value = UiState.Rewording(context.getString(R.string.dictate__status_formatting))
            // Hint the model with the readable language name ("German"), or "unknown" for auto-detect.
            val languageName = DictateLanguages.englishNameFor(prefs.dictate.activeInputLanguage.get())
            val formatPrompt = DictatePromptDefaults.buildAutoFormattingPrompt(languageName, text)
            val formatted = runCatching { requestRewordRaw(formatPrompt) }.getOrDefault(text)
            // Safety net (#124): on (near-)empty input the model sometimes echoes the formatting prompt
            // itself instead of returning nothing. If the result looks like that prompt, discard it and keep
            // the original text — the master prompt must never land in the field.
            text = if (formatted.isBlank() || DictatePromptDefaults.looksLikeAutoFormattingPrompt(formatted)) {
                text
            } else {
                formatted
            }
        }

        // 2) Auto-apply prompts, in POS order; each operates on the running text if it needs input.
        val autoApply = withContext(Dispatchers.IO) {
            promptsDb(context).getAll().filter { it.autoApply }
        }
        for (p in autoApply) {
            val instruction = p.prompt.orEmpty()
            if (instruction.isBlank()) continue
            _state.value = UiState.Rewording(p.name ?: context.getString(R.string.dictate__status_rewording))
            text = runCatching {
                requestReword(instruction, if (p.requiresSelection) text else null)
            }.getOrDefault(text)
        }
        return text
    }

    /**
     * Applies the prompts the user queued by tapping the always-on prompt row while recording (ROW
     * layout), in tap order, to the finished [text]. Each step is best-effort (a failing prompt keeps
     * the text so far). `[snippet]` prompts are appended literally; everything else runs through the
     * rewording model (operating on the running text when the prompt requires a selection). Clears the
     * queue (so the highlights disappear) regardless of outcome.
     */
    private suspend fun applyPendingPrompts(context: Context, text: String): String {
        val queued = _pendingPrompts.value
        _pendingPrompts.value = emptyList()
        if (queued.isEmpty()) return text
        if (rewordingApiKey().isBlank()) return text
        var result = text
        for (p in queued) {
            val raw = p.prompt.orEmpty()
            if (raw.isBlank()) continue
            // Snippet shortcut: text wrapped in [...] is appended literally (no network call).
            if (raw.length >= 2 && raw.startsWith("[") && raw.endsWith("]")) {
                result += raw.substring(1, raw.length - 1)
                continue
            }
            _state.value = UiState.Rewording(p.name ?: context.getString(R.string.dictate__status_rewording))
            result = runCatching {
                requestReword(raw, if (p.requiresSelection) result else null)
            }.getOrDefault(result)
        }
        return result
    }

    /**
     * High-level rewording call: builds the user message as `instruction [+ system prompt] [+ input]`
     * (exactly as the legacy app did – the be-precise prompt is tuned for this position) and returns
     * the trimmed model output.
     */
    private suspend fun requestReword(instruction: String, input: String?): String {
        val sys = systemPrompt()
        val content = buildString {
            append(instruction)
            if (sys.isNotBlank()) append("\n\n").append(sys)
            if (!input.isNullOrBlank()) append("\n\n").append(input)
        }
        return requestRewordRaw(content)
    }

    /** Low-level rewording call: sends [userContent] verbatim as a single user message. */
    private suspend fun requestRewordRaw(userContent: String): String {
        val account = rewordingAccount()
        // Blank rewording key falls back to the transcription account's key (legacy "reuse" behavior).
        val apiKey = account.apiKey.ifBlank { transcriptionAccount().apiKey }
        if (apiKey.isBlank() && requiresKey(account)) {
            throw DictateApiException(DictateApiException.Kind.INVALID_API_KEY, "No API key set")
        }
        val preset = presetFor(account)
        val model = account.chatModel.ifBlank { preset.defaultChatModel ?: "gpt-4o-mini" }
        val client = OpenAiCompatibleClient.from(
            preset, apiKey,
            baseUrlOverride = baseUrlOverrideFor(account),
            proxy = prefs.dictate.dictateProxyConfig(),
            trustUserCerts = prefs.dictate.trustUserCertificates.get(),
        )
        val result = client.complete(
            ChatRequest.ofUser(
                model, userContent,
                // Reasoning effort for reasoning models (issue #141); OFF → null → field omitted.
                reasoningEffort = prefs.dictate.rewordingReasoningEffort.get().wire,
            ),
        ).text.trim()
        // Lifetime statistics (issue #142): every rewording/prompt pass funnels through here.
        DictateStats.recordRewording(prefs)
        return result
    }

    private fun systemPrompt(): String = when (prefs.dictate.systemPromptSelection.get()) {
        DictatePromptDefaults.SELECTION_PREDEFINED -> DictatePromptDefaults.REWORDING_BE_PRECISE
        DictatePromptDefaults.SELECTION_CUSTOM -> prefs.dictate.systemPromptCustom.get()
        else -> ""
    }

    /**
     * Style/punctuation prompt sent with the transcription request (independent of rewording). The
     * user's custom words (roadmap 11.12) are appended on top of whichever style prompt is active, so
     * names/jargon are spelled correctly even with the predefined punctuation prompt or with none.
     */
    private fun transcriptionStylePrompt(): String? {
        val base = when (prefs.dictate.stylePromptSelection.get()) {
            DictatePromptDefaults.SELECTION_PREDEFINED ->
                DictatePromptDefaults.punctuationPromptFor(prefs.dictate.activeInputLanguage.get())
            DictatePromptDefaults.SELECTION_CUSTOM ->
                prefs.dictate.stylePromptCustom.get().takeIf { it.isNotBlank() }
            else -> null
        }
        return DictatePromptDefaults.appendCustomWords(base, prefs.dictate.customWords.get())
    }

    /**
     * The instruction sent alongside the audio in the single-call multimodal path (issue #130). Folds
     * everything the two-call flow would otherwise do into one prompt: the spoken language (readable
     * name), the style/punctuation prompt + custom words, and — when rewording is enabled — the
     * auto-formatting rules and the user's auto-apply prompts. The client prepends a "transcribe, return
     * only the text" preamble.
     */
    private suspend fun buildChatAudioInstruction(context: Context): String {
        val parts = mutableListOf<String>()
        val langCode = prefs.dictate.activeInputLanguage.get()
        if (langCode != DictateLanguages.DETECT) {
            // Source-language hint only (not an output directive) so it never fights a "translate to X"
            // rewording prompt — the weaker models otherwise just echo the spoken language.
            DictateLanguages.englishNameFor(langCode)?.takeIf { it.isNotBlank() }
                ?.let { parts.add("The audio is spoken in $it.") }
        }
        transcriptionStylePrompt()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        // Formatting/rewording is folded in only when the user has rewording enabled (mirrors
        // postProcessTranscript's gating), so single-call output matches the two-call output.
        if (prefs.dictate.rewordingEnabled.get()) {
            if (prefs.dictate.autoFormattingEnabled.get()) {
                parts.add(DictatePromptDefaults.AUTO_FORMATTING_PROMPT)
            }
            val autoApply = withContext(Dispatchers.IO) {
                promptsDb(context).getAll().filter { it.autoApply }
            }
            autoApply.forEach { p -> p.prompt?.takeIf { it.isNotBlank() }?.let { parts.add(it) } }
        }
        return parts.joinToString("\n\n")
    }

    /** The active transcription provider's stored credentials (keyring). */
    private fun transcriptionAccount(): ProviderAccount {
        val id = prefs.dictate.transcriptionProviderId.get()
        return prefs.dictate.providerAccounts.get().getOrEmpty(id)
    }

    /** The active rewording provider's stored credentials (keyring). */
    private fun rewordingAccount(): ProviderAccount {
        val id = prefs.dictate.rewordingProviderId.get()
        return prefs.dictate.providerAccounts.get().getOrEmpty(id)
    }

    /** Effective rewording key: the rewording account's, falling back to the transcription account's. */
    private fun rewordingApiKey(): String =
        rewordingAccount().apiKey.ifBlank { transcriptionAccount().apiKey }

    private fun promptsDb(context: Context) = PromptsDatabaseHelper.getInstance(context)

    private fun requestAudioFocusIfEnabled(context: Context) {
        if (!prefs.dictate.audioFocus.get()) return
        val am = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).also { audioManager = it }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) {
                    // Another app permanently took focus: pause the recording so we don't fight it.
                    val current = _state.value
                    if (current is UiState.Recording && !current.paused) togglePause()
                }
            }
            .build()
        focusRequest = request
        am.requestAudioFocus(request)
    }

    private suspend fun setupBluetoothIfEnabled(context: Context): Int {
        // Non-Bluetooth path uses the user's chosen audio source (issue #62); Bluetooth SCO always needs
        // VOICE_COMMUNICATION. If BT is requested but can't be activated, fall back to the chosen source.
        val localSource = prefs.dictate.audioInputSource.get().resolve(context)
        if (!prefs.dictate.useBluetoothMic.get()) return localSource
        val router = BluetoothMicRouter(context).also { btRouter = it }
        return if (router.activate()) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            localSource
        }
    }

    private fun cleanupAudioRouting() {
        focusRequest?.let { request -> audioManager?.abandonAudioFocusRequest(request) }
        focusRequest = null
        audioManager = null
        btRouter?.deactivate()
        btRouter = null
    }

    /** Resolves the registry preset (base URL, defaults, headers) backing [account]. */
    private fun presetFor(account: ProviderAccount): ProviderPreset = when {
        account.isCustom -> ProviderRegistry.custom(account.customBaseUrl)
        else -> ProviderRegistry.byId(account.providerId) ?: ProviderRegistry.OPENAI
    }

    /**
     * The account's own base URL, when it has one: custom endpoints always, and base-URL-editable
     * built-ins like Ollama (issue #136). Null → the preset's default base URL is used.
     */
    private fun baseUrlOverrideFor(account: ProviderAccount): String? =
        if (account.isCustom || presetFor(account).allowsCustomBaseUrl) {
            account.customBaseUrl.takeIf { it.isNotBlank() }
        } else {
            null
        }

    /** Whether [account] needs an API key: built-in cloud providers do; custom/local servers may not. */
    private fun requiresKey(account: ProviderAccount): Boolean =
        !account.isCustom && presetFor(account).apiKeyUrl != null

    /**
     * The on-device provider to retry [error] on as an offline fallback (#104), or null when it doesn't
     * apply: the fallback is disabled, the failure isn't a connectivity one, the active provider is
     * already local, or no local model is downloaded.
     */
    private fun localFallbackProvider(
        context: Context,
        activePreset: ProviderPreset,
        error: DictateApiException,
    ): LocalTranscriptionProvider? {
        if (!prefs.dictate.localFallbackEnabled.get()) return null
        if (activePreset.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE) return null
        if (error.kind != DictateApiException.Kind.NETWORK &&
            error.kind != DictateApiException.Kind.TIMEOUT
        ) return null
        val localModel = prefs.dictate.providerAccounts.get().getOrEmpty(ProviderRegistry.LOCAL.id)
            .transcriptionModel.takeIf { it.isNotBlank() }
            ?: ProviderRegistry.LOCAL.defaultTranscriptionModel
            ?: return null
        if (!LocalModelManager.isInstalled(context, localModel)) return null
        return LocalTranscriptionProvider(LocalTranscriptionProvider.modelDir(context, localModel))
    }
}
