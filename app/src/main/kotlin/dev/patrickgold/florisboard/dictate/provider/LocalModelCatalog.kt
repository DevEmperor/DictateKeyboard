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

    /**
     * Dolphin's CTC branch: the same single-file shape as [SENSE_VOICE], but its config has no language
     * field at all — it is neither told a language nor offered "auto", it simply decodes.
     */
    DOLPHIN,
}

/**
 * Who made a model's weights and under what terms. All three are proper nouns, identifiers and a URL,
 * so none of them is ever translated. Restates what `assets/license/data_attributions.txt` says, and a
 * test holds the two in step — the file is what the attributions screen renders, this is what the
 * picker can show next to the model it belongs to.
 */
data class ModelCredit(val author: String, val license: String, val url: String)

/**
 * A group of variants that share a name and differ only in size or in language, shown as one row at
 * the top level of the picker. Sixteen of the catalog's entries are two such groups.
 *
 * Presentation only. **Never fold a family's members into a single [LocalModelSpec].**
 * `LocalModelManager.installedIds` filters against [LocalModelCatalog.all] and
 * [LocalModelCatalog.kindOf] falls back to `WHISPER` for an id it does not know, so a member that
 * left `all` would orphan its bytes on disk *and* build the wrong recognizer for anyone who had it.
 */
enum class LocalModelFamily(val displayName: String) {
    WHISPER("Whisper"),
    KROKO("Kroko"),
}

/**
 * One row of the picker's first level: a model that stands on its own, or a family standing in for the
 * variants behind it.
 *
 * A view over [LocalModelCatalog.all], never a replacement for it — `all` stays the authority on what
 * is installed and which recognizer to build.
 */
sealed interface LocalModelEntry {
    val isStreaming: Boolean

    data class Single(val spec: LocalModelSpec) : LocalModelEntry {
        override val isStreaming: Boolean get() = spec.isStreaming
    }

    data class Family(
        val family: LocalModelFamily,
        val members: List<LocalModelSpec>,
    ) : LocalModelEntry {
        /** A family is all live or none of it, which a test holds it to, so the first member decides. */
        override val isStreaming: Boolean get() = members.first().isStreaming
    }
}

/**
 * A selectable on-device model (issue #104). [id] doubles as the install directory name and the value
 * stored in [ProviderAccount.transcriptionModel] for the local provider.
 */
