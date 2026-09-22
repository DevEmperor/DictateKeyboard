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

import dev.patrickgold.florisboard.dictate.DictateLanguages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Invariants of the on-device model catalog.
 *
 * These matter because the catalog is now what decides whether a model counts as installed and which
 * recognizer gets built for it. A spec whose declared files do not match its kind does not fail here —
 * it fails natively, on a phone, after a several-hundred-megabyte download.
 */
class LocalModelCatalogTest {

    private val encoder = LocalTranscriptionProvider.ENCODER
    private val decoder = LocalTranscriptionProvider.DECODER
    private val joiner = LocalTranscriptionProvider.JOINER
    private val tokens = LocalTranscriptionProvider.TOKENS
    private val model = LocalTranscriptionProvider.MODEL
    private val vad = LocalTranscriptionProvider.VAD

    private fun names(spec: LocalModelSpec) = spec.files.map { it.destName }

    @Test
    fun `every model declares its tokens and nothing twice`() {
        for (spec in LocalModelCatalog.all + LocalModelCatalog.SMART_TURN) {
            val destNames = names(spec)
            assertEquals(
                destNames.distinct(), destNames,
                "${spec.id} declares the same destination file more than once",
            )
        }
        for (spec in LocalModelCatalog.all) {
            assertTrue(tokens in names(spec), "${spec.id} has no tokens file")
        }
    }

    @Test
    fun `ids are unique and usable as directory names`() {
        val ids = LocalModelCatalog.all.map { it.id }
        assertEquals(ids.distinct(), ids, "duplicate model id in the catalog")
        for (id in ids) {
            assertTrue(id.isNotBlank() && '/' !in id && '\\' !in id, "'$id' is not a usable directory name")
        }
    }

    @Test
    fun `the file shape matches the recognizer the model asks for`() {
        for (spec in LocalModelCatalog.all) {
            val files = names(spec)
            when (spec.kind) {
                LocalModelKind.WHISPER, LocalModelKind.CANARY -> {
                    assertTrue(encoder in files && decoder in files, "${spec.id} needs an encoder and a decoder")
                    assertTrue(model !in files, "${spec.id} is not a single-file model")
                }
                LocalModelKind.NEMO_TRANSDUCER -> {
                    assertTrue(
                        encoder in files && decoder in files && joiner in files,
                        "${spec.id} is a transducer and needs encoder, decoder and joiner",
                    )
                }
                // SenseVoice (#262) and Dolphin (#406): one file, no encoder/decoder pair at all.
                LocalModelKind.SENSE_VOICE, LocalModelKind.DOLPHIN -> {
                    assertTrue(model in files, "${spec.id} needs its single model file")
                    assertTrue(
                        encoder !in files && decoder !in files && joiner !in files,
                        "${spec.id} should not declare encoder/decoder/joiner",
                    )
                }
            }
        }
    }

    @Test
    fun `streaming models bring no VAD and offline ones do`() {
        for (spec in LocalModelCatalog.streaming) {
            // They detect speech pauses themselves (endpointing), so a VAD companion would be dead weight.
            assertTrue(vad !in names(spec), "${spec.id} streams and does not need the VAD")
            assertTrue(spec.kind == LocalModelKind.NEMO_TRANSDUCER, "${spec.id} streams but is not a transducer")
        }
        for (spec in LocalModelCatalog.batchOnly) {
            // Without it, anything past one model window is silently cut off.
            assertTrue(vad in names(spec), "${spec.id} transcribes in one shot and needs the VAD to segment")
        }
    }

    @Test
    fun `the chinese-capable model is offered and points at the original`() {
        val spec = assertNotNull(LocalModelCatalog.byId("sense-voice-small"), "SenseVoice is missing (#262)")
        assertTrue(spec in LocalModelCatalog.all, "SenseVoice is not offered in the picker")
        // The 2025-09-09 export under a near-identical name is a Cantonese fine-tune, not a newer
        // version — pinning the hash here is what keeps that mistake from creeping back in.
        val modelFile = spec.files.first { it.destName == model }
        assertTrue(
            modelFile.sha256 == "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
            "SenseVoice model file is not the 2024-07-17 FunAudioLLM export",
        )
    }

