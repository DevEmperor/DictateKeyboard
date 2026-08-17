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
 * Which recognizer a model needs. Every entry names its own instead of the runtime guessing from the
 * files on disk: that worked while a joiner meant "transducer" and its absence meant "Whisper", but
 * Canary (#255) has the same encoder/decoder/tokens shape as Whisper and a completely different config.
 */
enum class LocalModelKind {
    /** Whisper encoder/decoder. Auto-detects the language when none is given. */
    WHISPER,

    /** NeMo transducer (encoder/decoder/joiner) — Parakeet, GigaAM. Language-agnostic at decode time. */
    NEMO_TRANSDUCER,

    /** NVIDIA Canary: an attention encoder/decoder that is *told* its language rather than detecting it. */
    CANARY,

    /**
     * SenseVoice: a single-file non-autoregressive recognizer. Unlike every other kind here it has no
     * encoder/decoder pair at all — just one model file next to the tokens.
     */
    SENSE_VOICE,
}

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
    /** Which recognizer to build for it; see [LocalModelKind]. */
    val kind: LocalModelKind = LocalModelKind.WHISPER,
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
        description = "Multilingual · ~99 MB",
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
        description = "Multilingual · ~153 MB",
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
        description = "Multilingual · ~358 MB",
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
        description = "English · ~99 MB",
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
        description = "English · ~153 MB",
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
        description = "English · ~358 MB",
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
        description = "25 European languages · ~670 MB",
        kind = LocalModelKind.NEMO_TRANSDUCER,
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
        description = "German · ~670 MB",
        kind = LocalModelKind.NEMO_TRANSDUCER,
        files = listOf(
            LocalModelFile("$REL/parakeet-primeline-de-encoder.int8.onnx", LocalTranscriptionProvider.ENCODER, 652_282_409, "4ce2447d5d996f1ea369c68cd8c1a8372c5e2b4c5784c9dc9c706b5e42ddc85e"),
            LocalModelFile("$REL/parakeet-primeline-de-decoder.int8.onnx", LocalTranscriptionProvider.DECODER, 11_845_274, "ebcae1f7cf869507c1c77932e607df5f8d650b67897b41fbdcb3aea09fc39c4d"),
            LocalModelFile("$REL/parakeet-primeline-de-joiner.int8.onnx", LocalTranscriptionProvider.JOINER, 6_355_277, "8220c0d117d81bdd0d8c770881932ac340f1ce4b36932941d561d11ad1aaffce"),
            LocalModelFile("$REL/parakeet-primeline-de-tokens.txt", LocalTranscriptionProvider.TOKENS, 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
            VAD_FILE,
        ),
    )

    /**
     * ~207 MB. NVIDIA Canary 180M Flash (issue #255) — English, German, French and Spanish in a third of
     * Parakeet's footprint, at comparable accuracy (MLS WER 4.75 French / 4.81 German), with punctuation
     * and capitalisation of its own. For the western European languages this is the model to pick unless
     * the phone has room to spare; Parakeet still wins on breadth with its other 21 languages.
     *
     * Unlike the transducers it is an attention encoder/decoder, so it is *told* which language it is
     * hearing rather than working it out — see [LocalTranscriptionProvider] for what happens when the
     * input language is set to something it does not speak.
     *
     * Licensing: Canary weights are CC-BY-4.0 (NVIDIA) and cleared for commercial use; the ONNX int8
     * export is the sherpa-onnx project's own (Apache-2.0). Both allow redistribution with attribution.
     */
    val CANARY_180M_FLASH = LocalModelSpec(
        id = "canary-180m-flash",
        displayName = "Canary 180M Flash",
        description = "English, German, French, Spanish · ~207 MB",
        kind = LocalModelKind.CANARY,
        files = listOf(
            LocalModelFile("$REL/canary-encoder.int8.onnx", LocalTranscriptionProvider.ENCODER, 132_678_643, "7a75b4e2a5857a6dcc0819503bbe3fad66943db4a3ccf21d3f27c633667d303f"),
            LocalModelFile("$REL/canary-decoder.int8.onnx", LocalTranscriptionProvider.DECODER, 74_437_848, "e41a2ab9c0c2fe81a1e8ade5a45fb02a74bc4db7d1f91b89a54a25e2cf79cba2"),
            LocalModelFile("$REL/canary-tokens.txt", LocalTranscriptionProvider.TOKENS, 53_555, "2dae6fc7815f9640645e0c765522b278ee0cef49b482d91f6913e334628d3e77"),
            VAD_FILE,
        ),
    )

    /**
     * ~241 MB. GigaAM v2 Russian (issue #255) — a Russian-specialized NeMo transducer from Salute Devices,
     * where Parakeet only covers Russian as one of 25. Same architecture as the Parakeet entries, so it
     * needs no runtime of its own.
     *
     * Licensing: GigaAM v2 is MIT (the earlier 2024 v1 was non-commercial — that one is deliberately not
     * here); the ONNX export is sherpa-onnx's (Apache-2.0).
     */
    val GIGAAM_V2_RU = LocalModelSpec(
        id = "gigaam-v2-ru",
        displayName = "GigaAM v2 Russian",
        description = "Russian · ~241 MB",
        kind = LocalModelKind.NEMO_TRANSDUCER,
        files = listOf(
            LocalModelFile("$REL/gigaam-v2-ru-encoder.int8.onnx", LocalTranscriptionProvider.ENCODER, 236_314_144, "b51efc61e3c0037ad1cb804079975468de3d175324fe8323aef5be4f5c6a38a1"),
            LocalModelFile("$REL/gigaam-v2-ru-decoder.onnx", LocalTranscriptionProvider.DECODER, 3_331_651, "208e24cc150fb0ebca3fab169502796daa12e0255dcf7b4acf65015c436e9f76"),
            LocalModelFile("$REL/gigaam-v2-ru-joiner.onnx", LocalTranscriptionProvider.JOINER, 1_440_448, "4b02eced18e033fc5173e6c47b6ab166b5efea8d35c3f33a6755ff0d622fb5b0"),
            LocalModelFile("$REL/gigaam-v2-ru-tokens.txt", LocalTranscriptionProvider.TOKENS, 196, "17cc514451bcceac9c280068c71502f8448f99e9fb1456b8d0761651fd0392f2"),
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
     * **Provenance.** German, English and French use sherpa-onnx's own published conversion, which is a
     * leaner re-export (~70 MB encoder) and measurably better than the upstream build. Every other
     * language only exists upstream, so those files are extracted from Banafo's `.data` containers —
     * a plain `u32 length | blob` archive holding exactly encoder/decoder/joiner/tokens — and carry the
     * ~155 MB encoder. That size difference is real: the large build decodes roughly 1.5–2x slower.
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
            description = "$languageLabel · ~$approxMb MB",
            kind = LocalModelKind.NEMO_TRANSDUCER,
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

    /** ~156 MB. Italian live model. */
    val KROKO_IT = kroko(
        "it", "Kroko Italian", "Italian",
        154_878_660, "81c436e4f1cc381276859c858e3e881e382d0e0ca77a21bea1fde74c1275f6b2",
        617_488, "f9c8093a12cb93b14e82f9205f1c4f57cb19143e0cca0079c6770c717611961c",
        "3056ae55986ba4fb6203599baaeebb5f7eeb776798c3146df3bf76a198d172a9",
        6_107, "6c1ce19563e9fa59cc05ad921ccc31106497c1e2895346e2aa3fa936a103ed39",
    )

    /** ~156 MB. Dutch live model. */
    val KROKO_NL = kroko(
        "nl", "Kroko Dutch", "Dutch",
        154_878_660, "200616faee86985fee53f16073f8aa2b745988ef7a1dc7825271c464193d0266",
        617_488, "e5f8003008d4f00b52f0f16fb76544218957115e2b12a6397a89ec6bfe0e21f9",
        "4813be19995e1188b4b144e69ecb23d2e26e47f7d21b263443e647d8d7edc156",
        6_241, "157f0d8363aa1d179eebbe5948db07d19f56711328ba0561376e87d5cb68ee9e",
    )

    /** ~156 MB. Portuguese live model. */
    val KROKO_PT = kroko(
        "pt", "Kroko Portuguese", "Portuguese",
        154_878_660, "336b9a62fd37d8b94855fcbe0414000aa5f1bd75d4cb907e112bd6b7ef97c52e",
        617_488, "2380832dbb1867779a550aea3948776d6a53ffa1cccd075bb7592ebaf21b7638",
        "de7afbc23e7e55af7fed85780690b8f883c62b881fe14d546d9677151581962f",
        6_235, "e9b9b588c138558388c9a53385007082f58130a0ceddce8df6a4aed032162b3f",
    )

    /** ~156 MB. Swedish live model. */
    val KROKO_SV = kroko(
        "sv", "Kroko Swedish", "Swedish",
        154_877_618, "60c367201c16f6a8f3fbd7edcf86c2bf59e71455a841fdaacbaf5ea6767273b0",
        617_488, "3424e0908f578d0fd6a1911e73e0d6fc4ef430b8892389d1c49768b5ee75ead1",
        "194e38c970ca06743439b101b7dcb4b45b4e215d7b6dbc9419f4a1c557286413",
        5_706, "d6b161d3547eaae1927ddba4af83c117f27ae3efff685850c0c50b538b5a5781",
    )

    /** ~156 MB. Turkish live model. */
    val KROKO_TR = kroko(
        "tr", "Kroko Turkish", "Turkish",
        154_878_660, "d36d8abbcbd9d87c5446f296b59a9fce26ccd87c7edb278f61631ef3d02803a2",
        617_489, "08f317129a6ffed14f8755e61d50b1df6ac1cc5af3bdd832b7ea93961199217e",
        "aa49f0e96e4ef5ea408cb09f4b2ef5785995513b21fd8f04675d2f5f0ffcd1f3",
        5_423, "c7e93bbc0f57852154df4e52005ae163d653f3daf0d1dbeb4f75a3ffa4b25c57",
    )

    /** ~156 MB. Hebrew live model. */
    val KROKO_HE = kroko(
        "he", "Kroko Hebrew", "Hebrew",
        154_878_660, "6b4a447c2bbb829ec6b58677befd136220d7b1e090fbb66247d150c5066143d7",
        617_488, "8cb83589aa39bb898a2a52dc2fe87155deb9abf9a0e5d86f8c6acece1164330e",
        "77d8566a35eae6f9d45dce1095d2c60b381515470b0755159b23fe6f636fbd32",
        6_331, "be979c5715abf12e88a88318e60b33e744fffd83f47b562e5d9964539d46ada1",
    )

    /**
     * ~240 MB. SenseVoice Small (issue #262) — Mandarin, Cantonese, English, Japanese and Korean, and by
     * a wide margin the best Chinese this app can do without a network. Whisper only ever treated Chinese
     * as one language among a hundred; this one was trained for it. It matters most where none of the
     * cloud providers are reachable, which is the situation the reporter of #262 is in, but it is the
     * better pick for Chinese, Japanese or Korean anywhere.
     *
     * Architecturally it is neither Whisper nor a transducer: one non-autoregressive model file, no
     * decoder, which is why [LocalModelKind.SENSE_VOICE] exists and why [LocalModelSpec.files] is the
     * authority on what a model needs on disk rather than a hardcoded encoder/decoder/tokens triple.
     *
     * **Provenance.** The int8 export dated 2024-07-17, which is the original FunAudioLLM model. Note the
     * trap: sherpa-onnx also publishes a *newer-dated* 2025-09-09 build under a near-identical name, and
     * that one is not a newer version of this model but a Cantonese fine-tune (ASLP-lab/WSYue-ASR) —
     * a downgrade for the Mandarin this entry is mainly here for.
     *
     * **Licensing:** SenseVoice weights are under the FunASR Model License v1.1 (© Alibaba Group), which
     * permits commercial use as long as source, author and model name are attributed — hence the entry in
     * NOTICE and on the attributions screen, and hence "SenseVoice" in the display name. The sherpa-onnx
     * ONNX export is Apache-2.0.
     */
    val SENSE_VOICE_SMALL = LocalModelSpec(
        id = "sense-voice-small",
        displayName = "SenseVoice Small",
        description = "Chinese, Cantonese, English, Japanese, Korean · ~240 MB",
        kind = LocalModelKind.SENSE_VOICE,
        files = listOf(
            LocalModelFile("$REL/sense-voice-small-model.int8.onnx", LocalTranscriptionProvider.MODEL, 239_233_841, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
            LocalModelFile("$REL/sense-voice-small-tokens.txt", LocalTranscriptionProvider.TOKENS, 315_894, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
            VAD_FILE,
        ),
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
     * All catalog models in display order: Parakeet first (broadest), then Canary — which beats it on
     * size for the four languages it does speak — then the language-specialized ones, then Whisper
     * multilingual and its English-only variants. Finally the streaming models, which the picker renders
     * under their own "Live" heading. Keep the streaming entries last: [LocalModelSection] relies on this
     * order to know where that heading goes.
     */
    val all: List<LocalModelSpec> = listOf(
        PARAKEET_TDT_V3,
        CANARY_180M_FLASH,
        PARAKEET_PRIMELINE_DE,
        GIGAAM_V2_RU,
        SENSE_VOICE_SMALL,
        WHISPER_TINY, WHISPER_BASE, WHISPER_SMALL,
        WHISPER_TINY_EN, WHISPER_BASE_EN, WHISPER_SMALL_EN,
        KROKO_EN, KROKO_DE, KROKO_ES, KROKO_FR,
        KROKO_IT, KROKO_NL, KROKO_PT, KROKO_SV, KROKO_TR, KROKO_HE,
    )

    /**
     * The two models the setup wizard offers for [language] (issue #273): the one that fits, and the
     * bigger one for anyone willing to trade storage for accuracy. Everything else stays one tap away
     * behind "show all models" — a first-run screen that lists twenty-one downloads is not a choice, it
     * is an obstacle.
     *
     * [language] is a plain ISO code (`de`, `zh`); region and script are ignored.
     *
     * **Canary is deliberately absent**, although for English, German, French and Spanish it is the best
     * accuracy-per-megabyte in the catalog. It is *told* its language rather than detecting it
     * (`LocalTranscriptionProvider.canaryLanguage`), and during setup the input language is still on
     * auto-detect — so it would be handed "en" and would transcribe German as English. Every model
     * offered here either detects its own language (Whisper, SenseVoice) or is language-agnostic while
     * decoding (the transducers).
     */
    fun onboardingPicks(language: String): List<LocalModelSpec> =
        when (language.lowercase().substringBefore('-').substringBefore('_')) {
            // SenseVoice was trained for these; Whisper only ever treated them as languages number
            // seventy-something. Its fallback is the multilingual Whisper, not the English one.
            "zh", "yue", "ja", "ko" -> listOf(SENSE_VOICE_SMALL, WHISPER_SMALL)
            "ru" -> listOf(GIGAAM_V2_RU, WHISPER_SMALL)
            // German is the one language with a specialized model that is also cheap to recommend
            // against: same architecture, far better German, but 670 MB — an offer, not a default.
            "de" -> listOf(WHISPER_BASE, PARAKEET_PRIMELINE_DE)
            "en" -> listOf(WHISPER_BASE_EN, WHISPER_SMALL_EN)
            else -> listOf(WHISPER_BASE, WHISPER_SMALL)
        }

    /** Which recognizer [id] needs; unknown ids (a leftover pref) fall back to the Whisper shape. */
    fun kindOf(id: String): LocalModelKind = byId(id)?.kind ?: LocalModelKind.WHISPER

    fun byId(id: String): LocalModelSpec? = all.firstOrNull { it.id == id }

    /** The streaming models, in display order — the "Live" group of the on-device picker (#233). */
    val streaming: List<LocalModelSpec> get() = all.filter { it.isStreaming }

    /** The classic one-shot models — everything that is not [streaming]. */
    val batchOnly: List<LocalModelSpec> get() = all.filter { !it.isStreaming }

    /** True if [id] names a streaming model. Unknown ids (e.g. a leftover pref) count as non-streaming. */
    fun isStreaming(id: String): Boolean = byId(id)?.isStreaming == true
}
