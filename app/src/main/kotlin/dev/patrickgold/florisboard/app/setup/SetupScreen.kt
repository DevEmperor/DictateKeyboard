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

package dev.patrickgold.florisboard.app.setup

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisAppActivity
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.dictate.cloud.DictateCloud
import dev.patrickgold.florisboard.dictate.ui.DictateWaveform
import dev.patrickgold.florisboard.app.settings.dictate.providerIcon
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.dictate.provider.ProviderAccounts
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.compose.FlorisScreenScope
import dev.patrickgold.florisboard.lib.util.InputMethodUtils
import dev.patrickgold.florisboard.lib.util.launchActivity
import dev.patrickgold.florisboard.lib.util.launchUrl
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.PreferenceUiScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.florisboard.lib.android.AndroidVersion
import org.florisboard.lib.compose.FlorisBulletSpacer
import org.florisboard.lib.compose.FlorisStep
import org.florisboard.lib.compose.FlorisStepLayout
import org.florisboard.lib.compose.FlorisStepLayoutScope
import org.florisboard.lib.compose.FlorisStepState
import org.florisboard.lib.compose.stringRes

/** The provider recommended to new (non-technical) users: fast and free for everyday dictation. */
private const val RECOMMENDED_PROVIDER_ID = "groq"

@Composable
fun SetupScreen() = FlorisScreen {
    title = stringRes(R.string.setup__title)
    navigationIconVisible = false
    scrollable = false

    val navController = LocalNavController.current
    val context = LocalContext.current

    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()

    val isFlorisBoardEnabled by InputMethodUtils.observeIsFlorisboardEnabled(foregroundOnly = true)
    val isFlorisBoardSelected by InputMethodUtils.observeIsFlorisboardSelected(foregroundOnly = true)
    val hasNotificationPermission by prefs.internal.notificationPermissionState.collectAsState()

    // Dictate onboarding: the active transcription provider must have a usable key (or be keyless)
    // before the user can dictate. This drives the new "Connect a free AI service" step.
    val accounts by prefs.dictate.providerAccounts.collectAsState()
    val activeProviderId by prefs.dictate.transcriptionProviderId.collectAsState()
    val isProviderConfigured = isProviderConfigured(accounts, activeProviderId)
    var providerSkipped by rememberSaveable { mutableStateOf(false) }
    // The floating-button step is optional and has no completion signal of its own, so (like the
    // provider step) a flag lets the user move past it to the final page once they've decided.
    var floatingButtonStepPassed by rememberSaveable { mutableStateOf(false) }

    val requestNotification =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            scope.launch {
                if (isGranted) {
                    prefs.internal.notificationPermissionState.set(NotificationPermissionState.GRANTED)
                } else {
                    prefs.internal.notificationPermissionState.set(NotificationPermissionState.DENIED)
                }
            }
        }

    var isMicGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val requestMic =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            isMicGranted = isGranted
        }

    content(
        isFlorisBoardEnabled,
        isFlorisBoardSelected,
        isMicGranted,
        isProviderConfigured,
        providerSkipped,
        { providerSkipped = true },
        floatingButtonStepPassed,
        { floatingButtonStepPassed = true },
        accounts,
        context,
        navController,
        requestNotification,
        requestMic,
        hasNotificationPermission,
        scope,
    )
}

/** Reads the current clipboard text (used to paste an API key without opening the on-screen keyboard). */
private fun readClipboardText(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    return cm.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
}

/** Masks an API key for on-screen confirmation, e.g. "gsk_…AB12" (keeps the ends, hides the middle). */
private fun maskKey(key: String): String =
    if (key.length > 8) "${key.take(4)}…${key.takeLast(4)}" else "•".repeat(key.length)

