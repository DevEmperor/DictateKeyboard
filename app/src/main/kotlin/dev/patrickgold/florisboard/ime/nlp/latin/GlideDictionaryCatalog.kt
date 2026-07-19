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
 * One downloadable glide-typing dictionary: a `<lang>.json` word→frequency map used by the glide
 * classifier (issue #127, phase 2). Stored under the language code so the runtime stays layout-agnostic;
 * [sizeBytes] and [sha256] are verified after download (see [GlideDictionaryManager]).
 */
data class GlideDict(
    /** Language code matching a subtype's primary locale language, e.g. "de". */
    val lang: String,
    /** Human-readable name shown in the picker, e.g. "German · Deutsch". */
    val displayName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * Catalog of downloadable glide-typing dictionaries, mirrored on the project's own GitHub release ([REL])
 * — same hosting pattern as the on-device Whisper models ([dev.patrickgold.florisboard.dictate.provider.LocalModelCatalog]).
 * English ships bundled in the APK ([ime/dict/en.json]) and is always available; every other language is
 * downloaded on demand when its subtype is selected.
 *
 * The dictionaries are generated with `tools/glide-dict/generate.py` from the Leipzig Corpora Collection
 * (Universität Leipzig), which preserves correct casing and diacritics. To (re-)host, upload the generated
 * `<lang>.json` files as assets of the release named below and paste each script-printed catalog line here.
 */
object GlideDictionaryCatalog {
    /** Project-hosted release holding the dictionary files. Single re-point for hosting. */
    const val REL = "https://github.com/DevEmperor/DictateKeyboard/releases/download/glide-dicts-v1"

    /**
     * The downloadable dictionaries. Populated from `generate.py` output once the files are hosted.
     * (English is intentionally absent — it is bundled in the APK.)
     */
    val all: List<GlideDict> = listOf(
        GlideDict("bg", "Bulgarian · Български", "$REL/bg.json", 1794600, "fa3b438e428427e2ff6597d3db50593e3e045415099b520d203ef5e934e7f623"),
        GlideDict("ca", "Catalan · Català", "$REL/ca.json", 908503, "57069d83f312c608db9c3b19aec4389acd5ea7b5583b63f57f8c51de9c5263fb"),
        GlideDict("cs", "Czech · Čeština", "$REL/cs.json", 1293084, "b7b0d4aea071aebe22ce9c4284ba205816ee0bac7d006ce42f8a84dd21c28112"),
        GlideDict("da", "Danish · Dansk", "$REL/da.json", 1207132, "fd916d4861b70a2f2317d0c788f07d452100c64e1cc64defc59d71c8ea511f2a"),
        GlideDict("el", "Greek · Ελληνικά", "$REL/el.json", 1633614, "409049cc9392c492438c3de91ee644fc30475174d091f30f674c17d2ee73bbb6"),
        GlideDict("eo", "Esperanto", "$REL/eo.json", 425775, "b0f033386ca0a75cb9548d8a0a224d48f5269818dfcb523708e34a608e22b18e"),
        GlideDict("es", "Spanish · Español", "$REL/es.json", 1035140, "1cf52f26081615cca70a498a3ea80ca8010cac0d51f30a1d8a35abcdf92ce126"),
        GlideDict("et", "Estonian · Eesti", "$REL/et.json", 1238171, "1a845ba70014ffcad5dbe3e6a90fe35e3b35cdf566ca82776d450887cdf27039"),
        GlideDict("fa", "Persian · فارسی", "$REL/fa.json", 480596, "ff83c40921458dc42b026a75e37cb1cd97070bd6841f1f8e1d5d111d38ffe923"),
        GlideDict("fr", "French · Français", "$REL/fr.json", 1082093, "ee78ad4cad149c62895fa5f285b638c16bc7ceb908a5d4e7f22ec825a2473ec3"),
        GlideDict("hr", "Croatian · Hrvatski", "$REL/hr.json", 1128369, "1f01c211d27380a96564816a41b920980c8447dc30934e3b606422cc381347dd"),
        GlideDict("hu", "Hungarian · Magyar", "$REL/hu.json", 1419533, "d62b994d0f850214cd5d9ef16ff99aa9ac17150fe6bf5ec49b81821b3d3326ad"),
        GlideDict("hy", "Armenian · Հայերեն", "$REL/hy.json", 64323, "a8500f822bf91af56d93661c40cb45c9bbd38913d5d4152d0feeab3a9c8b0201"),
        GlideDict("is", "Icelandic · Íslenska", "$REL/is.json", 912647, "ccc6201d291df83ea2dbf87b9137e4e88ccd8363b99e402dd2e47cc01385218e"),
        GlideDict("it", "Italian · Italiano", "$REL/it.json", 1112321, "69e2c34c64e9ce3d695768016f959f997bebed482950ff4e11ed7d6eba70ea4a"),
        GlideDict("he", "Hebrew · עברית", "$REL/he.json", 1064193, "b4c9f080f4433a9ecb6e7d9bd5152c2a3020d20ba0ea25cead9ca5462f9bbe74"),
        GlideDict("ka", "Georgian · ქართული", "$REL/ka.json", 2723522, "79d5ea2487450a3c607da60c68591f8739845e1190910c531501797aa6def396"),
        GlideDict("lt", "Lithuanian · Lietuvių", "$REL/lt.json", 1254284, "c8c301a321b84e985ad27865248b78b56d76da433a225202e0a8f62d8af2f4d3"),
        GlideDict("lv", "Latvian · Latviešu", "$REL/lv.json", 1209056, "cdde6ba876ee378d67d0efe3edb45663e340d1ec855a8ebb2d6eacfafc6b18e0"),
        GlideDict("nb", "Norwegian Bokmål", "$REL/nb.json", 1078332, "75d658e8322966af0274c1926e1592a95a0b180ae728eed6ae1cb98174208d41"),
        GlideDict("nl", "Dutch · Nederlands", "$REL/nl.json", 1135562, "5b84ea6aeae907c1f11b255064c67f42c4f5cfe7b1e656b8b1e79a67423931ca"),
        GlideDict("nn", "Norwegian Nynorsk", "$REL/nn.json", 625614, "6c598984d37f6ad72508a39d5beec272e62e5782f18f00b1a8ce7009d936baca"),
        GlideDict("pl", "Polish · Polski", "$REL/pl.json", 1401161, "64aa7f4e1ace255c57c3c79a6d282c3511cc4721f7d4b53fc7a9b53916334531"),
        GlideDict("pt", "Portuguese · Português", "$REL/pt.json", 1149100, "42310d6ea7d3f456baa8facda220060892bdbb7c32973a5478e21d05f4e6f991"),
        GlideDict("ro", "Romanian · Română", "$REL/ro.json", 880168, "d37c91834d5944fb6b98a6c2ad5617fe43d3144d165a2ba8d30f34420d9ecef1"),
        GlideDict("ru", "Russian · Русский", "$REL/ru.json", 1912571, "1fde7e3821f5b24a11dda8d57fe0eeabb4c2549812c7b431debe3c3a0563177b"),
        GlideDict("sk", "Slovak · Slovenčina", "$REL/sk.json", 1211954, "b8dc78366cd0040fc4fa3c5ee06cdf650d9b12aff36c2b0317ba1def92a7d1d4"),
        GlideDict("sl", "Slovenian · Slovenščina", "$REL/sl.json", 1180769, "6fb51d930483c98a55610241aa9e7ef6f75e46e28e1498740716226f23e11fd4"),
        GlideDict("sr", "Serbian · Српски", "$REL/sr.json", 161220, "e765337efc1edf8cc726562e7bbaba54208f648179753b762effa3d3d82bdf6e"),
        GlideDict("sv", "Swedish · Svenska", "$REL/sv.json", 1178943, "95dd05108d3035cd7dd501accd4dfa60e20da4ae11adf8f082941fab277f7cee"),
        GlideDict("tr", "Turkish · Türkçe", "$REL/tr.json", 1397305, "7ac6fae177496d60b12b7f5fb82c14b4da0e0e83bfcd62233edf36fd292d1b28"),
        GlideDict("uk", "Ukrainian · Українська", "$REL/uk.json", 1260704, "d4e66ac5e00cc76418de3391c0abea68fe03f2653fc0d6e508fd38d7cd7cbe73"),
        GlideDict("vi", "Vietnamese · Tiếng Việt", "$REL/vi.json", 202701, "3f1669574875da6491db36f6fe291d31827de4de95e0b4658be271b82db57c07"),
    )

    /** Languages whose dictionary ships inside the APK (ime/dict/<lang>.json) and needs no download. */
    val BUNDLED = setOf("en", "de")

    fun forLang(lang: String): GlideDict? =
        all.firstOrNull { it.lang.equals(lang, ignoreCase = true) }

    /** Language codes that have a downloadable dictionary (excludes the bundled ones). */
    val downloadableLangs: Set<String> = all.map { it.lang.lowercase() }.toSet()

    /** Whether glide typing has a dictionary (bundled or downloadable) for [lang]. */
    fun isSupported(lang: String): Boolean = lang in BUNDLED || forLang(lang) != null
}
