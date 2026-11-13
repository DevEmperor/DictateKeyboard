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
import dev.patrickgold.florisboard.ime.keyboard.KeyboardManager
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
    /**
     * Inserts [text] at the cursor, replacing the active selection if any. Returns whether the write
     * actually landed: the keyboard path always succeeds, but the accessibility/overlay path can fail
     * silently on some app fields (Compose/WebView), so callers can avoid flashing a false success (#156).
     */
    fun commitText(text: String, verify: Boolean = true): Boolean

    /** The currently selected text, or empty when nothing is selected. */
    fun selectedText(): String

    /**
     * The full text of the focused field. For the keyboard in an app's field this is only the window
     * around the cursor that the editor keeps, so it says whether there is text, never what all of it is
     * (issue #473) — that is [selectWholeField].
     */
    fun fullText(): String

    /** Selects the whole field so a subsequent [commitText] replaces its content. */
    fun selectAll()

    /**
     * Selects the whole field and returns all of its text: what a prompt with nothing selected rewords,
     * and what the result then replaces. Null when the field is empty or cannot be read in full — a part
     * of the field taken for the whole of it would have the rest deleted by the commit (issue #473).
     *
     * The default suits sinks whose [fullText] is the whole field: the overlay, which refuses a field it
     * cannot prove (issue #314), and the keyboard's own fields, held whole in memory.
     */
    suspend fun selectWholeField(): String? {
        val whole = fullText().takeIf { it.isNotBlank() } ?: return null
        selectAll()
        return whole
    }

    /**
     * Up to [length] characters in front of the cursor (in front of the selection, when there is one),
     * or null when the field cannot be read. Empty means the cursor sits at the start of the field —
     * the two are kept apart because only the second is a fact. Used to space a dictation against the
     * text it lands next to ([TranscriptJoin.separatorBefore]).
     */
    fun textBeforeCursor(length: Int): String?

    /**
     * Up to [length] characters behind the cursor (behind the selection, when there is one), or null when
     * the field cannot be read; empty at the end of the field. What a dictation into the middle of a
     * sentence ends against: whether its full stop goes, and whether a space must follow it (#476).
     */
    fun textAfterCursor(length: Int): String?

    /**
     * Presses Enter / triggers the editor action (auto-enter, roadmap 10.1). Returns whether the field
     * accepted it — the keyboard dispatches a real key event and always does, while the overlay can only
     * *ask* the field, and an app that implements no editor action simply refuses (issue #278).
     */
    fun performEnter(): Boolean

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

    /**
     * Finalize: replace the [prevText] preview with the finished/reworded [finalText] (minimal diff).
     * Returns whether the field took it — the realtime path used to skip the insert-failure check
     * entirely, so a swallowed write ended in a green check (issue #277).
     */
    fun commitDictationFinal(finalText: String, prevText: String): Boolean

    /**
     * Remove the [prevText] preview entirely (a realtime recording was cancelled / fell back to batch) —
     * unless the field no longer [holds][holdsPreview] it: text the user has edited is theirs (issue #421).
     */
    fun clearDictationPreview(prevText: String)

    /**
     * Whether the field still ends, at the cursor, with [prevText] — the live preview as it was last
     * written. False once the user has edited it, moved the cursor away or cleared the field (issue #421):
     * every preview write is a diff against [prevText], and against a field that no longer holds it the
     * diff deletes the user's own words and types back the ones they removed.
     *
     * True when the field cannot be read, which is what every write assumed before. Paths that keep no
     * preview text of their own in a field answer true.
     */
    fun holdsPreview(prevText: String): Boolean = true
}

/**
 * [DictationSink] backed by the keyboard's own editor — the active-IME path. Resolves the app-wide
 * [dev.patrickgold.florisboard.ime.editor.EditorInstance] and [dev.patrickgold.florisboard.ime.keyboard.KeyboardManager]
 * singletons exactly as the original inline code did, so dictation output is unchanged.
 */
class ImeDictationSink(context: Context) : DictationSink {
    private val appContext = context.applicationContext
    private val editorInstance by appContext.editorInstance()
    private val keyboardManager by appContext.keyboardManager()

    /**
     * The keyboard's own field that has the keys (issue #424) — the translate bar or a search. The
     * keyboard writes where it types: while such a field is open, dictation, a live preview and a prompt
     * go into it, exactly as the keys do, and the app's field is left alone.
     */
    private fun field(): KeyboardManager.InternalField? = keyboardManager.activeInternalField()

