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

import java.io.File
import java.io.InputStream

/**
 * What an audio file actually is, and the three names a provider might want it called (issue #322).
 *
 * Until this existed the app answered that question three times, from three tables, all keyed on the
 * **file name**: `guessAudioMediaType` for a `Content-Type`, `guessAudioFormat` for OpenRouter's and
 * the chat-audio `format` field, and a Gemini-specific one for its Interactions block. They disagreed —
 * `amr` was in one, `m4a` got three different answers, and `opus` was in none of them, which is the bug
 * that started this: a WhatsApp voice note went out as `application/octet-stream` and Gemini refused it.
 *
 * A file name is the worst possible source for this. It is written by whatever app shared the file, and
 * in one case it is written by **us** — the transcription history stored every retained recording as
 * `<id>.wav` no matter what was inside, so re-transcribing an imported MP3 told every provider it was
 * receiving a WAV. So the container is read from the **bytes** and the extension is only the fallback.
 *
 * Ogg is the answer for a `.opus` file, and deliberately so: WhatsApp and Telegram write Opus inside an
 * Ogg stream, so `OggS` is genuinely what is there. `audio/opus` exists and Gemini takes it, but it is
 * accepted in fewer places than `audio/ogg` and describes the same bytes.
 */
enum class AudioContainer(
    /** The `Content-Type` / `inline_data.mime_type` for this container. */
    val mimeType: String,
    /** The bare `format` string OpenRouter's JSON body and the chat-audio `input_audio` part take. */
    val formatName: String,
    /** The extension to use when *this app* names a file of this container itself. */
    val extension: String,
) {
    WAV("audio/wav", "wav", "wav"),
    MP3("audio/mpeg", "mp3", "mp3"),
    M4A("audio/mp4", "m4a", "m4a"),
    OGG("audio/ogg", "ogg", "ogg"),
    FLAC("audio/flac", "flac", "flac"),
    WEBM("audio/webm", "webm", "webm"),
    AAC("audio/aac", "aac", "aac"),
    AMR("audio/amr", "amr", "amr"),

    /** Nothing recognisable. Never treat this as "fine to send" — it is the absence of an answer. */
    UNKNOWN("application/octet-stream", "", "");

    companion object {

        /** Bytes read from the front of a file; every signature below fits comfortably inside this. */
        private const val HEADER_BYTES = 16

        /**
         * What the single-call multimodal route takes (issue #130): the `input_audio` part of
         * `chat/completions` is documented as `wav` or `mp3`, and nothing else — a narrower list than
         * any provider's own transcription endpoint, which is why packing to m4a is skipped for it.
         * Not a provider property, so it does not live on [ProviderPreset].
         */
        val CHAT_AUDIO_INPUT: Set<AudioContainer> = setOf(WAV, MP3)

        /**
         * What [file] is, read from its first bytes, falling back to its extension when the bytes say
         * nothing (an empty file, an unreadable one, or a container with no signature we know).
         */
        fun of(file: File): AudioContainer {
            val header = readHeader(file)
            return ofHeader(header) ?: ofExtension(file.extension)
        }

        /**
         * The container [header] identifies, or null. Pure arithmetic on the leading bytes — no file
         * system, no Android, so the whole signature table is unit-testable.
         */
        fun ofHeader(header: ByteArray): AudioContainer? = when {
            header.size < 4 -> null
            header.ascii(0, "RIFF") && header.ascii(8, "WAVE") -> WAV
            header.ascii(0, "OggS") -> OGG
            header.ascii(0, "fLaC") -> FLAC
            header.ascii(0, "#!AMR") -> AMR
            // The first box of an MP4/M4A is `ftyp` at offset 4, after its own length.
            header.ascii(4, "ftyp") -> M4A
            // EBML, the Matroska/WebM header.
            header.matches(0, 0x1A, 0x45, 0xDF, 0xA3) -> WEBM
            // An ID3 tag only ever precedes MPEG audio.
            header.ascii(0, "ID3") -> MP3
            else -> mpegFrame(header)
        }

        /**
         * The container an extension claims. Only a fallback — see the class comment for why a name is
         * not evidence. `opus` maps to [OGG] because that is what the bytes of such a file always are.
         */
        fun ofExtension(extension: String): AudioContainer = when (extension.lowercase()) {
            "wav", "wave" -> WAV
            "mp3", "mpeg", "mpga" -> MP3
            "m4a", "mp4", "m4b" -> M4A
            "ogg", "oga", "opus" -> OGG
            "flac" -> FLAC
            "webm" -> WEBM
            "aac" -> AAC
            "amr" -> AMR
            else -> UNKNOWN
        }

        /**
         * A bare MPEG audio frame — no ID3 tag in front, which is how a stripped MP3 and a raw AAC
         * stream both arrive. The sync word is the same for both; the two layer bits directly after it
         * are what separates them, so this must not stop at the sync word.
         */
        private fun mpegFrame(header: ByteArray): AudioContainer? {
            if (header.size < 2) return null
            val b0 = header[0].toInt() and 0xFF
            val b1 = header[1].toInt() and 0xFF
            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
            return when ((b1 shr 1) and 0x03) {
                0b01 -> MP3  // MPEG Layer III
                0b00 -> AAC  // "reserved" as a layer, which is what ADTS puts there
                else -> null // Layer I/II — not something this app has any business guessing at
            }
        }

        private fun readHeader(file: File): ByteArray = try {
            file.inputStream().use { readHeader(it) }
        } catch (_: Exception) {
            ByteArray(0)
        }

        /**
         * Fills the buffer rather than trusting one `read`, which is allowed to return fewer bytes
         * than asked for — the same lesson `MediaFormat.readHeader` records for image containers.
         */
        private fun readHeader(input: InputStream, count: Int = HEADER_BYTES): ByteArray {
            val buffer = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val read = input.read(buffer, filled, count - filled)
                if (read <= 0) break
                filled += read
            }
            return if (filled <= 0) ByteArray(0) else buffer.copyOf(filled)
        }

        private fun ByteArray.ascii(offset: Int, tag: String): Boolean {
            if (offset + tag.length > size) return false
            return tag.indices.all { this[offset + it].toInt() and 0xFF == tag[it].code }
        }

        private fun ByteArray.matches(offset: Int, vararg bytes: Int): Boolean {
            if (offset + bytes.size > size) return false
            return bytes.indices.all { this[offset + it].toInt() and 0xFF == bytes[it] }
        }
    }
}

