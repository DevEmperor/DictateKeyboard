/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.provider

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The model catalog against the presets, and against itself.
 *
 * Until the presets' curated lists are made from the catalog, both exist side by side, and a model added to
 * one and forgotten in the other is exactly the drift the catalog is there to end. So every id a preset
 * offers must have an entry here, with the role it is offered for.
 */
class ModelCatalogTest : FunSpec({

    // Their models are the user's: a local server, the on-device engine.
    val userModels = setOf(ProviderRegistry.OLLAMA.id, ProviderRegistry.LOCAL.id)
    val presets = ProviderRegistry.presets.filter { it.id !in userModels }

    test("every shipped provider has an entry, apart from those whose models are the user's") {
        presets.map { it.id }.filter { it !in ModelCatalog.providerIds }.shouldBeEmpty()
        (ModelCatalog.providerIds - ProviderRegistry.presets.map { it.id }.toSet()).shouldBeEmpty()
    }

    test("every model a preset offers has an entry with the role it is offered for") {
        for (preset in presets) {
            val offered = listOf(
                ModelRole.CHAT to preset.curatedChatModels + listOfNotNull(preset.defaultChatModel),
                ModelRole.TRANSCRIPTION to preset.curatedTranscriptionModels + listOfNotNull(preset.defaultTranscriptionModel),
                ModelRole.REALTIME to preset.curatedRealtimeModels + listOfNotNull(preset.defaultRealtimeModel),
            )
            for ((role, ids) in offered) {
                for (id in ids.distinct()) {
                    withClue("${preset.id} offers $id for $role") {
                        val spec = ModelCatalog.spec(preset.id, id)
                        spec shouldNotBe null
                        spec!!.roles shouldContain role
                    }
                }
            }
        }
    }

    test("no id or alias appears twice for a provider") {
        for (id in ModelCatalog.providerIds) {
            val names = ModelCatalog.modelsOf(id).flatMap { listOf(it.id) + it.aliases }
            withClue(id) { names.groupBy { it }.filterValues { it.size > 1 }.keys.shouldBeEmpty() }
        }
    }

    test("reasoning values are distinct words of the scale, least thinking first") {
        for (id in ModelCatalog.providerIds) {
            for (spec in ModelCatalog.modelsOf(id)) {
                val reasoning = spec.reasoning ?: continue
                withClue("$id ${spec.id}") {
                    reasoning.values.isEmpty() shouldBe false
                    val positions = reasoning.values.map { ReasoningSpec.SCALE.indexOf(it) }
                    positions.filter { it < 0 }.shouldBeEmpty()
                    positions shouldBe positions.sorted().distinct()
                    reasoning.whenOmitted?.let { reasoning.values shouldContain it }
                }
            }
        }
    }

    test("only a chat model has a reasoning setting") {
        for (id in ModelCatalog.providerIds) {
            ModelCatalog.modelsOf(id).filter { it.reasoning != null && ModelRole.CHAT !in it.roles }
                .map { it.id }.shouldBeEmpty()
        }
    }

    test("the effort a preset gives its own default model is one the catalog lists for it") {
        for (preset in presets) {
            val effort = preset.defaultChatReasoningEffort ?: continue
            val model = preset.defaultChatModel!!
            withClue("${preset.id} $model $effort") {
                ModelCatalog.spec(preset.id, model)?.reasoning?.values.orEmpty() shouldContain effort
            }
        }
    }

    test("a model that was never confirmed says why") {
        for (id in ModelCatalog.providerIds) {
            ModelCatalog.modelsOf(id).filter { it.verified == null && it.note.isNullOrBlank() }
                .map { "$id ${it.id}" }.shouldBeEmpty()
        }
    }

    test("an alias finds its model") {
        ModelCatalog.spec(ProviderRegistry.DEEPGRAM.id, "nova-3-general")?.id shouldBe "nova-3"
        ModelCatalog.spec(ProviderRegistry.DEEPGRAM.id, "nova-9") shouldBe null
    }
})
