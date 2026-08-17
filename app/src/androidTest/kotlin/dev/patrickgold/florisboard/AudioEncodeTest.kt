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

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.dictate.audio.AudioDecode
import dev.patrickgold.florisboard.dictate.audio.AudioEncode
import dev.patrickgold.florisboard.dictate.audio.AudioWav
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The AAC packing that keeps a long dictation under the provider's upload limit (issue #281).
 *
 * Has to run on a device: [AudioEncode] drives MediaCodec and MediaMuxer, neither of which exists on
 * the JVM. What is being checked is the property the upload path depends on — the result is smaller,
 * it is still the same audio, and *every* failure yields null rather than an exception, because the
 * caller's fallback is to upload the WAV unchanged.
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.patrickgold.florisboard.AudioEncodeTest
 */
@RunWith(AndroidJUnit4::class)
class AudioEncodeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val rate = AudioDecode.TARGET_SAMPLE_RATE

    /** A recording-shaped WAV: 16 kHz mono 16-bit, a 220 Hz tone so it is not silence. */
    private fun writeTone(seconds: Int, name: String): File {
        val count = rate * seconds
        val file = File(context.cacheDir, name)
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
    fun packsToASmallerFileThatIsStillTheSameAudio(): Unit = runBlocking {
        val wav = writeTone(seconds = 5, name = "enc_in.wav")
        val out = File(context.cacheDir, "enc_out.m4a")
        val packed = AudioEncode.toM4a(wav, out)
        assertNotNull("encoding returned null on a well-formed WAV", packed)
        packed!!

        // The whole point: 16 kHz mono WAV is 32 kB/s, AAC at 64 kbit/s is 8 kB/s.
        assertTrue(
            "packed ${packed.length()} is not smaller than ${wav.length()}",
            packed.length() < wav.length() / 2,
        )

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(packed.absolutePath)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
            assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            assertEquals(rate, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        } finally {
            extractor.release()
        }

        // Decoded back it must still be five seconds of audible tone, not silence or a fragment.
        val samples = AudioDecode.decodeToMono16k(packed)
        val seconds = samples.size.toDouble() / rate
        assertTrue("decoded $seconds s, expected about 5", abs(seconds - 5.0) < 0.5)
        val peak = samples.maxOf { abs(it) }
        assertTrue("decoded audio is silent (peak $peak)", peak > 0.05f)

        listOf(wav, packed).forEach { it.delete() }
    }

    @Test
    fun refusesRatherThanThrowsOnAnythingItCannotRead(): Unit = runBlocking {
        val junk = File(context.cacheDir, "enc_junk.wav").apply { writeBytes(ByteArray(2048) { 0x7f }) }
        assertNull(AudioEncode.toM4a(junk, File(context.cacheDir, "enc_junk.m4a")))

        val empty = File(context.cacheDir, "enc_empty.wav").apply { writeBytes(ByteArray(0)) }
        assertNull(AudioEncode.toM4a(empty, File(context.cacheDir, "enc_empty.m4a")))

        val missing = File(context.cacheDir, "enc_absent.wav").apply { delete() }
        assertNull(AudioEncode.toM4a(missing, File(context.cacheDir, "enc_absent.m4a")))

        listOf(junk, empty).forEach { it.delete() }
    }

    @Test
    fun leavesNoOutputBehindWhenItRefuses(): Unit = runBlocking {
        // The caller falls back to the WAV, so a half-written m4a lying in the cache would be a file
        // nobody deletes and nobody uses.
        val junk = File(context.cacheDir, "enc_leak.wav").apply { writeBytes(ByteArray(2048) { 0x7f }) }
        val out = File(context.cacheDir, "enc_leak.m4a")
        assertNull(AudioEncode.toM4a(junk, out))
        assertTrue("a rejected encode left ${out.name} behind", !out.exists())
        junk.delete()
    }
}
