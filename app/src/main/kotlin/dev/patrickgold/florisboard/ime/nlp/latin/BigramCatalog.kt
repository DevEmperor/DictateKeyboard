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
 * One downloadable per-language bigram file (`<lang>_bigrams.txt`) for the autocorrect Tier 2 context
 * model: a `"w1 w2" -> count` table used to re-rank corrections by the previous word. Downloaded and
 * removed alongside the glide-typing dictionary (same trigger: adding/removing an input language), so the
 * device never holds bigram data for languages the user doesn't use.
 *
 * English ships bundled in the APK ([ime/dict/en_bigrams.txt]); every other language is downloaded on
 * demand. Data is generated from the Leipzig Corpora Collection (wortschatz-leipzig.de, CC BY) by
 * `tools/glide-dict/generate_ngrams.py`, which also writes that language's trigram table; paste the
 * script-printed catalog line here after uploading the files as assets of the release named below.
 *
 * ### 150,000 pairs, counted from sentences
 *
 * Every entry was rebuilt for issue #334: **150,000** pairs, counted from the corpus text rather than
 * from Leipzig's pre-computed `co_n.txt`, which the old 60,000-pair files came from. The larger table
 * is not cosmetic — measured, it nearly triples top-3 prediction after a word the small one had no
 * continuations for (6.8 % → 19.2 % in English) — and counting it from sentences is what makes it
 * comparable with the trigram table that backs off to it.
 *
 * Sizes run from 2.2 MB to 7.1 MB, and that spread is script rather than vocabulary: the tables all
 * hold the same number of entries, but a Georgian or Tamil character costs three UTF-8 bytes where a
 * Latin one costs one.
 *
 * ### A regenerated table gets a new file name, never a replaced asset
 *
 * Hence `de_bigrams_150k.txt` beside the older `de_bigrams.txt`. Overwriting an asset in place — which
 * is what was done when the Icelandic and Georgian *word lists* were corrected — is safe only when the
 * old file is one nobody should keep. Here it is not: a device still running the previous app version
 * holds the previous catalog, so it would download the new file, fail the byte-size check in
 * [GlideDictionaryManager] against the size it knows, throw the file away, and — since
 * `ensureDownloaded` runs on every subtype activation — do that again and again. The prune size in the
 * name comes from `generate_ngrams.py`, so this happens by itself rather than by remembering to.
 */
