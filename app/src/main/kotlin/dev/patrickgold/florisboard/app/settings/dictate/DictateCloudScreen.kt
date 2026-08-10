/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.dictate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.dictate.cloud.DictateCloud
import dev.patrickgold.florisboard.dictate.cloud.DictateCloudApi
import dev.patrickgold.florisboard.dictate.cloud.DictateCloudDeletion
import dev.patrickgold.florisboard.dictate.cloud.DictateCloudException
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes
import java.text.DateFormat
import java.util.Date

/**
 * Dictate Cloud: balance, the credit packs, and getting an account back onto a new device.
 *
 * The screen is written to be readable by someone who has not decided yet. Dictate Cloud is one of
 * two equal ways to use the app, not the intended one — so the alternative is named here rather
 * than tucked away, and what the cloud path *cannot* do is stated on the same screen that sells it.
 * A user who finds that out after paying has been misled, however true the small print was.
 */
@Composable
fun DictateCloudScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__cloud_title)
    previewFieldVisible = false
    iconSpaceReserved = false

    val prefs by FlorisPreferenceStore

    content {
        val context = LocalContext.current
        val activity = LocalActivity.current
        val navController = LocalNavController.current
        val scope = rememberCoroutineScope()

        // Where "use my own provider" leads depends on how this screen was reached: back into the
        // setup step it was opened from, or into the provider settings. Sending someone mid-setup to
        // a settings screen strands them — they then have to find their own way back and skip the
        // step they were in the middle of.
        val backToOwnProvider: () -> Unit = if (DictateCloud.openedFromSetup) {
            {
                DictateCloud.ownKeyRequested.value = true
                navController.popBackStack()
            }
        } else {
            { navController.navigate(Routes.Settings.DictateProviders) }
        }

        val accounts by prefs.dictate.providerAccounts.collectAsState()
        val activeProviderId by prefs.dictate.transcriptionProviderId.collectAsState()
        val account = accounts.getOrEmpty(ProviderRegistry.CLOUD.id)
        val isSelected = activeProviderId == ProviderRegistry.CLOUD.id

        var shop by remember { mutableStateOf<DictateCloud.Shop?>(null) }
        var buying by remember { mutableStateOf<String?>(null) }
        var refreshing by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf<String?>(null) }
        var showRestore by remember { mutableStateOf(false) }
        var showDelete by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf(false) }
        var deletePreview by remember { mutableStateOf<DictateCloudDeletion?>(null) }

        val noticePurchased = stringRes(R.string.dictate__cloud_notice_purchased)
        val noticePending = stringRes(R.string.dictate__cloud_notice_pending)
        val noticeNotRedeemed = stringRes(R.string.dictate__cloud_notice_not_redeemed)
        val noticeNeedsRecovery = stringRes(R.string.dictate__cloud_notice_needs_recovery)
        val noticeFailed = stringRes(R.string.dictate__cloud_notice_failed)
        val noticeDeleted = stringRes(R.string.dictate__cloud_notice_deleted)

        // Settling outstanding purchases first is the important part of entering this screen: if a
        // payment went through but the credit never arrived, this is where it is put right, before
        // anything is drawn that would claim the balance is zero.
        LaunchedEffect(Unit) {
            DictateCloud.redeemPending(context)
            DictateCloud.refreshBalance()
            shop = DictateCloud.shop(context)
        }

        fun buy(pack: dev.patrickgold.florisboard.dictate.cloud.DictateCloudPack) {
            val host = activity ?: return
            buying = pack.productId
            scope.launch {
                notice = when (val result = DictateCloud.purchase(host, pack)) {
                    is DictateCloud.PurchaseResult.Granted ->
                        noticePurchased.replace("{minutes}", result.grantedMinutes.toString())
                    is DictateCloud.PurchaseResult.Pending -> noticePending
                    is DictateCloud.PurchaseResult.NeedsRecovery -> noticeNeedsRecovery
                    is DictateCloud.PurchaseResult.NotRedeemed -> noticeNotRedeemed
                    is DictateCloud.PurchaseResult.Cancelled -> null
                    is DictateCloud.PurchaseResult.Unavailable -> noticeFailed
                    is DictateCloud.PurchaseResult.Failed -> noticeFailed
                }
                buying = null
                DictateCloud.refreshBalance()
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {

            if (account.hasWallet) {
                BalanceCard(
                    minutesLeft = account.balanceSeconds.coerceAtLeast(0) / 60,
                    rewordsLeft = account.balanceRewords.coerceAtLeast(0),
                    checkedAt = account.balanceCheckedAt,
                    refreshing = refreshing,
                    onRefresh = {
                        refreshing = true
                        scope.launch {
                            DictateCloud.redeemPending(context)
                            DictateCloud.refreshBalance()
                            refreshing = false
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))

                if (!isSelected) {
                    ActivateCard(onActivate = { scope.launch { DictateCloud.activate() } })
                    Spacer(Modifier.height(12.dp))
                }
            } else {
                IntroCard(onOwnProvider = backToOwnProvider)
                Spacer(Modifier.height(12.dp))
            }

            notice?.let { text ->
                NoticeCard(text = text, onDismiss = { notice = null })
                Spacer(Modifier.height(12.dp))
            }

            SectionTitle(
                stringRes(
                    if (account.hasWallet) R.string.dictate__cloud_topup_title
                    else R.string.dictate__cloud_packs_title
                ),
            )
            Spacer(Modifier.height(4.dp))

            when (val state = shop) {
                null -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }

                is DictateCloud.Shop.Unavailable -> BodyText(
                    stringRes(R.string.dictate__cloud_shop_unavailable),
                )

                is DictateCloud.Shop.Ready -> state.offers.forEach { offer ->
                    PackCard(
                        title = offer.title,
                        price = offer.formattedPrice,
                        minutes = offer.pack.minutes,
                        rewords = offer.pack.rewords,
                        busy = buying == offer.pack.productId,
                        anyBusy = buying != null,
                        onBuy = { buy(offer.pack) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle(stringRes(R.string.dictate__cloud_recovery_title))
            Spacer(Modifier.height(4.dp))
            BodyText(stringRes(R.string.dictate__cloud_recovery_explain))

            if (account.walletRecoveryCode.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                RecoveryCodeCard(code = account.walletRecoveryCode, context = context)
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showRestore = true }) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringRes(R.string.dictate__cloud_recovery_restore_btn))
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringRes(R.string.dictate__cloud_limits_title))
            Spacer(Modifier.height(4.dp))
            BodyText(stringRes(R.string.dictate__cloud_limits_body))

            Spacer(Modifier.height(16.dp))
            SectionTitle(stringRes(R.string.dictate__cloud_privacy_title))
            Spacer(Modifier.height(4.dp))
            BodyText(stringRes(R.string.dictate__cloud_privacy_body))

            // Deleting sits below everything else on purpose: it is the one action here that cannot
            // be undone, and it should not share a resting place with buying credit.
            if (account.hasWallet) {
                Spacer(Modifier.height(20.dp))
                SectionTitle(stringRes(R.string.dictate__cloud_delete_title))
                Spacer(Modifier.height(4.dp))
                BodyText(stringRes(R.string.dictate__cloud_delete_explain))
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        deleting = true
                        scope.launch {
                            deletePreview = runCatching { DictateCloud.previewDeletion() }.getOrNull()
                            deleting = false
                            // No preview means the server could not be reached. Opening a dialog
                            // that says "you will lose ? minutes" would be worse than saying so.
                            if (deletePreview != null) showDelete = true else notice = noticeFailed
                        }
                    },
                    enabled = !deleting,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringRes(R.string.dictate__cloud_delete_btn))
                }
            }

            Spacer(Modifier.height(16.dp))
            OtherWayCard(onOwnProvider = backToOwnProvider)
            Spacer(Modifier.height(24.dp))
        }

        if (showRestore) {
            RestoreDialog(
                onDismiss = { showRestore = false },
                onRestore = { code, onResult ->
                    scope.launch {
                        val result = runCatching { DictateCloud.restore(code) }
                        onResult(
                            result.exceptionOrNull()?.let { error ->
                                (error as? DictateCloudException)?.code
                                    ?.takeIf { it == DictateCloudApi.ErrorCode.WALLET_NOT_FOUND }
                                    ?.let { RestoreOutcome.NotFound }
                                    ?: RestoreOutcome.Error
                            } ?: RestoreOutcome.Success,
                        )
                    }
                },
            )
        }

        deletePreview?.let { preview ->
            if (showDelete) {
                DeleteAccountDialog(
                    preview = preview,
                    busy = deleting,
                    onDismiss = { if (!deleting) { showDelete = false; deletePreview = null } },
                    onConfirm = {
                        deleting = true
                        scope.launch {
                            val result = runCatching { DictateCloud.deleteAccount() }
                            deleting = false
                            showDelete = false
                            deletePreview = null
                            notice = if (result.isSuccess) noticeDeleted else noticeFailed
                        }
                    },
                )
            }
        }
    }
}

