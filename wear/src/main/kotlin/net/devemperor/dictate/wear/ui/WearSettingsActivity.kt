/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.devemperor.dictate.wear.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import dev.patrickgold.florisboard.dictate.sync.DictateSyncedSettings
import net.devemperor.dictate.wear.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.devemperor.dictate.wear.audio.WearAudioRecorder
import net.devemperor.dictate.wear.sync.WearSettingsStore
import net.devemperor.dictate.wear.sync.WearSyncClient
import net.devemperor.dictate.wear.transcribe.WearTranscription

/**
 * On-watch settings, onboarding & status (#106). Besides the microphone permission, the connection/sync
 * state and a manual re-sync, this launcher screen now:
 *
 *  - lets the reviewer/user **dictate right here** via a self-contained "Try dictation" button (records
 *    → transcribes → shows the text in-app), so the core feature is demonstrable without first enabling
 *    the keyboard, and
 *  - guides enabling and selecting the **Dictate keyboard** (this app is an input method), since that is
 *    how dictation reaches other apps.
 *
 * Provider/model and the rest are synced read-only from the phone; the watch transcribes through the
 * phone when it is reachable and on its own otherwise (no mode toggle needed).
 */
class WearSettingsActivity : ComponentActivity() {

    private var micGranted by mutableStateOf(false)
    private var imeEnabled by mutableStateOf(false)
    private var imeSelected by mutableStateOf(false)

    // In-app "Try dictation" demo state (independent of the IME).
    private var demoState by mutableStateOf(DemoState.IDLE)
    private var demoResult by mutableStateOf<String?>(null)
    private var demoError by mutableStateOf<String?>(null)

    private val recorder by lazy { WearAudioRecorder(applicationContext) }

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshMic()
        refreshImeState()

        WearSettingsStore.load(this)
        syncFromPhone()

        // App version, shown at the bottom and explicitly labelled "Wear OS" so a self-built / GitHub
        // release is unmistakably the watch APK (its versionCode also lives in the +100000 band).
        val pkgInfo = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val versionName = pkgInfo?.versionName.orEmpty()
        val versionCode = pkgInfo?.longVersionCode ?: 0L

