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

import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.dictateProxyConfig
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The wallet half of Dictate Cloud: redeeming a purchase, reading the balance, moving an account to
 * a new device.
 *
 * Only these three endpoints live here. Dictation and rewording deliberately do not — the server
 * speaks the ordinary OpenAI formats for those, so they run through `OpenAiCompatibleClient` like
 * every other provider, with the wallet token in place of an API key.
 *
 * Nothing here decides anything about credit. The app cannot grant itself minutes, and a reply that
 * says otherwise changes nothing upstream: every request is metered where the money is.
 */
object DictateCloudApi {

    /** Codes the server sends in `error.code`, for the cases a caller must react to differently. */
    object ErrorCode {
        /** Payment started but not finished (cash at a counter, for instance). Ask again later. */
        const val PURCHASE_PENDING = "purchase_pending"
        /** Google has no record of this purchase. */
        const val PURCHASE_UNKNOWN = "purchase_unknown"
        /** The purchase was refunded — credit was clawed back. */
        const val PURCHASE_VOIDED = "purchase_voided"
        /** Google could not be asked right now. Our problem, not the purchase's: retry. */
        const val VERIFY_UNAVAILABLE = "verify_unavailable"
        /** No account for this recovery code (also returned for blocked ones, on purpose). */
        const val WALLET_NOT_FOUND = "wallet_not_found"
        /** The stored token is not (or no longer) valid. */
        const val INVALID_TOKEN = "invalid_token"
    }

    private val base = ProviderRegistry.CLOUD.baseUrl.trimEnd('/')

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val prefs by FlorisPreferenceStore

    /**
     * Turns a Play purchase into credit.
     *
     * Safe to call repeatedly with the same [purchaseToken]: the server keys the purchase table on
     * it, so a second attempt reports the same outcome instead of granting twice. That is not a
     * nicety but the design — the app calls this again after any crash or lost connection between
     * paying and being credited.
     *
     * [walletId] tops up an existing account; leaving it null has the server create one and return
     * its token and recovery code, once and only once.
     */
    suspend fun redeem(
        purchaseToken: String,
        productId: String,
        walletId: String? = null,
    ): DictateCloudRedeem = post(
        path = "/wallet/redeem",
        body = json.encodeToString(
            RedeemRequest(
                purchaseToken = purchaseToken,
                productId = productId,
                walletId = walletId?.takeIf { it.isNotBlank() },
            ),
        ),
    )

    /** Current balance for [token]. */
    suspend fun balance(token: String): DictateCloudBalance {
        val request = Request.Builder()
            .url("$base/wallet")
            .header("authorization", "Bearer $token")
            .get()
            .build()
        return call(request)
    }

    /**
     * Claims an existing account on this device using its recovery code.
     *
     * Issues an additional token rather than replacing the old one, so recovering onto a new phone
     * does not silently lock out the old one — a shared account across two devices is a feature, and
     * cutting one off without being asked to would be a surprise.
     */
    suspend fun restore(code: String, label: String? = null): DictateCloudRestore = post(
        path = "/wallet/restore",
        body = json.encodeToString(RestoreRequest(code = code, label = label)),
    )

    private suspend inline fun <reified T> post(path: String, body: String): T {
        val request = Request.Builder()
            .url("$base$path")
            .post(body.toRequestBody(jsonMedia))
            .build()
        return call(request)
    }

    private suspend inline fun <reified T> call(request: Request): T {
        val response = try {
            client().newCall(request).await()
        } catch (e: IOException) {
            throw DictateCloudException(0, "unreachable", e.message ?: "Network error")
        }
        val body = response.use { it.body.string() }
        if (!response.isSuccessful) throw errorFrom(response.code, body)
        return try {
            json.decodeFromString<T>(body)
        } catch (e: Exception) {
            throw DictateCloudException(response.code, "bad_response", e.message ?: "Unreadable reply")
        }
    }

