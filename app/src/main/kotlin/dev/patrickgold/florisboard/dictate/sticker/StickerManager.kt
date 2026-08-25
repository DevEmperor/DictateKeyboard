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
import android.content.Intent
import android.net.Uri
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import androidx.core.content.FileProvider
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.dictate.media.MediaCache
import dev.patrickgold.florisboard.dictate.media.MediaFormat
import dev.patrickgold.florisboard.dictate.media.MediaLog
import dev.patrickgold.florisboard.dictate.media.WebPTranscoder
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.editor.EditorInstance
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.io.File
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.stringRes

/**
 * Inserts a sticker from the user's own folder into the current editor (issue #280).
 *
 * The panel shows stickers straight from their `content://` URI, but inserting one cannot use that URI:
 * it belongs to the system's documents provider, and the read permission this app holds on it is not
 * ours to hand on to a third app. So the file is copied into [MediaCache] first and inserted from there
 * through the same [EditorInstance.commitMedia] path the GIF search uses — including its fallback of
 * putting the sticker on the clipboard when the target app refuses rich content of that type.
 */
object StickerManager {
    private val prefs by FlorisPreferenceStore

    /** How many stickers one opening of the panel prepares ahead of the user. */
    private const val PrewarmLimit = 48

    /** A breath between two conversions, so browsing a folder does not read as a batch job. */
    private const val PrewarmPauseMs = 120L

    /**
     * True while a tap is being served.
     *
     * The background pass must never be the reason a tap is slow, and both compress frames on the
     * same cores. So it stands aside for the one piece of work the user is actually waiting on.
     */
    @Volatile
    private var inserting = false

    private fun extensionFor(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/jpeg" -> "jpg"
        else -> "img"
    }

    /**
     * Copies [item] into the media cache and returns the file, or null if it could not be read.
     *
     * The cache name carries the modification stamp, so replacing a sticker with a new file of the same
     * name inserts the new one instead of silently re-sending the copy made before the edit.
     */
    private suspend fun materialize(context: Context, treeUri: Uri, item: StickerItem): File? =
        withContext(Dispatchers.IO) {
            try {
                val stamp = item.lastModified.toString()
                val safeName = item.docId.hashCode().absoluteValue.toString(16)
                val file = File(
                    MediaCache.dir(context),
                    "sticker-$safeName-$stamp.${extensionFor(item.mime)}",
                )
                if (file.exists() && file.length() > 0L) return@withContext file
                val source = StickerScanner.documentUri(treeUri, item.docId)
                context.contentResolver.openInputStream(source)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext null
                if (file.length() > 0L) file else null
            } catch (e: Exception) {
                flogError { "Failed to stage sticker ${item.docId}: ${e.message}" }
                null
            }
        }

    /**
     * Stages and inserts [item]. On success it is recorded as recently used in [categoryId] and in the
     * combined list.
     *
     * Decides before it acts, which is the opposite of what this did at first. Offering the file's own
     * type, watching it bounce and only then converting meant the clipboard had already been written
     * to — and on Android 13+ the system announces every clipboard write, so the user was told
     * "Copied" a second before the sticker they wanted appeared anyway. Now the editor is asked what
     * it takes, the file is prepared for that answer, and one attempt is made. The clipboard is the
     * last resort it was always meant to be.
     *
     * [onPreparing] is called when the file has to be converted or re-encoded first, which is the only
     * case that takes long enough to need saying.
     */
    suspend fun insert(
        context: Context,
        treeUri: Uri,
        item: StickerItem,
        categoryId: String,
        onPreparing: (Boolean) -> Unit = {},
    ): EditorInstance.MediaCommitResult {
        inserting = true
        try {
            return insertNow(context, treeUri, item, categoryId, onPreparing)
        } finally {
            inserting = false
        }
    }

