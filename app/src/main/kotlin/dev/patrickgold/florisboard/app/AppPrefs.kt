/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import dev.patrickgold.florisboard.app.settings.theme.ColorPreferenceSerializer
import dev.patrickgold.florisboard.app.settings.theme.DisplayKbdAfterDialogs
import dev.patrickgold.florisboard.app.settings.theme.SnyggLevel
import dev.patrickgold.florisboard.app.setup.NotificationPermissionState
import dev.patrickgold.florisboard.dictate.DictateFloatingButtonDesign
import dev.patrickgold.florisboard.dictate.audio.DictateAudioSource
import dev.patrickgold.florisboard.dictate.DictateFloatingButtonSize
import dev.patrickgold.florisboard.dictate.DictateLegacyLayout
import dev.patrickgold.florisboard.dictate.DictatePromptsLayout
import dev.patrickgold.florisboard.dictate.DictateReasoningEffort
import dev.patrickgold.florisboard.dictate.data.mappings.DictateMappings
import dev.patrickgold.florisboard.dictate.provider.DictateProxyType
import dev.patrickgold.florisboard.dictate.provider.ProviderAccounts
import dev.patrickgold.florisboard.ime.clipboard.CLIPBOARD_HISTORY_NUM_GRID_COLUMNS_AUTO
import dev.patrickgold.florisboard.ime.clipboard.ClipboardSyncBehavior
import dev.patrickgold.florisboard.ime.core.DisplayLanguageNamesIn
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.input.CapitalizationBehavior
import dev.patrickgold.florisboard.ime.input.HapticVibrationMode
import dev.patrickgold.florisboard.ime.input.InputFeedbackActivationMode
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import dev.patrickgold.florisboard.ime.keyboard.SpaceBarMode
import dev.patrickgold.florisboard.ime.landscapeinput.LandscapeInputUiMode
import dev.patrickgold.florisboard.ime.media.emoji.EmojiHairStyle
import dev.patrickgold.florisboard.ime.media.emoji.EmojiHistory
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSkinTone
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSuggestionType
import dev.patrickgold.florisboard.ime.nlp.SpellingLanguageMode
import dev.patrickgold.florisboard.ime.smartbar.CandidatesDisplayMode
import dev.patrickgold.florisboard.ime.smartbar.ExtendedActionsPlacement
import dev.patrickgold.florisboard.ime.smartbar.IncognitoDisplayMode
import dev.patrickgold.florisboard.ime.smartbar.SmartbarLayout
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickAction
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionArrangement
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionJsonConfig
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyHintConfiguration
import dev.patrickgold.florisboard.ime.text.key.KeyHintMode
import dev.patrickgold.florisboard.ime.text.key.UtilityKeyAction
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.ThemeMode
import dev.patrickgold.florisboard.ime.theme.extCoreTheme
import dev.patrickgold.florisboard.ime.window.ImeWindowConfig
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import dev.patrickgold.florisboard.lib.util.VersionName
import dev.patrickgold.jetpref.datastore.annotations.Preferences
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import dev.patrickgold.jetpref.datastore.model.LocalTime
import dev.patrickgold.jetpref.datastore.model.PreferenceData
import dev.patrickgold.jetpref.datastore.model.PreferenceMigrationEntry
import dev.patrickgold.jetpref.datastore.model.PreferenceModel
import dev.patrickgold.jetpref.datastore.model.PreferenceType
import dev.patrickgold.jetpref.material.ui.ColorRepresentation
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.isOrientationPortrait

val FlorisPreferenceStore = jetprefDataStoreOf(FlorisPreferenceModel::class)

@Preferences
abstract class FlorisPreferenceModel : PreferenceModel() {
    companion object {
        const val NAME = "florisboard-app-prefs"
    }

    val clipboard = Clipboard()
    inner class Clipboard {
        val useInternalClipboard = boolean(
            key = "clipboard__use_internal_clipboard",
            default = false,
        )
        val syncToFloris = enum(
            key = "clipboard__sync_to_floris",
            default = ClipboardSyncBehavior.ALL_EVENTS,
        )
        val syncToSystem = enum(
            key = "clipboard__sync_to_system",
            default = ClipboardSyncBehavior.NO_EVENTS,
        )
        val suggestionEnabled = boolean(
            key = "clipboard__suggestion_enabled",
            default = true,
        )
        val suggestionTimeout = int(
            key = "clipboard__suggestion_timeout",
            default = 60,
        )
        val historyEnabled = boolean(
            key = "clipboard__history_enabled",
            default = false,
        )
        val historyNumGridColumnsPortrait = int(
            key = "clipboard__history_num_grid_columns_portrait",
            default = CLIPBOARD_HISTORY_NUM_GRID_COLUMNS_AUTO,
        )
        val historyNumGridColumnsLandscape = int(
            key = "clipboard__history_num_grid_columns_landscape",
            default = CLIPBOARD_HISTORY_NUM_GRID_COLUMNS_AUTO,
        )
        @Composable
        fun historyNumGridColumns(): PreferenceData<Int> {
            val configuration = LocalConfiguration.current
            return if (configuration.isOrientationPortrait()) {
                historyNumGridColumnsPortrait
            } else {
                historyNumGridColumnsLandscape
            }
        }
        val historyAutoCleanOldEnabled = boolean(
            key = "clipboard__history_auto_clean_old_enabled",
            default = false,
        )
        val historyAutoCleanOldAfter = int(
            key = "clipboard__history_auto_clean_old_after",
            default = 20,
        )
        val historyAutoCleanSensitiveEnabled = boolean(
            key = "clipboard__history_auto_clean_sensitive_enabled",
            default = false,
        )
        val historyAutoCleanSensitiveAfter = int(
            key = "clipboard__history_auto_clean_sensitive_after",
            default = 20,
        )
        val historySizeLimitEnabled = boolean(
            key = "clipboard__history_size_limit_enabled",
            default = true,
        )
        val historySizeLimit = int(
            key = "clipboard__history_size_limit",
            default = 20,
        )
        val historyHideOnPaste = boolean(
            key = "clipboard__history_hide_on_paste",
            default = false,
        )
        val historyHideOnNextTextField = boolean(
            key = "clipboard__history_hide_on_next_text_field",
            default = true,
        )
        val clearPrimaryClipAffectsHistoryIfUnpinned = boolean(
            key = "clipboard__clear_primary_clip_affects_history_if_unpinned",
            default = true,
        )
    }

    val correction = Correction()
    inner class Correction {
        val autoCapitalization = boolean(
            key = "correction__auto_capitalization",
            default = true,
        )
        val autoSpacePunctuation = boolean(
            key = "correction__auto_space_punctuation",
            default = false,
        )
        val doubleSpacePeriod = boolean(
            key = "correction__double_space_period",
            default = true,
        )
        val rememberCapsLockState = boolean(
            key = "correction__remember_caps_lock_state",
            default = false,
        )
    }

