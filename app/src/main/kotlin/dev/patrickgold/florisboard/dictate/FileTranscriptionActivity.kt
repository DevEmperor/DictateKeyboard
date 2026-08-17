/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore

/**
 * Invisible trampoline launched by [DictateController.startFileTranscription] (long-press mic). The
 * keyboard's IME cannot start an activity-for-result itself, so this activity opens the system file
 * picker, validates the size and copies the chosen audio/video file into the dedicated pending cache
 * directory ([DictateController.pendingTranscriptionDir]).
 *
 * The handoff is intentionally file-based rather than going through a preference: the IME process is
 * routinely killed while another app (the file picker) is foreground, and a copied cache file is the
 * only thing guaranteed to survive that and a synchronous, race-free signal. When the keyboard next
 * regains focus, [DictateController.consumePendingFileTranscription] picks the file up.
 */
class FileTranscriptionActivity : ComponentActivity() {

    private val prefs by FlorisPreferenceStore

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        handlePickedUri(uri)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            picker.launch(MIME_TYPES)
        }
    }

    private fun handlePickedUri(uri: Uri?) {
        if (uri == null) return

        var fileName = "dictate_file"
        var fileSize = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) cursor.getString(nameIndex)?.let { fileName = it }
                if (sizeIndex >= 0) fileSize = cursor.getLong(sizeIndex)
            }
        }

        // Provider-aware size guard. OpenAI/Groq cap uploads at 25 MB; custom servers are left to
        // enforce their own limit (the transcription error surfaces it if exceeded).
        val sizeLimit = maxUploadBytesFor(prefs.dictate.transcriptionProviderId.get())
        if (sizeLimit > 0L && fileSize > sizeLimit) {
            Toast.makeText(this, R.string.dictate__file_too_large, Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, R.string.dictate__file_preparing, Toast.LENGTH_SHORT).show()
        // Reset the pending dir so a previous, never-consumed pick can't linger, then write the file.
        // Keeping the original extension matters – providers infer the audio format from it.
        val pendingDir = DictateController.pendingTranscriptionDir(this).apply {
            deleteRecursively()
            mkdirs()
        }
        val target = java.io.File(pendingDir, fileName.substringAfterLast('/').ifBlank { "dictate_file" })
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                Toast.makeText(this, R.string.dictate__file_read_error, Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            target.delete()
            Toast.makeText(this, R.string.dictate__file_read_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun maxUploadBytesFor(providerId: String): Long =
        dev.patrickgold.florisboard.dictate.provider.ProviderRegistry.maxUploadBytes(providerId)

    companion object {
        private val MIME_TYPES = arrayOf("audio/*", "video/*")
    }
}
