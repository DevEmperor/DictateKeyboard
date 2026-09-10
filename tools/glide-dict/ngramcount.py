#!/usr/bin/env python3
"""
Reading a Leipzig package and counting word n-grams in it (issue #334).

Shared by eval_ngrams.py (which measures what a table is worth) and generate_ngrams.py (which writes
the table that ships), for the same reason wordfilter.py is shared by generate.py and
generate_bigrams.py: the two must never disagree about what a word is, where a run of words ends, or
how an n-gram is counted. Measuring one thing and shipping another is exactly the failure that made
the #242 numbers impossible to reproduce.

The corpora are the Leipzig Corpora Collection (downloads.wortschatz-leipzig.de), CC BY — © Universität
Leipzig / Sächsische Akademie der Wissenschaften / InfAI; Goldhahn, Eckart & Quasthoff, LREC 2012.

Everything here reads the package's `*-sentences.txt`, not the `*-co_n.txt` that generate_bigrams.py
used: that file holds pre-computed adjacent *pairs* and can say nothing about triples. Bigrams are
therefore recounted from the sentences too, so that a bigram and the trigram backing off to it are
counted the same way and their scores stay comparable.
"""
import sys, os, io, tarfile, urllib.request, heapq
from collections import Counter

from wordfilter import is_word, strip_arabic_marks

LEIPZIG = "https://downloads.wortschatz-leipzig.de/corpora"

# Which sentences are held back from counting, so a measurement has text the tables have never seen.
# Leipzig sorts its sentence file ALPHABETICALLY, so "the last tenth" would be every sentence starting
# with a late letter — a test set with a different vocabulary from its training set. Every tenth id
# keeps the two halves drawn from the same distribution. The generator uses the whole corpus.
HOLDOUT_EVERY = 10

# How many hash shards the corpus is counted in — see [count_ngrams]. Peak memory is one shard's
# worth, so this trades wall clock (one pass over the tokenised runs each) for RAM.
SHARDS = 8

# Stripped from both ends of a token before it is judged. The joiners are in here on purpose: `'` and
# `-` belong inside a word ("don't", "well-known") and is_word refuses them at either edge, so a
# quoted word would otherwise be thrown away along with the pair it stands in.
EDGE_PUNCT = "\"'’‘“”()[]{}<>«»„.,;:!?…—–-"


def package_path(pkg: str, cache: str) -> str:
    """The package tarball, downloaded into [cache] on first use. They are ~230-290 MB each."""
    os.makedirs(cache, exist_ok=True)
    path = os.path.join(cache, f"{pkg}.tar.gz")
    if os.path.isfile(path) and os.path.getsize(path) > 0:
        return path
    url = f"{LEIPZIG}/{pkg}.tar.gz"
    sys.stderr.write(f"  GET {url}\n")
    req = urllib.request.Request(url, headers={"User-Agent": "dictate-ngram-tools"})
    tmp = path + ".tmp"
    with urllib.request.urlopen(req) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if total:
                sys.stderr.write(f"\r  {done * 100 // total}%")
    sys.stderr.write("\r  done\n")
    os.replace(tmp, path)
    return path


def sentences(pkg: str, cache: str):
    """Yield `(id, text)` from the package's `*-sentences.txt` (tab-separated, id first)."""
    tar = tarfile.open(package_path(pkg, cache), mode="r:gz")
    name = next((m for m in tar.getnames() if m.endswith("-sentences.txt")), None)
    if name is None:
        sys.exit(f"error: no -sentences.txt in {pkg}")
    for line in io.TextIOWrapper(tar.extractfile(name), encoding="utf-8", errors="replace"):
        sid, tab, text = line.partition("\t")
        if tab and sid.isdigit():
            yield int(sid), text.rstrip("\n")


def windows(text: str):
    """Split a sentence into runs of words, the way the keyboard sees them.

    A token that is not a word ends the run rather than being skipped over. That mirrors the runtime:
    `previousWordOf` walks letters backwards and stops at the first non-word character, so the
    keyboard never sees a pair spanning a comma or a digit either. Joining across one would build a
    table of context the engine can never look up.
    """
    run = []
    for raw in text.split():
        # Quotes, brackets and sentence punctuation come off both ends; the apostrophe and hyphen go
        # too, because they are joiners only *inside* a word and is_word rejects them at either edge.
        tok = strip_arabic_marks(raw.lower().strip(EDGE_PUNCT))
        if tok and is_word(tok):
            run.append(tok)
        else:
            if len(run) >= 2:
                yield run
            run = []
    if len(run) >= 2:
        yield run


