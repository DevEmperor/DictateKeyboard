/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import java.text.Normalizer

/**
 * How a typed word is reduced to the key it is looked up under (issue #265).
 *
 * For most languages this is what it has always been — lowercasing. French additionally removes
 * diacritics and expands its common ligatures, so an unaccented prefix still reaches its dictionary
 * spelling: `ho` finds `hôte`, and `oeu` finds `œuvre`.
 *
 * Arabic script needs more. A reader sees مدرسة and مَدْرَسَة as one word, and writers use أ إ آ and ا
 * interchangeably because only ا sits on the base keyboard — the others hide behind a long press. Left
 * alone, every such spelling is simply "not in the dictionary": the strip stays empty and the spell
 * checker underlines correct Arabic. Folding those distinctions away is what lets the engine find the
 * word, and because the dictionary stores the *correct* spelling under the folded key, finding it is
 * the same act as correcting it — ان becomes أن, الى becomes إلى.
 *
 * ### Which distinctions are folded, and why those
 *
 * Measured against the shipped Arabic dictionary (78,914 words) and the 21,086 spellings its Hunspell
 * filter rejected — the misspellings people actually write. "Correctable" counts rejected spellings
 * that land on a real word; "merged" counts real words that end up sharing a key:
 *
 * | rule                        | correctable |  merged | ratio |
 * |-----------------------------|------------:|--------:|------:|
 * | alef  آ أ إ ٱ ٲ ٳ → ا         |       2,755 |   2,281 |  1.21 |
 * | + alef maqsura  ى → ي        |       4,584 |   3,019 |  1.52 |
 * | + hamza on yeh  ئ → ي        |       4,670 |   3,068 |  1.52 |
 * | + ta marbuta  ة → ه          |       5,123 |   4,507 |  1.14 |
 *
 * Ta marbuta is deliberately **not** folded. It buys 453 more corrections and costs 1,439 more merged
 * pairs — and unlike the hamza forms, which are orthographic noise, ة and ه carry meaning (the feminine
 * ending against the masculine suffix, عليه "on him" against علية "upper room"). Merging the two would
 * make the engine unable to tell those apart for the sake of a fifth of the corrections the other rules
 * already deliver. Tashkil, tatweel and the zero-width joiners fold for free: they merge nothing,
 * because no two distinct words differ only by them.
 *
 * Persian and Urdu write the same letters with different code points (ی for yeh, ک for kaf), and the
 * Arabic subtype types eastern digits — both are unified here so a word is one entry however it was
 * keyed in.
 *
 * The generator applies the same mark-stripping when it builds the dictionary
 * (`tools/glide-dict/wordfilter.py`), so tatweel-stretched and vowelled spellings never become separate
 * entries in the first place.
 *
 * Pure functions with no Android dependency, so they are unit-testable — same reason
 * [LatinLanguageProvider.normalizeLang] lives in a companion object.
 */
object DictFold {
    /** Languages written in the Arabic script, using [foldArabic] instead of a plain lowercase. */
    private val ARABIC_SCRIPT = setOf("ar", "fa", "ur", "ckb")
    /** French accents sit behind long presses, so completion matches their base-letter spelling too. */
    private const val FRENCH = "fr"

    /**
     * Whether [lang] folds to something other than its lowercase form. Callers use this to skip folded
     * lookup/index work for the languages that don't need it, so English and the rest stay on their
     * original code path.
     */
    fun hasNonTrivialFold(lang: String): Boolean = lang in ARABIC_SCRIPT || lang == FRENCH

    /**
     * The dictionary key for [word] in [lang]. Both the stored words and the typed word go through this,
     * so the two always meet.
     */
    fun foldKey(lang: String, word: String): String = when {
        lang in ARABIC_SCRIPT -> foldArabic(word)
        lang == FRENCH -> foldFrench(word)
        else -> word.lowercase()
    }

    /**
     * Reduces French accents to their base letters for dictionary lookup. NFD covers acute, grave,
     * circumflex, diaeresis and cedilla whether the input arrived precomposed or as a combining mark;
     * `œ` and `æ` need their conventional two-letter expansions because Unicode does not decompose them.
     */
    fun foldFrench(word: String): String = Normalizer.normalize(word, Normalizer.Form.NFD)
        .asSequence()
        .filter { it.category != CharCategory.NON_SPACING_MARK }
        .joinToString("")
        .lowercase()
        .replace("œ", "oe")
        .replace("æ", "ae")

    /**
     * Reduce an Arabic-script word to its lookup key. Also lowercases, because Arabic text is full of
     * embedded Latin (brand names, units, URLs) and those still need case folding.
     */
    fun foldArabic(word: String): String {
        val sb = StringBuilder(word.length)
        for (ch in word) {
            when {
                isArabicMark(ch) -> Unit                      // tashkil, tatweel, zero-width joiners
                else -> sb.append(FOLD_MAP[ch] ?: ch)
            }
        }
        return sb.toString().lowercase()
    }

    /**
     * Diacritics and formatting characters that carry no lexical weight: the vowel points, the Quranic
     * annotation marks, the superscript alef, tatweel (decorative elongation) and the zero-width
     * joiners that Persian and Urdu use inside compounds.
     *
     * Deliberately a list of Arabic ranges rather than "every combining mark" — Indic vowel signs are
     * combining marks too, and they are very much part of the word.
     */
    private fun isArabicMark(ch: Char): Boolean = when (ch) {
        'ـ' -> true                                    // tatweel (decorative elongation)
        '‌', '‍' -> true                          // zero-width non-joiner / joiner
        'ٰ' -> true                                    // superscript alef
        else -> ch in 'ؐ'..'ؚ' ||                 // Arabic signs
            ch in 'ً'..'ٟ' ||                     // tashkil (vowel points)
            ch in 'ۖ'..'ۭ' ||                     // Quranic annotation marks
            ch in 'ࣣ'..'ࣿ'                        // Arabic Extended-A marks
    }

    private val FOLD_MAP: Map<Char, Char> = buildMap {
        // Alef, written five ways, typed as the bare form because the rest need a long press.
        for (ch in listOf('آ', 'أ', 'إ', 'ٱ', 'ٲ', 'ٳ')) put(ch, 'ا')
        // Alef maqsura and the two yeh forms Persian/Urdu use, plus hamza-on-yeh.
        for (ch in listOf('ى', 'ی', 'ئ', 'ې')) put(ch, 'ي')
        // Keheh (Persian/Urdu) → Arabic kaf.
        for (ch in listOf('ک', 'ڪ')) put(ch, 'ك')
        // Heh variants that are the *same* letter in another orthography. Note ta marbuta (ة) is
        // absent on purpose — see the class comment.
        for (ch in listOf('ہ', 'ۃ', 'ە')) put(ch, 'ه')
        // Eastern Arabic and extended (Persian/Urdu) digits → ASCII. The Arabic subtype ships the
        // eastern number row, so the same number is typed differently depending on the layout.
        for (i in 0..9) {
            put('٠' + i, '0' + i)
            put('۰' + i, '0' + i)
        }
    }

    /**
     * Whether [ch] belongs to a word for the purpose of scanning backwards over already-typed text.
     *
     * `Char.isLetter()` alone is not enough outside Latin: Devanagari, Bengali and Tamil write their
     * vowels as combining marks (Unicode categories Mn and Mc), so a scan that stops at the first
     * non-letter cuts किताब after क and hands the bigram model a fragment.
     */
    fun isWordChar(ch: Char): Boolean = when (ch.category) {
        CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK -> true
        else -> ch.isLetter()
    }
}
