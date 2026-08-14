#!/usr/bin/env python3
"""
Generate the per-language emoji annotation files used by the emoji search (issue #274).

Background: the keyboard ships one emoji *inventory* (`root.txt`, categories + skin-tone variants, no
text) and, next to it, one *annotation* file per language holding the searchable name and keywords for
every base emoji. Before #274 there was no such split — six languages carried a full copy of the
inventory *with* names (de/en/es/fr/it/pt, 2.5 MB) and the other 49 keyboard languages fell back to
`root.txt`, whose lines are literally `😀;;`. Searching in those languages could therefore never match
anything, which is exactly what was reported.

Source: Unicode CLDR, via the cldr-json mirror. Two files per locale are needed and both are required
for full coverage:
  * cldr-annotations-full/annotations/<loc>            — the hand-curated annotations
  * cldr-annotations-derived-full/annotationsDerived/<loc> — sequences derived by rule (families,
    flags, skin-tone/gender combinations)

⚠️ Look-ups must strip U+FE0F (VARIATION SELECTOR-16). CLDR keys the emoji-presentation characters
without it while `root.txt` stores the fully-qualified form; without stripping, 364 of the 1913 base
emojis (❤️ ☺️ ☠️ ❤️‍🔥 …) silently get no annotation and stay unsearchable. With it the coverage is
complete for every language checked.

Output format, one line per base emoji, in `root.txt` order:

    😘;csókot dobó arc;arc|csók|flörtöl|puszi|szeret|szeretlek|szív|szmájli

Usage:
    python3 generate.py                 # all languages, into the app's asset directory
    python3 generate.py hu de           # only these
    python3 generate.py --out /tmp/ann  # elsewhere

Licensing: CLDR is published under the Unicode License v3 (permissive, redistribution allowed with the
notice); see app/src/main/assets/license/data_attributions.txt.
"""
import argparse
import json
import os
import sys
import unicodedata
import urllib.error
import urllib.request

CLDR_BRANCH = "main"
BASE_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/{branch}/cldr-json/"
    "cldr-annotations-full/annotations/{loc}/annotations.json"
)
DERIVED_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/{branch}/cldr-json/"
    "cldr-annotations-derived-full/annotationsDerived/{loc}/annotations.json"
)
VERSION_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/{branch}/cldr-json/"
    "cldr-annotations-full/package.json"
)

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
EMOJI_DIR = os.path.join(REPO, "app", "src", "main", "assets", "ime", "media", "emoji")
DEFAULT_OUT = os.path.join(EMOJI_DIR, "annotations")

VS16 = "️"

# Every keyboard subtype language (org.florisboard.localization/extension.json) for which CLDR has
# annotations, plus nl — a UI language of the app that users can pick as a custom subtype. Left out
# because CLDR has nothing: eo, hoc, rue, udm, IPA. They fall back to the English index at runtime.
#
# The key is the asset file name (what the runtime looks for), the value the CLDR locale. They differ
# where CLDR uses a macrolanguage: Norwegian Bokmål is `no` there. Android's own legacy codes
# (`iw`, `in`) are aliased on the Kotlin side, not here — the assets stay on the modern names.
LANGS = {
    "ar": "ar", "ast": "ast", "az": "az", "bg": "bg", "bn": "bn", "ca": "ca", "ckb": "ckb",
    "cs": "cs", "da": "da", "de": "de", "el": "el", "en": "en", "es": "es", "fa": "fa",
    "fi": "fi", "fo": "fo", "fr": "fr", "he": "he", "hi": "hi", "hr": "hr", "hu": "hu",
    "hy": "hy", "id": "id", "ig": "ig", "is": "is", "it": "it", "ja": "ja", "ka": "ka",
    "kab": "kab", "ko": "ko", "ku": "ku", "lt": "lt", "lv": "lv", "nb": "no", "nl": "nl",
    "nn": "nn", "pl": "pl", "pt": "pt", "ro": "ro", "ru": "ru", "sk": "sk", "sl": "sl",
    "sr": "sr", "sv": "sv", "ta": "ta", "th": "th", "tr": "tr", "uk": "uk", "ur": "ur",
    "vi": "vi", "zh": "zh",
}