/**
 * The confirmation before an account is destroyed.
 *
 * It names the **exact** number of minutes about to be forfeited, fetched from the server a moment
 * earlier rather than read from the cached balance. A warning that says "your credit will be lost"
 * is easy to click past; one that says "your 340 minutes will be lost" is not, and that difference
 * is the entire point of asking.
 *
 * Two things the wording has to carry, because both surprise people afterwards: the credit is not
 * refunded, and the purchase records stay for tax reasons. Saying the second one here is cheaper
 * than explaining it to someone who later reads the privacy policy and feels misled.
 */
@Composable
private fun DeleteAccountDialog(
    preview: DictateCloudDeletion,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringRes(R.string.dictate__cloud_delete_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringRes(
                        R.string.dictate__cloud_delete_dialog_body,
                        "minutes" to preview.minutesLeft.toString(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringRes(R.string.dictate__cloud_delete_dialog_forfeit),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
                if (preview.purchases > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringRes(R.string.dictate__cloud_delete_dialog_records),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(
                    text = stringRes(R.string.dictate__cloud_delete_dialog_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringRes(R.string.dictate__cloud_delete_dialog_cancel))
            }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The balance, as the first thing on the screen when there is one. */
@Composable
private fun BalanceCard(
    minutesLeft: Int,
    rewordsLeft: Int,
    checkedAt: Long,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringRes(R.string.dictate__cloud_balance_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringRes(
                        R.string.dictate__cloud_balance_minutes,
                        "minutes" to minutesLeft.toString(),
                    ),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringRes(
                        R.string.dictate__cloud_balance_rewords,
                        "count" to rewordsLeft.toString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (checkedAt > 0L) {
                    Text(
                        text = stringRes(
                            R.string.dictate__cloud_balance_checked,
                            "time" to DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(checkedAt)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringRes(R.string.dictate__cloud_balance_refresh),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

/**
 * Shown before there is an account: what Dictate Cloud is, and — deliberately in the same card —
 * that an own API key remains a full alternative.
 */
@Composable
private fun IntroCard(onOwnProvider: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = providerIcon(ProviderRegistry.CLOUD.id),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringRes(R.string.dictate__cloud_intro_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            BodyText(stringRes(R.string.dictate__cloud_intro_body))
            Spacer(Modifier.height(8.dp))
            BodyText(stringRes(R.string.dictate__cloud_intro_alternative))
            TextButton(onClick = onOwnProvider) {
                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringRes(R.string.dictate__cloud_use_own_provider_btn))
            }
        }
    }
}

/** Credit exists but dictation still runs through another provider — offer to switch, do not switch. */
@Composable
private fun ActivateCard(onActivate: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringRes(R.string.dictate__cloud_activate_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringRes(R.string.dictate__cloud_activate_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onActivate) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringRes(R.string.dictate__cloud_activate_btn))
            }
        }
    }
}

@Composable
private fun NoticeCard(text: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            TextButton(onClick = onDismiss) {
                Text(stringRes(R.string.dictate__cloud_notice_dismiss))
            }
        }
    }
}

@Composable
private fun PackCard(
    title: String,
    price: String,
    minutes: Int,
    rewords: Int,
    busy: Boolean,
    anyBusy: Boolean,
    onBuy: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringRes(
                        R.string.dictate__cloud_pack_minutes,
                        "minutes" to minutes.toString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringRes(
                        R.string.dictate__cloud_balance_rewords,
                        "count" to rewords.toString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Button(onClick = onBuy, enabled = !anyBusy) {
                    Text(price)
                }
            }
        }
    }
}

@Composable
private fun RecoveryCodeCard(code: String, context: Context) {
    val copied = stringRes(R.string.dictate__cloud_recovery_copied)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = code,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            IconButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("Dictate Cloud", code))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                },
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringRes(R.string.dictate__cloud_recovery_copy),
                )
            }
        }
    }
}