/**
 * The `Content-Type` to send [file] as. Unknown stays `application/octet-stream`, which is what it has
 * always been — a provider refusing that is telling the truth about what it received.
 */
fun audioMimeTypeOf(file: File): String = AudioContainer.of(file).mimeType

/**
 * The bare `format` string for [file]. An unrecognised container falls back to the raw extension rather
 * than to nothing: passing a still-valid container through has a chance of working, and rejecting it
 * here would refuse locally what the provider might well have accepted.
 */
fun audioFormatOf(file: File): String =
    AudioContainer.of(file).formatName.ifEmpty { file.extension.lowercase() }

/**
 * The name to upload [file] under: its own stem, but the **canonical extension for what is inside it**.
 *
 * Measured against OpenAI on 2026-09-04, and it is the whole reason this function exists. The same Ogg
 * bytes, with the same `audio/ogg` part type, sent twice:
 *
 * ```
 * filename=PTT-20260904-WA0001.opus  ->  400  "Unsupported file format opus"
 * filename=voice.ogg                 ->  200  a correct transcript
 * ```
 *
 * So the endpoint reads the **file name**, not the part's content type, and it refuses `.opus` as a
 * *name* while accepting the container it holds. A WhatsApp voice note therefore needs renaming, not
 * re-encoding — which is free, where a transcode costs half a second and tripled the upload.
 *
 * An unrecognised container keeps its original name: there is no better guess to make, and the old
 * behaviour is the right fallback.
 */
fun audioUploadNameOf(file: File): String {
    val extension = AudioContainer.of(file).extension
    if (extension.isEmpty() || file.extension.equals(extension, ignoreCase = true)) return file.name
    return "${file.nameWithoutExtension}.$extension"
}
