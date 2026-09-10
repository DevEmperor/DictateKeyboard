/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.nlp

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.media.emoji.Emoji
import dev.patrickgold.florisboard.lib.util.NetworkUtils

/**
 * Interface for a candidate item, which is returned by a suggestion provider and used by the UI logic to render
 * the candidate row.
 */
interface SuggestionCandidate {
    /**
     * Required primary text of a candidate item, must be non-null and non-blank. The value of this property will
     * be committed to the target editor when the user clicks on this candidate item (either replacing the current
     * word or inserting after the cursor if there is no current word).
     *
     * In the UI it will be shown as the main label of a candidate item. Long texts that don't fit the maximum
     * candidate item width may be shortened and ellipsized.
     */
    val text: CharSequence

    /**
     * Optional secondary text of a candidate item, can be used to provide additional context, e.g. for translation
     * or transliteration. Regardless of this property's value it will never be committed to the target editor.
     *
     * In the UI it will be shown below the main label of a candidate item with a smaller font size, if the text
     * is a non-null and non-blank character sequence. Long texts that don't fit the maximum candidate item width
     * may be shortened and ellipsized.
     */
    val secondaryText: CharSequence?

    /**
     * The confidence of this suggestion to be what the user wanted to type. Must be a value between 0.0 and 1.0 (both
     * inclusive), where 0.0 means no confidence and 1.0 means highest confidence. The confidence rating may be used to
     * sort and filter candidates if multiple providers provide suggestions for a single input.
     */
    val confidence: Double

    /**
     * If true, it indicates that this candidate item should be automatically committed to the target editor once the
     * user inserts a non-letter character.
     *
     * In the UI, auto-insert candidates use a visual distinction such as bold font or a different color, depending
     * heavily on the theme the user has set.
     *
     * Only set this property to true if the algorithm has a high confidence that this suggestion is what the user
     * wanted to type.
     */
    val isEligibleForAutoCommit: Boolean

    /**
     * If true, it indicates that this candidate item should be user removable (by long-pressing). This flag should
     * only be set if it actually makes sense for this type of candidate to be removable and if the linked source
     * provider supports this action.
     */
    val isEligibleForUserRemoval: Boolean

    /**
     * Optional icon ID for showing an icon on the start of the candidate item. Mainly used for special suggestions
     * such as clipboard, word suggestions should not use this property. Do not provide an invalid drawable ID, any
     * non-null drawable ID that does not exist will result in an unhandled crash.
     *
     * In the UI, if the ID is non-null, it will be shown to the start of the main label and scaled accordingly.
     * The color of the icon is entirely decided by the theme of the user. Icons that are monochrome work best.
     */
    val icon: ImageVector?

    /**
     * The source provider of this candidate. Is used for several callbacks for training, blacklisting of candidates on
     * user-request, and so on. If null, it means that the source provider is unknown or does not want to receive
     * callbacks.
     */
    val sourceProvider: SuggestionProvider?

    /**
     * True when this word comes from the user's own vocabulary rather than the bundled dictionary — either
     * picked up from their typing or added by hand (issue #318).
     *
     * The UI shows these in italics. That is not decoration: a keyboard that quietly builds a vocabulary
     * out of what you write should be able to show you which of its suggestions came from you, and it is
     * also what makes the feature testable at all — without it "did it learn that?" can only be answered
     * by waiting to see whether autocorrect stops interfering.
     */
    val isLearned: Boolean
        get() = false

    /**
     * True when the end of [text] identifies it as much as the beginning does, so a label too long for its
     * cell should lose its middle rather than its tail (issue #346).
     *
     * An address is the case that matters: cutting `prateeksingh8997@gmail.com` at the front leaves a name
     * without a domain, which could be anyone's, while `prateeksin…@gmail.com` still says who it is. A word
     * suggestion is the opposite — it is matched against the prefix being typed, so its head is the part
     * the reader is checking.
     */
    val keepsTailWhenShortened: Boolean
        get() = false
}