/** True once the active transcription provider has a saved key, or is a keyless endpoint (Ollama). */
private fun isProviderConfigured(accounts: ProviderAccounts, providerId: String): Boolean {
    if (accounts.getOrEmpty(providerId).hasKey) return true
    // Dictate Cloud has no key page either, but it is not a keyless endpoint: without credit
    // there is nothing to dictate with, so it must not pass as set up the way Ollama does.
    if (providerId == ProviderRegistry.CLOUD.id) return false
    val preset = ProviderRegistry.byId(providerId)
    return preset != null && preset.apiKeyUrl == null
}

@Composable
private fun FlorisScreenScope.content(
    isFlorisBoardEnabled: Boolean,
    isFlorisBoardSelected: Boolean,
    isMicGranted: Boolean,
    isProviderConfigured: Boolean,
    providerSkipped: Boolean,
    onSkipProvider: () -> Unit,
    floatingButtonStepPassed: Boolean,
    onPassFloatingButton: () -> Unit,
    accounts: ProviderAccounts,
    context: Context,
    navController: NavController,
    requestNotification: ManagedActivityResultLauncher<String, Boolean>,
    requestMic: ManagedActivityResultLauncher<String, Boolean>,
    hasNotificationPermission: NotificationPermissionState,
    scope: CoroutineScope,
) {

    fun targetStep(): Int = when {
        !isFlorisBoardEnabled -> Steps.EnableIme.id
        !isFlorisBoardSelected -> Steps.SelectIme.id
        !isMicGranted -> Steps.GrantMicPermission.id
        hasNotificationPermission == NotificationPermissionState.NOT_SET && AndroidVersion.ATLEAST_API33_T -> Steps.SelectNotification.id
        !isProviderConfigured && !providerSkipped -> Steps.SetUpProvider.id
        // Land on the optional floating-button step first, only moving on to the final page once the
        // user has explicitly decided to skip it or set it up.
        !floatingButtonStepPassed -> Steps.FloatingButton.id
        else -> Steps.FinishUp.id
    }

    val stepState = rememberSaveable(saver = FlorisStepState.Saver) {
        FlorisStepState.new(init = targetStep())
    }

    content {
        LaunchedEffect(
            isFlorisBoardEnabled, isFlorisBoardSelected, isMicGranted,
            hasNotificationPermission, isProviderConfigured, providerSkipped,
            floatingButtonStepPassed,
        ) {
            stepState.setCurrentAuto(targetStep())
        }

        // Below block allows to return from the system IME enabler activity
        // as soon as it gets selected.
        LaunchedEffect(Unit) {
            while (true) {
                delay(200L)
                val isEnabled = InputMethodUtils.isFlorisboardEnabled(context)
                if (stepState.getCurrentAuto().value == Steps.EnableIme.id &&
                    stepState.getCurrentManual().value == -1 &&
                    !isFlorisBoardEnabled &&
                    !isFlorisBoardSelected &&
                    hasNotificationPermission == NotificationPermissionState.NOT_SET &&
                    isEnabled
                ) {
                    context.launchActivity(FlorisAppActivity::class) {
                        it.flags = (Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                }
            }
        }
        FlorisStepLayout(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            stepState = stepState,
            backLabel = stringRes(R.string.setup__nav_back),
            nextLabel = stringRes(R.string.setup__nav_next),
            header = {
                StepText(stringRes(R.string.setup__intro_message))
                Spacer(modifier = Modifier.height(16.dp))
            },
            steps = steps(
                context, navController, requestNotification, requestMic,
                isProviderConfigured, onSkipProvider, onPassFloatingButton, accounts, scope,
            ),
            footer = {
                footer(context)
            },
        )
    }
}

@Composable
private fun footer(context: Context) {
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val privacyPolicyUrl = stringRes(R.string.florisboard__privacy_policy_url)
        TextButton(onClick = { context.launchUrl(privacyPolicyUrl) }) {
            Text(text = stringRes(R.string.setup__footer__privacy_policy))
        }
        FlorisBulletSpacer()
        val repositoryUrl = stringRes(R.string.florisboard__repo_url)
        TextButton(onClick = { context.launchUrl(repositoryUrl) }) {
            Text(text = stringRes(R.string.setup__footer__repository))
        }
    }
}

@Composable
private fun PreferenceUiScope<FlorisPreferenceModel>.steps(
    context: Context,
    navController: NavController,
    requestNotification: ManagedActivityResultLauncher<String, Boolean>,
    requestMic: ManagedActivityResultLauncher<String, Boolean>,
    isProviderConfigured: Boolean,
    onSkipProvider: () -> Unit,
    onPassFloatingButton: () -> Unit,
    accounts: ProviderAccounts,
    scope: CoroutineScope,
): List<FlorisStep> {

    // Persists the entered key into the keyring and points the active transcription (and, where the
    // provider also supports chat, rewording) provider at it. Done in this scope so the step composable
    // stays free of preference plumbing.
    fun saveKey(providerId: String, key: String) {
        scope.launch {
            this@steps.prefs.dictate.providerAccounts.set(
                accounts.edit(providerId) { it.copy(apiKey = key.trim()) }
            )
            this@steps.prefs.dictate.transcriptionProviderId.set(providerId)
            if (ProviderRegistry.byId(providerId)?.capabilities?.chat == true) {
                this@steps.prefs.dictate.rewordingProviderId.set(providerId)
            }
        }
    }

    return listOfNotNull(
        FlorisStep(
            id = Steps.EnableIme.id,
            title = stringRes(R.string.setup__enable_ime__title),
            // The app greets with its waveform rather than a keyboard glyph — the same animation the
            // "What's new" tour opens with, so the two screens rhyme from the first second.
            art = { SetupWelcomeWave() },
        ) {
            StepText(stringRes(R.string.setup__enable_ime__description))
            StepButton(label = stringRes(R.string.setup__enable_ime__open_settings_btn)) {
                InputMethodUtils.showImeEnablerActivity(context)
            }
        },
        FlorisStep(
            id = Steps.SelectIme.id,
            title = stringRes(R.string.setup__select_ime__title),
            icon = Icons.Default.SwapHoriz,
        ) {
            StepText(stringRes(R.string.setup__select_ime__description))
            StepButton(label = stringRes(R.string.setup__select_ime__switch_keyboard_btn)) {
                InputMethodUtils.showImePicker(context)
            }
        },
        FlorisStep(
            id = Steps.GrantMicPermission.id,
            title = stringRes(R.string.setup__grant_mic_permission__title),
            icon = Icons.Default.Mic,
        ) {
            StepText(stringRes(R.string.setup__grant_mic_permission__description))
            StepButton(stringRes(R.string.setup__grant_mic_permission__btn)) {
                requestMic.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        if (AndroidVersion.ATLEAST_API33_T) {
            FlorisStep(
                id = Steps.SelectNotification.id,
                title = stringRes(R.string.setup__grant_notification_permission__title),
                icon = Icons.Default.NotificationsActive,
            ) {
                StepText(stringRes(R.string.setup__grant_notification_permission__description))
                StepButton(stringRes(R.string.setup__grant_notification_permission__btn)) {
                    requestNotification.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else null,
        FlorisStep(
            id = Steps.SetUpProvider.id,
            title = stringRes(R.string.setup__provider__title),
        ) {
            ProviderSetupStep(
                onSaveKey = ::saveKey,
                onSkip = onSkipProvider,
                onOpenCloud = {
                    DictateCloud.openedFromSetup = true
                    navController.navigate(Routes.Settings.DictateCloud)
                },
            )
        },
        FlorisStep(
            id = Steps.FloatingButton.id,
            title = stringRes(R.string.setup__floating_button__title),
            icon = Icons.Default.Adjust,
        ) {
            StepText(stringRes(R.string.setup__floating_button__intro))
            Spacer(modifier = Modifier.height(8.dp))
            StepText(stringRes(R.string.setup__floating_button__accessibility_note))
            Spacer(modifier = Modifier.height(8.dp))
            StepText(
                text = stringRes(R.string.setup__floating_button__optional_note),
                fontStyle = FontStyle.Italic,
            )
            StepButton(label = stringRes(R.string.setup__floating_button__btn)) {
                // Finishing setup flips isImeSetUp, which resets the nav back stack to Home; the flag
                // makes FlorisAppActivity continue on to the floating-button settings afterwards.
                scope.launch {
                    this@steps.prefs.internal.openFloatingButtonAfterSetup.set(true)
                    this@steps.prefs.internal.isImeSetUp.set(true)
                }
                navController.navigate(Routes.Settings.Home) {
                    popUpTo(Routes.Setup.Screen) { inclusive = true }
                }
            }
            TextButton(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 4.dp),
                onClick = onPassFloatingButton,
            ) {
                Text(
                    text = stringRes(R.string.setup__floating_button__skip_btn),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        FlorisStep(
            id = Steps.FinishUp.id,
            title = stringRes(R.string.setup__finish_up__title),
            icon = Icons.Default.Celebration,
        ) {
            StepText(stringRes(R.string.setup__finish_up__description_p1))
            StepText(stringRes(R.string.setup__finish_up__description_p2))
            if (!isProviderConfigured) {
                Spacer(modifier = Modifier.height(8.dp))
                StepText(
                    text = stringRes(R.string.setup__finish_up__add_key_hint),
                    fontStyle = FontStyle.Italic,
                )
            }
            StepButton(label = stringRes(R.string.setup__finish_up__finish_btn)) {
                scope.launch { this@steps.prefs.internal.isImeSetUp.set(true) }
                navController.navigate(Routes.Settings.Home) {
                    popUpTo(Routes.Setup.Screen) {
                        inclusive = true
                    }
                }
            }
        }
    )
}

/**
 * The Dictate onboarding step that gets a non-technical user from "what is an API key" to a working
 * provider. It defaults to the recommended free provider (Groq) with a plain-language explanation and a
 * step-by-step mini guide, lets the user open the provider's sign-up page and paste the resulting key,
 * and offers an advanced picker for anyone who prefers a different provider. The key is saved into the
 * keyring on confirm, which (via the parent's auto-advance) moves the flow on to the final step.
 */
@Composable
private fun FlorisStepLayoutScope.ProviderSetupStep(
    onSaveKey: (providerId: String, key: String) -> Unit,
    onSkip: () -> Unit,
    onOpenCloud: () -> Unit,
) {
    val context = LocalContext.current

    var selectedProviderId by rememberSaveable { mutableStateOf(RECOMMENDED_PROVIDER_ID) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var showManualEntry by rememberSaveable { mutableStateOf(false) }
    var pasteHint by remember { mutableStateOf<String?>(null) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    // Which of the two ways the user has picked. Starts undecided on purpose: presenting the
    // API-key flow first and mentioning credit afterwards would be a recommendation dressed up
    // as an order, and both ways are meant to be equal here.
    var choseOwnKey by rememberSaveable { mutableStateOf(false) }

    // Coming back from the credit screen via "use my own provider" lands here, and must land on the
    // key flow rather than on the fork the user has already answered.
    val ownKeyRequested by DictateCloud.ownKeyRequested.collectAsState()
    LaunchedEffect(ownKeyRequested) {
        if (ownKeyRequested) {
            choseOwnKey = true
            DictateCloud.ownKeyRequested.value = false
        }
    }

    // Both sides of the fork are long enough to scroll, and to the layout this is one step
    // throughout — so without this, answering the fork from halfway down the page lands the key
    // flow halfway down as well, past its own heading.
    ScrollToTopOn(choseOwnKey)

    val selectedPreset = ProviderRegistry.byId(selectedProviderId) ?: ProviderRegistry.GROQ
    val isRecommended = selectedProviderId == RECOMMENDED_PROVIDER_ID

    // No plate on this step, deliberately. Its content is two cards that each already carry a mark;
    // a third illustration above them competes rather than orients.
    if (!choseOwnKey) {
        ProviderChoice(
            onChooseCloud = onOpenCloud,
            onChooseOwnKey = { choseOwnKey = true },
            onSkip = onSkip,
        )
        return
    }

    TextButton(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        onClick = { choseOwnKey = false },
    ) {
        Text(stringRes(R.string.setup__provider__back_to_choice))
    }

    // The whole key flow in one card, so this branch looks like the fork it came from rather than a
    // stack of loose buttons. The provider's own mark sits at the top: it is what the user is about
    // to open in a browser, and recognising the logo there is half of not getting lost.
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = providerIcon(selectedProviderId),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = selectedPreset.displayName,
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (isRecommended) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringRes(R.string.setup__provider__recommended),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringRes(R.string.setup__provider__what_is_key),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (isRecommended) {
                    stringRes(R.string.setup__provider__steps_groq)
                } else {
                    stringRes(R.string.setup__provider__steps_generic, "provider" to selectedPreset.displayName)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { selectedPreset.apiKeyUrl?.let { context.launchUrl(it) } },
            ) {
                Text(stringRes(R.string.setup__provider__open_btn, "provider" to selectedPreset.displayName))
            }

            // Paste-first: the user has just copied the key on the provider page, so the common path
            // needs no on-screen keyboard — which would otherwise cover this cramped step. Typing
            // stays available underneath.
            val clipboardEmptyMsg = stringRes(R.string.setup__provider__clipboard_empty)
            Spacer(modifier = Modifier.height(8.dp))
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val pasted = readClipboardText(context)?.trim()
                    if (pasted.isNullOrBlank()) {
                        pasteHint = clipboardEmptyMsg
                    } else {
                        apiKey = pasted
                        pasteHint = null
                    }
                },
            ) {
                Text(stringRes(R.string.setup__provider__paste_btn))
            }

            if (apiKey.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringRes(R.string.setup__provider__key_detected, "key" to maskKey(apiKey)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
            pasteHint?.let { hint ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            TextButton(onClick = { showManualEntry = !showManualEntry }) {
                Text(stringRes(R.string.setup__provider__enter_manually))
            }
            if (showManualEntry) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    singleLine = true,
                    label = { Text(stringRes(R.string.setup__provider__key_field)) },
                )
            }

            if (apiKey.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                // A plain Button rather than the layout's StepButton: that one is an extension on
                // FlorisStepLayoutScope, and inside this Card the receiver is out of reach.
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSaveKey(selectedProviderId, apiKey) },
                ) {
                    Text(stringRes(R.string.setup__provider__save_btn))
                }
            }
        }
    }

    // Changing provider is its own card rather than a disclosure triangle: it is a decision, not a
    // detail, and the marks make the choice legible before the menu is even opened.
    Spacer(modifier = Modifier.height(12.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringRes(R.string.setup__provider__other_provider),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringRes(R.string.setup__provider__other_provider_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box {
                FilledTonalButton(onClick = { providerMenuExpanded = true }) {
                    Icon(
                        imageVector = providerIcon(selectedProviderId),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.padding(start = 8.dp))
                    Text("${selectedPreset.displayName}  ▾")
                }
                DropdownMenu(
                    expanded = providerMenuExpanded,
                    onDismissRequest = { providerMenuExpanded = false },
                ) {
                    ProviderRegistry.presets
                        .filter { it.capabilities.transcription }
                        // Dictate Cloud is the *other* branch of this step, not an entry in the list
                        // of providers to bring a key for — it has no key page and nothing to paste.
                        .filter { it.id != ProviderRegistry.CLOUD.id }
                        .forEach { preset ->
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = providerIcon(preset.id),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                text = { Text(preset.displayName) },
                                onClick = {
                                    selectedProviderId = preset.id
                                    providerMenuExpanded = false
                                },
                            )
                        }
                }
            }
        }
    }

    TextButton(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(top = 6.dp),
        onClick = onSkip,
    ) {
        Text(
            text = stringRes(R.string.setup__provider__skip_btn),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The welcome: Dictate's waveform, the same one the "What's new" tour opens with.
 *
 * Chosen over the cloud orb deliberately. The orb is one of several floating-button skins and only
 * some users ever see it, so as a first impression it promises a look the app may not have. The
 * waveform is Dictate's general picture of itself — it says "this listens" without committing to a
 * skin, and it follows the user's own accent colour.
 *
 * This one loops, unlike the plates on the other steps: it is the page's subject rather than its
 * decoration, and a frozen equaliser would look broken.
 */
@Composable
private fun SetupWelcomeWave() {
    DictateWaveform(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(96.dp),
    )
}

/**
 * The fork in the road, shown before anything else in the provider step.
 *
 * Both ways get the same space, the same shape and the same tone — including the sentence that
 * credit costs more than going to a provider directly. Someone who finds that out after paying
 * has been steered, and the whole point of Dictate Cloud is that it is a convenience, not a
 * funnel. "Set up later" stays available underneath, as it always was.
 */
@Composable
private fun FlorisStepLayoutScope.ProviderChoice(
    onChooseCloud: () -> Unit,
    onChooseOwnKey: () -> Unit,
    onSkip: () -> Unit,
) {
    StepText(stringRes(R.string.setup__provider__choose_intro))
    Spacer(modifier = Modifier.height(16.dp))

    ChoiceCard(
        title = stringRes(R.string.setup__provider__choice_cloud_title),
        body = stringRes(R.string.setup__provider__choice_cloud_body),
        buttonLabel = stringRes(R.string.setup__provider__choice_cloud_btn),
        onClick = onChooseCloud,
        // One mark, because this is one service.
        marks = listOf(ProviderRegistry.CLOUD.id),
    )
    Spacer(modifier = Modifier.height(12.dp))
    ChoiceCard(
        title = stringRes(R.string.setup__provider__choice_own_title),
        body = stringRes(R.string.setup__provider__choice_own_body),
        buttonLabel = stringRes(R.string.setup__provider__choice_own_btn),
        onClick = onChooseOwnKey,
        // A row of provider marks, because "many to choose from" is the whole point of this option
        // and a sentence saying so is weaker than seeing the logos.
        marks = listOf("groq", "openai", "gemini", "anthropic", "mistral", "deepgram", "elevenlabs"),
    )

    TextButton(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(top = 8.dp),
        onClick = onSkip,
    ) {
        Text(
            text = stringRes(R.string.setup__provider__skip_btn),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One of the two ways in, with the marks of what it actually connects to.
 *
 * The marks are the app's existing monochrome provider glyphs rather than the brands' colours. In a
 * row whose message is "a set of options", uniform marks read as a set; seven brand colours read as
 * a jumble, and each would need its own contrast check against both themes.
 */
@Composable
private fun ChoiceCard(
    title: String,
    body: String,
    buttonLabel: String,
    onClick: () -> Unit,
    marks: List<String> = emptyList(),
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (marks.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 10.dp),
                ) {
                    // Same size, same tint on both cards. Making the single mark bigger and the
                    // accent colour was meant to give it weight and instead made it look like it
                    // belonged to a different set than its neighbour.
                    marks.forEach { id ->
                        Icon(
                            imageVector = providerIcon(id),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            FilledTonalButton(onClick = onClick) { Text(buttonLabel) }
        }
    }
}

private sealed class Steps(val id: Int) {
    data object EnableIme : Steps(id = 1)
    data object SelectIme : Steps(id = 2)
    data object GrantMicPermission : Steps(id = 3)
    data object SelectNotification : Steps(id = 4)
    data object SetUpProvider : Steps(id = 5)
    data object FloatingButton : Steps(id = 6)
    data object FinishUp : Steps(id = 7)
}
