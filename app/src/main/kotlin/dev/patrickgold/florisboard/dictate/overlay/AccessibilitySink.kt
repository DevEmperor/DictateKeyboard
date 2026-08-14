/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.overlay

import dev.patrickgold.florisboard.dictate.DictationSink

/**
 * [DictationSink] backed by [DictateAccessibilityService]. Used by the floating dictation button
 * (issue #88) to write the transcription into whichever app's text field is focused, where no
 * InputConnection is available. Every call is a no-op (returns gracefully) when the accessibility
 * service is not running or no editable field is focused.
 */
class AccessibilitySink : DictationSink {
    override fun commitText(text: String): Boolean =
        DictateAccessibilityService.injectText(text)

    override fun selectedText(): String = DictateAccessibilityService.selectedText()

    override fun fullText(): String = DictateAccessibilityService.fullText()

    override fun selectAll() {
        DictateAccessibilityService.selectAll()
    }

    override fun performEnter(): Boolean = DictateAccessibilityService.performEnter()

    override fun deleteLastText(text: String): Boolean =
        DictateAccessibilityService.deleteLastText(text)

    // Real-time overlay preview (#128): the service tracks what it injected and applies a throttled minimal
    // diff (so live streaming into another app doesn't flood the accessibility channel). It keeps its own
    // shown-text state, so the sink's prevText is unused here.
    override fun setDictationPreview(newText: String, prevText: String) {
        DictateAccessibilityService.setPreview(newText)
    }

    override fun commitDictationFinal(finalText: String, prevText: String) {
        DictateAccessibilityService.commitPreviewFinal(finalText)
    }

    override fun clearDictationPreview(prevText: String) {
        DictateAccessibilityService.clearPreview()
    }
}