    private suspend fun insertNow(
        context: Context,
        treeUri: Uri,
        item: StickerItem,
        categoryId: String,
        onPreparing: (Boolean) -> Unit,
    ): EditorInstance.MediaCommitResult {
        val file = materialize(context, treeUri, item) ?: return EditorInstance.MediaCommitResult.FAILED
        val editorInstance by context.editorInstance()
        val description = item.name.ifBlank { "Sticker" }

        val accepted = withContext(Dispatchers.Main) { editorInstance.acceptedMediaMimeTypes() }
        val appPackage = withContext(Dispatchers.Main) { editorInstance.activeEditorPackage() }
        val info = withContext(Dispatchers.IO) { MediaFormat.inspect(file, item.mime) }
        val target = MediaFormat.negotiate(info, accepted)
        MediaLog.log(
            "insert \"${item.name}\": ${info.mime} ${info.width}x${info.height} ${info.bytes} B " +
                "animated=${info.animated} | app=$appPackage " +
                "accepts=[${accepted.joinToString()}] | offering=$target"
        )

        var committed = false
        var payload: File? = null
        if (target != null) {
            val needsWork = target != item.mime
            if (needsWork) onPreparing(true)
            payload = try {
                MediaLog.timed("insert: preparing $target") { prepare(context, file, info, target) }
            } finally {
                if (needsWork) onPreparing(false)
            }
            if (payload != null) {
                committed = withContext(Dispatchers.Main) {
                    editorInstance.tryCommitMedia(payload, target, description)
                }
                MediaLog.log("insert: commit as $target (${payload.length()} B) -> $committed")
            } else {
                MediaLog.log("insert: could not prepare $target")
            }
        }

        // A declaration is a hint rather than a contract, so a file's own type still deserves an
        // attempt — but only where that is a hint and not a contradiction. An app that listed its
        // types and left this one out was not being coy: handing it the bytes anyway is how a sticker
        // ends up as an empty frame with "Couldn't share" beside it, which is a worse answer than the
        // clipboard. So the retry is for apps that declared nothing, or that named this type already.
        val ownTypeIsPlausible = accepted.isEmpty() || accepted.any { MediaFormat.matches(item.mime, it) }
        if (!committed && target != item.mime && ownTypeIsPlausible) {
            committed = withContext(Dispatchers.Main) {
                editorInstance.tryCommitMedia(file, item.mime, description)
            }
            MediaLog.log("insert: retry as ${item.mime} -> $committed")
        }

        val result = when {
            committed -> EditorInstance.MediaCommitResult.COMMITTED
            withContext(Dispatchers.Main) { editorInstance.copyMediaToClipboard(file, item.mime) } ->
                EditorInstance.MediaCommitResult.COPIED_TO_CLIPBOARD
            else -> EditorInstance.MediaCommitResult.FAILED
        }
        MediaLog.log("insert: result=$result")

        if (result != EditorInstance.MediaCommitResult.FAILED) {
            StickerHistoryHelper.markUsed(prefs, categoryId, item.docId)
        }
        if (committed && payload != null && payload != file && target == MediaFormat.WA_STICKER) {
            keepConversion(context, treeUri, item, payload, info)
        }
        withContext(Dispatchers.IO) {
            MediaCache.prune(context)
            MediaCache.pruneConverted(context)
        }
        return result
    }

    /**
     * Writes a re-encoded sticker back over the original in the user's folder.
     *
     * The conversion has to happen at least once per sticker, and the first version of this kept the
     * result in a private directory — which works, but hides from the user the fact that the file
     * they see in their folder is not the file that gets sent. Writing it back makes the two the
     * same thing: the sticker becomes one WhatsApp accepts, permanently, visible in any file manager,
     * and every later insert is instant because nothing is left to convert.
     *
     * Only ever narrows the gap: this runs when the original was outside the receiving app's sticker
     * bounds and the re-encoded copy is inside them. A failure is logged and otherwise ignored — the
     * sticker was already inserted, and the private copy still stands in for the next attempt.
     */
    private suspend fun keepConversion(
        context: Context,
        treeUri: Uri,
        item: StickerItem,
        converted: File,
        original: MediaFormat.ImageInfo,
    ) {
        // Never trade a smaller file for a larger one. Enlarging a 240×240 sticker to the 512×512 a
        // sticker has to be invents pixels, and those invented pixels cost bytes: measured on the
        // device, one 197 KB sticker came back out at 412 KB. It is still the version that gets
        // inserted — it is the only one WhatsApp takes — but it stays in the app's own store rather
        // than doubling the size of the file in the user's folder. Nothing is lost by that: the
        // store lives in `filesDir`, so the conversion survives and later inserts are still instant.
        if (converted.length() > original.bytes) {
            MediaLog.log(
                "insert: keeping \"${item.name}\" as it was, " +
                    "the converted copy is larger (${original.bytes} B -> ${converted.length()} B)"
            )
            return
        }
        val ok = StickerWriter.overwrite(context, treeUri, item.docId, converted)
        MediaLog.log(
            "insert: rewrote \"${item.name}\" in place, " +
                "${original.bytes} B -> ${converted.length()} B: $ok"
        )
        // The folder no longer matches what was scanned, so the next open re-reads it and picks up
        // the new size and stamp — after which the sticker qualifies outright and is never converted
        // again.
        if (ok) withContext(Dispatchers.IO) { StickerScanner.clearCached(context) }
    }

