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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * What a file *is*, against what it is *called* (issue #322).
 *
 * The whole point of [AudioContainer] is that the second one is not evidence, so the cases that matter
 * most here are the ones where the two disagree — a WhatsApp voice note named `.opus`, and the history's
 * own `<id>.wav` holding something else entirely.
 */
class AudioContainerTest : FunSpec({

    fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    fun ascii(text: String, pad: Int = 0) = text.toByteArray(Charsets.US_ASCII) + ByteArray(pad)

    /** A file on disk with [content] at the front and whatever [name] claims. */
    fun file(name: String, content: ByteArray): File =
        File.createTempFile("audiocontainer_", "_$name").apply {
            deleteOnExit()
            writeBytes(content)
        }

    test("the bytes decide, not the name") {
        // The reported case: WhatsApp writes Opus inside an Ogg stream and calls it .opus, which no
        // extension table knew. The first four bytes have always said so.
        val voiceNote = file("PTT-20260903.opus", ascii("OggS", pad = 12))
        AudioContainer.of(voiceNote) shouldBe AudioContainer.OGG
        audioMimeTypeOf(voiceNote) shouldBe "audio/ogg"
        audioFormatOf(voiceNote) shouldBe "ogg"
    }

    test("a lying name loses to the bytes") {
        // The history stored every retained recording as <id>.wav whatever was inside it, so this is
        // not a hypothetical: it is what re-transcribing an imported voice note used to send.
        val mislabelled = file("42.wav", ascii("OggS", pad = 12))
        AudioContainer.of(mislabelled) shouldBe AudioContainer.OGG

        val alsoMislabelled = file("42.wav", ascii("....ftypM4A ", pad = 4))
        AudioContainer.of(alsoMislabelled) shouldBe AudioContainer.M4A
    }

    test("every container this app produces or receives is recognised") {
        AudioContainer.ofHeader(ascii("RIFF····WAVE")) shouldBe AudioContainer.WAV
        AudioContainer.ofHeader(ascii("....ftypisom")) shouldBe AudioContainer.M4A
        AudioContainer.ofHeader(ascii("OggS", pad = 8)) shouldBe AudioContainer.OGG
        AudioContainer.ofHeader(ascii("fLaC", pad = 8)) shouldBe AudioContainer.FLAC
        AudioContainer.ofHeader(ascii("#!AMR", pad = 8)) shouldBe AudioContainer.AMR
        AudioContainer.ofHeader(ascii("ID3", pad = 8)) shouldBe AudioContainer.MP3
        AudioContainer.ofHeader(bytes(0x1A, 0x45, 0xDF, 0xA3, 0, 0, 0, 0)) shouldBe AudioContainer.WEBM
    }

    test("a bare MPEG frame is read down to its layer bits, so MP3 and AAC stay apart") {
        // Both start 0xFF Ex/Fx. Stopping at the sync word would call every raw AAC stream an MP3.
        AudioContainer.ofHeader(bytes(0xFF, 0xFB, 0x90, 0x00)) shouldBe AudioContainer.MP3
        AudioContainer.ofHeader(bytes(0xFF, 0xF1, 0x50, 0x80)) shouldBe AudioContainer.AAC
        AudioContainer.ofHeader(bytes(0xFF, 0xF9, 0x4C, 0x80)) shouldBe AudioContainer.AAC
    }

    test("nothing recognisable is not silently called something") {
        AudioContainer.ofHeader(ByteArray(0)) shouldBe null
        AudioContainer.ofHeader(bytes(0x00, 0x01, 0x02, 0x03)) shouldBe null
        AudioContainer.of(file("mystery.bin", bytes(0, 1, 2, 3, 4, 5, 6, 7))) shouldBe AudioContainer.UNKNOWN
    }

    test("the extension is the fallback, and it knows opus means ogg") {
        // Reached when the bytes say nothing: an empty file, or a container with no signature we read.
        AudioContainer.of(file("empty.opus", ByteArray(0))) shouldBe AudioContainer.OGG
        AudioContainer.of(file("empty.m4a", ByteArray(0))) shouldBe AudioContainer.M4A
        AudioContainer.of(file("empty.mpga", ByteArray(0))) shouldBe AudioContainer.MP3
        AudioContainer.of(file("empty.whatever", ByteArray(0))) shouldBe AudioContainer.UNKNOWN
    }

    test("an unknown container still travels under its own extension rather than under nothing") {
        // The old table passed unknown extensions through on purpose: refusing locally would deny what
        // the provider might well accept. That escape hatch survives the rewrite.
        val odd = file("recording.aiff", bytes(0, 1, 2, 3))
        audioFormatOf(odd) shouldBe "aiff"
        audioMimeTypeOf(odd) shouldBe "application/octet-stream"
    }

    test("an upload travels under the canonical name for what is inside it") {
        // Measured against OpenAI on 2026-09-04: the same Ogg bytes with the same audio/ogg part type
        // are refused as `PTT-….opus` ("Unsupported file format opus") and accepted as `voice.ogg`.
        // The endpoint reads the NAME, so renaming is the fix and transcoding was the expensive detour.
        val voiceNote = file("PTT-20260904-WA0001.opus", ascii("OggS", pad = 12))
        audioUploadNameOf(voiceNote).endsWith(".ogg") shouldBe true
        audioUploadNameOf(voiceNote).endsWith(".opus") shouldBe false

        // A name that already matches the contents is left exactly as it is.
        val recording = file("dictate_audio.wav", ascii("RIFF····WAVE"))
        audioUploadNameOf(recording) shouldBe recording.name

        // Nothing recognisable: no better guess exists, so the original name stands.
        val mystery = file("whatever.xyz", bytes(9, 9, 9, 9))
        audioUploadNameOf(mystery) shouldBe mystery.name
    }

    test("every container names itself consistently across the three fields") {
        // One table now answers all three questions; this is what "they can no longer disagree" means.
        for (container in AudioContainer.entries) {
            if (container == AudioContainer.UNKNOWN) continue
            container.mimeType.startsWith("audio/") shouldBe true
            container.formatName.isNotEmpty() shouldBe true
            AudioContainer.ofExtension(container.extension) shouldBe container
        }
    }
})
