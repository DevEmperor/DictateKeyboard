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

/**
 * One downloadable file of an on-device model. [destName] is the fixed name it is stored under (so the
 * runtime stays variant-agnostic — see [LocalTranscriptionProvider]); [sizeBytes] and [sha256] are
 * verified after download to guarantee integrity.
 */
data class LocalModelFile(
    val url: String,
    val destName: String,
    val sizeBytes: Long,
    val sha256: String? = null,
)

/**
 * A selectable on-device model (issue #104). [id] doubles as the install directory name and the value
 * stored in [ProviderAccount.transcriptionModel] for the local provider.
 */
data class LocalModelSpec(
    val id: String,
    val displayName: String,
    /** Short note for the picker, e.g. languages / accuracy/speed trade-off. */
    val description: String,
    val files: List<LocalModelFile>,
    /**
     * True for a *streaming* model (issue #233): it transcribes while the user is still speaking, so it
     * can drive the live/real-time path via [LocalRealtimeSession]. Offline models (Whisper, Parakeet)
     * only produce text once the whole utterance is in.
     *
     * This flag — not the presence of `joiner.onnx` — is what tells the two runtimes apart, because a
     * streaming transducer and an offline NeMo transducer both ship a joiner.
     */
    val isStreaming: Boolean = false,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

/**
 * The fixed catalog of on-device models offered for download: one-shot recognizers (Whisper, NeMo
 * Parakeet) plus the streaming ones that transcribe live (Kroko, issue #233). All int8-quantised
 * sherpa-onnx builds.
 *
 * **Attribution / licensing:** every model here comes from an upstream project under a license that
 * permits redistribution (see each entry, and NOTICE). The files are mirrored on the project's own
 * GitHub release ([REL]) for a stable, project-controlled source instead of depending on a third party
 * at runtime. To re-point hosting, change [REL] only. The runtime never fetches this list — it is
 * shipped in the app.
 */
object LocalModelCatalog {

    /** Project-hosted mirror of the model files (GitHub release assets). Single re-point for hosting. */
    private const val REL = "https://github.com/DevEmperor/DictateKeyboard/releases/download/whisper-models-v1"

    /**
     * Silero VAD model, downloaded into every model dir so [LocalTranscriptionProvider] can segment
     * long audio at speech pauses (Whisper itself only handles ~30 s per pass). Same file for all models.
     */
    private val VAD_FILE = LocalModelFile(
        "$REL/silero_vad.onnx", LocalTranscriptionProvider.VAD, 643_854,
        "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
    )

    /** ~99 MB. Fastest, lowest accuracy — good for low-end devices / quick notes. */
    val WHISPER_TINY = LocalModelSpec(
        id = "whisper-tiny",
        displayName = "Whisper Tiny",
        description = "Multilingual · fastest · ~99 MB",
        files = listOf(
            LocalModelFile("$REL/tiny-encoder.int8.onnx", LocalTranscriptionProvider.ENCODER, 12_937_772, "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434"),
            LocalModelFile("$REL/tiny-decoder.int8.onnx", LocalTranscriptionProvider.DECODER, 89_855_401, "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925"),
            LocalModelFile("$REL/tiny-tokens.txt", LocalTranscriptionProvider.TOKENS, 816_730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
            VAD_FILE,
        ),
    )

    /** ~153 MB. The recommended default — noticeably better accuracy, still usable on mid-range. */
    val WHISPER_BASE = LocalModelSpec(
        id = "whisper-base",
        displayName = "Whisper Base",
        description = "Multilingual · recommended · ~153 MB",
        files = listOf(
            LocalModelFile("$REL/base-encoder.int8.onnx", LocalTranscriptionProvider.ENCODER, 29_120_534, "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11"),
            LocalModelFile("$REL/base-decoder.int8.onnx", LocalTranscriptionProvider.DECODER, 130_672_026, "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d"),
            LocalModelFile("$REL/base-tokens.txt", LocalTranscriptionProvider.TOKENS, 816_730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
            VAD_FILE,
        ),
    )

    /** ~358 MB. Most accurate, but large and slower on mid-range — Base is recommended for most users. */
    val WHISPER_SMALL = LocalModelSpec(
        id = "whisper-small",
        displayName = "Whisper Small",
        description = "Multilingual · most accurate, large · ~358 MB (Base recommended)",
        files = listOf(
            LocalModelFile("$REL/small-encoder.int8.onnx", LocalTranscriptionProvider.ENCODER, 112_442_483, "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9"),
            LocalModelFile("$REL/small-decoder.int8.onnx", LocalTranscriptionProvider.DECODER, 262_226_114, "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee"),
            LocalModelFile("$REL/small-tokens.txt", LocalTranscriptionProvider.TOKENS, 816_730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
            VAD_FILE,
        ),
    )

    /** ~99 MB. English-only — faster/leaner than the multilingual tiny when you only need English. */
    val WHISPER_TINY_EN = LocalModelSpec(
        id = "whisper-tiny.en",
        displayName = "Whisper Tiny (English)",
        description = "English only · fastest · ~99 MB",
        files = listOf(
            LocalModelFile("$REL/tiny.en-encoder.int8.onnx", LocalTranscriptionProvider.ENCODER, 12_937_772, "0ce578b827c94a961aacb8fa14b02f096504b337e5c94be37c36238cbe3e8bc6"),
            LocalModelFile("$REL/tiny.en-decoder.int8.onnx", LocalTranscriptionProvider.DECODER, 89_853_865, "06c0e6ff6348d427e51839219d1c886c18cfdf411e629e33f5e1679bff9c1527"),
            LocalModelFile("$REL/tiny.en-tokens.txt", LocalTranscriptionProvider.TOKENS, 835_554, "306cd27f03c1a714eca7108e03d66b7dc042abe8c258b44c199a7ed9838dd930"),
            VAD_FILE,
        ),
    )

    /** ~153 MB. English-only — the recommended English model: good accuracy without the multilingual cost. */
    val WHISPER_BASE_EN = LocalModelSpec(
        id = "whisper-base.en",
        displayName = "Whisper Base (English)",
        description = "English only · recommended for English · ~153 MB",
        files = listOf(
            LocalModelFile("$REL/base.en-encoder.int8.onnx", LocalTranscriptionProvider.ENCODER, 29_120_534, "ef6b936f4c9b1d90a3b68634b60c4ed8576b26172b33c2535ec0e933c9edb823"),
            LocalModelFile("$REL/base.en-decoder.int8.onnx", LocalTranscriptionProvider.DECODER, 130_669_978, "f7162ad6db2dbef16cfaeaa7f945b9d7dd9c1b8d472f6aca82f2273d185e4d41"),
            LocalModelFile("$REL/base.en-tokens.txt", LocalTranscriptionProvider.TOKENS, 835_554, "306cd27f03c1a714eca7108e03d66b7dc042abe8c258b44c199a7ed9838dd930"),
            VAD_FILE,
        ),
    )

    /** ~358 MB. English-only, most accurate English model — large and slower on mid-range. */
    val WHISPER_SMALL_EN = LocalModelSpec(
        id = "whisper-small.en",
        displayName = "Whisper Small (English)",
        description = "English only · most accurate · ~358 MB",
        files = listOf(
            LocalModelFile("$REL/small.en-encoder.int8.onnx", LocalTranscriptionProvider.ENCODER, 112_442_483, "8bdac288f369aa94ee2194059238c465ed82ea9d47ee8fa4a8c0a891873e462f"),
            LocalModelFile("$REL/small.en-decoder.int8.onnx", LocalTranscriptionProvider.DECODER, 262_223_042, "710ccf890e10f3faa15f51ec346081a2723c9f3adb6e4da81c6573a5a6f877fb"),
            LocalModelFile("$REL/small.en-tokens.txt", LocalTranscriptionProvider.TOKENS, 835_554, "306cd27f03c1a714eca7108e03d66b7dc042abe8c258b44c199a7ed9838dd930"),
            VAD_FILE,
        ),
    )

    /**
     * ~670 MB. NVIDIA Parakeet TDT 0.6B v3 (issue #154) — a NeMo *transducer* (encoder/decoder/joiner),
     * not Whisper. Covers 25 European languages; typically faster and more accurate than the small
     * Whisper variants. Exported to ONNX (int8) by the sherpa-onnx project. Licensing: the Parakeet
     * weights are CC-BY-4.0 (NVIDIA); sherpa-onnx export is Apache-2.0 — both allow redistribution.
     */
    val PARAKEET_TDT_V3 = LocalModelSpec(
        id = "parakeet-tdt-0.6b-v3",
        displayName = "Parakeet TDT 0.6B v3",
        description = "25 European languages · fast, accurate · ~670 MB",
        files = listOf(
            LocalModelFile("$REL/parakeet-tdt-0.6b-v3-encoder.int8.onnx", LocalTranscriptionProvider.ENCODER, 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
            LocalModelFile("$REL/parakeet-tdt-0.6b-v3-decoder.int8.onnx", LocalTranscriptionProvider.DECODER, 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
            LocalModelFile("$REL/parakeet-tdt-0.6b-v3-joiner.int8.onnx", LocalTranscriptionProvider.JOINER, 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
            LocalModelFile("$REL/parakeet-tdt-0.6b-v3-tokens.txt", LocalTranscriptionProvider.TOKENS, 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
            VAD_FILE,
        ),
    )

    /**
     * ~670 MB. Parakeet German (primeline, issue #176) — a German-specialized fine-tune of NVIDIA
     * Parakeet TDT 0.6B v3, notably more accurate on German (e.g. ~41 % lower WER on Tuda-De than the
     * base) while keeping the same architecture/speed. Exported to sherpa-onnx ONNX (int8) from the
     * primeline `.nemo` the same way as the base v3. Licensing: CC-BY-4.0 (primeline / NVIDIA base),
     * sherpa-onnx export tooling Apache-2.0 — both allow redistribution with attribution.
     */
    val PARAKEET_PRIMELINE_DE = LocalModelSpec(
        id = "parakeet-primeline-de",
        displayName = "Parakeet German (primeline)",
        description = "German · higher accuracy · ~670 MB",
        files = listOf(
            LocalModelFile("$REL/parakeet-primeline-de-encoder.int8.onnx", LocalTranscriptionProvider.ENCODER, 652_282_409, "4ce2447d5d996f1ea369c68cd8c1a8372c5e2b4c5784c9dc9c706b5e42ddc85e"),
            LocalModelFile("$REL/parakeet-primeline-de-decoder.int8.onnx", LocalTranscriptionProvider.DECODER, 11_845_274, "ebcae1f7cf869507c1c77932e607df5f8d650b67897b41fbdcb3aea09fc39c4d"),
            LocalModelFile("$REL/parakeet-primeline-de-joiner.int8.onnx", LocalTranscriptionProvider.JOINER, 6_355_277, "8220c0d117d81bdd0d8c770881932ac340f1ce4b36932941d561d11ad1aaffce"),
            LocalModelFile("$REL/parakeet-primeline-de-tokens.txt", LocalTranscriptionProvider.TOKENS, 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
            VAD_FILE,
        ),
    )

    /**
     * Builds a Kroko streaming entry. All of them have the same four-file transducer shape and differ
     * only in language and encoder size, so the repetition lives here instead of in six literals.
     *
     * **Attribution / licensing:** Kroko ASR community models by Banafo, licensed **CC-BY-SA**; exported
     * to sherpa-onnx ONNX by the sherpa-onnx project (Apache-2.0). ShareAlike governs adaptations of the
     * model — these files are mirrored verbatim — and both licenses permit redistribution with
     * attribution. See NOTICE.
     *
     * Unlike Whisper these need **no** VAD companion file: the recognizer detects speech pauses itself
     * (endpointing), which is also what settles a segment during live dictation.
     */
    private fun kroko(
        lang: String,
        displayName: String,
        languageLabel: String,
        encoderBytes: Long,
        encoderSha: String,
        decoderBytes: Long,
        decoderSha: String,
        joinerSha: String,
        tokensBytes: Long,
        tokensSha: String,
    ): LocalModelSpec {
        val approxMb = (encoderBytes + decoderBytes + JOINER_BYTES + tokensBytes) / 1_000_000
        return LocalModelSpec(
            id = "kroko-$lang",
            displayName = displayName,
            description = "$languageLabel · live · ~$approxMb MB",
            isStreaming = true,
            files = listOf(
                LocalModelFile("$REL/kroko-$lang-encoder.onnx", LocalTranscriptionProvider.ENCODER, encoderBytes, encoderSha),
                LocalModelFile("$REL/kroko-$lang-decoder.onnx", LocalTranscriptionProvider.DECODER, decoderBytes, decoderSha),
                LocalModelFile("$REL/kroko-$lang-joiner.onnx", LocalTranscriptionProvider.JOINER, JOINER_BYTES, joinerSha),
                LocalModelFile("$REL/kroko-$lang-tokens.txt", LocalTranscriptionProvider.TOKENS, tokensBytes, tokensSha),
            ),
        )
    }

    /** The joiner is architecture-only and byte-identical in size across every Kroko language. */
    private const val JOINER_BYTES = 336_817L

    /** ~71 MB. German live model — measurably more accurate on German than Whisper Base, and far faster. */
    val KROKO_DE = kroko(
        "de", "Kroko German", "German",
        70_091_557, "6e83993d6967ec7a3498b055b7e85ace85b5d64d1b1e8773cb29a43a11f5edb5",
        617_489, "94a29592b403c53fa2231b478637da1ab4abcef7f5e46e432098416a4a3ed562",
        "28356bff070aea51ab1d725a3278e81d19f9300f860d3248a7014292264df15a",
        5_606, "86e8370994ff2c01149ba8c4f8709aa93cdc18914b27a717e291e96faf39a6eb",
    )

    /** ~71 MB. English live model. */
    val KROKO_EN = kroko(
        "en", "Kroko English", "English",
        70_092_599, "d4881c57449d581e0770fd53fa66c2fdc6cd167d92ece7c715e603defc96d9d4",
        617_488, "455ba38466fce8d5a57e7db68a323b684079ca4d9e1dd93a740d9b2429aae3b1",
        "d406f616736350e2a7df3e39398b78eb2fc1a2ca6973a19d3853fa3227e25b52",
        6_310, "396dbeb5f4858875690716084f54e90d339679d0ba3e6b5b584f3d7589254d2d",
    )

    /** ~71 MB. French live model. */
    val KROKO_FR = kroko(
        "fr", "Kroko French", "French",
        70_092_599, "e02facae1daf6f1f13da67ea3ace7c722516d0868d1768d78c0580bc22cc0c5b",
        617_488, "6aed547570e3ab5afc05429a017cedd3a056c16df3baa5703f02461cefa25bac",
        "a51eec759bcdcaae2614686fa2a8b57417b2d420dd55a5a5558b388d35a9b2b6",
        5_415, "fedfb9c844bfb2bf14171f8184863e3d617b815a8667bdd9fc9a3149fde73298",
    )

    /** ~156 MB. Spanish live model — upstream only publishes the larger encoder for Spanish. */
    val KROKO_ES = kroko(
        "es", "Kroko Spanish", "Spanish",
        154_878_102, "2d9f5ef87d1a5257f8a6687e21501c56f3aa2fcbfcfab9364dcc4ce4e06ae81b",
        617_488, "d4ce176b94b25f7acc88717bc3f704fcf5d6e131aaac2e0cabab3885541181ee",
        "dae35df88d676e320fcdb99217328e66dcf722bf11b0f2459e14ddb5b982ded5",
        6_385, "1be5e0a58e05d06d327df4c6b7b5e4f8aba01da6981eb016fcaceafc6a56680f",
    )

    /** Install-dir id of the on-device Smart Turn v3 classifier (issue #191). */
    const val SMART_TURN_ID = "smart-turn-v3"

    /**
     * The Smart Turn v3.2 semantic turn-completion model for long-form auto-split (issue #191). Kept out
     * of [all] because it is not an STT model (it never appears in the transcription-model picker); it is
     * downloaded on demand from the Smart Turn checkbox in the long-form settings. Single derived model
     * file (Pipecat classifier + Whisper feature graph), verified after download.
     */
    val SMART_TURN = LocalModelSpec(
        id = SMART_TURN_ID,
        displayName = "Smart Turn v3",
        description = "On-device thought-completion model for long-form auto-split.",
        files = listOf(
            LocalModelFile(
                "$REL/smart-turn-v3.2-cpu.onnx", "smart-turn.onnx", 8_840_701,
                "7e7bfa1924cf89bd12ca9ba8f6d9165e3154884c377944911926ed9fda2f6bab",
            ),
        ),
    )

    /**
     * All catalog models in display order: Parakeet first (best overall), then the German-specialized
     * Parakeet, then Whisper multilingual and the English-only variants — and finally the streaming
     * models, which the picker renders under their own "Live" heading. Keep the streaming entries last:
     * [LocalModelSection] relies on this order to know where that heading goes.
     */
    val all: List<LocalModelSpec> = listOf(
        PARAKEET_TDT_V3,
        PARAKEET_PRIMELINE_DE,
        WHISPER_TINY, WHISPER_BASE, WHISPER_SMALL,
        WHISPER_TINY_EN, WHISPER_BASE_EN, WHISPER_SMALL_EN,
        KROKO_EN, KROKO_DE, KROKO_ES, KROKO_FR,
    )

    fun byId(id: String): LocalModelSpec? = all.firstOrNull { it.id == id }

    /** The streaming models, in display order — the "Live" group of the on-device picker (#233). */
    val streaming: List<LocalModelSpec> get() = all.filter { it.isStreaming }

    /** The classic one-shot models — everything that is not [streaming]. */
    val batchOnly: List<LocalModelSpec> get() = all.filter { !it.isStreaming }

    /** True if [id] names a streaming model. Unknown ids (e.g. a leftover pref) count as non-streaming. */
    fun isStreaming(id: String): Boolean = byId(id)?.isStreaming == true
}
