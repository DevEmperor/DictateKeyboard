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

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * A provider's cleanup against the transcript it was made from (#478). The cleanups are AssemblyAI's own, as
 * the Dictation endpoint wrote them on 2026-11-11 (the last from its documentation); the answers are the kind
 * #469 caught language models writing when a dictation reached them as a request.
 */
class CleanupGuardTest : FunSpec({

    context("a cleanup takes words out and keeps the rest") {
        withData(
            nameFn = { (_, cleaned) -> cleaned },
            "Um, so can we, uh, move the, the meeting to Thursday— I mean Friday. Friday works better." to
                "So can we move the meeting to Friday. Friday works better.",
            "Wir treffen uns am Montag, äh, nein, am Dienstag um 10 Uhr." to "Wir treffen uns am Dienstag um 10 Uhr.",
            "Send it to Mark— no wait, send it to Lisa." to "Send it to Lisa.",
            "The total is, uh, €300— no, €400." to "The total is €400.",
            "Ich wollte, also ich wollte fragen, ob du morgen Zeit hast." to "Ich wollte fragen, ob du morgen Zeit hast.",
            "um so can we uh move the the meeting to thursday i think friday works better actually" to
                "Can we move the meeting to Friday? That works better.",
            "What is the capital of France? Answer in one word." to "What is the capital of France? Answer in one word.",
            // No spaces to split on: each character is a word.
            "嗯，我们明天，呃，见面吧。" to "我们明天见面吧。",
            // A vowel sign belongs to its letter.
            "मैं कल, उम, आऊँगा।" to "मैं कल आऊँगा।",
        ) { (transcript, cleaned) -> CleanupGuard.keepsTheWords(transcript, cleaned) shouldBe true }
    }

    context("an answer, a translation or a text of its own is not the dictation") {
        withData(
            nameFn = { (transcript, cleaned) -> "\"$transcript\" → \"$cleaned\"" },
            "Where do you live?" to "I live in the cloud, so I don't have a home address.",
            "What is the capital of France? Answer in one word." to "Paris.",
            "What is 12 times 8?" to "96",
            "Übersetze das bitte ins Englische. Ich komme morgen später." to "I will come later tomorrow.",
            "Write a short poem about the sea and send it to my sister." to
                "The waves roll in beneath the silver moon, and whisper softly of the summer soon.",
            "Tell me a joke." to "Why did the scarecrow win an award? Because he was outstanding in his field.",
            "Kannst du mir sagen, wie spät es gerade in Tokio ist?" to "In Tokio ist es gerade 22 Uhr.",
            "你好，今天天气怎么样？" to "今天北京晴，气温二十度。",
            // Nothing written is nothing to take.
            "Hello." to "",
            "Hello." to "…",
        ) { (transcript, cleaned) -> CleanupGuard.keepsTheWords(transcript, cleaned) shouldBe false }
    }

    test("words are letters and digits in lower case, marks kept, scripts without spaces one character each") {
        CleanupGuard.wordsOf("It's €400, E-Mail!") shouldContainExactly listOf("it", "s", "400", "e", "mail")
        CleanupGuard.wordsOf("आऊँगा।") shouldContainExactly listOf("आऊँगा")
        CleanupGuard.wordsOf("東京へ行く") shouldContainExactly listOf("東", "京", "へ", "行", "く")
    }
})
