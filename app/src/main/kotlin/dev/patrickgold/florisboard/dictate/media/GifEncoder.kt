/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.media

import java.io.ByteArrayOutputStream

/**
 * Writes an animated GIF (issue #280).
 *
 * Android can decode a GIF and cannot write one — `Bitmap.CompressFormat` has PNG, JPEG and WebP and
 * nothing else — so an app that will only animate GIFs is unreachable without this. Signal is such an
 * app: measured on the device it accepts `image/webp` and then flattens it to a single frame, and the
 * only moving format in the list it declares is `image/gif`.
 *
 * The format costs something real, and it is worth naming rather than discovering later: a GIF holds
 * at most 256 colours and its transparency is one bit, on or off. A sticker's soft outline therefore
 * comes out with a hard edge. That is the price of it moving at all in an app that will not animate
 * anything else, and it is why GIF is a fallback here and never the first choice.
 *
 * One palette is built for the whole animation rather than one per frame. It is smaller, it avoids
 * the colours shifting between frames, and — since the nearest-colour lookup is the expensive part —
 * it means that work is done once instead of once per frame.
 *
 * Deliberately free of Android types, like [WebPContainer], so the part that is easy to get wrong by
 * one byte can be tested on a plain JVM.
 */
object GifEncoder {

    /** One frame: `ARGB_8888` pixels, row by row, and how long it shows. */
    class Frame(val pixels: IntArray, val delayMs: Int)

    /** A pixel this transparent becomes the transparent index; anything above it becomes opaque. */
    private const val ALPHA_THRESHOLD = 128

    /** One index of the 256 is spent on transparency. */
    private const val MAX_COLORS = 255

    /** GIF counts delays in hundredths of a second, and treats 0 as "as fast as possible". */
    private const val MIN_DELAY_CS = 2

    fun encode(width: Int, height: Int, frames: List<Frame>, loopCount: Int = 0): ByteArray? {
        if (width <= 0 || height <= 0 || frames.isEmpty()) return null
        if (frames.any { it.pixels.size != width * height }) return null

        val palette = buildPalette(frames)
        val lookup = buildLookup(palette)
        val transparentIndex = palette.size
        val tableSize = tableSizeFor(palette.size + 1)

        val out = ByteArrayOutputStream(64 * 1024)
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeLe16(out, width)
        writeLe16(out, height)
        // Global colour table present, 8 bits of colour resolution, table size as a power of two.
        out.write(0x80 or 0x70 or (bitsFor(tableSize) - 1))
        out.write(0) // background colour index
        out.write(0) // pixel aspect ratio: none given
        for (index in 0 until tableSize) {
            val color = palette.getOrElse(index) { 0 }
            out.write((color shr 16) and 0xFF)
            out.write((color shr 8) and 0xFF)
            out.write(color and 0xFF)
        }
        writeLoopExtension(out, loopCount)

        for (frame in frames) {
            val indices = mapPixels(frame.pixels, lookup, transparentIndex)
            writeGraphicControl(out, frame.delayMs, transparentIndex)
            out.write(0x2C) // image separator
            writeLe16(out, 0)
            writeLe16(out, 0)
            writeLe16(out, width)
            writeLe16(out, height)
            out.write(0) // no local colour table, not interlaced
            writeLzw(out, indices, bitsFor(tableSize))
        }
        out.write(0x3B) // trailer
        return out.toByteArray()
    }

    /**
     * Chooses up to [MAX_COLORS] colours by median cut over the colours that actually occur.
     *
     * Colours are counted in 15 bits — five per channel — which is the resolution the lookup table
     * below works in anyway. Repeatedly the box holding the most pixels is split at its median along
     * its widest channel, so colours that cover a lot of the picture get subdivided and a stray
     * highlight does not claim a quarter of the palette.
     */
    private fun buildPalette(frames: List<Frame>): IntArray {
        val counts = IntArray(1 shl 15)
        for (frame in frames) {
            for (pixel in frame.pixels) {
                if ((pixel ushr 24) < ALPHA_THRESHOLD) continue
                counts[cellOf(pixel)]++
            }
        }
        val cells = (0 until counts.size).filter { counts[it] > 0 }.toIntArray()
        if (cells.isEmpty()) return intArrayOf(0)
        if (cells.size <= MAX_COLORS) return cells.map { colorOfCell(it) }.toIntArray()

        var boxes = listOf(Box(cells, 0, cells.size, counts))
        while (boxes.size < MAX_COLORS) {
            val target = boxes.filter { it.canSplit() }.maxByOrNull { it.population } ?: break
            val split = target.split() ?: break
            boxes = boxes - target + split.toList()
        }
        return boxes.map { it.averageColor() }.toIntArray()
    }

