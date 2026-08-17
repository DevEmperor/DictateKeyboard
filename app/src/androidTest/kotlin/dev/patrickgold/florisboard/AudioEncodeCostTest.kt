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
import dev.patrickgold.florisboard.dictate.audio.AudioDecode
import dev.patrickgold.florisboard.dictate.audio.AudioEncode
import dev.patrickgold.florisboard.dictate.audio.AudioWav
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * What packing the upload actually costs in time (issue #281) — a measurement, not an assertion.
 *
 * The trade is encode time against upload time, and the decision between "always pack" and "pack only
 * above N seconds" turns on how much of the cost is fixed (MediaCodec start-up, paid by every
 * dictation however short) and how much is per second. One short sample cannot tell those apart, so
 * this encodes several lengths and prints the split.
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.patrickgold.florisboard.AudioEncodeCostTest
 *   adb logcat -d -s AudioEncodeCost
 */
@RunWith(AndroidJUnit4::class)
class AudioEncodeCostTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val rate = AudioDecode.TARGET_SAMPLE_RATE

    private fun writeTone(seconds: Int): File {
        val count = rate * seconds
        val file = File(context.cacheDir, "cost_$seconds.wav")
        file.outputStream().buffered().use { out ->
            out.write(AudioWav.header(rate, channels = 1, bitsPerSample = 16, dataLen = count.toLong() * 2))
            for (i in 0 until count) {
                val v = (sin(2.0 * PI * 220.0 * i / rate) * 12000).toInt()
                out.write(v and 0xff)
                out.write((v shr 8) and 0xff)
            }
        }
        return file
    }

    @Test
    fun measure(): Unit = runBlocking {
        Log.i(TAG, "seconds | wav kB | m4a kB | encode ms")
        for (seconds in listOf(5, 5, 15, 60, 180)) {
            val wav = writeTone(seconds)
            val out = File(context.cacheDir, "cost_$seconds.m4a")
            val started = System.nanoTime()
            val packed = AudioEncode.toM4a(wav, out)
            val ms = (System.nanoTime() - started) / 1_000_000
            Log.i(
                TAG,
                "%7d | %6d | %6d | %9d".format(
                    seconds, wav.length() / 1024, (packed?.length() ?: 0) / 1024, ms,
                ),
            )
            wav.delete()
            packed?.delete()
        }
    }

    private companion object {
        const val TAG = "AudioEncodeCost"
    }
}