    /**
     * The server answers errors in OpenAI's envelope, which is exactly why it does: the same shape
     * carries the dictation and rewording failures, so one parser covers all of it.
     */
    private fun errorFrom(status: Int, body: String): DictateCloudException {
        val parsed = runCatching { json.decodeFromString<ErrorEnvelope>(body).error }.getOrNull()
        return DictateCloudException(
            status = status,
            code = parsed?.code.orEmpty(),
            message = parsed?.message?.takeIf { it.isNotBlank() } ?: "HTTP $status",
        )
    }

    /**
     * Built per call rather than kept around: these three endpoints are used a handful of times in
     * an install's life, and rebuilding costs nothing next to holding a client that would miss a
     * proxy setting changed in the meantime.
     *
     * User-installed CA certificates are not honoured here, unlike for provider calls (#137). This
     * endpoint is ours and presents a public certificate; there is no self-hosted variant of it to
     * accommodate, and trusting a user CA for the wallet would only widen what can intercept it.
     */
    private fun client(): OkHttpClient {
        val timeout = Duration.ofSeconds(30)
        val builder = OkHttpClient.Builder()
            .callTimeout(timeout)
            .connectTimeout(Duration.ofSeconds(10))
            .fastFallback(true)
            .readTimeout(timeout)
            .writeTimeout(timeout)
        prefs.dictate.dictateProxyConfig()?.let { proxy ->
            builder.proxy(proxy.toJavaProxy())
            if (proxy.type == Proxy.Type.HTTP && proxy.hasCredentials) {
                builder.proxyAuthenticator { _, response ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(proxy.username!!, proxy.password!!))
                        .build()
                }
            }
        }
        return builder.build()
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    @Serializable
    private data class RedeemRequest(
        val purchaseToken: String,
        val productId: String,
        val walletId: String? = null,
    )

    @Serializable
    private data class RestoreRequest(
        val code: String,
        val label: String? = null,
    )

    @Serializable
    private data class ErrorEnvelope(val error: ErrorBody? = null)

    @Serializable
    private data class ErrorBody(
        val message: String = "",
        val code: String = "",
        val type: String = "",
    )
}

/**
 * A failed wallet call. [status] is 0 when the server was never reached, [code] the server's own
 * error code (see [DictateCloudApi.ErrorCode]) or empty when it did not send one.
 */
class DictateCloudException(
    val status: Int,
    val code: String,
    message: String,
) : IOException(message)

/** Reply to a redemption. [token] and [recoveryCode] are present only when the account was created. */
@Serializable
data class DictateCloudRedeem(
    @SerialName("wallet_id") val walletId: String,
    val token: String? = null,
    @SerialName("recovery_code") val recoveryCode: String? = null,
    @SerialName("granted_minutes") val grantedMinutes: Int = 0,
    @SerialName("seconds_left") val secondsLeft: Int = 0,
    @SerialName("minutes_left") val minutesLeft: Int = 0,
    @SerialName("rewords_left") val rewordsLeft: Int = 0,
    @SerialName("already_redeemed") val alreadyRedeemed: Boolean = false,
)

@Serializable
data class DictateCloudBalance(
    @SerialName("wallet_id") val walletId: String,
    @SerialName("seconds_left") val secondsLeft: Int = 0,
    @SerialName("minutes_left") val minutesLeft: Int = 0,
    @SerialName("rewords_left") val rewordsLeft: Int = 0,
    @SerialName("seconds_bought") val secondsBought: Int = 0,
    @SerialName("seconds_used") val secondsUsed: Int = 0,
    val status: String = "active",
)

@Serializable
data class DictateCloudRestore(
    @SerialName("wallet_id") val walletId: String,
    val token: String,
    @SerialName("seconds_left") val secondsLeft: Int = 0,
    @SerialName("minutes_left") val minutesLeft: Int = 0,
    @SerialName("rewords_left") val rewordsLeft: Int = 0,
)