    val devtools = Devtools()
    inner class Devtools {
        val enabled = boolean(
            key = "devtools__enabled",
            default = false,
        )
        val showPrimaryClip = boolean(
            key = "devtools__show_primary_clip",
            default = false,
        )
        val showInputStateOverlay = boolean(
            key = "devtools__show_input_state_overlay",
            default = false,
        )
        val showSpellingOverlay = boolean(
            key = "devtools__show_spelling_overlay",
            default = false,
        )
        val showInlineAutofillOverlay = boolean(
            key = "devtools__show_inline_autofill_overlay",
            default = false,
        )
        val showKeyTouchBoundaries = boolean(
            key = "devtools__show_touch_boundaries",
            default = false,
        )
        val showDragAndDropHelpers = boolean(
            key = "devtools__show_drag_and_drop_helpers",
            default = false,
        )
        val showWindowResizeHandleBoundaries = boolean(
            key = "devtools__show_window_resize_handle_boundaries",
            default = false,
        )
    }

    val dictate = Dictate()
    inner class Dictate {
        // --- Provider keyring (multi-provider, roadmap section 4.x) ------------------------------
        // Per-provider credentials (API key + chosen models + custom base URL), keyed by provider id.
        // This is the source of truth for keys/models; transcriptionProviderId / rewordingProviderId
        // below are just the *active* pointers into this keyring. See ProviderAccounts.
        val providerAccounts = custom(
            key = "dictate__provider_accounts",
            default = ProviderAccounts.Empty,
            serializer = ProviderAccounts.Serializer,
        )
        // Guard so the one-time import of the legacy flat prefs (apiKey/transcriptionModel/… below)
        // into the keyring runs exactly once. See DictateProviderMigrator.
        val providerAccountsMigrated = boolean(
            key = "dictate__provider_accounts_migrated",
            default = false,
        )

        // Active transcription provider id, matching a ProviderRegistry id that supports speech-to-text
        // ("openai", "groq") or a "custom:<uuid>" endpoint. The actual key/model live in the keyring.
        val transcriptionProviderId = string(
            key = "dictate__transcription_provider_id",
            default = "openai",
        )

        // On-device offline fallback (issue #104): when the active provider is a cloud one and its call
        // fails because the device is offline (after the normal retries), retry once on-device using the
        // local provider's downloaded model. No effect if the local model isn't installed, or if the
        // active provider is already the local one.
        val localFallbackEnabled = boolean(
            key = "dictate__local_fallback_enabled",
            default = false,
        )

        // Wear OS standalone (#106): when on, the paired watch is allowed to transcribe by itself and
        // the API key is included in the settings snapshot synced to it (stored private to the watch app).
        // On by default so the watch keeps working when the phone is out of range; it still tethers
        // through the phone whenever one is reachable. Turn off to keep the key strictly on the phone
        // (the watch is then tether-only and can't dictate without the phone).
        val wearStandaloneEnabled = boolean(
            key = "dictate__wear_standalone_enabled",
            default = true,
        )

        // Wear OS (#106/#130): when on, dictations made from the watch are auto-reworded like on the
        // phone — tethered dictations are reworded by the phone, standalone ones by the watch itself
        // (using the synced rewording config + auto-apply prompts). On by default.
        val wearAutoRewordingEnabled = boolean(
            key = "dictate__wear_auto_rewording_enabled",
            default = true,
        )

        // --- Network proxy (roadmap 5.6) ---------------------------------------------------------
        // Optional proxy applied to *every* provider API call (transcription, rewording, model
        // listing, connection test). Disabled by default; built into a ProxyConfig via ProxyConfig.of
        // and forwarded to OkHttp. HTTP proxies support user/password; SOCKS5 credentials are not
        // forwarded (JVM limitation). See dictateProxyConfig().
        val proxyEnabled = boolean(
            key = "dictate__proxy_enabled",
            default = false,
        )
        val proxyType = enum(
            key = "dictate__proxy_type",
            default = DictateProxyType.HTTP,
        )
        val proxyHost = string(
            key = "dictate__proxy_host",
            default = "",
        )
        val proxyPort = string(
            key = "dictate__proxy_port",
            default = "8080",
        )
        val proxyUsername = string(
            key = "dictate__proxy_username",
            default = "",
        )
        val proxyPassword = string(
            key = "dictate__proxy_password",
            default = "",
        )
        val trustUserCertificates = boolean(
            key = "dictate__trust_user_certificates",
            default = false,
        )

        // --- DEPRECATED flat credential prefs (migration source only) ----------------------------
        // Kept solely so DictateProviderMigrator can copy them into the keyring once. Do not read these
        // for live calls anymore – use providerAccounts[transcriptionProviderId].
        @Deprecated("Migrated into providerAccounts; read the keyring instead.")
        val apiKey = string(
            key = "dictate__api_key",
            default = "",
        )
        @Deprecated("Migrated into providerAccounts; read the keyring instead.")
        val transcriptionModel = string(
            key = "dictate__transcription_model",
            default = "",
        )
        @Deprecated("Migrated into providerAccounts; read the keyring instead.")
        val customBaseUrl = string(
            key = "dictate__custom_base_url",
            default = "",
        )
        // Pause/duck other apps' audio while recording (default on, as in the legacy Dictate).
        val audioFocus = boolean(
            key = "dictate__audio_focus",
            default = true,
        )
        // Route recording through a connected Bluetooth (SCO) microphone when available.
        val useBluetoothMic = boolean(
            key = "dictate__use_bluetooth_mic",
            default = false,
        )
        // Which audio source the recorder captures from (issue #62). Default keeps the device MIC
        // (current behavior); VOICE_RECOGNITION/UNPROCESSED skip phone audio processing that can hurt
        // transcription. Ignored on the Bluetooth-SCO path (always VOICE_COMMUNICATION).
        val audioInputSource = enum(
            key = "dictate__audio_input_source",
            default = DictateAudioSource.DEFAULT,
        )
        // Keep the screen awake while a recording is in progress (default on).
        val keepScreenAwake = boolean(
            key = "dictate__keep_screen_awake",
            default = true,
        )
        // Skip transcription when a local Silero VAD finds no speech in the recording, so silent clips
        // don't produce "ghost text" hallucinations or waste API credits (issue #93). Default on.
        val skipSilentRecordings = boolean(
            key = "dictate__skip_silent_recordings",
            default = true,
        )
        // Haptic feedback on dictation state changes (issue #166): a short buzz on record start/stop, a
        // double on transcription done, a longer one when a rewording/LLM prompt finished — so the user
        // knows blindly when to look back at the screen. Off by default. Amplitude honours the system
        // haptic intensity. Also mirrored on the watch (synced to it via DictateSyncedSettings).
        val hapticFeedback = boolean(
            key = "dictate__haptic_feedback",
            default = false,
        )
        // Start recording immediately whenever the keyboard opens on a text field (default off).
        val instantRecording = boolean(
            key = "dictate__instant_recording",
            default = false,
        )
        // When instant recording is on, still don't auto-start on number-only fields (number, phone, PIN,
        // date/time), where dictation rarely makes sense (issue #146). Default on.
        val instantRecordingSkipNumeric = boolean(
            key = "dictate__instant_recording_skip_numeric",
            default = true,
        )
        // Floating dictation button (issue #88): the in-app master toggle. The bubble only shows when
        // this is on AND the DictateAccessibilityService is enabled in the system accessibility settings
        // (the latter is the actual permission; this lets the user hide the bubble without digging into
        // system settings). Default off — opt-in feature.
        val floatingButtonEnabled = boolean(
            key = "dictate__floating_button_enabled",
            default = false,
        )
        // Whether the floating button also shows while the Dictate keyboard itself is the active input
        // method. Default off: when our own keyboard is up it already has a mic key, so the bubble would
        // be redundant; turning this on shows it everywhere regardless of the active keyboard.
        val floatingButtonShowWithDictateKeyboard = boolean(
            key = "dictate__floating_button_show_with_dictate_keyboard",
            default = false,
        )
        // Visual style of the floating button: a compact ring (RING) or a bubble that expands into a pill
        // with a timer + live waveform while active (PILL). See DictateFloatingButtonDesign.
        val floatingButtonDesign = enum(
            key = "dictate__floating_button_design",
            default = DictateFloatingButtonDesign.PILL,
        )
        // Overall size of the floating button (scales the skin dimensions).
        val floatingButtonSize = enum(
            key = "dictate__floating_button_size",
            default = DictateFloatingButtonSize.MEDIUM,
        )
        // Whether the floating button snaps to the nearest screen edge after being dragged. Default on;
        // turn off to leave it wherever it is dropped (still kept within the screen bounds).
        val floatingButtonSnapToEdge = boolean(
            key = "dictate__floating_button_snap_to_edge",
            default = true,
        )
        // Accent color of the floating button (idle/transcribing visuals). Defaults to the Dictate light blue.
        val floatingButtonColor = custom(
            key = "dictate__floating_button_color",
            default = Color(0xFF30B7E6),
            serializer = ColorPreferenceSerializer,
        )
        // Fade + shrink the button to a small dot after a few seconds of inactivity; tap to restore.
        val floatingButtonAutoDim = boolean(
            key = "dictate__floating_button_auto_dim",
            default = true,
        )
        // Remember the button's position separately per app.
        val floatingButtonRememberPosition = boolean(
            key = "dictate__floating_button_remember_position",
            default = true,
        )
        // Vibrate briefly when the button is tapped.
        val floatingButtonHaptic = boolean(
            key = "dictate__floating_button_haptic",
            default = true,
        )
        // Optional undo control on the floating button (issue #133): when on, an undo button appears
        // next to the bubble after a dictation and removes the last inserted text in one tap. Off by
        // default to keep the overlay minimal; needs "remember last dictation" to have something to undo.
        val floatingButtonUndoEnabled = boolean(
            key = "dictate__floating_button_undo_enabled",
            default = false,
        )
        // Whether the user has opened the floating-button screen at least once (clears the "New" badge).
        val floatingButtonHintSeen = boolean(
            key = "dictate__floating_button_hint_seen",
            default = false,
        )
        // App version whose one-time floating-button Smartbar spotlight has already been shown.
        val floatingButtonSpotlightVersion = string(
            key = "dictate__floating_button_spotlight_version",
            default = "",
        )
        // --- Output behavior (roadmap section 10) ------------------------------------------------
        // Press Enter / trigger the editor action automatically after committing a transcription.
        val autoEnter = boolean(
            key = "dictate__auto_enter",
            default = false,
        )
        // Commit the transcription all at once (true) or "type" it out character by character (false).
        val instantOutput = boolean(
            key = "dictate__instant_output",
            default = true,
        )
        // Real-time (streaming) transcription (issue #128): show text live while speaking, for providers
        // that support it (OpenAI realtime, Soniox, Deepgram, …). Global switch; falls back to batch when
        // the selected provider has no realtime support. Default off.
        val realtimeTranscription = boolean(
            key = "dictate__realtime_transcription",
            default = false,
        )
        // Speed of the typewriter animation when instantOutput is off (1 = slow … 10 = fast).
        val outputSpeed = int(
            key = "dictate__output_speed",
            default = 5,
        )
        // Show a resend button when a recording failed to transcribe/reword, to retry the same audio.
        val resendButton = boolean(
            key = "dictate__resend_button",
            default = true,
        )
        // Safety net (issue #111): keep the last successful dictation around so it can be re-inserted
        // via the "Re-insert last dictation" Smartbar action after the field is cleared (rotation,
        // context switch, host app refreshing its state). When off, nothing is cached and the action
        // stays disabled. The text is stored locally (see lastDictation) until the next dictation.
        val rememberLastDictation = boolean(
            key = "dictate__remember_last_dictation",
            default = true,
        )
        // The last successfully committed dictation text, persisted so it survives the IME process being
        // killed. Overwritten by the next successful dictation; never shown directly in the UI. Empty
        // means there is nothing to re-insert. Only populated while rememberLastDictation is on.
        val lastDictation = string(
            key = "dictate__last_dictation",
            default = "",
        )
        // Interrupted recording (keyboard closed mid-recording): the audio is finalized and moved to a
        // file in filesDir so it survives the recorder/process being destroyed; these prefs are the
        // persisted marker + metadata so the "recording interrupted — send it?" offer can be restored on
        // the next keyboard open. pending=true means an interrupted-audio file is waiting.
        val interruptedAudioPending = boolean(
            key = "dictate__interrupted_audio_pending",
            default = false,
        )
        // Recorded seconds of the interrupted audio, re-credited towards the rate/donate nudges on send.
        val interruptedAudioSeconds = long(
            key = "dictate__interrupted_audio_seconds",
            default = 0L,
        )
        // Whether the interrupted recording was a live-prompt session, so sending it repeats that mode.
        val interruptedAudioLive = boolean(
            key = "dictate__interrupted_audio_live",
            default = false,
        )
        // --- Lifetime dictation statistics (issue #142) ------------------------------------------
        // Never auto-reset (unlike totalAudioSeconds below, which the rate nudge clears); only the user
        // can reset them from the stats screen. Updated centrally after each successful dictation.
        val statsDictations = long(key = "dictate__stats_dictations", default = 0L)
        val statsWords = long(key = "dictate__stats_words", default = 0L)
        val statsChars = long(key = "dictate__stats_chars", default = 0L)
        val statsSpokenSeconds = long(key = "dictate__stats_spoken_seconds", default = 0L)
        val statsRewordings = long(key = "dictate__stats_rewordings", default = 0L)
        // Epoch millis of the first ever dictation (0 = none yet), for the "tracking since" line.
        val statsFirstUseEpochMs = long(key = "dictate__stats_first_use_epoch_ms", default = 0L)
        // Day-streak bookkeeping: last active day (epoch day) plus current/best consecutive-day runs.
        val statsLastDayEpoch = long(key = "dictate__stats_last_day_epoch", default = 0L)
        val statsStreakCurrent = int(key = "dictate__stats_streak_current", default = 0)
        val statsStreakBest = int(key = "dictate__stats_streak_best", default = 0)
        // Compact rolling per-day word counts for the 7-day chart: "epochDay:words;epochDay:words;…".
        val statsDaily = string(key = "dictate__stats_daily", default = "")
        // One-time milestone celebrations (issue #142). Only saved-time and dictation-count milestones,
        // shown once each in the app (never on the keyboard). Toggle lives on the stats screen.
        val statsMilestonesEnabled = boolean(key = "dictate__stats_milestones_enabled", default = true)
        val statsMilestoneTimeShown = long(key = "dictate__stats_milestone_time_shown", default = 0L)
        val statsMilestoneCountShown = long(key = "dictate__stats_milestone_count_shown", default = 0L)
        // A crossed-but-not-yet-shown milestone, consumed on next app open: "time:<min>" | "count:<n>".
        val statsPendingMilestone = string(key = "dictate__stats_pending_milestone", default = "")

        // --- Rate / Donate nudges (roadmap 9.7/9.8) ----------------------------------------------
        // Cumulative seconds of successfully transcribed *recorded* audio, used to gate the one-time
        // rate/donate prompts. Replaces the legacy usage DB (which was dropped); only this counter
        // remains. Incremented after each successful mic transcription.
        val totalAudioSeconds = long(
            key = "dictate__total_audio_seconds",
            default = 0L,
        )
        // Set once the user has acted on the rate prompt (accepted or declined), so it never reappears.
        val hasRated = boolean(
            key = "dictate__has_rated",
            default = false,
        )
        // Set once the user has acted on the donate prompt; accepting/declining donate also sets
        // hasRated, so a donor is never asked to rate afterwards (mirrors the legacy behavior).
        val hasDonated = boolean(
            key = "dictate__has_donated",
            default = false,
        )
        // The app version whose "Dictate was updated" changelog nudge has already been shown on the
        // keyboard (Smartbar). Set when the user taps or dismisses that nudge, so it appears only once
        // per update. Empty until the first post-update nudge. Independent of the in-app dialog's
        // versionLastChangelog bookkeeping, so the two surfaces never suppress each other.
        val changelogNudgeVersion = string(
            key = "dictate__changelog_nudge_version",
            default = "",
        )
        // Comma-separated dictation language codes the user cycles through on the recording bar
        // (see DictateLanguages; "detect" = auto-detect). Default mirrors the legacy app.
        val inputLanguages = string(
            key = "dictate__input_languages",
            default = "detect,en",
        )
        // The currently active dictation language code; persists across sessions and is switched
        // from the recording bar's language chip.
        val activeInputLanguage = string(
            key = "dictate__active_input_language",
            default = "detect",
        )
        // Guard so the one-time seeding of the device/system dictation language (added on top of the
        // default detect,en) runs only once on a fresh install. See
        // DictateLegacyMigrator.seedDeviceLanguageIfNeeded.
        val inputLanguagesSeeded = boolean(
            key = "dictate__input_languages_seeded",
            default = false,
        )
        // Guard so the one-time import from the legacy Dictate SharedPreferences runs only once.
        val legacyImported = boolean(
            key = "dictate__legacy_imported",
            default = false,
        )
        // Guard for the one-time injection of the live-prompt Smartbar action into arrangements that
        // were saved before the action existed (otherwise upgrading users never see it).
        val livePromptActionMigrated = boolean(
            key = "dictate__live_prompt_action_migrated",
            default = false,
        )
        // Same one-time injection for the AI prompt-panel Smartbar action (DICTATE_PROMPTS).
        val promptsActionMigrated = boolean(
            key = "dictate__prompts_action_migrated",
            default = false,
        )
        // Guard for the one-time *removal* of the live-prompt Smartbar action: the live prompt is now a
        // chip inside the prompt panel/row, so it no longer ships as a separate Smartbar button. Strips
        // any previously-injected DICTATE_LIVE_PROMPT action from saved arrangements exactly once.
        val livePromptActionRemoved = boolean(
            key = "dictate__live_prompt_action_removed",
            default = false,
        )
        // Guard for the one-time switch of existing users to the always-on prompt ROW layout (the new
        // default). Fires once so users who were on PANEL land on ROW after the update; they can switch
        // back any time. See DictateLegacyMigrator.migratePromptsLayoutToRowIfNeeded.
        val promptsLayoutRowMigrated = boolean(
            key = "dictate__prompts_layout_row_migrated",
            default = false,
        )
        // Guard for the one-time re-engagement reset shipped with the 4.0.0 relaunch: existing users
        // (who had already rated/donated, or whose audio counter was long past the thresholds) are
        // given the rate & donate nudges one more time so they can react to the new app. Clears
        // hasRated/hasDonated and resets totalAudioSeconds exactly once. See
        // DictateLegacyMigrator.reofferRateAndDonateIfNeeded.
        val promoReengagementDone = boolean(
            key = "dictate__promo_reengagement_done",
            default = false,
        )

        // --- Rewording / GPT (roadmap section 4) -------------------------------------------------
        // Master switch for the rewording feature (prompt chips, auto-apply, live prompt). Default
        // on, mirroring the legacy app.
        val rewordingEnabled = boolean(
            key = "dictate__rewording_enabled",
            default = true,
        )
        // Reasoning effort sent as OpenAI-compatible `reasoning_effort` on rewording chat calls for
        // reasoning models (issue #141). OFF omits the field, so non-reasoning models are unaffected.
        val rewordingReasoningEffort = enum(
            key = "dictate__rewording_reasoning_effort",
            default = DictateReasoningEffort.OFF,
        )
        // How the rewording prompt chips are surfaced: a dedicated panel (PANEL) opened from the
        // Smartbar, or an always-on extra row pinned above the Smartbar (ROW). See DictatePromptsLayout.
        // Defaults to ROW so the prompts are immediately visible; existing users are moved to ROW once via
        // DictateLegacyMigrator.migratePromptsLayoutToRowIfNeeded.
        val promptsLayout = enum(
            key = "dictate__prompts_layout",
            default = DictatePromptsLayout.ROW,
        )
        // Classic keyboard-less "legacy" dictation layout (issue #125): OFF = modern keyboard (default);
        // LOCKED = only the legacy record-first UI; SWIPE = legacy UI as home, horizontal swipe flips to
        // the modern typing keyboard and back. See DictateLegacyLayout / LegacyDictateLayout.
        val legacyLayout = enum(
            key = "dictate__legacy_layout",
            default = DictateLegacyLayout.OFF,
        )
        // Chat (rewording) provider id – any chat-capable ProviderRegistry id ("openai", "groq",
        // "openrouter", … or "custom"). Independent from the transcription provider.
        val rewordingProviderId = string(
            key = "dictate__rewording_provider_id",
            default = "openai",
        )
        // --- DEPRECATED flat rewording credential prefs (migration source only) ------------------
        // Kept solely for the one-time keyring import; live calls read providerAccounts instead.
        @Deprecated("Migrated into providerAccounts; read the keyring instead.")
        val rewordingApiKey = string(
            key = "dictate__rewording_api_key",
            default = "",
        )
        @Deprecated("Migrated into providerAccounts; read the keyring instead.")
        val rewordingModel = string(
            key = "dictate__rewording_model",
            default = "",
        )
        @Deprecated("Migrated into providerAccounts; read the keyring instead.")
        val rewordingCustomBaseUrl = string(
            key = "dictate__rewording_custom_base_url",
            default = "",
        )
        // System prompt appended to every rewording request: 0 = none, 1 = predefined (be-precise),
        // 2 = custom. See DictatePromptDefaults.SELECTION_*.
        val systemPromptSelection = int(
            key = "dictate__system_prompt_selection",
            default = 1,
        )
        val systemPromptCustom = string(
            key = "dictate__system_prompt_custom",
            default = "",
        )
        // Style prompt biasing the transcription model (roadmap 2.4): 0 = none, 1 = predefined
        // per-language punctuation/capitalization sentence, 2 = custom.
        val stylePromptSelection = int(
            key = "dictate__style_prompt_selection",
            default = 1,
        )
        val stylePromptCustom = string(
            key = "dictate__style_prompt_custom",
            default = "",
        )
        // Custom vocabulary (roadmap 11.12): names/jargon appended to the transcription prompt so the
        // speech model spells them correctly. Comma- or newline-separated; empty = unused. Applied on
        // top of whatever style prompt (none/predefined/custom) is active.
        val customWords = string(
            key = "dictate__custom_words",
            default = "",
        )
        // Custom mappings (issue #129): deterministic find-and-replace applied to the finished transcript
        // before it is inserted — exact and token-free, unlike the prompt-hint customWords above.
        val customMappings = custom(
            key = "dictate__custom_mappings",
            default = DictateMappings.Empty,
            serializer = DictateMappings.Serializer,
        )
        // Run the spoken-formatting-cues → Markdown pass automatically on every transcript.
        val autoFormattingEnabled = boolean(
            key = "dictate__auto_formatting_enabled",
            default = false,
        )
    }