    /**
     * The wizard's shortlist (#273). What is being protected here is not the exact pair but the property
     * that makes the offer safe to accept blindly: during setup the input language is still on
     * auto-detect, so a model that has to be *told* its language would be handed the wrong one.
     */
    @Test
    fun `the onboarding shortlist only offers models that find the language themselves`() {
        val languages = listOf("de", "en", "ru", "zh", "yue", "ja", "ko", "fr", "th", "", "de-DE", "zh_CN")
        for (language in languages) {
            val picks = LocalModelCatalog.onboardingPicks(language)
            assertEquals(2, picks.size, "'$language' should be offered exactly two models")
            assertEquals(picks.distinct(), picks, "'$language' offers the same model twice")
            for (spec in picks) {
                assertTrue(spec in LocalModelCatalog.all, "${spec.id} is not in the catalog")
                assertTrue(
                    !spec.isStreaming,
                    "${spec.id} is a live model and only works with real-time transcription switched on",
                )
                // Canary is told its language and would default to English (LocalTranscriptionProvider).
                assertTrue(
                    spec.kind != LocalModelKind.CANARY,
                    "${spec.id} must be told its language, which setup has not asked for yet",
                )
                // The shortlist is the one place a retired model could still reach somebody brand new:
                // visibleTopLevel hides them from the picker, but this list bypasses it entirely.
                assertNull(
                    spec.supersededBy,
                    "${spec.id} was retired in favour of ${spec.supersededBy}, so setup must not offer it",
                )
            }
        }
    }

    @Test
    fun `the onboarding shortlist prefers the specialized model where one exists`() {
        assertEquals("sense-voice-small", LocalModelCatalog.onboardingPicks("zh").first().id)
        assertEquals("sense-voice-small", LocalModelCatalog.onboardingPicks("ja").first().id)
        // v3, not the v2 that is still in the catalog for the people who already installed it (#406).
        assertEquals("gigaam-v3-ru", LocalModelCatalog.onboardingPicks("ru").first().id)
        // German is offered its own specialized model first and the 670 MB one as the bigger alternative.
        assertEquals("fastconformer-de", LocalModelCatalog.onboardingPicks("de").first().id)
        // The bigger German offer is the multilingual Parakeet, not the German-only primeline, because
        // it reads German better at the same size and speaks 24 more languages (#414).
        assertEquals("parakeet-ultra", LocalModelCatalog.onboardingPicks("de")[1].id)
        assertEquals("parakeet-tdt-110m-en", LocalModelCatalog.onboardingPicks("en").first().id)
        assertEquals("whisper-base", LocalModelCatalog.onboardingPicks("fr").first().id)
        // A region or script suffix must not fall through to the default.
        assertEquals(
            LocalModelCatalog.onboardingPicks("zh"),
            LocalModelCatalog.onboardingPicks("zh-Hans-CN"),
        )
    }

    /**
     * The invariant the picker's "Live" heading has always relied on and nothing ever checked: it is
     * placed in front of the first streaming entry, so a non-streaming model appended after the Kroko
     * block would silently end up under it.
     */
    @Test
    fun `the streaming models are a contiguous tail of the catalog`() {
        val fromFirstStreaming = LocalModelCatalog.all.dropWhile { !it.isStreaming }
        assertTrue(
            fromFirstStreaming.all { it.isStreaming },
            "a one-shot model sits after the first streaming one: " +
                fromFirstStreaming.filter { !it.isStreaming }.joinToString { it.id },
        )
    }

    @Test
    fun `a family is contiguous, and is either all streaming or none of it`() {
        for (family in LocalModelFamily.entries) {
            val positions = LocalModelCatalog.all.withIndex()
                .filter { it.value.family == family }
                .map { it.index }
            assertTrue(positions.isNotEmpty(), "$family has no members")
            assertEquals(
                positions.last() - positions.first() + 1, positions.size,
                "$family's members are not next to each other in the catalog",
            )
            val members = positions.map { LocalModelCatalog.all[it] }
            assertEquals(
                1, members.map { it.isStreaming }.distinct().size,
                "$family mixes live and one-shot models, so one heading cannot describe it",
            )
        }
    }

    @Test
    fun `the top level covers every model exactly once, in catalog order`() {
        val flattened = LocalModelCatalog.topLevel.flatMap { entry ->
            when (entry) {
                is LocalModelEntry.Single -> listOf(entry.spec)
                is LocalModelEntry.Family -> entry.members
            }
        }
        assertEquals(LocalModelCatalog.all, flattened, "the picker would show a model twice or not at all")
        assertEquals(
            LocalModelCatalog.all.count { it.family == null } + LocalModelFamily.entries.size,
            LocalModelCatalog.topLevel.size,
            "a family is not being folded into exactly one row",
        )
    }

