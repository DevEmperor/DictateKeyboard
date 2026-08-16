#!/usr/bin/env python3
"""
Measure which long-press character a popup mapping should default to (issue #279 follow-up).

A key's popup only puts a chosen character under the finger when the mapping declares it as `main`.
Without one, `PopupUiController` hands over `relevant[initUiIndex]` — the entry whose *list position*
happens to line up with where the key sits on the keyboard. That is how a Portuguese `E` came to
insert `ê`. 26 of the shipped mappings still declare no `main` for any letter, so their long-press
defaults are equally arbitrary.

Which character each key *should* default to is a question about the language, and this answers it
from the language's own words rather than from intuition: the glide-typing word lists in
tools/glide-dict/dist/ carry ~50k–70k words each with a frequency, so every candidate in a popup can
be weighed by how much of that language actually uses it.

Two measures are reported because they can disagree, and the disagreement is informative:
  * types  — the share of *distinct words* containing the character
  * tokens — the share weighted by how often those words occur (Zipf by rank)
Portuguese `a` is the example: á leads by types (62%), ã by tokens (53%), because ã lives in a few
very frequent words (não, são, então).

A recommendation is only made when the character is one the language demonstrably writes. If no
candidate for a key reaches MIN_SHARE of that language's words, the key is left without a `main` —
English is the case that matters here: its popups exist for foreign words, and defaulting `a` to `à`
would be worse than the arbitrary order it has now.

Usage:
    python3 measure.py                 # every mapping that has no main and a word list
    python3 measure.py pl cs sv        # only these
    python3 measure.py --verbose pl    # show every candidate, not just the winner
"""
import argparse
import collections
import json
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MAPPINGS = os.path.join(
    REPO, "app/src/main/assets/ime/keyboard/org.florisboard.localization/popupMappings"
)
WORDLISTS = os.path.join(REPO, "tools/glide-dict/dist")

# A candidate has to reach this share of the language's *distinct words* before it is worth
# defaulting to. Below it the character is decoration in that language, not part of writing it.
# Calibrated against languages whose answer is known: Spanish ñ sits at 1.4% and Portuguese ê at
# 1.1%, both obviously right; Spanish ç and ë sit at 0.0%, both obviously wrong.
MIN_SHARE = 0.002
# How far ahead the winner must be before the call is made without a human looking at it.
CLEAR_LEAD = 1.5


def load_wordlist(language):
    """The language's words with their weights, most frequent first."""
    for name in (language, language.split("-")[0]):
        path = os.path.join(WORDLISTS, f"{name}.json")
        if os.path.exists(path):
            return list(json.load(open(path, encoding="utf-8")).items()), name
    return None, None


def candidates_of(entry):
    """Every character a key's popup offers, `main` included — a mapping already fixed must be
    measurable against the same field, or its own default drops out of the comparison."""
    out = []
    items = ([entry["main"]] if entry.get("main") else []) + (entry.get("relevant") or [])
    for item in items:
        label = item.get("label")
        if isinstance(label, str) and len(label) == 1 and label.isalpha() and label not in out:
            out.append(label)
    return out


def measure(words, characters):
    """Share of the language's words carrying each character, by types and by tokens."""
    types = collections.Counter()
    tokens = collections.Counter()
    wanted = set(characters)
    for rank, (word, _) in enumerate(words, start=1):
        seen = wanted.intersection(word.lower())
        for char in seen:
            types[char] += 1
            tokens[char] += 1.0 / rank
    total_types = len(words)
    total_tokens = sum(1.0 / r for r in range(1, len(words) + 1))
    return (
        {c: types[c] / total_types for c in characters},
        {c: tokens[c] / total_tokens for c in characters},
    )


def main():
    parser = argparse.ArgumentParser(description="Measure long-press defaults from word lists.")
    parser.add_argument("languages", nargs="*", help="mapping names (default: all without a main)")
    parser.add_argument("--verbose", action="store_true", help="list every candidate")
    args = parser.parse_args()

    names = args.languages
    if not names:
        names = []
        for file in sorted(os.listdir(MAPPINGS)):
            if not file.endswith(".json"):
                continue
            data = json.load(open(os.path.join(MAPPINGS, file), encoding="utf-8")).get("all")
            if not isinstance(data, dict):
                continue
            letters = {k: v for k, v in data.items() if len(k) == 1 and k.isalpha()}
            if letters and not any(v.get("main") for v in letters.values()):
                names.append(file[:-5])

    for name in names:
        path = os.path.join(MAPPINGS, f"{name}.json")
        if not os.path.exists(path):
            print(f"{name}: no such mapping", file=sys.stderr)
            continue
        data = json.load(open(path, encoding="utf-8")).get("all") or {}
        words, source = load_wordlist(name)
        if words is None:
            print(f"\n=== {name}: no word list, cannot measure ===")
            continue

        print(f"\n=== {name} (word list: {source}, {len(words)} words) ===")
        for key, entry in data.items():
            if len(key) != 1 or not key.isalpha():
                continue
            characters = candidates_of(entry)
            if not characters:
                continue
            types, tokens = measure(words, characters)
            ranked = sorted(characters, key=lambda c: types[c] + tokens[c], reverse=True)
            best = ranked[0]
            runner = ranked[1] if len(ranked) > 1 else None
            if types[best] < MIN_SHARE and tokens[best] < MIN_SHARE:
                verdict = "— (not written in this language)"
            elif runner and (types[best] + tokens[best]) < CLEAR_LEAD * (types[runner] + tokens[runner]):
                verdict = f"{best}?  (close to {runner} — look at this one)"
            else:
                verdict = f"{best}"
            shown = ranked if args.verbose else ranked[:3]
            detail = "  ".join(f"{c} {types[c]:.1%}/{tokens[c]:.1%}" for c in shown)
            print(f"  {key} -> {verdict:<38} {detail}")

    print("\n(shares are types/tokens; '—' means leave the key without a main)")


if __name__ == "__main__":
    sys.exit(main())
