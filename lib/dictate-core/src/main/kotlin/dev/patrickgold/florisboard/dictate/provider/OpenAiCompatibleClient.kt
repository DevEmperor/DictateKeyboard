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

import android.util.Base64
import android.util.Base64OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.Proxy
import java.security.KeyStore
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * A single client implementation that talks to any OpenAI Chat Completions / Audio Transcriptions
 * compatible endpoint. This one class covers OpenAI, Groq, OpenRouter, Together, DeepInfra, Mistral,
 * xAI, DeepSeek, local Ollama and arbitrary custom servers – they only differ by base URL, key and
 * a few headers (see [ProviderRegistry] and [ProviderConfig]).
 *
 * Google Gemini is also handled here: chat/rewording goes through its OpenAI-compatible layer
 * unchanged, while transcription uses the native generateContent endpoint (see
 * [transcribeGeminiGenerateContent]). Providers with a genuinely different chat API (e.g. Anthropic
 * native) would still need their own [LlmProvider] implementation; until then they are reachable via
 * OpenRouter.
 */
class OpenAiCompatibleClient(
    private val config: ProviderConfig,
) : LlmProvider, TranscriptionProvider {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val client: OkHttpClient by lazy { buildClient() }

    override suspend fun complete(request: ChatRequest): ChatResult {
        // Skip reasoning_effort up-front for endpoint+model pairs already known to reject it, so we don't
        // waste a doubled request on every rewording (#184/#186).
        val key = "${config.normalizedBaseUrl}|${request.model}"
        val effective = if (request.reasoningEffort != null && key in reasoningEffortUnsupported) {
            request.copy(reasoningEffort = null)
        } else {
            request
        }
        return try {
            completeOnce(effective)
        } catch (e: DictateApiException) {
            // Many models/endpoints reject `reasoning_effort`: it's an unknown option (#184), an
            // unsupported value such as "minimal" on Ollama (#186), or the model "does not support
            // thinking" (#186). Rather than hard-fail the rewording, remember it and retry once without it.
            if (effective.reasoningEffort != null && isReasoningEffortRejected(e)) {
                reasoningEffortUnsupported.add(key)
                completeOnce(effective.copy(reasoningEffort = null))
            } else {
                throw e
            }
        }
    }

    /** True when [e] looks like the provider rejecting the `reasoning_effort` field or its value. */
    private fun isReasoningEffortRejected(e: DictateApiException): Boolean {
        val m = (e.message ?: return false).lowercase()
        return "reasoning_effort" in m ||
            "reasoning value" in m ||
            "reasoning effort" in m ||
            ("does not support" in m && ("thinking" in m || "reasoning" in m))
    }

    private suspend fun completeOnce(request: ChatRequest): ChatResult {
        val dto = ChatCompletionRequestDto(
            model = request.model,
            messages = request.messages.map { MessageDto(it.role.wire, it.content) },
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            reasoningEffort = request.reasoningEffort,
        )
        val payload = json.encodeToString(ChatCompletionRequestDto.serializer(), dto)
        val httpRequest = Request.Builder()
            .url(config.normalizedBaseUrl + "chat/completions")
            .headers(authHeaders())
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val body = executeForBody(httpRequest)
        val response = json.decodeFromString(ChatCompletionResponseDto.serializer(), body)
        val text = response.choices.firstOrNull()?.message?.content.orEmpty()
        // Some OpenAI-compatible gateways (notably OpenRouter) report errors as HTTP 200 with an empty
        // `choices` array and an `{ "error": { ... } }` envelope. Surface that instead of returning "".
        if (text.isBlank() && response.choices.isEmpty()) {
            val message = extractErrorMessage(body)
            throw DictateApiException(DictateApiException.Kind.UNKNOWN, message ?: "Empty response from provider")
        }
        val usage = response.usage?.let { TokenUsage(it.promptTokens, it.completionTokens) }
        return ChatResult(text, usage)
    }

    /**
     * Transcribes [request]. [onRetry] is invoked with the (1-based) attempt number each time a
     * transient failure triggers a retry, so the UI can surface a "retrying…" indicator. Dispatches to
     * the right wire format for the configured provider (see [TranscriptionApi]).
     */
    suspend fun transcribe(
        request: TranscriptionRequest,
        onRetry: (attempt: Int) -> Unit,
    ): TranscriptionResult = when {
        // Single-call multimodal (issue #130): route audio through chat/completions with input_audio,
        // overriding the dedicated STT endpoint, so one request transcribes and formats together.
        config.useChatAudio -> transcribeViaChatAudio(request, onRetry)
        else -> transcribeByApi(request, onRetry)
    }

    private suspend fun transcribeByApi(
        request: TranscriptionRequest,
        onRetry: (attempt: Int) -> Unit,
    ): TranscriptionResult = when (config.transcriptionApi) {
        TranscriptionApi.OPENAI_MULTIPART -> transcribeMultipart(request, onRetry)
        TranscriptionApi.OPENROUTER_JSON -> transcribeOpenRouterJson(request, onRetry)
        TranscriptionApi.SONIOX_ASYNC -> transcribeSonioxAsync(request, onRetry)
        TranscriptionApi.GEMINI_GENERATE_CONTENT -> transcribeGeminiGenerateContent(request, onRetry)
        TranscriptionApi.ELEVENLABS_MULTIPART -> transcribeElevenLabs(request, onRetry)
        TranscriptionApi.DEEPGRAM -> transcribeDeepgram(request, onRetry)
        TranscriptionApi.ASSEMBLYAI_ASYNC -> transcribeAssemblyAi(request, onRetry)
        // On-device transcription never uses this HTTP client; the dictation flow routes local providers
        // to LocalTranscriptionProvider before one is ever constructed.
        TranscriptionApi.LOCAL_ONDEVICE -> error("LOCAL_ONDEVICE is handled by LocalTranscriptionProvider")
    }

    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult =
        transcribe(request, onRetry = {})

    /** OpenAI-style `multipart/form-data` upload (OpenAI, Groq, Mistral, most custom servers). */
    private suspend fun transcribeMultipart(
        request: TranscriptionRequest,
        onRetry: (attempt: Int) -> Unit,
    ): TranscriptionResult {
        val fileBody = request.audioFile.asRequestBody(guessAudioMediaType(request.audioFile))
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", request.audioFile.name, fileBody)
            .addFormDataPart("model", request.model)
            .addFormDataPart("response_format", "json")
            .apply {
                val lang = request.language
                if (!lang.isNullOrEmpty() && lang != "detect") addFormDataPart("language", lang)
                if (!request.prompt.isNullOrEmpty()) addFormDataPart("prompt", request.prompt)
            }
            .build()
        val httpRequest = Request.Builder()
            .url(config.normalizedBaseUrl + "audio/transcriptions")
            .headers(authHeaders())
            .post(multipart)
            .build()
        val body = executeForBody(httpRequest, onRetry = onRetry)
        val response = json.decodeFromString(TranscriptionResponseDto.serializer(), body)
        return TranscriptionResult(response.text.trim())
    }

    /**
     * OpenRouter's JSON transcription endpoint: the audio is base64-encoded and wrapped in an
     * `input_audio` object instead of a multipart upload. `prompt` has no equivalent here and is
     * ignored. See https://openrouter.ai/docs/api/api-reference/transcriptions/create-audio-transcriptions
     */
    private suspend fun transcribeOpenRouterJson(
        request: TranscriptionRequest,
        onRetry: (attempt: Int) -> Unit,
    ): TranscriptionResult {
        val base64 = withContext(Dispatchers.IO) {
            base64EncodeFile(request.audioFile)
        }
        val lang = request.language?.takeIf { it.isNotEmpty() && it != "detect" }
        val dto = TranscriptionJsonRequestDto(
            model = request.model,
            inputAudio = InputAudioDto(data = base64, format = guessAudioFormat(request.audioFile)),
            language = lang,
        )
        val payload = json.encodeToString(TranscriptionJsonRequestDto.serializer(), dto)
        val httpRequest = Request.Builder()
            .url(config.normalizedBaseUrl + "audio/transcriptions")
            .headers(authHeaders())
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val body = executeForBody(httpRequest, onRetry = onRetry)
        val response = json.decodeFromString(TranscriptionResponseDto.serializer(), body)
        return TranscriptionResult(response.text.trim())
    }

    /**
     * Single-call multimodal transcription (issue #130): sends the audio as an `input_audio` content part
     * to `chat/completions` of a multimodal model (e.g. Gemini Flash) together with a text instruction, so
     * the model transcribes (and formats, per the instruction) in one request. The instruction comes from
     * [TranscriptionRequest.prompt] (the caller builds it: style + formatting); a sane default is prepended.
     * Returns the model's text output. Reuses the chat error-envelope handling from [complete].
     */
    private suspend fun transcribeViaChatAudio(
        request: TranscriptionRequest,
        onRetry: (attempt: Int) -> Unit,
    ): TranscriptionResult {
        val base64 = withContext(Dispatchers.IO) {
            base64EncodeFile(request.audioFile)
        }
        val extra = request.prompt?.trim()?.takeIf { it.isNotEmpty() }
        val instruction = buildString {
            append("Transcribe the speech in the attached audio.")
            if (extra != null) {
                append(
                    " Then apply ALL of the following instructions to the transcript before returning it — " +
                        "they are mandatory and may change the wording or even the language (e.g. translation, " +
                        "formatting):\n\n",
                )
                append(extra)
            }
            request.language?.takeIf { it.isNotEmpty() && it != "detect" }
                ?.let { append("\n\nThe language spoken in the audio is '$it'.") }
            append("\n\nReturn ONLY the final resulting text after applying the instructions — no preamble, no quotes, no explanations, no notes.")
        }
        val dto = ChatAudioRequestDto(
            model = request.model,
            temperature = 0.0,
            messages = listOf(
                ChatAudioMessageDto(
                    role = "user",
                    content = listOf(
                        ContentPartDto(type = "text", text = instruction),
                        ContentPartDto(
                            type = "input_audio",
                            inputAudio = InputAudioDto(data = base64, format = guessAudioFormat(request.audioFile)),
                        ),
                    ),
                ),
            ),
        )
        val payload = json.encodeToString(ChatAudioRequestDto.serializer(), dto)
        val httpRequest = Request.Builder()
            .url(config.normalizedBaseUrl + "chat/completions")
            .headers(authHeaders())
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val body = executeForBody(httpRequest, onRetry = onRetry)
        val response = json.decodeFromString(ChatCompletionResponseDto.serializer(), body)
        val text = response.choices.firstOrNull()?.message?.content.orEmpty()
        if (text.isBlank() && response.choices.isEmpty()) {
            val message = extractErrorMessage(body)
            throw DictateApiException(DictateApiException.Kind.UNKNOWN, message ?: "Empty response from provider")
        }
        return TranscriptionResult(text.trim())
    }

    /**
     * Soniox async transcription. Unlike the OpenAI/OpenRouter one-shot endpoints this is a multi-step
     * REST flow (see [TranscriptionApi.SONIOX_ASYNC]):
     *   1. upload the audio (`POST /files`) → `file_id`
     *   2. create a job (`POST /transcriptions` with `file_id`) → transcription id
     *   3. poll `GET /transcriptions/{id}` until `status == completed` (or `error`)
     *   4. fetch `GET /transcriptions/{id}/transcript` → the assembled `text`
     * The uploaded file and the transcription are deleted afterwards (best-effort) because Soniox caps the
     * number of stored files/transcriptions per organization. [onRetry] only covers transient per-request
     * network retries; the polling itself is normal operation and does not report a retry.
     */
    private suspend fun transcribeSonioxAsync(
        request: TranscriptionRequest,
        onRetry: (attempt: Int) -> Unit,
    ): TranscriptionResult {
        val base = config.normalizedBaseUrl

        // 1. Upload the audio file.
        val fileBody = request.audioFile.asRequestBody(guessAudioMediaType(request.audioFile))
        val uploadBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", request.audioFile.name, fileBody)
            .build()
        val uploadRequest = Request.Builder()
            .url(base + "files")
            .headers(authHeaders())
            .post(uploadBody)
            .build()
        val fileId = json.decodeFromString(
            SonioxFileDto.serializer(),
            executeForBody(uploadRequest, onRetry = onRetry),
        ).id

        var transcriptionId: String? = null
        try {
            // 2. Create the transcription job referencing the uploaded file.
            val lang = request.language?.takeIf { it.isNotEmpty() && it != "detect" }
            val createDto = SonioxCreateDto(
                model = request.model,
                fileId = fileId,
                languageHints = lang?.let { listOf(it) },
                // The style/punctuation prompt maps onto Soniox's free-text `context` field.
                context = request.prompt?.takeIf { it.isNotBlank() },
            )
            val createRequest = Request.Builder()
                .url(base + "transcriptions")
                .headers(authHeaders())
                .post(json.encodeToString(SonioxCreateDto.serializer(), createDto).toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val id = json.decodeFromString(
                SonioxTranscriptionDto.serializer(),
                executeForBody(createRequest, onRetry = onRetry),
            ).id
            transcriptionId = id

            // 3. Poll until the job completes or fails (or we exceed the overall budget).
            val statusUrl = base + "transcriptions/" + id
            var waitedMs = 0L
            while (true) {
                val statusRequest = Request.Builder()
                    .url(statusUrl)
                    .headers(authHeaders())
                    .get()
                    .build()
                val status = json.decodeFromString(
                    SonioxTranscriptionDto.serializer(),
                    executeForBody(statusRequest, maxRetries = 2, onRetry = onRetry),
                )
                when (status.status) {
                    "completed" -> break
                    "error", "failed" -> {
                        // Soniox reports billing/quota problems as a job error (not an HTTP 402), so run the
                        // message through the same classifier — a balance/quota issue must not look like a
                        // transient "try again" server error. The 502 default keeps genuine processing
                        // failures retryable.
                        throw DictateApiException.fromHttp(
                            status = 502,
                            message = status.errorMessage ?: "Soniox transcription failed",
                        )
                    }
                    // queued / processing / downloading → keep waiting
                    else -> {
                        if (waitedMs >= SONIOX_POLL_TIMEOUT_MS) {
                            throw DictateApiException(
                                DictateApiException.Kind.TIMEOUT,
                                "Soniox transcription timed out",
                            )
                        }
                        delay(SONIOX_POLL_INTERVAL_MS)
                        waitedMs += SONIOX_POLL_INTERVAL_MS
                    }
                }
            }

            // 4. Fetch the finished transcript (the top-level `text` is already fully assembled).
            val transcriptRequest = Request.Builder()
                .url(statusUrl + "/transcript")
                .headers(authHeaders())
                .get()
                .build()
            val transcript = json.decodeFromString(
                SonioxTranscriptDto.serializer(),
                executeForBody(transcriptRequest, onRetry = onRetry),
            )
            return TranscriptionResult(transcript.text.trim())
        } finally {
            // Best-effort cleanup so we don't pile up against Soniox's stored-object limits.
            transcriptionId?.let { sonioxDelete(base + "transcriptions/" + it) }
            sonioxDelete(base + "files/" + fileId)
        }
    }

    /** Fire-and-forget DELETE used to clean up Soniox files/transcriptions; failures are ignored. */
    private suspend fun sonioxDelete(url: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).headers(authHeaders()).delete().build()
                client.newCall(request).execute().use { /* ignore body/status */ }
            }
        }
    }

    /**
     * ElevenLabs Scribe (issue #143): a multipart upload much like [transcribeMultipart], but with the
     * `xi-api-key` auth header (not Bearer), a `model_id` field and the `speech-to-text` path. No prompt.
     */
    private suspend fun transcribeElevenLabs(
        request: TranscriptionRequest,
        onRetry: (attempt: Int) -> Unit,
    ): TranscriptionResult {
        val fileBody = request.audioFile.asRequestBody(guessAudioMediaType(request.audioFile))
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", request.audioFile.name, fileBody)
            .addFormDataPart("model_id", request.model)
            .apply {
                val lang = request.language
                if (!lang.isNullOrEmpty() && lang != "detect") addFormDataPart("language_code", lang)
            }
            .build()
        val httpRequest = Request.Builder()
            .url(config.normalizedBaseUrl + "speech-to-text")
            .header("xi-api-key", config.apiKey)
            .post(multipart)
            .build()
        val body = executeForBody(httpRequest, onRetry = onRetry)
        val response = json.decodeFromString(TranscriptionResponseDto.serializer(), body)
        return TranscriptionResult(response.text.trim())
    }

    /**
     * Deepgram (issue #143): the raw audio bytes are POSTed to `listen?model=…` (model + language as query
     * params) with an `Authorization: Token <key>` header; the transcript is nested in the response.
     */
    private suspend fun transcribeDeepgram(
        request: TranscriptionRequest,
        onRetry: (attempt: Int) -> Unit,
    ): TranscriptionResult {
        val lang = request.language?.takeIf { it.isNotEmpty() && it != "detect" }
        val url = buildString {
            append(config.normalizedBaseUrl).append("listen?model=").append(request.model)
            append("&smart_format=true")
            if (lang != null) append("&language=").append(lang) else append("&detect_language=true")
        }
        val audioBody = request.audioFile.asRequestBody(guessAudioMediaType(request.audioFile))
        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Token ${config.apiKey}")
            .post(audioBody)
            .build()
        val body = executeForBody(httpRequest, onRetry = onRetry)
        val response = json.decodeFromString(DeepgramResponseDto.serializer(), body)
        val text = response.results?.channels?.firstOrNull()?.alternatives?.firstOrNull()?.transcript.orEmpty()
        return TranscriptionResult(text.trim())
    }

    /**
     * AssemblyAI (issue #143): async upload → create → poll, mirroring [transcribeSonioxAsync]. Uses a raw
     * `authorization: <key>` header (no Bearer prefix) against the `api.assemblyai.com/v2` endpoints.
     */
    private suspend fun transcribeAssemblyAi(
        request: TranscriptionRequest,
        onRetry: (attempt: Int) -> Unit,
    ): TranscriptionResult {
        val base = config.normalizedBaseUrl
        val authHeader = config.apiKey

        // 1. Upload the raw audio bytes.
        val uploadRequest = Request.Builder()
            .url(base + "v2/upload")
            .header("authorization", authHeader)
            .post(request.audioFile.asRequestBody(guessAudioMediaType(request.audioFile)))
            .build()
        val uploadUrl = json.decodeFromString(
            AssemblyUploadDto.serializer(),
            executeForBody(uploadRequest, onRetry = onRetry),
        ).uploadUrl

        // 2. Create the transcription job.
        val lang = request.language?.takeIf { it.isNotEmpty() && it != "detect" }
        val createDto = AssemblyCreateDto(
            audioUrl = uploadUrl,
            speechModels = request.model.takeIf { it.isNotBlank() }?.let { listOf(it) },
            languageCode = lang,
            languageDetection = if (lang == null) true else null,
        )
        val createRequest = Request.Builder()
            .url(base + "v2/transcript")
            .header("authorization", authHeader)
            .post(json.encodeToString(AssemblyCreateDto.serializer(), createDto).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val id = json.decodeFromString(
            AssemblyTranscriptDto.serializer(),
            executeForBody(createRequest, onRetry = onRetry),
        ).id

        // 3. Poll until completed / error, bounded by the overall budget.
        val statusUrl = base + "v2/transcript/" + id
        var waitedMs = 0L
        while (true) {
            val pollRequest = Request.Builder()
                .url(statusUrl)
                .header("authorization", authHeader)
                .get()
                .build()
            val dto = json.decodeFromString(
                AssemblyTranscriptDto.serializer(),
                executeForBody(pollRequest, maxRetries = 2, onRetry = onRetry),
            )
            when (dto.status) {
                "completed" -> return TranscriptionResult(dto.text.orEmpty().trim())
                "error" -> throw DictateApiException.fromHttp(
                    status = 502,
                    message = dto.error ?: "AssemblyAI transcription failed",
                )
                else -> {
                    if (waitedMs >= SONIOX_POLL_TIMEOUT_MS) {
                        throw DictateApiException(
                            DictateApiException.Kind.TIMEOUT,
                            "AssemblyAI transcription timed out",
                        )
                    }
                    delay(SONIOX_POLL_INTERVAL_MS)
                    waitedMs += SONIOX_POLL_INTERVAL_MS
                }
            }
        }
    }

    /**
     * Google Gemini transcription. Gemini exposes no speech-to-text endpoint; its multimodal models
     * transcribe audio sent as base64 `inline_data` to the native `generateContent` endpoint (the
     * OpenAI-compatible layer used for chat does not accept audio). We give the model a strict instruction
     * to emit only the verbatim transcript – and nothing at all for silence – so the output can be used
     * directly and won't echo the style hint or hallucinate on empty audio.
     */
    private suspend fun transcribeGeminiGenerateContent(
        request: TranscriptionRequest,
        onRetry: (attempt: Int) -> Unit,
    ): TranscriptionResult {
        val base64 = withContext(Dispatchers.IO) {
            base64EncodeFile(request.audioFile)
        }
        val mimeType = guessAudioMediaType(request.audioFile).toString().substringBefore(";").trim()
        val dto = GeminiGenerateRequestDto(
            contents = listOf(
                GeminiContentDto(
                    parts = listOf(
                        GeminiPartDto(text = buildGeminiTranscriptionInstruction(request)),
                        GeminiPartDto(inlineData = GeminiInlineDataDto(mimeType = mimeType, data = base64)),
                    ),
                ),
            ),
            // Temperature 0 keeps the model faithful to the audio and discourages creative rewrites.
            generationConfig = GeminiGenerationConfigDto(temperature = 0.0),
        )
        val payload = json.encodeToString(GeminiGenerateRequestDto.serializer(), dto)
        // The native URL carries the `models/` prefix itself, so strip any the user/catalog included.
        val model = request.model.removePrefix("models/")
        val httpRequest = Request.Builder()
            .url(geminiNativeBaseUrl() + "models/" + model + ":generateContent")
            .headers(geminiNativeHeaders())
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val body = executeForBody(httpRequest, onRetry = onRetry)
        val response = json.decodeFromString(GeminiGenerateResponseDto.serializer(), body)
        val text = response.candidates.firstOrNull()?.content?.parts.orEmpty()
            .mapNotNull { it.text }
            .joinToString("")
        return TranscriptionResult(text.trim())
    }

    /** Strict transcription prompt for [transcribeGeminiGenerateContent]; folds in the language and style hints. */
    private fun buildGeminiTranscriptionInstruction(request: TranscriptionRequest): String = buildString {
        append("Transcribe the speech in the audio exactly as spoken, with correct punctuation and ")
        append("capitalization. Output only the transcription text. Do not add any preamble, commentary, ")
        append("translation, quotation marks, or formatting. If there is no intelligible speech, output ")
        append("nothing at all.")
        request.language?.takeIf { it.isNotEmpty() && it != "detect" }?.let { lang ->
            append(" The spoken language is '").append(lang).append("'; transcribe in that language.")
        }
        request.prompt?.takeIf { it.isNotBlank() }?.let { style ->
            append("\n\nStyle/context hint (use it to guide spelling and punctuation, but never transcribe ")
            append("the hint itself):\n").append(style)
        }
    }

    /** Native Gemini base URL (`.../v1beta/`) derived from the OpenAI-compat base (`.../v1beta/openai/`). */
    private fun geminiNativeBaseUrl(): String = config.normalizedBaseUrl.removeSuffix("openai/")

    /** Gemini's native API authenticates via the `x-goog-api-key` header rather than a bearer token. */
    private fun geminiNativeHeaders(): Headers {
        val builder = Headers.Builder()
        if (config.apiKey.isNotBlank()) {
            builder.add("x-goog-api-key", config.apiKey)
        }
        config.extraHeaders.forEach { (key, value) -> builder.add(key, value) }
        return builder.build()
    }

    override suspend fun listModels(): List<ModelInfo> {
        // Providers without a model-list endpoint (ElevenLabs, AssemblyAI, #143) ship a curated list
        // instead; return it offline so the picker/connection test work (key validated on first use).
        if (config.transcriptionApi in NO_MODELS_CATALOG_APIS) {
            return config.curatedModels.map { ModelInfo(it) }
        }
        // Deepgram has its own catalog: GET /v1/models with a `Token` header returns `{ stt: [...] }`;
        // the `canonical_name` is the value for the listen `?model=` param (issue #143).
        if (config.transcriptionApi == TranscriptionApi.DEEPGRAM) {
            val request = Request.Builder()
                .url(config.normalizedBaseUrl + "models")
                .header("Authorization", "Token ${config.apiKey}")
                .get()
                .build()
            val body = executeForBody(request, maxRetries = 1)
            return json.decodeFromString(DeepgramModelsDto.serializer(), body)
                .stt
                .map { ModelInfo(it.canonicalName) }
                .filter { it.id.isNotBlank() }
                .sortedBy { it.id.lowercase() }
        }
        // Anthropic (Claude, rewording): its chat/completions endpoint speaks the OpenAI wire format and
        // accepts a Bearer key, but the model catalog is the NATIVE endpoint — GET /v1/models requires an
        // `x-api-key` + `anthropic-version` header and rejects Bearer with "Invalid bearer token". The
        // response is the same `{ data: [{ id }] }` shape, so parse it like the default path. This keeps the
        // picker/connection test live (and actually key-validating) without touching the Bearer chat path.
        if (config.normalizedBaseUrl.startsWith("https://api.anthropic.com/")) {
            val request = Request.Builder()
                .url(config.normalizedBaseUrl + "models")
                .header("x-api-key", config.apiKey)
                .header("anthropic-version", "2023-06-01")
                .get()
                .build()
            val body = executeForBody(request, maxRetries = 1)
            return json.decodeFromString(ModelsResponseDto.serializer(), body)
                .data
                .map { ModelInfo(it.id) }
                .filter { it.id.isNotBlank() }
                .sortedBy { it.id.lowercase() }
        }
        // OpenRouter's /models defaults to output_modalities=text, which hides its DEDICATED speech-to-text
        // models (they output "transcription", e.g. microsoft/mai-transcribe-1.5, Whisper, Parakeet). Ask
        // for all output modalities so the picker can discover them live instead of relying on curation (#157).
        val modelsPath = if (config.transcriptionApi == TranscriptionApi.OPENROUTER_JSON) {
            "models?output_modalities=all"
        } else {
            "models"
        }
        val httpRequest = Request.Builder()
            .url(config.normalizedBaseUrl + modelsPath)
            .headers(authHeaders())
            .get()
            .build()
        val body = executeForBody(httpRequest, maxRetries = 1)
        // Soniox returns `{ models: [ { id, transcription_mode, … } ] }` instead of OpenAI's `{ data: [...] }`,
        // and lists both async and real-time models; only the async ones work with our SONIOX_ASYNC flow.
        if (config.transcriptionApi == TranscriptionApi.SONIOX_ASYNC) {
            val response = json.decodeFromString(SonioxModelsDto.serializer(), body)
            return response.models
                .filter { it.transcriptionMode == "async" }
                .map { ModelInfo(it.id) }
                .sortedBy { it.id.lowercase() }
        }
        val response = json.decodeFromString(ModelsResponseDto.serializer(), body)
        // Gemini's catalog reports ids as `models/gemini-…`; strip that prefix so the picker shows clean
        // ids that also work directly as the `model` field in both chat and generateContent calls.
        val stripPrefix = config.transcriptionApi == TranscriptionApi.GEMINI_GENERATE_CONTENT
        return response.data
            .map {
                ModelInfo(
                    id = if (stripPrefix) it.id.removePrefix("models/") else it.id,
                    // Normalize each provider's own modality reporting to a single "audio" flag, used by
                    // the single-call multimodal feature (issue #130) and the 🎤 markers (#132).
                    inputModalities = if (isAudioInputChatModel(it)) listOf("audio") else emptyList(),
                    // Carry the raw output modalities so dedicated STT models (output "transcription") are
                    // recognised for the transcription picker, separately from chat-audio models (#157).
                    outputModalities = it.architecture?.outputModalities ?: emptyList(),
                )
            }
            .sortedBy { it.id.lowercase() }
    }

    /**
     * Whether a catalog entry is an audio-input **chat** model usable for single-call multimodal
     * transcription (issue #130). Each provider reports this differently (verified against the live APIs):
     *  - **Mistral** exposes a `capabilities` object → `audio && completion_chat` (e.g. Voxtral).
     *  - **OpenRouter** lists `architecture.input_modalities`/`output_modalities`: a chat-audio model is
     *    `audio` in + `text` out; a dedicated STT model is `audio` in + `transcription` out and is excluded
     *    here (with `output_modalities=all`, both are now listed — see listModels, #157).
     *  - **Groq** uses top-level `input_modalities`/`output_modalities` → audio in, **text** out; this
     *    excludes Whisper, whose output modality is `transcription` (STT-only, not a chat model).
     *  - **OpenAI** and **Gemini** report no modality info at all → treated as unknown (false).
     */
    private fun isAudioInputChatModel(m: ModelEntryDto): Boolean {
        m.capabilities?.let { return it.audio && it.completionChat }
        m.architecture?.let { arch ->
            val audioIn = arch.inputModalities.any { it.equals("audio", ignoreCase = true) }
            // A dedicated STT model outputs "transcription", not "text" — it's served via the transcription
            // endpoint, not the chat-audio (#130) path, so it must NOT count as a chat-audio model (#157).
            // When output modalities aren't reported, assume text so existing behaviour is unchanged.
            val chatOutput = arch.outputModalities.isEmpty() ||
                arch.outputModalities.any { it.equals("text", ignoreCase = true) }
            return audioIn && chatOutput
        }
        m.inputModalities?.let { inputs ->
            val audioIn = inputs.any { it.equals("audio", ignoreCase = true) }
            val textOut = m.outputModalities?.any { it.equals("text", ignoreCase = true) } == true
            return audioIn && textOut
        }
        return false
    }

    private fun authHeaders(): Headers {
        val builder = Headers.Builder()
        if (config.apiKey.isNotBlank()) {
            builder.add("Authorization", "Bearer ${config.apiKey}")
        }
        config.extraHeaders.forEach { (key, value) -> builder.add(key, value) }
        return builder.build()
    }

    private suspend fun executeForBody(
        request: Request,
        maxRetries: Int = 3,
        onRetry: (attempt: Int) -> Unit = {},
    ): String {
        var attempt = 0
        while (true) {
            try {
                return withContext(Dispatchers.IO) { executeOnce(request) }
            } catch (e: Throwable) {
                val mapped = when (e) {
                    is DictateApiException -> e
                    is IOException -> DictateApiException.fromIo(e)
                    else -> DictateApiException(DictateApiException.Kind.UNKNOWN, e.message, e)
                }
                if (mapped.kind.isRetryable && attempt < maxRetries) {
                    attempt++
                    onRetry(attempt + 1) // report the upcoming attempt (2nd, 3rd, …)
                    delay(RETRY_DELAY_MS)
                } else {
                    throw mapped
                }
            }
        }
    }

    /** Blocking single HTTP call. Throws [DictateApiException] on non-2xx, [IOException] on transport errors. */
    private fun executeOnce(request: Request): String {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = parseError(body)
                throw DictateApiException.fromHttp(
                    status = response.code,
                    message = error?.message ?: body.take(500),
                    code = error?.code,
                    type = error?.type,
                )
            }
            return body
        }
    }

    /**
     * Extracts the error detail from a non-2xx body. Tries the OpenAI-style `{ "error": { … } }` envelope
     * first, then falls back to Soniox's flat `{ error_type, message, status_code }` shape; null if the body
     * is neither (e.g. plain-text gateways).
     */
    private fun parseError(body: String): ErrorBodyDto? {
        runCatching { json.decodeFromString(ErrorEnvelopeDto.serializer(), body).error }
            .getOrNull()?.let { return it }
        return runCatching {
            val soniox = json.decodeFromString(SonioxErrorDto.serializer(), body)
            if (soniox.message.isNullOrBlank() && soniox.errorType.isNullOrBlank()) {
                null
            } else {
                ErrorBodyDto(message = soniox.message, code = soniox.errorType, type = soniox.errorType)
            }
        }.getOrNull()
    }

    private fun extractErrorMessage(body: String): String? = parseError(body)?.message

    private fun buildClient(): OkHttpClient {
        val timeout = Duration.ofSeconds(config.timeoutSeconds)
        val builder = OkHttpClient.Builder()
            .callTimeout(timeout)
            .connectTimeout(timeout)
            .readTimeout(timeout)
            .writeTimeout(timeout)
        config.proxy?.let { proxy ->
            builder.proxy(proxy.toJavaProxy())
            if (proxy.type == Proxy.Type.HTTP && proxy.hasCredentials) {
                builder.proxyAuthenticator { _, response ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(proxy.username!!, proxy.password!!))
                        .build()
                }
            }
            // SOCKS proxy authentication is not handled here (OkHttp limitation). Add a
            // java.net.Authenticator if SOCKS-with-credentials support is ever required.
        }
        if (config.trustUserCerts) {
            applyUserCertTrust(builder)
        }
        return builder.build()
    }

    /**
     * Makes this client trust user-installed CA certificates as well as system ones (issue #137).
     * Android's default trust manager (API 24+) honours only system CAs, but the `AndroidCAStore`
     * keystore exposes the combined system + user trust anchors, so we build an [X509TrustManager]
     * from it and install it on this client only. Best-effort: if the platform store is unavailable
     * for any reason, the default (system-CAs-only) trust configuration stays in place.
     */
    private fun applyUserCertTrust(builder: OkHttpClient.Builder) {
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore)
            }
            val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: return@runCatching
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), null)
            }
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        }
    }

    private fun guessAudioMediaType(file: File): MediaType {
        val type = when (file.extension.lowercase()) {
            "mp3", "mpeg", "mpga" -> "audio/mpeg"
            "mp4", "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "webm" -> "audio/webm"
            "ogg", "oga" -> "audio/ogg"
            "flac" -> "audio/flac"
            "amr" -> "audio/amr"
            else -> "application/octet-stream"
        }
        return type.toMediaType()
    }

    /**
     * Maps a file to one of OpenRouter's accepted `format` strings (wav, mp3, flac, m4a, ogg, webm,
     * aac). Dictate records m4a; other extensions come from picked files. Unknown extensions are passed
     * through as-is so a still-valid container isn't rejected client-side.
     */
    private fun guessAudioFormat(file: File): String = when (val ext = file.extension.lowercase()) {
        "mp4", "m4a", "aac" -> "m4a"
        "mpeg", "mpga", "mp3" -> "mp3"
        "oga", "ogg" -> "ogg"
        "wav", "flac", "webm" -> ext
        else -> ext
    }

    private fun base64EncodeFile(file: File): String {
        val out = Base64StringOutput(base64Capacity(file.length()))
        file.inputStream().use { input ->
            Base64OutputStream(out, Base64.NO_WRAP).use { base64 ->
                input.copyTo(base64)
            }
        }
        return out.toString()
    }

    private fun base64Capacity(length: Long): Int {
        if (length <= 0L) return 16
        if (length >= (Int.MAX_VALUE.toLong() / 4L) * 3L) return Int.MAX_VALUE
        return (((length + 2L) / 3L) * 4L).toInt()
    }

    private class Base64StringOutput(initialCapacity: Int) : OutputStream() {
        private val builder = StringBuilder(initialCapacity)

        override fun write(b: Int) {
            builder.append((b and 0xff).toChar())
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            var i = offset
            val end = offset + length
            while (i < end) {
                builder.append((buffer[i].toInt() and 0xff).toChar())
                i++
            }
        }

        override fun toString(): String = builder.toString()
    }

    @Serializable
    private data class ChatCompletionRequestDto(
        val model: String,
        val messages: List<MessageDto>,
        val temperature: Double? = null,
        @SerialName("max_tokens") val maxTokens: Int? = null,
        // Omitted when null (encodeDefaults = false), so non-reasoning models are unaffected (issue #141).
        @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    )

    @Serializable
    private data class MessageDto(val role: String, val content: String)

    @Serializable
    private data class ChatCompletionResponseDto(
        val choices: List<ChoiceDto> = emptyList(),
        val usage: UsageDto? = null,
    )

    @Serializable
    private data class ChoiceDto(val message: ResponseMessageDto? = null)

    @Serializable
    private data class ResponseMessageDto(val content: String? = null)

    @Serializable
    private data class UsageDto(
        @SerialName("prompt_tokens") val promptTokens: Long = 0,
        @SerialName("completion_tokens") val completionTokens: Long = 0,
    )

    @Serializable
    private data class TranscriptionJsonRequestDto(
        val model: String,
        @SerialName("input_audio") val inputAudio: InputAudioDto,
        val language: String? = null,
    )

    @Serializable
    private data class InputAudioDto(val data: String, val format: String)

    // Single-call multimodal chat request (issue #130): chat/completions with array content carrying a
    // text instruction + an input_audio part. `encodeDefaults = false` keeps the unused nullable out.
    @Serializable
    private data class ChatAudioRequestDto(
        val model: String,
        val messages: List<ChatAudioMessageDto>,
        // 0 → deterministic, accurate transcription (mirrors the Gemini generateContent path); max_tokens
        // is intentionally left unset so a long dictation is never truncated.
        val temperature: Double? = null,
    )

    @Serializable
    private data class ChatAudioMessageDto(
        val role: String,
        val content: List<ContentPartDto>,
    )

    @Serializable
    private data class ContentPartDto(
        val type: String,
        val text: String? = null,
        @SerialName("input_audio") val inputAudio: InputAudioDto? = null,
    )

    @Serializable
    private data class TranscriptionResponseDto(val text: String = "")

    // --- Deepgram / AssemblyAI DTOs (issue #143) ---

    @Serializable
    private data class DeepgramResponseDto(val results: DeepgramResultsDto? = null)

    @Serializable
    private data class DeepgramResultsDto(val channels: List<DeepgramChannelDto> = emptyList())

    @Serializable
    private data class DeepgramChannelDto(val alternatives: List<DeepgramAlternativeDto> = emptyList())

    @Serializable
    private data class DeepgramAlternativeDto(val transcript: String = "")

    @Serializable
    private data class DeepgramModelsDto(val stt: List<DeepgramModelDto> = emptyList())

    @Serializable
    private data class DeepgramModelDto(@SerialName("canonical_name") val canonicalName: String = "")

    @Serializable
    private data class AssemblyUploadDto(@SerialName("upload_url") val uploadUrl: String)

    @Serializable
    private data class AssemblyCreateDto(
        @SerialName("audio_url") val audioUrl: String,
        // `speech_model` (singular) is deprecated; the current API takes a `speech_models` array (#143).
        @SerialName("speech_models") val speechModels: List<String>? = null,
        @SerialName("language_code") val languageCode: String? = null,
        @SerialName("language_detection") val languageDetection: Boolean? = null,
    )

    @Serializable
    private data class AssemblyTranscriptDto(
        val id: String = "",
        val status: String = "",
        val text: String? = null,
        val error: String? = null,
    )

    // --- Gemini native generateContent DTOs (see transcribeGeminiGenerateContent) ---

    @Serializable
    private data class GeminiGenerateRequestDto(
        val contents: List<GeminiContentDto>,
        val generationConfig: GeminiGenerationConfigDto? = null,
    )

    @Serializable
    private data class GeminiContentDto(
        // Defaulted so the same shape parses the response, where a blocked candidate may omit `parts`.
        val parts: List<GeminiPartDto> = emptyList(),
        val role: String? = null,
    )

    @Serializable
    private data class GeminiPartDto(
        val text: String? = null,
        // Gemini's proto-JSON accepts the snake_case `inline_data` on input; responses only carry `text`.
        @SerialName("inline_data") val inlineData: GeminiInlineDataDto? = null,
    )

    @Serializable
    private data class GeminiInlineDataDto(
        @SerialName("mime_type") val mimeType: String,
        val data: String,
    )

    @Serializable
    private data class GeminiGenerationConfigDto(val temperature: Double? = null)

    @Serializable
    private data class GeminiGenerateResponseDto(
        val candidates: List<GeminiCandidateDto> = emptyList(),
    )

    @Serializable
    private data class GeminiCandidateDto(val content: GeminiContentDto? = null)

    @Serializable
    private data class ModelsResponseDto(val data: List<ModelEntryDto> = emptyList())

    // Each provider exposes audio-input capability differently in its /models response (verified against
    // the live APIs): OpenRouter under `architecture.input_modalities`, Groq as top-level
    // `input_modalities`/`output_modalities`, Mistral via a `capabilities` object. OpenAI and Gemini
    // report no modality info at all. See [isAudioInputChatModel] (issue #130/#132).
    @Serializable
    private data class ModelEntryDto(
        val id: String,
        val architecture: ArchitectureDto? = null, // OpenRouter
        @SerialName("input_modalities") val inputModalities: List<String>? = null, // Groq (top-level)
        @SerialName("output_modalities") val outputModalities: List<String>? = null, // Groq (top-level)
        val capabilities: CapabilitiesDto? = null, // Mistral
    )

    @Serializable
    private data class ArchitectureDto(
        @SerialName("input_modalities") val inputModalities: List<String> = emptyList(),
        // OpenRouter reports output modalities too; a dedicated STT model outputs "transcription" (#157).
        @SerialName("output_modalities") val outputModalities: List<String> = emptyList(),
    )

    @Serializable
    private data class CapabilitiesDto(
        val audio: Boolean = false,
        @SerialName("completion_chat") val completionChat: Boolean = false,
    )

    // --- Soniox async REST DTOs (see transcribeSonioxAsync) ---

    @Serializable
    private data class SonioxFileDto(val id: String)

    @Serializable
    private data class SonioxCreateDto(
        val model: String,
        @SerialName("file_id") val fileId: String,
        @SerialName("language_hints") val languageHints: List<String>? = null,
        val context: String? = null,
    )

    @Serializable
    private data class SonioxTranscriptionDto(
        val id: String = "",
        val status: String = "",
        @SerialName("error_message") val errorMessage: String? = null,
    )

    @Serializable
    private data class SonioxTranscriptDto(val text: String = "")

    @Serializable
    private data class SonioxModelsDto(val models: List<SonioxModelDto> = emptyList())

    @Serializable
    private data class SonioxModelDto(
        val id: String,
        @SerialName("transcription_mode") val transcriptionMode: String = "",
    )

    @Serializable
    private data class SonioxErrorDto(
        @SerialName("error_type") val errorType: String? = null,
        val message: String? = null,
    )

    @Serializable
    private data class ErrorEnvelopeDto(val error: ErrorBodyDto? = null)

    @Serializable
    private data class ErrorBodyDto(
        val message: String? = null,
        // OpenAI-style machine-readable hints (e.g. code = "invalid_api_key", type = "insufficient_quota").
        // Decoded as strings; providers that send a non-string code simply fall back to status/keywords.
        val code: String? = null,
        val type: String? = null,
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val RETRY_DELAY_MS = 3000L

        /**
         * Endpoint+model pairs known to reject `reasoning_effort` (#184/#186). Remembered for the process
         * lifetime so we omit the field up-front and don't waste a doubled request on every rewording.
         */
        private val reasoningEffortUnsupported =
            java.util.Collections.synchronizedSet(HashSet<String>())

        /** Soniox / AssemblyAI async polling: interval between status checks and the overall budget. */
        private const val SONIOX_POLL_INTERVAL_MS = 1500L
        private const val SONIOX_POLL_TIMEOUT_MS = 300_000L

        /** Transcription APIs with no model-list endpoint; listModels() returns curated ids (#143). */
        private val NO_MODELS_CATALOG_APIS = setOf(
            TranscriptionApi.ELEVENLABS_MULTIPART,
            TranscriptionApi.ASSEMBLYAI_ASYNC,
        )

        /** Builds a client from a registry [preset] plus the user's key/proxy. */
        fun from(
            preset: ProviderPreset,
            apiKey: String,
            baseUrlOverride: String? = null,
            proxy: ProxyConfig? = null,
            useChatAudio: Boolean = false,
            trustUserCerts: Boolean = false,
        ): OpenAiCompatibleClient = OpenAiCompatibleClient(
            ProviderConfig(
                baseUrl = baseUrlOverride ?: preset.baseUrl,
                apiKey = apiKey,
                extraHeaders = preset.extraHeaders,
                proxy = proxy,
                transcriptionApi = preset.transcriptionApi,
                useChatAudio = useChatAudio,
                trustUserCerts = trustUserCerts,
                curatedModels = (preset.curatedTranscriptionModels + preset.curatedChatModels).distinct(),
            )
        )
    }
}
