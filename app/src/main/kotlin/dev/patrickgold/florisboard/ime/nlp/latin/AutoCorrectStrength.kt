/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp.latin

/**
 * How much evidence autocorrect wants before it replaces a word **without being asked** (issue #295).
 *
 * Every level offers the same suggestions in the strip; they differ only in when one is swapped in
 * silently. See [AutoCommitGate] for the measured trade-off each of them buys.
 */
enum class AutoCorrectStrength {
    /** Only unmistakable slips — a finger visibly landing between two keys. */
    CAUTIOUS,

    /** The default: ordinary mis-taps are fixed, deliberate unusual spellings are left alone. */
    BALANCED,

    /** Also whole-key misses, at the price of occasionally rewriting a word that was meant. */
    AGGRESSIVE;
}