    override fun commitText(text: String, verify: Boolean): Boolean {
        field()?.let { field ->
            keyboardManager.fieldText(field)?.let { keyboardManager.setFieldText(field, it.insert(text)) }
            keyboardManager.reevaluateInputShiftState()
            return true
        }
        editorInstance.commitText(text)
        return true // the keyboard writes through its own InputConnection; this never silently no-ops
    }

    override fun selectedText(): String =
        field()?.let { keyboardManager.fieldText(it)?.selectedText.orEmpty() } ?: editorInstance.activeContent.selectedText

    override fun fullText(): String =
        field()?.let { keyboardManager.fieldText(it)?.text.orEmpty() } ?: editorInstance.activeContent.text

    override fun selectAll() {
        field()?.let { field ->
            keyboardManager.fieldText(field)?.let { keyboardManager.setFieldText(field, it.selectAll()) }
            return
        }
        editorInstance.performClipboardSelectAll()
    }

    /**
     * An app's field is read back from the app once it has selected everything; see
     * [dev.patrickgold.florisboard.ime.editor.EditorInstance.selectAllAndRead].
     */
    override suspend fun selectWholeField(): String? =
        if (field() != null) super.selectWholeField() else editorInstance.selectAllAndRead()

    override fun textBeforeCursor(length: Int): String? {
        field()?.let { field ->
            val current = keyboardManager.fieldText(field) ?: return null
            return current.text.substring(0, current.cursor.coerceIn(0, current.text.length)).takeLast(length)
        }
        return editorInstance.activeContent.textBeforeSelection.takeLast(length)
    }

    override fun textAfterCursor(length: Int): String? {
        field()?.let { field ->
            val current = keyboardManager.fieldText(field) ?: return null
            val end = current.selection?.last ?: current.cursor
            return current.text.substring(end.coerceIn(0, current.text.length)).take(length)
        }
        return editorInstance.activeContent.textAfterSelection.take(length)
    }

    override fun performEnter(): Boolean {
        val keyboardManager by appContext.keyboardManager()
        // Dispatches a real Enter key event so it reuses the keyboard's full enter logic (editor action,
        // newline, …) rather than committing a literal "\n". A key event is delivered unconditionally,
        // so unlike the overlay there is nothing here that can refuse it.
        keyboardManager.inputEventDispatcher.sendDownUp(EnterKeyData)
        return true
    }

    override fun deleteLastText(text: String): Boolean {
        if (text.isEmpty()) return false
        field()?.let { field ->
            val current = keyboardManager.fieldText(field) ?: return false
            if (!current.text.substring(0, current.cursor).endsWith(text)) return false
            keyboardManager.setFieldText(field, current.replaceBeforeCursor(text.length, ""))
            return true
        }
        // Only undo when the characters right before the cursor are exactly what we inserted.
        if (!editorInstance.activeContent.textBeforeSelection.endsWith(text)) return false
        val keyboardManager by appContext.keyboardManager()
        // Reuse the keyboard's own delete handling (one backspace per character).
        repeat(text.length) { keyboardManager.inputEventDispatcher.sendDownUp(TextKeyData.DELETE) }
        return true
    }

    override fun setDictationPreview(newText: String, prevText: String) {
        val surface = previewSurface(prevText)
        if (surface is PreviewSurface.Field) {
            applyFieldDiff(surface.field, prevText, newText)
        } else {
            applyDictationDiff(if (surface == PreviewSurface.AppFresh) "" else prevText, newText)
        }
    }

    override fun commitDictationFinal(finalText: String, prevText: String): Boolean {
        val surface = previewSurface(prevText)
        activePreview = null
        if (surface is PreviewSurface.Field) {
            applyFieldDiff(surface.field, prevText, finalText)
            return true
        }
        // Atomic swap of the streamed preview for the finished/reworded text (keeps the common prefix,
        // replaces only the divergent tail in one batch → no character-by-character flicker).
        val shown = if (surface == PreviewSurface.AppFresh) "" else prevText
        if (shown == finalText) return true
        val cp = shown.commonPrefixWith(finalText).length
        editorInstance.replaceTextBeforeCursor(shown.length - cp, finalText.substring(cp))
        return true
    }

