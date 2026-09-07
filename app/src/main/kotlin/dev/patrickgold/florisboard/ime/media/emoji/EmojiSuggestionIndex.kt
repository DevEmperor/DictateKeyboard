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

import dev.patrickgold.florisboard.ime.nlp.latin.DictFold

/**
 * Word → emoji, derived from the CLDR annotations the app already ships (issue #338).
 *
 * The point of building this rather than shipping a curated word list: `assets/ime/media/emoji/
 * annotations/` holds **50 languages**, ~1900 emoji each, and the file for the active locale is parsed
 * at preload anyway. Inverting it costs one more pass over data already in memory, and the feature
 * starts in every one of those languages at once instead of in English with 49 translation debts.
 *
 * Inverting it *naively* is a trap, though, and the trap is worth writing down: CLDR keywords never
 * repeat the emoji's own name, so a keyword-only index answers `pizza` with 🍄 (the brown mushroom
 * lists "pizza"), `fire` with ❤️‍🔥 and `heart` with 💘. The name has to be indexed too — and then the
 * *names* bring their own noise, because they are descriptions: "face with tears of joy" would make
 * `with` and `face` fire, "rolling on the floor laughing" would make `the` fire.
 *
 * Three rules answer all of that, and none of them knows what language it is looking at:
 *
 *  1. **The whole name always counts.** `pizza` → 🍕 is never in doubt.
 *  2. **A name contributes single words only when it is short** ([NAME_TOKEN_LIMIT] tokens or fewer).
 *     Descriptive names are exactly the long ones, so this one rule silences `the`, `with`, `and`,
 *     `of`, `that` in English, `auf`, `mit`, `und`, `der` in German and `में` in Hindi — measured, not
 *     assumed — while "red heart" still contributes `heart`.
 *  3. **A keyword counts only if it is specific**, i.e. it belongs to at most [KEYWORD_EMOJI_LIMIT]
 *     emoji. This is what drops the category words that survive rule 2 because they *are* short names:
 *     `flag` (274 emoji), `face` (163), `animal` (122), `hand` (58).
 *
 * What no CLDR file can answer is which of several matching emoji people actually use — and that
 * question is language-independent: deciding once that ❤️ beats 💘 for the concept "heart" settles it
 * for `herz` and `दिल` too, because they land on the same emoji. Hence [PRIORITY], which is both the
 * filter (nothing outside it is ever suggested — this is what stops 🍄) and the tie-break.
 */
class EmojiSuggestionIndex private constructor(private val byWord: Map<String, List<Emoji>>) {

    /** The emojis for [word], best first, or empty when nothing matches. Exact match, never partial. */
    fun lookup(word: String): List<Emoji> = byWord[word.lowercase()].orEmpty()

    /** How many words the index answers. Only for logs and tests. */
    val wordCount: Int
        get() = byWord.size

