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

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.IntentFilter
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
import dev.patrickgold.florisboard.dictate.audio.AudioDecode
import dev.patrickgold.florisboard.dictate.audio.AudioLevelSmoother
import dev.patrickgold.florisboard.dictate.audio.BluetoothMicRouter
import dev.patrickgold.florisboard.dictate.audio.LiveSpeechSplitter
import dev.patrickgold.florisboard.dictate.audio.Pcm16Resampler
import dev.patrickgold.florisboard.dictate.audio.RecordingController
import dev.patrickgold.florisboard.dictate.audio.SpeechGate
import dev.patrickgold.florisboard.dictate.data.prompts.DictatePromptDefaults
import dev.patrickgold.florisboard.dictate.data.prompts.PromptModel
import dev.patrickgold.florisboard.dictate.data.prompts.PromptsDatabaseHelper
import dev.patrickgold.florisboard.dictate.data.history.DictateHistoryEntry
import dev.patrickgold.florisboard.dictate.data.history.DictateHistorySource
import dev.patrickgold.florisboard.dictate.data.history.DictateHistoryStore
import dev.patrickgold.florisboard.dictate.data.stats.DictateStats
import dev.patrickgold.florisboard.dictate.provider.ChatRequest
import dev.patrickgold.florisboard.dictate.provider.DictateApiException
import dev.patrickgold.florisboard.dictate.provider.LocalModelManager
import dev.patrickgold.florisboard.dictate.provider.LocalTranscriptionProvider
import dev.patrickgold.florisboard.dictate.provider.OpenAiCompatibleClient
import dev.patrickgold.florisboard.dictate.provider.RealtimeApi
import dev.patrickgold.florisboard.dictate.provider.RealtimeCallbacks
import dev.patrickgold.florisboard.dictate.provider.RealtimeClient
import dev.patrickgold.florisboard.dictate.provider.RealtimeSession
import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderPreset
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.dictate.provider.TranscriptionApi
import dev.patrickgold.florisboard.dictate.provider.TranscriptionRequest
import dev.patrickgold.florisboard.dictate.overlay.AccessibilitySink
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.util.AppVersionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
            /** Informational (not a failure), e.g. "no speech detected" — rendered neutral, not red. */
            val neutral: Boolean = false,
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

    private val _livePromptActive = MutableStateFlow(false)
    /**
     * True while a *live-prompt* recording is in progress, so the live-prompt chip can show the same
     * accent highlight the queued prompt chips use. Tapping the chip again stops the recording (toggle),
     * which clears this. Set/cleared alongside the recording lifecycle.
     */
    val livePromptActive: StateFlow<Boolean> = _livePromptActive.asStateFlow()

    // --- Real-time streaming transcription (issue #128) -----------------------------------------
    private val _interimText = MutableStateFlow("")
    /**
     * Live transcript while a real-time recording runs: finalized segments plus the current partial. The
     * Smartbar shows this as a live caption; IME fields receive a coalesced preview which final commit
     * replaces with the finished (reworded) text on stop. Empty outside a realtime recording.
     */
    val interimText: StateFlow<String> = _interimText.asStateFlow()

    private var realtimeSession: RealtimeSession? = null
    private val realtimeTextLock = Any()
    private val realtimeFinal = StringBuilder()      // accumulated finalized segments
    @Volatile private var realtimeFailed = false     // stream errored → fall back to batch on stop
    private var realtimeClosed: CompletableDeferred<Unit>? = null
    private var realtimeContext: Context? = null     // app context to edit the field's provisional text
    private val realtimeShown = StringBuilder()       // text currently committed to the field this session
    private var realtimePreviewJob: Job? = null
    @Volatile private var realtimePreviewLatest = ""  // newest transcript received, even if not flushed yet
    private var realtimePreviewLastFlushMs = 0L
    @Volatile private var realtimeCancelled = false   // block late stream callbacks from re-adding text

    // --- Long-form segmented dictation (issue #170) ---------------------------------------------
    // Segmented mode transcribes cut segments in the background while recording continues, appending raw
    // text to the field as a live preview (reusing [realtimeShown] as the shown-text buffer, since
    // segmented and realtime are mutually exclusive). All formatting/rewording runs ONCE at the end via
    // finalizeAndCommit(finalizeViaComposing=true), which replaces the preview with the finished text.
    private var segmentedActive = false
    private var segmentNextIndex = 0          // next index to assign to a cut segment (cut order)
    private var segmentCommitIndex = 0        // next index expected by the ordered commit drain
    private val segmentResults = HashMap<Int, String>()  // index -> raw text, buffered until in-order
    private val segmentJobs = mutableSetOf<Job>()
    private val segmentMutex = Mutex()        // orders index assignment + rotate + the commit drain
    private var segmentInFlightCount = 0      // segments cut but not yet committed
    private var segmentStopped = false        // stop requested; finish once the queue drains
    private var segmentRecordedSeconds = 0L
    private var segmentVad: LiveSpeechSplitter? = null  // live VAD auto-split, when enabled (Phase 2)
    private val segmentAudioFiles = HashMap<Int, File>()  // kept segment WAVs (index -> file) for history merge
    private var segmentKeepAudio = false                  // whether to keep + merge segment audio (retention on)
    private val _segmentFlushCount = MutableStateFlow(0)
    /** Monotonic count of segment cuts — the recording bar flashes the Next button on each change (#170). */
    val segmentFlushCount: StateFlow<Int> = _segmentFlushCount.asStateFlow()
    private val _segmentedRecording = MutableStateFlow(false)
    /** True while a long-form segmented recording is active — drives the "Next segment" button (#170). */
    val segmentedRecording: StateFlow<Boolean> = _segmentedRecording.asStateFlow()
    private val _segmentsInFlight = MutableStateFlow(0)
    /** How many cut segments are transcribing in the background — drives the recording-bar badge (#170). */
    val segmentsInFlight: StateFlow<Int> = _segmentsInFlight.asStateFlow()

    private var recorder: RecordingController? = null
    private var startJob: Job? = null

    private val _audioLevel = MutableStateFlow(0f)
    /**
     * Shared, noise-gated microphone level for lightweight recording visuals. Sampling once here keeps
     * the Smartbar and floating overlay from competing for [RecordingController.maxAmplitude]'s
     * read-and-reset peak. Values are smoothed and normalized to 0..1 at 20 Hz.
     */
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()
    private var audioLevelJob: Job? = null

    // While a recording is active we listen for the screen turning off (device locked / display timeout):
    // that is the reliable "the user has left" signal that finalizes and keeps the recording and releases
    // the mic, instead of depending on IME teardown callbacks that are not always delivered (issue #147).
    // Long recordings stay possible — this never fires while the screen is on. Held at process scope
    // (alongside the recorder) with the context used to register it, so any stop path can unregister.
    private var screenOffReceiver: BroadcastReceiver? = null
    private var screenOffContext: Context? = null

    /** The in-flight transcription coroutine, cancellable via the stop button (see [cancelTranscription]). */
    private var transcribeJob: Job? = null

    // The in-flight manual rewording coroutine (a prompt chip / "Send"), so the stop button can abort it
    // mid-generation (issue #192). The post-transcription rewording chain instead runs inside
    // [transcribeJob]; [cancelRewording] cancels whichever is active.
    private var rewordJob: Job? = null

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var btRouter: BluetoothMicRouter? = null

    /** When true, the next finished recording is fed to the rewording model instead of committed. */
    private var livePromptArmed = false

    /** Output destination of the in-flight dictation; see [OutputTarget]. Reset to IME when idle. */
    private var outputTarget = OutputTarget.IME

    // Haptic feedback (#166) fires on dictation state transitions. Started lazily on the first dictation
    // (so we have an application context for the vibrator), then it observes for the whole process life.
    private var hapticObserverStarted = false
    private fun ensureHapticObserver(context: Context) {
        if (hapticObserverStarted) return
        hapticObserverStarted = true
        val appContext = context.applicationContext
        // Capture the current state synchronously (the caller is about to change it): the launched
        // collector otherwise reads _state.value only after the change and would miss the first transition.
        val initial = _state.value
        scope.launch {
            var prev: UiState = initial
            _state.collect { new ->
                when {
                    // Record started — skipped for the floating button when its own tap already buzzed
                    // (no double buzz); a resend enters at Transcribing so it never matches here.
                    new is UiState.Recording && prev !is UiState.Recording -> {
                        val buttonAlreadyBuzzed = outputTarget == OutputTarget.OVERLAY &&
                            prefs.dictate.floatingButtonHaptic.get()
                        if (!buttonAlreadyBuzzed) DictateHaptics.short(appContext)
                    }
                    // Record stopped → transcribing (a resend is Idle→Transcribing and is ignored).
                    prev is UiState.Recording && new is UiState.Transcribing -> DictateHaptics.short(appContext)
                    // Transcription ready (heading to commit/idle or on to a rewording pass) — not on failure.
                    prev is UiState.Transcribing && (new is UiState.Idle || new is UiState.Rewording) ->
                        DictateHaptics.double(appContext)
                    // Rewording / LLM prompt applied.
                    prev is UiState.Rewording && new is UiState.Idle -> DictateHaptics.medium(appContext)
                }
                prev = new
            }
        }
    }

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
     * Metadata threaded from a transcription into [finalizeAndCommit] so the finished dictation can be
     * logged to the history store (issue #140). [audioFile] is the (still-present) recorded WAV to retain
     * when audio retention is on. [isReplay] marks a re-transcription of already-counted audio so stats
     * aren't double-counted, and [replayHistoryId] (when set) updates that existing entry's text in place
     * instead of inserting a new row.
     */
    private data class HistoryCapture(
        val audioFile: File?,
        val providerId: String,
        val providerName: String,
        val model: String,
        val language: String,
        val source: String,
        val isReplay: Boolean = false,
        val replayHistoryId: Long? = null,
    )

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
    // Realtime (#128): cap IME preview writes to about one editor update per display frame.
    private const val REALTIME_PREVIEW_FRAME_MS = 66L
    // Realtime (#128): after finish(), how long to wait for the provider to flush the last words before we
    // commit the already-streamed text. Short — the text is already on screen; we only wait for the tail.
    private const val REALTIME_FINALIZE_TIMEOUT_MS = 1_200L

    /** 20 Hz is responsive for a voice indicator while avoiding a display-rate UI loop. */
    private const val AUDIO_LEVEL_SAMPLE_MS = 50L

    private fun cancelRealtimePreviewJob() {
        realtimePreviewJob?.cancel()
        realtimePreviewJob = null
    }

    private fun resetRealtimePreviewScheduler() {
        cancelRealtimePreviewJob()
        synchronized(realtimeTextLock) {
            realtimePreviewLatest = ""
        }
        realtimePreviewLastFlushMs = 0L
    }

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
            // Tapping the mic while transcribing or rewording aborts it (the button shows a stop icon,
            // see the ComputingEvaluator) — e.g. after accidentally sending a prompt (issue #192).
            is UiState.Transcribing -> cancelTranscription()
            is UiState.Rewording -> cancelRewording()
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
     * Snaps [activeInputLanguage] back into the current [inputLanguages] selection. A stale active
     * code — most importantly "detect" left over after the user disabled auto-detect — would otherwise
     * keep the transcription request on auto-detect (language = null, so e.g. Portuguese gets detected
     * as English) and show a phantom globe on the recording bar. Falls back to the first selected
     * language and persists the correction. No-op when the active code is already selected.
     */
    private suspend fun reconcileActiveLanguage() {
        val selection = DictateLanguages.parseSelection(prefs.dictate.inputLanguages.get())
        if (selection.isEmpty()) return
        val current = prefs.dictate.activeInputLanguage.get()
        if (selection.none { it.code == current }) {
            prefs.dictate.activeInputLanguage.set(selection.first().code)
        }
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
        // Long-form segmented (#170): abort the background segment transcriptions; the realtime cleanup
        // below removes the progressively-shown preview text (segmented reuses realtimeShown/Context).
        if (segmentedActive) {
            segmentJobs.forEach { it.cancel() }
            segmentJobs.clear()
            segmentAudioFiles.values.forEach { runCatching { it.delete() } }
            resetSegmentedState()
        }
        // Tear down any realtime stream (#128) and remove the live provisional text from the field. Set the
        // cancelled flag first so any stream callback still queued on the main thread can't re-add the text.
        realtimeCancelled = true
        resetRealtimePreviewScheduler()
        realtimeSession?.cancel()
        realtimeSession = null
        realtimeClosed = null
        _interimText.value = ""
        realtimeContext?.let { ctx -> runCatching { sink(ctx).clearDictationPreview(realtimeShown.toString()) } }
        realtimeShown.setLength(0)
        realtimeContext = null
        unregisterScreenOffReceiver()
        cleanupAudioRouting()
        livePromptArmed = false
        _livePromptActive.value = false
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
     * Starts listening for [Intent.ACTION_SCREEN_OFF] while a recording is in progress (issue #147). When
     * the screen turns off we treat it exactly like the keyboard being hidden ([stashRecordingOnHide]):
     * the audio is finalized and kept, and the mic is released. This is the dependable catch-all for
     * recordings that would otherwise be orphaned when no IME teardown callback is delivered (abrupt app
     * switch, IME switch, lock). It never fires while the screen is on, so long recordings are unaffected.
     */
    private fun registerScreenOffReceiver(appContext: Context) {
        if (screenOffReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    stashRecordingOnHide(appContext)
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
        }.isSuccess
        if (registered) {
            screenOffReceiver = receiver
            screenOffContext = appContext
        }
    }

    /** Stops listening for screen-off. Called from every path that stops a recording. Idempotent. */
    private fun unregisterScreenOffReceiver() {
        val receiver = screenOffReceiver ?: return
        val ctx = screenOffContext
        screenOffReceiver = null
        screenOffContext = null
        if (ctx != null) runCatching { ctx.unregisterReceiver(receiver) }
    }

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
     * Aborts an in-flight rewording (the stop button shown on the mic while rewording, issue #192).
     * Covers both a manually applied prompt ([rewordJob]) and the post-transcription / live-prompt
     * rewording chain that runs inside [transcribeJob]. Cancelling before the model answer is committed
     * leaves the field untouched — for a "reword the selection" prompt the original text stays selected
     * and intact. No-op outside the rewording state.
     */
    fun cancelRewording() {
        if (_state.value !is UiState.Rewording) return
        rewordJob?.cancel()
        rewordJob = null
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
        ensureHapticObserver(appContext)
        startJob = scope.launch {
            try {
                // Correct any stale active language (e.g. leftover "detect" after auto-detect was
                // disabled) before the realtime session / request reads it.
                reconcileActiveLanguage()
                requestAudioFocusIfEnabled(appContext)
                val audioSource = setupBluetoothIfEnabled(appContext)
                // Long-form segmented dictation (#170): transcribe cut segments in the background while
                // recording continues. Off for realtime / live-prompt / overlay / multimodal (see the gate).
                val segmented = isSegmentedMode()
                // Auto-split (Phase 2): a live VAD watches the mic and cuts a segment on a long pause.
                segmentVad?.release()
                segmentVad = if (segmented && prefs.dictate.longformMode.get() == DictateLongformMode.AUTO) {
                    LiveSpeechSplitter(
                        appContext,
                        prefs.dictate.longformAutoSplitSeconds.get() * 1000,
                    ) { flushSegment(appContext) }.also { it.start() }
                } else null
                // The mic PCM tap: the realtime session (batch mode), the VAD splitter (auto-split), or none.
                val pcmSink: ((ByteArray, Int) -> Unit)? = when {
                    !segmented -> openRealtimeSession(appContext)
                    segmentVad != null -> { val v = segmentVad!!; { pcm, len -> v.feed(pcm, len) } }
                    else -> null
                }
                recorder = RecordingController(appContext).also { it.start(audioSource, pcmSink) }
                _state.value = UiState.Recording(SystemClock.elapsedRealtime(), accumulatedMs = seedAccumulatedMs)
                startAudioLevelSampling()
                // Highlight the live-prompt chip for the duration of a live-prompt recording.
                _livePromptActive.value = livePromptArmed
                if (segmented) initSegmented(appContext)
                registerScreenOffReceiver(appContext)
            } catch (t: Throwable) {
                recorder = null
                segmentVad?.release()
                segmentVad = null
                _livePromptActive.value = false
                cleanupAudioRouting()
                _state.value = UiState.Error(
                    // Most common cause is the missing RECORD_AUDIO permission (granted in onboarding).
                    appContext.getString(R.string.dictate__error_recording_failed, t.message ?: ""),
                )
            }
        }
    }

    /** Samples the recorder once for all UI consumers; the job ends itself with the recording state. */
    private fun startAudioLevelSampling() {
        audioLevelJob?.cancel()
        val smoother = AudioLevelSmoother()
        audioLevelJob = scope.launch {
            while (_state.value is UiState.Recording) {
                val recording = _state.value as UiState.Recording
                _audioLevel.value = if (recording.paused) {
                    smoother.reset()
                } else {
                    smoother.update(recorder?.maxAmplitude() ?: 0)
                }
                delay(AUDIO_LEVEL_SAMPLE_MS)
            }
            _audioLevel.value = smoother.reset()
        }
    }

    private fun stopAndTranscribe(context: Context) {
        // Long-form segmented (#170): finish the segment queue instead of uploading one big file.
        if (segmentedActive) {
            stopSegmentedAndFinalize(context)
            return
        }
        // Real-time recording (#128): finalize the stream instead of uploading the whole file.
        if (realtimeSession != null) {
            stopRealtimeAndFinalize(context)
            return
        }
        val activeRecorder = recorder
        recorder = null
        _livePromptActive.value = false
        unregisterScreenOffReceiver()
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
        // A deliberately picked file is transcribed as-is (no silence gate — see issue #93).
        transcribe(context, claimed, gate = false, source = DictateHistorySource.IMPORT)
        return true
    }

    /**
     * Shared transcription path for both recorded and picked audio: resolves provider/key/model,
     * uploads [audioFile], commits the result and deletes the file afterwards.
     */
    private fun transcribe(
        context: Context,
        audioFile: File,
        recordedSeconds: Long = 0L,
        gate: Boolean = true,
        // History (issue #140): [isReplay] re-transcribes already-counted audio (skip stats),
        // [replayHistoryId] updates that stored entry's text in place, [source] tags the origin.
        isReplay: Boolean = false,
        source: String = DictateHistorySource.KEYBOARD,
        replayHistoryId: Long? = null,
    ) {
        val account = transcriptionAccount()
        val apiKey = account.apiKey
        val preset = presetFor(account)
        val model = account.transcriptionModel.takeIf { it.isNotBlank() }
            ?: preset.defaultTranscriptionModel
            ?: "gpt-4o-mini-transcribe"
        val appContext = context.applicationContext
        // History metadata (issue #140), resolved once so the success (capture) and EVERY failure path —
        // including the early returns below (no key / model not downloaded) — log the same info.
        val historyProviderName = account.displayName.ifBlank { preset.displayName }
        val historyLanguage = prefs.dictate.activeInputLanguage.get().takeIf { it != DictateLanguages.DETECT } ?: ""
        val historySource = if (outputTarget == OutputTarget.OVERLAY) DictateHistorySource.OVERLAY else source
        // Logs the failed dictation with its audio (safety net, issue #140) unless this is a replay, then
        // drops the cache file. Async so the early-return callers don't block.
        fun logFailureAndDrop() {
            if (replayHistoryId != null) {
                audioFile.delete()
                return
            }
            scope.launch {
                recordFailedHistory(appContext, audioFile, account.providerId, historyProviderName, model, historyLanguage, recordedSeconds, historySource)
                if (audioFile.exists()) audioFile.delete()
            }
        }

        if (apiKey.isBlank() && requiresKey(account)) {
            _state.value = UiState.Error(
                message = context.getString(R.string.dictate__error_no_api_key),
                kind = DictateApiException.Kind.INVALID_API_KEY,
                action = ErrorAction.OPEN_SETTINGS,
            )
            logFailureAndDrop()
            return
        }

        // On-device (#104): guide the user to download a model instead of failing mid-transcription.
        if (preset.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE &&
            !LocalModelManager.isInstalled(appContext, model)
        ) {
            _state.value = UiState.Error(
                message = context.getString(R.string.dictate__local_model_not_installed_error),
                kind = DictateApiException.Kind.UNKNOWN,
                action = ErrorAction.OPEN_SETTINGS,
            )
            logFailureAndDrop()
            return
        }

        ensureHapticObserver(appContext)
        _state.value = UiState.Transcribing()
        // Live prompt is consumed by this transcription only (the next recording is normal again).
        val live = livePromptArmed
        livePromptArmed = false
        transcribeJob = scope.launch {
            var keepAudio = false
            try {
                reconcileActiveLanguage() // correct a stale active language before it's read for the request
                // Silence gate (issue #93): before spending an upload, run a local Silero VAD; if the
                // recording contains no speech, skip transcription so silent clips can't produce "ghost
                // text" hallucinations. Fails open (treats as speech) if the check can't run. Not applied
                // to picked files or resends of already-captured audio (see callers).
                if (gate && prefs.dictate.skipSilentRecordings.get() &&
                    !SpeechGate.hasSpeech(appContext, audioFile)
                ) {
                    _state.value = UiState.Error(
                        message = appContext.getString(R.string.dictate__no_speech_detected),
                        action = ErrorAction.NONE,
                        neutral = true, // informational, not a failure → white/themed, not red
                    )
                    return@launch // audio is dropped by the finally block
                }
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
                // Shared finalize: rewording/formatting + mappings + commit + stats. Reused by the
                // realtime path (issue #128), which supplies its own already-streamed transcript.
                val capture = HistoryCapture(
                    audioFile = audioFile,
                    providerId = account.providerId,
                    providerName = historyProviderName,
                    model = model,
                    language = historyLanguage,
                    source = historySource,
                    isReplay = isReplay,
                    replayHistoryId = replayHistoryId,
                )
                finalizeAndCommit(appContext, result.text, recordedSeconds, live, alreadyFormatted = chatAudio, capture = capture)
            } catch (c: CancellationException) {
                // User aborted via the stop button: discard quietly (state set by cancelTranscription),
                // never show an error. The audio is dropped in the finally block.
                throw c
            } catch (e: DictateApiException) {
                _pendingPrompts.value = emptyList()
                // Exportable failures (too large / bad format) keep the audio regardless of the resend
                // pref, so it can be saved instead of lost (issue #144).
                keepAudio = retainFailedAudio(audioFile, live, recordedSeconds, force = e.kind in EXPORTABLE_ERROR_KINDS)
                // Safety net (issue #140): log the failed dictation with its audio so it can be recovered
                // later; not for replays (the entry already exists).
                if (replayHistoryId == null) {
                    recordFailedHistory(appContext, audioFile, account.providerId, historyProviderName, model, historyLanguage, recordedSeconds, historySource)
                }
                _state.value = apiError(e, appContext, canResend = keepAudio)
            } catch (t: Throwable) {
                _pendingPrompts.value = emptyList()
                keepAudio = retainFailedAudio(audioFile, live, recordedSeconds)
                if (replayHistoryId == null) {
                    recordFailedHistory(appContext, audioFile, account.providerId, historyProviderName, model, historyLanguage, recordedSeconds, historySource)
                }
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

    /**
     * Shared finalize step for a produced transcript, used by both the batch [transcribe] path and the
     * realtime path (issue #128): runs live-prompt rewording or the auto-formatting/auto-apply/pending
     * prompt chain (unless [alreadyFormatted]), applies the deterministic mappings, commits, and records
     * stats. [rawText] is the transcript to process; [live] routes it as a live-prompt instruction.
     */
    private suspend fun finalizeAndCommit(
        appContext: Context,
        rawText: String,
        recordedSeconds: Long,
        live: Boolean,
        alreadyFormatted: Boolean,
        finalizeViaComposing: Boolean = false,
        capture: HistoryCapture? = null,
    ) {
        val finalText = if (live) {
            // The spoken transcript is an instruction; send it to GPT (optionally operating on the current
            // selection) and insert the answer instead of the transcript.
            _pendingPrompts.value = emptyList() // a live prompt ignores any queued prompts
            _state.value = UiState.Rewording(appContext.getString(R.string.dictate__status_rewording))
            val selection = sink(appContext).selectedText().takeIf { it.isNotEmpty() }
            requestReword(rawText, selection)
        } else {
            // Normal dictation: auto-formatting + auto-apply prompts, then the prompts the user queued by
            // tapping the prompt row while recording, in tap order; then commit. [alreadyFormatted] skips
            // the rewording pass (single-call multimodal #130 already returns finished text).
            val processed = if (alreadyFormatted) rawText else postProcessTranscript(appContext, rawText)
            applyPendingPrompts(appContext, processed)
        }
        // Deterministic find-and-replace dictionary (issue #129), applied right before insert.
        val outputText = prefs.dictate.customMappings.get().apply(finalText)
        if (finalizeViaComposing) {
            // Realtime (#128): replace the live-streamed preview with the finished (reworded) result via the
            // minimal diff, then honor auto-enter — instead of committing on top of the preview.
            val outSink = sink(appContext)
            outSink.commitDictationFinal(outputText, realtimeShown.toString())
            realtimeShown.setLength(0)
            if (prefs.dictate.autoEnter.get() && outputText.isNotEmpty()) outSink.performEnter()
        } else {
            // Safety net (#214): for the floating button, optionally copy every dictation to the system
            // clipboard so nothing is lost if the accessibility insert is silently swallowed (the known
            // "green check but no text" case). Done BEFORE the commit on purpose: the paste-fallback captures
            // the current clipboard as "previous" and restores it 400 ms later, so with our text already on
            // the clipboard that restore becomes a no-op instead of wiping the copy.
            if (outputTarget == OutputTarget.OVERLAY && outputText.isNotEmpty() &&
                prefs.dictate.floatingButtonCopyToClipboard.get()
            ) {
                copyToSystemClipboard(appContext, outputText)
            }
            val committed = commitOutput(appContext, outputText)
            // Floating button (#156): the accessibility insert can be silently swallowed by some app fields
            // (Gemini's Compose box, WebViews). Don't flash a false green check — stash the text so the
            // user can recover it via Reinsert, and surface an error instead of "success".
            if (!committed && outputTarget == OutputTarget.OVERLAY && outputText.isNotEmpty()) {
                rememberLastDictation(outputText)
                if (capture?.isReplay != true) {
                    DictateStats.recordDictation(prefs, outputText, recordedSeconds)
                    if (recordedSeconds > 0L) creditAudioSeconds(recordedSeconds)
                }
                recordHistory(appContext, outputText, recordedSeconds, capture, reworded = live)
                discardRetainedAudio()
                _state.value = UiState.Error(
                    message = appContext.getString(R.string.dictate__error_overlay_insert_failed),
                )
                return
            }
        }
        // Re-insert safety net (issue #111) + lifetime stats (issue #142) + history log (issue #140).
        rememberLastDictation(outputText)
        if (capture?.isReplay != true) {
            DictateStats.recordDictation(prefs, outputText, recordedSeconds)
            if (recordedSeconds > 0L) creditAudioSeconds(recordedSeconds)
        }
        recordHistory(appContext, outputText, recordedSeconds, capture, reworded = live)
        discardRetainedAudio()
        _state.value = UiState.Idle
        if (outputTarget != OutputTarget.IME || !showMilestoneNudge(appContext)) {
            maybePromptForReview()
        }
    }

    /** Copies [text] to the system clipboard — the floating-button always-copy safety net (issue #214). */
    private fun copyToSystemClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Dictate", text)) }
    }

    // --- Real-time streaming (issue #128) -------------------------------------------------------

    /** The realtime wire API to use for the active transcription account, or null if realtime shouldn't run. */
    private fun realtimeApiForActiveAccount(): RealtimeApi? {
        if (!prefs.dictate.realtimeTranscription.get()) return null
        val account = transcriptionAccount()
        if (account.apiKey.isBlank()) return null
        val preset = presetFor(account)
        return if (preset.supportsRealtime) preset.realtimeApi else null
    }

    /** True if the next recording should stream in real time (global toggle on + provider supports it). */
    fun isRealtimeActive(): Boolean = realtimeApiForActiveAccount() != null

    /** True while a real-time streaming recording is actually in progress (a session is open). */
    fun isRealtimeRecording(): Boolean = realtimeSession != null

    /**
     * Opens a realtime session for the active account and returns a PCM sink to hand [RecordingController]
     * (which feeds captured 16 kHz frames, resampled per provider). Returns null when realtime does not
     * apply or the session can't be created — the caller then records normally (batch).
     */
    private fun openRealtimeSession(appContext: Context): ((ByteArray, Int) -> Unit)? {
        val api = realtimeApiForActiveAccount() ?: return null
        val account = transcriptionAccount()
        val preset = presetFor(account)
        val model = account.realtimeModel.takeIf { it.isNotBlank() } ?: preset.defaultRealtimeModel ?: return null
        val language = prefs.dictate.activeInputLanguage.get().takeIf { it != DictateLanguages.DETECT }
        synchronized(realtimeTextLock) {
            realtimeFinal.setLength(0)
            realtimePreviewLatest = ""
        }
        realtimeFailed = false
        realtimeCancelled = false
        _interimText.value = ""
        realtimeContext = appContext
        realtimeShown.setLength(0)
        resetRealtimePreviewScheduler()
        val closed = CompletableDeferred<Unit>()
        realtimeClosed = closed
        val previewSink = sink(appContext)
        val coalescePreview = outputTarget == OutputTarget.IME

        fun flushPreview() {
            if (realtimeCancelled) return
            val full = realtimePreviewLatest
            val prev = realtimeShown.toString()
            if (full == prev) return
            runCatching { previewSink.setDictationPreview(full, prev) }
            realtimeShown.setLength(0)
            realtimeShown.append(full)
            realtimePreviewLastFlushMs = SystemClock.elapsedRealtime()
        }

        fun publishLive(full: String, force: Boolean = false) {
            if (realtimeCancelled) return
            if (full == _interimText.value && !force) return
            _interimText.value = full

            if (!coalescePreview || force) {
                cancelRealtimePreviewJob()
                flushPreview()
                return
            }

            if (realtimePreviewJob?.isActive == true) return
            val elapsed = SystemClock.elapsedRealtime() - realtimePreviewLastFlushMs
            val waitMs = (REALTIME_PREVIEW_FRAME_MS - elapsed).coerceAtLeast(0L)
            if (waitMs == 0L) {
                flushPreview()
                return
            }
            realtimePreviewJob = scope.launch {
                delay(waitMs)
                flushPreview()
                realtimePreviewJob = null
            }
        }
        fun recordPartial(text: String): String? = synchronized(realtimeTextLock) {
            if (realtimeCancelled) {
                null
            } else {
                val head = realtimeFinal.toString()
                (if (head.isEmpty()) text else "$head $text").trim().also { realtimePreviewLatest = it }
            }
        }
        fun recordFinalSegment(text: String): String? = synchronized(realtimeTextLock) {
            if (realtimeCancelled) {
                null
            } else {
                val t = text.trim()
                if (t.isNotEmpty()) {
                    if (realtimeFinal.isNotEmpty()) realtimeFinal.append(' ')
                    realtimeFinal.append(t)
                }
                realtimeFinal.toString().also { realtimePreviewLatest = it }
            }
        }
        val callbacks = object : RealtimeCallbacks {
            override fun onPartial(text: String) {
                val full = recordPartial(text) ?: return
                scope.launch {
                    publishLive(full)
                }
            }
            override fun onFinalSegment(text: String) {
                val full = recordFinalSegment(text) ?: return
                scope.launch {
                    publishLive(full, force = true)
                }
            }
            override fun onError(t: Throwable) { realtimeFailed = true }
            override fun onClosed() { closed.complete(Unit) }
        }
        val session = runCatching { RealtimeClient.open(api, account.apiKey, model, language, callbacks) }
            .getOrElse { realtimeFailed = true; null } ?: return null
        realtimeSession = session
        val targetRate = RealtimeClient.sampleRateFor(api)
        if (targetRate == AudioDecode.TARGET_SAMPLE_RATE) {
            return { pcm, len ->
                runCatching { session.sendAudio(pcm, len) }
            }
        }
        var resampleBuffer = ByteArray(0)
        return { pcm, len ->
            val outLen = Pcm16Resampler.outputLengthBytes(len, AudioDecode.TARGET_SAMPLE_RATE, targetRate)
            if (outLen > 0) {
                if (resampleBuffer.size < outLen) resampleBuffer = ByteArray(outLen)
                val written = Pcm16Resampler.resampleInto(
                    pcm = pcm,
                    len = len,
                    srcRate = AudioDecode.TARGET_SAMPLE_RATE,
                    dstRate = targetRate,
                    out = resampleBuffer,
                )
                runCatching { session.sendAudio(resampleBuffer, written) }
            }
        }
    }

    /**
     * Stops a realtime recording: finalizes the stream, then commits the accumulated transcript through the
     * shared [finalizeAndCommit]. Keeps the recorded WAV so any stream failure (or an empty transcript)
     * falls back to a normal batch [transcribe] of the audio — the user never loses their dictation.
     */
    private fun stopRealtimeAndFinalize(context: Context) {
        val session = realtimeSession
        realtimeSession = null
        realtimeContext = null
        val activeRecorder = recorder
        recorder = null
        _livePromptActive.value = false
        unregisterScreenOffReceiver()
        val recordedSeconds = recordedSecondsOf(_state.value)
        val wavFile = activeRecorder?.stop()
        cleanupAudioRouting()
        val live = livePromptArmed
        livePromptArmed = false
        val closed = realtimeClosed
        realtimeClosed = null
        _state.value = UiState.Transcribing()
        val appContext = context.applicationContext
        transcribeJob = scope.launch {
            try {
                runCatching { session?.finish() }
                // Wait briefly for the provider to flush the last words (ends early if it closes), then
                // force-close the socket — several providers keep it open after finish, which otherwise
                // stalls us until the timeout and later trips a ping/pong failure.
                withTimeoutOrNull(REALTIME_FINALIZE_TIMEOUT_MS) { closed?.await() }
                runCatching { session?.cancel() }
                realtimeCancelled = true
                cancelRealtimePreviewJob()
                // Prefer the newest received transcript; it may be newer than the coalesced field preview.
                val transcript = synchronized(realtimeTextLock) {
                    realtimePreviewLatest.trim()
                        .ifEmpty { realtimeShown.toString().trim() }
                        .ifEmpty { realtimeFinal.toString().trim() }
                }
                _interimText.value = ""
                if (realtimeFailed || transcript.isEmpty()) {
                    // Drop the live provisional text; the batch path commits fresh from the WAV.
                    runCatching { sink(appContext).clearDictationPreview(realtimeShown.toString()) }
                    realtimeShown.setLength(0)
                    resetRealtimePreviewScheduler()
                    if (wavFile != null && wavFile.exists() && wavFile.length() > 0L) {
                        livePromptArmed = live
                        transcribe(context, wavFile, recordedSeconds, gate = false)
                    } else {
                        _state.value = UiState.Error(appContext.getString(R.string.dictate__error_no_audio))
                    }
                    return@launch
                }
                // History (issue #140): capture the metadata + WAV before deleting the cache file, so
                // audio retention (if on) can copy it in during finalize; then drop the cache original.
                val rtAccount = transcriptionAccount()
                val rtPreset = presetFor(rtAccount)
                val rtModel = rtAccount.realtimeModel.takeIf { it.isNotBlank() }
                    ?: rtPreset.defaultRealtimeModel ?: ""
                val rtCapture = HistoryCapture(
                    audioFile = wavFile?.takeIf { it.exists() && it.length() > 0L },
                    providerId = rtAccount.providerId,
                    providerName = rtAccount.displayName.ifBlank { rtPreset.displayName },
                    model = rtModel,
                    language = prefs.dictate.activeInputLanguage.get().takeIf { it != DictateLanguages.DETECT } ?: "",
                    source = DictateHistorySource.REALTIME,
                )
                finalizeAndCommit(appContext, transcript, recordedSeconds, live, alreadyFormatted = false, finalizeViaComposing = true, capture = rtCapture)
                resetRealtimePreviewScheduler()
                wavFile?.delete()
            } catch (c: CancellationException) {
                realtimeCancelled = true
                cancelRealtimePreviewJob()
                _interimText.value = ""
                runCatching { session?.cancel() }
                runCatching { sink(appContext).clearDictationPreview(realtimeShown.toString()) }
                realtimeShown.setLength(0)
                resetRealtimePreviewScheduler()
                wavFile?.delete()
                throw c
            } catch (t: Throwable) {
                _interimText.value = ""
                runCatching { sink(appContext).clearDictationPreview(realtimeShown.toString()) }
                realtimeShown.setLength(0)
                resetRealtimePreviewScheduler()
                if (wavFile != null && wavFile.exists() && wavFile.length() > 0L) {
                    livePromptArmed = live
                    transcribe(context, wavFile, recordedSeconds, gate = false)
                } else {
                    _state.value = UiState.Error(appContext.getString(R.string.dictate__error_unknown))
                }
            }
        }
    }

    // --- Long-form segmented dictation (issue #170) ---------------------------------------------

    /**
     * Whether the next recording should run in segmented mode: the feature is on, output goes to the
     * keyboard (not the accessibility overlay), it's not a live-prompt recording, realtime streaming is
     * not active, and the provider isn't in single-call multimodal mode (which would format per segment).
     */
    private fun isSegmentedMode(): Boolean =
        prefs.dictate.longformMode.get().isEnabled &&
            outputTarget == OutputTarget.IME &&
            !livePromptArmed &&
            !isRealtimeActive() &&
            !transcriptionAccount().transcriptionViaChat

    private fun initSegmented(appContext: Context) {
        segmentedActive = true
        segmentNextIndex = 0
        segmentCommitIndex = 0
        segmentResults.clear()
        segmentAudioFiles.clear()
        segmentInFlightCount = 0
        segmentStopped = false
        segmentRecordedSeconds = 0L
        _segmentFlushCount.value = 0
        // Keep + merge the segment audio only when the history feature would actually store it.
        segmentKeepAudio = prefs.dictate.historyEnabled.get() && prefs.dictate.historyAudioRetention.get()
        realtimeShown.setLength(0)
        // Reuse the realtime shown-text context so the existing cancel/interrupt cleanup clears the preview.
        realtimeContext = appContext
        realtimeCancelled = false
        _segmentsInFlight.value = 0
        _segmentedRecording.value = true
    }

    private fun resetSegmentedState() {
        segmentedActive = false
        segmentNextIndex = 0
        segmentCommitIndex = 0
        segmentResults.clear()
        segmentAudioFiles.clear() // files themselves are deleted by finalize/cancel, not here
        segmentInFlightCount = 0
        segmentStopped = false
        segmentVad?.release()
        segmentVad = null
        _segmentsInFlight.value = 0
        _segmentedRecording.value = false
    }

    /**
     * Cuts the current segment and keeps recording (the "Next segment" button, issue #170). The cut audio
     * is transcribed in the background and its raw text appended to the field in order. No-op unless a
     * segmented recording is actually in progress.
     */
    fun flushSegment(context: Context) {
        if (!segmentedActive || _state.value !is UiState.Recording) return
        val appContext = context.applicationContext
        scope.launch {
            val assigned = segmentMutex.withLock {
                if (!segmentedActive || _state.value !is UiState.Recording) return@withLock null
                val i = segmentNextIndex++
                val w = withContext(Dispatchers.IO) { recorder?.rotate() }
                if (segmentKeepAudio && w != null && w.exists() && w.length() > 0L) segmentAudioFiles[i] = w
                segmentInFlightCount++
                _segmentsInFlight.value = segmentInFlightCount
                _segmentFlushCount.value = _segmentFlushCount.value + 1
                i to w
            } ?: return@launch
            val (idx, wav) = assigned
            segmentVad?.notifyCut() // require fresh speech before the next auto-cut
            if (wav != null && wav.exists() && wav.length() > 0L) {
                launchSegmentTranscription(appContext, idx, wav)
            } else {
                // Nothing captured since the last cut (e.g. a double tap): keep the index sequence
                // contiguous so the ordered drain never stalls.
                onSegmentResult(appContext, idx, "")
            }
        }
    }

    /**
     * Cancel-button behaviour: in long-form mode, tapping the trash button ends the recording but keeps
     * everything transcribed so far — the already-committed segments are finalized and saved to history as
     * usual; only the current, not-yet-cut chunk is thrown away instead of being transcribed (#183).
     * Outside long-form it aborts the whole recording ([cancelRecording]). Used by both the Smartbar and
     * the legacy layout so the trash button behaves consistently.
     */
    fun cancelOrDiscardSegment(context: Context) {
        if (segmentedActive && _state.value is UiState.Recording) {
            stopSegmentedAndFinalize(context, discardFinal = true)
        } else {
            cancelRecording()
        }
    }

    /**
     * Stops a segmented recording: cuts the final open segment, then finishes once every queued segment
     * has transcribed and been committed in order — at which point the whole assembled transcript runs
     * through the normal post-processing once (auto-format + prompts + mappings) and replaces the preview.
     *
     * [discardFinal] (the cancel button, #183) throws the final open chunk away instead of transcribing it,
     * so the session still finalizes + saves to history with everything captured up to the last cut, but
     * the current unfinished utterance is dropped.
     */
    private fun stopSegmentedAndFinalize(context: Context, discardFinal: Boolean = false) {
        val appContext = context.applicationContext
        _livePromptActive.value = false
        unregisterScreenOffReceiver()
        segmentRecordedSeconds = recordedSecondsOf(_state.value)
        _segmentedRecording.value = false
        _state.value = UiState.Transcribing()
        scope.launch {
            val assigned = segmentMutex.withLock {
                val i = segmentNextIndex++
                val activeRecorder = recorder
                recorder = null
                val w = withContext(Dispatchers.IO) { activeRecorder?.stop() }
                cleanupAudioRouting()
                if (discardFinal) {
                    // Deleted chunk: drop its audio so it lands in neither the transcript nor the history WAV.
                    withContext(Dispatchers.IO) { runCatching { w?.delete() } }
                } else if (segmentKeepAudio && w != null && w.exists() && w.length() > 0L) {
                    segmentAudioFiles[i] = w
                }
                segmentStopped = true
                segmentInFlightCount++
                _segmentsInFlight.value = segmentInFlightCount
                i to w
            }
            val (idx, wav) = assigned
            if (!discardFinal && wav != null && wav.exists() && wav.length() > 0L) {
                launchSegmentTranscription(appContext, idx, wav)
            } else {
                onSegmentResult(appContext, idx, "")
            }
        }
    }

    private fun launchSegmentTranscription(appContext: Context, idx: Int, wav: File) {
        val job = scope.launch {
            // Best-effort cross-segment continuity: bias the recognizer with what's committed so far.
            val continuity = realtimeShown.toString()
            // One retry before giving up; a still-failed segment leaves a gap (no placeholder), but its
            // audio is preserved in the merged history WAV so nothing is truly lost.
            val text = transcribeSegmentRaw(appContext, wav, continuity)
                ?: transcribeSegmentRaw(appContext, wav, continuity)
            // Keep the WAV when it will be merged into the history audio; otherwise drop it now.
            if (!segmentKeepAudio) withContext(Dispatchers.IO) { runCatching { wav.delete() } }
            onSegmentResult(appContext, idx, text ?: "")
        }
        segmentJobs.add(job)
        job.invokeOnCompletion { segmentJobs.remove(job) }
    }

    /**
     * Buffers a finished segment's raw text and drains the ordered commit queue: appends every now-in-order
     * segment to the field's live preview. When the last segment lands after a stop, runs the end finalize.
     */
    private suspend fun onSegmentResult(appContext: Context, idx: Int, text: String) {
        val shouldFinish = segmentMutex.withLock {
            segmentResults[idx] = text
            while (segmentResults.containsKey(segmentCommitIndex)) {
                val raw = segmentResults.remove(segmentCommitIndex)!!.trim()
                segmentCommitIndex++
                if (raw.isNotEmpty()) {
                    val prev = realtimeShown.toString()
                    val full = if (prev.isEmpty()) raw else "$prev $raw"
                    runCatching { sink(appContext).setDictationPreview(full, prev) }
                    realtimeShown.setLength(0)
                    realtimeShown.append(full)
                }
            }
            segmentInFlightCount--
            _segmentsInFlight.value = segmentInFlightCount.coerceAtLeast(0)
            segmentStopped && segmentInFlightCount <= 0
        }
        if (shouldFinish) finalizeSegmentedEnd(appContext)
    }

    /**
     * All segments are in and committed as a raw preview; run the whole dictation through the normal
     * post-processing once and replace the preview with the finished (formatted/reworded) text.
     */
    private suspend fun finalizeSegmentedEnd(appContext: Context) {
        val account = transcriptionAccount()
        val preset = presetFor(account)
        val model = account.transcriptionModel.takeIf { it.isNotBlank() }
            ?: preset.defaultTranscriptionModel ?: ""
        val assembled = realtimeShown.toString().trim()
        val recordedSeconds = segmentRecordedSeconds
        // Snapshot the kept segment files (in cut order) before resetting; merge them into one WAV so the
        // whole dictation has a single retained-audio file in the history (issue #170 / #140 reuse).
        val keepAudio = segmentKeepAudio
        val audioFiles = segmentAudioFiles.toSortedMap().values.filter { it.exists() && it.length() > 0L }
        resetSegmentedState()
        val mergedWav = if (keepAudio && audioFiles.isNotEmpty()) {
            val merged = File(appContext.cacheDir, "dictate_seg_merged.wav")
            merged.delete()
            if (withContext(Dispatchers.IO) { AudioConcat.concat(audioFiles, merged) } && merged.exists() && merged.length() > 0L) merged else null
        } else null
        withContext(Dispatchers.IO) { audioFiles.forEach { runCatching { it.delete() } } }
        if (assembled.isEmpty()) {
            runCatching { sink(appContext).clearDictationPreview(realtimeShown.toString()) }
            realtimeShown.setLength(0)
            mergedWav?.delete()
            _state.value = UiState.Idle
            return
        }
        val capture = HistoryCapture(
            audioFile = mergedWav, // the merged segment audio, or null when retention is off
            providerId = account.providerId,
            providerName = account.displayName.ifBlank { preset.displayName },
            model = model,
            language = prefs.dictate.activeInputLanguage.get().takeIf { it != DictateLanguages.DETECT } ?: "",
            source = DictateHistorySource.KEYBOARD,
        )
        finalizeAndCommit(
            appContext, assembled, recordedSeconds, live = false,
            alreadyFormatted = false, finalizeViaComposing = true, capture = capture,
        )
        mergedWav?.delete() // history already copied it during finalize
    }

    /**
     * Transcribes one cut segment to RAW text (no formatting/rewording — that runs once at the end). Uses
     * the dedicated STT endpoint (never the multimodal chat path, which would format), with the offline
     * on-device fallback. Returns null on failure (the segment is dropped, keeping the sequence contiguous).
     */
    private suspend fun transcribeSegmentRaw(appContext: Context, wav: File, continuity: String): String? {
        // Silence gate (issue #93): skip a segment that is just silence — e.g. the trailing pause before a
        // cut/stop — so the model can't hallucinate ghost text ("Vielen Dank" / "Thanks for watching")
        // from it. Fails open (transcribes) if the check can't run, so real speech is never dropped.
        if (prefs.dictate.skipSilentRecordings.get() && !SpeechGate.hasSpeech(appContext, wav)) return null
        val account = transcriptionAccount()
        val apiKey = account.apiKey
        val preset = presetFor(account)
        val model = account.transcriptionModel.takeIf { it.isNotBlank() }
            ?: preset.defaultTranscriptionModel ?: "gpt-4o-mini-transcribe"
        val language = prefs.dictate.activeInputLanguage.get().takeIf { it != DictateLanguages.DETECT }
        val style = transcriptionStylePrompt()
        val prompt = continuity.takeLast(200).trim().let { if (it.isEmpty()) style else "$it $style".trim() }
        val request = TranscriptionRequest(audioFile = wav, model = model, language = language, prompt = prompt)
        return try {
            val result = if (preset.transcriptionApi == TranscriptionApi.LOCAL_ONDEVICE) {
                if (!LocalModelManager.isInstalled(appContext, model)) return null
                withContext(Dispatchers.IO) {
                    LocalTranscriptionProvider(LocalTranscriptionProvider.modelDir(appContext, model)).transcribe(request)
                }
            } else {
                if (apiKey.isBlank() && requiresKey(account)) return null
                try {
                    OpenAiCompatibleClient.from(
                        preset, apiKey,
                        baseUrlOverride = baseUrlOverrideFor(account),
                        proxy = prefs.dictate.dictateProxyConfig(),
                        useChatAudio = false,
                        trustUserCerts = prefs.dictate.trustUserCertificates.get(),
                    ).transcribe(request)
                } catch (e: DictateApiException) {
                    val fallback = localFallbackProvider(appContext, preset, e) ?: throw e
                    fallback.transcribe(request)
                }
            }
            result.text
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
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
    private suspend fun commitOutput(context: Context, text: String): Boolean {
        // Empty result (e.g. silence): nothing to insert — a no-op is a success, not a failed write.
        if (text.isEmpty()) return true
        val sink = sink(context)
        var committed: Boolean
        if (prefs.dictate.instantOutput.get()) {
            committed = sink.commitText(text)
        } else {
            val perChar = perCharDelayMs(prefs.dictate.outputSpeed.get())
            committed = true
            text.forEach { ch ->
                if (!sink.commitText(ch.toString())) committed = false
                delay(perChar)
            }
        }
        // No auto-enter on an empty result (e.g. silence): don't fire a stray newline into the field (#124).
        if (prefs.dictate.autoEnter.get()) {
            sink.performEnter()
        }
        return committed
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
        // A user-initiated resend of already-captured audio is sent as-is (no silence gate — issue #93).
        transcribe(context, r.file, r.seconds, gate = false)
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
        _livePromptActive.value = false
        // Realtime (#128): drop the stream; the WAV is stashed below and recoverable via batch as usual.
        realtimeCancelled = true
        resetRealtimePreviewScheduler()
        realtimeSession?.cancel()
        realtimeSession = null
        realtimeClosed = null
        _interimText.value = ""
        realtimeContext?.let { ctx -> runCatching { sink(ctx).clearDictationPreview(realtimeShown.toString()) } }
        realtimeShown.setLength(0)
        realtimeContext = null
        unregisterScreenOffReceiver()
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

    /** Whether the history feature is enabled — gates the history Smartbar button's enabled state (#140). */
    fun isHistoryEnabled(): Boolean = prefs.dictate.historyEnabled.get()

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

    // --- Transcription history (issue #140) -----------------------------------------------------

    /**
     * Logs a finished dictation to the history store, honoring the opt-in and the privacy gate. Called
     * from [finalizeAndCommit] with the final committed text and the threaded [capture] metadata. A replay
     * with a known entry id overwrites that entry's text in place; otherwise a new row is inserted (and the
     * WAV copied in when audio retention is on). No-op when history is off, the field is sensitive, or
     * there is no capture context (e.g. a plain re-insert).
     */
    private suspend fun recordHistory(
        appContext: Context,
        text: String,
        recordedSeconds: Long,
        capture: HistoryCapture?,
        reworded: Boolean,
    ) {
        if (capture == null || text.isBlank()) return
        if (!prefs.dictate.historyEnabled.get()) return
        if (isSensitiveDictationField(appContext)) return
        capture.replayHistoryId?.let { id ->
            DictateHistoryStore.updateText(appContext, id, text)
            return
        }
        DictateHistoryStore.record(
            context = appContext,
            prefs = prefs,
            text = text,
            providerId = capture.providerId,
            providerName = capture.providerName,
            model = capture.model,
            language = capture.language,
            durationSecs = recordedSeconds,
            source = capture.source,
            reworded = reworded,
            audioFile = capture.audioFile,
        )
    }

    /**
     * Logs a *failed* dictation to the history (issue #140 safety net) so its audio can be recovered and
     * re-transcribed later — the resend chip is transient (it disappears; see issue #114). Only when
     * history + audio retention are on (a failure with no kept audio is not recoverable), and never in a
     * sensitive field. The entry carries a placeholder text and `failed=true`.
     */
    private suspend fun recordFailedHistory(
        appContext: Context,
        audioFile: File,
        providerId: String,
        providerName: String,
        model: String,
        language: String,
        recordedSeconds: Long,
        source: String,
    ) {
        // Gated only on the master history switch: a failed dictation has no text, so its audio is the ONLY
        // recovery path — we keep it even when "keep audio" (which governs successful dictations) is off.
        if (!prefs.dictate.historyEnabled.get()) return
        if (isSensitiveDictationField(appContext)) return
        if (!audioFile.exists() || audioFile.length() == 0L) return
        DictateHistoryStore.record(
            context = appContext,
            prefs = prefs,
            text = appContext.getString(R.string.dictate__history_failed),
            providerId = providerId,
            providerName = providerName,
            model = model,
            language = language,
            durationSecs = recordedSeconds,
            source = source,
            reworded = false,
            audioFile = audioFile,
            failed = true,
            forceAudio = true,
        )
    }

    /** Exports a history entry's retained audio to Downloads/Dictate (issue #140), toasting the result. */
    fun exportHistoryAudio(context: Context, entry: DictateHistoryEntry) {
        val path = entry.audioPath ?: return
        val src = File(path)
        if (!src.exists() || src.length() == 0L) return
        val appContext = context.applicationContext
        scope.launch {
            val savedName = withContext(Dispatchers.IO) { exportAudioToDownloads(appContext, src) }
            val message = if (savedName != null) {
                appContext.getString(R.string.dictate__audio_saved, savedName)
            } else {
                appContext.getString(R.string.dictate__audio_save_failed)
            }
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * True when the active in-keyboard field is a password field or in incognito mode, so a dictation into
     * it must not be logged. Only meaningful for the IME target — the floating button injects into
     * arbitrary apps via accessibility with no reliable field-sensitivity signal, so it is never gated here.
     */
    private fun isSensitiveDictationField(context: Context): Boolean {
        if (outputTarget != OutputTarget.IME) return false
        return runCatching {
            val state = context.keyboardManager().value.activeState
            state.isIncognitoMode || state.keyVariation == KeyVariation.PASSWORD
        }.getOrDefault(false)
    }

    /**
     * Commits a stored history entry's text into the focused field (issue #140), verbatim like
     * [reinsertLastDictation] — no auto-formatting/auto-enter (those already ran). Used by the in-keyboard
     * history panel's per-row insert. No-op while a recording/transcription/rewording is in flight.
     */
    fun insertHistoryText(context: Context, text: String) {
        if (_state.value is UiState.Recording || _state.value is UiState.Transcribing ||
            _state.value is UiState.Rewording
        ) return
        if (text.isEmpty()) return
        outputTarget = OutputTarget.IME
        sink(context).commitText(text)
        clearError()
    }

    /**
     * Re-transcribes a stored entry's retained audio (issue #140) and commits the fresh result into the
     * field, then overwrites the entry's text in place. The history-owned WAV is copied to a cache temp
     * first so the shared transcribe path's finally-delete can't remove it. Marked as a replay so stats
     * aren't double-counted. No-op when the entry has no retained audio or a dictation is in flight.
     */
    fun retranscribeHistoryEntry(context: Context, entry: DictateHistoryEntry) {
        if (_state.value is UiState.Recording || _state.value is UiState.Transcribing ||
            _state.value is UiState.Rewording
        ) return
        val path = entry.audioPath ?: return
        val src = File(path)
        if (!src.exists() || src.length() == 0L) return
        val temp = File(context.cacheDir, "dictate_history_replay.wav")
        runCatching { src.copyTo(temp, overwrite = true) }.getOrElse { return }
        outputTarget = OutputTarget.IME
        clearError()
        // A failed entry's first successful re-transcribe SHOULD count stats (it was never counted); an
        // already-successful entry's re-transcribe must not double-count → isReplay only when not failed.
        transcribe(
            context, temp, entry.durationSecs, gate = false,
            isReplay = !entry.failed, source = entry.source, replayHistoryId = entry.id,
        )
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
        // Store the job so the stop button can abort this reword mid-generation (issue #192).
        rewordJob = scope.launch {
            try {
                val text = requestReword(raw, input, prompt.reasoningEffort, prompt.reasoningEffortCustom)
                // commitText replaces the active selection if any, else inserts at the cursor.
                sink.commitText(text)
                _state.value = UiState.Idle
            } catch (e: CancellationException) {
                throw e // stop button pressed: cancelRewording already reset the state, leave the field as-is
            } catch (e: DictateApiException) {
                _state.value = apiError(e, appContext, canResend = false)
            } catch (t: Throwable) {
                _state.value = UiState.Error(
                    message = appContext.getString(R.string.dictate__error_rewording_failed),
                    kind = DictateApiException.Kind.UNKNOWN,
                    detail = t.message?.takeIf { it.isNotBlank() },
                )
            } finally {
                rewordJob = null
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
                requestReword(instruction, if (p.requiresSelection) text else null, p.reasoningEffort, p.reasoningEffortCustom)
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
                requestReword(raw, if (p.requiresSelection) result else null, p.reasoningEffort, p.reasoningEffortCustom)
            }.getOrDefault(result)
        }
        return result
    }

    /**
     * High-level rewording call: builds the user message as `instruction [+ system prompt] [+ input]`
     * (exactly as the legacy app did – the be-precise prompt is tuned for this position) and returns
     * the trimmed model output.
     */
    private suspend fun requestReword(
        instruction: String,
        input: String?,
        reasoning: DictateReasoningEffort? = null,
        reasoningCustom: String? = null,
    ): String {
        val sys = systemPrompt()
        val content = buildString {
            append(instruction)
            if (sys.isNotBlank()) append("\n\n").append(sys)
            if (!input.isNullOrBlank()) append("\n\n").append(input)
        }
        return requestRewordRaw(content, reasoning, reasoningCustom)
    }

    /**
     * Low-level rewording call: sends [userContent] verbatim as a single user message. [reasoning] is
     * the per-prompt reasoning-effort override (issue #155); null falls back to the global setting.
     */
    private suspend fun requestRewordRaw(
        userContent: String,
        reasoning: DictateReasoningEffort? = null,
        reasoningCustom: String? = null,
    ): String {
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
        // Reasoning effort for reasoning models (issue #141); a per-prompt override wins over the global
        // setting (#155). OFF → null → field omitted. CUSTOM (#186) uses a user-entered wire value —
        // the per-prompt one when the override itself is CUSTOM, else the global custom value.
        val effort = reasoning ?: prefs.dictate.rewordingReasoningEffort.get()
        val reasoningWire = if (effort == DictateReasoningEffort.CUSTOM) {
            val custom = if (reasoning == DictateReasoningEffort.CUSTOM) {
                reasoningCustom
            } else {
                prefs.dictate.rewordingReasoningEffortCustom.get()
            }
            custom?.trim()?.ifBlank { null }
        } else {
            effort.wire
        }
        val result = client.complete(
            ChatRequest.ofUser(model, userContent, reasoningEffort = reasoningWire),
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