    override fun clearDictationPreview(prevText: String) {
        val surface = previewSurface(prevText)
        activePreview = null
        if (prevText.isEmpty()) return
        when (surface) {
            is PreviewSurface.Field -> applyFieldDiff(surface.field, prevText, "")
            // Atomic delete of the whole streamed preview in one batch. Doing this per-character (backspaces)
            // ANRs and can kill the keyboard when a long dictation is cancelled mid-recording.
            PreviewSurface.App -> if (appHolds(prevText)) editorInstance.replaceTextBeforeCursor(prevText.length, "")
            PreviewSurface.AppFresh -> Unit
        }
    }

    override fun holdsPreview(prevText: String): Boolean {
        if (prevText.isEmpty()) return true
        return when (val surface = activePreview ?: field()?.let { PreviewSurface.Field(it) } ?: PreviewSurface.App) {
            // A field that has closed under its preview is not an edit: the preview carries on in the app
            // as if it had just started there (see [previewSurface]).
            is PreviewSurface.Field -> keyboardManager.fieldText(surface.field)
                ?.let { it.text.substring(0, it.cursor).endsWith(prevText) } ?: true
            PreviewSurface.App -> appHolds(prevText)
            PreviewSurface.AppFresh -> true
        }
    }

    /**
     * The app's field read back from the editor: the whole preview has to be there, character for
     * character. A field that cannot be read is taken to hold it, as before.
     */
    private fun appHolds(prevText: String): Boolean =
        editorInstance.textBeforeCursorFromEditor(prevText.length)?.endsWith(prevText) ?: true

    /**
     * Where the running live preview is being shown. Decided when it starts and kept until it ends, so a
     * field opened or closed mid-dictation never has the preview's diff applied to text it was not
     * written into: that would delete the user's own words. A preview whose field closed under it carries
     * on in the app as if it had just started there ([PreviewSurface.AppFresh]).
     */
    private fun previewSurface(prevText: String): PreviewSurface {
        val current = activePreview
        val surface = when {
            prevText.isEmpty() || current == null -> field()?.let { PreviewSurface.Field(it) } ?: PreviewSurface.App
            current is PreviewSurface.Field && keyboardManager.fieldText(current.field) == null -> PreviewSurface.AppFresh
            else -> current
        }
        activePreview = if (surface == PreviewSurface.AppFresh) PreviewSurface.App else surface
        return surface
    }

    /** The preview's diff applied to the tail of a field's text in front of its cursor. */
    private fun applyFieldDiff(field: KeyboardManager.InternalField, old: String, new: String) {
        val current = keyboardManager.fieldText(field) ?: return
        val shown = if (current.text.substring(0, current.cursor).endsWith(old)) old else ""
        if (shown == new) return
        val cp = shown.commonPrefixWith(new).length
        keyboardManager.setFieldText(field, current.replaceBeforeCursor(shown.length - cp, new.substring(cp)))
    }

    /**
     * Turns the currently-shown dictation text [old] into [new] with the minimal edit: keep the common
     * prefix, delete the divergent tail (right before the cursor) and commit the new tail. Uses the
     * editor's own commit/delete so it stays consistent with the content model (no composing-region
     * collision). Append-only streaming (the common case) never deletes.
     */
    private fun applyDictationDiff(old: String, new: String) {
        if (old == new) return
        val cp = old.commonPrefixWith(new).length
        val deleteLen = old.length - cp
        if (deleteLen == 0) {
            // Pure append (the common streaming case): editor-consistent raw commit (no phantom/auto space
            // so the field stays byte-identical to what we tracked), no composing-region collision.
            editorInstance.commitTextRaw(new.substring(cp))
        } else {
            // A revision (provider rewrote the tail): replace it in one atomic batch, never per-character.
            editorInstance.replaceTextBeforeCursor(deleteLen, new.substring(cp))
        }
    }

    private sealed interface PreviewSurface {
        data object App : PreviewSurface
        /** The preview's field closed under it: the app has none of it yet. */
        data object AppFresh : PreviewSurface
        data class Field(val field: KeyboardManager.InternalField) : PreviewSurface
    }

    private companion object {
        /** The live preview's surface; a sink is created per call, so it lives here (see [previewSurface]). */
        @Volatile private var activePreview: PreviewSurface? = null

        /** Synthetic Enter key dispatched for auto-enter; reuses the keyboard's full enter logic. */
        private val EnterKeyData =
            TextKeyData(type = KeyType.ENTER_EDITING, code = KeyCode.ENTER, label = "enter")
    }
}