    val dictionary = Dictionary()
    inner class Dictionary {
        val enableSystemUserDictionary = boolean(
            key = "suggestion__enable_system_user_dictionary",
            default = true,
        )
        val enableFlorisUserDictionary = boolean(
            key = "suggestion__enable_floris_user_dictionary",
            default = true,
        )
    }

    val emoji = Emoji()
    inner class Emoji {
        val preferredSkinTone = enum(
            key = "emoji__preferred_skin_tone",
            default = EmojiSkinTone.DEFAULT,
        )
        val preferredHairStyle = enum(
            key = "emoji__preferred_hair_style",
            default = EmojiHairStyle.DEFAULT,
        )
        val historyEnabled = boolean(
            key = "emoji__history_enabled",
            default = true,
        )
        val historyData = custom(
            key = "emoji__history_data",
            default = EmojiHistory.Empty,
            serializer = EmojiHistory.Serializer,
        )
        val historyPinnedUpdateStrategy = enum(
            key = "emoji__history_pinned_update_strategy",
            default = EmojiHistory.UpdateStrategy.MANUAL_SORT_PREPEND,
        )
        val historyPinnedMaxSize = int(
            key = "emoji__history_pinned_max_size",
            default = EmojiHistory.MaxSizeUnlimited,
        )
        val historyRecentUpdateStrategy = enum(
            key = "emoji__history_recent_update_strategy",
            default = EmojiHistory.UpdateStrategy.AUTO_SORT_PREPEND,
        )
        val historyRecentMaxSize = int(
            key = "emoji__history_recent_max_size",
            default = 90,
        )
        val suggestionEnabled = boolean(
            key = "emoji__suggestion_enabled",
            default = true,
        )
        val suggestionType = enum(
            key = "emoji__suggestion_type",
            default = EmojiSuggestionType.LEADING_COLON,
        )
        val suggestionUpdateHistory = boolean(
            key = "emoji__suggestion_update_history",
            default = true,
        )
        val suggestionCandidateShowName = boolean(
            key = "emoji__suggestion_candidate_show_name",
            default = false,
        )
        val suggestionQueryMinLength = int(
            key = "emoji__suggestion_query_min_length",
            default = 3,
        )
        val suggestionCandidateMaxCount = int(
            key = "emoji__suggestion_candidate_max_count",
            default = 5,
        )
    }