def fetch_json(url):
    """Download and parse a JSON document, returning None on 404 (a locale may lack derived data)."""
    try:
        with urllib.request.urlopen(url, timeout=60) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def read_root_emojis(path):
    """The base emojis of the inventory, in file order — skin-tone variants (tab-indented) excluded."""
    emojis = []
    with open(path, encoding="utf-8") as file:
        for line in file:
            if line.startswith(("#", "[", "\t")) or not line.strip():
                continue
            emojis.append(line.split(";")[0].strip())
    return emojis


def load_annotations(cldr_locale):
    """Merge the derived annotations with the curated ones, keyed without U+FE0F.

    Either half may be absent or carry nothing but an identity block — ckb, for one, has an empty
    curated file and lives entirely off the derived one — so both are read defensively.
    """
    derived = fetch_json(DERIVED_URL.format(branch=CLDR_BRANCH, loc=cldr_locale)) or {}
    base = fetch_json(BASE_URL.format(branch=CLDR_BRANCH, loc=cldr_locale))
    if base is None:
        raise SystemExit(f"no CLDR annotations for '{cldr_locale}'")
    merged = {}
    merged.update(derived.get("annotationsDerived", {}).get("annotations", {}))
    # The curated file wins where both describe the same emoji.
    merged.update(base.get("annotations", {}).get("annotations", {}))
    return {key.replace(VS16, ""): value for key, value in merged.items()}


def clean(text):
    """Collapse whitespace and drop the field separators, so a line can never be mis-parsed."""
    return " ".join(text.replace(";", " ").replace("|", " ").split())


def build_lines(root_emojis, annotations):
    lines, missing = [], []
    for emoji in root_emojis:
        entry = annotations.get(emoji.replace(VS16, ""))
        if entry is None:
            missing.append(emoji)
            continue
        name = clean((entry.get("tts") or [""])[0])
        keywords, seen = [], {name.casefold()}
        for keyword in entry.get("default") or []:
            keyword = clean(keyword)
            folded = keyword.casefold()
            if keyword and folded not in seen:
                seen.add(folded)
                keywords.append(keyword)
        if not name and not keywords:
            missing.append(emoji)
            continue
        lines.append(f"{emoji};{name};{'|'.join(keywords)}")
    return lines, missing


def cldr_version():
    package = fetch_json(VERSION_URL.format(branch=CLDR_BRANCH))
    return (package or {}).get("version", "unknown")


def main():
    parser = argparse.ArgumentParser(description="Generate emoji annotation files from CLDR.")
    parser.add_argument("langs", nargs="*", help="asset language codes (default: all)")
    parser.add_argument("--out", default=DEFAULT_OUT, help="output directory")
    parser.add_argument("--root", default=os.path.join(EMOJI_DIR, "root.txt"), help="emoji inventory")
    args = parser.parse_args()

    selected = args.langs or sorted(LANGS)
    unknown = [lang for lang in selected if lang not in LANGS]
    if unknown:
        raise SystemExit(f"unknown language(s): {', '.join(unknown)}")

    root_emojis = read_root_emojis(args.root)
    print(f"CLDR v{cldr_version()} · {len(root_emojis)} base emojis in {os.path.basename(args.root)}")
    os.makedirs(args.out, exist_ok=True)

    total, partial, empty = 0, [], []
    for lang in selected:
        annotations = load_annotations(LANGS[lang])
        lines, missing = build_lines(root_emojis, annotations)
        if not lines:
            # Nothing to ship: the runtime falls back to the English index for this language.
            empty.append(lang)
            print(f"  {lang:<4} {'—':>5}          skipped, CLDR has no annotations")
            continue
        path = os.path.join(args.out, f"{lang}.txt")
        with open(path, "w", encoding="utf-8") as file:
            file.write("\n".join(lines) + "\n")
        size = os.path.getsize(path)
        total += size
        coverage = len(lines) / len(root_emojis)
        note = ""
        if missing:
            partial.append((lang, coverage))
            note = f"  ⚠ {len(missing)} without annotation ({coverage:.0%} covered)"
        print(f"  {lang:<4} {len(lines):>5} entries  {size // 1024:>4} KB{note}")

    print(f"total {total // 1024} KB raw in {args.out}")
    if partial:
        # Not an error: an emoji without a local annotation is still found through the English index.
        summary = ", ".join(f"{lang} {coverage:.0%}" for lang, coverage in sorted(partial, key=lambda it: it[1]))
        print(f"partial coverage — {summary}")
    if empty:
        print(f"no CLDR data — {', '.join(empty)}")


if __name__ == "__main__":
    sys.exit(main())
