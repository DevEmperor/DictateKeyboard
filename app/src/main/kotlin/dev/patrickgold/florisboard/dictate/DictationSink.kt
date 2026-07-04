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

import android.content.Context
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.keyboardManager

/**
 * Where a finished dictation/rewording is written, and how the focused field is read. Abstracts the
 * editor so the same dictation engine ([DictateController]) can output through the keyboard's own
 * InputConnection when Dictate is the active IME, or — for the floating overlay (issue #88) — through
 * an AccessibilityService-injected field, where no InputConnection is available.
 *
 * The IME-backed implementation ([ImeDictationSink]) mirrors the original direct EditorInstance usage
 * exactly, so routing every output through a sink is behavior-neutral for the keyboard path.
 */
interface DictationSink {
    /** Inserts [text] at the cursor, replacing the active selection if any. */
    fun commitText(text: String)

    /** The currently selected text, or empty when nothing is selected. */
    fun selectedText(): String

    /** The full text of the focused field (used by the "rework the whole field" path). */
    fun fullText(): String

    /** Selects the whole field so a subsequent [commitText] replaces its content. */
    fun selectAll()

    /** Presses Enter / triggers the editor action (auto-enter, roadmap 10.1). */
    fun performEnter()

    /**
     * Removes the last inserted [text] from the field again (undo, issue #133). Only deletes when the
     * text immediately before the cursor still matches [text], so the user's own edits are never eaten.
     * Returns true when the field accepted the removal.
     */
    fun deleteLastText(text: String): Boolean

    /**
     * Live real-time dictation preview (issue #128): reflect [newText] (the growing transcript) in the
     * field, applying only the minimal diff from the [prevText] already shown — so streaming words appear
     * in place, committed to the field. The overlay path has no cheap in-place update, so it skips the
     * preview and shows nothing until [commitDictationFinal].
     */
    fun setDictationPreview(newText: String, prevText: String)

    /** Finalize: replace the [prevText] preview with the finished/reworded [finalText] (minimal diff). */
    fun commitDictationFinal(finalText: String, prevText: String)

    /** Remove the [prevText] preview entirely (a realtime recording was cancelled / fell back to batch). */
    fun clearDictationPreview(prevText: String)
}

/**
 * [DictationSink] backed by the keyboard's own editor — the active-IME path. Resolves the app-wide
 * [dev.patrickgold.florisboard.ime.editor.EditorInstance] and [dev.patrickgold.florisboard.ime.keyboard.KeyboardManager]
 * singletons exactly as the original inline code did, so dictation output is unchanged.
 */
class ImeDictationSink(context: Context) : DictationSink {
    private val appContext = context.applicationContext
    private val editorInstance by appContext.editorInstance()

    override fun commitText(text: String) {
        editorInstance.commitText(text)
    }

    override fun selectedText(): String = editorInstance.activeContent.selectedText

    override fun fullText(): String = editorInstance.activeContent.text

    override fun selectAll() {
        editorInstance.performClipboardSelectAll()
    }

    override fun performEnter() {
        val keyboardManager by appContext.keyboardManager()
        // Dispatches a real Enter key event so it reuses the keyboard's full enter logic (editor action,
        // newline, …) rather than committing a literal "\n".
        keyboardManager.inputEventDispatcher.sendDownUp(EnterKeyData)
    }

    override fun deleteLastText(text: String): Boolean {
        if (text.isEmpty()) return false
        // Only undo when the characters right before the cursor are exactly what we inserted.
        if (!editorInstance.activeContent.textBeforeSelection.endsWith(text)) return false
        val keyboardManager by appContext.keyboardManager()
        // Reuse the keyboard's own delete handling (one backspace per character).
        repeat(text.length) { keyboardManager.inputEventDispatcher.sendDownUp(TextKeyData.DELETE) }
        return true
    }

    override fun setDictationPreview(newText: String, prevText: String) = applyDictationDiff(prevText, newText)

    override fun commitDictationFinal(finalText: String, prevText: String) {
        // Atomic swap of the streamed preview for the finished/reworded text (keeps the common prefix,
        // replaces only the divergent tail in one batch → no character-by-character flicker).
        if (prevText == finalText) return
        val cp = prevText.commonPrefixWith(finalText).length
        editorInstance.replaceDictationTail(prevText.length - cp, finalText.substring(cp))
    }

    override fun clearDictationPreview(prevText: String) = applyDictationDiff(prevText, "")

    /**
     * Turns the currently-shown dictation text [old] into [new] with the minimal edit: keep the common
     * prefix, delete the divergent tail (right before the cursor) and commit the new tail. Uses the
     * editor's own commit/delete so it stays consistent with the content model (no composing-region
     * collision). Append-only streaming (the common case) never deletes.
     */
    private fun applyDictationDiff(old: String, new: String) {
        if (old == new) return
        val cp = old.commonPrefixWith(new).length
        if (cp < old.length) deleteLastText(old.substring(cp))
        // Raw commit (no phantom/auto space) so the field stays byte-identical to what we tracked.
        if (cp < new.length) editorInstance.commitTextRaw(new.substring(cp))
    }

    private companion object {
        /** Synthetic Enter key dispatched for auto-enter; reuses the keyboard's full enter logic. */
        private val EnterKeyData =
            TextKeyData(type = KeyType.ENTER_EDITING, code = KeyCode.ENTER, label = "enter")
    }
}
