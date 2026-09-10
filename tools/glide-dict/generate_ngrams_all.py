#!/usr/bin/env python3
"""
Build the context tables for every shipped language (issue #334).

    python3 generate_ngrams_all.py                 # all of them, resuming where it left off
    python3 generate_ngrams_all.py --jobs 3        # three at a time
    python3 generate_ngrams_all.py de fr           # only these

    python3 ngram_status.py                        # how far along it is

One language is a ~250 MB download and about four minutes of counting. Several run at once because
most of that is a single-threaded count on one core and a download on none: measured, one language
peaks well under a gigabyte, so three fit alongside each other on an ordinary machine while a single
one leaves seven cores idle.

It is resumable on purpose: a language whose two output files already exist is skipped, and the corpus
cache under dist/corpora survives too, so an interrupted run costs only the languages in flight.

Prints the catalog lines for every language it built, collected into dist/ngram_catalog.txt. That file
is rewritten to hold **every** table currently in dist/, not just this run's — the old
generate_all.py overwrote it with the last run's languages only, which is how the full list stopped
existing.
"""
import concurrent.futures, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DIST = os.path.join(HERE, "dist")

BIGRAMS = 150_000
TRIGRAMS = 100_000

# Which Leipzig package each language is built from. The ten that were already recorded keep their
# corpus — a language's word list and its context tables agreeing about vocabulary is worth more than
# a newer year — and the rest were resolved against the download server, preferring news, then a news
# crawl, then Wikipedia, newest first.
PACKAGES = {
    "ar": "ara_news_2022_1M",
    "bg": "bul_news_2022_1M",
    "bn": "ben_wikipedia_2021_1M",
    "ca": "cat_newscrawl_2016_1M",
    "cs": "ces_news_2024_1M",
    "da": "dan_news_2021_1M",
    "de": "deu_news_2022_1M",
    "el": "ell_news_2024_1M",
    "en": "eng_news_2024_1M",
    "eo": "epo_newscrawl_2017_1M",
    "es": "spa_news_2024_1M",
    "et": "est_newscrawl_2017_1M",
    "fa": "fas_news_2024_1M",
    "fi": "fin_news_2022_1M",
    "fr": "fra_news_2024_1M",
    "he": "heb_news_2020_1M",
    "hi": "hin_news_2022_1M",
    "hr": "hrv_news_2020_1M",
    "hu": "hun_news_2024_1M",
    "hy": "hye_wikipedia_2021_1M",
    "id": "ind_news_2022_1M",
    "is": "isl_newscrawl_2011_1M",
    "it": "ita_news_2024_1M",
    "ka": "kat_newscrawl_2016_1M",
    "lt": "lit_news_2020_1M",
    "lv": "lav_newscrawl_2016_1M",
    "nb": "nob_news_2013_1M",
    "nl": "nld_news_2023_1M",
    # The only language Leipzig has no million-sentence corpus for. 300,000 sentences yield a much
    # thinner table, which the min-count floor in write_table turns into a shorter one rather than a
    # table of coincidences.
    "nn": "nno_wikipedia_2021_300K",
    "pl": "pol_news_2024_1M",
    "pt": "por_news_2024_1M",
    "ro": "ron_news_2024_1M",
    "ru": "rus_news_2024_1M",
    "sk": "slk_newscrawl_2016_1M",
    "sl": "slv_news_2020_1M",
    "sr": "srp_wikipedia_2021_1M",
    "sv": "swe_news_2023_1M",
    "ta": "tam_wikipedia_2021_1M",
    "tr": "tur_news_2024_1M",
    "uk": "ukr_news_2024_1M",
    "ur": "urd_newscrawl_2016_1M",
    "vi": "vie_news_2022_1M",
}


def outputs(lang):
    return (os.path.join(DIST, f"{lang}_bigrams_{BIGRAMS // 1000}k.txt"),
            os.path.join(DIST, f"{lang}_trigrams_{TRIGRAMS // 1000}k.txt"))


def collect_catalog():
    """Rewrite dist/ngram_catalog.txt from every table present, so it is always the whole list."""
    lines = []
    for lang in sorted(PACKAGES):
        log = os.path.join(DIST, f"{lang}.catalog")
        if os.path.isfile(log):
            lines.append(open(log, encoding="utf-8").read().rstrip("\n"))
    with open(os.path.join(DIST, "ngram_catalog.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def build(lang):
    """Run one language to completion. Its own log goes to dist/<lang>.log, because several of these
    run at once and interleaved progress lines would be unreadable."""
    with open(os.path.join(DIST, f"{lang}.log"), "w", encoding="utf-8") as log:
        proc = subprocess.run(
            [sys.executable, "-u", os.path.join(HERE, "generate_ngrams.py"), lang,
             "--pkg", PACKAGES[lang], "--bigrams", str(BIGRAMS), "--trigrams", str(TRIGRAMS)],
            stdout=subprocess.PIPE, stderr=log, text=True,
        )
    if proc.returncode != 0:
        return lang, proc.returncode
    with open(os.path.join(DIST, f"{lang}.catalog"), "w", encoding="utf-8") as f:
        f.write(proc.stdout)
    return lang, 0


def main():
    args = [a for a in sys.argv[1:]]
    jobs = 3
    if "--jobs" in args:
        i = args.index("--jobs")
        jobs = int(args[i + 1])
        del args[i:i + 2]

    langs = args or sorted(PACKAGES)
    unknown = [l for l in langs if l not in PACKAGES]
    if unknown:
        sys.exit(f"error: no package recorded for {', '.join(unknown)}")

    todo = []
    for lang in langs:
        big, tri = outputs(lang)
        if os.path.isfile(big) and os.path.isfile(tri):
            sys.stderr.write(f"{lang}: already built, skipping\n")
        else:
            todo.append(lang)

    sys.stderr.write(f"building {len(todo)} languages, {jobs} at a time\n")
    done = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        for lang, rc in pool.map(build, todo):
            done += 1
            state = "ok" if rc == 0 else f"FAILED ({rc}), see dist/{lang}.log"
            sys.stderr.write(f"[{done}/{len(todo)}] {lang}: {state}\n")
            collect_catalog()

    collect_catalog()
    sys.stderr.write(f"\ncatalog lines in {os.path.join(DIST, 'ngram_catalog.txt')}\n")


if __name__ == "__main__":
    main()
