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

    test("tighten takes the space out from in front of a mark inside one piece") {
        // Verbatim from NVIDIA's German FastConformer, whose vocabulary spells `▁,` and `▁.` — "space,
        // then the mark" — as its two most frequent tokens, so this is what it genuinely predicts.
        TranscriptJoin.tighten("Alles hat ein Ende , nur die Wurst hat zwei .") shouldBe
            "Alles hat ein Ende, nur die Wurst hat zwei."
        TranscriptJoin.tighten("Hast du die Datei schon abgeschickt ?") shouldBe
            "Hast du die Datei schon abgeschickt?"
    }

    test("tighten leaves a transcript that was already spaced correctly untouched") {
        // The English Parakeet and GigaAM write their marks against the word; this must be a no-op there.
        val clean = "Well, I don't wish to see it any more. It is very like the old portrait."
        TranscriptJoin.tighten(clean) shouldBe clean
        TranscriptJoin.tighten("") shouldBe ""
    }

    test("tighten obeys the same set, so French keeps the space before ? and !") {
        val french = ".,"
        TranscriptJoin.tighten("Ça va ?", french) shouldBe "Ça va ?"
        TranscriptJoin.tighten("Ça va , vraiment .", french) shouldBe "Ça va, vraiment."
        // An empty set is a caller saying "no mark binds backwards here" and must change nothing.
        TranscriptJoin.tighten("Ça va ?", "") shouldBe "Ça va ?"
    }

    test("tighten closes a whole run of spaces in front of a mark, and consecutive marks too") {
        TranscriptJoin.tighten("Moment   , bitte") shouldBe "Moment, bitte"
        TranscriptJoin.tighten("Warte .  .  .") shouldBe "Warte..."
    }

    test("tighten reflows nothing else — only the space in front of a mark is its business") {
        // Spacing *after* a mark is left exactly as the model wrote it. Collapsing that too would mean
        // rewriting whitespace nobody complained about, on every transcript, to fix one tokenizer.
        TranscriptJoin.tighten("Moment   ,  bitte") shouldBe "Moment,  bitte"
        TranscriptJoin.tighten("zwei  Wörter") shouldBe "zwei  Wörter"
    }

    test("the default set is the conservative rule, not a superset invented here") {
        // Mirrors the "default" punctuation rule in the localization extension; a caller that can reach
        // the active rule passes it instead.
        TranscriptJoin.DEFAULT_TIGHTENING_SYMBOLS shouldBe ".,;:?!‽"
    }

    test("a dictation after a finished sentence gets the space it was missing (issue #443's video)") {
        // `Testing 1, 2, 3.Testing one-two-three.` — the second dictation glued to the first.
        TranscriptJoin.separatorBefore("1, 2, 3.", "Testing one-two-three.") shouldBe " "
        TranscriptJoin.separatorBefore("Hey there", "how are you") shouldBe " "
        TranscriptJoin.separatorBefore("Wait,", "what") shouldBe " "
        TranscriptJoin.separatorBefore("(see above)", "Then") shouldBe " "
    }

    test("whitespace already in the field, or at the head of the dictation, is the separator") {
        TranscriptJoin.separatorBefore("Hey there ", "how") shouldBe ""
        TranscriptJoin.separatorBefore("First line\n", "Second") shouldBe ""
        TranscriptJoin.separatorBefore("Hey there", " how") shouldBe ""
    }

    test("the start of a field, an unreadable field and an empty dictation get nothing") {
        TranscriptJoin.separatorBefore("", "Hello") shouldBe ""
        // Null is "could not read", not "empty" — a stray space at the start of a field would be ours.
        TranscriptJoin.separatorBefore(null, "Hello") shouldBe ""
        TranscriptJoin.separatorBefore("Hello", "") shouldBe ""
    }

    test("a dictation opening with a mark that binds backwards goes straight against the word") {
        TranscriptJoin.separatorBefore("Hey there", ".") shouldBe ""
        TranscriptJoin.separatorBefore("Hey there", ", and then") shouldBe ""
        TranscriptJoin.separatorBefore("Hey there", "?") shouldBe ""
    }

    test("opening marks on the left and closing marks on the right are not word edges") {
        TranscriptJoin.separatorBefore("Note (", "see above") shouldBe ""
        TranscriptJoin.separatorBefore("\"", "Hello") shouldBe ""
        TranscriptJoin.separatorBefore("Hey", ")") shouldBe ""
        // …while an opening mark at the head of the dictation still starts a new word.
        TranscriptJoin.separatorBefore("Hey there.", "(Aside)") shouldBe " "
        TranscriptJoin.separatorBefore("Hola.", "¿Qué tal?") shouldBe " "
    }

    test("scripts written without spaces never get one, on either side of the seam") {
        TranscriptJoin.separatorBefore("你好", "我很好") shouldBe ""
        TranscriptJoin.separatorBefore("こんにちは", "元気です") shouldBe ""
        TranscriptJoin.separatorBefore("สวัสดี", "ครับ") shouldBe ""
        TranscriptJoin.separatorBefore("OK", "你好") shouldBe ""
        TranscriptJoin.separatorBefore("你好。", "OK") shouldBe ""
        // Korean spaces its words, so it is treated like any other spaced script.
        TranscriptJoin.separatorBefore("안녕하세요.", "반갑습니다") shouldBe " "
    }

    test("the marks come from the caller, so a rule without opening marks spaces fewer seams") {
        TranscriptJoin.separatorBefore("Hey there.", "(Aside)", followingSymbols = "") shouldBe ""
        TranscriptJoin.separatorBefore("50%", "of it", precedingSymbols = "") shouldBe ""
        TranscriptJoin.separatorBefore("50%", "of it") shouldBe " "
    }

    // Long-form segments (#170), seen on the emulator 2026-11-04: Smart Turn cut after "this is", Whisper
    // closed the fragment with "..." and the next segment went on in lower case.
    test("a cut the sentence went on through loses the ellipsis Whisper put at it") {
        val head = StringBuilder("Hello, this is...")
        TranscriptJoin.dropCutEllipsis(head, "a short test.") shouldBe true
        TranscriptJoin.appendPiece(head, "a short test.").toString() shouldBe "Hello, this is a short test."

        val single = StringBuilder("Wir treffen uns… ")
        TranscriptJoin.dropCutEllipsis(single, "morgen am Bahnhof.") shouldBe true
        single.toString() shouldBe "Wir treffen uns"

        val four = StringBuilder("and then....")
        TranscriptJoin.dropCutEllipsis(four, "nothing") shouldBe true
        four.toString() shouldBe "and then"
    }

    test("a new sentence, a script without case or a head without an ellipsis keeps the head") {
        listOf(
            "Hello, this is..." to "A short test.",
            "Hello, this is..." to "你好",
            "Hello, this is." to "a short test.",
            "Wait." to "and then",
            "" to "and then",
            "Hello, this is..." to "",
        ).forEach { (head, next) ->
            val builder = StringBuilder(head)
            TranscriptJoin.dropCutEllipsis(builder, next) shouldBe false
            builder.toString() shouldBe head
        }
    }
})
