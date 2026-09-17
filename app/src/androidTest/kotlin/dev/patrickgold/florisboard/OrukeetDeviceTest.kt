/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.patrickgold.florisboard

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.dictate.provider.LocalModelCatalog
import dev.patrickgold.florisboard.dictate.provider.LocalModelManager
import dev.patrickgold.florisboard.dictate.provider.LocalTranscriptionProvider
import dev.patrickgold.florisboard.dictate.provider.TranscriptionRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opt-in Android/JNI validation using the production downloader and transcription provider.
 *
 * `runOrukeetDownload=1` enables the ~672 MB verified Hugging Face installation. Then place mono
 * PCM WAV recordings named en.wav/de.wav/es.wav/fr.wav in the app's private files/orukeet-fixtures
 * directory and set `runOrukeetRecognition=1`. `requireOffline=1` additionally asserts that Android
 * reports no active network. Model weights and audio fixtures are never bundled with the tests.
 */
@RunWith(AndroidJUnit4::class)
class OrukeetDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val spec get() = LocalModelCatalog.ORUKEET

    @Test
    fun downloadsAndVerifiesPublishedModel() {
        assumeTrue("set runOrukeetDownload=1 to download Orukeet", args.getString("runOrukeetDownload") == "1")
        var lastPercent = -1
        runBlocking {
            LocalModelManager.download(context, spec) { done, total ->
                val percent = (done * 100 / total).toInt()
                if (percent / 10 != lastPercent / 10 || percent == 100 && lastPercent != 100) {
                    Log.i(TAG, "verified download progress $percent%")
                }
                lastPercent = percent
            }
        }
        assertTrue("verified model installation missing", LocalModelManager.isInstalled(context, spec.id))
        Log.i(TAG, "verified Hugging Face installation complete: ${spec.id}")
    }

    @Test
    fun transcribesRepeatedSpeechAndSilenceThroughAndroidProvider() {
        assumeTrue("set runOrukeetRecognition=1", args.getString("runOrukeetRecognition") == "1")
        assertTrue("install Orukeet first", LocalModelManager.isInstalled(context, spec.id))
        if (args.getString("requireOffline") == "1") {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            assertEquals("Android must have no active network", null, connectivity.activeNetwork)
        }
        val directory = File(context.filesDir, "orukeet-fixtures")
        val modelDirectory = LocalTranscriptionProvider.modelDir(context, spec.id)
        val provider = LocalTranscriptionProvider(modelDirectory)
        val expectedWords = linkedMapOf("en" to "country", "de" to "Wurst", "es" to "país", "fr" to "pays")
        fun transcribe(file: File): String = runBlocking {
            provider.transcribe(TranscriptionRequest(file, spec.id)).text
        }
        var englishTranscript = ""
        LocalTranscriptionProvider.unloadCachedModel()
        try {
            for ((language, expectedWord) in expectedWords) {
                val fixture = File(directory, "$language.wav")
                assertTrue("missing recording ${fixture.absolutePath}", fixture.isFile)
                val first = transcribe(fixture)
                if (language == "en") englishTranscript = first
                assertTrue("unexpected $language transcript: $first", first.contains(expectedWord, ignoreCase = true))
                assertEquals("repeat changed $language transcript", first, transcribe(fixture))
                Log.i(TAG, "$language repeated transcript: $first")
            }
            for (milliseconds in listOf(100, 1000, 5000)) {
                val silence = File(context.cacheDir, "orukeet-silence-$milliseconds.wav")
                try {
                    writeSilence(silence, milliseconds)
                    assertEquals("silence should be empty ($milliseconds ms)", "", transcribe(silence))
                    Log.i(TAG, "silence ${milliseconds}ms: empty")
                } finally {
                    silence.delete()
                }
            }
            // Rebuild the native recognizer from the installed cache, including after silence.
            LocalTranscriptionProvider.unloadCachedModel()
            val finalText = transcribe(File(directory, "en.wav"))
            assertEquals("fresh cache load changed the transcript", englishTranscript, finalText)
            Log.i(TAG, "fresh cached recognizer after silence: $finalText")
        } finally {
            LocalTranscriptionProvider.unloadCachedModel()
        }
    }

    private fun writeSilence(file: File, milliseconds: Int) {
        val byteCount = 16000 * milliseconds / 1000 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + byteCount).put("WAVEfmt ".toByteArray())
        header.putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(byteCount)
        file.outputStream().use { it.write(header.array()); it.write(ByteArray(byteCount)) }
    }

    companion object { private const val TAG = "OrukeetDeviceTest" }
}
