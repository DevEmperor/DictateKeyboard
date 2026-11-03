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

class FinalFullStopTest : FunSpec({

    test("one sentence loses its full stop and keeps its capital") {
        FinalFullStop.drop("Buy tofu.") shouldBe "Buy tofu"
        FinalFullStop.drop("Tofu kaufen.") shouldBe "Tofu kaufen"
        FinalFullStop.drop("Milch, Eier, Brot.") shouldBe "Milch, Eier, Brot"
    }

    test("the length of the sentence does not matter") {
        FinalFullStop.drop("Ich komme heute etwas später, weil der Zug wegen einer Signalstörung Verspätung hat.") shouldBe
            "Ich komme heute etwas später, weil der Zug wegen einer Signalstörung Verspätung hat"
    }

    test("several sentences keep every mark") {
        FinalFullStop.drop("I'm late. The train is delayed.") shouldBe "I'm late. The train is delayed."
        FinalFullStop.drop("Hi! Buy tofu.") shouldBe "Hi! Buy tofu."
        FinalFullStop.drop("Ready? Go.") shouldBe "Ready? Go."
        FinalFullStop.drop("Wait… buy tofu.") shouldBe "Wait… buy tofu."
        FinalFullStop.drop("He said \"yes.\" Then he left.") shouldBe "He said \"yes.\" Then he left."
    }

    test("a line break makes it more than one sentence") {
        FinalFullStop.drop("Buy tofu\nCall mom.") shouldBe "Buy tofu\nCall mom."
        FinalFullStop.drop("- Buy tofu\n- Call mom.") shouldBe "- Buy tofu\n- Call mom."
    }

    test("question and exclamation marks stay") {
        FinalFullStop.drop("Where are you?") shouldBe "Where are you?"
        FinalFullStop.drop("Great!") shouldBe "Great!"
    }

    test("an ellipsis stays") {
        FinalFullStop.drop("Wait...") shouldBe "Wait..."
        FinalFullStop.drop("Hmm…") shouldBe "Hmm…"
    }

    test("a dot that belongs to the last word stays") {
        FinalFullStop.drop("Buy milk, eggs etc.") shouldBe "Buy milk, eggs etc."
        FinalFullStop.drop("See you at 6 p.m.") shouldBe "See you at 6 p.m."
        FinalFullStop.drop("Spende an den Verein e.V.") shouldBe "Spende an den Verein e.V."
        FinalFullStop.drop("Go with plan B.") shouldBe "Go with plan B."
    }

    test("a number at the end is not taken for an ordinal") {
        FinalFullStop.drop("Meet at 5.") shouldBe "Meet at 5"
    }

    test("dots inside the sentence that end nothing do not count") {
        FinalFullStop.drop("z.B. Tofu kaufen.") shouldBe "z.B. Tofu kaufen"
        FinalFullStop.drop("Call Dr. Smith.") shouldBe "Call Dr. Smith"
        FinalFullStop.drop("It costs 3.50 euros.") shouldBe "It costs 3.50 euros"
    }

    test("nothing to drop") {
        FinalFullStop.drop("Buy tofu") shouldBe "Buy tofu"
        FinalFullStop.drop("Buy tofu. ") shouldBe "Buy tofu. "
        FinalFullStop.drop(".") shouldBe "."
        FinalFullStop.drop("...") shouldBe "..."
        FinalFullStop.drop("") shouldBe ""
    }

    test("the full stops of other scripts") {
        FinalFullStop.drop("豆腐を買う。") shouldBe "豆腐を買う"
        FinalFullStop.drop("你好。买豆腐。") shouldBe "你好。买豆腐。"
        FinalFullStop.drop("टोफू खरीदो।") shouldBe "टोफू खरीदो"
    }

    test("what the user took over of the preview counts toward the sentence") {
        FinalFullStop.droppable("and milk.", head = "Buy tofu") shouldBe true
        FinalFullStop.droppable("Call mom.", head = "Buy tofu.") shouldBe false
        FinalFullStop.droppable("Call mom.", head = "Buy tofu\n") shouldBe false
    }

    test("isEnd knows every mark a restored stop must not follow") {
        ".!?…。！？।".forEach { FinalFullStop.isEnd(it) shouldBe true }
        FinalFullStop.isEnd('u') shouldBe false
        FinalFullStop.isEnd(')') shouldBe false
    }
})
