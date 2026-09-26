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

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.util.Locale

/**
 * Guards [DictateLanguages.matchDevice], the lookup behind the one-time seeding of the device/system
 * dictation language on a fresh install (DictateLegacyMigrator.seedDeviceLanguageIfNeeded). The
 * device language must map to a supported code even when the system reports a regional tag such as
 * `de-DE`, otherwise a German user never gets German added to their list.
 *
 * Also guards [DictateLanguages.expectedLanguages], which decides what a provider with a list-shaped
 * language field is told about a multi-language setup (issue #99).
 */
class DictateLanguageMatchTest : FunSpec({
    context("matchDevice resolves a plain or regional system locale to its base language") {
        withData(
            nameFn = { "${it.first} -> ${it.second}" },
            Locale("de") to "de",
            Locale("de", "DE") to "de",
            Locale("de", "AT") to "de",
            Locale("de", "CH") to "de",
            Locale.GERMANY to "de",
            Locale("en", "US") to "en",
            Locale("fr", "FR") to "fr",
            Locale("pt", "BR") to "pt",
        ) { (locale, expected) ->
            DictateLanguages.matchDevice(locale)?.code shouldBe expected
        }
    }

    context("matchDevice prefers the full BCP-47 tag for regional variants that have their own code") {
        withData(
            nameFn = { "${it.first.toLanguageTag()} -> ${it.second}" },
            Locale.forLanguageTag("zh-CN") to "zh-CN",
            Locale.forLanguageTag("zh-TW") to "zh-TW",
        ) { (locale, expected) ->
            DictateLanguages.matchDevice(locale)?.code shouldBe expected
        }
    }

    context("matchDevice returns null for unsupported languages and never returns detect") {
        withData(
            nameFn = { "locale=<${it.toLanguageTag()}>" },
            Locale.forLanguageTag("xx"),
            Locale("", ""),
        ) { locale ->
            DictateLanguages.matchDevice(locale) shouldBe null
        }
    }

    // Issue #99: someone who dictates in four languages cannot express that with one language code, and
    // the code that gets sent instead is the one that forces the wrong transcription. Providers whose
    // language field takes a list can be told the actual set — but only while nothing is pinned.
    context("expectedLanguages hands the user's selection to a provider that takes a list") {
        test("auto-detect with several languages passes them all, in the selected order") {
            DictateLanguages.expectedLanguages("detect", "detect,de,en,fr") shouldBe listOf("de", "en", "fr")
        }

        test("a pinned language is never widened") {
            DictateLanguages.expectedLanguages("de", "detect,de,en,fr") shouldBe emptyList()
        }

        test("a single language is left to the ordinary one-language hint") {
            DictateLanguages.expectedLanguages("detect", "detect,de") shouldBe emptyList()
        }

        test("no language at all stays free detection") {
            DictateLanguages.expectedLanguages("detect", "detect") shouldBe emptyList()
        }

        test("a wish list of languages is not an expectation") {
            val many = DictateLanguages.all.filter { it.code != DictateLanguages.DETECT }.take(9)
            DictateLanguages.expectedLanguages(
                "detect",
                DictateLanguages.serializeSelection(many),
            ) shouldBe emptyList()
        }

        test("regional codes lose their region, and two variants of one language count once") {
            // A hint cannot act on CN vs TW anyway, and a code the provider rejects fails the whole
            // request — where the user previously just got free detection.
            DictateLanguages.expectedLanguages("detect", "detect,zh-CN,zh-TW,en") shouldBe listOf("zh", "en")
        }

        test("detect itself is never sent as a language") {
            DictateLanguages.expectedLanguages("detect", "detect,de,en")
                .shouldNotContain(DictateLanguages.DETECT)
        }
    }

    // Issue #431: switching the keyboard's language can switch dictation too — but only ever to one of the
    // languages the user dictates in, never widening the list and never to "detect".
    context("forKeyboard picks the dictation language a keyboard language stands for") {
        test("a regional keyboard finds its bare language") {
            DictateLanguages.forKeyboard(Locale("en", "GB"), "detect,en,hi")?.code shouldBe "en"
            DictateLanguages.forKeyboard(Locale("hi", "IN"), "detect,en,hi")?.code shouldBe "hi"
        }

        test("a language outside the selection leaves dictation alone") {
            DictateLanguages.forKeyboard(Locale("de", "DE"), "detect,en,hi") shouldBe null
        }

        test("detect is never the answer") {
            DictateLanguages.forKeyboard(Locale("en", "US"), "detect") shouldBe null
            DictateLanguages.forKeyboard(Locale("", ""), "detect,en") shouldBe null
        }

        test("the full tag wins over the bare language") {
            DictateLanguages.forKeyboard(Locale.forLanguageTag("zh-TW"), "zh-CN,zh-TW")?.code shouldBe "zh-TW"
            DictateLanguages.forKeyboard(Locale.forLanguageTag("zh-CN"), "zh-CN,zh-TW")?.code shouldBe "zh-CN"
            // No exact match: the same language in another region is still that language.
            DictateLanguages.forKeyboard(Locale.forLanguageTag("zh-CN"), "en,zh-TW")?.code shouldBe "zh-TW"
        }

        test("a script subtag does not hide the language") {
            DictateLanguages.forKeyboard(Locale.forLanguageTag("hi-Latn"), "en,hi")?.code shouldBe "hi"
            DictateLanguages.forKeyboard(Locale.forLanguageTag("sr-Latn-RS"), "en,sr")?.code shouldBe "sr"
        }

        test("keyboard codes the catalog spells differently") {
            DictateLanguages.forKeyboard(Locale("nb", "NO"), "en,no")?.code shouldBe "no"
            DictateLanguages.forKeyboard(Locale("fil", "PH"), "en,tl")?.code shouldBe "tl"
            DictateLanguages.forKeyboard(Locale("jv"), "en,jw")?.code shouldBe "jw"
            // Java's legacy "iw" still comes out of Locale("he") on some runtimes; the tag is "he".
            DictateLanguages.forKeyboard(Locale("he", "IL"), "en,he")?.code shouldBe "he"
        }
    }
})
