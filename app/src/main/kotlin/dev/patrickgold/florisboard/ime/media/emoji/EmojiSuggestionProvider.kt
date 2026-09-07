/*
 * Copyright (C) 2024-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.media.emoji

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.stream.Collectors
import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.keyboard.PrivateSession
import dev.patrickgold.florisboard.ime.nlp.EmojiSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.lib.FlorisLocale
import io.github.reactivecircus.cache4k.Cache

/**
 * Where an emoji query is read from (issue #298): the composing region when there is one, otherwise the
 * word the cursor sits in.
 *
 * Emoji suggestions have their own switch, but they used to read the composing text alone — and that is
 * switched off by an entirely different preference, *Display suggestions*. So turning off **word**
 * suggestions silently turned off **emoji** suggestions as well, while their switch went on claiming they
 * were enabled.
 *
 * The current word is the right source because it is what this feature was ever after: the editor
 * determines it independently of the composing region, so it is there either way, and reading it involves
 * no composing region — hence no underline for the people who switched suggestions off to be rid of one.
 */
internal fun emojiQuerySource(composingText: String, currentWordText: String): String =
    composingText.ifEmpty { currentWordText }

/**
 * Provides emoji suggestions within a text input context.
 *
 * This class handles the following tasks:
 * - Initializes and maintains a list of supported emojis.
 * - Generates and returns emoji suggestions based on user input and preferences.
 *
 * @param context The application context.
 */
class EmojiSuggestionProvider(private val context: Context) : SuggestionProvider {
    override val providerId = "org.florisboard.nlp.providers.emoji"

    private val prefs by FlorisPreferenceStore

    private val cachedEmojiMappings = Cache.Builder<FlorisLocale, EmojiDataBySkinTone>().build()

    /**
     * The word→emoji index per locale for the inline mode (issue #338), built from the very annotations
     * loaded above — no extra asset, and every language that has a CLDR file gets one.
     */
    private val cachedIndexes = Cache.Builder<FlorisLocale, EmojiSuggestionIndex>().build()

    override suspend fun create() {
    }

    override suspend fun preload(subtype: Subtype) {
        subtype.locales().forEach { locale ->
            val bySkinTone = cachedEmojiMappings.get(locale) {
                EmojiData.annotated(context, locale).bySkinTone
            }
            cachedIndexes.get(locale) {
                EmojiSuggestionIndex.build(bySkinTone?.get(EmojiSkinTone.DEFAULT).orEmpty())
            }
        }
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean
    ): List<SuggestionCandidate> {
        val preferredSkinTone = prefs.emoji.preferredSkinTone.get()
        val showName = prefs.emoji.suggestionCandidateShowName.get()
        val typed = emojiQuerySource(content.composingText, content.currentWordText)
        // Inline mode (issue #338): a plain typed word is an exact lookup, not a search. The fuzzy
        // sweep below stays reachable from either mode through the colon, so switching the trigger
        // over costs nobody their `:heart`.
        if (prefs.emoji.suggestionType.get() == EmojiSuggestionType.INLINE_TEXT &&
            !typed.startsWith(EmojiSuggestionType.LEADING_COLON.prefix)
        ) {
            return suggestInline(subtype, content, typed, showName, maxCandidateCount)
        }
        val query = validateInputQuery(typed, EmojiSuggestionType.LEADING_COLON.prefix)
            ?: return emptyList()
        val emojis = cachedEmojiMappings.get(subtype.primaryLocale)?.get(preferredSkinTone) ?: emptyList()
        val candidates = withContext(Dispatchers.Default) {
            emojis.parallelStream()
                .map { emoji ->
                    val nameWeight = emoji.name.containsWeighted(query, ignoreCase = true)
                    val keywordWeight = emoji.keywords
                        .any { it.contains(query, ignoreCase = true) }
                        .let { if (it) 1.0 else 0.0 }
                    emoji to (nameWeight * 0.7 + keywordWeight * 0.3)
                }
                .sorted { (_, a), (_, b) -> b.compareTo(a) }
                .limit(maxCandidateCount.toLong())
                .filter { (_, a) -> a > 0 }
                .map { (emoji, _) ->
                    EmojiSuggestionCandidate(
                        emoji = emoji,
                        showName = showName,
                        sourceProvider = this@EmojiSuggestionProvider,
                    )
                }
                .collect(Collectors.toList())
        }
        return candidates
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        val updateHistory = prefs.emoji.suggestionUpdateHistory.get()
        if (!updateHistory || candidate !is EmojiSuggestionCandidate) {
            return
        }
        EmojiHistoryHelper.markEmojiUsed(prefs, PrivateSession.isActive(context), candidate.emoji)
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        // No-op
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate) = false

    override suspend fun getListOfWords(subtype: Subtype) = emptyList<String>()

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String) = 0.0

    override suspend fun destroy() {
        cachedEmojiMappings.invalidateAll()
    }

    /**
     * The emojis for a plainly typed word (issue #338), best first — an exact lookup in the locale's
     * index, never a search.
     *
     * Two moments are asked, in this order: the word being typed right now, and — when the cursor has
     * just left one, i.e. a space or punctuation was typed — the word that was finished. The second is
     * what makes "I love " keep offering ❤️ while the next word begins, which is the behaviour the
     * request was about.
     */
    private suspend fun suggestInline(
        subtype: Subtype,
        content: EditorContent,
        typed: String,
        showName: Boolean,
        maxCandidateCount: Int,
    ): List<SuggestionCandidate> {
        val index = cachedIndexes.get(subtype.primaryLocale) ?: return emptyList()
        val minLength = prefs.emoji.suggestionQueryMinLength.get()
        val word = typed.ifEmpty { EmojiSuggestionIndex.completedWordBefore(content.textBeforeSelection) }
        if (word.length < minLength) return emptyList()
        // The same "maximum candidate count" the colon search obeys. A typed word rarely has more than
        // two or three emoji worth offering, so the slider is a ceiling here rather than a quota.
        return index.lookup(word).take(maxCandidateCount).map { emoji ->
            EmojiSuggestionCandidate(
                emoji = emoji,
                showName = showName,
                sourceProvider = this,
            )
        }
    }

    /**
     * Validates the user input query for the fuzzy `:query` search. [prefix] is passed in rather than
     * read from the setting because the colon search stays available in both trigger modes (#338).
     */
    private fun validateInputQuery(composingText: CharSequence, prefix: String): String? {
        val queryMinLength = prefs.emoji.suggestionQueryMinLength.get() + prefix.length
        if (prefix.isNotEmpty() && !composingText.startsWith(prefix)) {
            return null
        }
        if (composingText.length < queryMinLength) {
            return null
        }
        val emojiPartialName = composingText.substring(prefix.length)
        // Letters of any script, not `[A-Za-z]`: that spelling rejected every accented or non-Latin
        // query outright, so ":csók", ":grün" and ":улыбка" never reached the matcher (issue #274).
        if (!emojiPartialName.all { it.isLetter() }) {
            return null
        }
        return emojiPartialName
    }
}

private fun String.containsWeighted(other: String, ignoreCase: Boolean = false): Double = let { str ->
    if (str.contains(other, ignoreCase = ignoreCase)) {
        other.length.toDouble() / str.length.toDouble()
    } else {
        0.0
    }
}
