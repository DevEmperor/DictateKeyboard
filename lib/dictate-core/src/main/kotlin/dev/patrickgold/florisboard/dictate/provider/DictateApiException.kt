/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.provider

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Normalised error for all provider calls. The [kind] lets the UI show the right message/action
 * (mirrors the error handling of the original Dictate input method service).
 */
class DictateApiException(
    val kind: Kind,
    message: String? = null,
    cause: Throwable? = null,
    val httpStatus: Int? = null,
    /**
     * The provider's machine-readable `error.code`, kept verbatim.
     *
     * [kind] deliberately flattens many causes into one bucket — "out of credit" and "rate limited"
     * are both [Kind.QUOTA_EXCEEDED], because for most providers the remedy is the same: wait or top
     * up somewhere else. Dictate Cloud is the exception, since the remedy is a button in this app, so
     * that one case has to be told apart from the rest by something more precise than a keyword.
     */
    val code: String? = null,
) : Exception(message, cause) {

    enum class Kind {
        INVALID_API_KEY,
        QUOTA_EXCEEDED,
        CONTENT_SIZE_LIMIT,

        /**
         * A *text* was too long to reword — as opposed to an audio file being too long, which is
         * what [CONTENT_SIZE_LIMIT] means everywhere it is shown.
         *
         * They arrive as the same HTTP 413 and used to be classified as the same thing, so a
         * rewording that did not fit told the user their "recording" was too long and offered to
         * save an audio file that had nothing to do with it. Dictate Cloud names the difference in
         * its error code; this is the app listening to it.
         */
        TEXT_SIZE_LIMIT,
        FORMAT_NOT_SUPPORTED,

        /**
         * The key is fine, but the model is not one it may use: an OpenAI project that allows only some
         * models answers `403 model_not_found`, "Project … does not have access to model …", and a model
         * that does not exist (any more) gets the same code with a 404 (both asked of the endpoint,
         * 2026-11-02). As a 403 it used to read "Check your API key", which sent the user to the one
         * setting that was right; the remedy is another model, or allowing this one in the project.
         */
        MODEL_UNAVAILABLE,
        TIMEOUT,
        NETWORK,
        SERVER_ERROR,
        UNKNOWN;

        /** Whether retrying the same request could plausibly succeed. */
        val isRetryable: Boolean
            get() = this == TIMEOUT || this == NETWORK || this == SERVER_ERROR || this == UNKNOWN
    }

    /**
     * Whether sending this very request again could plausibly succeed — [Kind.isRetryable], minus the
     * answers that were a refusal.
     *
     * A 4xx means the provider read the request and said no: a model it has shut down, one it does not
     * serve on this endpoint, a parameter it does not take. The same bytes get the same no. Those land in
     * [Kind.UNKNOWN] whenever their wording matches none of the keywords in [fromHttp], and UNKNOWN is
     * retryable — so each one was sent four times with three-second pauses between, ten seconds of silence
     * per rewording step before the error the first answer already held (#416). 408 and 425 are the two
     * 4xx that ask to be sent again, and stay retryable.
     */
    val isRetryable: Boolean
        get() = kind.isRetryable && (httpStatus == null || httpStatus !in 400..499 || httpStatus in RETRYABLE_4XX)

    /**
     * Whether no connection to the provider could be made at all: the name did not resolve, nothing
     * listened on the port, there was no route, or the connection attempt timed out (#455).
     *
     * Narrower than [Kind.NETWORK] and [Kind.TIMEOUT] on purpose. Those also cover a connection that broke
     * halfway through the upload and a server that took too long to answer, and in both of those the
     * provider has already got the audio. Handing it to another provider then would be the "switch after
     * N seconds" that #391 declined: the same dictation paid for twice, and a slow answer thrown away. A
     * server that was never reached has seen nothing, so passing the recording on costs nothing.
     *
     * A connection timeout and a read timeout are the same exception type; only the message tells them
     * apart. Android words a connect timeout "failed to connect to … after N ms", the JVM "Connect timed
     * out", while a read timeout says "timeout" or "Read timed out".
     */
    val isConnectFailure: Boolean
        get() = httpStatus == null && generateSequence(cause) { it.cause }.take(MAX_CAUSE_DEPTH).any(::isUnreachable)

    companion object {
        private const val MAX_CAUSE_DEPTH = 8

        private fun isUnreachable(t: Throwable): Boolean = when (t) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException, is PortUnreachableException -> true
            is SocketTimeoutException -> t.message.orEmpty().lowercase().let {
                it.startsWith("failed to connect") || it.contains("connect timed out")
            }
            else -> false
        }

        /** Request Timeout and Too Early: the only client errors whose remedy is to send the request again. */
        private val RETRYABLE_4XX = setOf(408, 425)

        /**
         * Classifies a non-2xx HTTP response into a [Kind]. Works across all OpenAI-compatible providers
         * by combining the HTTP status (the most reliable, standardized signal) with the machine-readable
         * `error.code` / `error.type` from the JSON envelope and, as a last resort, keyword matching on the
         * human message. [message] is kept verbatim as the exception message (the raw provider detail).
         */
        fun fromHttp(
            status: Int,
            message: String?,
            code: String? = null,
            type: String? = null,
        ): DictateApiException {
            // Search code + type + message together so any of the three can trigger a match.
            val hay = listOf(code, type, message).joinToString(" ") { it.orEmpty() }.lowercase()
            val kind = when {
                // Before the key check: a project without access to a model answers 403 like a bad key.
                hay.contains("model_not_found") || hay.contains("does not have access to model") ||
                    (hay.contains("model") && hay.contains("does not exist")) ||
                    // Gemini's 400 for a model this key cannot use as asked names the model, not the
                    // request: "Thinking is not enabled for this model" (gemini-3.5-transcribe, #447).
                    ((status == 400 || status == 404) && hay.contains("for this model")) ||
                    // And its 404 for a model id it does not know, a typo included: "Model 'gemini-3.5-
                    // transcribe-x' not found. Did you mean 'gemini-3.5-transcribe'?" (2026-11-02).
                    (status == 404 && hay.contains("model") && hay.contains("not found")) -> Kind.MODEL_UNAVAILABLE
                status == 401 || status == 403 ||
                    hay.contains("api key") || hay.contains("api_key") || hay.contains("invalid_api_key") ||
                    hay.contains("unauthorized") || hay.contains("authentication") -> Kind.INVALID_API_KEY
                status == 429 || status == 402 ||
                    hay.contains("quota") || hay.contains("insufficient_quota") || hay.contains("billing") ||
                    hay.contains("rate limit") || hay.contains("rate_limit") ||
                    // Soniox 402 billing signals: balance/budget exhausted.
                    hay.contains("exhausted") || hay.contains("balance") || hay.contains("budget") -> Kind.QUOTA_EXCEEDED
                // Before the generic 413, because it is also a 413 — only about text rather than
                // audio. `input_too_long` is the pre-check, `reword_truncated` the answer that ran
                // out of room; both leave the text untouched and neither has audio to offer.
                hay.contains("input_too_long") || hay.contains("reword_truncated") -> Kind.TEXT_SIZE_LIMIT
                status == 413 ||
                    hay.contains("audio duration") || hay.contains("content size limit") ||
                    hay.contains("too large") || hay.contains("maximum context length") ||
                    // Scaleway refuses an oversized upload with a 400, "Maximum file size exceeded" (#423).
                    hay.contains("file size") -> Kind.CONTENT_SIZE_LIMIT
                hay.contains("format") || hay.contains("unsupported") || hay.contains("decode") ||
                    hay.contains("could not process") -> Kind.FORMAT_NOT_SUPPORTED
                status in 500..599 -> Kind.SERVER_ERROR
                else -> Kind.UNKNOWN
            }
            return DictateApiException(kind, message ?: "HTTP $status", null, status, code)
        }

        /** Classifies a transport-level exception (timeouts, no connection, …). */
        fun fromIo(cause: Throwable): DictateApiException {
            val m = cause.message?.lowercase().orEmpty()
            val kind = when {
                cause is InterruptedIOException || m.contains("timeout") || m.contains("timed out") -> Kind.TIMEOUT
                else -> Kind.NETWORK
            }
            return DictateApiException(kind, cause.message, cause)
        }
    }
}