    val gestures = Gestures()
    inner class Gestures {
        val swipeUp = enum(
            key = "gestures__swipe_up",
            default = SwipeAction.SHIFT,
        )
        val swipeDown = enum(
            key = "gestures__swipe_down",
            default = SwipeAction.HIDE_KEYBOARD,
        )
        val swipeLeft = enum(
            key = "gestures__swipe_left",
            default = SwipeAction.SWITCH_TO_NEXT_SUBTYPE,
        )
        val swipeRight = enum(
            key = "gestures__swipe_right",
            default = SwipeAction.SWITCH_TO_PREV_SUBTYPE,
        )
        val spaceBarSwipeUp = enum(
            key = "gestures__space_bar_swipe_up",
            default = SwipeAction.NO_ACTION,
        )
        val spaceBarSwipeLeft = enum(
            key = "gestures__space_bar_swipe_left",
            default = SwipeAction.MOVE_CURSOR_LEFT,
        )
        val spaceBarSwipeRight = enum(
            key = "gestures__space_bar_swipe_right",
            default = SwipeAction.MOVE_CURSOR_RIGHT,
        )
        val spaceBarLongPress = enum(
            key = "gestures__space_bar_long_press",
            default = SwipeAction.SHOW_INPUT_METHOD_PICKER,
        )
        val deleteKeySwipeLeft = enum(
            key = "gestures__delete_key_swipe_left",
            default = SwipeAction.DELETE_CHARACTERS_PRECISELY,
        )
        val deleteKeyLongPress = enum(
            key = "gestures__delete_key_long_press",
            default = SwipeAction.DELETE_CHARACTER,
        )
        val swipeDistanceThreshold = int(
            key = "gestures__swipe_distance_threshold",
            default = 32,
        )
        val swipeVelocityThreshold = int(
            key = "gestures__swipe_velocity_threshold",
            default = 1900,
        )
    }

