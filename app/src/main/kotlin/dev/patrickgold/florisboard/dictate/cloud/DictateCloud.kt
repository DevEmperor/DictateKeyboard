/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.cloud

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.Purchase
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Dictate Cloud, from the app's side: buying a pack, turning purchases into credit, and keeping the
 * balance somewhere the settings can read it.
 *
 * The order of operations here is the whole point of the file, so it is worth stating plainly:
 *
 *  1. Play takes the money and hands back a purchase token.
 *  2. The server is asked to redeem it. It checks with Google, grants the minutes and acknowledges.
 *  3. Only then is the purchase **consumed**, which is what lets the same pack be bought again.
 *
 * Nothing is consumed before credit is granted. A purchase that Play still lists as owned is the
 * only durable record that money changed hands, so it stays until it has become minutes — every
 * failure in between costs a retry rather than the purchase. [redeemPending] is that retry, and
 * redemption is idempotent server-side precisely so it can be called as often as needed.
 */
object DictateCloud {

    private val prefs by FlorisPreferenceStore

    /**
     * True while the credit screen was opened from the setup flow rather than from settings.
     *
     * The screen offers a way back to "use my own provider", and where that leads has to differ:
     * during setup it belongs to the step the user is standing in, afterwards it is the provider
     * settings. A flag rather than a route argument because the route is also a deep link, and a
     * deep link carrying an onboarding flag would be a way to reach a half-state from outside.
     */
    var openedFromSetup: Boolean = false

    /**
     * Raised when that way back is taken, so the setup step can switch to its own-key branch as the
     * screen pops. Consumed by the reader.
     */
    val ownKeyRequested = MutableStateFlow(false)

    /** What settling a single purchase turned into. */
    sealed interface Settlement {
        /** Credit granted (or already granted earlier) and the purchase consumed. */
        data class Granted(val grantedMinutes: Int, val minutesLeft: Int) : Settlement

        /** Paid for but not finalised by Google yet. Left alone; it will be picked up later. */
        data object Pending : Settlement

        /**
         * The purchase belongs to an account this device holds no token for — it was redeemed on
         * another install. Only the recovery code gets the credit back, so the purchase is left
         * untouched rather than consumed.
         */
        data object NeedsRecovery : Settlement

        /** Not one of our packs. */
        data object Skipped : Settlement

        data class Failed(val error: DictateCloudException) : Settlement
    }

    sealed interface PurchaseResult {
        data class Granted(val grantedMinutes: Int, val minutesLeft: Int) : PurchaseResult
        data object Cancelled : PurchaseResult
        data object Pending : PurchaseResult
        data object NeedsRecovery : PurchaseResult

        /** Billing itself is not usable here — no Play install, sideloaded build, no store account. */
        data class Unavailable(val code: Int) : PurchaseResult

        /** Paid, but the credit could not be fetched. The purchase is kept and retried later. */
        data class NotRedeemed(val error: DictateCloudException) : PurchaseResult

        data class Failed(val code: Int, val message: String) : PurchaseResult
    }

    /** One pack as Play offers it here — localised name and price, ready to show. */
    data class Offer(
        val pack: DictateCloudPack,
        val title: String,
        val description: String,
        /** Already in the buyer's own currency and formatting, straight from Play. */
        val formattedPrice: String,
    )

    sealed interface Shop {
        data class Ready(val offers: List<Offer>) : Shop

        /**
         * Billing cannot be used here. Nearly always benign and worth saying plainly: the app was
         * installed as an APK rather than from Play, or there is no store account on the device.
         */
        data class Unavailable(val code: Int) : Shop
    }

    /** This device's credit account, or an empty record when there is none yet. */
    fun account(): ProviderAccount =
        prefs.dictate.providerAccounts.get().getOrEmpty(ProviderRegistry.CLOUD.id)

    /** True once credit can actually be spent from this device. */
    fun isActive(): Boolean = account().hasWallet

    /** True when Dictate Cloud is the provider dictation and rewording currently run through. */
    fun isSelected(): Boolean =
        prefs.dictate.transcriptionProviderId.get() == ProviderRegistry.CLOUD.id

