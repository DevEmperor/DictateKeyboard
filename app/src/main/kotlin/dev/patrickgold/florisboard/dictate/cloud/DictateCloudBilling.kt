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
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * A coroutine-shaped wrapper around Google Play Billing, kept deliberately thin: it connects, lists
 * the packs, starts a purchase, reports what came back and consumes. It knows nothing about credit —
 * what a purchase is worth is decided by the server, and the two never meet in this file.
 *
 * One instance covers one errand and is then closed. Billing connections are cheap next to how
 * rarely this runs (a purchase, or one sweep at startup), and a short-lived client removes a whole
 * class of questions about stale state and reconnection.
 *
 * Requires Billing 8 or newer, which Play makes mandatory for uploads from 31.08.2026. Two things
 * changed there that shape the code below: purchase results arrive through [QueryProductDetailsResult]
 * rather than a bare list, and a one-time product can no longer be bought without an **offer token**
 * taken from its purchase option — passing none is rejected outright.
 */
class DictateCloudBilling(context: Context) {

    /** What a purchase attempt turned into. */
    sealed interface Outcome {
        data class Purchased(val purchases: List<Purchase>) : Outcome
        data object Cancelled : Outcome
        data class Failed(val code: Int, val message: String) : Outcome
    }

    /**
     * Buffered rather than a [kotlinx.coroutines.flow.SharedFlow]: Play may report the result before
     * the caller gets around to waiting for it, and a channel holds it instead of dropping it for
     * want of a subscriber. Losing that message would mean a paid-for purchase nobody redeems.
     */
    private val outcomes = Channel<Outcome>(Channel.BUFFERED)

    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            outcomes.trySend(
                when (result.responseCode) {
                    BillingResponseCode.OK -> Outcome.Purchased(purchases.orEmpty())
                    BillingResponseCode.USER_CANCELED -> Outcome.Cancelled
                    else -> Outcome.Failed(result.responseCode, result.debugMessage)
                },
            )
        }
        .enableAutoServiceReconnection()
        // Required since Billing 6, and true for us: a one-time product can be paid for in ways that
        // do not complete immediately (cash at a counter, for one). Such a purchase arrives PENDING
        // and must not be credited until it settles — see how the caller treats the state.
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    /** Connects, returning a [BillingResponseCode]. [BillingResponseCode.OK] means ready. */
    suspend fun connect(): Int = suspendCancellableCoroutine { cont ->
        if (client.isReady) {
            cont.resume(BillingResponseCode.OK)
            return@suspendCancellableCoroutine
        }
        var settled = false
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (!settled) {
                    settled = true
                    cont.resume(result.responseCode)
                }
            }

            override fun onBillingServiceDisconnected() {
                // Only interesting while still waiting for the first answer; afterwards the client
                // reconnects on its own (see enableAutoServiceReconnection above).
                if (!settled) {
                    settled = true
                    cont.resume(BillingResponseCode.SERVICE_DISCONNECTED)
                }
            }
        })
    }

    /** The four packs as Play knows them — localised name, description and price. */
    suspend fun products(): Products = suspendCancellableCoroutine { cont ->
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                DictateCloudPack.productIds.map { id ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                },
            )
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            cont.resume(Products(result.responseCode, details.productDetailsList.orEmpty()))
        }
    }

    data class Products(val code: Int, val details: List<ProductDetails>) {
        fun find(pack: DictateCloudPack): ProductDetails? =
            details.firstOrNull { it.productId == pack.productId }
    }

    /**
     * Purchases Play still considers owned — that is, paid for and not yet consumed.
     *
     * This is the safety net of the whole flow. Anything that was paid for but never turned into
     * credit stays in this list until it does, so a crash or a dead connection between paying and
     * being credited costs a retry rather than the purchase.
     */
    suspend fun purchases(): List<Purchase> = suspendCancellableCoroutine { cont ->
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            cont.resume(if (result.responseCode == BillingResponseCode.OK) purchases else emptyList())
        }
    }

    /**
     * Opens Play's purchase sheet. **Must be called on the main thread.** The result does not arrive
     * here but through [awaitOutcome]; this only says whether the sheet could be opened.
     *
     * [walletId] travels along as Play's `obfuscatedAccountId`. It is not needed for anything in the
     * normal flow — its purpose is that a purchase can still be traced back to an account by hand
     * when something has gone wrong, which is the one moment nobody has better information.
     */
    fun launch(
        activity: Activity,
        details: ProductDetails,
        offerToken: String,
        walletId: String,
    ): Int {
        val product = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .build()
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(product))
            .apply { if (walletId.isNotBlank()) setObfuscatedAccountId(walletId) }
            .build()
        return client.launchBillingFlow(activity, flow).responseCode
    }

    /** Suspends until Play reports how the purchase went. */
    suspend fun awaitOutcome(): Outcome = outcomes.receive()

    /**
     * Consumes a purchase so the pack can be bought again.
     *
     * Call this only once the credit is safely granted. Consuming removes the purchase from Play's
     * owned list, which is exactly the record [purchases] relies on to retry — throw it away too
     * early and a paid purchase is gone with nothing to show for it.
     */
    suspend fun consume(purchaseToken: String): Int = suspendCancellableCoroutine { cont ->
        val params = ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build()
        client.consumeAsync(params) { result, _ -> cont.resume(result.responseCode) }
    }

    fun close() {
        outcomes.close()
        client.endConnection()
    }

    /** The offer token of a product's first (and for us only) purchase option, or null. */
    companion object {
        fun offerTokenOf(details: ProductDetails): String? =
            details.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
    }
}