    companion object {
        /** The index of an unavailable or unannotated locale: answers nothing. */
        val Empty = EmojiSuggestionIndex(emptyMap())

        /**
         * Longest name still allowed to contribute its individual words, in tokens. Two covers
         * "red heart", "grinning cat", "birthday cake"; three would already let "face with tears of
         * joy" through, which is where `with` and `face` come from.
         */
        internal const val NAME_TOKEN_LIMIT = 2

        /**
         * How many emoji a keyword may belong to and still be specific enough to fire. Measured
         * against the English file: `love` reaches 35 and should fire, `food` 55 and `hand` 58 should
         * not, so the line sits between them.
         */
        internal const val KEYWORD_EMOJI_LIMIT = 40

        /** Below this, a word is too short to mean anything on its own ("ok", "hi"). */
        internal const val MIN_WORD_LENGTH = 3

        /**
         * At most this many emoji per word. The strip shows as many as the user's "maximum candidate
         * count" allows, so this only has to be generous enough not to be the binding limit at the
         * top of that slider's useful range — measured, the quality holds this far: `love` gives
         * ❤️😍🥰😘🌹, `happy` gives 😂🙂😁😄🤣🥳.
         */
        internal const val MAX_PER_WORD = 6

        /**
         * How much of [PRIORITY] a mere *keyword* may reach. The list is ordered by everyday use, so
         * this says: a keyword can only summon an emoji people actually send.
         *
         * It exists because the two kinds of match are not equally trustworthy. A name is what an
         * emoji *is* — "unicorn" is 🦄, and no ranking should stand in the way of that. A keyword is a
         * loose association, and the far end of the list is full of ones that read as absurd out of
         * context: the trash can lists "can", the koala "down" (as in *down under*), the frying pan
         * "over" (as in *over easy*). Measured against the 250 most frequent English and German words,
         * this one line is the difference between 23 of them firing and 14.
         */
        internal const val KEYWORD_CORE_SIZE = 172

        /**
         * The emoji a suggestion may ever produce, in order of how much people use them.
         *
         * Both halves matter. As a **filter** it is what keeps the index sane: CLDR happily maps
         * `pizza` to a mushroom, and outside this list that mapping simply does not exist. As an
         * **order** it settles the ties CLDR cannot: `heart` matches a dozen hearts, and the first one
         * here is the one that gets suggested — in every language at once, because the tie is between
         * emoji, not between words.
         *
         * Deliberately no flags, no professions, no skin-tone or ZWJ variants: none of them is what
         * someone means by a single typed word, and each would be a way to get a suggestion wrong.
         */
        internal val PRIORITY: List<String> = listOf(
            "❤️", "😂", "😍", "🔥", "👍", "😊", "🎉", "😢", "😭", "😅",
            "🙏", "💀", "🥰", "😘", "😎", "🤔", "😉", "🙂", "😁", "😄",
            "🤣", "😡", "😱", "🥳", "😴", "🤗", "🤝", "👏", "💪", "🙌",
            "👋", "✌️", "🤞", "💯", "✨", "⭐", "🌟", "💥", "💫", "🌈",
            "☀️", "🌙", "⛅", "🌧️", "❄️", "☃️", "🌊", "💧", "🌸", "🌹",
            "🌻", "🌵", "🌲", "🌳", "🍀", "🍁", "🎂", "🍰", "🍕", "🍔",
            "🍟", "🌮", "🍣", "🍩", "🍪", "🍫", "🍦", "🍎", "🍌", "🍓",
            "🍞", "🧀", "🥚", "🍳", "☕", "🍵", "🍺", "🍷", "🥂", "🥤",
            "🍾", "🐶", "🐱", "🐻", "🐰", "🦊", "🐼", "🦁", "🐮", "🐷",
            "🐸", "🐵", "🐦", "🦄", "🐴", "🐝", "🦋", "🐟", "🐬", "🏠",
            "🏫", "🏥", "🏢", "🚗", "🚕", "🚌", "🚲", "✈️", "🚀", "🚂",
            "🚢", "⛰️", "🏖️", "📱", "💻", "📞", "📷", "⌚", "⏰", "📅",
            "📚", "📖", "📝", "✏️", "✉️", "📦", "🔑", "🔒", "💡", "🎧",
            "🎤", "💰", "💸", "💳", "🎁", "🎈", "🎄", "🎃", "💍", "👑",
            "🎵", "🎸", "🎮", "⚽", "🏀", "🏆", "🎯", "✅", "❌", "⚠️",
            "❓", "❗", "💤", "💭", "💬", "💊", "🩺", "🚑", "👶", "👵",
            "💃", "🕺", "🏃", "🚶", "🧘", "🏊", "🚴", "👗", "👕", "👟",
            "🧢", "🕶️", "👌", "🐭", "🐔", "🦉", "🐳", "🐢", "🐍", "🕷️",
            "🏈", "🎾", "🏏", "🎲", "🚿", "🛏️", "🧹", "🔨", "🚒", "😔",
            "😕", "😳", "🤯", "😬", "😩", "🥺", "🤤", "🤒", "🤕", "🤧",
            "😷", "🥱", "🤫", "🤭", "🙄", "😏", "😒", "😌", "🤠", "🥸",
            "🤓", "🧐", "😇", "🤡", "👻", "👽", "🤖", "👼", "🧙", "🧚",
            "🧜", "👦", "👧", "👴", "🤰", "👪", "👀", "👁️", "👂", "👃",
            "👄", "👅", "🦷", "🧠", "🦴", "🦵", "🦶", "🐎", "🐺", "🐗",
            "🦌", "🐇", "🐁", "🐹", "🐨", "🐯", "🦒", "🦓", "🐘", "🦏",
            "🦛", "🐪", "🦙", "🐂", "🐄", "🐖", "🐑", "🐐", "🦔", "🦇",
            "🦥", "🦦", "🦘", "🐓", "🐧", "🕊️", "🦅", "🦆", "🦢", "🦜",
            "🦚", "🐊", "🦎", "🐉", "🦕", "🦖", "🦈", "🐙", "🦀", "🦞",
            "🦐", "🐌", "🐛", "🐜", "🪲", "🦗", "🌴", "🌱", "🌿", "☘️",
            "🍂", "🍃", "💐", "🌺", "🌼", "🌷", "🌾", "🍄", "🍇", "🍈",
            "🍉", "🍊", "🍋", "🍍", "🥭", "🍐", "🍑", "🍒", "🫐", "🍅",
            "🥑", "🍆", "🥔", "🥕", "🥒", "🥦", "🧄", "🧅", "🥜", "🌰",
            "🥐", "🥨", "🥯", "🥞", "🧇", "🥓", "🥪", "🌯", "🍿", "🧂",
            "🍝", "🍡", "🥟", "🍮", "🧁", "🥧", "🍬", "🍭", "🧊", "🍶",
            "🧉", "🥛", "🏦", "🏨", "🏭", "🏰", "⛪", "🕌", "⛲", "⛺",
            "🏕️", "🏜️", "🌋", "🏟️", "🧱", "🪨", "🌅", "🌇", "🏙️", "🚆",
            "🚇", "🚉", "🚜", "🏍️", "🛹", "⚓", "⛵", "🚤", "🚁", "🛰️",
            "🧳", "🚧", "⏱️", "🌡️", "☁️", "🌪️", "🌀", "☂️", "☄️", "🎆",
            "🎇", "🧨", "🎀", "🎫", "🏐", "🏸", "🎿", "🛷", "🪀", "🪁",
            "🕹️", "🃏", "🧵", "🧶", "👓", "👔", "👖", "🧣", "🧤", "🧥",
            "🧦", "👘", "👙", "👛", "👜", "🎒", "💄", "📢", "🔔", "📻",
            "🎷", "🎺", "🪗", "🎻", "🥁", "☎️", "🔋", "🖨️", "⌨️", "📀",
            "🧮", "📺", "📼", "🕯️", "🔦", "📓", "📜", "📰", "🔖", "🏷️",
            "🪙", "🧾", "📧", "📮", "🖊️", "🖌️", "🖍️", "💼", "📋", "📌",
            "📎", "✂️", "🗑️", "🔓", "🪓", "⛏️", "🗡️", "💣", "🛡️", "🔧",
            "🪛", "⚙️", "🔗", "⛓️", "🧰", "🧲", "🪜", "⚗️", "🧬", "🔬",
            "🔭", "💉", "🩻", "🚪", "🪞", "🪟", "🪑", "🚽", "🛁", "🪒",
            "🧺", "🪣", "🧼", "🪥", "🧽", "🗿", "✖️", "➕", "➖", "➗",
            "♾️", "🚫",
        )

        /**
         * Words that reach an emoji through a keyword the rules above cannot fault, and still should
         * not fire. Measured leftovers rather than a guessed stopword list, and short on purpose: the
         * rules are meant to do the work, this only catches what they demonstrably miss.
         */
        internal val BLOCKED: Set<String> = setOf(
            // English: all of these arrive as keywords of a perfectly ordinary emoji.
            "you", "where", "open", "body", "face", "hand", "eyes", "mouth", "people", "person",
            "over", "well", "man", "high", "keep", "four", "point",
            // German. "man" is above and covers both languages; "mal" and "durch" are the arithmetic
            // keywords of ✖️ and ➗, and "zwei" reaches 🙌 through "two hands".
            "mal", "zwei", "durch", "schön",
            // Hindi: "साथ" (with) is a keyword of the cup with straw.
            "साथ",
        )

        /** Everything in [PRIORITY], keyed the way [rankOf] compares. */
        private val priorityRanks: Map<String, Int> =
            PRIORITY.withIndex().associate { (i, e) -> e.withoutVariationSelectors() to i }

        /**
         * Where [value] sits in [PRIORITY], or null when it is not allowed to be suggested at all.
         *
         * Compared without variation selectors: whether an emoji carries U+FE0F depends on the CLDR
         * file, and a list that had to spell each of them exactly right would silently lose entries.
         */
        private fun rankOf(value: String): Int? = priorityRanks[value.withoutVariationSelectors()]

        private fun String.withoutVariationSelectors(): String = filterNot { it == '\uFE0F' }

        /**
         * The individual words of an emoji name.
         *
         * Split on [DictFold.isWordChar], the same boundary rule the typing side uses, because it
         * keeps combining marks inside their word — the ordinary letter-only spelling tears Devanagari
         * and every other Indic script apart mid-word (see the dictionary pipeline, issue #265).
         */
        internal fun tokenize(name: String): List<String> {
            val out = mutableListOf<String>()
            val current = StringBuilder()
            for (ch in name) {
                if (DictFold.isWordChar(ch)) {
                    current.append(ch)
                } else if (current.isNotEmpty()) {
                    out.add(current.toString())
                    current.setLength(0)
                }
            }
            if (current.isNotEmpty()) out.add(current.toString())
            return out.filter { it.length >= MIN_WORD_LENGTH }.map { it.lowercase() }
        }

        /**
         * Builds the index for one locale from its annotated emoji list.
         *
         * [emojis] is the whole annotated set for the locale — the same list the fuzzy `:query` search
         * runs over — so the keyword counts of rule 3 are taken across *all* emoji, not just the
         * suggestible ones. A keyword is generic because it describes a hundred emoji, whether or not
         * those hundred are ones we would ever suggest.
         */
        fun build(emojis: List<Emoji>): EmojiSuggestionIndex {
            if (emojis.isEmpty()) return Empty
            val keywordEmojiCount = HashMap<String, Int>()
            for (emoji in emojis) {
                for (keyword in emoji.keywords.mapTo(HashSet()) { it.trim().lowercase() }) {
                    if (keyword.isNotEmpty()) {
                        keywordEmojiCount[keyword] = (keywordEmojiCount[keyword] ?: 0) + 1
                    }
                }
            }

            // word -> (tier, priority rank, emoji); tier 0 = whole name, 1 = name word, 2 = keyword.
            val hits = HashMap<String, MutableList<Triple<Int, Int, Emoji>>>()
            fun add(word: String, tier: Int, rank: Int, emoji: Emoji) {
                if (word.length < MIN_WORD_LENGTH || word in BLOCKED) return
                hits.getOrPut(word) { mutableListOf() }.add(Triple(tier, rank, emoji))
            }

            for (emoji in emojis) {
                val rank = rankOf(emoji.value) ?: continue
                val name = emoji.name.trim().lowercase()
                if (name.isNotEmpty()) add(name, 0, rank, emoji)
                val nameWords = tokenize(emoji.name)
                if (nameWords.size <= NAME_TOKEN_LIMIT) {
                    for (word in nameWords.toSet()) add(word, 1, rank, emoji)
                }
                if (rank < KEYWORD_CORE_SIZE) {
                    for (keyword in emoji.keywords.mapTo(HashSet()) { it.trim().lowercase() }) {
                        if ((keywordEmojiCount[keyword] ?: 0) <= KEYWORD_EMOJI_LIMIT) {
                            add(keyword, 2, rank, emoji)
                        }
                    }
                }
            }

            val byWord = HashMap<String, List<Emoji>>(hits.size)
            for ((word, candidates) in hits) {
                val best = candidates
                    .sortedWith(compareBy({ it.first }, { it.second }))
                    .map { it.third }
                    .distinctBy { it.value }
                    .take(MAX_PER_WORD)
                if (best.isNotEmpty()) byWord[word] = best
            }
            return EmojiSuggestionIndex(byWord)
        }

        /**
         * The word the cursor has just finished, or empty when it is still inside one.
         *
         * This is the moment the reporter's example lives in: "I love " — the space is typed, there is
         * no composing word any more, and the emoji should still be offered for what was just written.
         * Returns empty while the cursor sits directly after a word character, because then the word is
         * still being typed and the ordinary current-word path already covers it.
         */
        internal fun completedWordBefore(textBeforeSelection: String): String {
            var end = textBeforeSelection.length
            while (end > 0 && !DictFold.isWordChar(textBeforeSelection[end - 1])) end--
            if (end == textBeforeSelection.length) return "" // still inside a word
            var start = end
            while (start > 0 && DictFold.isWordChar(textBeforeSelection[start - 1])) start--
            return textBeforeSelection.substring(start, end)
        }
    }
}
