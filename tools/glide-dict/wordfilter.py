#!/usr/bin/env python3
"""
What counts as a word, for every script the keyboard supports.

Shared by generate.py (unigram dictionaries) and generate_bigrams.py (context data) so the two can
never drift apart on the question "is this token a word".

The rule this replaces was `[^\\W\\d_]+(?:['’-][^\\W\\d_]+)*$`, which demanded that every character be
alphanumeric. Combining marks are not — Unicode files them under Mn (non-spacing, e.g. the Arabic
fatha) and Mc (spacing combining, e.g. the Devanagari vowel sign i). Since practically every Hindi,
Bengali and Tamil word carries at least one, that rule discarded almost the entire language:

    Bengali  9,194 → 100,868 words        Tamil  1,307 → 18,926        Hindi  6,206 → 25,828

For scripts that do not use combining marks the change is a rounding error (German +996, Finnish
+387, Indonesian +1,265 — decomposed accent forms that were wrongly dropped), which is why the 33
dictionaries generated before this fix stay valid.
"""
import unicodedata

# Characters allowed inside a word but not at either end.
JOINERS = "'’-"

# Zero-width non-joiner / joiner: Persian and Urdu use these *inside* words (e.g. می‌روم), and
# Unicode files them as Cf (format), so they need explicit permission. They are removed again by
# strip_arabic_marks() before the word is stored.
ZERO_WIDTH = "‌‍"

# Arabic-script marks that carry no lexical meaning and must not create separate dictionary entries:
# tashkil (vowel points), the superscript alef, Quranic annotation marks, and tatweel (the decorative
# elongation, U+0640). Deliberately a fixed list of Arabic ranges rather than "every Mn" — Indic
# vowel signs are also Mn and are very much part of the word.
_ARABIC_MARKS = (
    "".join(chr(c) for c in range(0x0610, 0x061B))      # Arabic signs (sallallahou alayhe wasallam …)
    + "".join(chr(c) for c in range(0x064B, 0x0660))    # tashkil: fatha … sukun, plus superscript marks
    + "ٰ"                                          # superscript alef
    + "".join(chr(c) for c in range(0x06D6, 0x06ED + 1))  # Quranic annotation marks
    + "".join(chr(c) for c in range(0x08E3, 0x0900))    # Arabic Extended-A marks
    + "ـ"                                          # tatweel
    + ZERO_WIDTH
)
_ARABIC_MARK_TABLE = {ord(c): None for c in _ARABIC_MARKS}


def strip_arabic_marks(word: str) -> str:
    """Remove Arabic diacritics, tatweel and zero-width joiners. A no-op for every other script."""
    return word.translate(_ARABIC_MARK_TABLE)


def is_word(w: str, max_len: int = 30) -> bool:
    """Whether [w] is a real word in any script: letters plus their combining marks, optionally joined
    by an internal apostrophe or hyphen. No digits, no punctuation, no leading/trailing joiner."""
    if not (1 <= len(w) <= max_len):
        return False
    if w[0] in JOINERS or w[-1] in JOINERS:
        return False
    seen_letter = False
    for ch in w:
        if ch in JOINERS or ch in ZERO_WIDTH:
            continue
        cat = unicodedata.category(ch)
        if cat[0] == "L":
            seen_letter = True
        elif cat not in ("Mn", "Mc"):
            return False
    return seen_letter


def _selftest():
    """Run with `python3 wordfilter.py`. The Kotlin side has the same cases in WordMarkTest."""
    good = [
        "Baum", "straße", "don't", "well-known",          # Latin, with and without joiners
        "мир", "Ελλάδα",                                   # Cyrillic, Greek
        "كتاب", "مَدْرَسَة", "می‌روم",                        # Arabic bare / vowelled / with ZWNJ
        "किताब", "नमस्ते", "क्या",                             # Devanagari (Mc + Mn)
        "বাংলা", "আমি",                                     # Bengali
        "தமிழ்", "புத்தகம்",                                  # Tamil
    ]
    bad = [
        "", "abc123", "42", "_x", "-word", "word-", "'", "a" * 31, "hello!", "a b",
    ]
    for w in good:
        assert is_word(w), f"expected a word: {w!r}"
    for w in bad:
        assert not is_word(w), f"expected NOT a word: {w!r}"
    assert strip_arabic_marks("مَدْرَسَة") == "مدرسة"
    assert strip_arabic_marks("مــن") == "من"          # tatweel
    assert strip_arabic_marks("می‌روم") == "میروم"      # ZWNJ
    assert strip_arabic_marks("किताब") == "किताब"          # Indic marks untouched
    assert strip_arabic_marks("Baum") == "Baum"
    print(f"wordfilter selftest OK ({len(good)} words, {len(bad)} non-words)")


if __name__ == "__main__":
    _selftest()
