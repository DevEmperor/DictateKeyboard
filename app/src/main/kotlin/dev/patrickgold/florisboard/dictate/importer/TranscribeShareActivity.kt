/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.importer

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.apptheme.FlorisAppTheme
import org.florisboard.lib.compose.ProvideLocalizedResources
import java.io.File

/**
 * "Share → Dictate" for a voice message (issue #301).
 *
 * The other half of file transcription. The long-press-the-mic route answers "put this file's text
 * into the field I am typing in"; this one answers what people actually do with a voice message,
 * which is the reverse: you are in Signal, holding the audio, and you want to know what it says.
 * There is no focused field anywhere in that story, so the transcript needs a screen of its own to
 * land on — see [TranscribeShareScreen].
 *
 * Also registered for ACTION_VIEW, so "Open with → Dictate" works from any file manager.
 *
 * **The grant dies with this activity.** A content Uri from ACTION_SEND is readable only while we
 * are alive, so the file is copied into our own cache before anything else happens. Everything
 * afterwards — playback, a second transcription, the history entry — works on that copy.
 */
class TranscribeShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = incomingUris(intent)
        val prefs by FlorisPreferenceStore

        setContent {
            // Read once: the theme cannot change while this screen is up, and the jetpref observer
            // would clash by name with the runtime one (same reasoning as RecognitionActivity).
            val theme = remember { prefs.other.settingsTheme.get() }
            ProvideLocalizedResources(this, appName = R.string.app_name_full) {
                FlorisAppTheme(theme = theme) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        TranscribeShareScreen(
                            uris = uris,
                            onClose = { finish() },
                        )
                    }
                }
            }
        }
    }

    companion object {
        /** Extra carrying an already-picked file, used by the in-app "Transcribe a file" entry. */
        const val EXTRA_PICKED_URI = "dictate.pickedUri"

        val MIME_TYPES = arrayOf("audio/*", "video/*")

        /** Launches the screen for a file the user picked inside the app. */
        fun intentFor(context: Context, uri: Uri): Intent =
            Intent(context, TranscribeShareActivity::class.java)
                .putExtra(EXTRA_PICKED_URI, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        /**
         * Every audio/video Uri the intent carries, in the order it arrived.
         *
         * SEND_MULTIPLE is accepted rather than refused — a share sheet offers it whether we asked or
         * not — but only the first file is transcribed in this round. The screen says how many were
         * left, because silently doing part of the job is worse than plainly doing one of it.
         */
        @Suppress("DEPRECATION")
        fun incomingUris(intent: Intent?): List<Uri> {
            intent ?: return emptyList()
            (intent.getParcelableExtra(EXTRA_PICKED_URI) as? Uri)?.let { return listOf(it) }
            return when (intent.action) {
                Intent.ACTION_SEND ->
                    listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
                Intent.ACTION_SEND_MULTIPLE ->
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty().filterNotNull()
                Intent.ACTION_VIEW -> listOfNotNull(intent.data)
                else -> emptyList()
            }
        }
    }
}

/** Name, byte size and duration of a shared file, read before anything is copied. */
data class SharedFileInfo(
    val displayName: String,
    val sizeBytes: Long,
    val durationSecs: Long,
    /**
     * Whether the file actually carries sound.
     *
     * Asked because the share filter has to accept application/octet-stream to see WhatsApp voice
     * notes at all — Android's MimeTypeMap has no entry for "opus", so every documents provider
     * calls them unknown. Accepting unknown means accepting things that are not audio, and the
     * honest place to find that out is here, before anything is uploaded.
     */
    val hasAudio: Boolean,
)

/** 80 ms between copy progress reports — often enough to look live, rarely enough to be free. */
private const val COPY_REPORT_INTERVAL_NANOS = 80_000_000L

/**
 * What the sharing app says about [uri] before anything is read from it.
 *
 * Asked on its own so the screen can list the steps ahead *before* the copy starts (issue #337):
 * whether there is a video to unpack and whether the file is too big to send in one piece are both
 * answers this pair already contains. [sizeBytes] is 0 when the provider does not say — never a
 * reason to guess one.
 */
data class SharedFileHeader(val displayName: String, val sizeBytes: Long)

/** Reads [uri]'s name and size. Cheap: one cursor query, no bytes moved. */
fun readSharedFileHeader(context: Context, uri: Uri): SharedFileHeader {
    var name = "shared_audio"
    var size = 0L
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) cursor.getString(nameIndex)?.let { name = it }
                if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
            }
        }
    }
    // The extension travels with the file: providers infer the audio format from it, and the decoder
    // uses it to tell a video container from an audio one.
    return SharedFileHeader(name.substringAfterLast('/').ifBlank { "shared_audio" }, size)
}

/**
 * Copies [uri] into our own cache and reads what we can about it.
 *
 * [header] is what the caller already asked for; left out, it is read here. [onProgress] reports
 * `(copied, total)` as the bytes move — the file may be coming from a cloud provider rather than the
 * phone, which is when this step stops being instant. `total` is 0 when the size was never stated.
 *
 * Returns null when the content could not be read at all — a share whose grant was already gone, or
 * a provider that hands back nothing. The caller shows that as an error rather than an empty screen.
 */
fun copySharedFile(
    context: Context,
    uri: Uri,
    header: SharedFileHeader? = null,
    onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
): Pair<File, SharedFileInfo>? {
    val resolved = header ?: readSharedFileHeader(context, uri)
    val safeName = resolved.displayName
    var size = resolved.sizeBytes
    val dir = File(context.cacheDir, "dictate_share").apply { deleteRecursively(); mkdirs() }
    val target = File(dir, safeName)
    val copied = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var moved = 0L
                var reportedAtNanos = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    moved += read
                    // Throttled for the same reason the upload counter is: a 100 MB file is over a
                    // thousand chunks, and redrawing the screen for each of them would cost more than
                    // the copy itself.
                    val now = System.nanoTime()
                    if (now - reportedAtNanos >= COPY_REPORT_INTERVAL_NANOS) {
                        reportedAtNanos = now
                        onProgress(moved, size)
                    }
                }
                onProgress(moved, if (size > 0L) size else moved)
            }
        } ?: return@runCatching false
        true
    }.getOrDefault(false)
    if (!copied || !target.exists() || target.length() == 0L) {
        target.delete()
        return null
    }
    if (size <= 0L) size = target.length()

    val probe = runCatching {
        MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(target.absolutePath)
            val secs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L
            val audio = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            secs to audio
        }
    }.getOrDefault(0L to false)

    return target to SharedFileInfo(safeName, size, probe.first, probe.second)
}
