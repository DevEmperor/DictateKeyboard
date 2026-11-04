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

import dev.patrickgold.florisboard.dictate.data.prompts.PromptModel
import dev.patrickgold.florisboard.dictate.provider.DictateRewording
import dev.patrickgold.florisboard.dictate.provider.ModelCatalog
import dev.patrickgold.florisboard.dictate.provider.PromptReasoning
import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderPreset
import dev.patrickgold.florisboard.dictate.provider.ReasoningResolver
import dev.patrickgold.florisboard.dictate.provider.ReasoningSpec
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * The words the old global "Reasoning effort" setting (#141) and the per-prompt override (#155) were stored in.
 *
 * Since 2026-11-04 the setting lives in each provider account ([ProviderAccount.chatReasoning]), because the
 * words belong to the model, and a prompt holds a level ([PromptReasoning]). This enum stays as the storage of
 * both. The global pref is read once, to move it into the accounts (DictateLegacyMigrator). Prompts keep
 * storing these names, in prompts.db and in backups, which every version reads with a strict JSON reader: a
 * new name would make an older version refuse the whole prompt list. So a level is stored as NONE, LOW or
 * HIGH ([storedFor]), names every version knows, and read back by [PromptReasoning.fromStored].
 */
@Serializable
enum class DictateReasoningEffort(val wire: String?) {
    OFF(null),
    NONE("none"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),

    // A user-entered value (#186), stored in `rewordingReasoningEffortCustom`, not in [wire]. A prompt's
    // CUSTOM now follows the account: a word typed for one provider is what a level replaces.
    CUSTOM(null);

    companion object {
        /** How a prompt's [level] is stored: a name every version understands, or nothing for the account's. */
        fun storedFor(level: PromptReasoning): DictateReasoningEffort? = when (level) {
            PromptReasoning.ACCOUNT -> null
            PromptReasoning.NONE -> NONE
            PromptReasoning.LITTLE -> LOW
            PromptReasoning.MUCH -> HIGH
        }
    }
}

/**
 * The `reasoning_effort` a rewording through [account] ([preset], [model]) goes out with at a prompt's
 * [level]; null leaves the field out. See [ReasoningResolver] for the rules and [ModelCatalog] for the words.
 *
 * Every rewording path asks this — keyboard, share screen, the watch — so they all send the same thing.
 */
fun rewordingReasoningWire(
    account: ProviderAccount,
    preset: ProviderPreset,
    model: String,
    level: PromptReasoning = PromptReasoning.ACCOUNT,
): String? = ReasoningResolver.wire(ModelCatalog.spec(preset.id, model), account.chatReasoning, level)

/**
 * [prompt]'s own effort in the form [DictateRewording.Prompt.reasoningEffort] takes: null when the prompt
 * follows the account, blank when its level leaves the field out for this model.
 */
fun promptReasoningWire(
    account: ProviderAccount,
    preset: ProviderPreset,
    model: String,
    prompt: PromptModel,
): String? = prompt.reasoning.takeIf { it != PromptReasoning.ACCOUNT }?.let {
    rewordingReasoningWire(account, preset, model, it).orEmpty()
}

/**
 * What the old global setting ([global], with its Custom text [globalCustom]) becomes in one account whose
 * rewording runs on [model] at [preset], when it moves into the accounts (2026-11-04).
 *
 * Off stays the default (empty). A model the catalog knows keeps the word if it takes it, else gets the
 * nearest word it does take, the lower one on a tie, as the client did with a refused value; a model without
 * reasoning gets the default. A model the catalog does not know keeps the word as it was sent.
 */
internal fun migratedChatReasoning(
    global: DictateReasoningEffort,
    globalCustom: String,
    preset: ProviderPreset,
    model: String,
): String {
    val word = when (global) {
        DictateReasoningEffort.OFF -> return ""
        DictateReasoningEffort.CUSTOM -> globalCustom.trim()
        else -> global.wire.orEmpty()
    }
    if (word.isEmpty()) return ""
    val spec = ModelCatalog.spec(preset.id, model) ?: return word
    val values = spec.reasoning?.values ?: return ""
    if (word in values) return word
    val at = ReasoningSpec.SCALE.indexOf(word)
    if (at < 0) return ""
    return values.minWith(compareBy({ abs(ReasoningSpec.SCALE.indexOf(it) - at) }, { ReasoningSpec.SCALE.indexOf(it) }))
}
