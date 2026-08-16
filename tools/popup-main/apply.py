#!/usr/bin/env python3
"""
Apply the long-press defaults measured by measure.py to the popup mappings (issue #279 follow-up).

Moves a character out of a key's `relevant` list into its `main`, which is what makes
`PopupUiController` place it under the finger. Copying instead of moving would show it twice, so the
edit is done surgically on the file text — one line removed, one line inserted — and the result is
re-parsed and checked before it is written. A whole-file JSON round trip is deliberately avoided: it
would reformat every mapping and bury the change in noise.

The table below is the *decision*, taken from measure.py's output. Four entries overrule it, each
noted with why: a measurement is evidence, not an authority on a language.

Usage:
    python3 apply.py            # apply every language in DECISIONS
    python3 apply.py cs pl      # only these
    python3 apply.py --check    # verify without writing
"""
import argparse
import json
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MAPPINGS = os.path.join(
    REPO, "app/src/main/assets/ime/keyboard/org.florisboard.localization/popupMappings"
)

# mapping -> {key: character to put under the finger}
DECISIONS = {
    "cs": {"a": "á", "c": "č", "d": "ď", "e": "ě", "i": "í", "n": "ň", "o": "ó",
           "r": "ř", "s": "š", "t": "ť", "u": "ů", "y": "ý", "z": "ž"},
    "da": {"a": "æ", "e": "é", "o": "ø"},
    # Esperanto's q/w/x/y keys are not Esperanto letters at all; their popups exist to reach the
    # accented ones (the x-convention, where "cx" means ĉ), so the accented letter is the whole point.
    "eo": {"c": "ĉ", "g": "ĝ", "h": "ĥ", "j": "ĵ", "s": "ŝ", "u": "ŭ",
           "q": "ŝ", "w": "ĝ", "x": "ĉ", "y": "ŭ"},
    "et": {"a": "ä", "o": "õ", "u": "ü"},
    "fi": {"a": "ä", "o": "ö"},
    "hr": {"c": "č", "d": "đ", "s": "š", "z": "ž"},
    # Icelandic had to wait for its word list to be rebuilt: measured against the mojibaked one it
    # came out u→ū (not an Icelandic letter at all — it was þ), o→ö and t→þ far too low. See
    # tools/glide-dict/generate.py.
    "is": {"a": "á", "d": "ð", "e": "é", "i": "í", "o": "ó", "t": "þ", "u": "ú", "y": "ý"},
    # ō and ŗ are absent on purpose: both were dropped from Latvian orthography, and the measurement
    # found them at 0.0% without being told.
    "lv": {"a": "ā", "c": "č", "e": "ē", "g": "ģ", "i": "ī", "k": "ķ", "l": "ļ",
           "n": "ņ", "s": "š", "u": "ū", "z": "ž"},
    "nb": {"a": "å", "o": "ø"},
    "nn": {"a": "å", "o": "ø"},
    "pl": {"a": "ą", "c": "ć", "e": "ę", "l": "ł", "n": "ń", "o": "ó", "s": "ś",
           "x": "ź", "z": "ż"},
    # `s` overrules the measurement: ș scored 0.1% only because the corpus writes Romanian with the
    # older cedilla forms (ş/ţ, 0.8%/1.5%) rather than the comma-below ones the popup offers and the
    # 1993 standard requires. Counting both encodings puts it at 0.9%, well inside.
    "ro": {"a": "ă", "i": "î", "s": "ș", "t": "ț"},
    "ru": {"е": "ё", "ь": "ъ"},
    # `o` overrules the measurement, which is a near tie (ô 0.7/1.1 against ó 1.1/0.3): ô is a native
    # Slovak letter (kôň, stôl) while ó turns up almost only in loanwords.
    "sk": {"a": "á", "c": "č", "d": "ď", "e": "é", "i": "í", "l": "ľ", "n": "ň",
           "o": "ô", "s": "š", "t": "ť", "u": "ú", "y": "ý", "z": "ž"},
    "sl-SI": {"c": "č", "s": "š", "z": "ž"},
    "sv": {"a": "ä", "e": "é", "o": "ö"},
    "uk": {"і": "ї"},
    "uk-cyr-ext": {"і": "ї"},
    # Vietnamese `a` is left out on purpose: â and ă come out 1.6% against 1.3% on a word list of only
    # 13k, which is a coin toss, and Vietnamese is typed through tone marks these popups do not carry.
    # The other four are not close.
    "vi-VN": {"d": "đ", "e": "ê", "o": "ô", "u": "ư"},
}


def apply_to(text, key, char):
    """Move [char] from [key]'s relevant list into its main, editing the file text in place."""
    # The key's block: from `"<key>": {` to the closing `},` at the same indent.
    start = re.search(r'^    "%s": \{$' % re.escape(key), text, re.M)
    if not start:
        return None, f"no block for key {key!r}"
    end = text.find("\n    },", start.end())
    if end < 0:
        return None, f"unterminated block for key {key!r}"
    block = text[start.end():end]

    if '"main"' in block:
        return None, f"{key!r} already has a main"
    line = re.search(r'^ *\{[^\n]*"label": "%s" \},?$' % re.escape(char), block, re.M)
    if not line:
        return None, f"{char!r} is not among {key!r}'s relevant entries"

    entry = line.group(0).strip().rstrip(",")
    # Drop the line; if it was the last one, the new last line must lose its trailing comma.
    remaining = block[:line.start()] + block[line.end():]
    remaining = remaining.replace("\n\n", "\n")
    remaining = re.sub(r",(\s*\n\s*\])", r"\1", remaining)
    new_block = f'\n      "main": {entry},' + remaining
    return text[:start.end()] + new_block + text[end:], None


def main():
    parser = argparse.ArgumentParser(description="Apply measured long-press defaults.")
    parser.add_argument("languages", nargs="*", help="mapping names (default: all)")
    parser.add_argument("--check", action="store_true", help="do not write")
    args = parser.parse_args()

    names = args.languages or sorted(DECISIONS)
    failures = 0
    for name in names:
        decisions = DECISIONS.get(name)
        if decisions is None:
            print(f"{name}: not in the decision table", file=sys.stderr)
            failures += 1
            continue
        path = os.path.join(MAPPINGS, f"{name}.json")
        text = open(path, encoding="utf-8").read()
        applied = []
        for key, char in decisions.items():
            new_text, error = apply_to(text, key, char)
            if error:
                print(f"  {name}: {error}", file=sys.stderr)
                failures += 1
                continue
            text, _ = new_text, applied.append(f"{key}->{char}")

        # Nothing is written until the result parses and says what it was meant to say.
        try:
            data = json.loads(text)["all"]
        except Exception as exc:
            print(f"  {name}: result is not valid JSON ({exc}) — not written", file=sys.stderr)
            failures += 1
            continue
        for key, char in decisions.items():
            entry = data.get(key, {})
            main_label = (entry.get("main") or {}).get("label")
            labels = [r.get("label") for r in entry.get("relevant") or []]
            if main_label != char or char in labels:
                print(f"  {name}: {key!r} came out main={main_label!r} relevant={labels}",
                      file=sys.stderr)
                failures += 1
        if not args.check:
            open(path, "w", encoding="utf-8").write(text)
        print(f"  {name:<11} {'checked' if args.check else 'written'}: " + " ".join(applied))

    if failures:
        raise SystemExit(f"{failures} problem(s)")


if __name__ == "__main__":
    sys.exit(main())
