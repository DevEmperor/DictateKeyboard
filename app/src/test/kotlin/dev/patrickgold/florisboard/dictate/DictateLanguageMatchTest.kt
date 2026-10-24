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

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.text.Collator
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

    // Issue #431 follow-up: the catalog is kept in English-name order, but the picker shows each language
    // under its name in the reader's language. Left in catalog order, a German reader finds "Deutsch"
    // among the G's (German) and "Niederländisch" among the D's (Dutch), and a Hindi reader no order at all.
    context("sortedForDisplay orders the picker by the names the reader sees") {
        test("a German reader finds Deutsch under D and Niederländisch under N") {
            val names = DictateLanguages.sortedForDisplay(DictateLanguages.all, Locale.GERMAN)
                .map { it.displayName(Locale.GERMAN) }
            names.indexOf("Deutsch") shouldBeLessThan names.indexOf("Englisch")
            names.indexOf("Niederländisch") shouldBeGreaterThan names.indexOf("Englisch")
        }

        withData(
            nameFn = { "every neighbour in ${it.toLanguageTag()} collation order" },
            Locale.GERMAN,
            Locale.ENGLISH,
            Locale("hi"),
            Locale.JAPANESE,
            Locale("ar"),
        ) { locale ->
            val collator = Collator.getInstance(locale)
            val names = DictateLanguages.sortedForDisplay(DictateLanguages.all, locale)
                .drop(1)
                .map { it.displayName(locale) }
            names.zipWithNext().forEach { (a, b) ->
                withClue("\"$a\" before \"$b\"") { collator.compare(a, b) shouldBeLessThanOrEqual 0 }
            }
        }

        test("detect stays on top, and nothing is lost or doubled") {
            val sorted = DictateLanguages.sortedForDisplay(DictateLanguages.all, Locale.GERMAN)
            sorted.first().code shouldBe DictateLanguages.DETECT
            sorted shouldContainExactlyInAnyOrder DictateLanguages.all
        }
    }

    context("forImport: the share screen's own language, until then the keyboard's (issue #461)") {
        test("nothing picked on the share screen yet: the active dictation language, as before") {
            DictateLanguages.forImport(importCode = "", activeCode = "de") shouldBe "de"
            DictateLanguages.forImport(importCode = "", activeCode = DictateLanguages.DETECT) shouldBe DictateLanguages.DETECT
        }

        test("a pick on the share screen wins, detect included") {
            DictateLanguages.forImport(importCode = "ar", activeCode = "en") shouldBe "ar"
            DictateLanguages.forImport(importCode = DictateLanguages.DETECT, activeCode = "en") shouldBe DictateLanguages.DETECT
        }

        test("a code the catalog no longer knows falls back to detect") {
            DictateLanguages.forImport(importCode = "zz", activeCode = "en") shouldBe DictateLanguages.DETECT
        }
    }
})
