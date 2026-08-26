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

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import kotlinx.coroutines.launch

/**
 * "Share → Dictate" for stickers (issue #280).
 *
 * This is the answer to *"can I get my WhatsApp stickers in here"*, and it is the only answer that
 * holds across apps. Reading another app's sticker collection off the disk does not work in general:
 * Samsung's keyboard keeps its stickers in private app storage that no other app may read, Telegram
 * keeps its in a cache, and since Android 11 the file picker refuses `Android/data` outright. What
 * every one of them does offer is the share sheet, and what arrives through it is a plain image.
 *
 * So: long-press the sticker in the other app, share it here, and it lands in the folder. No UI of its
 * own beyond a toast — a screen would only be in the way of a two-second action.
 */
class StickerImportActivity : ComponentActivity() {

    private val prefs by FlorisPreferenceStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        val sources = StickerWriter.sharedUris(intent)
        val folder = prefs.sticker.folderUri.get()

        if (sources.isEmpty()) {
            toastAndFinish(getString(R.string.sticker__import_nothing))
            return
        }
        if (folder.isBlank()) {
            // Nowhere to put it. Say so and open the screen where a folder is chosen, rather than
            // dropping the share on the floor.
            Toast.makeText(this, R.string.sticker__import_no_folder, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Intent.ACTION_VIEW, "ui://florisboard/settings/media".toUri())
                    .setPackage(packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            finish()
            return
        }
        if (!StickerWriter.canWrite(this, folder)) {
            toastAndFinish(getString(R.string.sticker__import_needs_write))
            return
        }

        lifecycleScope.launch {
            val result = StickerWriter.importInto(this@StickerImportActivity, folder.toUri(), sources)
            toastAndFinish(summarize(result))
        }
    }

    /**
     * One line the user can act on. The counts that are zero are left out — "3 added" is the whole
     * story most of the time, and naming every category of non-event would bury the one that matters.
     */
    private fun summarize(result: StickerWriter.ImportResult): String =
        stickerImportSummary(this, result)

    private fun toastAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
