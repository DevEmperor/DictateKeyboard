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

package dev.patrickgold.florisboard.app.settings.keyboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor
import dev.patrickgold.florisboard.app.enumDisplayEntriesOf
import dev.patrickgold.florisboard.ime.input.HapticVibrationMode
import dev.patrickgold.florisboard.ime.input.InputFeedbackActivationMode
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.DialogSliderPreference
import dev.patrickgold.jetpref.datastore.ui.ExperimentalJetPrefDatastoreUi
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import org.florisboard.lib.android.AndroidVersion
import org.florisboard.lib.android.systemServiceOrNull
import org.florisboard.lib.android.systemVibratorOrNull
import org.florisboard.lib.android.vibrate
import org.florisboard.lib.compose.FlorisWarningCard
import org.florisboard.lib.compose.stringRes

@OptIn(ExperimentalJetPrefDatastoreUi::class)
@Composable
fun InputFeedbackScreen() = FlorisScreen {
    title = stringRes(R.string.settings__input_feedback__title)
    previewFieldVisible = true
    iconSpaceReserved = false

    val context = LocalContext.current
    val vibrator = context.systemVibratorOrNull()
    val audioManager = context.systemServiceOrNull(AudioManager::class)

    content {
        val lifecycleOwner = LocalLifecycleOwner.current
        val audioEnabled by prefs.inputFeedback.audioEnabled.collectAsState()
        val audioActivationMode by prefs.inputFeedback.audioActivationMode.collectAsState()

        // Two gates outside this screen decide whether a key sound is ever heard, and both of them are
        // silent about it (issue #324): Android's own touch sounds setting, which our default
        // activation mode defers to and which ships *off* on many phones, and the ringer, which mutes
        // STREAM_SYSTEM on vibrate and on silent — that one even outranks "always".
        var ringerMutesSounds by remember { mutableStateOf(audioManager.mutesSystemSounds()) }
        var systemSoundsEnabled by remember { mutableStateOf(context.systemTouchSoundsEnabled()) }
        DisposableEffect(lifecycleOwner) {
            // Returning from the system sound settings must leave the card already gone.
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    ringerMutesSounds = audioManager.mutesSystemSounds()
                    systemSoundsEnabled = context.systemTouchSoundsEnabled()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            // The ringer, unlike the settings, can change while this screen stays open — the volume
            // rocker does it in one press.
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    ringerMutesSounds = audioManager.mutesSystemSounds()
                }
            }
            val filter = IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)
            if (AndroidVersion.ATLEAST_API33_T) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                runCatching { context.unregisterReceiver(receiver) }
            }
        }

        // Null while a key press can be heard; otherwise why it cannot, which is both what the card
        // says and what keeps the volume slider from previewing a sound the keyboard would not play.
        val silenceReason = when {
            ringerMutesSounds -> R.string.pref__input_feedback__audio_muted_by_ringer__warning
            audioActivationMode == InputFeedbackActivationMode.RESPECT_SYSTEM_SETTINGS &&
                !systemSoundsEnabled -> R.string.pref__input_feedback__audio_muted_by_system__warning
            else -> null
        }

        PreferenceGroup(title = stringRes(R.string.pref__input_feedback__group_audio__label)) {
            if (audioEnabled && silenceReason != null) {
                FlorisWarningCard(
                    modifier = Modifier.padding(all = 8.dp),
                    text = stringRes(silenceReason),
                    onClick = { openSystemSoundSettings(context) },
                )
            }
            ListPreference(
                listPref = prefs.inputFeedback.audioActivationMode,
                switchPref = prefs.inputFeedback.audioEnabled,
                modifier = Modifier.settingsSearchAnchor("pref__input_feedback__audio_enabled__label"),
                title = stringRes(R.string.pref__input_feedback__audio_enabled__label),
                summarySwitchDisabled = stringRes(R.string.pref__input_feedback__audio_enabled__summary_disabled),
                entries = enumDisplayEntriesOf(InputFeedbackActivationMode::class, "audio"),
            )
            DialogSliderPreference(
                prefs.inputFeedback.audioVolume,
                modifier = Modifier.settingsSearchAnchor("pref__input_feedback__audio_volume__label"),
                title = stringRes(R.string.pref__input_feedback__audio_volume__label),
                valueLabel = { stringRes(R.string.unit__percent__symbol, "v" to it) },
                min = 1,
                max = 100,
                stepIncrement = 1,
                onPreviewSelectedValue = { volume ->
                    // Every haptic slider buzzes as you drag it; this was the one control on the screen
                    // whose effect you could not check without leaving it. It plays through the same
                    // gates as a real key press, so a silent drag is answered by the card above rather
                    // than leaving the impression of a broken slider.
                    if (silenceReason == null) {
                        audioManager?.playSoundEffect(
                            AudioManager.FX_KEYPRESS_STANDARD,
                            (volume / 100f).coerceIn(0.01f, 1.0f),
                        )
                    }
                },
                enabledIf = { prefs.inputFeedback.audioEnabled isEqualTo true },
            )
            SwitchPreference(
                prefs.inputFeedback.audioFeatKeyPress,
                modifier = Modifier.settingsSearchAnchor("pref__input_feedback__audio_feat_key_press__label"),
                title = stringRes(R.string.pref__input_feedback__audio_feat_key_press__label),
                summary = stringRes(R.string.pref__input_feedback__any_feat_key_press__summary),
                enabledIf = { prefs.inputFeedback.audioEnabled isEqualTo true },
            )
            SwitchPreference(
                prefs.inputFeedback.audioFeatKeyLongPress,
                modifier = Modifier.settingsSearchAnchor("pref__input_feedback__audio_feat_key_long_press__label"),
                title = stringRes(R.string.pref__input_feedback__audio_feat_key_long_press__label),
                summary = stringRes(R.string.pref__input_feedback__any_feat_key_long_press__summary),
                enabledIf = { prefs.inputFeedback.audioEnabled isEqualTo true },
            )
            SwitchPreference(
                prefs.inputFeedback.audioFeatKeyRepeatedAction,
                modifier = Modifier.settingsSearchAnchor("pref__input_feedback__audio_feat_key_repeated_action__label"),
                title = stringRes(R.string.pref__input_feedback__audio_feat_key_repeated_action__label),
                summary = stringRes(R.string.pref__input_feedback__any_feat_key_repeated_action__summary),
                enabledIf = { prefs.inputFeedback.audioEnabled isEqualTo true },
            )
            SwitchPreference(
                prefs.inputFeedback.audioFeatGestureSwipe,
                modifier = Modifier.settingsSearchAnchor("pref__input_feedback__audio_feat_gesture_swipe__label"),
                title = stringRes(R.string.pref__input_feedback__audio_feat_gesture_swipe__label),
                summary = stringRes(R.string.pref__input_feedback__any_feat_gesture_swipe__summary),
                enabledIf = { prefs.inputFeedback.audioEnabled isEqualTo true },
            )
            SwitchPreference(
                prefs.inputFeedback.audioFeatGestureMovingSwipe,
                modifier = Modifier.settingsSearchAnchor("pref__input_feedback__audio_feat_gesture_moving_swipe__label"),
                title = stringRes(R.string.pref__input_feedback__audio_feat_gesture_moving_swipe__label),
                summary = stringRes(R.string.pref__input_feedback__any_feat_gesture_moving_swipe__summary),
                enabledIf = { prefs.inputFeedback.audioEnabled isEqualTo true },
            )
        }

        PreferenceGroup(title = stringRes(R.string.pref__input_feedback__group_haptic__label)) {
            ListPreference(
                listPref = prefs.inputFeedback.hapticActivationMode,
                switchPref = prefs.inputFeedback.hapticEnabled,
                modifier = Modifier.settingsSearchAnchor("pref__input_feedback__haptic_enabled__label"),
                title = stringRes(R.string.pref__input_feedback__haptic_enabled__label),
                summarySwitchDisabled = stringRes(R.string.pref__input_feedback__haptic_enabled__summary_disabled),
                entries = enumDisplayEntriesOf(InputFeedbackActivationMode::class, "haptic")
            )
            ListPreference(
                prefs.inputFeedback.hapticVibrationMode,
                modifier = Modifier.settingsSearchAnchor("pref__input_feedback__haptic_vibration_mode__label"),
                title = stringRes(R.string.pref__input_feedback__haptic_vibration_mode__label),
                enabledIf = { prefs.inputFeedback.hapticEnabled isEqualTo true },
                entries = enumDisplayEntriesOf(HapticVibrationMode::class),
            )
            DialogSliderPreference(
                prefs.inputFeedback.hapticVibrationDuration,
                modifier = Modifier.settingsSearchAnchor("pref__input_feedback__haptic_vibration_duration__label"),
                title = stringRes(R.string.pref__input_feedback__haptic_vibration_duration__label),
                valueLabel = { stringRes(R.string.unit__milliseconds__symbol, "v" to it) },
                summary = {
                    if (vibrator == null || !vibrator.hasVibrator()) {
                        stringRes(R.string.pref__input_feedback__haptic_vibration_strength__summary_no_vibrator)
                    } else {
                        stringRes(R.string.unit__milliseconds__symbol, "v" to it)
                    }
                },
                min = 1,
                max = 100,
                stepIncrement = 1,
                onPreviewSelectedValue = { duration ->
                    val strength = prefs.inputFeedback.hapticVibrationStrength.get()
                    vibrator?.vibrate(duration, strength)
                },
                enabledIf = {
                    prefs.inputFeedback.hapticEnabled isEqualTo true &&
                        prefs.inputFeedback.hapticVibrationMode isEqualTo HapticVibrationMode.USE_VIBRATOR_DIRECTLY &&
                        vibrator != null && vibrator.hasVibrator()
                },
            )
            DialogSliderPreference(
                prefs.inputFeedback.hapticVibrationStrength,
                modifier = Modifier.settingsSearchAnchor("pref__input_feedback__haptic_vibration_strength__label"),
                title = stringRes(R.string.pref__input_feedback__haptic_vibration_strength__label),
                valueLabel = { stringRes(R.string.unit__percent__symbol, "v" to it) },
                summary = { strength ->
                    if (vibrator == null || !vibrator.hasVibrator()) {
                        stringRes(R.string.pref__input_feedback__haptic_vibration_strength__summary_no_vibrator)
                    } else if (!vibrator.hasAmplitudeControl()) {
                        stringRes(R.string.pref__input_feedback__haptic_vibration_strength__summary_no_amplitude_ctrl)
                    } else {
                        stringRes(R.string.unit__percent__symbol, "v" to strength)
                    }
                },
                min = 1,
                max = 100,
                stepIncrement = 1,
                onPreviewSelectedValue = { strength ->
                    val duration = prefs.inputFeedback.hapticVibrationDuration.get()
                    vibrator?.vibrate(duration, strength)
                },
                enabledIf = {
                    prefs.inputFeedback.hapticEnabled isEqualTo true &&
                        prefs.inputFeedback.hapticVibrationMode isEqualTo HapticVibrationMode.USE_VIBRATOR_DIRECTLY &&
                        vibrator != null && vibrator.hasVibrator() &&
                        vibrator.hasAmplitudeControl()
                },
            )
            SwitchPreference(
                prefs.inputFeedback.hapticFeatKeyPress,
                modifier = Modifier.settingsSearchAnchor("pref__input_feedback__haptic_feat_key_press__label"),
                title = stringRes(R.string.pref__input_feedback__haptic_feat_key_press__label),
                summary = stringRes(R.string.pref__input_feedback__any_feat_key_press__summary),
                enabledIf = { prefs.inputFeedback.hapticEnabled isEqualTo true },
            )
            SwitchPreference(
                prefs.inputFeedback.hapticFeatKeyLongPress,
                modifier = Modifier.settingsSearchAnchor("pref__input_feedback__haptic_feat_key_long_press__label"),
                title = stringRes(R.string.pref__input_feedback__haptic_feat_key_long_press__label),
                summary = stringRes(R.string.pref__input_feedback__any_feat_key_long_press__summary),
                enabledIf = { prefs.inputFeedback.hapticEnabled isEqualTo true },
            )
            SwitchPreference(
                prefs.inputFeedback.hapticFeatKeyRepeatedAction,
                modifier = Modifier.settingsSearchAnchor("pref__input_feedback__haptic_feat_key_repeated_action__label"),
                title = stringRes(R.string.pref__input_feedback__haptic_feat_key_repeated_action__label),
                summary = stringRes(R.string.pref__input_feedback__any_feat_key_repeated_action__summary),
                enabledIf = { prefs.inputFeedback.hapticEnabled isEqualTo true },
            )
            SwitchPreference(
                prefs.inputFeedback.hapticFeatGestureSwipe,
                modifier = Modifier.settingsSearchAnchor("pref__input_feedback__haptic_feat_gesture_swipe__label"),
                title = stringRes(R.string.pref__input_feedback__haptic_feat_gesture_swipe__label),
                summary = stringRes(R.string.pref__input_feedback__any_feat_gesture_swipe__summary),
                enabledIf = { prefs.inputFeedback.hapticEnabled isEqualTo true },
            )
            SwitchPreference(
                prefs.inputFeedback.hapticFeatGestureMovingSwipe,
                modifier = Modifier.settingsSearchAnchor("pref__input_feedback__haptic_feat_gesture_moving_swipe__label"),
                title = stringRes(R.string.pref__input_feedback__haptic_feat_gesture_moving_swipe__label),
                summary = stringRes(R.string.pref__input_feedback__any_feat_gesture_moving_swipe__summary),
                enabledIf = { prefs.inputFeedback.hapticEnabled isEqualTo true },
            )
        }
    }
}

/**
 * Whether Android's own *touch sounds* are on — the setting our default activation mode defers to, and
 * the one that ships off on plenty of phones while touch vibration ships on. That asymmetry is why a
 * user sees the keyboard vibrate but never hears it, with both of our switches showing as enabled.
 */
private fun Context.systemTouchSoundsEnabled(): Boolean =
    Settings.System.getInt(contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 0) != 0

/**
 * Whether the ringer is currently swallowing key sounds. [AudioManager.playSoundEffect] ends up in the
 * platform's `AudioService`, which drops the effect while `STREAM_SYSTEM` is muted — vibrate and silent
 * both do that — however loudly this app asked for it, and whatever the activation mode says.
 */
private fun AudioManager?.mutesSystemSounds(): Boolean =
    this != null && ringerMode != AudioManager.RINGER_MODE_NORMAL

private fun openSystemSoundSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
