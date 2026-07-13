/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.gif

import dev.patrickgold.florisboard.dictate.gif.GifProvider.Companion.PER_PAGE
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/**
 * [GifProvider] backed by the KLIPY GIF API (<https://klipy.com>). KLIPY is a free, self-serve,
 * Tenor-compatible GIF/sticker backend; each user brings their own free API key (Bring-Your-Own-Key,
 * consistent with the app's LLM/STT provider keys). The key is passed as a path segment:
 *
 *     https://api.klipy.com/api/v1/<apiKey>/gifs/{trending,search}?page=&per_page=&customer_id=
 *
 * The many `ad-*` query parameters from KLIPY's sample app are only needed for the (optional) Ads
 * API; we deliberately omit them so results are clean and ad-free.
 *
 * @param apiKeyProvider supplies the current (possibly blank) API key.
 * @param customerIdProvider supplies a stable per-install id used for relevance/localization; may
 *   return a blank string, in which case the parameter is omitted.
 */
class KlipyGifProvider(
    private val apiKeyProvider: () -> String,
    private val customerIdProvider: () -> String = { "" },
) : GifProvider {

    override val isConfigured: Boolean
        get() = apiKeyProvider().isNotBlank()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun trending(page: Int): GifPage = fetch("trending", page, query = null)

    override suspend fun search(query: String, page: Int): GifPage = fetch("search", page, query)

    private suspend fun fetch(endpoint: String, page: Int, query: String?): GifPage =
        withContext(Dispatchers.IO) {
            val apiKey = apiKeyProvider()
            require(apiKey.isNotBlank()) { "KLIPY API key is not set" }
            val urlBuilder = "https://api.klipy.com/api/v1/$apiKey/gifs/$endpoint".toHttpUrl()
                .newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("per_page", PER_PAGE.toString())
            val customerId = customerIdProvider()
            if (customerId.isNotBlank()) {
                urlBuilder.addQueryParameter("customer_id", customerId)
            }
            if (query != null) {
                urlBuilder.addQueryParameter("q", query)
            }
            val request = Request.Builder().url(urlBuilder.build()).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code} from KLIPY $endpoint" }
                val body = response.body?.string().orEmpty()
                check(body.isNotBlank()) { "Empty response body from KLIPY $endpoint" }
                JSON.decodeFromString<KlipyResponse>(body).toGifPage()
            }
        }

    override suspend fun registerShare(id: String) {
        if (id.isBlank()) return
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) return
        try {
            withContext(Dispatchers.IO) {
                val customerId = customerIdProvider()
                val payload = if (customerId.isNotBlank()) {
                    """{"customer_id":"$customerId"}"""
                } else {
                    "{}"
                }
                val request = Request.Builder()
                    .url("https://api.klipy.com/api/v1/$apiKey/gifs/share/$id")
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                client.newCall(request).execute().close()
            }
        } catch (e: Exception) {
            // Purely advisory ranking ping — never surface failures.
            flogError { "KLIPY share ping failed: ${e.message}" }
        }
    }

    private fun KlipyResponse.toGifPage(): GifPage {
        val items = data?.data.orEmpty().mapNotNull { it.toGifItem() }
        return GifPage(items = items, hasNext = data?.hasNext ?: false)
    }

    private fun KlipyItem.toGifItem(): GifItem? {
        // Skip ad slots and anything that isn't a plain GIF.
        if (type != null && type != "gif") return null
        val slug = slug ?: return null
        val file = file ?: return null
        // Preview: smallest animated variant that exists (fast grid); prefer WebP, fall back to GIF.
        val preview = (file.sm ?: file.md ?: file.xs ?: file.hd)?.let { it.webp ?: it.gif }
        // Insert: best-quality GIF (we commit image/gif into the target editor).
        val full = (file.hd ?: file.md ?: file.sm ?: file.xs)?.gif
        val previewUrl = preview?.url ?: return null
        val fullUrl = full?.url ?: return null
        return GifItem(
            id = slug,
            title = title,
            previewUrl = previewUrl,
            fullGifUrl = fullUrl,
            width = (preview.width ?: full.width) ?: 1,
            height = (preview.height ?: full.height) ?: 1,
        )
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

// --- KLIPY response DTOs (only the fields we use; unknown keys are ignored) --------------------

@Serializable
private data class KlipyResponse(
    val result: Boolean? = null,
    val data: KlipyData? = null,
)

@Serializable
private data class KlipyData(
    val data: List<KlipyItem> = emptyList(),
    @SerialName("has_next") val hasNext: Boolean = false,
)

@Serializable
private data class KlipyItem(
    val slug: String? = null,
    val title: String? = null,
    val type: String? = null,
    val file: KlipyDimensions? = null,
)

@Serializable
private data class KlipyDimensions(
    val hd: KlipyFileTypes? = null,
    val md: KlipyFileTypes? = null,
    val sm: KlipyFileTypes? = null,
    val xs: KlipyFileTypes? = null,
)

@Serializable
private data class KlipyFileTypes(
    val gif: KlipyFileMeta? = null,
    val webp: KlipyFileMeta? = null,
)

@Serializable
private data class KlipyFileMeta(
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)
