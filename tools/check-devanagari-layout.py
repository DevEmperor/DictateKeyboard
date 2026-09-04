#!/usr/bin/env python3
"""Audit a Devanagari character layout (issue #315).

A Gradle compile check never opens an asset, so a layout that ships a wrong code point, a label that
does not match what the key sends, or a letter nobody can reach looks perfectly healthy until someone
types with it. This walks the layout, its shift faces and its popup mapping and reports:

  * every key whose label disagrees with the code point(s) it sends,
  * which of the 33 consonants, 11 independent vowels, 10 vowel signs and 5 signs are unreachable,
  * whether the layout id is registered exactly once and used by exactly one subtype preset.

Usage:  python3 tools/check-devanagari-layout.py [layout_id]   (default: hindi_varnamala)
"""

import json
import sys
import unicodedata
from pathlib import Path

ASSETS = Path(__file__).resolve().parent.parent / "app/src/main/assets/ime/keyboard"
LAYOUTS = ASSETS / "org.florisboard.layouts"
LOCALIZATION = ASSETS / "org.florisboard.localization"

NUKTA = 0x093C
CONSONANTS = list(range(0x0915, 0x093A))  # क .. ह, 37 slots of which 33 are the standard alphabet
NON_STANDARD = {0x0929, 0x0931, 0x0933, 0x0934}  # ऩ ऱ ळ ऴ — not part of the Hindi 33
VOWELS = [0x0905, 0x0906, 0x0907, 0x0908, 0x0909, 0x090A, 0x090B, 0x090F, 0x0910, 0x0913, 0x0914]
MATRAS = [0x093E, 0x093F, 0x0940, 0x0941, 0x0942, 0x0947, 0x0948, 0x094B, 0x094C]
SIGNS = [0x0901, 0x0902, 0x0903, NUKTA, 0x094D]


def name(cp):
    try:
        return unicodedata.name(chr(cp))
    except ValueError:
        return "?"


def sent(key):
    """The code points a key sends, or None if it is not a character key."""
    kind = key.get("$")
    if kind == "multi_text_key":
        return list(key["codePoints"])
    if "code" in key:
        return [key["code"]]
    return None


def walk(key, ctx, reachable, problems):
    """Collect what a key can produce, following selectors into both of their faces."""
    kind = key.get("$")
    if kind in ("case_selector", "shift_state_selector"):
        for face, sub in key.items():
            if face != "$":
                walk(sub, f"{ctx}.{face}", reachable, problems)
        return
    if kind in ("variation_selector", "layout_direction_selector", "char_width_selector", "kana_selector"):
        for face, sub in key.items():
            if face != "$" and isinstance(sub, dict):
                walk(sub, f"{ctx}.{face}", reachable, problems)
        return

    codes = sent(key)
    if codes is None or any(c < 0x20 for c in codes):
        return  # negative values are internal actions (view switches, .com), not characters
    label = (key.get("label") or "").strip("◌ ")  # keys draw a dotted circle under a lone mark
    expected = "".join(chr(c) for c in codes)
    if kind == "devanagari_vowel_key":
        # The adaptive face sends the matra instead, with the pending consonant only drawn, not sent.
        if "matra" in key and key["matra"]:
            reachable.add(key["matra"])
    # Zero-width joiners carry a drawn stand-in for a label, and a direction selector deliberately
    # mirrors brackets, so neither can be checked against its code point.
    checkable = label and not ctx.endswith(".rtl") and \
        all(unicodedata.category(c) != "Cf" for c in expected)
    if checkable and label != expected:
        problems.append(f"{ctx}: label {label!r} does not match what the key sends ({expected!r})")
    for c in codes:
        reachable.add(c)


def report(title, code_points, reachable):
    missing = [c for c in code_points if c not in reachable]
    status = "OK" if not missing else "MISSING " + ", ".join(f"{chr(c)} U+{c:04X} {name(c)}" for c in missing)
    print(f"  {title}: {len(code_points) - len(missing)}/{len(code_points)}  {status}")
    return missing


def main():
    layout_id = sys.argv[1] if len(sys.argv) > 1 else "hindi_varnamala"
    layout_path = LAYOUTS / "layouts/characters" / f"{layout_id}.json"
    rows = json.loads(layout_path.read_text(encoding="utf-8"))

    reachable, problems = set(), []
    for r, row in enumerate(rows, 1):
        for k, key in enumerate(row, 1):
            walk(key, f"row {r} key {k}", reachable, problems)
    on_keys = set(reachable)

    popup_reachable, popup_problems = set(), []
    mapping = json.loads((LOCALIZATION / "popupMappings/hi-IN.json").read_text(encoding="utf-8"))
    for group, entries in mapping.items():
        for base, entry in entries.items():
            if entry.get("main"):
                walk(entry["main"], f"popup {group}/{base}/main", popup_reachable, popup_problems)
            for i, rel in enumerate(entry.get("relevant", [])):
                walk(rel, f"popup {group}/{base}/relevant[{i}]", popup_reachable, popup_problems)
    reachable |= popup_reachable

    print(f"{layout_id}: {len(rows)} rows {[len(r) for r in rows]}, {sum(len(r) for r in rows)} key slots\n")
    print("On the keys themselves (including shift faces):")
    standard = [c for c in CONSONANTS if c not in NON_STANDARD]
    report("consonants", standard, on_keys)
    report("independent vowels", VOWELS, on_keys)
    report("vowel signs", MATRAS, on_keys)
    report("signs", SIGNS, on_keys)
    print("\nIncluding long-press popups:")
    failures = []
    failures += report("consonants", standard, reachable)
    failures += report("independent vowels", VOWELS, reachable)
    failures += report("vowel signs", MATRAS, reachable)
    failures += report("signs", SIGNS, reachable)

    registry = json.loads((LAYOUTS / "extension.json").read_text(encoding="utf-8"))
    registered = [c for c in registry["layouts"]["characters"] if c["id"] == layout_id]
    presets = json.loads((LOCALIZATION / "extension.json").read_text(encoding="utf-8"))["subtypePresets"]
    used = [p for p in presets if p["preferred"].get("characters", "").endswith(f":{layout_id}")]
    print(f"\nRegistered in the layout extension: {len(registered)} (want 1)")
    print(f"Used by subtype presets: {len(used)} (want 1)")
    hi = [p for p in presets if p["languageTag"].startswith("hi")]
    print(f"First hi-* preset (this is the default a new Hindi user gets): "
          f"{hi[0]['preferred'].get('characters') if hi else 'none'}")

    print()
    for p in problems + popup_problems:
        print("PROBLEM:", p)
    ok = not problems and not popup_problems and not failures and len(registered) == 1 and len(used) == 1
    print("RESULT:", "ok" if ok else "needs attention")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
