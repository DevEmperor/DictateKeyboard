/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.translate

import dev.patrickgold.florisboard.dictate.translate.TranslationModelFile.Role
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** The config that loads one installed direction into Marian (issue #424). */
class BergamotConfigTest : FunSpec({

    fun file(role: Role, name: String) = TranslationModelFile(role, name, "https://x/$name.gz", 1, 2, "0".repeat(64))

    test("a shared vocabulary is named twice, for the source and the target side") {
        val direction = TranslationDirection(
            "de", "en", "base-memory",
            listOf(
                file(Role.MODEL, "model.deen.intgemm.alphas.bin"),
                file(Role.SHORTLIST, "lex.50.50.deen.s2t.bin"),
                file(Role.VOCAB, "vocab.deen.spm"),
            ),
        )
        val yaml = BergamotConfig.yaml(direction) { "/models/de-en/${it.fileName}" }
        yaml shouldContain "models:\n  - \"/models/de-en/model.deen.intgemm.alphas.bin\"\n"
        yaml shouldContain "vocabs:\n  - \"/models/de-en/vocab.deen.spm\"\n  - \"/models/de-en/vocab.deen.spm\"\n"
        yaml shouldContain "shortlist:\n  - \"/models/de-en/lex.50.50.deen.s2t.bin\"\n  - false\n"
        yaml shouldContain "gemm-precision: int8shiftAlphaAll\n"
        yaml shouldContain "beam-size: 1\n"
    }

    test("separate vocabularies go in source, target order") {
        val direction = TranslationDirection(
            "en", "zh", "base-memory",
            listOf(
                file(Role.MODEL, "model.enzh.intgemm.alphas.bin"),
                file(Role.SHORTLIST, "lex.50.50.enzh.s2t.bin"),
                file(Role.TARGET_VOCAB, "trgvocab.enzh.spm"),
                file(Role.SOURCE_VOCAB, "srcvocab.enzh.spm"),
            ),
        )
        val yaml = BergamotConfig.yaml(direction) { "/m/${it.fileName}" }
        yaml shouldContain "vocabs:\n  - \"/m/srcvocab.enzh.spm\"\n  - \"/m/trgvocab.enzh.spm\"\n"
    }

    test("a quote in a path cannot end the YAML string early") {
        val direction = TranslationDirection(
            "de", "en", "tiny",
            listOf(file(Role.MODEL, "m.bin"), file(Role.SHORTLIST, "l.bin"), file(Role.VOCAB, "v.spm")),
        )
        val yaml = BergamotConfig.yaml(direction) { "/odd\"dir/${it.fileName}" }
        yaml shouldContain "\"/odd\\\"dir/m.bin\""
        yaml shouldNotContain "\"/odd\"dir"
    }
})