    /** One region of colour space, held as a slice of the shared cell array. */
    private class Box(
        val cells: IntArray,
        val from: Int,
        val until: Int,
        val counts: IntArray,
    ) {
        val population: Int = (from until until).sumOf { counts[cells[it]] }

        fun canSplit(): Boolean = until - from > 1

        /** The channel over which this box is widest, as a shift into the 15-bit cell. */
        private fun widestChannelShift(): Int {
            var minR = 31; var maxR = 0
            var minG = 31; var maxG = 0
            var minB = 31; var maxB = 0
            for (i in from until until) {
                val cell = cells[i]
                val r = (cell shr 10) and 0x1F
                val g = (cell shr 5) and 0x1F
                val b = cell and 0x1F
                if (r < minR) minR = r; if (r > maxR) maxR = r
                if (g < minG) minG = g; if (g > maxG) maxG = g
                if (b < minB) minB = b; if (b > maxB) maxB = b
            }
            val spanR = maxR - minR
            val spanG = maxG - minG
            val spanB = maxB - minB
            return when {
                spanG >= spanR && spanG >= spanB -> 5
                spanR >= spanB -> 10
                else -> 0
            }
        }

        fun split(): Pair<Box, Box>? {
            val shift = widestChannelShift()
            val sorted = cells.copyOfRange(from, until).sortedBy { (it shr shift) and 0x1F }
            for (i in sorted.indices) cells[from + i] = sorted[i]
            // Cut where half the pixels lie, not half the colours: a box is split to balance what is
            // seen, not what is listed.
            var running = 0
            val half = population / 2
            var cut = from
            for (i in from until until) {
                running += counts[cells[i]]
                if (running >= half) { cut = i + 1; break }
            }
            if (cut <= from || cut >= until) cut = (from + until) / 2
            if (cut <= from || cut >= until) return null
            return Box(cells, from, cut, counts) to Box(cells, cut, until, counts)
        }

        fun averageColor(): Int {
            var r = 0L; var g = 0L; var b = 0L; var total = 0L
            for (i in from until until) {
                val cell = cells[i]
                val weight = counts[cell].toLong()
                r += (((cell shr 10) and 0x1F) shl 3).toLong() * weight
                g += (((cell shr 5) and 0x1F) shl 3).toLong() * weight
                b += ((cell and 0x1F) shl 3).toLong() * weight
                total += weight
            }
            if (total == 0L) return 0
            return (((r / total).toInt() and 0xFF) shl 16) or
                (((g / total).toInt() and 0xFF) shl 8) or
                ((b / total).toInt() and 0xFF)
        }
    }

