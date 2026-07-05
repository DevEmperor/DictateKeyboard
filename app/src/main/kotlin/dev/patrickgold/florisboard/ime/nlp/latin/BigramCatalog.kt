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
 * `tools/glide-dict/generate_bigrams.py`; paste the script-printed catalog line here after uploading the
 * `<lang>_bigrams.txt` files as assets of the release named below.
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
        BigramDict("bg", "$REL/bg_bigrams.txt", 1500236, "9393406839a3ef2f61dddedcde1ba670ddb010ad021471dce54193f18c7c3236"),
        BigramDict("ca", "$REL/ca_bigrams.txt", 938858, "82b4af96057cee467a164ffa73680fc9cb25ee0d9f4cea1a8eb06b1ac56ff164"),
        BigramDict("cs", "$REL/cs_bigrams.txt", 960186, "ed2a6551735c0dadb60ad78934784a5e16188af0c31835ea484b5d4eb7ac3899"),
        BigramDict("da", "$REL/da_bigrams.txt", 922016, "eb07fb6ac80c53e09485b6dbae210dc7917fee61bbedd6de096d553a78e2672a"),
        BigramDict("de", "$REL/de_bigrams.txt", 989807, "52d906c15bab021d386e6bf2537a2090c321959f16f814de3b64357dffb33c67"),
        BigramDict("el", "$REL/el_bigrams.txt", 1619772, "ba6e8834112083c9257ab1dc09a27270f7b7868f0e9df6192faf20480f68c56d"),
        BigramDict("eo", "$REL/eo_bigrams.txt", 955857, "1096a6eccc275223a14e4dd5fa288fa13a5b986fa5040815ad919eae2f46ddb9"),
        BigramDict("es", "$REL/es_bigrams.txt", 949805, "b7cd13759f0dc7249f7f928ce8aff15077b4b112726ff3a0c5bfa182f045d7f4"),
        BigramDict("et", "$REL/et_bigrams.txt", 986103, "05682addfe5cae9b8bafa606d63828a422c55a76c0cd9d1909aad0552d0fc08b"),
        BigramDict("fa", "$REL/fa_bigrams.txt", 1276906, "1fbbd502d1ab923ea8e605086f586460a30c7eefd5e87309c5cd283e87acf20d"),
        BigramDict("fr", "$REL/fr_bigrams.txt", 969735, "a7c6f7cac5b8ec00db2435c9431fb25dd936bcbb10c1d0bb06398326128ea1e9"),
        BigramDict("he", "$REL/he_bigrams.txt", 1304054, "0f5189b27e88533527cd32d7497252b003bf43fbe6af9ccf34edc4e206cba7a9"),
        BigramDict("hr", "$REL/hr_bigrams.txt", 922250, "9967f976ad960d3ab0cd4be33e6e1a664718b8415e5d8ce736c0daa138f4cec4"),
        BigramDict("hu", "$REL/hu_bigrams.txt", 1039602, "70298f46cd41f39140a73042576b1584cc835724ab38fadeda70e4ed142363d5"),
        BigramDict("hy", "$REL/hy_bigrams.txt", 1741206, "0a1643ae806a3ae4b521845c06cc6a49e8aecbdd092fbffd8c5c43f4bb8ef0dc"),
        BigramDict("is", "$REL/is_bigrams.txt", 986807, "bd9fb27249702ff84ce4dc5ed9ba3b5c8bc02a1ee7cbdb19bc304f470ed96152"),
        BigramDict("it", "$REL/it_bigrams.txt", 940777, "4f626dea5e4c4840ab7ee54c5d9e065ca8b49af2e3ce15da5f97402b5610d7e0"),
        BigramDict("ka", "$REL/ka_bigrams.txt", 2873715, "936dfdd90c481068eb59979bdde9edce14447d71a0b9ce60d4b0f0f6d01f1085"),
        BigramDict("lt", "$REL/lt_bigrams.txt", 1067694, "ae46fb8f69ff1d011c02dca439a153b6d9a29fc5f00acbd002bf285e3de22558"),
        BigramDict("lv", "$REL/lv_bigrams.txt", 1067669, "d4bb1381f98290593c37606082c7b0a2f2cdb4e0d1924ec3990395353ec3c613"),
        BigramDict("nb", "$REL/nb_bigrams.txt", 924745, "810e184a79fb9b54549cdf57dbe6ba4d356e26fce85d35a70175882d22adbed2"),
        BigramDict("nn", "$REL/nn_bigrams.txt", 884866, "33460825c138eb6ae6279f8f8a5cf4e1cdd1a900fb0451fb62a421ffc5c8e17c"),
        BigramDict("pl", "$REL/pl_bigrams.txt", 1008318, "b31f7bf58f3e23ce269212d54fece5dd7ee7a30de63fd97b028c0e13bd8160c2"),
        BigramDict("pt", "$REL/pt_bigrams.txt", 947388, "4ffc62080866d7651d403201a40b38baec5703736233c4048eeeabb61322838d"),
        BigramDict("ro", "$REL/ro_bigrams.txt", 926789, "33d7efda514a43db842301b9c54125744716ca2e0e61a3daa7955f379fb7811a"),
        BigramDict("ru", "$REL/ru_bigrams.txt", 1699036, "55c587d1b676bc8237839718108e14a1a8ca4cccb1a3534d84434de38586bd74"),
        BigramDict("sk", "$REL/sk_bigrams.txt", 976317, "6d9b582c84741931e61091899941a9c4b078aca688aed2d96be25b387f4f908f"),
        BigramDict("sl", "$REL/sl_bigrams.txt", 925016, "1c3a9f8971220ace0a4e778e9ccea46072f19bdc5e926bd6673b53dbea27cf31"),
        BigramDict("sr", "$REL/sr_bigrams.txt", 1473306, "aad44c86dab972cd2e955ccb0e894dde2bd1e75f140c2b6d76d7dbaaacdef2a1"),
        BigramDict("sv", "$REL/sv_bigrams.txt", 936521, "971683aa408ba6bce39c9fc25f30adc28e1cf3c91a119a3138c3dcbee7837f86"),
        BigramDict("tr", "$REL/tr_bigrams.txt", 1083480, "f18782859638baa7ad14c2801a995b4e4b87b1df61b24b081bc85ea1de50f79e"),
        BigramDict("uk", "$REL/uk_bigrams.txt", 1650695, "3d76653f06affb1c6c6e04654d2cedc6da3b7de324238432643d661820549cf5"),
        BigramDict("vi", "$REL/vi_bigrams.txt", 872623, "60e1a7c46e9be547deaf3aac799c13304974b0587410373d0204df3e5bfff850"),
    )

    private val byLang = all.associateBy { it.lang }

    fun forLang(lang: String): BigramDict? = byLang[LatinLanguageProvider.normalizeLang(lang)]
}
