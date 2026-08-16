#!/usr/bin/env python3
"""
Batch-generate all downloadable glide-typing dictionaries (issue #127) into ./dist and print the
GlideDictionaryCatalog.kt entries.

Runs generate.py once per language with the right OPUS frequency code and Hunspell dictionary. The table
below is the intersection of (Dictate keyboard subtypes) × (OPUS OpenSubtitles frequency lists) ×
(a Hunspell dictionary under a licence we may use), restricted to scripts where a word list is meaningful.
English and German ship bundled in the APK, so they are intentionally absent here.

    python3 generate_all.py            # generate everything into ./dist, write ./dist/catalog.txt
    python3 generate_all.py de fr ...  # only the given output codes

Columns: out_code  opus_code  hunspell_dict  display_name

A hunspell_dict starting with "lo:" comes from LibreOffice/dictionaries instead of wooorm/dictionaries,
which covers no Arabic and none of the Indic languages.

Not here, and why: th (Thai writes without word delimiters, so a word list without a segmenter has nothing
to attach to), ja/ko/zh (need their own providers — issue #262), and az/ast/ckb/fo/hoc/ig/kab/ku/rue/udm/IPA
(no reachable frequency list).
"""
import sys, os, subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "dist")

# Languages built OPUS-only (no Hunspell), either because the only Hunspell dictionary has a licence we
# cannot use — he = AGPL-3.0, is = CC-BY-SA-3.0, fi = Voikko/GPL — or because none exists at all (ur).
# The cost is real: nothing filters corpus noise, and misspelled variants stay in the dictionary where
# they can no longer be corrected.
NO_HUNSPELL = {"he", "is", "fi", "ur"}

# Languages where OPUS OpenSubtitles alone is too thin to autocorrect against — a small dictionary is
# worse than none, because every word outside it becomes a correction target. A Leipzig Corpora package
# (news or Wikipedia, CC BY) is merged in by relative frequency to fill the gap. Measured word counts
# from OPUS alone: Tamil 7,145 · Hindi 15,706 · Urdu 11,230.
LEIPZIG = {
    "hi": "hin_news_2022_1M",
    "ta": "tam_wikipedia_2021_1M",
    "ur": "urd_newscrawl_2016_1M",
    # Not thin but broken: the OPUS Georgian list is not Georgian. Its most frequent entries are
    # `ეა ვ ნვ ჟვ ჱა` — single letters, fragments and obsolete characters (ჲ in 41 % of entries,
    # ჱ in 15 %), where real Georgian starts `და რომ არ ეს`. Unlike Icelandic the damage is not an
    # invertible substitution, so the source is replaced rather than repaired; OPUS is switched off
    # for Georgian below.
    "ka": "kat-ge_web_2019_300K",
}

# OPUS lists that arrive mojibaked, as out_code -> "STORED:READ" for --fix-opus-encoding. Icelandic's
# was written as Latin-1 and read as ISO-8859-4, so þ→ū, ð→đ and ó→ķ: 20 % of the entries were
# misspelled, including every one of the most frequent words (`ađ`, `ūađ`, `viđ`). Autocorrect then
# held the corrupt spelling to be the right one.
FIX_OPUS_ENCODING = {"is": "iso8859_4:latin1"}

# Languages built from Leipzig alone, because their OPUS list is unusable rather than merely small.
LEIPZIG_ONLY = {"ka"}