    /**
     * The nearest palette entry for every colour, for all 32768 of them.
     *
     * Searching the palette per pixel would mean a quarter of a million searches per frame. Searching
     * it per *colour* means 32768, once for the whole animation, after which every pixel is an array
     * read.
     */
    private fun buildLookup(palette: IntArray): IntArray {
        val lookup = IntArray(1 shl 15)
        for (cell in lookup.indices) {
            val r = ((cell shr 10) and 0x1F) shl 3
            val g = ((cell shr 5) and 0x1F) shl 3
            val b = (cell and 0x1F) shl 3
            var best = 0
            var bestDistance = Int.MAX_VALUE
            for (index in palette.indices) {
                val color = palette[index]
                val dr = r - ((color shr 16) and 0xFF)
                val dg = g - ((color shr 8) and 0xFF)
                val db = b - (color and 0xFF)
                val distance = dr * dr + dg * dg + db * db
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index
                    if (distance == 0) break
                }
            }
            lookup[cell] = best
        }
        return lookup
    }

    private fun mapPixels(pixels: IntArray, lookup: IntArray, transparentIndex: Int): ByteArray {
        val out = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            out[i] = if ((pixel ushr 24) < ALPHA_THRESHOLD) {
                transparentIndex.toByte()
            } else {
                lookup[cellOf(pixel)].toByte()
            }
        }
        return out
    }

    private fun cellOf(pixel: Int): Int =
        (((pixel shr 19) and 0x1F) shl 10) or (((pixel shr 11) and 0x1F) shl 5) or ((pixel shr 3) and 0x1F)

    private fun colorOfCell(cell: Int): Int =
        ((((cell shr 10) and 0x1F) shl 3) shl 16) or
            ((((cell shr 5) and 0x1F) shl 3) shl 8) or
            ((cell and 0x1F) shl 3)

    /** Colour tables come in powers of two, and the smallest LZW code size the format allows is 2. */
    private fun tableSizeFor(colors: Int): Int {
        var size = 4
        while (size < colors) size = size shl 1
        return size.coerceAtMost(256)
    }

    private fun bitsFor(tableSize: Int): Int {
        var bits = 2
        while ((1 shl bits) < tableSize) bits++
        return bits
    }

    /** The Netscape extension, the only way a GIF says how often it repeats. */
    private fun writeLoopExtension(out: ByteArrayOutputStream, loopCount: Int) {
        out.write(0x21)
        out.write(0xFF)
        out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3)
        out.write(1)
        writeLe16(out, if (loopCount <= 0) 0 else loopCount)
        out.write(0)
    }

    private fun writeGraphicControl(out: ByteArrayOutputStream, delayMs: Int, transparentIndex: Int) {
        out.write(0x21)
        out.write(0xF9)
        out.write(4)
        // Disposal 2 restores the frame area to the background before the next frame, which is what
        // keeps a moving sticker from smearing its own transparent parts across the frames after it.
        out.write((2 shl 2) or 0x01)
        writeLe16(out, (delayMs / 10).coerceAtLeast(MIN_DELAY_CS))
        out.write(transparentIndex and 0xFF)
        out.write(0)
    }

    /**
     * GIF's LZW: codes grow from `minCodeSize + 1` bits, the dictionary is cleared when it fills, and
     * the whole stream is packed least-significant-bit first into sub-blocks of at most 255 bytes.
     */
    private fun writeLzw(out: ByteArrayOutputStream, indices: ByteArray, minCodeSize: Int) {
        out.write(minCodeSize)
        val blocks = SubBlockWriter(out)
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        val dictionary = HashMap<Int, Int>(4096)
        var next = endCode + 1
        var codeSize = minCodeSize + 1

        blocks.write(clearCode, codeSize)
        if (indices.isEmpty()) {
            blocks.write(endCode, codeSize)
            blocks.flush()
            return
        }
        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val pixel = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or pixel
            val known = dictionary[key]
            if (known != null) {
                prefix = known
                continue
            }
            blocks.write(prefix, codeSize)
            if (next < 4096) {
                dictionary[key] = next
                // The width grows when the code just handed out no longer fits in it — checked on
                // that code, not on the one after. One entry either way and the decoder, which
                // rebuilds the same dictionary a step behind, parts company with the stream.
                if (next >= (1 shl codeSize) && codeSize < 12) codeSize++
                next++
            } else {
                blocks.write(clearCode, codeSize)
                dictionary.clear()
                next = endCode + 1
                codeSize = minCodeSize + 1
            }
            prefix = pixel
        }
        blocks.write(prefix, codeSize)
        blocks.write(endCode, codeSize)
        blocks.flush()
    }

    /** Packs codes into bits and bits into the length-prefixed blocks the format is made of. */
    private class SubBlockWriter(private val out: ByteArrayOutputStream) {
        private val block = ByteArray(255)
        private var blockSize = 0
        private var bitBuffer = 0
        private var bitCount = 0

        fun write(code: Int, codeSize: Int) {
            bitBuffer = bitBuffer or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                push((bitBuffer and 0xFF).toByte())
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
            }
        }

        fun flush() {
            if (bitCount > 0) {
                push((bitBuffer and 0xFF).toByte())
                bitBuffer = 0
                bitCount = 0
            }
            flushBlock()
            out.write(0) // block terminator
        }

        private fun push(byte: Byte) {
            block[blockSize++] = byte
            if (blockSize == 255) flushBlock()
        }

        private fun flushBlock() {
            if (blockSize == 0) return
            out.write(blockSize)
            out.write(block, 0, blockSize)
            blockSize = 0
        }
    }

    private fun writeLe16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }
}
