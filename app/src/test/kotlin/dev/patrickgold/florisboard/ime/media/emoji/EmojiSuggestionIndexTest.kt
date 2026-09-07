/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.media.emoji

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rules that turn CLDR annotations into a word→emoji index (issue #338).
 *
 * The input is a hand-written excerpt of the real annotation data, spellings and all — including the
 * three entries that decided the design: the mushroom that lists "pizza" as a keyword, the long
 * descriptive name that would otherwise make "the" fire, and two hearts competing for one word.
 */
class EmojiSuggestionIndexTest {

    private fun emoji(value: String, name: String, vararg keywords: String) =
        Emoji(value, name, keywords.toList())

    // Straight out of annotations/en.txt, abbreviated.
    private val heart = emoji("❤️", "red heart", "heart", "red")
    private val arrowHeart = emoji("💘", "heart with arrow", "arrow", "cupid", "heart", "love")
    private val loveLetter = emoji("💌", "love letter", "heart", "letter", "love", "mail")
    private val pizza = emoji("🍕", "pizza", "cheese", "slice")
    private val mushroom = emoji("🍄‍🟫", "brown mushroom", "mushroom", "pizza", "toadstool")
    private val rofl = emoji("🤣", "rolling on the floor laughing", "face", "floor", "laugh", "rofl")
    private val joy = emoji("😂", "face with tears of joy", "face", "joy", "laugh", "tear")
    private val sweat = emoji("😅", "grinning face with sweat", "face", "laugh", "sweat")
    private val fire = emoji("🔥", "fire", "flame", "tool")
    private val heartOnFire = emoji("❤️‍🔥", "heart on fire", "burn", "fire", "love")

    private val all = listOf(heart, arrowHeart, loveLetter, pizza, mushroom, rofl, joy, sweat, fire, heartOnFire)
    private val index = EmojiSuggestionIndex.build(all)

    private fun lookup(word: String) = index.lookup(word).map { it.value }

    /** The whole name is the one match nothing may override — and the reason `pizza` is not a mushroom. */
    @Test
    fun `the whole name always matches`() {
        assertEquals(listOf("🍕"), lookup("pizza"))
        assertEquals(listOf("🔥"), lookup("fire"))
    }

    /**
     * The rule the whole design rests on. "rolling on the floor laughing" is a description, so its
     * individual words mean nothing; "red heart" is a name, so "heart" does.
     */
    @Test
    fun `only a short name contributes its individual words`() {
        // Words that occur *only* inside the long name. ("floor" would be a poor test: CLDR lists it
        // as a keyword of 🤣 as well, and keywords are a different rule with its own limit.)
        assertTrue(lookup("the").isEmpty(), "a descriptive name must not make 'the' fire")
        assertTrue(lookup("rolling").isEmpty())
        assertTrue(lookup("tears").isEmpty())
        assertEquals("❤️", lookup("heart").first())
        assertEquals("❤️", lookup("red").first())
    }

    /** An emoji outside the priority list is never suggested, however well it matches. */
    @Test
    fun `an emoji outside the priority list never appears`() {
        assertTrue(all.contains(mushroom))
        assertTrue(lookup("mushroom").isEmpty())
        assertTrue(lookup("brown mushroom").isEmpty())
        assertTrue(lookup("pizza").none { it.startsWith("🍄") }, "the mushroom claims 'pizza' as a keyword")
    }

    /** Where several allowed emoji match, the priority list decides — one decision for every language. */
    @Test
    fun `the priority list breaks the tie`() {
        // 😂 sits ahead of 😅 and 🤣 in the list, and all three carry the keyword "laugh".
        assertEquals(listOf("😂", "😅"), lookup("laugh"))
    }

    /** A whole-name match outranks a keyword match, whatever the priority order says. */
    @Test
    fun `how a word was matched outranks how popular the emoji is`() {
        // 🔥 is named "fire" and 😂 (far earlier in the list) merely carries it as a keyword.
        val built = EmojiSuggestionIndex.build(
            listOf(emoji("😂", "face with tears of joy", "fire", "joy"), fire),
        )
        assertEquals("🔥", built.lookup("fire").first().value)
    }

    @Test
    fun `a generic keyword is dropped once it belongs to too many emoji`() {
        val generic = (1..EmojiSuggestionIndex.KEYWORD_EMOJI_LIMIT + 1).map { n ->
            emoji("😂", "laughing test $n", "everywhere")
        }
        val specific = emoji("🔥", "fire", "rare")
        val built = EmojiSuggestionIndex.build(generic + specific)
        assertTrue(built.lookup("everywhere").isEmpty())
        assertEquals(listOf("🔥"), built.lookup("rare").map { it.value })
    }

    @Test
    fun `blocked words never fire`() {
        for (word in EmojiSuggestionIndex.BLOCKED) {
            assertTrue(index.lookup(word).isEmpty(), "'$word' is blocked and must stay silent")
        }
    }

    @Test
    fun `matching is exact and never partial`() {
        assertTrue(lookup("hear").isEmpty())
        assertTrue(lookup("hearts").isEmpty())
        assertTrue(lookup("pizzas").isEmpty())
    }

    @Test
    fun `a word carries at most two emoji`() {
        for (word in listOf("heart", "laugh", "fire", "red")) {
            assertTrue(
                index.lookup(word).size <= EmojiSuggestionIndex.MAX_PER_WORD,
                "'$word' offered ${index.lookup(word).size} emoji",
            )
        }
    }

    @Test
    fun `words below the minimum length are not indexed`() {
        val short = EmojiSuggestionIndex.build(listOf(emoji("🔥", "fire", "up")))
        assertTrue(short.lookup("up").isEmpty())
    }

    /** Names in scripts that use combining marks must not be torn apart mid-word (see #265). */
    @Test
    fun `devanagari names survive tokenization`() {
        assertEquals(listOf("लाल", "दिल"), EmojiSuggestionIndex.tokenize("लाल दिल"))
        val hindi = EmojiSuggestionIndex.build(listOf(emoji("❤️", "लाल दिल", "दिल", "प्यार")))
        assertEquals(listOf("❤️"), hindi.lookup("दिल").map { it.value })
    }

    /** "I love " — the space is typed, the word is finished, and the emoji should still be offered. */
    @Test
    fun `the word before the cursor is found once it is finished`() {
        assertEquals("love", EmojiSuggestionIndex.completedWordBefore("I love "))
        assertEquals("love", EmojiSuggestionIndex.completedWordBefore("I love! "))
        assertEquals("love", EmojiSuggestionIndex.completedWordBefore("I love."))
        assertEquals("", EmojiSuggestionIndex.completedWordBefore("I love"), "still being typed")
        assertEquals("", EmojiSuggestionIndex.completedWordBefore(""))
        assertEquals("", EmojiSuggestionIndex.completedWordBefore("   "))
    }
}