    val glide = Glide()
    inner class Glide {
        val enabled = boolean(
            key = "glide__enabled",
            default = false,
        )
        val showTrail = boolean(
            key = "glide__show_trail",
            default = true,
        )
        val trailDuration = int(
            key = "glide__trail_fade_duration",
            default = 200,
        )
        val showPreview = boolean(
            key = "glide__show_preview",
            default = true,
        )
        val previewRefreshDelay = int(
            key = "glide__preview_refresh_delay",
            default = 150,
        )
        val immediateBackspaceDeletesWord = boolean(
            key = "glide__immediate_backspace_deletes_word",
            default = true,
        )
    }

    val inputFeedback = InputFeedback()
    inner class InputFeedback {
        val audioEnabled = boolean(
            key = "input_feedback__audio_enabled",
            default = true,
        )
        val audioActivationMode = enum(
            key = "input_feedback__audio_activation_mode",
            default = InputFeedbackActivationMode.RESPECT_SYSTEM_SETTINGS,
        )
        val audioVolume = int(
            key = "input_feedback__audio_volume",
            default = 50,
        )
        val audioFeatKeyPress = boolean(
            key = "input_feedback__audio_feat_key_press",
            default = true,
        )
        val audioFeatKeyLongPress = boolean(
            key = "input_feedback__audio_feat_key_long_press",
            default = false,
        )
        val audioFeatKeyRepeatedAction = boolean(
            key = "input_feedback__audio_feat_key_repeated_action",
            default = false,
        )
        val audioFeatGestureSwipe = boolean(
            key = "input_feedback__audio_feat_gesture_swipe",
            default = false,
        )
        val audioFeatGestureMovingSwipe = boolean(
            key = "input_feedback__audio_feat_gesture_moving_swipe",
            default = false,
        )

        val hapticEnabled = boolean(
            key = "input_feedback__haptic_enabled",
            default = true,
        )
        val hapticActivationMode = enum(
            key = "input_feedback__haptic_activation_mode",
            default = InputFeedbackActivationMode.RESPECT_SYSTEM_SETTINGS,
        )
        val hapticVibrationMode = enum(
            key = "input_feedback__haptic_vibration_mode",
            default = HapticVibrationMode.USE_VIBRATOR_DIRECTLY,
        )
        val hapticVibrationDuration = int(
            key = "input_feedback__haptic_vibration_duration",
            default = 10,
        )
        val hapticVibrationStrength = int(
            key = "input_feedback__haptic_vibration_strength",
            default = 5,
        )
        val hapticFeatKeyPress = boolean(
            key = "input_feedback__haptic_feat_key_press",
            default = true,
        )
        val hapticFeatKeyLongPress = boolean(
            key = "input_feedback__haptic_feat_key_long_press",
            default = false,
        )
        val hapticFeatKeyRepeatedAction = boolean(
            key = "input_feedback__haptic_feat_key_repeated_action",
            default = true,
        )
        val hapticFeatGestureSwipe = boolean(
            key = "input_feedback__haptic_feat_gesture_swipe",
            default = false,
        )
        val hapticFeatGestureMovingSwipe = boolean(
            key = "input_feedback__haptic_feat_gesture_moving_swipe",
            default = true,
        )
    }