    /**
     * The catalog's order *is* the picker's order, so the decision about what people meet first lives
     * in a list literal and would otherwise be undone by the next person appending an entry.
     */
    @Test
    fun `the picker leads with the small broadly useful models and ends with Whisper`() {
        val rows = LocalModelCatalog.topLevel
        val first = assertNotNull(rows.first() as? LocalModelEntry.Single)
        assertEquals("parakeet-tdt-110m-en", first.spec.id, "the cheapest good English model is not first")

        val oneShot = rows.filter { !it.isStreaming }
        val last = assertNotNull(oneShot.last() as? LocalModelEntry.Family, "Whisper is not the last row")
        assertEquals(
            LocalModelFamily.WHISPER, last.family,
            "Whisper must stay at the bottom: it is the name people recognise, and for nearly every " +
                "language here something else now beats it at the same size",
        )
    }

    /**
     * A retired model is hidden from everyone who does not already have it, and shown to everyone who
     * does — hiding it from them would strand its bytes and leave them no way to switch away.
     */
    @Test
    fun `a superseded model is offered to nobody new and taken from nobody who has it`() {
        val superseded = LocalModelCatalog.all.filter { it.supersededBy != null }
        assertTrue(superseded.isNotEmpty(), "this test is about retiring models; none is retired")
        for (spec in superseded) {
            assertNotNull(
                LocalModelCatalog.byId(spec.supersededBy!!),
                "${spec.id} points at a replacement that is not in the catalog",
            )
            val withoutIt = LocalModelCatalog.visibleTopLevel(emptySet())
            assertTrue(
                withoutIt.none { it is LocalModelEntry.Single && it.spec.id == spec.id },
                "${spec.id} is still offered to someone who does not have it",
            )
            val withIt = LocalModelCatalog.visibleTopLevel(setOf(spec.id))
            assertTrue(
                withIt.any { it is LocalModelEntry.Single && it.spec.id == spec.id },
                "${spec.id} vanished for someone who has it installed, stranding its files",
            )
        }
        // Everything else is unaffected either way.
        assertEquals(
            LocalModelCatalog.topLevel.size - superseded.size,
            LocalModelCatalog.visibleTopLevel(emptySet()).size,
        )
    }

    /** The "Live" heading is placed in front of the first streaming *row*, so the tail has to hold here too. */
    @Test
    fun `the top level keeps the streaming rows as a contiguous tail`() {
        val fromFirstStreaming = LocalModelCatalog.topLevel.dropWhile { !it.isStreaming }
        assertTrue(fromFirstStreaming.all { it.isStreaming }, "a one-shot row sits under the Live heading")
    }

    @Test
    fun `every model says which languages it covers, in codes that resolve to a name`() {
        for (spec in LocalModelCatalog.all) {
            assertTrue(spec.languages.isNotEmpty(), "${spec.id} does not say what it transcribes")
            for (code in spec.languages) {
                assertEquals(code, code.substringBefore('-'), "${spec.id} carries a region in '$code'")
                val name = DictateLanguages.displayNameOf(code)
                assertTrue(
                    !name.equals(code, ignoreCase = true),
                    "${spec.id}: '$code' has no language name — a typo, or a code Android cannot place",
                )
                assertTrue(
                    name != DictateLanguages.of(DictateLanguages.DETECT).englishName,
                    "${spec.id}: '$code' resolved to the auto-detect entry",
                )
            }
        }
    }

    /**
     * Pinned against how the model actually behaves, not against its marketing. Canary is settled by
     * `usePnc = true` where the recognizer is built; the rest were read from a decode against the
     * vendored sherpa-onnx or from the marks present in the model's own `tokens.txt`.
     */
    @Test
    fun `the punctuation flag matches what the model really writes`() {
        assertTrue(LocalModelCatalog.CANARY_180M_FLASH.punctuates)
        assertTrue(LocalModelCatalog.PARAKEET_TDT_110M_EN.punctuates)
        assertTrue(LocalModelCatalog.FASTCONFORMER_DE.punctuates)
        assertTrue(LocalModelCatalog.GIGAAM_V3_RU.punctuates)
        assertTrue(LocalModelCatalog.KROKO_EN.punctuates)
        assertTrue(LocalModelCatalog.DOLPHIN_BASE.punctuates)
        // Dolphin's export carries an <en> token but Dolphin does not claim English, and on real English
        // speech it answers in Urdu script. Naming it here would send people to a model that cannot.
        assertTrue(
            "en" !in LocalModelCatalog.DOLPHIN_BASE.languages,
            "Dolphin must not be offered for English",
        )
        // The one model in the catalog that writes none — and the reason v3 was added beside it.
        assertTrue(!LocalModelCatalog.GIGAAM_V2_RU.punctuates)
    }