    /**
     * Makes Dictate Cloud the active provider for both dictation and rewording.
     *
     * Never done as a side effect of a purchase. Someone may buy credit while happily using their
     * own key — for a spare, or for the phone where the key is not set up — and silently
     * redirecting their dictation would be a decision made on their behalf.
     */
    suspend fun activate() {
        prefs.dictate.transcriptionProviderId.set(ProviderRegistry.CLOUD.id)
        prefs.dictate.rewordingProviderId.set(ProviderRegistry.CLOUD.id)
    }

    /**
     * Switches over after a purchase, but only when nothing else was set up.
     *
     * The rule above — never activate as a side effect — protects someone who already has a working
     * key. It must not be applied to someone who has none: they chose the credit path during setup,
     * paid, and would otherwise be left holding a balance in front of a keyboard that refuses to use
     * it. Nobody buys minutes in order to keep dictating through an unconfigured provider.
     *
     * A keyless endpoint (Ollama, on-device) counts as set up and is left alone — same judgement the
     * onboarding makes when deciding whether the provider step is done.
     */
    private suspend fun activateIfNothingElseConfigured() {
        val currentId = prefs.dictate.transcriptionProviderId.get()
        if (currentId == ProviderRegistry.CLOUD.id) return
        if (prefs.dictate.providerAccounts.get().getOrEmpty(currentId).hasKey) return
        val preset = ProviderRegistry.byId(currentId)
        if (preset != null && preset.apiKeyUrl == null) return
        activate()
    }

    /** What Play has on offer, with local prices. */
    suspend fun shop(context: Context): Shop {
        val billing = DictateCloudBilling(context)
        try {
            val connection = billing.connect()
            if (connection != BillingResponseCode.OK) return Shop.Unavailable(connection)
            val products = billing.products()
            if (products.details.isEmpty()) return Shop.Unavailable(products.code)
            // Ordered by the pack list, not by what Play happened to return, so the shop reads
            // cheapest-first even if a product is momentarily missing from the answer.
            val offers = DictateCloudPack.ordered.mapNotNull { pack ->
                val details = products.find(pack) ?: return@mapNotNull null
                // A pack with no purchase option cannot be bought at all, so it is left out
                // rather than shown as a button that would fail.
                val offer = details.oneTimePurchaseOfferDetailsList?.firstOrNull()
                    ?: return@mapNotNull null
                Offer(
                    pack = pack,
                    // `name` is the bare product name; `title` appends the app name in brackets,
                    // which reads oddly inside the app it names.
                    title = details.name.ifBlank { details.title },
                    description = details.description,
                    formattedPrice = offer.formattedPrice,
                )
            }
            return if (offers.isEmpty()) Shop.Unavailable(products.code) else Shop.Ready(offers)
        } finally {
            billing.close()
        }
    }

    /**
     * Buys [pack]: opens Play's sheet, waits for the answer and settles whatever comes back.
     *
     * [activity] must be a real, visible activity — Play draws its sheet on top of it.
     */
    suspend fun purchase(activity: Activity, pack: DictateCloudPack): PurchaseResult {
        val billing = DictateCloudBilling(activity)
        try {
            val connection = billing.connect()
            if (connection != BillingResponseCode.OK) return PurchaseResult.Unavailable(connection)

            val products = billing.products()
            val details = products.find(pack)
                ?: return PurchaseResult.Unavailable(products.code)
            // Since Billing 8 a one-time product is bought through a purchase option, and its offer
            // token is not optional: without one Play rejects the flow before it opens.
            val offerToken = DictateCloudBilling.offerTokenOf(details)
                ?: return PurchaseResult.Unavailable(BillingResponseCode.ITEM_UNAVAILABLE)

            val launched = withContext(Dispatchers.Main) {
                billing.launch(activity, details, offerToken, account().walletId)
            }
            if (launched != BillingResponseCode.OK) {
                return PurchaseResult.Failed(launched, "launchBillingFlow returned $launched")
            }

            return when (val outcome = billing.awaitOutcome()) {
                is DictateCloudBilling.Outcome.Cancelled -> PurchaseResult.Cancelled
                is DictateCloudBilling.Outcome.Failed ->
                    PurchaseResult.Failed(outcome.code, outcome.message)
                is DictateCloudBilling.Outcome.Purchased -> {
                    val mine = outcome.purchases.filter { pack.productId in it.products }
                    // Play may report purchases that are not the one just made (a leftover from an
                    // earlier attempt, say). Settle them all, then answer about the one asked for.
                    val settled = mine.map { settle(billing, it) }
                    settled.filterIsInstance<Settlement.Granted>().lastOrNull()
                        ?.let { return PurchaseResult.Granted(it.grantedMinutes, it.minutesLeft) }
                    when (val first = settled.firstOrNull()) {
                        is Settlement.Pending -> PurchaseResult.Pending
                        is Settlement.NeedsRecovery -> PurchaseResult.NeedsRecovery
                        is Settlement.Failed -> PurchaseResult.NotRedeemed(first.error)
                        else -> PurchaseResult.Failed(
                            BillingResponseCode.ERROR,
                            "No purchase for ${pack.productId} in the result",
                        )
                    }
                }
            }
        } finally {
            billing.close()
        }
    }