data class LocalModelSpec(
    val id: String,
    val displayName: String,
    /**
     * The languages this model transcribes, as bare ISO-639-1 codes — `zh`, never `zh-CN`, because a
     * model speaks a language rather than a region. In the order they should be named, which for a list
     * short enough to read out means the best-supported first and for the long ones is just
     * alphabetical, since those are summarised by count. Empty only for [SMART_TURN], which is not a
     * recognizer; a test holds every entry in [LocalModelCatalog.all] to at least one.
     */
    val languages: List<String> = emptyList(),
    /**
     * Whether the model writes its own punctuation and capitals, rather than a flat run of words.
     *
     * Every value in the catalog is read off the model instead of its marketing: either from a decode
     * against the vendored runtime, or from the presence of `.` `,` `?` `!` in its own `tokens.txt`.
     * That is what separates GigaAM v2, whose 196-byte vocabulary has no mark in it at all, from v3.
     */
    val punctuates: Boolean = false,
    /**
     * The longest stretch of audio this model may be handed in one decode, in seconds.
     *
     * Everything shorter goes through in a single pass; past it the recording is split at speech pauses
     * and the pieces decoded separately, which costs punctuation and capitals at every seam — the model
     * starts each piece without the sentence it was in the middle of.
     *
     * The default is Whisper's, and for years it was every model's: sherpa-onnx crops a Whisper decode
     * at 30 s and logs that it "discarded the remaining data", so 28 leaves a margin. Nothing else in
     * the catalog has that ceiling, and several have one far higher — each entry says what its own is
     * and where the number comes from.
     */
    val maxSegmentSeconds: Int = 28,
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
    /** The top-level row this hides behind, or null when it stands on its own. See [LocalModelFamily]. */
    val family: LocalModelFamily? = null,
    /** Author, licence and upstream page; see [ModelCredit]. Null only for entries outside [LocalModelCatalog.all]. */
    val credit: ModelCredit? = null,
    /**
     * The id of the model that replaced this one, or null while it is still worth offering.
     *
     * A superseded entry stays in [LocalModelCatalog.all] and simply stops being offered to anyone who
     * does not already have it — see [LocalModelCatalog.visibleTopLevel]. It must **not** be deleted
     * outright: `LocalModelManager.installedIds` filters against `all` and [LocalModelCatalog.kindOf]
     * falls back to `WHISPER` for an unknown id, so removing it would leave whoever installed it with
     * hundreds of megabytes they can no longer delete *and* a recognizer built from the wrong kind.
     */
    val supersededBy: String? = null,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /**
     * Whether a long recording is cut into pieces at speech pauses before decoding.
     *
     * Derived from the VAD file rather than declared, because that file is the only thing
     * `LocalTranscriptionProvider` actually branches on: a declared flag could disagree with what the
     * runtime does, a derived one cannot. Note what this does *not* say — a model that could swallow an
     * hour in one pass is still cut at 28 s here, because the segmentation is the provider's, not the
     * model's.
     */
    val splitsLongAudio: Boolean
        get() = files.any { it.destName == LocalTranscriptionProvider.VAD }

    /**
     * False for a model that has to be *told* which language it is hearing. Only Canary, which is why
     * it is the one model kept out of [LocalModelCatalog.onboardingPicks].
     */
    val detectsLanguage: Boolean get() = kind != LocalModelKind.CANARY
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
     * The language lists the entries below share. Kept here rather than inline because two of them are
     * long enough to bury the entry that carries them.
     */
    private object Langs {
        /**
         * Whisper's 99, read out of the `all_language_codes` metadata of the very `base-encoder` file
         * this catalog ships (2026-09-20) rather than from a model card — the runtime takes the list
         * from there, so that is the only place it is a fact. Alphabetical; the picker summarises them
         * by count. Note `yue` is absent: Cantonese arrived with large-v3, and these are tiny/base/small.
         */
        val WHISPER = listOf(
            "af", "am", "ar", "as", "az", "ba", "be", "bg", "bn", "bo", "br", "bs", "ca", "cs", "cy",
            "da", "de", "el", "en", "es", "et", "eu", "fa", "fi", "fo", "fr", "gl", "gu", "ha", "haw",
            "he", "hi", "hr", "ht", "hu", "hy", "id", "is", "it", "ja", "jw", "ka", "kk", "km", "kn",
            "ko", "la", "lb", "ln", "lo", "lt", "lv", "mg", "mi", "mk", "ml", "mn", "mr", "ms", "mt",
            "my", "ne", "nl", "nn", "no", "oc", "pa", "pl", "ps", "pt", "ro", "ru", "sa", "sd", "si",
            "sk", "sl", "sn", "so", "sq", "sr", "su", "sv", "sw", "ta", "te", "tg", "th", "tk", "tl",
            "tr", "tt", "uk", "ur", "uz", "vi", "yi", "yo", "zh",
        )

        /** The 25 European languages NVIDIA documents for Parakeet TDT 0.6B v3 (read 2026-09-20). */
        val PARAKEET_V3 = listOf(
            "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "hr", "hu", "it", "lt", "lv",
            "mt", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "uk",
        )
    }

    /** The credits the entries below share, and the shape of the ones they do not. */
    private object Credits {
        val WHISPER = ModelCredit("OpenAI", "MIT", "https://github.com/openai/whisper")
        val KROKO = ModelCredit("Banafo", "CC-BY-SA", "https://huggingface.co/Banafo/Kroko-ASR")
        val GIGAAM = ModelCredit(
            "Salute Devices / GigaChat Team", "MIT", "https://github.com/salute-developers/GigaAM",
        )
        val SENSE_VOICE = ModelCredit(
            "Alibaba Group", "FunASR Model Open Source License Agreement v1.1",
            "https://github.com/FunAudioLLM/SenseVoice",
        )
        val PRIMELINE = ModelCredit(
            "primeline", "CC-BY-4.0", "https://huggingface.co/primeline/parakeet-primeline",
        )
        val MOONDREAM = ModelCredit(
            "moondream", "CC-BY-4.0", "https://huggingface.co/moondream/parakeet-ultra",
        )

        /** NVIDIA publishes under CC-BY-4.0 per model, never per family — hence the URL per entry. */
        fun nvidia(url: String) = ModelCredit("NVIDIA", "CC-BY-4.0", url)
    }

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
        languages = Langs.WHISPER,
        punctuates = true,
        family = LocalModelFamily.WHISPER,
        credit = Credits.WHISPER,
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
        languages = Langs.WHISPER,
        punctuates = true,
        family = LocalModelFamily.WHISPER,
        credit = Credits.WHISPER,
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
        languages = Langs.WHISPER,
        punctuates = true,
        family = LocalModelFamily.WHISPER,
        credit = Credits.WHISPER,
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
        languages = listOf("en"),
        punctuates = true,
        family = LocalModelFamily.WHISPER,
        credit = Credits.WHISPER,
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
        languages = listOf("en"),
        punctuates = true,
        family = LocalModelFamily.WHISPER,
        credit = Credits.WHISPER,
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
        languages = listOf("en"),
        punctuates = true,
        family = LocalModelFamily.WHISPER,
        credit = Credits.WHISPER,
        files = listOf(
            LocalModelFile("$REL/small.en-encoder.int8.onnx", LocalTranscriptionProvider.ENCODER, 112_442_483, "8bdac288f369aa94ee2194059238c465ed82ea9d47ee8fa4a8c0a891873e462f"),
            LocalModelFile("$REL/small.en-decoder.int8.onnx", LocalTranscriptionProvider.DECODER, 262_223_042, "710ccf890e10f3faa15f51ec346081a2723c9f3adb6e4da81c6573a5a6f877fb"),
            LocalModelFile("$REL/small.en-tokens.txt", LocalTranscriptionProvider.TOKENS, 835_554, "306cd27f03c1a714eca7108e03d66b7dc042abe8c258b44c199a7ed9838dd930"),
            VAD_FILE,
        ),
    )

    /**
     * ~670 MB. Parakeet Ultra (issue #414) — moondream's post-train of [PARAKEET_TDT_V3], which it
     * replaces at the same size, the same speed and the same 25 languages. Identical architecture and a
     * byte-identical tokenizer, so `tokens.txt` is v3's file unchanged.
     *
     * **What it is really worth is not what its model card says.** In full precision it beats the
     * original by ~9 % on German, which is roughly the advertised margin. The gap that matters here
     * opens only under the int8 quantisation everything in this catalog ships as: measured on 40 FLEURS
     * dev files through the vendored sherpa-onnx 1.13.3, German goes 3,95 → 8,62 % for v3 but 3,59 →
     * 3,71 % for this one, and Latvian 22,19 → 38,46 % against 17,90 → 25,15 %. So the shipped file
     * improves by 57 % (de), 35 % (lv) and 31 % (en) while the fp32 models are nearly level. The same
     * training that lets moondream publish a 1.58-bit sibling is the plausible reason.
     *
     * **Not exported by sherpa-onnx** — the only such entry. moondream publishes PyTorch weights and a
     * closed runtime, no ONNX. Since the architecture is the original's, the weights were substituted
     * into sherpa's own v3 graph instead of exporting a new one, so every property the runtime reads
     * (I/O names, TDT durations, subsampling factor, the `tdt` marker in the url metadata — see #176)
     * is the file sherpa published. The mapping was proved by replaying it with NVIDIA's own weights,
     * which reproduced both the fp32 export and the shipped `encoder.int8.onnx` bit for bit.
     *
     * Licensing: CC-BY-4.0 (moondream, over NVIDIA's CC-BY-4.0 base).
     */
    val PARAKEET_ULTRA = LocalModelSpec(
        id = "parakeet-ultra",
        displayName = "Parakeet Ultra",
        // Same encoder as v3, so the same reasoning about attention memory on a phone applies.
        maxSegmentSeconds = 120,
        languages = Langs.PARAKEET_V3,
        punctuates = true,
        credit = Credits.MOONDREAM,
        kind = LocalModelKind.NEMO_TRANSDUCER,
        files = listOf(
            LocalModelFile("$REL/parakeet-ultra-encoder.int8.onnx", LocalTranscriptionProvider.ENCODER, 652_184_240, "9f7435fa791c00b7b596cfd98878dbc0006e1059e7fc1b1884331d6828611ef6"),
            LocalModelFile("$REL/parakeet-ultra-decoder.int8.onnx", LocalTranscriptionProvider.DECODER, 11_845_274, "1fab98fe6c12aded87d2da66272cc9e148d0a0044ce3850a12fe56302ec4a922"),
            LocalModelFile("$REL/parakeet-ultra-joiner.int8.onnx", LocalTranscriptionProvider.JOINER, 6_355_277, "8ba94c6919c17a6bd27368fb89533628a72ffc81d4acedebf3cdcb2cae331dbb"),
            LocalModelFile("$REL/parakeet-ultra-tokens.txt", LocalTranscriptionProvider.TOKENS, 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
            VAD_FILE,
        ),
    )

    /**
     * ~670 MB. NVIDIA Parakeet TDT 0.6B v3 (issue #154) — a NeMo *transducer* (encoder/decoder/joiner),
     * not Whisper. Covers 25 European languages; typically faster and more accurate than the small
     * Whisper variants. Exported to ONNX (int8) by the sherpa-onnx project. Licensing: the Parakeet
     * weights are CC-BY-4.0 (NVIDIA); sherpa-onnx export is Apache-2.0 — both allow redistribution.
     *
     * **Retired by [PARAKEET_ULTRA]** (#414), which is the same model trained further: same size, same
     * languages, same speed, and it keeps its accuracy through int8 where this one loses half of it.
     * It stays here for everyone who already has it — see [visibleTopLevel]; dropping the id would
     * orphan 670 MB on their disk and build the wrong recognizer for their saved pick.
     */
    val PARAKEET_TDT_V3 = LocalModelSpec(
        id = "parakeet-tdt-0.6b-v3",
        displayName = "Parakeet TDT 0.6B v3",
        supersededBy = "parakeet-ultra",
        // NVIDIA: "up to 24 minutes long with full attention" — on an A100 80 GB. Two minutes is
        // what a phone is offered instead: attention memory grows with the square of the length,
        // and a pause every few seconds means the VAD rarely builds a piece this long anyway.
        maxSegmentSeconds = 120,
        languages = Langs.PARAKEET_V3,
        punctuates = true,
        credit = Credits.nvidia("https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3"),
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
     * ~137 MB. NVIDIA Parakeet TDT 110M (issue #406) — English in a fifth of [PARAKEET_TDT_V3]'s
     * footprint, trained on 36 000 h, and the first small English model in this catalog that is not a
     * Whisper. It writes its own punctuation and capitals, which the English Whispers do far less
     * reliably at this size.
     *
     * A TDT transducer, so the trap from #176 applies: sherpa-onnx decides a NeMo transducer is TDT by
     * looking for the substring `tdt` in the encoder's `url` metadata, and dies natively at `InitJoiner`
     * if it is missing. This export carries `url=https://huggingface.co/parakeet-tdt_ctc-110m`, and a
     * decode against the vendored 1.13.3 logged `TDT model. vocab_size: 1025, num_durations: 5` — which
     * is also why these files are mirrored byte for byte: rewriting the ONNX metadata would break it.
     *
     * Licensing: weights CC-BY-4.0 (NVIDIA), sherpa-onnx ONNX export Apache-2.0.
     */
    val PARAKEET_TDT_110M_EN = LocalModelSpec(
        id = "parakeet-tdt-110m-en",
        displayName = "Parakeet TDT 110M",
        // NVIDIA: "can transcribe up to 20 minutes of audio in one single pass". Same reasoning as
        // the 0.6B above for why the app stops well short of that.
        maxSegmentSeconds = 120,
        languages = listOf("en"),
        punctuates = true,
        credit = Credits.nvidia("https://huggingface.co/nvidia/parakeet-tdt_ctc-110m"),
        kind = LocalModelKind.NEMO_TRANSDUCER,
        files = listOf(
            LocalModelFile("$REL/parakeet-tdt-110m-en-encoder.int8.onnx", LocalTranscriptionProvider.ENCODER, 131_113_202, "0f35509ddeb9b39002fb077d979a9fe74f06eb0bc4dd5c34f512f82e5111d657"),
            LocalModelFile("$REL/parakeet-tdt-110m-en-decoder.int8.onnx", LocalTranscriptionProvider.DECODER, 3_955_863, "f7c331c5504c2e593c76ed22b728e3f554af6c4a383dde862e719ced08b1da19"),
            LocalModelFile("$REL/parakeet-tdt-110m-en-joiner.int8.onnx", LocalTranscriptionProvider.JOINER, 1_411_403, "bf7dff69e9f2cdbe9943d70da358f38b361c115ba0105bae7e908e0d6ec782f6"),
            LocalModelFile("$REL/parakeet-tdt-110m-en-tokens.txt", LocalTranscriptionProvider.TOKENS, 9_953, "450e56bd2f036fe5b6aa821865838cc5aa9d8b0106134ce9a9ba0664abe6cd10"),
            VAD_FILE,
        ),
    )

    /**
     * ~137 MB. NVIDIA FastConformer German (issue #406) — German with punctuation at a fifth of
     * [PARAKEET_PRIMELINE_DE]'s 670 MB, which is what makes it the one to offer a German phone first.
     * The `_pc` in the upstream name is the point: it transcribes "in upper and lower case German
     * alphabet along with spaces, periods, commas, and question marks" — those four marks and no others.
     *
     * A hybrid Transducer/CTC model of which sherpa-onnx publishes *both* branches, and only the
     * transducer's asset name says so (`…-nemo-transducer-stt_de_…`); the plain `…-nemo-stt_de_…` is the
     * CTC one and ships a single `model.onnx` that would not fit this kind at all.
     *
     * Its vocabulary spells `▁,` and `▁.` — a space and then the mark — as tokens 1 and 2, so it really
     * does predict `Ende , nur`. That space is taken back out on the way to the text field rather than
     * here; see [dev.patrickgold.florisboard.dictate.TranscriptJoin.tighten].
     *
     * Licensing: weights CC-BY-4.0 (NVIDIA) — note that the same NeMo family also contains CC-BY-NC-4.0
     * models, so the licence is read per model; sherpa-onnx ONNX export Apache-2.0.
     */
    val FASTCONFORMER_DE = LocalModelSpec(
        id = "fastconformer-de",
        displayName = "FastConformer German",
        // Not stated on its card, but it is the same FastConformer encoder with full attention as
        // the Parakeets, which do document minutes. Inference from the architecture rather than a
        // quoted number — if a long German dictation ever misbehaves, this is the line to suspect.
        maxSegmentSeconds = 120,
        languages = listOf("de"),
        punctuates = true,
        credit = Credits.nvidia("https://huggingface.co/nvidia/stt_de_fastconformer_hybrid_large_pc"),
        kind = LocalModelKind.NEMO_TRANSDUCER,
        files = listOf(
            LocalModelFile("$REL/fastconformer-de-encoder.int8.onnx", LocalTranscriptionProvider.ENCODER, 131_114_014, "10fbb959c36c461afb02ea87c109119eb001f30e91a130663bd6d5bbcba74ba9"),
            LocalModelFile("$REL/fastconformer-de-decoder.int8.onnx", LocalTranscriptionProvider.DECODER, 3_955_863, "4633a4b0a3f21f7ace4df02e772f5b5e63d45f84a71ee1e2660998fc4beb46ad"),
            LocalModelFile("$REL/fastconformer-de-joiner.int8.onnx", LocalTranscriptionProvider.JOINER, 1_408_183, "bac3af36a9bd66bfcad2ae6a35f17c7853e034e497134a8b0cbe48a8e954bd9f"),
            LocalModelFile("$REL/fastconformer-de-tokens.txt", LocalTranscriptionProvider.TOKENS, 10_686, "abb1136142604d6d1766ad5060bd4f4b1048d7a096cd094b2d40eec3e666be9f"),
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
        languages = listOf("de"),
        punctuates = true,
        credit = Credits.PRIMELINE,
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
        // NVIDIA: "designed to handle input audio smaller than 40 seconds". Under it with room.
        maxSegmentSeconds = 35,
        languages = listOf("en", "de", "fr", "es"),
        // Asked for explicitly by `usePnc = true` where the recognizer is built.
        punctuates = true,
        credit = Credits.nvidia("https://huggingface.co/nvidia/canary-180m-flash"),
        kind = LocalModelKind.CANARY,
        files = listOf(
            LocalModelFile("$REL/canary-encoder.int8.onnx", LocalTranscriptionProvider.ENCODER, 132_678_643, "7a75b4e2a5857a6dcc0819503bbe3fad66943db4a3ccf21d3f27c633667d303f"),
            LocalModelFile("$REL/canary-decoder.int8.onnx", LocalTranscriptionProvider.DECODER, 74_437_848, "e41a2ab9c0c2fe81a1e8ade5a45fb02a74bc4db7d1f91b89a54a25e2cf79cba2"),
            LocalModelFile("$REL/canary-tokens.txt", LocalTranscriptionProvider.TOKENS, 53_555, "2dae6fc7815f9640645e0c765522b278ee0cef49b482d91f6913e334628d3e77"),
            VAD_FILE,
        ),
    )

    /**
     * ~105 MB. Dolphin base (issue #406) — the languages this catalog had no answer for: Hindi, Arabic,
     * Persian, Thai, Vietnamese, Indonesian, Bengali, Tamil, Urdu, Burmese, Khmer, Lao and the rest of
     * DataoceanAI and Tsinghua's 40 Eastern languages, trained on 210 000 h, for less than a Whisper Base.
     *
     * The gap was not theoretical. Handed natural Hindi, the Whisper Base most people install answers in
     * **Urdu script** and stops halfway; Dolphin returns correct Devanagari with the question mark in
     * place. It punctuates in Arabic and CJK too.
     *
     * **English is deliberately absent from [languages], and that is not an oversight.** `<en>` exists
     * in the export's vocabulary but not in Dolphin's documented list, and on real English speech it
     * answers in Urdu script — so this is a specialist like [FASTCONFORMER_DE] or [GIGAAM_V3_RU], just
     * one with thirty-nine languages instead of one. Naming them is what keeps someone from picking it
     * for a language it was never trained on. `ct` in Dolphin's own table is Yue Chinese, carried here
     * under the ISO code `yue` that the rest of the catalog already uses.
     *
     * Its config has only a model path — no language field, not even "auto" — so unlike Canary it can
     * neither be told a language nor be told wrong. That is also why it fits [LocalModelKind.DOLPHIN]
     * rather than needing anything from the input-language setting.
     *
     * Licensing: Apache-2.0, stated in the export's own README rather than only on a web page; the
     * sherpa-onnx export is Apache-2.0 as well. Only the CTC branch is exported.
     */
    val DOLPHIN_BASE = LocalModelSpec(
        id = "dolphin-base",
        displayName = "Dolphin Base",
        // Left at the conservative default: Dolphin states no limit, and sherpa-onnx does not crop
        // it either, so there is nothing to raise this to that would not be a guess.
        maxSegmentSeconds = 28,
        languages = listOf(
            "ar", "az", "ba", "bn", "fa", "fil", "gu", "hi", "id", "ja", "jv", "kab", "kk", "km", "ko",
            "ks", "ky", "lo", "mn", "mr", "ms", "my", "ne", "or", "pa", "ps", "ru", "si", "su", "ta",
            "te", "tg", "th", "tl", "ug", "ur", "uz", "vi", "yue", "zh",
        ),
        punctuates = true,
        credit = ModelCredit(
            "DataoceanAI and Tsinghua University", "Apache-2.0",
            "https://github.com/DataoceanAI/Dolphin",
        ),
        kind = LocalModelKind.DOLPHIN,
        files = listOf(
            LocalModelFile("$REL/dolphin-base-model.int8.onnx", LocalTranscriptionProvider.MODEL, 103_729_802, "a3aa46c97f3f60f135ff949793cb05fabe7a0b3c484dc2e3cc699d354ee11b76"),
            LocalModelFile("$REL/dolphin-base-tokens.txt", LocalTranscriptionProvider.TOKENS, 504_662, "c3788261a51df1899ea4b210b552cd42139204de72c0ad60f6cebb199078872e"),
            VAD_FILE,
        ),
    )

    /**
     * ~232 MB. GigaAM v3 Russian (issue #406) — [GIGAAM_V2_RU]'s successor: slightly smaller, and the
     * first Russian in this catalog that writes punctuation and normalises numbers (the upstream
     * `v3_e2e_rnnt` line; the plain v3 without `punct` in its name does neither).
     *
     * It stands **beside** v2 rather than replacing it. `LocalModelManager.isInstalled` only checks that
     * the files exist, so a model that changed under an id already on disk would never be re-downloaded
     * (#176) — and an id dropped from [all] stops being seen by `installedIds` while its bytes stay,
     * which would leave anyone who had v2 with an orphaned 241 MB and a recognizer built from the wrong
     * [LocalModelKind].
     *
     * Not a stock NeMo model: its metadata carries `is_giga_am=1`, `subsampling_factor=4` and an empty
     * `normalize_type`, and sherpa-onnx reads that flag to override the feature dimension to 64 behind
     * our `featureDim = 80`. Verified by decoding against the vendored 1.13.3 — which is also the reason
     * nobody should make the feature config model-dependent.
     *
     * Licensing: MIT (GigaChat Team) — the licence file travels inside the export itself, not just on
     * the repo page; the ONNX export is sherpa-onnx's (Apache-2.0). Its decoder and joiner are fp32,
     * like v2's, so only the encoder carries an `.int8.` name.
     */
    val GIGAAM_V3_RU = LocalModelSpec(
        id = "gigaam-v3-ru",
        displayName = "GigaAM v3 Russian",
        // GigaAM's own README: transcription "is applicable for audio only up to 25 seconds";
        // anything longer wants their external-VAD long-form path. The app was feeding it 29.
        maxSegmentSeconds = 23,
        languages = listOf("ru"),
        punctuates = true,
        credit = Credits.GIGAAM,
        kind = LocalModelKind.NEMO_TRANSDUCER,
        files = listOf(
            LocalModelFile("$REL/gigaam-v3-ru-encoder.int8.onnx", LocalTranscriptionProvider.ENCODER, 224_570_820, "369f35a71bf288d3b8e0391fabd8dba5f2314088d440bca474056b7b4b6e66bf"),
            LocalModelFile("$REL/gigaam-v3-ru-decoder.onnx", LocalTranscriptionProvider.DECODER, 4_600_132, "38fc7475443ea2a26f63211ca350f73ac50fff824ab7a3876ee2bd610c53bbc4"),
            LocalModelFile("$REL/gigaam-v3-ru-joiner.onnx", LocalTranscriptionProvider.JOINER, 2_712_896, "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c"),
            LocalModelFile("$REL/gigaam-v3-ru-tokens.txt", LocalTranscriptionProvider.TOKENS, 13_354, "39abae20e692998290c574e606f11a9edef2902a1995463fcff63d1490cf22b7"),
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
        // Same 25 s ceiling as v3.
        maxSegmentSeconds = 23,
        languages = listOf("ru"),
        // Its whole vocabulary is 196 bytes of Cyrillic letters with not one mark in it, which is the
        // difference [GIGAAM_V3_RU] was added for — and the reason nobody new is offered this one.
        punctuates = false,
        supersededBy = "gigaam-v3-ru",
        credit = Credits.GIGAAM,
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
        encoderBytes: Long,
        encoderSha: String,
        decoderBytes: Long,
        decoderSha: String,
        joinerSha: String,
        tokensBytes: Long,
        tokensSha: String,
    ): LocalModelSpec {
        return LocalModelSpec(
            id = "kroko-$lang",
            displayName = displayName,
            languages = listOf(lang),
            // `,` `.` `?` `!` are tokens 6, 7, 134 and 312 of its 652-entry vocabulary — and releasing a
            // sentence-final mark a segment late is exactly what #356 was about.
            punctuates = true,
            credit = Credits.KROKO,
            family = LocalModelFamily.KROKO,
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
        "de", "Kroko German",
        70_091_557, "6e83993d6967ec7a3498b055b7e85ace85b5d64d1b1e8773cb29a43a11f5edb5",
        617_489, "94a29592b403c53fa2231b478637da1ab4abcef7f5e46e432098416a4a3ed562",
        "28356bff070aea51ab1d725a3278e81d19f9300f860d3248a7014292264df15a",
        5_606, "86e8370994ff2c01149ba8c4f8709aa93cdc18914b27a717e291e96faf39a6eb",
    )

    /** ~71 MB. English live model. */
    val KROKO_EN = kroko(
        "en", "Kroko English",
        70_092_599, "d4881c57449d581e0770fd53fa66c2fdc6cd167d92ece7c715e603defc96d9d4",
        617_488, "455ba38466fce8d5a57e7db68a323b684079ca4d9e1dd93a740d9b2429aae3b1",
        "d406f616736350e2a7df3e39398b78eb2fc1a2ca6973a19d3853fa3227e25b52",
        6_310, "396dbeb5f4858875690716084f54e90d339679d0ba3e6b5b584f3d7589254d2d",
    )

    /** ~71 MB. French live model. */
    val KROKO_FR = kroko(
        "fr", "Kroko French",
        70_092_599, "e02facae1daf6f1f13da67ea3ace7c722516d0868d1768d78c0580bc22cc0c5b",
        617_488, "6aed547570e3ab5afc05429a017cedd3a056c16df3baa5703f02461cefa25bac",
        "a51eec759bcdcaae2614686fa2a8b57417b2d420dd55a5a5558b388d35a9b2b6",
        5_415, "fedfb9c844bfb2bf14171f8184863e3d617b815a8667bdd9fc9a3149fde73298",
    )

    /** ~156 MB. Spanish live model — upstream only publishes the larger encoder for Spanish. */
    val KROKO_ES = kroko(
        "es", "Kroko Spanish",
        154_878_102, "2d9f5ef87d1a5257f8a6687e21501c56f3aa2fcbfcfab9364dcc4ce4e06ae81b",
        617_488, "d4ce176b94b25f7acc88717bc3f704fcf5d6e131aaac2e0cabab3885541181ee",
        "dae35df88d676e320fcdb99217328e66dcf722bf11b0f2459e14ddb5b982ded5",
        6_385, "1be5e0a58e05d06d327df4c6b7b5e4f8aba01da6981eb016fcaceafc6a56680f",
    )

    /** ~156 MB. Italian live model. */
    val KROKO_IT = kroko(
        "it", "Kroko Italian",
        154_878_660, "81c436e4f1cc381276859c858e3e881e382d0e0ca77a21bea1fde74c1275f6b2",
        617_488, "f9c8093a12cb93b14e82f9205f1c4f57cb19143e0cca0079c6770c717611961c",
        "3056ae55986ba4fb6203599baaeebb5f7eeb776798c3146df3bf76a198d172a9",
        6_107, "6c1ce19563e9fa59cc05ad921ccc31106497c1e2895346e2aa3fa936a103ed39",
    )

    /** ~156 MB. Dutch live model. */
    val KROKO_NL = kroko(
        "nl", "Kroko Dutch",
        154_878_660, "200616faee86985fee53f16073f8aa2b745988ef7a1dc7825271c464193d0266",
        617_488, "e5f8003008d4f00b52f0f16fb76544218957115e2b12a6397a89ec6bfe0e21f9",
        "4813be19995e1188b4b144e69ecb23d2e26e47f7d21b263443e647d8d7edc156",
        6_241, "157f0d8363aa1d179eebbe5948db07d19f56711328ba0561376e87d5cb68ee9e",
    )

    /** ~156 MB. Portuguese live model. */
    val KROKO_PT = kroko(
        "pt", "Kroko Portuguese",
        154_878_660, "336b9a62fd37d8b94855fcbe0414000aa5f1bd75d4cb907e112bd6b7ef97c52e",
        617_488, "2380832dbb1867779a550aea3948776d6a53ffa1cccd075bb7592ebaf21b7638",
        "de7afbc23e7e55af7fed85780690b8f883c62b881fe14d546d9677151581962f",
        6_235, "e9b9b588c138558388c9a53385007082f58130a0ceddce8df6a4aed032162b3f",
    )

    /** ~156 MB. Swedish live model. */
    val KROKO_SV = kroko(
        "sv", "Kroko Swedish",
        154_877_618, "60c367201c16f6a8f3fbd7edcf86c2bf59e71455a841fdaacbaf5ea6767273b0",
        617_488, "3424e0908f578d0fd6a1911e73e0d6fc4ef430b8892389d1c49768b5ee75ead1",
        "194e38c970ca06743439b101b7dcb4b45b4e215d7b6dbc9419f4a1c557286413",
        5_706, "d6b161d3547eaae1927ddba4af83c117f27ae3efff685850c0c50b538b5a5781",
    )

    /** ~156 MB. Turkish live model. */
    val KROKO_TR = kroko(
        "tr", "Kroko Turkish",
        154_878_660, "d36d8abbcbd9d87c5446f296b59a9fce26ccd87c7edb278f61631ef3d02803a2",
        617_489, "08f317129a6ffed14f8755e61d50b1df6ac1cc5af3bdd832b7ea93961199217e",
        "aa49f0e96e4ef5ea408cb09f4b2ef5785995513b21fd8f04675d2f5f0ffcd1f3",
        5_423, "c7e93bbc0f57852154df4e52005ae163d653f3daf0d1dbeb4f75a3ffa4b25c57",
    )

    /** ~156 MB. Hebrew live model. */
    val KROKO_HE = kroko(
        "he", "Kroko Hebrew",
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
        // Upstream claims "input of audio ... of any duration", but its own pipeline chunks at 30 s,
        // so this doubles what it used to get rather than trusting the unlimited claim.
        maxSegmentSeconds = 60,
        languages = listOf("zh", "yue", "en", "ja", "ko"),
        punctuates = true,
        credit = Credits.SENSE_VOICE,
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
        files = listOf(
            LocalModelFile(
                "$REL/smart-turn-v3.2-cpu.onnx", "smart-turn.onnx", 8_840_701,
                "7e7bfa1924cf89bd12ca9ba8f6d9165e3154884c377944911926ed9fda2f6bab",
            ),
        ),
    )

    /**
     * All catalog models in display order — **this list is the picker's order**, so it is arranged by
     * what most people will end up downloading rather than by architecture or by when a model was added.
     *
     * Broad coverage and a small download come first: the English Parakeet at 137 MB, then Canary with
     * the four biggest European languages at 208 MB, then Parakeet Ultra, which speaks twenty-five but
     * costs 670 MB — with the v3 it retired directly behind it, where only its existing owners see it. The language specialists follow, and the two 670 MB entries sink towards the bottom.
     *
     * **Whisper last**, before the live models. It is the one everybody recognises, which is exactly why
     * it should not be the first thing offered: for almost every language in this catalog there is now
     * something here that beats it at its size, and a name people already trust would otherwise collect
     * the downloads by default.
     *
     * Two ordering rules the code depends on: family members must sit next to each other, and the
     * streaming entries must stay a contiguous tail — [LocalModelSection] puts the "Live" heading in
     * front of the first of them. Both are covered by tests rather than by hope.
     */
    val all: List<LocalModelSpec> = listOf(
        PARAKEET_TDT_110M_EN,
        CANARY_180M_FLASH,
        PARAKEET_ULTRA,
        PARAKEET_TDT_V3,
        FASTCONFORMER_DE,
        SENSE_VOICE_SMALL,
        DOLPHIN_BASE,
        GIGAAM_V3_RU,
        GIGAAM_V2_RU,
        PARAKEET_PRIMELINE_DE,
        WHISPER_TINY, WHISPER_BASE, WHISPER_SMALL,
        WHISPER_TINY_EN, WHISPER_BASE_EN, WHISPER_SMALL_EN,
        KROKO_EN, KROKO_DE, KROKO_ES, KROKO_FR,
        KROKO_IT, KROKO_NL, KROKO_PT, KROKO_SV, KROKO_TR, KROKO_HE,
    )

    /**
     * The two models the setup wizard offers for [language] (issue #273): the one that fits, and the
     * bigger one for anyone willing to trade storage for accuracy. Everything else stays one tap away
     * behind "show all models" — a first-run screen that lists two dozen downloads is not a choice, it
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
            // v3 rather than v2: smaller, and the only Russian here that writes punctuation. v2 stays in
            // the catalog for everyone who already has it, but there is no reason to hand it to anyone new.
            "ru" -> listOf(GIGAAM_V3_RU, WHISPER_SMALL)
            // Until #406 the German offer was Whisper Base or 670 MB, and the specialized model was the
            // expensive one. FastConformer is specialized *and* the cheaper of the two, so the shape of
            // this pair finally matches every other language: the one that fits, then the bigger one.
            "de" -> listOf(FASTCONFORMER_DE, PARAKEET_PRIMELINE_DE)
            "en" -> listOf(PARAKEET_TDT_110M_EN, WHISPER_SMALL_EN)
            else -> listOf(WHISPER_BASE, WHISPER_SMALL)
        }

    /**
     * [all] folded into the rows the picker's first level shows: a family appears once, in the place of
     * its first member, and everything else stands for itself. Twenty-six entries become twelve rows,
     * of which [visibleTopLevel] shows ten to anyone new: eight models that are their own choice, plus
     * Whisper and Kroko — the two retired entries are seen only by the people who have them.
     *
     * Order is [all]'s, which is what keeps the streaming rows a contiguous tail and the "Live" heading
     * where it belongs. Relies on a family's members sitting next to each other — asserted by a test,
     * because getting it wrong here would scatter a family across the list rather than fail loudly.
     */
    val topLevel: List<LocalModelEntry> by lazy {
        val out = mutableListOf<LocalModelEntry>()
        val seen = mutableSetOf<LocalModelFamily>()
        for (spec in all) {
            val family = spec.family
            if (family == null) {
                out += LocalModelEntry.Single(spec)
            } else if (seen.add(family)) {
                out += LocalModelEntry.Family(family, all.filter { it.family == family })
            }
        }
        out
    }

    /**
     * [topLevel] without the models a newer one has replaced — unless they are still on disk, because
     * a model somebody is using cannot be hidden from them: they would have no way left to switch away
     * from it or to get its bytes back.
     *
     * This is how a model is retired. Dropping it from [all] instead would make
     * `LocalModelManager.installedIds` blind to it while its directory stays, and [kindOf] would build
     * the wrong recognizer for anyone whose pick still names it.
     */
    fun visibleTopLevel(installed: Set<String>): List<LocalModelEntry> = topLevel.filter { entry ->
        when (entry) {
            is LocalModelEntry.Single ->
                entry.spec.supersededBy == null || entry.spec.id in installed
            // No family has a superseded member today; when one does, the family row stays and the
            // variant is filtered inside its own dialog instead.
            is LocalModelEntry.Family -> true
        }
    }

    /** A family's variants, minus the retired ones nobody has installed. See [visibleTopLevel]. */
    fun visibleMembers(family: LocalModelEntry.Family, installed: Set<String>): List<LocalModelSpec> =
        family.members.filter { it.supersededBy == null || it.id in installed }

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
