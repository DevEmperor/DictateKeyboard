/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.dictate.audio.AudioSpeedUp
import dev.patrickgold.florisboard.dictate.provider.LocalModelCatalog
import dev.patrickgold.florisboard.dictate.provider.LocalModelManager
import dev.patrickgold.florisboard.dictate.provider.LocalTranscriptionProvider
import dev.patrickgold.florisboard.dictate.provider.OpenAiCompatibleClient
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.dictate.provider.TranscriptionRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #272 — what the speed-up costs in recognition accuracy, measured instead of guessed.
 *
 * Takes the WAVs pushed to [IN_DIR], produces a copy at each rate with the shipping [AudioSpeedUp] code,
 * and transcribes every one of them with an on-device model. The transcripts are logged in a parseable
 * form; the word error rate against the reference text is computed off-device, together with the same
 * runs against a cloud provider (the generated WAVs stay in the app cache to be pulled for that).
 *
 * Not part of any automated suite: it needs pushed audio, downloads ~670 MB of model, and takes minutes.
 *
 *   adb shell mkdir -p /data/local/tmp/speedup
 *   adb push en.wav de.wav /data/local/tmp/speedup/
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.patrickgold.florisboard.SpeedUpAccuracyTest \
 *     -Pandroid.testInstrumentationRunnerArguments.speedUpModel=parakeet-tdt-0.6b-v3
 *
 * `speedUpModel=none` only writes the variants (for the cloud half of the measurement) and skips
 * everything on-device.
 */
@RunWith(AndroidJUnit4::class)
class SpeedUpAccuracyTest {

    @Test
    fun transcribesEveryRateOnDevice() {
        val args = InstrumentationRegistry.getArguments()
        val modelId = args.getString("speedUpModel")
        assumeTrue("set speedUpModel=<id|none> to run the #272 measurement", modelId != null)
        val inDir = File(IN_DIR)
        val sources = inDir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }?.sortedBy { it.name }
        assumeTrue("no WAVs pushed to $IN_DIR — skipping", !sources.isNullOrEmpty())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The app's external files dir rather than its cache: `adb shell` can read that one, so the same
        // variants can be pulled and sent to a cloud provider for the other half of the measurement.
        val outDir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "speedup-out")
            .apply { mkdirs() }

        // 1) Every rate, produced by the code that ships. 100 % is copied so the reference run goes
        //    through exactly the same file plumbing as the rest.
        val variants = mutableListOf<Pair<File, Int>>()
        for (source in sources!!) {
            for (percent in RATES) {
                val out = File(outDir, "${source.nameWithoutExtension}-$percent.wav")
                val produced = if (percent == 100) {
                    source.copyTo(out, overwrite = true)
                } else {
                    runBlocking { AudioSpeedUp.process(source, out, percent / 100f) }
                }
                if (produced == null) {
                    Log.w(TAG, "$LINE|skip|${source.name}|$percent|speed-up declined this clip")
                    continue
                }
                variants += produced to percent
                Log.i(TAG, "$LINE|file|${source.name}|$percent|${produced.absolutePath}|${produced.length()}")
            }
        }
        assert(variants.isNotEmpty()) { "no variants were produced" }

        // 2a) The cloud half, if a key was pushed next to the audio: the same variants through the same
        //     client the app uses, so what is measured is the request Dictate actually sends. The key is
        //     read from the device and never logged.
        val cloudModel = args.getString("speedUpCloudModel")
        val keyFile = File(IN_DIR, "groq-key.txt")
        if (cloudModel != null && keyFile.isFile) {
            val client = OpenAiCompatibleClient.from(ProviderRegistry.GROQ, keyFile.readText().trim())
            for ((file, percent) in variants) {
                // Groq's free tier allows 20 requests a minute, and a measurement that dies two thirds of
                // the way through is no measurement. One request every few seconds stays under it.
                Thread.sleep(CLOUD_REQUEST_SPACING_MS)
                val startedMs = System.currentTimeMillis()
                val text = runBlocking {
                    client.transcribe(
                        TranscriptionRequest(
                            audioFile = file,
                            model = cloudModel,
                            language = languageOf(file.name),
                        ),
                    ).text
                }
                Log.i(
                    TAG,
                    "$LINE|text|groq:$cloudModel|${file.name}|$percent|" +
                        "${System.currentTimeMillis() - startedMs}ms|${text.replace('\n', ' ').trim()}",
                )
            }
        }

        if (modelId == "none") {
            Log.i(TAG, "$LINE|done|variants written to ${outDir.absolutePath}")
            return
        }

        // 2) The same audio through an on-device model, once per variant.
        val spec = LocalModelCatalog.byId(modelId!!) ?: error("unknown model id: $modelId")
        if (!LocalModelManager.isInstalled(context, spec.id)) {
            var lastLogged = -1
            runBlocking {
                LocalModelManager.download(context, spec) { done, total ->
                    val percent = (done * 100 / total).toInt()
                    if (percent / 10 != lastLogged / 10) {
                        Log.i(TAG, "$LINE|download|${spec.id}|$percent%")
                        lastLogged = percent
                    }
                }
            }
        }
        val provider = LocalTranscriptionProvider(LocalTranscriptionProvider.modelDir(context, spec.id))
        for ((file, percent) in variants) {
            val startedMs = System.currentTimeMillis()
            val text = runBlocking {
                provider.transcribe(
                    TranscriptionRequest(audioFile = file, model = spec.id, language = languageOf(file.name)),
                ).text
            }
            Log.i(
                TAG,
                "$LINE|text|${spec.id}|${file.name}|$percent|${System.currentTimeMillis() - startedMs}ms|" +
                    text.replace('\n', ' ').trim(),
            )
        }
    }

    /**
     * The language a clip is in, taken from its file name — the measurement runs with the language set,
     * the way someone who dictates in one language has it set.
     */
    private fun languageOf(name: String): String = if (name.startsWith("minds-de-")) "de" else "en"

    private companion object {
        const val TAG = "SpeedUpAccuracy"

        /** Marks the machine-readable lines so they can be pulled straight out of logcat. */
        const val LINE = "SPEEDUP"
        const val IN_DIR = "/data/local/tmp/speedup"

        /** Slow enough for a 20-requests-per-minute free tier, with room for the request itself. */
        const val CLOUD_REQUEST_SPACING_MS = 3_500L
        val RATES = listOf(100, 125, 150, 175, 200)
    }
}