    /**
     * Prepares stickers for the app in front of us before anyone taps them.
     *
     * Re-encoding an animation takes a moment, and the moment falls exactly where it is least
     * welcome: after the tap, with the chat waiting. But the panel spends most of its life being
     * looked at rather than used, and the work does not depend on which sticker is chosen — so it can
     * be done during the looking. What is left after that is a file copy.
     *
     * Only for an app that asks for a vendor sticker type, since that is the only case where a file
     * needs changing at all; ordered by what is most likely to be tapped; capped, paced, and yielding
     * to any insert that starts meanwhile. It ends when the panel closes, because the caller's scope
     * ends with it.
     */
    suspend fun prewarm(context: Context, treeUri: Uri, items: List<StickerItem>) {
        val editorInstance by context.editorInstance()
        val accepted = withContext(Dispatchers.Main) { editorInstance.acceptedMediaMimeTypes() }
        if (!accepted.contains(MediaFormat.WA_STICKER)) return
        var prepared = 0
        for (item in items) {
            if (prepared >= PrewarmLimit) break
            currentCoroutineContext().ensureActive()
            if (item.mime != "image/webp") continue
            while (inserting) delay(50)
            val file = materialize(context, treeUri, item) ?: continue
            val info = withContext(Dispatchers.IO) { MediaFormat.inspect(file, item.mime) }
            if (MediaFormat.qualifiesAsWhatsAppSticker(info)) continue
            if (WebPTranscoder.conversionOf(context, file, info.animated) != null) continue
            val converted = withContext(Dispatchers.IO) {
                WebPTranscoder.toStickerSpec(context, file, info.animated)
            } ?: continue
            prepared++
            if (converted.length() <= info.bytes) {
                StickerWriter.overwrite(context, treeUri, item.docId, converted)
            }
            delay(PrewarmPauseMs)
        }
        if (prepared > 0) {
            MediaLog.log("prewarm: prepared $prepared sticker(s) for the app in front")
            withContext(Dispatchers.IO) {
                StickerScanner.clearCached(context)
                MediaCache.prune(context)
                MediaCache.pruneConverted(context)
            }
        }
    }

    /**
     * The bytes to hand over for [target]: the file itself, a relabelling, a re-encode, or a PNG.
     */
    private suspend fun prepare(
        context: Context,
        file: File,
        info: MediaFormat.ImageInfo,
        target: String,
    ): File? = when {
        target == info.mime -> file
        // A vendor name for our own format is a relabelling — but only for a file already shaped like
        // a sticker. WhatsApp takes one that is not, and then refuses it with an empty frame and
        // "Couldn't share", so anything outside its bounds is re-encoded to 512×512 within budget
        // first. This holds for still stickers as much as for animated ones: letting stills through
        // untouched, as this did at first, is exactly what made an ordinary 256×256 sticker bounce.
        target == MediaFormat.WA_STICKER && MediaFormat.qualifiesAsWhatsAppSticker(info) -> file
        target == MediaFormat.WA_STICKER ->
            withContext(Dispatchers.IO) { WebPTranscoder.toStickerSpec(context, file, info.animated) }
        else -> withContext(Dispatchers.IO) { MediaFormat.convert(context, file, target) }
    }

    /**
     * Hands the sticker to the system share sheet instead of to the editor.
     *
     * The way out when an app will not take rich content from a keyboard at all: the share sheet goes
     * through that app's ordinary import path, which is usually far more forgiving than what it
     * declares to an input method. EweSticker, the established open-source sticker keyboard, uses the
     * same route as its last resort.
     *
     * Costs the user the chat picker, so it is an explicit choice in the long-press menu rather than
     * an automatic fallback.
     */
    suspend fun share(context: Context, treeUri: Uri, item: StickerItem): Boolean {
        val file = materialize(context, treeUri, item) ?: return false
        return try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.provider.file", file,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = item.mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            withContext(Dispatchers.Main) { context.startActivity(chooser) }
            true
        } catch (e: Exception) {
            flogError { "Failed to share sticker ${item.docId}: ${e.message}" }
            false
        }
    }

    /**
     * Why an insert ended up on the clipboard, in the words of the app that refused it.
     *
     * Worth saying out loud rather than hiding behind "this app does not accept stickers": the reason
     * is almost always a format the app never listed, and knowing which one turns a mystery into a
     * fact — for the user and for the next bug report.
     */
    fun refusalReason(context: Context, item: StickerItem): String {
        val editorInstance by context.editorInstance()
        val accepted = editorInstance.acceptedMediaMimeTypes()
        return if (accepted.isEmpty()) {
            context.stringRes(R.string.sticker__refused_declares_nothing)
        } else {
            context.stringRes(
                R.string.sticker__refused_accepts_only,
                "accepted" to accepted.joinToString(", "),
                "own" to item.mime,
            )
        }
    }
}