/** The closing reminder that the other path is still open, phrased as information rather than a warning. */
@Composable
private fun OtherWayCard(onOwnProvider: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringRes(R.string.dictate__cloud_other_way_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            BodyText(stringRes(R.string.dictate__cloud_other_way_body))
            TextButton(onClick = onOwnProvider) {
                Text(stringRes(R.string.dictate__cloud_use_own_provider_btn))
            }
        }
    }
}

private enum class RestoreOutcome { Success, NotFound, Error }

@Composable
private fun RestoreDialog(
    onDismiss: () -> Unit,
    onRestore: (String, (RestoreOutcome) -> Unit) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val notFound = stringRes(R.string.dictate__cloud_recovery_not_found)
    val failed = stringRes(R.string.dictate__cloud_recovery_error)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringRes(R.string.dictate__cloud_recovery_restore_btn)) },
        text = {
            Column {
                BodyText(stringRes(R.string.dictate__cloud_recovery_dialog_body))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = code,
                    onValueChange = { code = it; error = null },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    label = { Text(stringRes(R.string.dictate__cloud_recovery_field)) },
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && code.isNotBlank(),
                onClick = {
                    busy = true
                    onRestore(code) { outcome ->
                        busy = false
                        when (outcome) {
                            RestoreOutcome.Success -> onDismiss()
                            RestoreOutcome.NotFound -> error = notFound
                            RestoreOutcome.Error -> error = failed
                        }
                    }
                },
            ) {
                Text(stringRes(R.string.dictate__cloud_recovery_confirm))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringRes(android.R.string.cancel))
            }
        },
    )
}