/**
 * Default implementation for a word candidate (autocorrect and next/current word suggestion).
 *
 * @see SuggestionCandidate
 */
data class WordSuggestionCandidate(
    override val text: CharSequence,
    override val secondaryText: CharSequence? = null,
    override val confidence: Double = 0.0,
    override val isEligibleForAutoCommit: Boolean = false,
    override val isEligibleForUserRemoval: Boolean = true,
    override val sourceProvider: SuggestionProvider? = null,
    override val isLearned: Boolean = false,
) : SuggestionCandidate {
    override val icon: ImageVector? = null
}

/**
 * Default implementation for a clipboard candidate. Should generally not be used by a suggestion provider, except by
 * the clipboard suggestion provider.
 *
 * @see SuggestionCandidate
 */
data class ClipboardSuggestionCandidate(
    val clipboardItem: ClipboardItem,
    override val sourceProvider: SuggestionProvider?,
    val context: Context,
) : SuggestionCandidate {
    override val text: CharSequence = clipboardItem.displayText(context)

    override val secondaryText: CharSequence? = null

    override val confidence: Double = 1.0

    override val isEligibleForAutoCommit: Boolean = false

    override val isEligibleForUserRemoval: Boolean = true

    /**
     * Answered once, because the [NetworkUtils] regexes run over the whole clip — which can be a
     * paragraph — and both the icon and the shortening rule below ask the same question of it.
     */
    private val textKind =
        if (clipboardItem.type == ItemType.TEXT) ClipboardTextKind.of(text) else null

    override val icon: ImageVector = when (clipboardItem.type) {
        ItemType.TEXT -> when (textKind) {
            ClipboardTextKind.EMAIL -> Icons.Default.Email
            ClipboardTextKind.URL -> Icons.Default.Link
            ClipboardTextKind.PHONE -> Icons.Default.Phone
            else -> Icons.AutoMirrored.Outlined.Assignment
        }
        ItemType.IMAGE -> Icons.Default.Image
        ItemType.VIDEO -> Icons.Default.Videocam
    }

    override val keepsTailWhenShortened: Boolean = textKind?.keepsTail == true
}

/**
 * What a text clip turns out to be, as far as the suggestion strip cares.
 *
 * Separate from [ClipboardSuggestionCandidate] so the classification can be tested without a Context and
 * a database row, and so the icon and the shortening rule cannot drift apart by being decided twice.
 */
internal enum class ClipboardTextKind {
    EMAIL,
    URL,
    PHONE,
    PLAIN;

    /**
     * Whether the end of the text identifies it as much as the beginning does. True for an address:
     * `prateeksin…@gmail.com` still says who it is, `prateeksingh8997@gm…` could be anyone. A phone
     * number is short enough to fit anyway, and it is its *leading* digits — country and area code —
     * that would go missing if the front were eaten.
     */
    val keepsTail: Boolean
        get() = this == EMAIL || this == URL

    companion object {
        fun of(text: CharSequence): ClipboardTextKind = when {
            NetworkUtils.isEmailAddress(text) -> EMAIL
            NetworkUtils.isUrl(text) -> URL
            NetworkUtils.isPhoneNumber(text) -> PHONE
            else -> PLAIN
        }
    }
}

/**
 * Represents a candidate suggestion for an emoji.
 *
 * This class encapsulates an emoji, along with additional metadata for its presentation and behavior within
 * the suggestion system. It extends the [SuggestionCandidate] class, providing a specialized implementation for
 * emoji suggestions.
 *
 * @see SuggestionCandidate
 */
data class EmojiSuggestionCandidate(
    val emoji: Emoji,
    val showName: Boolean,
    override val confidence: Double = 1.0,
    override val isEligibleForAutoCommit: Boolean = false,
    override val isEligibleForUserRemoval: Boolean = false,
    override val icon: ImageVector? = null,
    override val sourceProvider: SuggestionProvider? = null,
) : SuggestionCandidate {
    override val text = emoji.value
    override val secondaryText = if (showName) emoji.name else null
}