data class BigramDict(
    val lang: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

object BigramCatalog {
    const val REL = "https://github.com/DevEmperor/DictateKeyboard/releases/download/bigram-dicts-v1"

    /** Languages whose bigram file ships in the APK ([ime/dict/<lang>_bigrams.txt]) — never downloaded/deleted. */
    val BUNDLED = setOf("en")

    val all: List<BigramDict> = listOf(
        BigramDict("ar", "$REL/ar_bigrams_150k.txt", 3603741, "8274541df961d87fc35bd1197478d18a27e8caf3fec9ae923b5fc9907d874d01"),
        BigramDict("bg", "$REL/bg_bigrams_150k.txt", 3906960, "a63b6fbd4358b6cb25439f695977e7d0efe4921c671ae57cff9f44ae9620fe7a"),
        BigramDict("bn", "$REL/bn_bigrams_150k.txt", 5559168, "796c2461d295a3d1fb932ea6fdbfbdb39ee2456fb07344a69127705b9391d501"),
        BigramDict("ca", "$REL/ca_bigrams_150k.txt", 2337008, "295bc7246c996b1ddff9d9e95bf4eac605682c9e33b842a7cdbc81469b322b93"),
        BigramDict("cs", "$REL/cs_bigrams_150k.txt", 2443020, "b6c732b3d59a70cd2f7246b487934726d39a6aa187bc17d1fdc5cf89dc3108a5"),
        BigramDict("da", "$REL/da_bigrams_150k.txt", 2372283, "7a2275ae478c99407f8d6b9a33f34520412c521fdcb3cf4b1af2d0c318fe3dd2"),
        BigramDict("de", "$REL/de_bigrams_150k.txt", 2479539, "f86806430c7293d2b7d5fefadeec920d865f74c725b7087c44f6bff01ba22ae3"),
        BigramDict("el", "$REL/el_bigrams_150k.txt", 4103910, "ae39b9a55ac121fa5c24f585fcc6b98b31e12f8254ee77b6950de61a9873ea66"),
        BigramDict("eo", "$REL/eo_bigrams_150k.txt", 2392708, "e0e86a4b4d279b0552f087cb70f6174b4c945fcd63c87d2ee85592b1ed3f06a4"),
        BigramDict("es", "$REL/es_bigrams_150k.txt", 2390341, "2760f1b379ae8c153ce0aeed6975f8527ce4aa3e9830805178cb02dcbd3c2079"),
        BigramDict("et", "$REL/et_bigrams_150k.txt", 2426766, "2b9f9a8ae69fe17ec97487a1be10f90a86b3362a6b7c58a3d637c8aa97644bd2"),
        BigramDict("fa", "$REL/fa_bigrams_150k.txt", 3284610, "4a61a5d26b930fcd9e0d2e525de0fbc20ab317a6b21e0ee228e32cbcb81cc7a0"),
        BigramDict("fi", "$REL/fi_bigrams_150k.txt", 2768864, "31daa91168040b693eea0ef221b12cbf5b57e3ef42d7b66af1091f1b9a127e33"),
        BigramDict("fr", "$REL/fr_bigrams_150k.txt", 2463147, "c89218c92a5adb9c2b93d9ccb3a20215ab22be8874fa6fd15c18cf02d46039ae"),
        BigramDict("he", "$REL/he_bigrams_150k.txt", 3313088, "5a3d7c9c64be2b76a81c0142f096c5ff613a515395692b5e0a7d38cbfcf276d1"),
        BigramDict("hi", "$REL/hi_bigrams_150k.txt", 4479839, "d1a6ad274c56fa5339f2c4a2b4b54b65a3d5db45e8cebc19690251cc715f6eec"),
        BigramDict("hr", "$REL/hr_bigrams_150k.txt", 2344424, "d4da046c4e43bb86407630898e486a94b6f98b61203abe51c856b1fbe1ede9b2"),
        BigramDict("hu", "$REL/hu_bigrams_150k.txt", 2679337, "20f3a206b8e95d68d49d3afa0db304f289f0d991c0aaaaa80828c84bb8df48b3"),
        BigramDict("hy", "$REL/hy_bigrams_150k.txt", 4304023, "5e285e371c6ea11c6f54dcb0f838d021950f16f3f7070877ade3ab3861b1366d"),
        BigramDict("id", "$REL/id_bigrams_150k.txt", 2597584, "24f867fb6ac6b96ffe9e7021eb703268cfc4a959330081b3593ecf37e7804d89"),
        BigramDict("is", "$REL/is_bigrams_150k.txt", 2502238, "ca2a91017f51928b6c101ebc678630e8c726feea0a03c157b8ebdd9b88ba8b7c"),
        BigramDict("it", "$REL/it_bigrams_150k.txt", 2385437, "9b98bffb279f8b2a20000579f3e9c8f3cc08baeeca8d90611c2464c3076e150a"),
        BigramDict("ka", "$REL/ka_bigrams_150k.txt", 7060477, "b79a70744973b57892d563461267583ef08fce2927ff513127e91c4ef4b86f15"),
        BigramDict("lt", "$REL/lt_bigrams_150k.txt", 2668801, "0bae036ec146bf4a8e5d40e243a474dfc11d4d2f5071eed5ef40a608a1d516d4"),
        BigramDict("lv", "$REL/lv_bigrams_150k.txt", 2637360, "aab3c54d0e79b827dabe5801b6e087db06cb3c8b7d2c9090c3e5e282ed41d548"),
        BigramDict("nb", "$REL/nb_bigrams_150k.txt", 2314564, "c9dc254f2e024d60dbfa27720ab6b0289d773f3cea2779e3cc8588fc3b9fc84c"),
        BigramDict("nl", "$REL/nl_bigrams_150k.txt", 2300477, "501ca84d1823f92218acb7a08ca89a41cbf3b09ee9641fe703d8a889afdd369c"),
        BigramDict("nn", "$REL/nn_bigrams_150k.txt", 2178758, "cf78004a09cfdfd78c5cefe1b3f5826087a4ceba3bf2db468f3fc107068d22de"),
        BigramDict("pl", "$REL/pl_bigrams_150k.txt", 2563078, "ba911f770dfb9fb84f4019f75925780befe50aec15aa64da7c177374d4335a34"),
        BigramDict("pt", "$REL/pt_bigrams_150k.txt", 2392332, "8e917f93571f009dbaea07098469ca2c604d5a4e8085a6951536fb1ff8e8f8af"),
        BigramDict("ro", "$REL/ro_bigrams_150k.txt", 2426155, "1b9a27f83e19c3f15284c4c6d9ec1d492ae8b993947c21b240379d36bf2ab06b"),
        BigramDict("ru", "$REL/ru_bigrams_150k.txt", 4320336, "1628e2b03df78971ac297a40552605156ed2b838a48f06852f4f8ccd1160089f"),
        BigramDict("sk", "$REL/sk_bigrams_150k.txt", 2447852, "95eb7d51e946f23496f633643682c20fb4d8107a1c83aa5479e12afee47b6666"),
        BigramDict("sl", "$REL/sl_bigrams_150k.txt", 2364942, "c387e06e7279858c7b09a69dd0d80f924c6e5e6a9d7aa4d1d165a717bc4b34c8"),
        BigramDict("sr", "$REL/sr_bigrams_150k.txt", 3692133, "f8e54da9fe8aea908b94ff5c04c20c26168bbbc19a7b8dcfc8974078bd42f731"),
        BigramDict("sv", "$REL/sv_bigrams_150k.txt", 2396247, "f413d0743258d3685d518a8b997e7388c1f1c68869ba7c99d41ad22011dc7d1b"),
        BigramDict("ta", "$REL/ta_bigrams_150k.txt", 7065645, "f15a572b290a15776b4002eadf78883a0e293d69c9f7f39192945f7834b6ec6f"),
        BigramDict("tr", "$REL/tr_bigrams_150k.txt", 2857189, "bff3a822f99ddc6adfef0e50dc01cc8c24905e34fbbd83ca07de2b2ff59d83ae"),
        BigramDict("uk", "$REL/uk_bigrams_150k.txt", 4165043, "f6dfb40011d67906b45fd629c7d96c9583c7de0927677e586a00b92b0d155784"),
        BigramDict("ur", "$REL/ur_bigrams_150k.txt", 3186074, "cbbc2dab2580f7d2a811154e7979ccbc4c1fa9b4815e4d6e5059ef6749adfd78"),
        BigramDict("vi", "$REL/vi_bigrams_150k.txt", 2252738, "97617c14eec6e5683a4b83c47ad07255f2b3d571964cb2070092faad376ada3b"),
    )

    private val byLang = all.associateBy { it.lang }

    fun forLang(lang: String): BigramDict? = byLang[LatinLanguageProvider.normalizeLang(lang)]
}
