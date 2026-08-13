/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Concatenates several PCM **WAV** segments (as produced by [RecordingController]) into a single WAV
 * file by joining their raw PCM data under one fresh header — no re-encoding. Used to stitch a recording
 * that was interrupted by the keyboard closing together with the segment recorded after the user chose to
 * *continue* it (see [DictateController]): the two finalized segments are merged here before
 * transcription, yielding one coherent audio (and thus one transcription).
 *
 * All segments are expected to share the same format (they are all produced by [RecordingController] with
 * identical 16 kHz mono PCM16 settings), so the `fmt ` chunk of the first usable segment defines the
 * output. (Previously the segments were AAC/m4a and were remuxed via MediaMuxer; the switch to WAV for
 * issue #130 makes a plain PCM splice both correct and far simpler.)
 */
object AudioConcat {

    private class WavFmt(val sampleRate: Int, val channels: Int, val bitsPerSample: Int)

    /**
     * Merges [segments] (in order) into [output], returning true on success. On any failure the partial
     * [output] is removed and false is returned, so the caller can fall back to a single segment.
     */
    fun concat(segments: List<File>, output: File): Boolean {
        val usable = segments.filter { it.exists() && it.length() > AudioWav.HEADER_SIZE }
        if (usable.isEmpty()) return false
        output.delete()
        var fmt: WavFmt? = null
        var dataBytes = 0L
        try {
            RandomAccessFile(output, "rw").use { out ->
                out.setLength(0)
                out.write(ByteArray(AudioWav.HEADER_SIZE)) // placeholder; patched once totals are known
                for (segment in usable) {
                    val parsed = RandomAccessFile(segment, "r").use { input ->
                        parseWav(input)?.also {
                            if (fmt == null) fmt = it.fmt
                            input.seek(it.dataOffset)
                            dataBytes += copy(input, out, it.dataLength)
                        }
                    } ?: continue
                    if (fmt == null) fmt = parsed.fmt
                }
                val format = fmt
                if (format == null || dataBytes <= 0L) {
                    output.delete()
                    return false
                }
                out.seek(0)
                out.write(
                    AudioWav.header(format.sampleRate, format.channels, format.bitsPerSample, dataBytes),
                )
            }
        } catch (_: Throwable) {
            output.delete()
            return false
        }
        return output.exists() && output.length() > AudioWav.HEADER_SIZE
    }

    private class ParsedWav(val fmt: WavFmt, val dataOffset: Long, val dataLength: Long)

    /** Parses a PCM WAV's `fmt `/`data` chunks, or returns null if [input] is not a usable WAV. */
    private fun parseWav(input: RandomAccessFile): ParsedWav? {
        if (input.length() < AudioWav.HEADER_SIZE) return null
        val header = ByteArray(12)
        input.readFully(header)
        if (!header.hasTag(0, "RIFF") || !header.hasTag(8, "WAVE")) return null

        var fmt: WavFmt? = null
        val chunkHeader = ByteArray(8)
        while (input.filePointer + chunkHeader.size <= input.length()) {
            input.readFully(chunkHeader)
            val size = chunkHeader.le32(4).toLong() and 0xffff_ffffL
            val body = input.filePointer
            when {
                chunkHeader.hasTag(0, "fmt ") -> {
                    if (size >= 16L && body + 16L <= input.length()) {
                        val bytes = ByteArray(16)
                        input.readFully(bytes)
                        fmt = WavFmt(
                            sampleRate = bytes.le32(4),
                            channels = bytes.le16(2).coerceAtLeast(1),
                            bitsPerSample = bytes.le16(14),
                        )
                    }
                }
                chunkHeader.hasTag(0, "data") -> {
                    val f = fmt ?: return null
                    val len = size.coerceAtMost(input.length() - body)
                    if (len <= 0L) return null
                    return ParsedWav(f, body, len)
                }
            }
            val next = (body + size + (size and 1L)).coerceAtMost(input.length())
            input.seek(next)
        }
        return null
    }

    private fun copy(input: RandomAccessFile, output: RandomAccessFile, length: Long): Long {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var remaining = length
        var written = 0L
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            output.write(buffer, 0, read)
            written += read
            remaining -= read
        }
        return written
    }

    private fun ByteArray.hasTag(offset: Int, tag: String): Boolean =
        this[offset] == tag[0].code.toByte() &&
            this[offset + 1] == tag[1].code.toByte() &&
            this[offset + 2] == tag[2].code.toByte() &&
            this[offset + 3] == tag[3].code.toByte()

    private fun ByteArray.le16(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.le32(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private const val COPY_BUFFER_SIZE = 64 * 1024
}