    /**
     * Settles everything Play still lists as owned.
     *
     * Cheap and safe to call on any occasion the balance matters — app start, opening the settings,
     * a failed dictation. It does nothing when there is nothing outstanding, and when there is, it
     * is the difference between a paid purchase and lost money.
     *
     * Returns how many purchases became credit on this run.
     */
    suspend fun redeemPending(context: Context): Int {
        val billing = DictateCloudBilling(context)
        try {
            if (billing.connect() != BillingResponseCode.OK) return 0
            val owned = billing.purchases()
            if (owned.isEmpty()) return 0
            var granted = 0
            for (purchase in owned) {
                when (val settlement = settle(billing, purchase)) {
                    is Settlement.Granted -> granted++
                    is Settlement.Failed -> flogError {
                        "Dictate Cloud: purchase not redeemed (${settlement.error.code}): ${settlement.error.message}"
                    }
                    else -> Unit
                }
            }
            if (granted > 0) flogInfo { "Dictate Cloud: redeemed $granted outstanding purchase(s)" }
            return granted
        } finally {
            billing.close()
        }
    }

    /** Fetches the balance from the server and caches it; null when there is no account or no answer. */
    suspend fun refreshBalance(): DictateCloudBalance? {
        val current = account()
        if (!current.hasWallet) return null
        return try {
            val balance = DictateCloudApi.balance(current.apiKey)
            edit {
                it.copy(
                    balanceSeconds = balance.secondsLeft,
                    balanceRewords = balance.rewordsLeft,
                    balanceCheckedAt = System.currentTimeMillis(),
                )
            }
            balance
        } catch (e: DictateCloudException) {
            flogError { "Dictate Cloud: balance unavailable (${e.code}): ${e.message}" }
            null
        }
    }

    /**
     * Claims an existing account with its recovery code and stores it on this device.
     *
     * Throws [DictateCloudException] so the caller can tell "wrong code" apart from "no connection";
     * both look identical to a user staring at a spinner, and they need different advice.
     */
    suspend fun restore(code: String): DictateCloudRestore {
        val restored = DictateCloudApi.restore(code, label = DictateCloudApi.deviceLabel())
        edit {
            it.copy(
                walletId = restored.walletId,
                apiKey = restored.token,
                // Same treatment as a purchase — see below.
                // The code the user just typed is the one that works — worth keeping so the settings
                // can show it again rather than asking them to have kept the paper.
                walletRecoveryCode = code.trim(),
                balanceSeconds = restored.secondsLeft,
                balanceRewords = restored.rewordsLeft,
                balanceCheckedAt = System.currentTimeMillis(),
            )
        }
        // Recovering credit is the same event as buying it, from the app's point of view: someone
        // now has minutes and nothing else set up. Whoever types their code during setup expects to
        // carry on from there, not to go back a screen and skip the step they just completed.
        prefs.dictate.cloudLowCreditNudged.set(false)
        activateIfNothingElseConfigured()
        return restored
    }