    val internal = Internal()
    inner class Internal {
        val homeIsBetaToolboxCollapsed = boolean(
            key = "internal__home_is_beta_toolbox_collapsed_040a01",
            default = false,
        )
        val isImeSetUp = boolean(
            key = "internal__is_ime_set_up",
            default = false,
        )
        // One-shot signal set by the onboarding's optional floating-button step: completing setup flips
        // [isImeSetUp], which rebuilds the nav graph and resets the back stack to Home; this flag lets
        // FlorisAppActivity then navigate on to the floating-button settings once that reset has settled.
        val openFloatingButtonAfterSetup = boolean(
            key = "internal__open_floating_button_after_setup",
            default = false,
        )
        val versionOnInstall = string(
            key = "internal__version_on_install",
            default = VersionName.DEFAULT_RAW,
        )
        val versionLastUse = string(
            key = "internal__version_last_use",
            default = VersionName.DEFAULT_RAW,
        )
        val versionLastChangelog = string(
            key = "internal__version_last_changelog",
            default = VersionName.DEFAULT_RAW,
        )
        val versionLastWhatsNew = string(
            key = "internal__version_last_whats_new",
            default = VersionName.DEFAULT_RAW,
        )
        val notificationPermissionState = enum(
            key = "internal__notification_permission_state",
            default = NotificationPermissionState.NOT_SET,
        )
    }

    val keyboard = Keyboard()
    inner class Keyboard {
        val windowConfig = custom(
            key = "keyboard__window_config",
            default = emptyMap(),
            serializer = ImeWindowConfig.ByTypeSerializer,
        )
        val numberRow = boolean(
            key = "keyboard__number_row",
            default = false,
        )
        val hintedNumberRowEnabled = boolean(
            key = "keyboard__hinted_number_row_enabled",
            default = true,
        )
        val hintedNumberRowMode = enum(
            key = "keyboard__hinted_number_row_mode",
            default = KeyHintMode.SMART_PRIORITY,
        )
        val hintedSymbolsEnabled = boolean(
            key = "keyboard__hinted_symbols_enabled",
            default = true,
        )
        val hintedSymbolsMode = enum(
            key = "keyboard__hinted_symbols_mode",
            default = KeyHintMode.SMART_PRIORITY,
        )
        val utilityKeyEnabled = boolean(
            key = "keyboard__utility_key_enabled",
            default = true,
        )
        val utilityKeyAction = enum(
            key = "keyboard__utility_key_action",
            default = UtilityKeyAction.DYNAMIC_SWITCH_LANGUAGE_EMOJIS,
        )
        val spaceBarMode = enum(
            key = "keyboard__space_bar_display_mode",
            default = SpaceBarMode.CURRENT_LANGUAGE,
        )
        val capitalizationBehavior = enum(
            key = "keyboard__capitalization_behavior",
            default = CapitalizationBehavior.CAPSLOCK_BY_DOUBLE_TAP,
        )
        val fontSizeMultiplierPortrait = int(
            key = "keyboard__font_size_multiplier_portrait",
            default = 100,
        )
        val fontSizeMultiplierLandscape = int(
            key = "keyboard__font_size_multiplier_landscape",
            default = 100,
        )
        val landscapeInputUiMode = enum(
            key = "keyboard__landscape_input_ui_mode",
            default = LandscapeInputUiMode.DYNAMICALLY_SHOW,
        )
        val keySpacingVertical = int(
            key = "keyboard__key_spacing_vertical",
            default = 100,
        )
        val keySpacingHorizontal = int(
            key = "keyboard__key_spacing_horizontal",
            default = 100,
        )
        val popupEnabled = boolean(
            key = "keyboard__popup_enabled",
            default = true,
        )
        val mergeHintPopupsEnabled = boolean(
            key = "keyboard__merge_hint_popups_enabled",
            default = false,
        )
        val longPressDelay = int(
            key = "keyboard__long_press_delay",
            default = 300,
        )
        val spaceBarSwitchesToCharacters = boolean(
            key = "keyboard__space_bar_switches_to_characters",
            default = true,
        )
        val incognitoDisplayMode = enum(
            key = "keyboard__incognito_indicator",
            default = IncognitoDisplayMode.DISPLAY_BEHIND_KEYBOARD,
        )

        fun keyHintConfiguration(): KeyHintConfiguration {
            return KeyHintConfiguration(
                numberHintMode = when {
                    hintedNumberRowEnabled.get() -> hintedNumberRowMode.get()
                    else -> KeyHintMode.DISABLED
                },
                symbolHintMode = when {
                    hintedSymbolsEnabled.get() -> hintedSymbolsMode.get()
                    else -> KeyHintMode.DISABLED
                },
                mergeHintPopups = mergeHintPopupsEnabled.get(),
            )
        }
    }

