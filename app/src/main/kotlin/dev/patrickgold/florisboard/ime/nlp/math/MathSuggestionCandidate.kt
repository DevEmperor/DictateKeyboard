/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp.math

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.ui.graphics.vector.ImageVector
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider

/**
 * The answer to a sum the user just finished typing, offered in the suggestion strip (issue #329).
 *
 * Never [isEligibleForAutoCommit]: the strip may offer an answer, it may not decide that `=` meant the
 * user wanted one. Somebody typing `x = ` in a note is not asking for arithmetic, and the cost of being
 * wrong about that is text they did not write.
 *
 * The icon is what tells the two apart at a glance — a bare `600` sitting where word suggestions
 * normally are would read as a word the dictionary is proposing.
 */
data class MathSuggestionCandidate(
    val result: String,
    override val sourceProvider: SuggestionProvider? = null,
) : SuggestionCandidate {
    override val text: CharSequence
        get() = result

    override val secondaryText: CharSequence? = null

    override val confidence: Double = 1.0

    override val isEligibleForAutoCommit: Boolean = false

    override val isEligibleForUserRemoval: Boolean = false

    override val icon: ImageVector = Icons.Default.Calculate
}
