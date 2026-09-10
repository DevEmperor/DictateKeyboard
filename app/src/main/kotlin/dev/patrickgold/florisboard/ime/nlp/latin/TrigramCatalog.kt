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
 * Data is generated from the Leipzig Corpora Collection (wortschatz-leipzig.de, CC BY) by
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
     * Languages whose trigram file ships in the APK ([ime/dict/<lang>_trigrams.txt]) — never
     * downloaded, never deleted.
     *
     * English, so the keyboard is whole the moment it is installed: its word list and both context
     * tables are all in the APK, and someone who types English on a fresh phone with no connection
     * gets the finished engine rather than a version of it that improves later. It costs 1.8 MB of
     * download size once, against 1.8 MB fetched by nearly every user anyway.
     */
    val BUNDLED = setOf("en")

    /**
     * Every downloadable language; English is absent because it is bundled, see [BUNDLED]. A language
     * missing from both simply has no second word of context: [NgramIndex.EMPTY] answers nothing, the
     * prediction falls back to the bigram table, and the keyboard behaves exactly as it did before this
     * existed — which is what made it safe to add these one pipeline run at a time.
     *
     * Tamil's table is the one short one (66,149 entries rather than 100,000): its corpus is Wikipedia
     * and the language is agglutinative, so fewer triples reach the minimum count. The floor is doing
     * its job there — the alternative is 34,000 rows of phrases seen twice.
     */
    val all: List<TrigramDict> = listOf(
        TrigramDict("ar", "$REL/ar_trigrams_100k.txt", 3340877, "ff5fd11e7f4584fc4729d90be7e357e87b993925660a9ac910848835808d9ede"),
        TrigramDict("bg", "$REL/bg_trigrams_100k.txt", 3311676, "16bda1e90e5e67cc23b837d64052e14c2d7ce87e2bda2370f6dde5d7e36331b6"),
        TrigramDict("bn", "$REL/bn_trigrams_100k.txt", 5121895, "ee9379260dc883fab1e0ea619e118d067ab69f2518a9b699b33b1e2c4eb0aa84"),
        TrigramDict("ca", "$REL/ca_trigrams_100k.txt", 1860166, "aad58ab68d1ccbf6e278e24083be4640749538146196c2ba58382d4ff34393af"),
        TrigramDict("cs", "$REL/cs_trigrams_100k.txt", 2027202, "67ad3d96b4a7a2c0311db34e26bbb2541d2b182b2df38ce3215c439d848cb5aa"),
        TrigramDict("da", "$REL/da_trigrams_100k.txt", 1863315, "bcd6ad0a35613087ed6dd387f91c21bc2768f6b6ac92dbb57dda529237c5706d"),
        TrigramDict("de", "$REL/de_trigrams_100k.txt", 1986190, "e7e5b18a10e2eeecbcb43b4002d3583fc8960705abab135b6adbb5bd59ba2e89"),
        TrigramDict("el", "$REL/el_trigrams_100k.txt", 3420631, "d2a47de1dc7b87d0b4d674c50e494b24cfa0803aeb809be7798405c0a8e1b216"),
        TrigramDict("eo", "$REL/eo_trigrams_100k.txt", 1931420, "de90216ad47d9ddf457fdd467f0a1349f0b2bf9a145ec2767003ca746b5cbf96"),
        TrigramDict("es", "$REL/es_trigrams_100k.txt", 1920598, "d1648461324fb76cf3cfe18f39a5358fc0fc6a19096cfebd2bcb861c9d9e9f14"),
        TrigramDict("et", "$REL/et_trigrams_100k.txt", 2019108, "2184490a517250c3a3194698c39e509b1df26410bb8adb43a161fd20a2fe45a4"),
        TrigramDict("fa", "$REL/fa_trigrams_100k.txt", 2797783, "7515b69122c5e068fa6ad807e1ccd0d38bcc9f827a38dc2fc3afce3f7b536806"),
        TrigramDict("fi", "$REL/fi_trigrams_100k.txt", 2447615, "c349dc5345cbdb450ba9595a21d34e3930edf29f87d324bfc8b370cdc0d7e452"),
        TrigramDict("fr", "$REL/fr_trigrams_100k.txt", 1987323, "b447ab6da3cef50cde052cae243b703970265150af2ea41669ad3f699a140925"),
        TrigramDict("he", "$REL/he_trigrams_100k.txt", 2946214, "43d79126cd55e989a261cd68587274f33f8ac8a9fde399158671da777f6888b3"),
        TrigramDict("hi", "$REL/hi_trigrams_100k.txt", 3900216, "c42d1a4092e1d14e180c64e6a36f59fa0984620b97718489a4ea8f511b4e36dd"),
        TrigramDict("hr", "$REL/hr_trigrams_100k.txt", 1935415, "a6c64a417b7cf50a4dacaaa9cbb6cfe80c53a3d38d5bef7a6e142e485b6917dc"),
        TrigramDict("hu", "$REL/hu_trigrams_100k.txt", 2182784, "aeb51eef2b6713db91ed41b38b98e0ea705be51b04c93a3dd24aa161d6d4ebe3"),
        TrigramDict("hy", "$REL/hy_trigrams_100k.txt", 3550041, "81679d699b2f360821b34755cff9a46af2a0c6613bfcfc2a773b618e98b45563"),
        TrigramDict("id", "$REL/id_trigrams_100k.txt", 2284779, "b21788a9f8bc71109f81c0dfce3f9f15719592135b8df6e4352c73f911d34fa0"),
        TrigramDict("is", "$REL/is_trigrams_100k.txt", 2055578, "522506c6d3bf28e44dd12ebee2dbf3834b637a721b130e5a424d73acf4a3b63f"),
        TrigramDict("it", "$REL/it_trigrams_100k.txt", 1953631, "2b17166095e56dfc20111a7daf931db895b285ef1faeb6a356af5d4974f7b367"),
        TrigramDict("ka", "$REL/ka_trigrams_100k.txt", 6637244, "c0cfb7d2c160145bb6c399976a9e10697410c7fa88b73694e515e4e3cf13489b"),
        TrigramDict("lt", "$REL/lt_trigrams_100k.txt", 2363955, "63abfb7ab32b7b5eefd5d435165ce76327d6439c2c2a1aa3ee01d05d38b32bd9"),
        TrigramDict("lv", "$REL/lv_trigrams_100k.txt", 2233755, "95498b064810e1902ed3dc884d17f86ea0ae66d90e4799444a9bbf4c355721ce"),
        TrigramDict("nb", "$REL/nb_trigrams_100k.txt", 1846651, "321c23e9691abba67d8f692f7412e70a50f5a4a4aaefc92b9ed4d9789de89af2"),
        TrigramDict("nl", "$REL/nl_trigrams_100k.txt", 1802666, "fdd1db313e401d383cc3c3393e6ed9d2cc1cc4624a0086f993f9ddbdc7b0a11a"),
        TrigramDict("nn", "$REL/nn_trigrams_100k.txt", 1798431, "5adf91adba1ea2cd0bd134fd754cea0fbd0280c8251fb16bafacf4f9fa52d04c"),
        TrigramDict("pl", "$REL/pl_trigrams_100k.txt", 2185906, "203a542a2e61ee2ac2115d6455a5cbeae04ef865d12deed330c82b65d8fc4070"),
        TrigramDict("pt", "$REL/pt_trigrams_100k.txt", 1985614, "94e90f15121dafc0f5b3dc0dee1709920faae55877279a95ba048b9c24c87f46"),
        TrigramDict("ro", "$REL/ro_trigrams_100k.txt", 1999092, "76cfaed02f0c82b56f0160733d021845e31280a9b47b82a18f30ab1f137421a0"),
        TrigramDict("ru", "$REL/ru_trigrams_100k.txt", 3786719, "8b0506f2988a08a6d5744e4f5a193462236e58f6e1f1629724cd36ef24bd955d"),
        TrigramDict("sk", "$REL/sk_trigrams_100k.txt", 2072186, "15002a633f96d9c0e611fdb17bf7116bd93a2dc13f3dccab29f7a5cabc0f1ebc"),
        TrigramDict("sl", "$REL/sl_trigrams_100k.txt", 1922207, "ced62c5bd8e72f041e4763209cd03d639713307756fb0b9cc94985f2aa209517"),
        TrigramDict("sr", "$REL/sr_trigrams_100k.txt", 3064410, "462c2403862bc3bdac05c76460d12ef981801ba26491928a381e4d1c1181219a"),
        TrigramDict("sv", "$REL/sv_trigrams_100k.txt", 1918729, "dbaeadc182716d9c6650263f9dcc14f541294717145cfb5ce104ba0e284a0dda"),
        TrigramDict("ta", "$REL/ta_trigrams_100k.txt", 4491028, "40b40419d5c49e4695fb14fa90a3a5b878b91b51ce0742870c1370e4fcf52bc1"),
        TrigramDict("tr", "$REL/tr_trigrams_100k.txt", 2562860, "82c2f27f4805121e9e15924de7e21ae8536a46c41d453ff97665b2d7dce1b3df"),
        TrigramDict("uk", "$REL/uk_trigrams_100k.txt", 3697129, "51d4a6a2f4aa8143f39ba062f409b166bb8307559d9f2146ff9ec032cc92e58a"),
        TrigramDict("ur", "$REL/ur_trigrams_100k.txt", 2847061, "509e3a6c7bfaff3c16d53d2416c14fb5e8c19fc3af60c42978f6667bad36364e"),
        TrigramDict("vi", "$REL/vi_trigrams_100k.txt", 2098547, "4d96a9880f2c9f409c0527aa1e903e84d28d5c7e76cb25af9dcfe1d78bf72a90"),
    )

    private val byLang = all.associateBy { it.lang }

    fun forLang(lang: String): TrigramDict? = byLang[LatinLanguageProvider.normalizeLang(lang)]
}
