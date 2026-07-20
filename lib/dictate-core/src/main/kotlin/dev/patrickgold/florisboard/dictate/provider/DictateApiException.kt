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

/**
 * Normalised error for all provider calls. The [kind] lets the UI show the right message/action
 * (mirrors the error handling of the original Dictate input method service).
 */
class DictateApiException(
    val kind: Kind,
    message: String? = null,
    cause: Throwable? = null,
    val httpStatus: Int? = null,
) : Exception(message, cause) {

    enum class Kind {
        INVALID_API_KEY,
        QUOTA_EXCEEDED,
        CONTENT_SIZE_LIMIT,
        FORMAT_NOT_SUPPORTED,
        TIMEOUT,
        NETWORK,
        SERVER_ERROR,
        UNKNOWN;

        /** Whether retrying the same request could plausibly succeed. */
        val isRetryable: Boolean
            get() = this == TIMEOUT || this == NETWORK || this == SERVER_ERROR || this == UNKNOWN
    }

    companion object {
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
                status == 401 || status == 403 ||
                    hay.contains("api key") || hay.contains("api_key") || hay.contains("invalid_api_key") ||
                    hay.contains("unauthorized") || hay.contains("authentication") -> Kind.INVALID_API_KEY
                status == 429 || status == 402 ||
                    hay.contains("quota") || hay.contains("insufficient_quota") || hay.contains("billing") ||
                    hay.contains("rate limit") || hay.contains("rate_limit") ||
                    // Soniox 402 billing signals: balance/budget exhausted.
                    hay.contains("exhausted") || hay.contains("balance") || hay.contains("budget") -> Kind.QUOTA_EXCEEDED
                status == 413 ||
                    hay.contains("audio duration") || hay.contains("content size limit") ||
                    hay.contains("too large") || hay.contains("maximum context length") -> Kind.CONTENT_SIZE_LIMIT
                hay.contains("format") || hay.contains("unsupported") || hay.contains("decode") ||
                    hay.contains("could not process") -> Kind.FORMAT_NOT_SUPPORTED
                status in 500..599 -> Kind.SERVER_ERROR
                else -> Kind.UNKNOWN
            }
            return DictateApiException(kind, message ?: "HTTP $status", null, status)
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