        setContent {
            WearDictateTheme {
                val synced by WearSettingsStore.settings.collectAsState()
                var phoneConnected by remember { mutableStateOf<Boolean?>(null) }

                LaunchedEffect(Unit) {
                    phoneConnected = runCatching {
                        WearSyncClient.findPhoneNodeId(this@WearSettingsActivity) != null
                    }.getOrDefault(false)
                }

                SettingsScreen(
                    micGranted = micGranted,
                    imeEnabled = imeEnabled,
                    imeSelected = imeSelected,
                    demoState = demoState,
                    demoResult = demoResult,
                    demoError = demoError,
                    phoneConnected = phoneConnected,
                    synced = synced,
                    versionName = versionName,
                    versionCode = versionCode,
                    onRequestMic = { requestMic.launch(Manifest.permission.RECORD_AUDIO) },
                    onTryDictation = { onTryDictation() },
                    onEnableKeyboard = { openInputMethodSettings() },
                    onChooseKeyboard = { showKeyboardPicker() },
                    onResync = {
                        syncFromPhone()
                        lifecycleScope.launch {
                            phoneConnected = runCatching {
                                WearSyncClient.findPhoneNodeId(this@WearSettingsActivity) != null
                            }.getOrDefault(false)
                        }
                    },
                    onSetStandalone = { enabled ->
                        lifecycleScope.launch {
                            runCatching { WearSyncClient.setStandalone(this@WearSettingsActivity, enabled) }
                        }
                    },
                    onSetAutoRewording = { enabled ->
                        lifecycleScope.launch {
                            runCatching { WearSyncClient.setAutoRewording(this@WearSettingsActivity, enabled) }
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshMic()
        refreshImeState()
        // Opening (or returning to) settings should always reconcile with the phone, as requested.
        syncFromPhone()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (recorder.isRecording) recorder.cancel()
    }

    /**
     * Pull the phone's current settings: read the latest replicated DataItem (works even right after a
     * flaky reconnect) AND nudge the phone to (re)publish, so a change made on the phone — accent color,
     * provider, prompt — lands on the watch every time settings are opened.
     */
    private fun syncFromPhone() {
        lifecycleScope.launch {
            runCatching { WearSyncClient.requestSettingsSync(this@WearSettingsActivity) }
            runCatching { WearSyncClient.fetchPublishedSettings(this@WearSettingsActivity) }
                .getOrNull()
                ?.let { WearSettingsStore.save(this@WearSettingsActivity, it) }
        }
    }

    private fun refreshMic() {
        micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Reflect whether the Dictate keyboard is enabled in the system and currently selected as default. */
    private fun refreshImeState() {
        val imm = getSystemService(InputMethodManager::class.java)
        imeEnabled = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
        val default = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull()
        imeSelected = default?.contains(packageName) == true
    }

    private fun openInputMethodSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun showKeyboardPicker() {
        runCatching { getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() }
    }

    // --- In-app "Try dictation" demo ---------------------------------------------------------------

    private fun onTryDictation() {
        if (!micGranted) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        when (demoState) {
            DemoState.RECORDING -> stopDemoAndTranscribe()
            DemoState.TRANSCRIBING -> Unit
            DemoState.IDLE -> startDemo()
        }
    }

    private fun startDemo() {
        demoError = null
        demoResult = null
        val started = runCatching { recorder.start() }
        if (started.isSuccess) {
            demoState = DemoState.RECORDING
        } else {
            demoError = started.exceptionOrNull()?.shortReason() ?: getString(R.string.wear_err_mic_unavailable)
        }
    }

    private fun stopDemoAndTranscribe() {
        demoState = DemoState.TRANSCRIBING
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val audio = recorder.stop()
                    try {
                        WearTranscription.transcribe(applicationContext, audio)
                    } finally {
                        audio.delete()
                    }
                }
            }
            val text = outcome.getOrNull()
            when {
                outcome.isFailure ->
                    demoError = outcome.exceptionOrNull()?.shortReason() ?: getString(R.string.wear_err_transcribe_failed)
                text.isNullOrBlank() ->
                    demoError = getString(R.string.wear_err_empty)
                else -> {
                    demoResult = text
                    demoError = null
                }
            }
            demoState = DemoState.IDLE
        }
    }

    private fun Throwable.shortReason(): String =
        (message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: getString(R.string.wear_err_unknown)).take(80)
}

/** State of the self-contained in-app dictation demo. */
private enum class DemoState { IDLE, RECORDING, TRANSCRIBING }

@Composable
private fun SettingsScreen(
    micGranted: Boolean,
    imeEnabled: Boolean,
    imeSelected: Boolean,
    demoState: DemoState,
    demoResult: String?,
    demoError: String?,
    phoneConnected: Boolean?,
    synced: DictateSyncedSettings,
    versionName: String,
    versionCode: Long,
    onRequestMic: () -> Unit,
    onTryDictation: () -> Unit,
    onEnableKeyboard: () -> Unit,
    onChooseKeyboard: () -> Unit,
    onResync: () -> Unit,
    onSetStandalone: (Boolean) -> Unit,
    onSetAutoRewording: (Boolean) -> Unit,
) {
    val listState = rememberScalingLazyListState()

    Scaffold(
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        timeText = { TimeText() },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { ListHeader { Text(stringResource(R.string.wear_app_name), fontWeight = FontWeight.SemiBold) } }

            // --- Status card: connection + active provider/model + resolved transcription path ---
            item { StatusCard(phoneConnected = phoneConnected, synced = synced) }

            // --- Try dictation right here (self-contained, no keyboard needed) — kept at the top so the
            //     core feature is demonstrable/usable before any setup. ---
            item { ListHeader { Text(stringResource(R.string.wear_sec_try), fontWeight = FontWeight.SemiBold) } }
            item {
                val label = when (demoState) {
                    DemoState.RECORDING -> "■  " + stringResource(R.string.wear_try_stop)
                    DemoState.TRANSCRIBING -> stringResource(R.string.wear_status_transcribing)
                    DemoState.IDLE -> "🎤  " + stringResource(R.string.wear_try)
                }
                Chip(
                    onClick = onTryDictation,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.primaryChipColors(),
                    label = { Text(label) },
                    secondaryLabel = when (demoState) {
                        DemoState.IDLE -> { { Text(stringResource(R.string.wear_try_hint)) } }
                        else -> null
                    },
                )
            }
            if (demoResult != null) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.wear_you_said),
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.primary,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = demoResult,
                            style = MaterialTheme.typography.body2,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            if (demoError != null) {
                item {
                    Text(
                        text = demoError,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // --- Setup: what dictation needs to work in other apps — mic permission, then the keyboard
            //     (Dictate is an input method) enabled + selected. ---
            item { ListHeader { Text(stringResource(R.string.wear_sec_setup), fontWeight = FontWeight.SemiBold) } }
            item {
                Chip(
                    onClick = onRequestMic,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (micGranted) ChipDefaults.secondaryChipColors() else ChipDefaults.primaryChipColors(),
                    label = { Text(if (micGranted) stringResource(R.string.wear_mic_ok) else stringResource(R.string.wear_mic_grant)) },
                    secondaryLabel = if (micGranted) null else { { Text(stringResource(R.string.wear_mic_required)) } },
                )
            }
            item {
                Chip(
                    onClick = onEnableKeyboard,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (imeEnabled) ChipDefaults.secondaryChipColors() else ChipDefaults.primaryChipColors(),
                    label = { Text(if (imeEnabled) stringResource(R.string.wear_kb_enabled) else stringResource(R.string.wear_kb_enable)) },
                    secondaryLabel = {
                        Text(if (imeEnabled) stringResource(R.string.wear_kb_enable_on) else stringResource(R.string.wear_kb_enable_off))
                    },
                )
            }
            item {
                Chip(
                    onClick = onChooseKeyboard,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors(),
                    label = { Text(if (imeSelected) stringResource(R.string.wear_kb_chosen) else stringResource(R.string.wear_kb_choose)) },
                    secondaryLabel = { Text(stringResource(R.string.wear_kb_choose_sub)) },
                )
            }

            // --- Sync key to watch (mirrors the phone toggle; flipping it here updates the phone) ---
            item { ListHeader { Text(stringResource(R.string.wear_sec_advanced), fontWeight = FontWeight.SemiBold) } }
            item {
                var standalone by remember(synced.keySyncEnabled) { mutableStateOf(synced.keySyncEnabled) }
                ToggleChip(
                    checked = standalone,
                    onCheckedChange = { standalone = it; onSetStandalone(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.wear_key)) },
                    secondaryLabel = { Text(if (standalone) stringResource(R.string.wear_key_on) else stringResource(R.string.wear_key_off)) },
                    toggleControl = { Switch(checked = standalone) },
                )
            }

            // --- Auto-reword watch dictations (mirrors the phone toggle) ---
            item {
                var reword by remember(synced.autoRewordingEnabled) { mutableStateOf(synced.autoRewordingEnabled) }
                ToggleChip(
                    checked = reword,
                    onCheckedChange = { reword = it; onSetAutoRewording(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.wear_reword)) },
                    secondaryLabel = { Text(if (reword) stringResource(R.string.wear_reword_on) else stringResource(R.string.wear_reword_off)) },
                    toggleControl = { Switch(checked = reword) },
                )
            }

            // --- Manual re-sync ---
            item {
                Chip(
                    onClick = onResync,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors(),
                    label = { Text(stringResource(R.string.wear_resync)) },
                )
            }

            // --- Version, explicitly Wear-labelled (see onCreate) ---
            item {
                Text(
                    text = stringResource(R.string.wear_version, versionName, versionCode),
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    phoneConnected: Boolean?,
    synced: DictateSyncedSettings,
) {
    val connectionText = when (phoneConnected) {
        null -> stringResource(R.string.wear_conn_checking)
        true -> stringResource(R.string.wear_conn_ok)
        false -> stringResource(R.string.wear_conn_out)
    }
    // The transport the watch will actually use, mirroring WearTranscription's AUTO decision.
    val pathText = when {
        phoneConnected == true -> stringResource(R.string.wear_path_phone)
        synced.canStandalone -> stringResource(R.string.wear_path_watch)
        else -> stringResource(R.string.wear_path_sync)
    }
    val provider = synced.providerLabel.ifBlank { synced.transcriptionProviderId.ifBlank { "—" } } +
        (synced.model.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = (if (phoneConnected == true) "⌚↔📱  " else "⌚  ") + connectionText,
            style = MaterialTheme.typography.caption2,
            textAlign = TextAlign.Center,
        )
        Text(
            text = pathText,
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.primary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = provider,
            style = MaterialTheme.typography.caption1,
            textAlign = TextAlign.Center,
        )
    }
}