    /**
     * Redeems one purchase and, if that worked, consumes it.
     *
     * The failure branches are the interesting part. A purchase is consumed **only** after credit is
     * granted — not when Google says it does not know it, and not when verification is merely
     * unreachable. Both of those may be temporary, and a purchase kept in Play's owned list can be
     * retried forever, whereas a consumed one is gone. The cost of being wrong in this direction is
     * that a bogus purchase sits there unredeemed, which costs nobody anything.
     */
    private suspend fun settle(billing: DictateCloudBilling, purchase: Purchase): Settlement {
        val productId = purchase.products.firstOrNull() ?: return Settlement.Skipped
        DictateCloudPack.byProductId(productId) ?: return Settlement.Skipped
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return Settlement.Pending

        val redeemed = try {
            DictateCloudApi.redeem(
                purchaseToken = purchase.purchaseToken,
                productId = productId,
                walletId = account().walletId.takeIf { it.isNotBlank() },
                label = DictateCloudApi.deviceLabel(),
            )
        } catch (e: DictateCloudException) {
            return Settlement.Failed(e)
        }

        if (!store(redeemed)) return Settlement.NeedsRecovery
        activateIfNothingElseConfigured()

        val consumed = billing.consume(purchase.purchaseToken)
        if (consumed != BillingResponseCode.OK) {
            // Harmless: the credit is granted and redemption is idempotent, so the next sweep
            // redeems again (getting "already redeemed") and consumes then.
            flogError { "Dictate Cloud: consume failed with $consumed, will retry" }
        }
        return Settlement.Granted(redeemed.grantedMinutes, redeemed.minutesLeft)
    }

    /**
     * Persists what a redemption returned. False when the reply carried no usable token and this
     * device has none either — that is a purchase redeemed by some other install, and only the
     * recovery code can reach it.
     *
     * Token and recovery code are sent exactly once, when the account is created, and the server
     * keeps only a hash of the code. This write is therefore the single moment they can be captured;
     * it happens before the purchase is consumed for that reason.
     */
    private suspend fun store(redeemed: DictateCloudRedeem): Boolean {
        val token = redeemed.token?.takeIf { it.isNotBlank() } ?: account().apiKey
        if (token.isBlank()) return false
        // Credit just arrived, so the low-balance nudge is owed again next time it runs out.
        prefs.dictate.cloudLowCreditNudged.set(false)
        edit {
            it.copy(
                walletId = redeemed.walletId,
                apiKey = token,
                walletRecoveryCode = redeemed.recoveryCode?.takeIf { code -> code.isNotBlank() }
                    ?: it.walletRecoveryCode,
                balanceSeconds = redeemed.secondsLeft,
                balanceRewords = redeemed.rewordsLeft,
                balanceCheckedAt = System.currentTimeMillis(),
            )
        }
        return true
    }

    /**
     * Asks the server what deleting this account would cost, without deleting anything.
     *
     * Deliberately not answered from the locally cached balance: that copy can be minutes old, and
     * the one number the confirmation dialog must get right is how much is about to be destroyed.
     */
    suspend fun previewDeletion(): DictateCloudDeletion? {
        val current = account()
        if (!current.hasWallet) return null
        return DictateCloudApi.previewDeletion(current.apiKey)
    }

    /**
     * Deletes the credit account on the server and wipes every trace of it from this device.
     *
     * The order matters. The server goes first: if it refuses — no connection, token already gone —
     * the exception propagates and the local account stays, so the user is not left holding a
     * phone that has forgotten an account which still exists. Only once the server confirms is the
     * local copy cleared, and then completely: token, wallet id, recovery code and cached balance.
     * Leaving the recovery code behind would be the worst of both, since it looks like a way back
     * to something that no longer exists.
     *
     * The credit is forfeited. That is stated in the dialog before this ever runs.
     */
    suspend fun deleteAccount(): DictateCloudDeletion {
        val current = account()
        val result = DictateCloudApi.deleteAccount(current.apiKey)

        edit {
            it.copy(
                walletId = "",
                apiKey = "",
                walletRecoveryCode = "",
                balanceSeconds = 0,
                balanceRewords = 0,
                balanceCheckedAt = 0L,
            )
        }
        // The low-credit nudge is keyed to an account that no longer exists; leaving it armed would
        // mean the next account inherits a warning it never earned.
        prefs.dictate.cloudLowCreditNudged.set(false)
        return result
    }

    private suspend fun edit(block: (ProviderAccount) -> ProviderAccount) {
        val accounts = prefs.dictate.providerAccounts.get()
        prefs.dictate.providerAccounts.set(accounts.edit(ProviderRegistry.CLOUD.id, block))
    }
}