    /**
     * Each of these is a number somebody documented, and the cost of getting one wrong is silent: too
     * high and the model drops or garbles the tail, too low and every long dictation loses punctuation
     * at seams it never needed.
     */
    @Test
    fun `no model is handed more audio in one pass than it says it can take`() {
        for (spec in LocalModelCatalog.all) {
            assertTrue(spec.maxSegmentSeconds > 0, "${spec.id} would be cut into nothing")
        }
        // sherpa-onnx crops a Whisper decode at 30 s and logs that it discarded the rest.
        for (spec in LocalModelCatalog.all.filter { it.kind == LocalModelKind.WHISPER }) {
            assertTrue(spec.maxSegmentSeconds <= 28, "${spec.id} would run into Whisper's 30 s window")
        }
        // GigaAM: "applicable for audio only up to 25 seconds". The app used to feed it 29.
        assertTrue(LocalModelCatalog.GIGAAM_V3_RU.maxSegmentSeconds <= 23)
        assertTrue(LocalModelCatalog.GIGAAM_V2_RU.maxSegmentSeconds <= 23)
        // Canary: "designed to handle input audio smaller than 40 seconds".
        assertTrue(LocalModelCatalog.CANARY_180M_FLASH.maxSegmentSeconds <= 35)
        // And the other direction: the models that document minutes must not quietly fall back to
        // Whisper's ceiling, which is the whole point of the field.
        for (spec in listOf(
            LocalModelCatalog.PARAKEET_TDT_V3,
            LocalModelCatalog.PARAKEET_TDT_110M_EN,
            LocalModelCatalog.FASTCONFORMER_DE,
        )) {
            assertTrue(
                spec.maxSegmentSeconds >= 60,
                "${spec.id} handles minutes in one pass and is being cut at ${spec.maxSegmentSeconds} s",
            )
        }
    }

    @Test
    fun `splitting long audio is derived from the VAD file rather than declared`() {
        for (spec in LocalModelCatalog.all) {
            assertEquals(
                vad in names(spec), spec.splitsLongAudio,
                "${spec.id} disagrees with the file the provider actually branches on",
            )
        }
    }

    @Test
    fun `only Canary has to be told its language`() {
        val told = LocalModelCatalog.all.filter { !it.detectsLanguage }
        assertEquals(listOf(LocalModelCatalog.CANARY_180M_FLASH.id), told.map { it.id })
    }

    /**
     * The credits on the specs and the file the attributions screen renders are two copies of the same
     * obligation, so they are kept in step here rather than by remembering to edit both.
     */
    @Test
    fun `every credit also stands in the attributions the app shows`() {
        val attributions = java.io.File("src/main/assets/license/data_attributions.txt")
        assertTrue(attributions.isFile, "attributions file not found at ${attributions.absolutePath}")
        val text = attributions.readText()
        // Compared with punctuation and spacing removed, so "CC-BY-4.0" here matches "CC BY 4.0" there
        // and neither file has to adopt the other's house style. What must agree is the substance.
        fun flatten(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val flatText = flatten(text)
        for (spec in LocalModelCatalog.all) {
            val credit = assertNotNull(spec.credit, "${spec.id} names nobody")
            assertTrue(text.contains(credit.url), "${spec.id}: ${credit.url} is not in the attributions")
            assertTrue(
                flatText.contains(flatten(credit.author)),
                "${spec.id}: the attributions do not credit '${credit.author}'",
            )
            assertTrue(
                flatText.contains(flatten(credit.license)),
                "${spec.id}: the attributions do not state the licence '${credit.license}'",
            )
        }
    }

    @Test
    fun `every downloadable file is verifiable`() {
        for (spec in LocalModelCatalog.all + LocalModelCatalog.SMART_TURN) {
            for (file in spec.files) {
                assertTrue(file.sizeBytes > 0, "${spec.id}/${file.destName} has no size to check against")
                assertTrue(
                    file.sha256?.length == 64,
                    "${spec.id}/${file.destName} has no usable SHA-256",
                )
                assertTrue(file.url.startsWith("https://"), "${spec.id}/${file.destName} is not fetched over https")
            }
        }
    }
}
