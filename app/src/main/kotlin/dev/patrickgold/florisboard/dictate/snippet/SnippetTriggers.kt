/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.snippet

import android.content.Context
import dev.patrickgold.florisboard.dictate.data.prompts.PromptModel
import dev.patrickgold.florisboard.dictate.data.prompts.PromptsDatabaseHelper
import dev.patrickgold.florisboard.dictate.data.prompts.snippetBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The typed shortcuts that expand a `[snippet]` prompt while typing (issue #283).
 *
 * A snippet prompt may carry an optional [PromptModel.trigger]: type it as a standalone word and the
 * next space, punctuation mark or line break swaps it for the snippet's text. The index lives here as
 * a plain map so the typing path (`KeyboardManager`) can answer "is this word a trigger?" with a
 * single lookup and without ever touching the database on the input thread.
 *
 * Only prompts with a [snippetBody] are indexed, so a trigger can never set an AI prompt — and thus a
 * network call — in motion just by typing.
 */
object SnippetTriggers {
    /** A trigger longer than this is refused in the editor and never matched here. */
    const val MAX_LENGTH = 32

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var index: Map<String, String> = emptyMap()

    /** True while no prompt defines a trigger — the typing path skips all further work then. */
    val isEmpty: Boolean
        get() = index.isEmpty()

    /**
     * The lookup table for [bodyFor]: lowercased trigger → the text to insert. Prompts without a
     * snippet body or without a usable trigger are left out; if two prompts claim the same trigger,
     * the one earlier in the list (i.e. higher up in the user's prompt order) wins.
     */
    fun indexOf(prompts: List<PromptModel>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (prompt in prompts) {
            val body = prompt.snippetBody() ?: continue
            val trigger = prompt.trigger?.trim().orEmpty()
            if (!isValidTrigger(trigger)) continue
            result.putIfAbsent(trigger.lowercase(), body)
        }
        return result
    }

    /** Replaces the cached index with the triggers of [prompts]. */
    fun update(prompts: List<PromptModel>) {
        index = indexOf(prompts)
    }

    /**
     * Reloads the index from the shared `prompts.db`.
     *
     * Called when the keyboard attaches to a field, because snippets are plain local text: they must
     * work for users who never enabled rewording, and those never reach `DictateController.refreshPrompts`.
     */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val prompts = runCatching {
                withContext(Dispatchers.IO) {
                    PromptsDatabaseHelper.getInstance(appContext).getAll()
                }
            }.getOrNull() ?: return@launch
            update(prompts)
        }
    }

    /** The snippet text [token] expands into, or `null` if it is not a trigger. Case is ignored. */
    fun bodyFor(token: String): String? {
        if (token.isEmpty() || token.length > MAX_LENGTH) return null
        return index[token.lowercase()]
    }

    /**
     * The word right before the cursor: the trailing run of non-whitespace characters of
     * [textBeforeCursor], or `null` if there is none (empty text, or the cursor sits after a space).
     *
     * Splitting on whitespace rather than on word characters is deliberate. It lets a trigger carry
     * punctuation the way the iOS/SwiftKey conventions do (`;sig`, `.br`), and it keeps a trigger that
     * happens to sit inside a longer word (`Hallo;sig`) from firing.
     */
    fun triggerCandidate(textBeforeCursor: String): String? {
        if (textBeforeCursor.isEmpty()) return null
        var start = textBeforeCursor.length
        while (start > 0 && !textBeforeCursor[start - 1].isWhitespace()) {
            start--
            // No trigger can be longer than this, so stop walking back through a long word.
            if (textBeforeCursor.length - start > MAX_LENGTH) return null
        }
        return if (start == textBeforeCursor.length) null else textBeforeCursor.substring(start)
    }

    /** Whether [input] can be used as a trigger: something, no whitespace, not too long. */
    fun isValidTrigger(input: String): Boolean {
        return input.isNotEmpty() && input.length <= MAX_LENGTH && input.none { it.isWhitespace() }
    }
}
