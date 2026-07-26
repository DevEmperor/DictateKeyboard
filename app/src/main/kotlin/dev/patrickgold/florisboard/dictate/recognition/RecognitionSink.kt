/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.recognition

import dev.patrickgold.florisboard.dictate.DictationSink

/**
 * [DictationSink] for the system voice-input path (issue #67). There is no text field to write into —
 * the finished transcript is accumulated and handed back to the OS through the active
 * [DictateRecognitionService] callback, which lets the calling app/keyboard insert it natively.
 *
 * Only text output is meaningful here; the field-reading methods return empty and the editing/enter
 * methods are no-ops. A live preview ([setDictationPreview]) maps to interim `partialResults` (used only
 * if a streaming path is ever wired up — the recognition path currently records in plain batch mode).
 */
class RecognitionSink : DictationSink {

    override fun commitText(text: String): Boolean {
        RecognitionBridge.appendResult(text)
        return true
    }

    override fun selectedText(): String = ""

    override fun fullText(): String = ""

    override fun selectAll() = Unit

    override fun performEnter() = Unit

    override fun deleteLastText(text: String): Boolean = false

    override fun setDictationPreview(newText: String, prevText: String) {
        RecognitionBridge.deliverPartial(newText)
    }

    override fun commitDictationFinal(finalText: String, prevText: String) {
        RecognitionBridge.appendResult(finalText)
    }

    override fun clearDictationPreview(prevText: String) = Unit
}