    val localization = Localization()
    inner class Localization {
        val displayLanguageNamesIn = enum(
            key = "localization__display_language_names_in",
            default = DisplayLanguageNamesIn.SYSTEM_LOCALE,
        )
        val displayKeyboardLabelsInSubtypeLanguage = boolean(
            key = "localization__display_keyboard_labels_in_subtype_language",
            default = false,
        )
        val activeSubtypeId = long(
            key = "localization__active_subtype_id",
            default = Subtype.DEFAULT.id,
        )
        val subtypes = string(
            key = "localization__subtypes",
            default = "[]",
        )
    }

    val other = Other()
    inner class Other {
        val settingsTheme = enum(
            key = "other__settings_theme",
            default = AppTheme.AUTO,
        )
        val accentColor = custom(
            key = "other__accent_color",
            default = Color(0xFF30B7E6), // Dictate light blue
            serializer = ColorPreferenceSerializer,
        )
        val settingsLanguage = string(
            key = "other__settings_language",
            default = "auto",
        )
        val showAppIcon = boolean(
            key = "other__show_app_icon",
            default = true,
        )
    }

    val physicalKeyboard = PhysicalKeyboard()
    inner class PhysicalKeyboard {
        val showOnScreenKeyboard = boolean(
            key = "physical_keyboard__show_on_screen_keyboard",
            default = false,
        )
    }

    val smartbar = Smartbar()
    inner class Smartbar {
        val enabled = boolean(
            key = "smartbar__enabled",
            default = true,
        )
        val layout = enum(
            key = "smartbar__layout",
            default = SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED,
        )
        val actionArrangement = custom(
            key = "smartbar__action_arrangement",
            default = QuickActionArrangement.Default,
            serializer = QuickActionArrangement.Serializer,
        )
        val flipToggles = boolean(
            key = "smartbar__flip_toggles",
            default = false,
        )
        val sharedActionsExpanded = boolean(
            key = "smartbar__shared_actions_expanded",
            default = false,
        )
        @Deprecated("Always enabled due to UX issues")
        val sharedActionsAutoExpandCollapse = boolean(
            key = "smartbar__shared_actions_auto_expand_collapse",
            default = true,
        )
        val sharedActionsExpandWithAnimation = boolean(
            key = "smartbar__shared_actions_expand_with_animation",
            default = true,
        )
        val extendedActionsExpanded = boolean(
            key = "smartbar__extended_actions_expanded",
            default = false,
        )
        val extendedActionsPlacement = enum(
            key = "smartbar__extended_actions_placement",
            default = ExtendedActionsPlacement.ABOVE_CANDIDATES,
        )
    }

    val spelling = Spelling()
    inner class Spelling {
        val languageMode = enum(
            key = "spelling__language_mode",
            default = SpellingLanguageMode.USE_KEYBOARD_SUBTYPES,
        )
        val useContacts = boolean(
            key = "spelling__use_contacts",
            default = true,
        )
        val useUdmEntries = boolean(
            key = "spelling__use_udm_entries",
            default = true,
        )
    }

    val suggestion = Suggestion()
    inner class Suggestion {
        val api30InlineSuggestionsEnabled = boolean(
            key = "suggestion__api30_inline_suggestions_enabled",
            default = true,
        )
        val enabled = boolean(
            key = "suggestion__enabled",
            default = true,
        )
        // Autocorrect the typed word on space/punctuation when it looks like a typo (issue #127). Gated by
        // [enabled]; on by default like other keyboards, with its own switch so suggestions can stay on
        // without autocorrect.
        val autoCorrect = boolean(
            key = "suggestion__auto_correct",
            default = true,
        )
        val displayMode = enum(
            key = "suggestion__display_mode",
            default = CandidatesDisplayMode.DYNAMIC_SCROLLABLE,
        )
        val incognitoMode = enum(
            key = "suggestion__incognito_mode",
            default = IncognitoMode.DYNAMIC_ON_OFF,
        )
        // Internal pref
        val forceIncognitoModeFromDynamic = boolean(
            key = "suggestion__force_incognito_mode_from_dynamic",
            default = false,
        )
    }

    val theme = Theme()
    inner class Theme {
        val mode = enum(
            key = "theme__mode",
            default = ThemeMode.FOLLOW_SYSTEM,
        )
        val dayThemeId = custom(
            key = "theme__day_theme_id",
            default = extCoreTheme("floris_day"),
            serializer = ExtensionComponentName.Serializer,
        )
        val nightThemeId = custom(
            key = "theme__night_theme_id",
            default = extCoreTheme("floris_night"),
            serializer = ExtensionComponentName.Serializer,
        )
        val accentColor = custom(
            key = "theme__accent_color",
            default = Color(0xFF30B7E6), // Dictate light blue
            serializer = ColorPreferenceSerializer,
        )
        val sunriseTime = localTime(
            key = "theme__sunrise_time",
            default = LocalTime(6, 0),
        )
        val sunsetTime = localTime(
            key = "theme__sunset_time",
            default = LocalTime(18, 0),
        )
        val editorColorRepresentation = enum(
            key = "theme__editor_color_representation",
            default = ColorRepresentation.HEX,
        )
        val editorDisplayKbdAfterDialogs = enum(
            key = "theme__editor_display_kbd_after_dialogs",
            default = DisplayKbdAfterDialogs.REMEMBER,
        )
        val editorLevel = enum(
            key = "theme__editor_level",
            default = SnyggLevel.ADVANCED,
        )
    }

