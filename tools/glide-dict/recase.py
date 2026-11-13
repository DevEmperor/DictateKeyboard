#!/usr/bin/env python3
"""
Restore the casing of an existing word list, leaving its words and their frequencies exactly as they are.

The bundled English list (app/src/main/assets/ime/dict/en.json) came from upstream FlorisBoard entirely in
lowercase — `tuesday`, `john`, `i` — so the keyboard could not tell a name from any other word, and a
dictation written into the middle of a sentence could not either (issue #476). Regenerating the list with
generate.py would have changed its words and frequencies too, and with them how glide typing and
autocorrect behave. This applies only generate.py's casing step, with the same two sources:

  - Hunspell as the oracle: a word it rejects in lowercase but accepts with a capital first letter is stored
    with that capital. A word rejected both ways stays as it is (generate.py drops those as noise; here the
    list is kept whole), and so does one Hunspell knows only in capitals (`DVD`) or with a capital inside.
  - With --corpus, a Leipzig package, the way generate.py --corpus-case does it for Afrikaans: a word is
    capitalised when the corpus writes it so mid-sentence at least CORPUS_CASE_SHARE of CORPUS_CASE_MIN or
    more times. That is what tells the names `john`, `peter` and `anna` from the words `john` (toilet),
    `peter` (to peter out) and `anna` (a coin), which the dictionaries all know; for those, Hunspell must
    accept the corpus spelling as well. For a word Hunspell does not know at all (`kardashian`, `spotify`)
    the corpus is the only evidence there is, and decides alone. And the other way round: a capital
    Hunspell asks for that the corpus writes in lowercase mid-sentence most of the time stays off.
  - --mixed writes the words that stay small although the corpus capitalises them mid-sentence at least
    MIXED_SHARE of the time: `ben`, `terry`, `frank`, and `north` from North Carolina. Too often small to
    be capitalised when typed, too often a name for a dictation to take a provider's capital off them in
    the middle of a sentence (#476); the keyboard reads the file for that.

The keyboard commits a capitalised dictionary spelling when the word is typed in lowercase (the rule that
writes German nouns), so a wrong capital here is a wrong capital in everything the user types. Hence three
limits on top, all found by reading the dry run of en.json (2026-11-11):
  - A word another variant of the language accepts in lowercase stays small (--also): the US dictionary
    knows `labour` and `grey` only as names, the British one as ordinary words.
  - Two-letter words stay small, but for the titles in TWO_LETTER_TITLES. What Hunspell accepts only
    capitalised at that length is mostly chemistry and post codes (Na, Au, Va, Ur, Ty), while the subtitles
    have `na`, `ur` and `ty` as chat words.
  - THREE_LETTER_NOT_NAMES: the three-letter words the dry run would have capitalised that are no names
    in running text — foreign words (que, sur), abbreviations (fla, adm) and clipped words (eco, blu).
A letter's plural (`a's`) is left alone too.

The casing is always decided from the list's lowercase spellings, so running this again on its own output
gives the same file.

Usage:
    recase.py <list.json> <hunspell base> [--also <base>]… [--corpus <leipzig package> [--mixed <out.txt>]]
              [--dry-run]
    recase.py app/src/main/assets/ime/dict/en.json /usr/share/hunspell/en_US \\
        --also <…>/en_GB --corpus eng-com_web-public_2018_1M \\
        --mixed app/src/main/assets/ime/dict/en_case_mixed.txt --dry-run

    recase.py dist/hr.json dist/hunspell/hr --corpus hrv_news_2020_1M \\
        --mixed app/src/main/assets/ime/dict/hr_case_mixed.txt --dry-run

Croatian (#476, 2026-11-13) is the first generated list it ran on. generate.py had cased it with Hunspell
alone, and the Croatian dictionary takes marko, sara and uskrs in lowercase as readily as with a capital,
so only the corpus can tell. THREE_LETTER_NOT_NAMES is English and applies to en.json alone: on Croatian it
took the capital off Rom. AS_IS holds, per language, the words read out of the dry run that the corpus
would capitalise but that are everyday lowercase words as well; they keep the list's casing, and a small
one can still go into the --mixed list.

Requires the `hunspell` binary; a --corpus package is downloaded into dist/corpora on first use (~230 MB).
The list is rewritten in place in the format it was read in (json.dumps defaults plus a final newline, the
format en.json has, or generate.py's compact UTF-8 without one); --dry-run prints what would change against
the file as it is instead.
"""
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from generate import CORPUS_CASE_MIN, corpus_spelling, load_mid_sentence_forms  # noqa: E402

