#!/usr/bin/env python3
"""
Generate a language's context tables — `<lang>_bigrams.txt` and `<lang>_trigrams.txt` (issue #334).

Replaces generate_bigrams.py, which read the Leipzig package's pre-computed `*-co_n.txt`. That file
holds adjacent *pairs* and can say nothing about triples, so the trigram table has to be counted from
the package's sentences — and once it is, the bigram table is counted from the sentences too. Not for
tidiness: a trigram and the bigram it backs off to are compared against each other at prediction time,
and two tables counted from two different notions of adjacency are not comparable.

    python3 generate_ngrams.py de --pkg deu_news_2022_1M

Sizes default to what the measurement chose (see eval_ngrams.py and the numbers in the commit that
added it): 150k bigrams and 100k trigrams. Both halves earn their bytes on different ground — more
bigram rows are worth *exactly zero* after a function word, where only the trigram helps, and the
trigram tier barely moves the band where the bigram table is empty, where only more rows help. The
trigram gain saturates early, which is why 100k rather than the 250k the issue suggests: T50 already
buys 4.0 of the 5.3 points available after a function word, T100 buys 4.6, and T250's last 0.7 costs
two and a half times the bytes.

Source is the Leipzig Corpora Collection (wortschatz-leipzig.de), CC BY — cite Goldhahn, Eckart &
Quasthoff, LREC 2012, and "© Universität Leipzig / Sächsische Akademie der Wissenschaften / InfAI".
What counts as a word comes from wordfilter.py, shared with generate.py, so a language's word list and
its context tables never disagree about their own vocabulary.

Prints the two catalog lines on success, for pasting into BigramCatalog.kt / TrigramCatalog.kt.
"""
import os, sys, argparse

from ngramcount import count_ngrams, runs_path, write_table

# Which Leipzig package each language is built from. generate_bigrams.py had this table too and it
# only ever held nine of the forty-one shipped languages, so the record of what most files were built
# from existed nowhere. Fill a row in whenever a language is (re)generated.
KNOWN_PACKAGES = {
    "ar": "ara_news_2022_1M",
    "bn": "ben_wikipedia_2021_1M",
    "de": "deu_news_2022_1M",
    "en": "eng_news_2024_1M",
    "fi": "fin_news_2022_1M",
    "hi": "hin_news_2022_1M",
    "id": "ind_news_2022_1M",
    "nl": "nld_news_2023_1M",
    "ta": "tam_wikipedia_2021_1M",
    "ur": "urd_newscrawl_2016_1M",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("lang", help="output language code, e.g. de")
    ap.add_argument("--pkg", help="Leipzig package basename; defaults to the recorded one")
    ap.add_argument("--bigrams", type=int, default=150_000)
    ap.add_argument("--trigrams", type=int, default=100_000)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "dist"))
    ap.add_argument("--cache", default=os.path.join(os.path.dirname(__file__), "dist", "corpora"))
    a = ap.parse_args()

    pkg = a.pkg or KNOWN_PACKAGES.get(a.lang)
    if not pkg:
        sys.exit(f"error: no package recorded for {a.lang} — pass --pkg")

    sys.stderr.write(f"counting {pkg} for {a.lang}\n")
    # The whole corpus, not the training slice: the held-out tenth exists so a measurement has text
    # its tables have never seen, and there is nothing to hold out from a table that ships.
    _, bi, tri, n = count_ngrams(runs_path(pkg, a.cache, "all"), a.bigrams, a.trigrams)
    sys.stderr.write(f"  {n} runs · {len(bi)} bi · {len(tri)} tri\n")

    for kind, counts, top, cls in (("bigrams", bi, a.bigrams, "BigramDict"),
                                   ("trigrams", tri, a.trigrams, "TrigramDict")):
        path = os.path.join(a.out, f"{a.lang}_{kind}.txt")
        size, entries, sha = write_table(counts, top, path)
        sys.stderr.write(f"  wrote {path} ({size} bytes, {entries} entries)\n")
        print(f'{cls}("{a.lang}", "$REL/{a.lang}_{kind}.txt", {size}, "{sha}"),')


if __name__ == "__main__":
    main()
