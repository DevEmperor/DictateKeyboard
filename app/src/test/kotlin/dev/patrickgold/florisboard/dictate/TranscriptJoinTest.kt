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
import io.kotest.matchers.shouldBe

class TranscriptJoinTest : FunSpec({

    test("word-shaped pieces keep the space that separates them") {
        TranscriptJoin.join("Hey there", "how are you") shouldBe "Hey there how are you"
    }

    test("a piece that is only a sentence mark binds to the word in front of it (issue #356)") {
        // Exactly the reported case: the transducer releases the period as its own segment, seconds
        // after the words settled, because it needs the trailing silence to be sure of it.
        TranscriptJoin.join("Hey there", ".") shouldBe "Hey there."
        TranscriptJoin.join("Was it", "?") shouldBe "Was it?"
        TranscriptJoin.join("Wait", ",") shouldBe "Wait,"
    }

    test("a piece that merely opens with a mark carries its own words along") {
        TranscriptJoin.join("Hey there", ". How are you") shouldBe "Hey there. How are you"
    }

    test("the tightening set decides, so French keeps the space it wants before ? and !") {
        val french = ".,"
        TranscriptJoin.join("Ça va", "?", french) shouldBe "Ça va ?"
        TranscriptJoin.join("Ça va", ".", french) shouldBe "Ça va."
    }

    test("a mark swallows spacing that is already there") {
        TranscriptJoin.join("Hey there ", ".") shouldBe "Hey there."
        TranscriptJoin.join("Hey there", "  .  ") shouldBe "Hey there."
    }

    test("an empty side is not a reason to invent a space") {
        TranscriptJoin.join("", "Hey there") shouldBe "Hey there"
        TranscriptJoin.join("Hey there", "") shouldBe "Hey there"
        TranscriptJoin.join("Hey there", "   ") shouldBe "Hey there"
        TranscriptJoin.join("", ".") shouldBe "."
    }

    test("appendPiece builds the same string the pieces arrive in") {
        val out = StringBuilder()
        listOf("Hey there", ".", "How are you", "?").forEach { TranscriptJoin.appendPiece(out, it) }
        out.toString() shouldBe "Hey there. How are you?"
    }

    test("appendPiece leaves a blank piece alone, including the very first one") {
        val out = StringBuilder()
        TranscriptJoin.appendPiece(out, "  ")
        out.toString() shouldBe ""
        TranscriptJoin.appendPiece(out, "Hey")
        TranscriptJoin.appendPiece(out, "")
        out.toString() shouldBe "Hey"
    }

    test("the default set is the conservative rule, not a superset invented here") {
        // Mirrors the "default" punctuation rule in the localization extension; a caller that can reach
        // the active rule passes it instead.
        TranscriptJoin.DEFAULT_TIGHTENING_SYMBOLS shouldBe ".,;:?!‽"
    }
})