TWO_LETTER_TITLES = {"mr", "ms", "dr", "jr", "sr"}

THREE_LETTER_NOT_NAMES = {
    "abo", "adm", "afr", "blu", "btu", "cdr", "che", "chr", "dem", "eco", "emp", "esq", "eur", "fla", "fra",
    "ger", "heb", "hel", "hts", "jnr", "jpn", "lon", "mex", "mme", "mon", "mss", "nam", "nev", "noh", "ont",
    "pfc", "pte", "pty", "que", "rom", "sgt", "snr", "sta", "ste", "sur", "tex", "wac", "wis",
}

# Per language (the list's file name), words whose casing the list keeps, read out of the dry run: the news
# corpus writes them with a capital almost always, but people type them as ordinary words too.
#   hr (2026-11-13): mario (nije mario, "didn't care"), monitor, lokomotivu (a Zagreb club, and a locomotive),
#   rima/rimu (Rome, and rhyme), luki (the name, and "in the port"), macan (tomcat, a good-looking man),
#   zlatan (golden, "zlatan si"), nosić (little nose), trut (drone bee), the adjectives latinskoj and
#   rumunjskoj (Latin, Romanian, beside Latin America and Romania), and the forms of sjedinjen and ujedinjen
#   (united) that the corpus would capitalise, so each paradigm stays one casing like the forms that are
#   capitalised only half the time. The other way: Krim (Crimea) keeps the capital Hunspell gave it, which
#   72 corpus sightings, mostly the abbreviation krim., are too few to take away.
AS_IS = {
    "hr": {
        "mario", "monitor", "lokomotivu", "rima", "rimu", "luki", "macan", "zlatan", "nosić", "trut",
        "latinskoj", "rumunjskoj", "sjedinjenih", "sjedinjene", "sjedinjenim", "ujedinjenih", "ujedinjenog",
        "ujedinjenom", "krim",
    },
}

LETTER_PLURAL = re.compile(r"^[a-z]'s$")

# The corpus share below which a capital Hunspell asks for stays off: generate.py's reverse rule.
CORPUS_LOWER_SHARE = 0.6

# The capitalised share from which a word that stays small goes into the --mixed list: as often a capital
# as not, so nothing says which one a dictation opening with it meant.
MIXED_SHARE = 0.5

# The formats a list is read and written back in: en.json's (json.dumps defaults, final newline) and
# generate.py's (compact, UTF-8 as it is, no final newline).
FORMATS = [({}, "\n"), ({"ensure_ascii": False, "separators": (",", ":")}, "")]


def capital_share(word: str, forms: dict):
    """How often the corpus writes [word] with a capital first letter mid-sentence, and on how many
    sightings — None below CORPUS_CASE_MIN."""
    counts = forms.get(word) or {}
    total = sum(counts.values())
    if total < CORPUS_CASE_MIN:
        return None
    capital = sum(n for s, n in counts.items() if s[:1].isupper() and s[1:] == s[1:].lower())
    return capital / total


def rejected(words, dictionary: str) -> set:
    """The words Hunspell does not accept, by `hunspell -l` (see generate.hunspell_checker)."""
    proc = subprocess.run(
        ["hunspell", "-l", "-d", dictionary, "-i", "utf-8"],
        input="\n".join(words) + "\n", capture_output=True, text=True, check=True,
    )
    return set(proc.stdout.split("\n")) - {""}


def wanted(word: str, lang: str) -> bool:
    """Whether [word] may take a capital at all, before any dictionary is asked (see the module doc)."""
    if LETTER_PLURAL.match(word):
        return False
    if len(word) <= 2:
        return word in TWO_LETTER_TITLES
    return lang != "en" or word not in THREE_LETTER_NOT_NAMES