# out_code, opus_code, hunspell_dict, display_name
LANGS = [
    ("ar", "ar", "lo:ar/ar", "Arabic · العربية"),
    ("bn", "bn", "lo:bn_BD/bn_BD", "Bengali · বাংলা"),
    ("bg", "bg", "bg", "Bulgarian · Български"),
    ("ca", "ca", "ca", "Catalan · Català"),
    ("cs", "cs", "cs", "Czech · Čeština"),
    ("da", "da", "da", "Danish · Dansk"),
    ("el", "el", "el", "Greek · Ελληνικά"),
    ("eo", "eo", "eo", "Esperanto"),
    ("es", "es", "es", "Spanish · Español"),
    ("et", "et", "et", "Estonian · Eesti"),
    ("fa", "fa", "fa", "Persian · فارسی"),
    ("fi", "fi", "fi", "Finnish · Suomi"),
    ("fr", "fr", "fr", "French · Français"),
    ("hi", "hi", "lo:hi_IN/hi_IN", "Hindi · हिन्दी"),
    ("hr", "hr", "hr", "Croatian · Hrvatski"),
    ("hu", "hu", "hu", "Hungarian · Magyar"),
    ("hy", "hy", "hy", "Armenian · Հայերեն"),
    ("id", "id", "lo:id/id_ID", "Indonesian · Bahasa Indonesia"),
    ("is", "is", "is", "Icelandic · Íslenska"),
    ("it", "it", "it", "Italian · Italiano"),
    ("he", "he", "he", "Hebrew · עברית"),
    ("ka", "ka", "ka", "Georgian · ქართული"),
    ("lt", "lt", "lt", "Lithuanian · Lietuvių"),
    ("lv", "lv", "lv", "Latvian · Latviešu"),
    ("nb", "no", "nb", "Norwegian Bokmål"),
    ("nl", "nl", "nl", "Dutch · Nederlands"),
    ("nn", "no", "nn", "Norwegian Nynorsk"),
    ("pl", "pl", "pl", "Polish · Polski"),
    ("pt", "pt", "pt", "Portuguese · Português"),
    ("ro", "ro", "ro", "Romanian · Română"),
    ("ru", "ru", "ru", "Russian · Русский"),
    ("sk", "sk", "sk", "Slovak · Slovenčina"),
    ("sl", "sl", "sl", "Slovenian · Slovenščina"),
    ("sr", "sr", "sr", "Serbian · Српски"),
    ("sv", "sv", "sv", "Swedish · Svenska"),
    ("ta", "ta", "lo:ta_IN/ta_IN", "Tamil · தமிழ்"),
    ("tr", "tr", "tr", "Turkish · Türkçe"),
    ("uk", "uk", "uk", "Ukrainian · Українська"),
    ("ur", "ur", "ur", "Urdu · اردو"),
    ("vi", "vi", "vi", "Vietnamese · Tiếng Việt"),
]

def main():
    only = set(sys.argv[1:])
    os.makedirs(OUT, exist_ok=True)
    catalog, failed = [], []
    for out_code, opus, dic, name in LANGS:
        if only and out_code not in only:
            continue
        sys.stderr.write(f"==> {out_code} (opus={opus}, dict={dic})\n")
        cmd = [sys.executable, os.path.join(HERE, "generate.py"), out_code,
               "--opus", opus, "--name", name, "--top", "100000", "--out", OUT]
        cmd += ["--lo-dict", dic[3:]] if dic.startswith("lo:") else ["--dict", dic]
        if out_code in LEIPZIG:
            cmd += ["--leipzig", LEIPZIG[out_code]]
        if out_code in LEIPZIG_ONLY:
            cmd.append("--no-opus")
        if out_code in FIX_OPUS_ENCODING:
            cmd += ["--fix-opus-encoding", FIX_OPUS_ENCODING[out_code]]
        if out_code in NO_HUNSPELL:
            cmd.append("--no-hunspell")
        proc = subprocess.run(cmd, capture_output=True, text=True)
        sys.stderr.write(proc.stderr)
        line = proc.stdout.strip()
        if proc.returncode != 0 or not line.startswith("GlideDict("):
            failed.append(out_code)
            sys.stderr.write(f"    FAILED {out_code}\n")
            continue
        catalog.append(line)
    with open(os.path.join(OUT, "catalog.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(catalog) + "\n")
    sys.stderr.write(f"\nDONE: {len(catalog)} dictionaries in {OUT}\n")
    if failed:
        sys.stderr.write(f"FAILED: {' '.join(failed)}\n")
    print("\n".join(catalog))

if __name__ == "__main__":
    main()
