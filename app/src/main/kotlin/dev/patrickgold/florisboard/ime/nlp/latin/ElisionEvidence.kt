/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp.latin

/**
 * Whether a rebuilt apostrophe form is a word the language actually writes, and whether it may replace
 * what was typed (issue #212, French follow-up).
 *
 * French elisions are 4 % of everything written in the language and all but 13 of them are missing from
 * the 68,605-word list: `tools/glide-dict/generate.py` runs every token past Hunspell, which splits on the
 * apostrophe, so `aime`, `accord` and `on` survive and `j'aime`, `d'accord` and `qu'on` do not. They have
 * to be rebuilt from a prefix and a known suffix — and that is trivially able to invent things, because
 * "on" is a word and `n'` is a prefix, so `n'on` falls straight out of it.
 *
 * ### Why the corpus and not the word list
 *
 * What separates `j'aime` from `n'on` is grammar — after `j' m' t' s' n'` comes a verb — and the word list
 * carries no part of speech. The bigram table does, implicitly: it is running text, so it contains every
 * elision people write and none of the ones they don't. Measured against the shipped French data
 * (`fr_bigrams_150k.txt` as the table, the *trigram* file held out as the test set):
 *
 * ```
 * rule                          elisions restored   correct words rewritten   nonsense in the strip
 * word list only (as proposed)             78.2 %                   0.017 %             631 words
 * + attested in the corpus                 97.2 %                  0.0009 %                     0
 * ```
 *
 * It settles the two cases the word list cannot decide at all. **h**: elision happens before h muet and
 * not before h aspiré, and no dictionary marks which is which — but `l'homme` is attested and `lhéros`
 * never is. **Names**: `Omar`, `Otto`, `Anna` and `El` are all in the word list, and `t'Omar` is in no
 * corpus, so it is never built.
 *
 * ### Why a ratio and not "is the typed word known"
 *
 * "Is the apostrophe-less form a real word" cannot be asked of the word list either: it holds `Jaime` (the
 * name), `sil`, `javais` and `jetais`, so the most valuable restorations would be blocked by corpus noise
 * while `c'an` sailed through. The corpus answers the useful question instead — which of the two spellings
 * people actually write — and [DOMINANCE] is where that answer stops being close:
 *
 * ```
 * dune    d'une is  745× more common   → replaced
 * quelle  qu'elle is  2×  more common   → offered only, it is a real word
 * den     d'en is  2.2× more common     → offered only
 * ```
 *
 * Pure functions with no Android dependency, so the rule is unit-testable without a device — same reason
 * [AutoCommitGate] and [DictFold] live on their own.
 */
object ElisionEvidence {

    /**
     * How many times more often the elision has to appear than the apostrophe-less spelling before it is
     * swapped in silently.
     *
     * Measured on the held-out trigram corpus; the column that decides is the third one, which is the
     * failure people remember (a correctly typed word rewritten):
     *
     * ```
     * ratio   elisions restored   correct words rewritten   what the last step bought
     *     8              98.0 %                   0.0102 %  —
     *    12              97.0 %                   0.0033 %  protects quelle/qu'elle
     *    16              97.2 %                   0.0009 %  protects lune/l'une
     *    24              95.7 %                   0.0008 %  costs 1.5 pp for nothing
     * ```
     *
     * At 16 the only correctly written words still touched are `dune`, `qua` and `lest`, where the elision
     * is 400–745× the more common reading; the typed word stays left-most in the strip and one backspace
     * puts it back (issue #150).
     */
    const val DOMINANCE = 16

    /**
     * The lookup key for an already-folded word.
     *
     * [DictFold.foldFrench] leaves the apostrophe alone — it is punctuation, not a diacritic — so a corpus
     * that writes `qu’à` and a reconstruction that writes `qu'a` would never meet. Unifying the two here
     * rather than inside the fold keeps the dictionary's own keys untouched.
     */
    fun key(foldedWord: String): String =
        if (foldedWord.indexOf('’') < 0) foldedWord else foldedWord.replace('’', '\'')

    /**
     * Per-word counts read out of a `key\tcount\n` n-gram table, for the words this rule can ask about:
     * every token carrying an apostrophe, and the apostrophe-less spelling of each.
     *
     * Everything else is dropped, which is what keeps this affordable — French has 26,204 distinct tokens
     * in its bigram table and 1,926 of them are reachable here. [fold] is the language's own fold, so the
     * counts are keyed exactly the way [LatinLanguageProvider] looks words up.
     */
    fun parse(text: String, fold: (String) -> String): Map<String, Int> {
        if (text.isEmpty()) return emptyMap()
        val all = HashMap<String, Int>(1 shl 14)
        text.lineSequence().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach
            val count = line.substring(tab + 1).toIntOrNull() ?: return@forEach
            var from = 0
            while (from < tab) {
                var to = line.indexOf(' ', from)
                if (to < 0 || to > tab) to = tab
                if (to > from) {
                    val token = key(fold(line.substring(from, to)))
                    if (token.isNotEmpty()) all[token] = (all[token] ?: 0) + count
                }
                from = to + 1
            }
        }
        val kept = HashMap<String, Int>()
        for ((token, count) in all) {
            if (token.indexOf('\'') < 0) continue
            kept[token] = count
            val bare = token.filterNot { it == '\'' }
            all[bare]?.let { kept[bare] = it }
        }
        return kept
    }

    /** Whether [candidateKey] is a form the language is on record as writing. */
    fun isAttested(counts: Map<String, Int>, candidateKey: String): Boolean = counts.containsKey(candidateKey)

    /**
     * Whether [candidateKey] may replace [typedKey] without being asked.
     *
     * A typed spelling the corpus has never seen counts as one sighting, not as zero: otherwise a single
     * stray line would be enough to rewrite it, and the elisions worth taking are attested thousands of
     * times over.
     */
    fun mayReplace(counts: Map<String, Int>, typedKey: String, candidateKey: String): Boolean {
        val elided = counts[candidateKey] ?: return false
        return elided >= DOMINANCE * maxOf(counts[typedKey] ?: 0, 1)
    }
}
