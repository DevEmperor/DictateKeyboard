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
 * One downloadable per-language trigram file (`<lang>_trigrams.txt`) — the second word of context for
 * next-word prediction (issue #334). Same `"w1 w2 w3" -> count` shape as the bigram tables, downloaded
 * and removed alongside them, and read through the same [NgramIndex].
 *
 * ### Why a separate tier rather than simply more bigrams
 *
 * Measured on held-out text in two languages: after a function word — "the", "to", "of", which is
 * where 46 % of all predictions are asked for — **five times as many bigram rows are worth exactly
 * nothing**, 20.4 % → 20.4 % top-3 in English and 15.9 % → 15.9 % in German. Those pairs are all in
 * the table already; the only thing that adds information there is a second word of context, which
 * buys 4.6 pp (en) and 3.5 pp (de). The converse also holds: after a rare word this tier is worth
 * almost nothing and only more bigram rows help. The two are complements, and shipping either alone
 * leaves half the gap open — which is why the bigram tables grew to 150k in the same change.
 *
 * 100k entries rather than the 250k the issue suggests, because the gain saturates early: 50k already
 * buys 4.0 of the 5.3 points available after a function word, 100k buys 4.6, and the last 0.7 costs
 * two and a half times the bytes — bytes that are worth more spent on bigram rows.
 *
 * **Nothing here reaches the corrector.** These tables only ever add candidates to the strip, which the
 * user has to tap; the context term in `bigramContextScore` still sees one word. Re-ranking a *silent*
 * replacement on corpus context was measured to mangle 2.4–9.2 % of correctly typed words, and the
 * harness that would have to clear a deeper model has no running text in it at all.
 *
 * English is not bundled: unlike the bigram table, which the corrector also reads and which therefore
 * has to be there on the first keystroke after install, this one only improves suggestions and can
 * arrive later. Data is generated from the Leipzig Corpora Collection (wortschatz-leipzig.de, CC BY) by
 * `tools/glide-dict/generate_ngrams.py`; paste the script-printed catalog line here after uploading the
 * files as assets of the release named below.
 */
data class TrigramDict(
    val lang: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

object TrigramCatalog {
    const val REL = "https://github.com/DevEmperor/DictateKeyboard/releases/download/trigram-dicts-v1"

    /**
     * Languages whose trigram file ships in the APK — none, deliberately. See the class comment.
     * Kept as a set rather than dropped so the download and deletion paths read the same as the
     * bigram ones next to them.
     */
    val BUNDLED = emptySet<String>()

    /**
     * Filled in as languages are regenerated. A language that is not here simply has no second word of
     * context: [NgramIndex.EMPTY] answers nothing, the prediction falls back to the bigram table, and
     * the keyboard behaves exactly as it did before this existed. That is what makes rolling the
     * remaining languages out one pipeline run at a time safe.
     */
    val all: List<TrigramDict> = listOf(
        TrigramDict("de", "$REL/de_trigrams.txt", 1986190, "e7e5b18a10e2eeecbcb43b4002d3583fc8960705abab135b6adbb5bd59ba2e89"),
        TrigramDict("en", "$REL/en_trigrams.txt", 1826210, "947acde30d23ffcd28d2f01ae3225317df86bbf684e9237ba6a50f35cc394b14"),
    )

    private val byLang = all.associateBy { it.lang }

    fun forLang(lang: String): TrigramDict? = byLang[LatinLanguageProvider.normalizeLang(lang)]
}