def casing(lowers: list, dictionary: str, also: list, forms: dict, lang: str) -> dict:
    """lowercase word → its capitalised spelling, for the words that take one. [forms] from
    load_mid_sentence_forms, or None for Hunspell alone."""
    candidates = [w for w in lowers if wanted(w, lang)]
    not_small = rejected(candidates, dictionary)
    for other in also:
        not_small &= rejected(sorted(not_small), other)
    known_small = set(candidates) - not_small
    titled = {w: w[0].upper() + w[1:] for w in candidates if w in not_small}
    refused = rejected(titled.values(), dictionary)
    cased = {w: t for w, t in titled.items() if t not in refused}
    print(f"hunspell: {len(not_small)} rejected in lowercase, {len(cased)} take a capital", file=sys.stderr)
    if forms is None:
        return cased

    spelled = {w: f for w in candidates if w not in cased and (f := corpus_spelling(w, forms)) is not None}
    bad = rejected(spelled.values(), dictionary)
    # A dictionary word needs Hunspell's agreement; a word it does not know has only the corpus.
    named = {w: f for w, f in spelled.items() if f not in bad or w not in known_small}
    lowered = []
    for w in list(cased):
        counts = forms.get(w) or {}
        total = sum(counts.values())
        if total >= CORPUS_CASE_MIN and counts.get(w, 0) >= CORPUS_LOWER_SHARE * total:
            lowered.append(w)
            del cased[w]
    cased.update(named)
    print(f"corpus: {len(named)} more take a capital, {len(lowered)} stay small: {' '.join(sorted(lowered))}",
          file=sys.stderr)
    return cased


def mixed(lowers: list, cased: dict, forms: dict) -> list:
    """The words that stay small although the corpus capitalises them at least MIXED_SHARE of the time."""
    shares = {w: capital_share(w, forms) for w in lowers if w not in cased}
    return sorted(w for w, s in shares.items() if s is not None and s >= MIXED_SHARE)


def main() -> None:
    argv = sys.argv[1:]
    dry_run = "--dry-run" in argv
    also, corpus, mixed_path, args = [], "", "", []
    i = 0
    while i < len(argv):
        if argv[i] in ("--also", "--corpus", "--mixed") and i + 1 < len(argv):
            if argv[i] == "--also":
                also.append(argv[i + 1])
            elif argv[i] == "--corpus":
                corpus = argv[i + 1]
            else:
                mixed_path = argv[i + 1]
            i += 2
            continue
        if not argv[i].startswith("--"):
            args.append(argv[i])
        i += 1
    if len(args) != 2 or (mixed_path and not corpus):
        sys.exit(__doc__)
    path, dictionary = args
    text = open(path, encoding="utf-8").read()
    words = json.loads(text)
    fmt = next(((opts, end) for opts, end in FORMATS if json.dumps(words, **opts) + end == text), None)
    if fmt is None:
        sys.exit(f"{path} is not in a format this tool writes back; nothing changed")
    lowers = [w.lower() for w in words]
    if len(set(lowers)) != len(lowers):
        sys.exit(f"{path} holds a word in two casings; nothing changed")

    lang = os.path.basename(path).split(".")[0]
    forms = load_mid_sentence_forms(corpus) if corpus else None
    cased = casing(lowers, dictionary, also, forms, lang)
    for low, old in zip(lowers, words):
        if low in AS_IS.get(lang, ()):
            if old != low:
                cased[low] = old
            else:
                cased.pop(low, None)
    out = {cased.get(low, low): f for low, f in zip(lowers, words.values())}

    changed = [(old, new, f) for (old, f), new in zip(words.items(), out) if old != new]
    print(f"{len(words)} words, {sum(1 for w in out if w[:1].isupper())} with a capital, "
          f"{len(changed)} differ from the file", file=sys.stderr)
    for old, new, f in sorted(changed, key=lambda c: -c[2]):
        print(f"{f}\t{old}\t{new}")
    both = mixed(lowers, cased, forms) if mixed_path else []
    if mixed_path:
        print(f"mixed: {len(both)} words small in the list, a capital at least half the time in the corpus",
              file=sys.stderr)
    if dry_run:
        for w in both:
            print(f"mixed\t{w}")
        return
    with open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(out, **fmt[0]) + fmt[1])
    if mixed_path:
        with open(mixed_path, "w", encoding="utf-8") as f:
            f.write("\n".join(both) + "\n")


if __name__ == "__main__":
    main()
