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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** Which flag stands beside which translation language (issue #424). */
class TranslationFlagsTest : FunSpec({

    test("every language in the catalog is decided: a flag, or deliberately none") {
        // A language added by the next catalog run must not quietly show nothing: either it gets a
        // country in the table or it is named in NO_FLAG, and this is where that is noticed.
        for (code in TranslationCatalog.codes) {
            if (code in TranslationFlags.NO_FLAG) {
                TranslationFlags.countryFor(code) shouldBe null
            } else {
                TranslationFlags.countryFor(code) shouldNotBe null
            }
        }
    }

    test("the flag is two regional-indicator letters") {
        TranslationFlags.emoji("DE") shouldBe "🇩🇪"
        TranslationFlags.emoji("tw") shouldBe "🇹🇼"
    }

    test("the phone's own region wins for its own language, and only for it") {
        TranslationFlags.countryFor("de") shouldBe "DE"
        TranslationFlags.countryFor("de", deviceLanguageTag = "de-AT") shouldBe "AT"
        TranslationFlags.countryFor("en", deviceLanguageTag = "en-US") shouldBe "US"
        TranslationFlags.countryFor("pt", deviceLanguageTag = "pt-BR") shouldBe "BR"
        TranslationFlags.countryFor("zh_hant", deviceLanguageTag = "zh-Hant-HK") shouldBe "HK"
        // A German phone does not decide what English looks like.
        TranslationFlags.countryFor("en", deviceLanguageTag = "de-DE") shouldBe "GB"
        // No region on the phone: the table's choice.
        TranslationFlags.countryFor("de", deviceLanguageTag = "de") shouldBe "DE"
    }

    test("Catalan, Basque and Galician are never given Spain's flag") {
        for (code in listOf("ca", "eu", "gl")) {
            TranslationFlags.countryFor(code) shouldBe null
            TranslationFlags.countryFor(code, deviceLanguageTag = "$code-ES") shouldBe null
        }
    }
})
