#!/usr/bin/env python3
"""
Generate a per-language bigram file (<lang>_bigrams.txt) for the autocorrect Tier 2 context model.

Autocorrect re-ranks a misspelling's corrections by how often a candidate follows the previous word
(bigram context). The runtime looks a pair up as lowercase "w1 w2" -> count, so this emits exactly that:

    <w1> <w2>\t<count>

sorted by count (desc), top N. Source is the Leipzig Corpora Collection (wortschatz-leipzig.de), whose
download packages are CC BY (commercial use with attribution — cite Goldhahn, Eckart & Quasthoff, LREC
2012, and "© Universität Leipzig / Sächsische Akademie der Wissenschaften / InfAI"). Each package's
`*-co_n.txt` holds the adjacent (neighbour) co-occurrences as word-id pairs; `*-words.txt` maps id -> word.

Usage:
    python3 generate_bigrams.py <lang> --pkg <leipzig_pkg> [--top N] [--out DIR]

    <lang>       output language code -> writes <out>/<lang>_bigrams.txt (e.g. de)
    --pkg NAME   Leipzig package basename, e.g. deu_news_2022_1M (see downloads.wortschatz-leipzig.de)
    --top N      keep the top N bigrams by count (default 60000)
    --out DIR    output directory (default: current dir)

Prints a catalog line "BigramDict(<lang>, <bytes>, <sha256>)" on success for pasting into the app catalog.
"""
import sys, os, re, io, json, tarfile, tempfile, hashlib, argparse, urllib.request

LEIPZIG = "https://downloads.wortschatz-leipzig.de/corpora"
# Script-agnostic "real word": letters (any script), optionally joined by ' ' - internally. No digits.
WORD_RE = re.compile(r"[^\W\d_]+(?:['’-][^\W\d_]+)*$", re.UNICODE)


def get(url: str) -> bytes:
    sys.stderr.write(f"  GET {url}\n")
    req = urllib.request.Request(url, headers={"User-Agent": "dictate-bigram-gen"})
    with urllib.request.urlopen(req) as r:
        return r.read()


def is_word(w: str) -> bool:
    return 1 <= len(w) <= 30 and WORD_RE.match(w) is not None


def build(lang: str, pkg: str, top: int, out_dir: str):
    # Stream the package to a temp file (some are hundreds of MB) instead of loading it into memory.
    url = f"{LEIPZIG}/{pkg}.tar.gz"
    sys.stderr.write(f"  GET {url}\n")
    req = urllib.request.Request(url, headers={"User-Agent": "dictate-bigram-gen"})
    tmp = tempfile.NamedTemporaryFile(suffix=".tar.gz", delete=False)
    try:
        with urllib.request.urlopen(req) as r:
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                tmp.write(chunk)
        tmp.close()
        tar = tarfile.open(tmp.name, mode="r:gz")
        _extract_and_write(tar, lang, pkg, top, out_dir)
    finally:
        os.unlink(tmp.name)


def _extract_and_write(tar, lang: str, pkg: str, top: int, out_dir: str):

    def member(suffix: str):
        name = next((m for m in tar.getnames() if m.endswith(suffix)), None)
        if name is None:
            sys.exit(f"error: {suffix} not found in {pkg}")
        return tar.extractfile(name)

    # id -> word (keep original case; we lowercase when emitting)
    id2word: dict[int, str] = {}
    for line in io.TextIOWrapper(member("-words.txt"), encoding="utf-8", errors="replace"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 2 and parts[0].isdigit():
            id2word[int(parts[0])] = parts[1]
    sys.stderr.write(f"  words: {len(id2word)}\n")

    # neighbour co-occurrences = adjacent bigrams: w1id w2id freq significance
    pairs: dict[tuple[str, str], int] = {}
    n_lines = 0
    for line in io.TextIOWrapper(member("-co_n.txt"), encoding="utf-8", errors="replace"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 3:
            continue
        try:
            w1 = id2word.get(int(parts[0])); w2 = id2word.get(int(parts[1])); cnt = int(parts[2])
        except ValueError:
            continue
        if not w1 or not w2 or not is_word(w1) or not is_word(w2):
            continue
        key = (w1.lower(), w2.lower())
        pairs[key] = pairs.get(key, 0) + cnt   # merge case variants
        n_lines += 1
    sys.stderr.write(f"  co_n lines used: {n_lines}, distinct lowercase bigrams: {len(pairs)}\n")

    ranked = sorted(pairs.items(), key=lambda kv: kv[1], reverse=True)[:top]
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, f"{lang}_bigrams.txt")
    with open(path, "w", encoding="utf-8") as f:
        for (w1, w2), cnt in ranked:
            f.write(f"{w1} {w2}\t{cnt}\n")

    data = open(path, "rb").read()
    sha = hashlib.sha256(data).hexdigest()
    sys.stderr.write(f"  wrote {path} ({len(data)} bytes, {len(ranked)} bigrams)\n")
    print(f'BigramDict("{lang}", "$REL/{lang}_bigrams.txt", {len(data)}, "{sha}"),')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("lang")
    ap.add_argument("--pkg", required=True)
    ap.add_argument("--top", type=int, default=60000)
    ap.add_argument("--out", default=".")
    a = ap.parse_args()
    build(a.lang, a.pkg, a.top, a.out)


if __name__ == "__main__":
    main()
