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

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Optional accessibility service that powers the floating dictation button (issue #88). It does two
 * things the keyboard cannot do from outside an active IME:
 *
 *  1. **Detect** when an editable text field holds input focus in *any* app, so the floating button can
 *     appear only when there is somewhere to dictate into ([editableFocused]).
 *  2. **Inject** the transcribed text into that focused field ([injectText]) — the equivalent of the
 *     IME's `commitText`, but driven from the overlay where no InputConnection exists.
 *
 * The service is entirely opt-in: it does nothing until the user enables both the floating-button
 * feature and this service in the system accessibility settings. It only ever reads the *focused*
 * field (to know it is editable and to place text at the cursor); it does not collect screen content.
 *
 * It also owns the floating bubble ([DictateBubbleController]) and promotes itself to a microphone
 * foreground service while a bubble-driven dictation records, so background mic capture is allowed.
 */
class DictateAccessibilityService : AccessibilityService() {

    private var bubble: DictateBubbleController? = null
    private var isForeground = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Coalesce selection re-checks. Selection-changed events can arrive on every keystroke, but the
    // expensive part of updateEditableFocus() — fetching the focused node's full AccessibilityNodeInfo
    // over IPC — only needs to run once a burst settles: the editable-focus state does not change while
    // typing in the same field. Debouncing only this noisy event removes the per-keystroke IPC flood
    // without delaying the bubble after a real focus or window change (#222).
    private val focusUpdateRunnable = Runnable { updateEditableFocus() }

    private fun scheduleFocusUpdate() {
        mainHandler.removeCallbacks(focusUpdateRunnable)
        mainHandler.postDelayed(focusUpdateRunnable, FOCUS_UPDATE_DEBOUNCE_MS)
    }

    /**
     * Runs a focus check as soon as Android tells us that the input target or window changed. Any pending
     * selection debounce is stale at that point, so cancel it rather than letting an old callback delay or
     * overwrite this state. These event types are not emitted for every typed character, unlike selection
     * changes, so the immediate IPC is both safe and necessary for a responsive overlay.
     */
    private fun updateEditableFocusImmediately() {
        mainHandler.removeCallbacks(focusUpdateRunnable)
        updateEditableFocus()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        flogDebug { "DictateAccessibilityService connected" }
        createNotificationChannel()
        bubble = DictateBubbleController(this).also { it.start() }
        updateEditableFocus()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(focusUpdateRunnable)
        clearInstance()
        super.onDestroy()
    }

    override fun onInterrupt() {
        // No ongoing feedback to interrupt.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            // Note: TYPE_WINDOW_CONTENT_CHANGED is intentionally absent (and not subscribed in the
            // service config): it fires on every keystroke and made updateEditableFocus() re-fetch the
            // whole focused AccessibilityNodeInfo per character — a per-keystroke IPC flood that caused
            // typing jank. Focus/editability only change on the events below, so we lose nothing.
            // A focus/click or window transition is exactly when the bubble should appear or disappear.
            // Do not route these through the typing-oriented debounce: it used to add 150 ms to every
            // transition on top of the accessibility framework's notification timeout (#222).
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> updateEditableFocusImmediately()
            // This is the only subscribed event which can arrive for every keystroke. Keep it coalesced
            // so caret moves and text selection do not cause a focused-node IPC round trip per character.
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> scheduleFocusUpdate()
        }
    }

    /** The currently input-focused node if it is an editable text field, else null. */
    private fun focusedEditableNode(): AccessibilityNodeInfo? {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (node.isLikelyEditable()) return node
        // findFocus sometimes returns a container that merely *holds* the editable view (common in
        // wrapped/cross-platform UIs); descend to the first editable descendant.
        return findEditableDescendant(node, 0)
    }

    /** Depth-first search under [node] for the first editable descendant, bounded to avoid deep trees. */
    private fun findEditableDescendant(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth >= MAX_EDITABLE_SEARCH_DEPTH) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isLikelyEditable()) return child
            findEditableDescendant(child, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * A node we should treat as a dictation target. [isEditable] is the canonical flag, but several apps
     * never set it on otherwise-editable fields; fall back to the EditText class hierarchy and to the
     * field advertising the text-editing actions, so detection is not limited to the few well-behaved apps.
     */
    private fun AccessibilityNodeInfo.isLikelyEditable(): Boolean {
        if (isEditable) return true
        if (className?.toString()?.contains("EditText") == true) return true
        val actions = actionList
        return actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT) &&
            actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION)
    }

    /**
     * Whether a soft keyboard (any IME) is currently shown on screen. This is the most reliable proxy for
     * "a keyboard would normally be extended here", independent of whether the focused field reports itself
     * as editable, so the bubble appears in the same situations a keyboard does. Requires
     * `flagRetrieveInteractiveWindows`, which the service config sets.
     */
    private fun isImeWindowShown(): Boolean = runCatching {
        windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(false)

    private fun updateEditableFocus() {
        // Show the bubble whenever there is somewhere to dictate: either an editable field holds focus, or a
        // soft keyboard is physically out (covers apps whose fields don't report an accessible editable focus).
        val imeShown = isImeWindowShown()
        val focused = focusedEditableNode() != null || imeShown
        if (_editableFocused.value != focused) {
            _editableFocused.value = focused
            flogDebug { "editable field focused = $focused" }
        }
        if (_imeVisible.value != imeShown) {
            _imeVisible.value = imeShown
            flogDebug { "IME window visible = $imeShown" }
        }
        val dictateKeyboard = isDictateKeyboardActive()
        if (_dictateKeyboardActive.value != dictateKeyboard) {
            _dictateKeyboardActive.value = dictateKeyboard
            flogDebug { "Dictate keyboard active = $dictateKeyboard" }
        }
        val pkg = currentAppPackage()
        if (!pkg.isNullOrEmpty() && pkg != packageName && _foregroundPackage.value != pkg) {
            _foregroundPackage.value = pkg
            flogDebug { "foreground app = $pkg" }
        }
    }

    /**
     * The package of the foreground *application* window (ignoring IME/system windows), for per-app bubble
     * positioning. Reading it from the focused application window avoids the churn of TYPE_WINDOW_STATE_CHANGED
     * events that fire for the keyboard and transient popups with their own package names.
     */
    private fun currentAppPackage(): String? = runCatching {
        val fromAppWindow = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.isFocused }
            .firstOrNull()
            ?.root?.packageName?.toString()
        fromAppWindow ?: rootInActiveWindow?.packageName?.toString()
    }.getOrNull()

    /** Whether the Dictate keyboard itself is the currently selected input method (handles .debug). */
    private fun isDictateKeyboardActive(): Boolean {
        val current = Settings.Secure.getString(
            contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD,
        ) ?: return false
        // DEFAULT_INPUT_METHOD is "<package>/<service-class>"; the package is our applicationId.
        return current.substringBefore('/') == packageName
    }

    /**
     * Inserts [text] into the focused editable field at the cursor, replacing the active selection
     * (matching the IME `commitText` semantics) and placing the cursor right after the inserted text.
     * Falls back to appending at the end when the field reports no usable selection. Returns true when
     * the field accepted the change — some custom/legacy views do not support `ACTION_SET_TEXT`.
     */
    private fun commitTextIntoFocused(text: String): Boolean {
        if (text.isEmpty()) return true // silence: nothing to insert — a no-op is a success, not a failure.

        // Insert exactly like a normal keyboard: through the accessibility input connection's commitText,
        // straight into the field at the cursor — no clipboard, no toast, and it never prepends a shown
        // placeholder (e.g. WhatsApp's "Message"). The stability trick is to first resolve the field the
        // user actually SEES (fresh from the live window, so a recreated/stale field can't swallow the
        // text) and, if it isn't already focused, give it input focus. Focusing the visible field points
        // the input connection at THAT field instead of an editor the app discarded — the root of the old
        // "green check, no text" flakiness. A short retry covers the instant right after a send when the
        // host app is still rebuilding its field. Node ACTION_SET_TEXT / clipboard paste stay only as
        // fallbacks for the rare fields that accept no input connection (old OS, some WebView/custom views).
        repeat(COMMIT_ATTEMPTS) { attempt ->
            val target = activeWindowEditable()
            if (target != null && !target.isFocused) {
                runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
                runCatching { target.refresh() }
                // Let the input connection rebind to the field we just focused before we commit into it.
                SystemClock.sleep(FOCUS_SETTLE_MS)
            }

            // 1. The keyboard-style path: commit through the input connection (no clipboard, no toast).
            if (commitViaInputConnection(text)) {
                flogDebug { "commit via inputConnection len=${text.length}" }
                return true
            }
            // 2. Fallback: write straight into the visible node (older OS without the a11y input method, or
            //    fields that expose no editor connection). Placeholder-safe via editableText().
            if (target != null && setTextOnFocused(target, text)) {
                flogDebug { "commit via setText len=${text.length}" }
                return true
            }
            // 3. Last resort only: clipboard paste (WebView/custom inputs that ignore both). The paste
            //    toast is therefore the rare exception, never the normal case.
            if (target != null && pasteIntoFocused(target, text)) {
                flogDebug { "commit via paste len=${text.length}" }
                return true
            }
            if (attempt < COMMIT_ATTEMPTS - 1) SystemClock.sleep(COMMIT_RETRY_DELAY_MS)
        }
        flogDebug { "commit FAILED after $COMMIT_ATTEMPTS attempts len=${text.length}" }
        return false
    }

    /**
     * The editable field in the currently active (visible) window, located fresh from the live node tree
     * via [rootInActiveWindow]. Used when the cached input focus is stale — e.g. the host app recreated
     * its input after sending a message — so dictation lands in the field the user actually sees rather
     * than a detached one (#132 follow-up).
     */
    private fun activeWindowEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
            if (focused.isLikelyEditable()) return focused
            findEditableDescendant(focused, 0)?.let { return it }
        }
        if (root.isLikelyEditable()) return root
        return findEditableDescendant(root, 0)
    }

    /**
     * Commits [text] through the accessibility [android.accessibilityservice.InputMethod] input connection
     * (API 33+). Requires the `flagInputMethodEditor` accessibility flag. Returns false when unavailable
     * (older OS, or no editor currently bound), so the caller falls back to the node-based methods.
     */
    private fun commitViaInputConnection(text: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val connection = inputMethod?.currentInputConnection ?: return false
        return runCatching {
            connection.commitText(text, 1, null)
            true
        }.getOrDefault(false)
    }

    /**
     * Inserts [text] via [AccessibilityNodeInfo.ACTION_SET_TEXT], reconstructing the field content around the
     * cursor/selection. A shown placeholder is treated as empty (see [editableText]) so it is not prepended.
     * Returns false when the field does not accept the action, so the caller can fall back to pasting.
     */
    private fun setTextOnFocused(node: AccessibilityNodeInfo, text: String): Boolean {
        val existing = node.editableText()
        val from = node.textSelectionStart.coerceForText(existing)
        val to = node.textSelectionEnd.coerceForText(existing)
        val start = minOf(from, to)
        val end = maxOf(from, to)
        val newText = existing.substring(0, start) + text + existing.substring(end)
        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
        if (ok) {
            val cursor = start + text.length
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        }
        return ok
    }

    /**
     * Inserts [text] by putting it on the clipboard and performing [AccessibilityNodeInfo.ACTION_PASTE] on
     * the focused field, then restoring the user's previous clipboard shortly after. Returns false when the
     * field does not advertise the paste action, so the caller can fall back to ACTION_SET_TEXT.
     *
     * Pasting inserts into the field's real content (so a shown placeholder is never prepended) and works in
     * WebView/browser inputs that ignore ACTION_SET_TEXT. We cannot verify the write by reading the clipboard
     * back (a background app's clipboard read is blocked on Android 10+ and returns null), so we trust the
     * write; if it were blocked the field simply would not receive our text and the user would re-try.
     */
    private fun pasteIntoFocused(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE)) return false
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
        val previous = runCatching { clipboard.primaryClip }.getOrNull()
        runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("dictate", text)) }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (ok) {
            // Restore the previous clipboard once the target app has consumed the paste, so we do not
            // clobber whatever the user had copied.
            mainHandler.postDelayed({
                runCatching {
                    clipboard.setPrimaryClip(previous ?: ClipData.newPlainText("", ""))
                }
            }, CLIPBOARD_RESTORE_DELAY_MS)
        }
        return ok
    }

    /**
     * Removes the last inserted [text] from the focused field again (undo, issue #133). Prefers the
     * accessibility input connection (API 33+) when the characters right before the cursor are exactly
     * [text]; otherwise removes the matching region — the window ending at the cursor if it matches,
     * else the last occurrence — via [AccessibilityNodeInfo.ACTION_SET_TEXT]. Returns true on success.
     */
    private fun deleteLastTextFromFocused(text: String): Boolean {
        if (text.isEmpty()) return false
        // Reconstruct the field without the inserted text via ACTION_SET_TEXT (works pre-API-33 too and
        // lets us verify the match first, so the user's own edits are never eaten).
        val node = focusedEditableNode() ?: return false
        node.refresh()
        val existing = node.editableText()
        val cursor = node.textSelectionEnd.coerceForText(existing)
        val start = when {
            cursor >= text.length && existing.regionMatches(cursor - text.length, text, 0, text.length) ->
                cursor - text.length
            existing.contains(text) -> existing.lastIndexOf(text)
            else -> return false
        }
        val newText = existing.removeRange(start, start + text.length)
        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) return false
        val selArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, start)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        flogDebug { "deleteLastText via setText len=${text.length}" }
        return true
    }

    // --- Real-time dictation preview via the overlay (issue #128) ---------------------------------
    // Live streaming into another app's field over AccessibilityService. Each accessibility write costs a
    // node fetch + set, so preview updates are throttled and applied as a minimal diff (delete the changed
    // tail, insert the new tail) rather than re-setting the whole field. [previewShown] tracks exactly what
    // we have injected so the diff stays correct even when throttling skips intermediate updates.
    private var previewShown = ""
    private var lastPreviewMs = 0L

    private fun applyPreviewDiff(old: String, new: String) {
        if (old == new) return
        val cp = old.commonPrefixWith(new).length
        if (cp < old.length) deleteLastTextFromFocused(old.substring(cp))
        if (cp < new.length) commitTextIntoFocused(new.substring(cp))
    }

    private fun setPreviewThrottled(full: String) {
        if (full == previewShown) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPreviewMs < PREVIEW_THROTTLE_MS) return   // skip; a later update catches up via the diff
        applyPreviewDiff(previewShown, full)
        previewShown = full
        lastPreviewMs = now
    }

    private fun commitPreviewFinalOnFocused(finalText: String) {
        applyPreviewDiff(previewShown, finalText)   // no throttle — final result always lands
        previewShown = ""
        lastPreviewMs = 0L
    }

    private fun clearPreviewOnFocused() {
        if (previewShown.isNotEmpty()) applyPreviewDiff(previewShown, "")
        previewShown = ""
        lastPreviewMs = 0L
    }

    /** The selected text in the focused editable field, or empty when nothing is selected. */
    private fun selectedTextOfFocused(): String {
        val node = focusedEditableNode() ?: return ""
        node.refresh()
        val text = node.editableText()
        if (text.isEmpty()) return ""
        val from = node.textSelectionStart
        val to = node.textSelectionEnd
        if (from < 0 || to < 0 || from == to) return ""
        val start = minOf(from, to).coerceIn(0, text.length)
        val end = maxOf(from, to).coerceIn(0, text.length)
        return text.substring(start, end)
    }

    /** The full text of the focused editable field, or empty when there is none. */
    private fun fullTextOfFocused(): String {
        val node = focusedEditableNode() ?: return ""
        node.refresh()
        return node.editableText()
    }

    /** Selects the whole field so a subsequent inject replaces it. Returns true on success. */
    private fun selectAllInFocused(): Boolean {
        val node = focusedEditableNode() ?: return false
        node.refresh()
        val len = node.editableText().length
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    /**
     * The field's real text, treating a shown hint/placeholder (e.g. WhatsApp's "Message") as empty so
     * the injected text never gets prepended to the placeholder.
     *
     * [AccessibilityNodeInfo.getText] returns the hint verbatim for an empty field. The documented way to
     * tell them apart is [isShowingHintText], but it is not reliable across apps — WhatsApp's compose field
     * returns its "Message" placeholder as `text` *without* setting the flag. So we additionally treat the
     * text as empty when it is identical to the node's declared [getHintText]; the (self-correcting) cost
     * is that a field whose real content exactly equals its placeholder is seen as empty.
     */
    private fun AccessibilityNodeInfo.editableText(): String {
        if (isShowingHintText) return ""
        val raw = text?.toString() ?: ""
        if (raw.isEmpty()) return ""
        // Treat the text as empty when it merely echoes the declared hint/placeholder (some apps return the
        // placeholder as text without setting isShowingHintText). Only hintText is used here — matching
        // against contentDescription is unsafe because some apps mirror the real content there, which would
        // make us drop existing text when appending.
        val hint = hintText?.toString()?.trim()
        if (!hint.isNullOrEmpty() && hint == raw.trim()) return ""
        return raw
    }

    /**
     * Presses the editor action / Enter on the focused field (auto-enter). Uses the proper IME-enter
     * action on Android 11+; on older releases there is no editor-action equivalent, so it falls back
     * to inserting a newline.
     */
    private fun performEnterOnFocused(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val node = focusedEditableNode() ?: return false
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else {
            commitTextIntoFocused("\n")
        }
    }

    /** Clamps a selection index into [0, length]; a missing index (-1) maps to the end (append). */
    private fun Int.coerceForText(text: String): Int =
        if (this in 0..text.length) this else text.length

    private fun clearInstance() {
        if (instance === this) {
            instance = null
            _editableFocused.value = false
            _dictateKeyboardActive.value = false
            _imeVisible.value = false
            bubble?.destroy()
            bubble = null
            mainHandler.removeCallbacksAndMessages(null)
            stopMicForeground()
            flogDebug { "DictateAccessibilityService disconnected" }
        }
    }

    // --- Microphone foreground (while-in-background recording, Android 14+) ----------------------

    /**
     * Promotes the (already running, system-bound) service to a microphone foreground service so the
     * recording started from the floating button is allowed while the app is in the background. Promoting
     * an existing service sidesteps the "start a foreground service from the background" restriction.
     */
    fun startMicForeground() {
        if (isForeground) return
        val notification = buildNotification(getString(R.string.dictate__overlay_notification_recording))
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            isForeground = true
        }
    }

    /** Drops the microphone foreground state once the dictation has finished. */
    fun stopMicForeground() {
        if (!isForeground) return
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        isForeground = false
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_dictate_overlay_mic)
            .setContentTitle(getString(R.string.floris_app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NOTIF_CHANNEL) != null) return
        val channel = NotificationChannel(
            NOTIF_CHANNEL,
            getString(R.string.dictate__overlay_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val NOTIF_ID = 0xD1C7
        private const val NOTIF_CHANNEL = "dictate_overlay_recording"
        private const val CLIPBOARD_RESTORE_DELAY_MS = 400L
        private const val MAX_EDITABLE_SEARCH_DEPTH = 6
        // Floating-button commit reliability (#161): resolve + focus the field the user sees so the input
        // connection binds to it, retry briefly while the host app rebuilds its field right after a send.
        private const val COMMIT_ATTEMPTS = 2
        private const val COMMIT_RETRY_DELAY_MS = 60L
        private const val FOCUS_SETTLE_MS = 40L
        // Debounce window for focus re-checks so a typing burst triggers at most one focused-node fetch.
        private const val FOCUS_UPDATE_DEBOUNCE_MS = 150L
        // Real-time overlay preview (#128): min gap between accessibility writes while streaming, so live
        // typing into another app doesn't flood the accessibility channel. 0 = apply every update (tested
        // to work smoothly in practice; raise if a target app can't keep up).
        private const val PREVIEW_THROTTLE_MS = 0L

        @Volatile
        private var instance: DictateAccessibilityService? = null

        /** Whether the service is connected and able to detect focus / inject text. */
        val isRunning: Boolean
            get() = instance != null

        private val _editableFocused = MutableStateFlow(false)

        /** Whether an editable text field currently holds input focus anywhere on screen. */
        val editableFocused: StateFlow<Boolean> = _editableFocused.asStateFlow()

        private val _dictateKeyboardActive = MutableStateFlow(false)

        /** Whether the Dictate keyboard is the currently selected input method. */
        val dictateKeyboardActive: StateFlow<Boolean> = _dictateKeyboardActive.asStateFlow()

        private val _imeVisible = MutableStateFlow(false)

        /** Whether a soft-keyboard (IME) window is currently shown on screen. */
        val imeVisible: StateFlow<Boolean> = _imeVisible.asStateFlow()

        private val _foregroundPackage = MutableStateFlow<String?>(null)

        /** Package name of the current foreground app, for per-app bubble positioning. */
        val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

        /**
         * Inserts [text] into the focused editable field via the running service, returning true on
         * success. Returns false when the service is not running or no editable field is focused.
         */
        fun injectText(text: String): Boolean = instance?.commitTextIntoFocused(text) ?: false

        /** The selection in the focused field, or empty when the service is unavailable. */
        fun selectedText(): String = instance?.selectedTextOfFocused() ?: ""

        /** The full text of the focused field, or empty when the service is unavailable. */
        fun fullText(): String = instance?.fullTextOfFocused() ?: ""

        /** Selects the whole focused field; false when the service is unavailable. */
        fun selectAll(): Boolean = instance?.selectAllInFocused() ?: false

        /** Presses Enter / the editor action on the focused field; false when unavailable. */
        fun performEnter(): Boolean = instance?.performEnterOnFocused() ?: false

        /** Removes the last inserted [text] from the focused field (undo, #133); false when unavailable. */
        fun deleteLastText(text: String): Boolean = instance?.deleteLastTextFromFocused(text) ?: false

        /** Real-time overlay preview (#128): throttled live update of the streamed text into the field. */
        fun setPreview(full: String) { instance?.setPreviewThrottled(full) }

        /** Replace the live preview with the finished/reworded [finalText] (unthrottled). */
        fun commitPreviewFinal(finalText: String) { instance?.commitPreviewFinalOnFocused(finalText) }

        /** Remove the live preview entirely (recording cancelled). */
        fun clearPreview() { instance?.clearPreviewOnFocused() }
    }
}
