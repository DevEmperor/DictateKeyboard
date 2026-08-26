/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.sticker

import android.content.Context
import dev.patrickgold.florisboard.dictate.media.MediaFormat
import dev.patrickgold.florisboard.dictate.media.WebPTranscoder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Brings every sticker in the folder into one shape (issue #280).
 *
 * The shape is WhatsApp's: WebP, exactly 512×512, at most 100 KB still or 500 KB animated. It is the
 * strictest of the formats any chat app asks for, so a file that satisfies it satisfies all of them —
 * which is the entire point. A folder in one shape needs no decisions at insert time.
 *
 * **Why this exists at all.** Earlier the conversion happened when a sticker was tapped: negotiate,
 * re-encode, cache, write back, and a background pass to hide the wait. Five mechanisms for a problem
 * that arises once per file. Gboard and the Samsung keyboard have none of them, and not because they
 * are cleverer — their stickers come from curated catalogues and are already 512×512 WebP. Doing the
 * work once, when a sticker arrives, puts this folder in the same position.
 *
 * **GIFs are left alone, deliberately.** Stepping an animated GIF frame by frame is awkward on
 * Android, WhatsApp accepts `image/gif` anyway, and there are few of them in a sticker collection.
 */
object StickerNormalizer {

    /**
     * The normalized form of [file], or null when there is nothing to do or nothing can be done.
     *
     * Null is the common and cheap answer: a sticker that is already in shape stays exactly as it is,
     * bit for bit, and a second pass over a normalized folder therefore costs one header read per
     * file. Works for PNG and JPEG as much as for WebP — the transcoder decodes whatever the platform
     * can decode and always writes WebP.
     */
    suspend fun normalize(context: Context, file: File, mime: String): File? {
        if (mime == "image/gif") return null
        val info = withContext(Dispatchers.IO) { MediaFormat.inspect(file, mime) }
        if (MediaFormat.qualifiesAsWhatsAppSticker(info)) return null
        return withContext(Dispatchers.IO) {
            WebPTranscoder.toStickerSpec(context, file, info.animated)
        }
    }
}
