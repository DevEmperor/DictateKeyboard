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
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.florisboard.lib.android.systemService

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

    /** Listens for the display going dark and coming back; see [refreshScreenState] for why (#269). */
    private var screenReceiver: BroadcastReceiver? = null

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
        registerScreenReceiver()
        refreshScreenState()
        bubble = DictateBubbleController(this).also { it.start() }
        updateEditableFocus()
    }

    /**
     * The screen changed shape: a rotation, a foldable being opened or closed, a move into or out of split
     * screen. The bubble is a window with raw coordinates on a screen that just became a different size,
     * so it has to be put back where the user meant it (issue #323) — nothing did that before.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bubble?.onScreenGeometryChanged()
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

    /**
     * The currently input-focused node if it is an editable text field, else null. Asks whether there is
     * somewhere to dictate at all, which is what decides whether the bubble is shown.
     *
     * Deliberately the single lookup rather than [dictationTarget]'s two: this runs on every focus and
     * window event, and the second lookup would be paid on exactly the events where there is no field —
     * the common case while browsing. Writing is rare and can afford to ask twice; showing is not.
     */
    private fun focusedEditableNode(): AccessibilityNodeInfo? =
        editableUnderFocus(findFocus(AccessibilityNodeInfo.FOCUS_INPUT))

    /** [targetUnderFocus] over accessibility nodes, with this service's editability heuristic. */
    private fun editableUnderFocus(focused: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        targetUnderFocus(
            focused = focused,
            editable = { it.isLikelyEditable() },
            children = { node -> (0 until node.childCount).mapNotNull { node.getChild(it) } },
        )

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

    /**
     * Starts listening for the display turning off and on. Both actions are sent to runtime-registered
     * receivers only — a manifest entry would never fire — and they are protected system broadcasts, so the
     * receiver is registered as not exported. Registered for as long as the service lives, unlike the
     * screen-off listener in `DictateController` (#147), which only runs during a recording.
     */
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refreshScreenState()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        }.isSuccess
        if (registered) screenReceiver = receiver
    }

    /** Stops listening. Idempotent, and safe to call from both teardown paths. */
    private fun unregisterScreenReceiver() {
        val receiver = screenReceiver ?: return
        screenReceiver = null
        runCatching { unregisterReceiver(receiver) }
    }

    /**
     * Publishes whether the display is interactive right now (#269).
     *
     * The floating button lives in a [android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]
     * window, a layer that deliberately outlives the keyguard so an accessibility tool keeps working there.
     * The platform therefore never takes it down for us, and no accessibility event announces a screen going
     * dark — so without this the last "a text field is focused" survives into the always-on display, and the
     * button is still sitting on a phone the user believes to be off.
     *
     * Cheap enough to call on every focus update as well: a single binder call for a boolean, not the node
     * tree fetch that made typing janky in #222. That second caller is the safety net — if an
     * [Intent.ACTION_SCREEN_ON] is ever missed, the next accessibility event brings the button back rather
     * than leaving it gone for the rest of the session.
     */
    private fun refreshScreenState() {
        val on = runCatching { systemService(PowerManager::class).isInteractive }.getOrDefault(true)
        if (_screenOn.value != on) {
            _screenOn.value = on
            flogDebug { "screen interactive = $on" }
        }
    }

    private fun updateEditableFocus() {
        // Re-read the screen here too, not only from the broadcast: every path that can make the bubble
        // appear runs through this method, so a missed ACTION_SCREEN_ON heals on the next event instead of
        // hiding the button for the rest of the session (#269).
        refreshScreenState()
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
    private fun commitTextIntoFocused(text: String, verify: Boolean = true): Boolean {
        if (text.isEmpty()) return true // silence: nothing to insert — a no-op is a success, not a failure.

        // Insert exactly like a normal keyboard: through the accessibility input connection's commitText,
        // straight into the field at the cursor — no clipboard, no toast, and it never prepends a shown
        // placeholder (e.g. WhatsApp's "Message"). That path needs no node of its own: it addresses
        // whatever the system holds as the input target, which is the field the user tapped. The node
        // [dictationTarget] resolves is for the two fallbacks below and for one nudge — a field that holds
        // focus without admitting it gets ACTION_FOCUS, because Compose fields refuse ACTION_SET_TEXT
        // otherwise (#156). A short retry covers the instant right after a send when the host app is still
        // rebuilding its field, which is what used to end as "green check, no text" (#132, #161).
        //
        // Nothing here goes looking for a field the user is *not* in. That search is what put dictations in
        // Chrome's address bar (#310); see [dictationTarget].
        repeat(COMMIT_ATTEMPTS) { attempt ->
            val target = dictationTarget()
            if (target != null && !target.isFocused) {
                // Only ever a node inside the focused subtree — [dictationTarget] cannot return anything
                // else — so this asks a field that already has focus to admit it, which is what Compose
                // fields need before they accept ACTION_SET_TEXT (#156). It can no longer move the user's
                // cursor to a different field (#310).
                runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
                runCatching { target.refresh() }
                // Let the input connection rebind to the field we just focused before we commit into it.
                SystemClock.sleep(FOCUS_SETTLE_MS)
            }
            // A field belonging to the keyboard itself is not what the input connection addresses, so
            // that path would write into the host app instead. Go through the node for it (#310).
            val viaNodeOnly = target != null && isInsideImeWindow(target)

            // 1. The keyboard-style path: commit through the input connection (no clipboard, no toast).
            //    Its API returns void, so "it did not throw" is all the call itself tells us — hence the
            //    read-back below (issue #277).
            val beforeText = if (verify) readNodeText(target) else null
            val before = if (verify && !viaNodeOnly) readBeforeCursor(text.length) else null
            if (!viaNodeOnly && commitViaInputConnection(text)) {
                if (!verify || landedInField(target, beforeText, before, text.length)) {
                    flogDebug { "commit via inputConnection len=${text.length}" }
                    return true
                }
                if (beforeText == null) {
                    // No node to ask, so the only verdict available came from the connection — and a
                    // connection cannot tell "written into a dead editor" from "not written". Do not write
                    // again on a verdict that weak: if it were wrong, the text would end up in the field
                    // twice, which is worse than a wrong error message. The caller puts it on the
                    // clipboard instead.
                    flogDebug { "commit swallowed, no node to recover through len=${text.length}" }
                    return false
                }
                // The *field* — the thing the user is looking at — demonstrably did not change, so the
                // write went somewhere invisible and falling through cannot duplicate anything. This is
                // the long-standing "green check but no text" (#132, #156, #277): the read-back went
                // through the same connection as the write, so it faithfully reported the text it had
                // just put into an editor the app had already discarded.
                flogDebug { "commit swallowed by the field, recovering via the node len=${text.length}" }
            }
            // 2. Fallback: write straight into the visible node (older OS without the a11y input method, or
            //    fields that expose no editor connection). Placeholder-safe via editableText().
            if (target != null && setTextOnFocused(target, text)) {
                // ACTION_SET_TEXT returning true means the action was accepted, not that the text stuck —
                // the same void-return problem one level down, and the reason this path used to report
                // success unconditionally.
                // [beforeText] is still the right baseline: either path 1 never ran, or it ran and the
                // node proved it changed nothing.
                if (!verify || landedInField(target, beforeText, null, text.length)) {
                    flogDebug { "commit via setText len=${text.length}" }
                    return true
                }
                flogDebug { "setText accepted but changed nothing len=${text.length}" }
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
     * The field a dictation may be written into: **the one holding input focus, and nothing else.**
     *
     * Asked twice, because the two lookups can disagree about how current they are. The service-wide
     * [findFocus] is the system's own answer to "who has input focus". The second, through
     * [rootInActiveWindow], re-reads it from the window that is actually on screen — the point of the
     * #132 hardening, for the moment right after a host app recreates its field (WhatsApp clearing its
     * composer on send) and the first answer still describes the field it discarded.
     *
     * What it deliberately no longer does is *guess*. It used to fall back to walking the whole window
     * tree and taking the first editable node in it, which in Chrome is the address bar and in WhatsApp
     * the message box — and [commitTextIntoFocused] then pulled focus onto that node before writing, so
     * the dictation did not merely land in the wrong field, it moved the cursor there first (issue #310).
     * Neither reason the walk was added needs it: a recreated field holds input focus like any other, so
     * [findFocus] finds it.
     *
     * Null is a legitimate answer and not a dead end. The input-connection path in [commitTextIntoFocused]
     * needs no node at all — it writes to whatever the system holds as the input target, which is exactly
     * the field the user tapped — so a browser whose web field this cannot name is still served correctly.
     * Only if that path fails too does the caller fall back to the clipboard.
     */
    private fun dictationTarget(): AccessibilityNodeInfo? =
        editableUnderFocus(findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
            ?: editableUnderFocus(rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))

    /**
     * Whether [node] lives in a soft-keyboard window, i.e. a text field belonging to the IME itself —
     * Gboard's translate box being the case that prompted this (issue #310).
     *
     * It matters because the accessibility input connection does *not* point there: the IME's own field
     * is a private editor inside the keyboard, while the connection still addresses the host app. Writing
     * through it would put the text in the app's message box while the cursor sits in the translate box,
     * which is precisely the complaint. Such a field can only be served through the node itself.
     *
     * **Do not add a lookup that goes hunting for that field.** One was tried and measured on a device:
     * Gboard's translate window is `TYPE_INPUT_METHOD` with `fl=81800108`, and bit `0x8` of that is
     * `FLAG_NOT_FOCUSABLE`. A window that never takes window focus never holds input focus either — the
     * accessibility window itself reports `focused=false` — so a `FOCUS_INPUT` search inside a keyboard
     * window has nothing to return, for every keyboard, by construction. That is not a Gboard quirk: it
     * is what lets the app keep its InputConnection while the keyboard is on screen. The only route left
     * would be to walk the keyboard's tree and guess which of its boxes was meant, which is the search
     * that put dictations in Chrome's address bar, aimed this time at another app's private UI.
     *
     * This guard stays because it is still the right handling for a keyboard that *does* take focus — a
     * dialog or a fullscreen extract view — and there the global lookup finds the node on its own.
     */
    private fun isInsideImeWindow(node: AccessibilityNodeInfo): Boolean = runCatching {
        node.window?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
    }.getOrDefault(false)

    /**
     * The text immediately before the cursor, read back through the *same* input connection the write
     * goes through, or null when it cannot be read.
     *
     * Deliberately not the accessibility node: reading the node means `refresh()` plus [editableText]'s
     * placeholder heuristic, which reports a hint-showing field as empty — unreliable enough that an
     * earlier attempt at verifying writes that way produced false "couldn't insert" errors and was
     * abandoned. `getSurroundingText` asks the app's own editor instead, and arrived with API 33, which
     * is the same floor the write path already has.
     */
    private fun readBeforeCursor(sentLength: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val connection = inputMethod?.currentInputConnection ?: return null
        return runCatching {
            val window = connection.getSurroundingText(sentLength + VERIFY_WINDOW_PAD, 0, 0) ?: return null
            val text = window.text ?: return null
            val end = window.selectionStart.coerceIn(0, text.length)
            text.subSequence(0, end).toString()
        }.getOrNull()
    }

    /**
     * Whether the write reached the field. Reads back once, and — only if nothing changed — once more
     * after a short settle, because an app applies a commit on its own UI thread and may not have got
     * to it yet.
     */
    private fun insertLanded(before: String?, sentLength: Int): Boolean {
        if (insertLandedFrom(before, readBeforeCursor(sentLength))) return true
        SystemClock.sleep(VERIFY_SETTLE_MS)
        return insertLandedFrom(before, readBeforeCursor(sentLength))
    }

    /**
     * The field's text as the *user* sees it, or null when there is no node or it cannot be read.
     *
     * Deliberately the node and not the input connection: the connection is the very thing under
     * suspicion. A write that goes into an editor the app has already discarded is faithfully read back
     * through that same connection, which is why "green check but no text" survived the read-back
     * verification of #277 — it was never verifying what the user was looking at.
     *
     * Placeholder-safe through [editableText], and that is what makes the comparison work in the case it
     * exists for: a field showing its hint reads as empty both before and after a swallowed write, so
     * nothing changed; a field that took the text reads as its content, so something did.
     */
    private fun readNodeText(node: AccessibilityNodeInfo?): String? {
        val target = node ?: return null
        return runCatching {
            if (!target.refresh()) return null
            target.editableText()
        }.getOrNull()
    }

    /**
     * Whether the write reached the field, asking the node when there is one and the input connection
     * only when there is not.
     *
     * Both verdicts follow [insertLandedFrom]'s lopsided rule — only a demonstrably unchanged field is a
     * failure — so an unreadable node falls back to the connection rather than inventing a failure. The
     * settle covers an app that applies the write on its own UI thread a moment later.
     */
    private fun landedInField(
        node: AccessibilityNodeInfo?,
        beforeText: String?,
        beforeCursor: String?,
        sentLength: Int,
    ): Boolean {
        if (beforeText == null) return insertLanded(beforeCursor, sentLength)
        if (insertLandedFrom(beforeText, readNodeText(node))) return true
        SystemClock.sleep(VERIFY_SETTLE_MS)
        return insertLandedFrom(beforeText, readNodeText(node))
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
        val node = dictationTarget() ?: return false
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

    /** Returns whether the field took the new tail, so callers only advance [previewShown] when it did. */
    private fun applyPreviewDiff(old: String, new: String): Boolean {
        if (old == new) return true
        val cp = old.commonPrefixWith(new).length
        if (cp < old.length) deleteLastTextFromFocused(old.substring(cp))
        if (cp >= new.length) return true
        // Streaming writes a tail many times a second; a read-back per update would double the IPC and
        // fight the app's own rendering. The final commit is verified instead.
        return commitTextIntoFocused(new.substring(cp), verify = false)
    }

    private fun setPreviewThrottled(full: String) {
        if (full == previewShown) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPreviewMs < PREVIEW_THROTTLE_MS) return   // skip; a later update catches up via the diff
        // Only move the diff base when the write landed (issue #277). Advancing it regardless left the
        // base describing text that was never inserted, and every later diff was computed against that
        // fiction — so one refused write desynchronised the whole rest of the streaming session.
        if (applyPreviewDiff(previewShown, full)) previewShown = full
        lastPreviewMs = now
    }

    private fun commitPreviewFinalOnFocused(finalText: String): Boolean {
        val landed = applyPreviewDiff(previewShown, finalText)   // no throttle — final result always lands
        previewShown = ""
        lastPreviewMs = 0L
        return landed
    }

    private fun clearPreviewOnFocused() {
        if (previewShown.isNotEmpty()) applyPreviewDiff(previewShown, "")
        previewShown = ""
        lastPreviewMs = 0L
    }

    /** The selected text in the focused editable field, or empty when nothing is selected. */
    private fun selectedTextOfFocused(): String {
        val node = dictationTarget() ?: return ""
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
        val node = dictationTarget() ?: return ""
        node.refresh()
        return node.editableText()
    }

    /** Selects the whole field so a subsequent inject replaces it. Returns true on success. */
    private fun selectAllInFocused(): Boolean {
        val node = dictationTarget() ?: return false
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
     *
     * Unlike the keyboard, which dispatches a real key event, this can only *ask* — and an app that
     * implements no editor action refuses. Returns whether it was accepted (issue #278); the caller
     * reports rather than pretends.
     *
     * The field is resolved the same way [commitTextIntoFocused] resolves it, through [dictationTarget]:
     * the plain input-focus lookup used before could return a different node than the one the text just
     * went into, which is the staleness #161 fixed for the insert but not for Enter.
     */
    private fun performEnterOnFocused(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return commitTextIntoFocused("\n")
        val node = dictationTarget()
        if (node != null &&
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        ) {
            return true
        }
        // Second try through the input connection we already hold — the same call the keyboard path
        // makes (EditorInstance.performEnterAction). Some fields accept the editor action while
        // refusing the node action. The action is the one the field itself declares in its imeOptions
        // (Send in a chat box, Search in a search bar, …); a field declaring none gets nothing, since
        // guessing one would send a message the writer had not finished.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val method = inputMethod
            val connection = method?.currentInputConnection
            val action = method?.currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            if (connection != null && action != null &&
                action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
            ) {
                return runCatching {
                    connection.performEditorAction(action)
                    true
                }.getOrDefault(false)
            }
        }
        flogDebug { "auto-enter refused by the focused field" }
        return false
    }

    /** Clamps a selection index into [0, length]; a missing index (-1) maps to the end (append). */
    private fun Int.coerceForText(text: String): Int =
        if (this in 0..text.length) this else text.length

    private fun clearInstance() {
        if (instance === this) {
            instance = null
            // A bubble dictation belongs to this service, and nothing else ends it now that the keyboard
            // window closing no longer does (#293). Before the bubble and the microphone foreground go,
            // so the recorder closes cleanly and the audio is kept rather than lost.
            DictateController.stashRecordingOnOverlayGone(applicationContext)
            unregisterScreenReceiver()
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
        // Read-back verification of the input-connection write (#277). The window is a little wider than
        // what was sent so a field that reformats around it still reads as changed; the settle covers an
        // app that applies the commit on its own UI thread a moment later.
        private const val VERIFY_WINDOW_PAD = 16
        private const val VERIFY_SETTLE_MS = 50L

        /**
         * Whether a write is judged to have reached the field, given the text before the cursor as it was
         * [before] the write and as it reads [after] it.
         *
         * **Only a demonstrably unchanged field counts as a failure.** Anything else — either read
         * unavailable, an exception, text that grew, shrank, or was reformatted by the app — counts as
         * landed and leaves the behaviour exactly as it was before verification existed.
         *
         * Checking that the field now *ends with what we sent* would be the obvious rule and the wrong
         * one: apps capitalise, trim and reflow what they are given, and every one of those would read as
         * a failure. "Did anything change at all" survives all of it, and still catches the case this is
         * for — the write that is silently dropped, where nothing changes because nothing happened.
         */
        internal fun insertLandedFrom(before: String?, after: String?): Boolean =
            before == null || after == null || before != after

        /**
         * The node a dictation may be written into, given the input-[focused] one: that node when it is a
         * field, otherwise the first field beneath it, otherwise **nothing**.
         *
         * The rule is one sentence — *only what input focus points at* — and it is written as a function
         * over an abstract tree so that sentence can be tested without an Android node behind it. The bug
         * it exists to make impossible is issue #310: with no field found, the service used to walk the
         * whole window and take the first editable node anywhere in it, which is the address bar in a
         * browser and the message box in a chat app.
         *
         * Descending is still right, and is the reason this is not simply `focused`: cross-platform and
         * wrapped UIs routinely give focus to a container that merely holds the editable view. Everything
         * it returns is inside the focused subtree, so it can only ever name a field the user is already
         * in. [maxDepth] bounds the walk, since a deep tree is walked over IPC one node at a time.
         */
        internal fun <N : Any> targetUnderFocus(
            focused: N?,
            editable: (N) -> Boolean,
            children: (N) -> List<N>,
            maxDepth: Int = MAX_EDITABLE_SEARCH_DEPTH,
        ): N? {
            val node = focused ?: return null
            if (editable(node)) return node
            fun descend(from: N, depth: Int): N? {
                if (depth >= maxDepth) return null
                for (child in children(from)) {
                    if (editable(child)) return child
                    descend(child, depth + 1)?.let { return it }
                }
                return null
            }
            return descend(node, 0)
        }
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

        private val _screenOn = MutableStateFlow(true)

        /**
         * Whether the display is interactive. The bubble's window layer outlives a screen going dark, so it
         * has to be told to leave (#269).
         */
        val screenOn: StateFlow<Boolean> = _screenOn.asStateFlow()

        /**
         * Inserts [text] into the focused editable field via the running service, returning true on
         * success. Returns false when the service is not running or no editable field is focused.
         */
        fun injectText(text: String, verify: Boolean = true): Boolean =
            instance?.commitTextIntoFocused(text, verify) ?: false

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
        fun commitPreviewFinal(finalText: String): Boolean =
            instance?.commitPreviewFinalOnFocused(finalText) ?: false

        /** Remove the live preview entirely (recording cancelled). */
        fun clearPreview() { instance?.clearPreviewOnFocused() }
    }
}
