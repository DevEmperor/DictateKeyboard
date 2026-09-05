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
import android.content.ClipDescription
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
import android.os.PersistableBundle
import android.provider.Settings
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
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

    private val prefs by FlorisPreferenceStore

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
            // The one witness that says what the *user* sees. Deliberately nothing but three field
            // writes — no node fetch, which is what made a per-keystroke event expensive (#222).
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> noteTextAdded(event)
        }
    }

    // --- "Did the text arrive?", asked of the system instead of the field ---------------------------
    // A read-back asks the field to describe itself, and both ways of asking have now been measured
    // lying: the accessibility node of a Compose text field still reported an empty field 300 ms after
    // a write that had visibly landed (it caught up seconds later), and the input connection reports
    // back what it wrote even into an editor the app had thrown away (#310). This is the third witness
    // and the only independent one: the platform announcing that a field's text grew.

    @Volatile
    private var textAddedAt = 0L

    @Volatile
    private var textAddedWindowId = -1

    /**
     * Records that some field gained text. [AccessibilityEvent.getAddedCount] is what makes this usable
     * as evidence: an app clearing its own composer after a send also fires a text-changed event, and
     * that is precisely the moment ([#132], [#161]) where mistaking someone else's change for our own
     * would resurrect "green check, no text".
     */
    private fun noteTextAdded(event: AccessibilityEvent) {
        if (event.addedCount <= 0) return
        textAddedAt = SystemClock.uptimeMillis()
        textAddedWindowId = event.windowId
    }

    /** Whether a field in [windowId] gained text since [since]; any window when [windowId] is unknown. */
    private fun textAddedSince(since: Long, windowId: Int): Boolean =
        textAddedAt >= since && (windowId == -1 || textAddedWindowId == windowId)

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
     * The field's content and caret as far as they could be **proven**, and where that proof came from
     * ([source], for the log line only).
     *
     * There is deliberately no "unknown" value: unknown is `null`, and keeping that apart from an empty
     * field is the whole of issue #314. A placeholder read as content prepends a word the user never
     * said; content read as empty *deletes* what they wrote.
     */
    internal data class FieldContent(
        val text: String,
        val start: Int,
        val end: Int,
        val source: String,
    )

    /**
     * Inserts [text] into the focused editable field at the cursor, replacing the active selection
     * (matching the IME `commitText` semantics) and placing the cursor right after the inserted text.
     * Falls back to appending at the end when the field reports no usable selection. Returns true when
     * the field accepted the change — some custom/legacy views do not support `ACTION_SET_TEXT`.
     */
    private fun commitTextIntoFocused(text: String, verify: Boolean = true): Boolean {
        if (text.isEmpty()) return true // silence: nothing to insert — a no-op is a success, not a failure.

        // The ceiling on everything this call may wait for. commitOutput runs on the main thread, so it
        // exists to bound how long the UI is blocked — but it is only a *ceiling*: each verification
        // gets its own [VERIFY_WAIT_MS] measured from its own write. Sharing one deadline from here was
        // wrong and cost nothing less than the verdict itself: resolving the field, focusing it and the
        // write ate the budget, so the first read-back found the deadline already passed and never
        // waited at all — which is how a Compose field that simply had not published its new text yet
        // read as "swallowed".
        val commitDeadline = SystemClock.uptimeMillis() + COMMIT_BUDGET_MS
        // Only for the failure log line: a commit that lost tells us much more when it also says what it
        // was able to work out about the field.
        var lastSource = "-"

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

            // Read the field through the input connection BEFORE writing through it. Afterwards it would
            // hand our own text back even when the app has already thrown that editor away (#310) — and
            // this reading is what decides below whether the field may be rebuilt at all (#314).
            val icContent = if (viaNodeOnly) null else readWholeFieldFromConnection()

            // 1. The keyboard-style path: commit through the input connection (no clipboard, no toast).
            //    Its API returns void, so "it did not throw" is all the call itself tells us — hence the
            //    read-back below (issue #277).
            val beforeText = if (verify) readNodeText(target) else null
            val before = if (verify && !viaNodeOnly) readBeforeCursor(text.length) else null
            val icWriteAt = SystemClock.uptimeMillis()
            if (!viaNodeOnly && commitViaInputConnection(text)) {
                if (!verify ||
                    landedInField(target, beforeText, before, text.length, icWriteAt, commitDeadline)
                ) {
                    logCommit("ic", icContent?.source ?: "-", text.length)
                    return true
                }
                if (beforeText == null) {
                    // No node to ask, so the only verdict available came from the connection — and a
                    // connection cannot tell "written into a dead editor" from "not written". Do not write
                    // again on a verdict that weak: if it were wrong, the text would end up in the field
                    // twice, which is worse than a wrong error message. The caller puts it on the
                    // clipboard instead.
                    flogDebug { "commit swallowed, no node to recover through len=${text.length}" }
                    logCommit("none", "-", text.length)
                    return false
                }
                // The *field* — the thing the user is looking at — demonstrably did not change, so the
                // write went somewhere invisible and falling through cannot duplicate anything. This is
                // the long-standing "green check but no text" (#132, #156, #277): the read-back went
                // through the same connection as the write, so it faithfully reported the text it had
                // just put into an editor the app had already discarded.
                flogDebug { "commit swallowed by the field, recovering via the node len=${text.length}" }
            }

            // What is in the field and where the caret is, as far as it can be **proven**. Null means
            // *unknown*, never "empty" — keeping those two apart is the whole of issue #314.
            val content = resolveFieldContent(target, icContent)
            lastSource = content?.source ?: "unknown"

            // 2. Rebuild the field through the node — but only around content we can prove. This is the
            //    one mechanism that can invent text: it sends back the whole field, so anything it
            //    misread (WhatsApp's "Message" placeholder) is written in as if the user had said it.
            val setTextWriteAt = SystemClock.uptimeMillis()
            if (target != null && content != null && setTextOnFocused(target, text, content)) {
                // ACTION_SET_TEXT returning true means the action was accepted, not that the text stuck —
                // the same void-return problem one level down, and the reason this path used to report
                // success unconditionally.
                // [beforeText] is still the right baseline: either path 1 never ran, or it ran and the
                // node proved it changed nothing.
                if (!verify ||
                    landedInField(target, beforeText, null, text.length, setTextWriteAt, commitDeadline)
                ) {
                    logCommit("setText", content.source, text.length)
                    return true
                }
                flogDebug { "setText accepted but changed nothing len=${text.length}" }
            }
            // 3. Paste. It inserts at the field's own caret and never reads what is already there, so it
            //    is structurally incapable of prepending a placeholder — which is why it now runs ahead
            //    of an unproven rebuild instead of last. It costs a system clipboard notice, and that is
            //    the right trade: a notice beats a word the user never said.
            val pasteWriteAt = SystemClock.uptimeMillis()
            if (target != null && pasteIntoFocused(target, text)) {
                if (!verify ||
                    landedInField(target, beforeText, null, text.length, pasteWriteAt, commitDeadline)
                ) {
                    logCommit("paste", content?.source ?: "unknown", text.length)
                    clearOwnClipboardAfterPaste(text)
                    return true
                }
                flogDebug { "paste accepted but changed nothing len=${text.length}" }
            }
            // 4. Last resort: rebuild around what the node merely claims. Only reached when the field
            //    refuses everything else, and there a possible prepend still beats no insert at all.
            val lastResortWriteAt = SystemClock.uptimeMillis()
            if (target != null && content == null && setTextOnFocused(target, text, guessedContent(target))) {
                if (!verify ||
                    landedInField(target, beforeText, null, text.length, lastResortWriteAt, commitDeadline)
                ) {
                    logCommit("setText", "unproven", text.length)
                    return true
                }
            }
            if (attempt < COMMIT_ATTEMPTS - 1) SystemClock.sleep(COMMIT_RETRY_DELAY_MS)
        }
        flogDebug { "commit FAILED after $COMMIT_ATTEMPTS attempts len=${text.length}" }
        logCommit("none", lastSource, text.length)
        return false
    }

    /**
     * One line per commit that survives a release build, unlike [flogDebug]. Names the mechanism that
     * won and where its knowledge of the field came from — **never the text itself**, only its length.
     */
    private fun logCommit(path: String, content: String, length: Int) {
        val legacy = if (legacyInsertionForced()) " legacy=forced" else ""
        Log.i(
            LOG_TAG,
            "commit path=$path content=$content started=${isInputStarted()}$legacy len=$length",
        )
    }

    /**
     * Whether the accessibility input method currently has a started editing session. Asking this is the
     * difference between "the connection refused the write" and "there was never a connection" — the
     * first question to answer when a floating-button insert goes the wrong way.
     */
    private fun isInputStarted(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { inputMethod?.currentInputStarted == true }.getOrDefault(false)

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
     * Whether the accessibility input connection may be used at all.
     *
     * The version check is the real condition: the API arrived with Android 13, and below it the
     * floating button has nothing but the node and the clipboard — which is where issue #314 lives.
     *
     * The devtools switch is what makes that half reachable on a modern phone. Without it every commit
     * here wins on route 1 and the placeholder handling, the caret probe and the paste route are never
     * executed at all. An emulator is no substitute: the apps whose fields misreport their placeholder
     * are precisely the ones that are not installed on one.
     */
    private fun inputConnectionUsable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !legacyInsertionForced()

    /** Debug builds only, so a release can never be talked out of its best insertion route. */
    private fun legacyInsertionForced(): Boolean =
        BuildConfig.DEBUG && runCatching { prefs.devtools.forceLegacyInsertion.get() }.getOrDefault(false)

    /**
     * The field's whole text and caret, read from the accessibility input connection (API 33+), or null
     * when that is unavailable or would only give a slice.
     *
     * This is the good source and the node is the bad one: the editor itself has no notion of a
     * placeholder, so it reports an empty field as empty where the node hands out "Message" as if it
     * were content (issue #314), and its selection is the real caret rather than the node's frequent -1.
     *
     * It must be read **before** anything is written through the same connection — afterwards it
     * faithfully reports our own text back, even out of an editor the app has already discarded (#310).
     */
    private fun readWholeFieldFromConnection(): FieldContent? {
        if (!inputConnectionUsable()) return null
        val connection = inputMethod?.currentInputConnection ?: return null
        return runCatching {
            val window = connection.getSurroundingText(FIELD_READ_WINDOW, FIELD_READ_WINDOW, 0)
                ?: return null
            val text = window.text?.toString() ?: return null
            // Only usable when it is the *whole* field: rebuilding a field out of a slice would delete
            // the rest of it. We asked for [FIELD_READ_WINDOW] characters on each side, so anything
            // shorter than one window cannot have been cut off on either side — which settles it without
            // consulting `offset`. That matters: the default `InputConnection.getSurroundingText` reports
            // offset -1 (unknown), so requiring offset == 0 threw away every app that does not implement
            // the call itself — most of them.
            if (text.length >= FIELD_READ_WINDOW) return null
            FieldContent(text, window.selectionStart, window.selectionEnd, "ic")
        }.getOrNull()
    }

    /**
     * Asks the field to put its caret at position [length]. The answer is the point, not the caret.
     *
     * `TextView` validates the range against the text it really holds — which for a field showing its
     * hint is the *empty* string, not the hint. So a field that claims seven characters of content and
     * then refuses position seven has just told us that those seven characters are a placeholder.
     *
     * A refusal is only ever "unknown", never "empty": a view with its own accessibility node provider
     * (WebView, Compose) may refuse for its own reasons, and concluding "empty" there would rebuild the
     * field without the user's text — deleting real content, which is far worse than a stray prepend.
     *
     * Only asked when the node reports no caret at all, i.e. exactly where we would otherwise have
     * appended blindly; asking a field that knows its caret would drag the user's cursor to the end.
     */
    private fun confirmCaretAtEnd(node: AccessibilityNodeInfo, length: Int): Boolean {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
        }
        return runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        }.getOrDefault(false)
    }

    /** [fieldContentFrom] over a real node, with [icContent] (read earlier) as the preferred source. */
    private fun resolveFieldContent(
        node: AccessibilityNodeInfo?,
        icContent: FieldContent?,
    ): FieldContent? {
        if (icContent == null && node == null) return null
        val nodeText = node?.let {
            runCatching {
                it.refresh()
                it.editableText()
            }.getOrDefault("")
        } ?: ""
        return fieldContentFrom(
            icContent = icContent,
            nodeText = nodeText,
            nodeStart = node?.textSelectionStart ?: -1,
            nodeEnd = node?.textSelectionEnd ?: -1,
            confirmCaretAtEnd = { node != null && confirmCaretAtEnd(node, nodeText.length) },
        )
    }

    /**
     * What the node alone claims, with the caret coerced the way it always was. Unproven by definition —
     * only for the last resort in [commitTextIntoFocused], where the alternative is inserting nothing.
     */
    private fun guessedContent(node: AccessibilityNodeInfo): FieldContent {
        val text = node.editableText()
        val from = node.textSelectionStart.coerceForText(text)
        val to = node.textSelectionEnd.coerceForText(text)
        return FieldContent(text, minOf(from, to), maxOf(from, to), "unproven")
    }

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
        if (!inputConnectionUsable()) return null
        val connection = inputMethod?.currentInputConnection ?: return null
        return runCatching {
            val window = connection.getSurroundingText(sentLength + VERIFY_WINDOW_PAD, 0, 0) ?: return null
            val text = window.text ?: return null
            val end = window.selectionStart.coerceIn(0, text.length)
            text.subSequence(0, end).toString()
        }.getOrNull()
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
     * Whether the write reached the field. Three witnesses, in descending order of trust.
     *
     * First the **system's own announcement** ([textAddedSince]): a field somewhere gained characters
     * after our write. It is the only witness that is not the field describing itself, and it is here
     * because both of the others were measured lying. It costs nothing to check.
     *
     * Then a read-back — the node when there is one, the input connection only when there is not.
     * **That precedence is not a preference, it is the fix for #310** and must not be relaxed into
     * "either will do": the connection reads back through the very channel the write went out on, so a
     * commit into an editor the app has already discarded is reported as having arrived. The node at
     * least describes something on screen — though not always in time, which is why it is no longer the
     * only voice: a Compose text field was measured still reporting an empty field 300 ms after a write
     * that had visibly landed, catching up only seconds later.
     *
     * Both verdicts follow [insertLandedFrom]'s lopsided rule — only a demonstrably unchanged field is a
     * failure — so an unreadable witness never invents one. What did change is the *waiting*: instead of
     * one 50 ms settle, this polls for [VERIFY_WAIT_MS] **measured from this write**, capped by
     * [commitDeadline] so the whole commit stays bounded on the main thread.
     *
     * The distinction is the entire point. A field does not publish its new text to the accessibility
     * layer the instant it accepts it — a Compose text field takes a composition pass to get there — so
     * reading back immediately finds the old text and calls a perfectly good write "swallowed". And a
     * false swallowed verdict is not harmless: it is what sends the commit on to the next mechanism,
     * which writes the text a second time and can invent a placeholder along the way (#314).
     */
    private fun landedInField(
        node: AccessibilityNodeInfo?,
        beforeText: String?,
        beforeCursor: String?,
        sentLength: Int,
        writtenAt: Long,
        commitDeadline: Long,
    ): Boolean {
        val windowId = runCatching { node?.windowId ?: -1 }.getOrDefault(-1)
        if (textAddedSince(writtenAt, windowId)) return true
        if (beforeText == null && beforeCursor == null) return true
        val startedAt = SystemClock.uptimeMillis()
        val deadline = minOf(startedAt + VERIFY_WAIT_MS, commitDeadline)
        while (true) {
            // The system's own announcement first: it costs nothing, it describes the field the user is
            // looking at, and it is the only witness here that is not the field describing itself.
            if (textAddedSince(writtenAt, windowId)) return true
            val landed = if (beforeText != null) {
                insertLandedFrom(beforeText, readNodeText(node))
            } else {
                insertLandedFrom(beforeCursor, readBeforeCursor(sentLength))
            }
            if (landed) return true
            if (SystemClock.uptimeMillis() >= deadline) break
            SystemClock.sleep(VERIFY_POLL_MS)
        }
        flogDebug {
            "verify: still unchanged after ${SystemClock.uptimeMillis() - startedAt}ms — " +
                describeForVerify(node, beforeText)
        }
        return false
    }

    /**
     * The shape of the field a verification just gave up on: class, flags and lengths, **never text**.
     * Debug builds only, and the first thing to look at when a write that visibly worked is reported as
     * refused — it says whether the node was unreadable, still showing its hint, or simply behind.
     */
    private fun describeForVerify(node: AccessibilityNodeInfo?, beforeText: String?): String {
        val before = "beforeLen=${beforeText?.length ?: -1}"
        val target = node ?: return "node=none $before"
        return runCatching {
            "cls=${target.className} refresh=${target.refresh()} " +
                "textLen=${target.text?.length ?: -1} hintLen=${target.hintText?.length ?: -1} " +
                "showHint=${target.isShowingHintText} " +
                "sel=${target.textSelectionStart}..${target.textSelectionEnd} $before"
        }.getOrDefault("node=unreadable $before")
    }

    /**
     * Commits [text] through the accessibility [android.accessibilityservice.InputMethod] input connection
     * (API 33+). Requires the `flagInputMethodEditor` accessibility flag. Returns false when unavailable
     * (older OS, or no editor currently bound), so the caller falls back to the node-based methods.
     */
    private fun commitViaInputConnection(text: String): Boolean {
        if (!inputConnectionUsable()) return false
        val connection = inputMethod?.currentInputConnection ?: return false
        return runCatching {
            connection.commitText(text, 1, null)
            true
        }.getOrDefault(false)
    }

    /**
     * Inserts [text] via [AccessibilityNodeInfo.ACTION_SET_TEXT] by sending back the field's whole
     * content with [text] spliced in at [content]'s caret or selection.
     *
     * Because it rewrites everything, it is only ever as correct as [content] is — which is why it no
     * longer reads the field itself. The caller passes content it could **prove** (issue #314); the one
     * exception is [guessedContent] in the last-resort branch.
     *
     * Returns false when the field does not accept the action, so the caller can fall back to pasting.
     */
    private fun setTextOnFocused(
        node: AccessibilityNodeInfo,
        text: String,
        content: FieldContent,
    ): Boolean {
        val existing = content.text
        val start = content.start.coerceIn(0, existing.length)
        val end = content.end.coerceIn(start, existing.length)
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
     * Inserts [text] by putting it on the clipboard and performing [AccessibilityNodeInfo.ACTION_PASTE]
     * on the focused field. Returns false when the field refuses the action.
     *
     * Pasting inserts at the field's own caret without ever reading what is in it, so it cannot prepend
     * a placeholder (#314), and it works in WebView/custom inputs that ignore ACTION_SET_TEXT.
     *
     * **It costs a system clipboard notice, so every avoidable clipboard write is avoided here:**
     *
     *  * We used to write twice — our text, then the "previous" clipboard 400 ms later — and *each*
     *    write raises that notice. Worse, the second write never restored anything: reading the
     *    clipboard from a background app has been blocked since Android 10, so `primaryClip` was always
     *    null and the "restore" *cleared* the user's clipboard. The text now simply stays, which also
     *    leaves it as the recovery route if the paste turns out not to have landed.
     *  * Nothing is written at all when the same text is already the primary clip — which is the normal
     *    case with the #214 always-copy setting, since that copies it moments earlier.
     *  * The clip is marked sensitive on API 33+, so the system's clipboard preview does not put the
     *    dictation on screen.
     *
     * The action is attempted rather than looked up in `actionList` first: `performAction` reports a
     * refusal by itself, and a field that takes a paste without advertising it is one we used to lose.
     */
    private fun pasteIntoFocused(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
        if (lastClipText != text) {
            val clip = ClipData.newPlainText("dictate", text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            if (runCatching { clipboard.setPrimaryClip(clip) }.isFailure) return false
            lastClipText = text
        }
        return runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
    }

    /**
     * Takes the dictation back off the clipboard once its paste has been **confirmed** — not when the
     * action was merely accepted, because until then the clipboard is the recovery route.
     *
     * [ClipboardManager.clearPrimaryClip] rather than an empty clip, and that is the whole trick: the
     * system's clipboard confirmation bails out when there is no clip at all, while an empty clip is
     * still a clip and still raises it. So this leaves nothing behind and stays quiet doing it.
     *
     * Skipped when the user asked for every dictation to be copied (#214) — there it belongs there.
     */
    private fun clearOwnClipboardAfterPaste(text: String) {
        if (lastClipText != text) return
        if (prefs.dictate.floatingButtonCopyToClipboard.get()) return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        mainHandler.postDelayed({
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
                lastClipText = null
            }
        }, CLIPBOARD_CLEAR_DELAY_MS)
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
        // Prefer content we could prove (the editor's own, on API 33+) over what the node claims — same
        // reason as in [setTextOnFocused]. Falling back to the node stays safe here because the match
        // below is its own guard: a placeholder never contains the text we are trying to remove.
        val proven = resolveFieldContent(node, readWholeFieldFromConnection())
        val existing = proven?.text ?: node.editableText()
        val cursor = proven?.end ?: node.textSelectionEnd.coerceForText(existing)
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

    /**
     * Whether live streaming into the focused field is safe at all: only over the input connection.
     *
     * Without it, every preview update would rebuild the *entire* field through ACTION_SET_TEXT — once
     * per word, each time around content that could only be guessed, and each retracted ending going the
     * same way through [deleteLastTextFromFocused]. That is the machinery of issue #314 running dozens
     * of times per dictation. The recording is unaffected: the bubble still shows the live text, and the
     * field gets one insert at the end, which is the path that is actually verified.
     */
    private fun canStreamIntoField(): Boolean =
        inputConnectionUsable() &&
            runCatching { inputMethod?.currentInputConnection != null }.getOrDefault(false)

    private fun setPreviewThrottled(full: String) {
        if (full == previewShown) return
        if (!canStreamIntoField()) return
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
        val content = resolveFieldContent(node, readWholeFieldFromConnection()) ?: return ""
        if (content.text.isEmpty() || content.start == content.end) return ""
        return content.text.substring(content.start, content.end)
    }

    /**
     * The full text of the focused editable field, or empty when there is none **or when we cannot
     * prove what is in it**.
     *
     * The second half matters: this is what a prompt operates on. With the node as the source, tapping a
     * prompt in an empty Telegram field handed the model the word "Message" — the placeholder — and it
     * dutifully reworded it. Refusing beats inventing an input (issue #314).
     */
    private fun fullTextOfFocused(): String {
        val node = dictationTarget() ?: return ""
        node.refresh()
        return resolveFieldContent(node, readWholeFieldFromConnection())?.text ?: ""
    }

    /** Selects the whole field so a subsequent inject replaces it. Returns true on success. */
    private fun selectAllInFocused(): Boolean {
        val node = dictationTarget() ?: return false
        node.refresh()
        // Same rule as [fullTextOfFocused]: selecting "everything" out of a length we only guessed would
        // hand a placeholder to the replacement that follows.
        val content = resolveFieldContent(node, readWholeFieldFromConnection()) ?: return false
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, content.text.length)
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
        private const val CLIPBOARD_CLEAR_DELAY_MS = 400L
        private const val MAX_EDITABLE_SEARCH_DEPTH = 6
        /** Tag for the one commit line that survives a release build, unlike [flogDebug]. */
        private const val LOG_TAG = "DictateOverlay"
        // Floating-button commit reliability (#161): resolve + focus the field the user sees so the input
        // connection binds to it, retry briefly while the host app rebuilds its field right after a send.
        private const val COMMIT_ATTEMPTS = 2
        private const val COMMIT_RETRY_DELAY_MS = 60L
        private const val FOCUS_SETTLE_MS = 40L
        // Read-back verification of the input-connection write (#277). The window is a little wider than
        // what was sent so a field that reformats around it still reads as changed.
        private const val VERIFY_WINDOW_PAD = 16
        // How long one read-back may wait for the field to show the write, measured from that write —
        // long enough for a Compose field to get the change through a composition pass. Too short and a
        // perfectly good write reads as "swallowed", which is what sends the commit into the rebuilding
        // path and writes the text a second time.
        private const val VERIFY_WAIT_MS = 250L
        private const val VERIFY_POLL_MS = 40L
        // The ceiling on all of a commit's waiting together. This runs on the main thread, so the
        // per-write waits above must not simply multiply with the paths tried and the retry.
        private const val COMMIT_BUDGET_MS = 900L
        // How much of the field to ask the input connection for. Anything longer than this is treated as
        // unreadable rather than truncated — see [readWholeFieldFromConnection].
        private const val FIELD_READ_WINDOW = 8192

        /**
         * What may be assumed about the field, given the editor's own answer ([icContent]) and the
         * node's claims — or null when neither proves anything.
         *
         * In order of trust:
         *  1. **The input connection.** The editor has no notion of a placeholder and knows its real
         *     caret. Available from API 33, and always right when it is.
         *  2. **An empty node.** Nothing to preserve, so nothing can be got wrong.
         *  3. **A node that reports a caret.** A field that knows where its cursor is, is a field that
         *     is telling us about real content rather than about the hint it is drawing.
         *  4. **A node that reports none** — the WhatsApp/Telegram case, `text="Message"`, `sel=-1`.
         *     Only [confirmCaretAtEnd] can settle it, and only in one direction.
         */
        internal fun fieldContentFrom(
            icContent: FieldContent?,
            nodeText: String,
            nodeStart: Int,
            nodeEnd: Int,
            confirmCaretAtEnd: () -> Boolean,
        ): FieldContent? {
            if (icContent != null) {
                val text = icContent.text
                val start = if (icContent.start in 0..text.length) icContent.start else text.length
                val end = if (icContent.end in 0..text.length) icContent.end else text.length
                return FieldContent(text, minOf(start, end), maxOf(start, end), icContent.source)
            }
            if (nodeText.isEmpty()) return FieldContent("", 0, 0, "empty")
            if (nodeStart >= 0 && nodeEnd >= 0) {
                val start = minOf(nodeStart, nodeEnd).coerceAtMost(nodeText.length)
                val end = maxOf(nodeStart, nodeEnd).coerceAtMost(nodeText.length)
                return FieldContent(nodeText, start, end, "node")
            }
            if (!confirmCaretAtEnd()) return null
            return FieldContent(nodeText, nodeText.length, nodeText.length, "probe")
        }

        /**
         * The text we ourselves last put on the clipboard, so a second identical write — and the second
         * system clipboard notice it would raise — can be skipped. Best-effort by design: a background
         * app may not read the clipboard back (Android 10+), so remembering is the only way to know.
         */
        @Volatile
        internal var lastClipText: String? = null

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
        // typing into another app doesn't flood the accessibility channel. It used to be 0 — every single
        // update written through — which is a lot of writes into a foreign app for no visible gain over a
        // gap this short.
        private const val PREVIEW_THROTTLE_MS = 120L

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
