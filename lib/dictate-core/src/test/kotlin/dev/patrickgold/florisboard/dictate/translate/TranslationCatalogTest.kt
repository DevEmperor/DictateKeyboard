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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith

/**
 * The generated catalog and the routing on top of it (issue #424).
 *
 * The catalog is written by a script, so these checks are what stands between a bad run of it and a
 * download that 404s or a model Marian refuses to load.
 */
class TranslationCatalogTest : FunSpec({

    test("every language carries both directions, each with English on the other side") {
        TranslationCatalog.languages.shouldNotBeEmpty()
        for (language in TranslationCatalog.languages) {
            language.toEnglish.from shouldBe language.code
            language.toEnglish.to shouldBe TranslationCatalog.ENGLISH
            language.fromEnglish.from shouldBe TranslationCatalog.ENGLISH
            language.fromEnglish.to shouldBe language.code
        }
        TranslationCatalog.languages.map { it.code }.distinct().size shouldBe TranslationCatalog.languages.size
        TranslationCatalog.language(TranslationCatalog.ENGLISH) shouldBe null
    }

    test("every direction has what Marian needs, under a name whose extension picks the right loader") {
        for (direction in TranslationCatalog.languages.flatMap { it.directions }) {
            val roles = direction.files.map { it.role }.toSet()
            (Role.MODEL in roles) shouldBe true
            (Role.SHORTLIST in roles) shouldBe true
            // One shared vocabulary, or a source and a target one — never a mix.
            (roles == setOf(Role.MODEL, Role.SHORTLIST, Role.VOCAB) ||
                roles == setOf(Role.MODEL, Role.SHORTLIST, Role.SOURCE_VOCAB, Role.TARGET_VOCAB)) shouldBe true
            for (file in direction.files) {
                when (file.role) {
                    Role.MODEL, Role.SHORTLIST -> file.fileName shouldMatch Regex(""".+\.bin""")
                    else -> file.fileName shouldMatch Regex(""".+\.spm""")
                }
            }
        }
    }

    test("every file is a release asset of our own, with a size and a SHA-256 to check it against") {
        val files = TranslationCatalog.languages.flatMap { it.directions }.flatMap { it.files }
        for (file in files) {
            file.url shouldStartWith "https://github.com/DevEmperor/DictateKeyboard/releases/download/"
            file.url shouldBe "$TRANSLATION_MODELS_RELEASE/${file.fileName}.gz"
            (file.downloadBytes > 0) shouldBe true
            (file.installedBytes > file.downloadBytes) shouldBe true
            file.sha256 shouldMatch Regex("[0-9a-f]{64}")
        }
        // Release assets share one flat namespace: two files with one name would overwrite each other.
        files.map { it.url }.distinct().size shouldBe files.size
    }

    test("a route runs through English only when neither side is English") {
        TranslationCatalog.route("de", "de")!!.shouldBeEmpty()
        TranslationCatalog.route("de", "en")!!.map { it.id } shouldContainExactly listOf("de-en")
        TranslationCatalog.route("en", "de")!!.map { it.id } shouldContainExactly listOf("en-de")
        TranslationCatalog.route("de", "hi")!!.map { it.id } shouldContainExactly listOf("de-en", "en-hi")
        TranslationCatalog.route("de", "xx") shouldBe null
        TranslationCatalog.route("xx", "en") shouldBe null
    }

    test("English is never something to download") {
        TranslationCatalog.requiredLanguages("de", "en") shouldBe setOf("de")
        TranslationCatalog.requiredLanguages("en", "hi") shouldBe setOf("hi")
        TranslationCatalog.requiredLanguages("de", "hi") shouldBe setOf("de", "hi")
    }

    test("a detected language maps onto the catalog, and a script is not thrown away") {
        TranslationCatalog.codeFor("en") shouldBe "en"
        TranslationCatalog.codeFor("en-GB") shouldBe "en"
        TranslationCatalog.codeFor("de") shouldBe "de"
        TranslationCatalog.codeFor("de-AT") shouldBe "de"
        TranslationCatalog.codeFor("pt-BR") shouldBe "pt"
        TranslationCatalog.codeFor("hi-Deva") shouldBe "hi"
        TranslationCatalog.codeFor("zh-Hant") shouldBe "zh_hant"
        TranslationCatalog.codeFor("zh-Hant-TW") shouldBe "zh_hant"
        TranslationCatalog.codeFor("zh") shouldBe "zh"
        TranslationCatalog.codeFor("zh-Hans-CN") shouldBe "zh"
        // Mozilla's "no" model is its "nb" one; the catalog carries it once.
        TranslationCatalog.language("no") shouldBe null
        TranslationCatalog.codeFor("no") shouldBe "nb"
        TranslationCatalog.codeFor("nb-NO") shouldBe "nb"
        TranslationCatalog.codeFor("xx") shouldBe null
        TranslationCatalog.codeFor("") shouldBe null
    }
})
