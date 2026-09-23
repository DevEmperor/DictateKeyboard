/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.prompts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The spoken command word (issue #139). The tests that matter are the ones about *not* firing: a false
 * positive means the user's sentence was executed instead of written, which is the expensive direction.
 */
class CommandTriggerTest : FunSpec({

    test("the trigger comes off the front and the rest is the instruction") {
        CommandTrigger.instructionFor("Jarvis, make this more formal", "Jarvis") shouldBe
            "make this more formal"
    }

    test("however the model decided to write it") {
        // Case, the mark after it, and a quote in front are all the transcription model's choice, not
        // the user's — they said one word either way.
        CommandTrigger.instructionFor("jarvis make this a list", "Jarvis") shouldBe "make this a list"
        CommandTrigger.instructionFor("Jarvis: translate to English", "Jarvis") shouldBe "translate to English"
        CommandTrigger.instructionFor("Jarvis. Was ist die Hauptstadt von Japan?", "Jarvis") shouldBe
            "Was ist die Hauptstadt von Japan?"
        CommandTrigger.instructionFor("„Jarvis, kürze das", "Jarvis") shouldBe "kürze das"
        // A quote *closing* right on the trigger is not tolerated, and that is the boundary rule doing
        // its job rather than a gap in it: the same character is the apostrophe in `Jarvis's`, and the
        // everyday word staying an everyday word is worth more than a shape no model actually writes.
        CommandTrigger.instructionFor("\"Jarvis\" shorten this", "Jarvis") shouldBe null
    }

    test("a trigger set with spaces tolerates whatever the model put between its words") {
        CommandTrigger.instructionFor("Hey Jarvis, shorten this", "hey jarvis") shouldBe "shorten this"
        CommandTrigger.instructionFor("Hey, Jarvis — shorten this", "hey jarvis") shouldBe "shorten this"
        // ...but the words still have to be words.
        CommandTrigger.instructionFor("HeyJarvis shorten this", "hey jarvis") shouldBe null
    }

    test("only at the very start") {
        CommandTrigger.instructionFor("Please Jarvis, make this formal", "Jarvis") shouldBe null
    }

    test("only as a whole word, which is what protects an everyday trigger") {
        // The reason the boundary is whitespace-or-sentence-mark and not "any punctuation": people pick
        // words they actually say, and a hyphen joins rather than separates.
        CommandTrigger.instructionFor("Command-line arguments go last", "command") shouldBe null
        CommandTrigger.instructionFor("Jarvis's report is attached", "Jarvis") shouldBe null
        CommandTrigger.instructionFor("Commander Riker is on the bridge", "command") shouldBe null
        // The same sentence, meant as a command, still works.
        CommandTrigger.instructionFor("Command, open the pod bay doors", "command") shouldBe
            "open the pod bay doors"
    }

    test("the trigger alone is a dictation, not an empty instruction") {
        CommandTrigger.instructionFor("Jarvis", "Jarvis") shouldBe null
        CommandTrigger.instructionFor("Jarvis.", "Jarvis") shouldBe null
    }

    test("no trigger set means the feature is off") {
        CommandTrigger.instructionFor("Jarvis, make this formal", "") shouldBe null
        CommandTrigger.instructionFor("Jarvis, make this formal", "   ") shouldBe null
    }

    test("a growing transcript is undecided until the word it opens with is finished") {
        // What the realtime stream needs: hold the preview back while "Ja" could still become "Jarvis",
        // and let it through the moment it cannot.
        CommandTrigger.match("Ja", "Jarvis") shouldBe CommandTrigger.Match.PARTIAL
        CommandTrigger.match("Jarvi", "Jarvis") shouldBe CommandTrigger.Match.PARTIAL
        CommandTrigger.match("Jarvis", "Jarvis") shouldBe CommandTrigger.Match.PARTIAL
        CommandTrigger.match("Jarvis,", "Jarvis") shouldBe CommandTrigger.Match.PARTIAL
        CommandTrigger.match("Jarvis, mach", "Jarvis") shouldBe CommandTrigger.Match.MATCHED
        CommandTrigger.match("Ja, das passt", "Jarvis") shouldBe CommandTrigger.Match.NONE
        CommandTrigger.match("Hallo zusammen", "Jarvis") shouldBe CommandTrigger.Match.NONE
    }

    test("a trigger that is not a Latin word works the same way") {
        CommandTrigger.instructionFor("小爱，把这段话改得正式一点", "小爱") shouldBe "把这段话改得正式一点"
    }
})