    override fun migrate(entry: PreferenceMigrationEntry): PreferenceMigrationEntry {
        return when (entry.key) {

            // Migrate media prefs to emoji prefs
            // Keep migration rule until: 0.6 dev cycle
            "media__emoji_recently_used" -> {
                val emojiValues = entry.rawValue.split(";")
                val recent = emojiValues.map {
                    dev.patrickgold.florisboard.ime.media.emoji.Emoji(it, "", emptyList())
                }
                val data = EmojiHistory(emptyList(), recent)
                entry.transform(key = "emoji__history_data", rawValue = Json.encodeToString(data))
            }
            "media__emoji_recently_used_max_size" -> {
                entry.transform(key = "emoji__history_recent_max_size")
            }

            // Migrate advanced prefs to other prefs
            // Keep migration rules until: 0.7 dev cycle
            "advanced__settings_theme" -> {
                entry.transform(key = "other__settings_theme")
            }
            "advanced__accent_color" -> {
                entry.transform(key = "other__accent_color")
            }
            "advanced__settings_language" -> {
                entry.transform(key = "other__settings_language")
            }
            "advanced__show_app_icon" -> {
                entry.transform(key = "other__show_app_icon")
            }
            "advanced__incognito_mode" -> {
                entry.transform(key = "suggestion__incognito_mode")
            }
            "advanced__force_incognito_mode_from_dynamic" -> {
                entry.transform(key = "suggestion__force_incognito_mode_from_dynamic")
            }
            // Migrate clipboard suggestion prefs to clipboard
            // Keep migration rules until: 0.7 dev cycle
            "suggestion__clipboard_content_enabled" -> {
                entry.transform(key = "clipboard__suggestion_enabled")
            }
            "suggestion__clipboard_content_timeout" -> {
                entry.transform(key = "clipboard__suggestion_timeout")
            }

            //Migrate one hand mode prefs keep until: 0.7 dev cycle
            "keyboard__one_handed_mode" -> {
                if (entry.rawValue == "OFF") {
                    entry.reset()
                } else {
                    entry.keepAsIs()
                }
            }
            "smartbar__action_arrangement" -> {
                fun migrateAction(action: QuickAction): QuickAction {
                    return if (action is QuickAction.InsertKey && action.data.code == KeyCode.COMPACT_LAYOUT_TO_RIGHT) {
                        action.copy(data = TextKeyData.TOGGLE_COMPACT_LAYOUT)
                    } else {
                        action
                    }
                }

                val arrangement = QuickActionJsonConfig.decodeFromString<QuickActionArrangement>(entry.rawValue)
                var newArrangement = arrangement.copy(
                    stickyAction = arrangement.stickyAction?.let{ migrateAction(it) },
                    dynamicActions = arrangement.dynamicActions.map { migrateAction(it) },
                    hiddenActions = arrangement.hiddenActions.map { migrateAction(it) },
                )
                if (QuickAction.InsertKey(TextKeyData.LANGUAGE_SWITCH) !in newArrangement) {
                    newArrangement = newArrangement.copy(
                        dynamicActions = newArrangement.dynamicActions.plus(QuickAction.InsertKey(TextKeyData.LANGUAGE_SWITCH))
                    )
                }
                if (QuickAction.InsertKey(TextKeyData.FORWARD_DELETE) !in newArrangement) {
                    newArrangement = newArrangement.copy(
                        dynamicActions = newArrangement.dynamicActions.plus(QuickAction.InsertKey(TextKeyData.FORWARD_DELETE))
                    )
                }
                if (QuickAction.InsertKey(TextKeyData.IME_HIDE_UI) !in newArrangement) {
                    newArrangement = newArrangement.copy(
                        dynamicActions = newArrangement.dynamicActions.plus(QuickAction.InsertKey(TextKeyData.IME_HIDE_UI))
                    )
                }
                if (QuickAction.InsertKey(TextKeyData.TOGGLE_FLOATING_WINDOW) !in newArrangement) {
                    newArrangement = newArrangement.copy(
                        dynamicActions = newArrangement.dynamicActions.plus(QuickAction.InsertKey(TextKeyData.TOGGLE_FLOATING_WINDOW))
                    )
                }
                if (QuickAction.InsertKey(TextKeyData.TOGGLE_RESIZE_MODE) !in newArrangement) {
                    newArrangement = newArrangement.copy(
                        dynamicActions = newArrangement.dynamicActions.plus(QuickAction.InsertKey(TextKeyData.TOGGLE_RESIZE_MODE))
                    )
                }
                val json = QuickActionJsonConfig.encodeToString(newArrangement.distinct())
                entry.transform(rawValue = json)
            }

            // Migrate theme editor fine-tuning
            // Keep migration rule until: 0.6 dev cycle
            "theme__editor_display_colors_as" -> {
                val colorRepresentation = when (entry.rawValue) {
                    "RGBA" -> ColorRepresentation.RGB
                    else -> ColorRepresentation.HEX
                }
                entry.transform(
                    key = "theme__editor_color_representation",
                    rawValue = colorRepresentation.name,
                )
            }

            // Migrate clipboard history pref names
            // Keep migration rules until: 0.7 dev cycle
            "clipboard__sync_to_floris", "clipboard__sync_to_system" -> {
                entry.transform(
                    type = PreferenceType.string(),
                    rawValue = when (entry.rawValue) {
                        "true" -> ClipboardSyncBehavior.ALL_EVENTS.name
                        "false" -> ClipboardSyncBehavior.NO_EVENTS.name
                        else -> entry.rawValue
                    },
                )
            }
            "clipboard__num_history_grid_columns_portrait" -> {
                entry.transform(key = "clipboard__history_num_grid_columns_portrait")
            }
            "clipboard__num_history_grid_columns_landscape" -> {
                entry.transform(key = "clipboard__history_num_grid_columns_landscape")
            }
            "clipboard__clean_up_old" -> {
                entry.transform(key = "clipboard__history_auto_clean_old_enabled")
            }
            "clipboard__clean_up_after" -> {
                entry.transform(key = "clipboard__history_auto_clean_old_after")
            }
            "clipboard__auto_clean_sensitive" -> {
                entry.transform(key = "clipboard__history_auto_clean_sensitive_enabled")
            }
            "clipboard__auto_clean_sensitive_after" -> {
                entry.transform(key = "clipboard__history_auto_clean_sensitive_after")
            }
            "clipboard__limit_history_size" -> {
                entry.transform(key = "clipboard__history_size_limit_enabled")
            }
            "clipboard__max_history_size" -> {
                entry.transform(key = "clipboard__history_size_limit")
            }
            "clipboard__clear_primary_clip_deletes_last_item" -> {
                entry.transform(key = "clipboard__clear_primary_clip_affects_history_if_unpinned")
            }

            // Migrate key spacing rules
            // Keep migration rules until: 0.8 dev cycle
            "keyboard__key_spacing_horizontal" -> {
                if (entry.type.isFloat()) {
                    entry.reset()
                } else {
                    entry.keepAsIs()
                }
            }
            "keyboard__key_spacing_vertical" -> {
                if (entry.type.isFloat()) {
                    entry.reset()
                } else {
                    entry.keepAsIs()
                }
            }

            // Default: keep entry
            else -> entry.keepAsIs()
        }
    }
}
