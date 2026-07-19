#!/usr/bin/env python3
"""
Batch-generate all downloadable glide-typing dictionaries (issue #127) into ./dist and print the
GlideDictionaryCatalog.kt entries.

Runs generate.py once per language with the right OPUS frequency code and wooorm Hunspell dictionary. The
table below is the intersection of (Dictate keyboard subtypes) × (OPUS OpenSubtitles frequency lists) ×
(wooorm Hunspell dictionaries), restricted to scripts where glide typing is meaningful. English and German
ship bundled in the APK, so they are intentionally absent here.

    python3 generate_all.py            # generate everything into ./dist, write ./dist/catalog.txt
    python3 generate_all.py de fr ...  # only the given output codes

Columns: out_code  opus_code  hunspell_dict  display_name
"""
import sys, os, subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "dist")

# Languages built OPUS-only (no Hunspell) because their wooorm Hunspell licence is incompatible with
# redistribution in a commercial app: he = AGPL-3.0, is = CC-BY-SA-3.0.
NO_HUNSPELL = {"he", "is"}

# out_code, opus_code, hunspell_dict, display_name
LANGS = [
    ("bg", "bg", "bg", "Bulgarian · Български"),
    ("ca", "ca", "ca", "Catalan · Català"),
    ("cs", "cs", "cs", "Czech · Čeština"),
    ("da", "da", "da", "Danish · Dansk"),
    ("el", "el", "el", "Greek · Ελληνικά"),
    ("eo", "eo", "eo", "Esperanto"),
    ("es", "es", "es", "Spanish · Español"),
    ("et", "et", "et", "Estonian · Eesti"),
    ("fa", "fa", "fa", "Persian · فارسی"),
    ("fr", "fr", "fr", "French · Français"),
    ("hr", "hr", "hr", "Croatian · Hrvatski"),
    ("hu", "hu", "hu", "Hungarian · Magyar"),
    ("hy", "hy", "hy", "Armenian · Հայերեն"),
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
    ("tr", "tr", "tr", "Turkish · Türkçe"),
    ("uk", "uk", "uk", "Ukrainian · Українська"),
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
               "--opus", opus, "--dict", dic, "--name", name, "--top", "100000", "--out", OUT]
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