def runs_path(pkg: str, cache: str, slice_: str) -> str:
    """Tokenise the package once into a plain file of word runs, one run per line.

    Counting reads this file [SHARDS] times, and re-running the tokeniser (and gzip) for each of
    those passes is most of the wall clock. `slice_` is `train` (everything but the held-out
    sentences), `holdout` (only those), or `all` (the whole corpus — what the generator ships from).
    """
    out = os.path.join(cache, f"{pkg}-{slice_}.runs")
    if os.path.isfile(out) and os.path.getsize(out) > 0:
        return out
    sys.stderr.write(f"  tokenising {pkg} [{slice_}]\n")
    tmp = out + ".tmp"
    n = 0
    with open(tmp, "w", encoding="utf-8") as f:
        for sid, text in sentences(pkg, cache):
            held = sid % HOLDOUT_EVERY == 0
            if (slice_ == "train" and held) or (slice_ == "holdout" and not held):
                continue
            for run in windows(text):
                f.write(" ".join(run) + "\n")
                n += 1
    os.replace(tmp, out)
    sys.stderr.write(f"  {n} runs\n")
    return out


def runs(path: str):
    with open(path, encoding="utf-8") as f:
        for line in f:
            yield line.split()


def count_ngrams(path: str, keep_bi: int, keep_tri: int, want_uni: bool = False):
    """Count bigrams and trigrams over a runs file, exactly. Returns `(uni, bi, tri, n_runs)`.

    A 1M-sentence package yields ~13M distinct trigrams, and a Python dict of those does not fit in
    an ordinary machine's memory. The obvious fix — drop the singletons whenever the table gets too
    big — is the wrong one here: **Leipzig sorts its sentence file alphabetically**, so a prune
    part-way through the corpus falls entirely on the letters not yet reached, and the table would
    systematically under-count n-grams starting late in the alphabet. That is a bias in exactly the
    thing being counted, and it would ship.

    So the file is read [SHARDS] times instead, each pass counting only the n-grams whose hash falls
    in that shard. Every count is exact and nothing is ever dropped mid-corpus. (`hash` is salted per
    process, which is fine — the shards only have to be consistent within one run.)

    Only the head of each shard is kept, which loses nothing that could reach a table of `keep_*`
    entries: the shards partition the keys, so an n-gram in the global top-N lies in exactly one
    shard and is in that shard's top-N too.
    """
    uni = Counter()
    bi_top, tri_top = {}, {}
    n = 0
    for shard in range(SHARDS):
        bi, tri = Counter(), Counter()
        for run in runs(path):
            if shard == 0:
                n += 1
            for i, w in enumerate(run):
                if shard == 0 and want_uni:
                    uni[w] += 1
                if i >= 1:
                    k = f"{run[i - 1]} {w}"
                    if hash(k) % SHARDS == shard:
                        bi[k] += 1
                if i >= 2:
                    k = f"{run[i - 2]} {run[i - 1]} {w}"
                    if hash(k) % SHARDS == shard:
                        tri[k] += 1
        bi_top.update(heapq.nlargest(keep_bi, bi.items(), key=lambda kv: kv[1]))
        tri_top.update(heapq.nlargest(keep_tri, tri.items(), key=lambda kv: kv[1]))
        del bi, tri
        sys.stderr.write(f"  shard {shard + 1}/{SHARDS} · "
                         f"{len(bi_top)} bi kept · {len(tri_top)} tri kept\n")
    return uni, bi_top, tri_top, n


def write_table(counts: dict, top: int, path: str, min_count: int = 3) -> tuple:
    """Write the top [top] n-grams as `<key>\\t<count>`, **sorted by key**. Returns `(bytes, entries,
    sha256)`.

    Which n-grams are kept is decided by count; the order they are written in is not. The app holds
    these tables as a flat blob it binary-searches, so a file that arrives in key order can be loaded
    by a linear scan, while the count-descending order the old bigram files used would make it sort a
    quarter of a million keys on first use of every language. It costs the ability to `head` the file
    and see the commonest phrases; `sort -t$'\\t' -k2 -rn` gives that back.

    [min_count] drops anything seen fewer times than that. It changes nothing for a 1M-sentence corpus
    — English cuts at 11 occurrences and German at 7 long before the floor is reached — and exists for
    the languages Leipzig only has a small corpus for. Nynorsk has 300,000 sentences, where filling a
    100,000-entry table means writing down phrases seen twice; a table of coincidences is worse than a
    short table, because every row of it is a suggestion offered to somebody.
    """
    import hashlib
    counts = {k: c for k, c in counts.items() if c >= min_count}
    ranked = heapq.nlargest(top, counts.items(), key=lambda kv: kv[1])
    ranked.sort(key=lambda kv: kv[0])
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for key, c in ranked:
            f.write(f"{key}\t{c}\n")
    data = open(path, "rb").read()
    return len(data), len(ranked), hashlib.sha256(data).hexdigest()
