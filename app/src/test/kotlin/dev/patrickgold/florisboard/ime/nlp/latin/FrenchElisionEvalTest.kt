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

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A measuring stand for French apostrophe restoration (issue #212, French follow-up).
 *
 * The rule has to be measured rather than argued, because it is the only place in the keyboard that can
 * *invent* a word: everything else picks from the dictionary, and this builds `j'aime` out of `j` and
 * `aime` — which is exactly as able to build `n'on` out of `n` and `on`. What separates the two is
 * grammar, and the word list has none, so the evidence has to come from running text.
 *
 * ### The three populations, and why all three
 *
 * Like [AutoCommitGate], a rule here is only ever judged on every population at once:
 *
 *  - an elision written without its apostrophe, which must be restored — the point of the feature;
 *  - a correctly written word that happens to start with an elision prefix (`non`, `test`, `savoir`,
 *    `Meaux`), which must be left alone and must not even be *offered* a nonsense alternative;
 *  - a word the French dictionary does not know — a name, a foreign word — standing in for the vocabulary
 *    no word list has. Same stand-in [AutoCommitGate] uses, and the failure people remember.
 *
 * ### Held out on purpose
 *
 * The table is built from the bigram file and measured on the **trigram** file, which is a different
 * extraction of the corpus. Measuring on the same file the counts came from would only prove that a
 * lookup table can look itself up.
 *
 * Both corpora are generated artefacts and absent on a clean checkout (`tools/glide-dict/dist/` is
 * ignored), so this skips rather than fails when they are missing — same as [ContextCompletionEvalTest].
 */
class FrenchElisionEvalTest {

    private companion object {
        const val DICT = "tools/glide-dict/dist/fr.json"
        const val TABLE = "tools/glide-dict/dist/fr_bigrams_150k.txt"
        const val HELD_OUT = "tools/glide-dict/dist/fr_trigrams_100k.txt"

        /** Word lists standing in for names and foreign vocabulary the French dictionary cannot hold. */
        val FOREIGN = listOf("es", "it", "pt", "ca", "nl")

        // Floors, not targets. Measured 97.2 % / 0.0009 % when this was written; a change that trades one
        // for the other has to be argued in the commit, not slipped past a single-number assertion.
        const val MIN_RESTORED = 0.95
        const val MAX_HARM = 0.00002
        const val MAX_FOREIGN_HARM = 0.0005
    }

    private fun repoFile(relative: String): File? {
        var dir = File(".").absoluteFile
        repeat(5) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    private fun fold(word: String) = DictFold.foldKey("fr", word)
    private fun key(word: String) = ElisionEvidence.key(fold(word))
    private fun bare(word: String) = word.filterNot { it == '\'' || it == '’' }

    /** Every token of a `key\tcount` n-gram file, with the counts of every n-gram it appears in. */
    private fun tokens(file: File): Map<String, Long> {
        val out = HashMap<String, Long>(32_768)
        file.forEachLine { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEachLine
            val count = line.substring(tab + 1).toLongOrNull() ?: return@forEachLine
            for (token in line.substring(0, tab).split(' ')) {
                if (token.isNotEmpty()) out[token] = (out[token] ?: 0L) + count
            }
        }
        return out
    }

    @Test
    fun measureFrenchElisionRestoration() {
        val dict = repoFile(DICT)
        val table = repoFile(TABLE)
        val heldOut = repoFile(HELD_OUT)
        if (dict == null || table == null || heldOut == null) {
            println("\n=== #212 · French elisions ===")
            println("skipped: the generated corpora are not in the repository")
            return
        }

        // The index the provider builds: fold key -> frequency and the spelling to commit.
        val freq = HashMap<String, Int>(70_000)
        val canonical = HashMap<String, String>(70_000)
        for ((word, f) in EvalKeyboard.parseDict(dict.readText())) {
            val k = fold(word)
            if ((freq[k] ?: -1) < f) {
                freq[k] = f
                canonical[k] = word
            }
        }
        val corpus = ElisionEvidence.parse(table.readText()) { fold(it) }

        // The same two routes [LatinLanguageProvider.suggest] assembles, in the same order.
        fun forms(word: String): LinkedHashMap<String, String> {
            val typedFreq = freq[fold(word)] ?: 0
            val out = LinkedHashMap<String, String>()
            for (i in 1 until word.length) {
                val variant = word.substring(0, i) + "'" + word.substring(i)
                val k = fold(variant)
                val f = freq[k] ?: continue
                if (f > typedFreq) out.putIfAbsent(key(fold(canonical[k] ?: variant)), canonical[k] ?: variant)
            }
            for ((prefix, suffix) in LatinLanguageProvider.frenchElisionSplits(word)) {
                val spelling = canonical[fold(suffix)] ?: continue
                val display = "$prefix'$spelling"
                val k = key(fold(display))
                if (ElisionEvidence.isAttested(corpus, k)) out.putIfAbsent(k, display)
            }
            return out
        }

        fun replacement(word: String): String? {
            val candidates = forms(word)
            val only = candidates.keys.singleOrNull() ?: return null
            return if (ElisionEvidence.mayReplace(corpus, key(word), only)) candidates.getValue(only) else null
        }

        var restored = 0L
        var missed = 0L
        var harmed = 0L
        var untouched = 0L
        var offered = 0L
        val harms = HashMap<String, Long>()
        for ((token, count) in tokens(heldOut)) {
            val typed = bare(token)
            if (typed.length < 3 || !typed.all { it.isLetter() }) continue
            val fix = replacement(typed)
            if (token != typed) {
                if (fix != null && key(fix) == key(token)) restored += count else missed += count
            } else {
                if (fix != null && key(fix) != key(token)) {
                    harmed += count
                    harms[
                        "$token→$fix",
                    ] = (harms["$token→$fix"] ?: 0L) + count
                } else {
                    untouched += count
                    if (forms(typed).isNotEmpty()) offered += count
                }
            }
        }

        val restoredShare = restored.toDouble() / (restored + missed)
        val harmShare = harmed.toDouble() / (harmed + untouched)
        println()
        println("=== #212 · French elisions — table from the bigrams, measured on the held-out trigrams ===")
        println("%-34s %,14d".format("elision tokens seen", restored + missed))
        println("%-34s %13.1f%%".format("restored", 100 * restoredShare))
        println()
        println("%-34s %,14d".format("apostrophe-free tokens seen", harmed + untouched))
        println("%-34s %13.4f%%  <-- the harm".format("silently rewritten", 100 * harmShare))
        println("%-34s %13.4f%%".format("merely offered an alternative", 100.0 * offered / (harmed + untouched)))
        println("  " + harms.entries.sortedByDescending { it.value }.take(6).joinToString { "${it.key} (${it.value})" })

        // The vocabulary no word list holds. A correctly typed name rewritten is the failure that gets
        // remembered, so it is counted per word rather than by corpus weight — every one of them matters.
        println()
        println("%-10s %10s %10s %9s".format("stand-in", "unknown", "rewritten", "share"))
        var worstForeign = 0.0
        for (lang in FOREIGN) {
            val file = repoFile("tools/glide-dict/dist/$lang.json") ?: continue
            val words = EvalKeyboard.parseDict(file.readText()).keys
                .filter { it.length >= 3 && it.all { ch -> ch.isLetter() } && fold(it) !in freq }
            val hit = words.count { replacement(it) != null }
            val share = hit.toDouble() / words.size.coerceAtLeast(1)
            worstForeign = maxOf(worstForeign, share)
            println("%-10s %10d %10d %8.3f%%".format(lang, words.size, hit, 100 * share))
        }
        println()

        assertTrue(restoredShare >= MIN_RESTORED, "restored ${100 * restoredShare}% < ${100 * MIN_RESTORED}%")
        assertTrue(harmShare <= MAX_HARM, "harm ${100 * harmShare}% > ${100 * MAX_HARM}%")
        assertTrue(worstForeign <= MAX_FOREIGN_HARM, "foreign harm ${100 * worstForeign}% > ${100 * MAX_FOREIGN_HARM}%")
    }
}
