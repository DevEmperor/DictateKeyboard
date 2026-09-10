#!/usr/bin/env python3
"""
Measure what a second word of context is worth to next-word prediction (issue #334).

The reporter asks the engine to condition on the last *two* words instead of one. That is two claims
in one, and only one of them is about the order of the model:

  1. after a common word ("the") one word of context cannot tell "in the" from "at the" — an order-3
     table fixes this;
  2. after a mid-frequency word the shipped table has no continuations at all — it is pruned to the
     top 60,000 pairs and holds only 8,090 distinct first words out of a 49,981-word dictionary, so
     *more rows* fix this, at a fraction of the size.

Measured on English, the two turn out to be complementary rather than alternatives: five times as
many bigram rows are worth exactly nothing after a function word (20.4 % → 20.4 % top-3), and the
trigram table barely moves the band where the bigram table is empty (6.8 % → 9.0 %). Neither
substitutes for the other, which is why the arms below sweep both orders at several sizes.

Which one carries the win is a pure data question: no decoder, no gate, no tap coordinates. That is
why it is measured here in Python rather than in the Kotlin harness — nothing in this file
reimplements anything that ships, so it cannot drift from the app the way the #242 simulation did.
Reading the corpus and counting live in ngramcount.py, shared with the generator, so the table that
is measured is counted exactly like the table that is written.

    python3 eval_ngrams.py --train eng_news_2024_1M [--test eng-com_web-public_2018_1M]

Both arguments are Leipzig package basenames (see downloads.wortschatz-leipzig.de).

The bigram arms are recounted from sentences, while the shipped file was built from Leipzig's
pre-computed `co_n.txt` — so "B60" is a *reconstruction* of today, not today itself. `--shipped`
prints how far the two key sets overlap, so it is on the record how comparable the arms are.
"""
import sys, os, argparse, heapq
from collections import Counter

from ngramcount import HOLDOUT_EVERY, count_ngrams, runs, runs_path

# How many candidates the strip can actually show (CandidatesRow renders three in classic mode), and
# therefore the k in top-k. top1 is printed alongside because it is the one that needs no reading.
TOP_K = 3

# The largest arm of each order swept below. Counting keeps this many per shard; see count_ngrams.
KEEP_BI = 300_000
KEEP_TRI = 250_000


# ── Arms ─────────────────────────────────────────────────────────────────────────────────────────

class Arm:
    """One candidate table, in the shape the app would hold it: prefix -> the top-k continuations.

    Built from the pruned top-N exactly as `generate_bigrams.py` writes it, so `bytes` is the real
    file size that would ship, not an estimate.
    """

    def __init__(self, name, counts, top_n, order):
        self.name = name
        self.order = order                      # 2 = conditioned on one word, 3 = on two
        kept = heapq.nlargest(top_n, counts.items(), key=lambda kv: kv[1])
        self.entries = len(kept)
        self.bytes = sum(len(f"{k}\t{c}\n".encode("utf-8")) for k, c in kept)
        best = {}
        for key, c in kept:
            prefix, _, last = key.rpartition(" ")
            best.setdefault(prefix, []).append((c, last))
        self.top = {p: [w for _, w in sorted(v, reverse=True)[:TOP_K]] for p, v in best.items()}

    def predict(self, w2, w1):
        """The strip this arm would show, given the two words before the cursor (`w2` may be None)."""
        if self.order == 3:
            return [] if w2 is None else self.top.get(f"{w2} {w1}", [])
        return self.top.get(w1, [])


class Backoff:
    """Trigram continuations first, bigram continuations filling the rest — what step 2 would ship."""

    def __init__(self, name, tri_arm, bi_arm):
        self.name = name
        self.bytes = tri_arm.bytes + bi_arm.bytes
        self.entries = tri_arm.entries + bi_arm.entries
        self.tri, self.bi = tri_arm, bi_arm

    def predict(self, w2, w1):
        out = list(self.tri.predict(w2, w1))
        for w in self.bi.predict(w2, w1):
            if len(out) >= TOP_K:
                break
            if w not in out:
                out.append(w)
        return out


# ── Evaluation ───────────────────────────────────────────────────────────────────────────────────

BANDS = ("function", "mid", "rare")


def band_of(word, rank):
    """Which frequency band the *previous* word falls in — the split that separates the reporter's
    complaint (context after a function word is uninformative) from the other hole (after a rarer
    word there is no context at all)."""
    r = rank.get(word)
    if r is None:
        return "rare"
    if r < 100:
        return "function"
    return "mid" if r < 5000 else "rare"


def evaluate(arms, pkg, cache, rank, slice_, witness=None):
    """Walk the test sentences and score every arm at every point the keyboard would predict.

    [witness] is the trigram arm. Every arm is scored a second time over the subset of points where
    that arm has something to say, because that subset is the whole question: a small gain spread
    over all points and a large gain confined to the points that actually have two-word evidence look
    identical in the overall column, and they argue for opposite decisions.
    """
    points = Counter()
    stats = {a.name: Counter() for a in arms}
    for run in runs(runs_path(pkg, cache, slice_)):
        for i in range(1, len(run)):
            w1 = run[i - 1]
            w2 = run[i - 2] if i >= 2 else None
            target = run[i]
            b = band_of(w1, rank)
            points["all"] += 1
            points[b] += 1
            on = bool(witness.predict(w2, w1)) if witness is not None else False
            if on:
                points["witness"] += 1
            for a in arms:
                got = a.predict(w2, w1)
                s = stats[a.name]
                if got:
                    s["covered"] += 1
                if got and got[0] == target:
                    s["top1"] += 1
                if target in got[:TOP_K]:
                    s["hit"] += 1
                    s[f"hit:{b}"] += 1
                    if on:
                        s["hit:witness"] += 1
    return points, stats


def report(title, arms, points, stats):
    total = points["all"] or 1
    print()
    print(f"=== {title} ===")
    print(f"{total:,} prediction points · " + " · ".join(
        f"{b} {points[b] * 100.0 / total:.1f}%" for b in BANDS))
    head = f"{'arm':<14}{'entries':>10}{'size':>9}{'coverage':>10}{'top1':>8}{'top3':>8}   "
    head += "".join(f"{b + ' top3':>15}" for b in BANDS)
    print(head)
    for a in arms:
        s = stats[a.name]
        row = (f"{a.name:<14}{a.entries:>10,}{a.bytes / 1e6:>8.2f}M"
               f"{s['covered'] * 100.0 / total:>9.1f}%"
               f"{s['top1'] * 100.0 / total:>7.1f}%"
               f"{s['hit'] * 100.0 / total:>7.1f}%   ")
        for b in BANDS:
            n = points[b] or 1
            row += f"{s[f'hit:{b}'] * 100.0 / n:>14.1f}%"
        print(row)
    w = points["witness"]
    if w:
        print(f"\nrestricted to the {w * 100.0 / total:.1f}% of points where the trigram table answers:")
        for a in arms:
            print(f"  {a.name:<14} top3 {stats[a.name]['hit:witness'] * 100.0 / w:>6.1f}%")


def shipped_overlap(bi_counts, path):
    """How much of the shipped table this reconstruction reproduces — the honesty check on the B60
    arm, which is built from sentences while the shipped file was built from Leipzig's own co_n."""
    if not os.path.isfile(path):
        return None
    shipped = set()
    with open(path, encoding="utf-8") as f:
        for line in f:
            key, tab, _ = line.partition("\t")
            if tab:
                shipped.add(key)
    mine = {k for k, _ in heapq.nlargest(len(shipped), bi_counts.items(), key=lambda kv: kv[1])}
    return len(shipped & mine), len(shipped)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--train", required=True, help="Leipzig package to count on, e.g. eng_news_2024_1M")
    ap.add_argument("--test", action="append", default=[],
                    help="extra package to evaluate on (register cross-check); repeatable")
    ap.add_argument("--cache", default=os.path.join(os.path.dirname(__file__), "dist", "corpora"))
    ap.add_argument("--shipped", help="path to the shipped <lang>_bigrams.txt, for the overlap check")
    a = ap.parse_args()

    sys.stderr.write(f"counting {a.train}\n")
    uni, bi, tri, n = count_ngrams(
        runs_path(a.train, a.cache, "train"), KEEP_BI, KEEP_TRI, want_uni=True)
    sys.stderr.write(f"  {n} training runs · {len(uni)} uni · {len(bi)} bi · {len(tri)} tri\n")

    rank = {w: i for i, (w, _) in enumerate(uni.most_common())}

    # Both orders are swept at several prune sizes, because the two questions the issue raises are
    # not the same question: more rows can only help where the table is *empty*, a second word of
    # context can only help where it is *crowded*. The per-band columns are what tells them apart.
    b = {n: Arm(f"B{n // 1000}", bi, n, 2) for n in (60_000, 150_000, 300_000)}
    t = {n: Arm(f"T{n // 1000}", tri, n, 3) for n in (50_000, 100_000, 250_000)}
    b[60_000].name = "B60 (today)"
    t250 = t[250_000]
    arms = [b[60_000], b[150_000], b[300_000], t[50_000], t[100_000], t250]
    for bn, tn in ((60_000, 50_000), (60_000, 100_000), (60_000, 250_000),
                   (150_000, 100_000), (300_000, 250_000)):
        arms.append(Backoff(f"B{bn // 1000}+T{tn // 1000}", t[tn], b[bn]))

    if a.shipped:
        ov = shipped_overlap(bi, a.shipped)
        if ov:
            print(f"reconstruction vs shipped table: {ov[0]:,} of {ov[1]:,} keys shared "
                  f"({ov[0] * 100.0 / ov[1]:.1f}%)")

    points, stats = evaluate(arms, a.train, a.cache, rank, "holdout", witness=t250)
    report(f"{a.train} — held-out (every {HOLDOUT_EVERY}th sentence)", arms, points, stats)

    for t in a.test:
        points, stats = evaluate(arms, t, a.cache, rank, "all", witness=t250)
        report(f"{t} — full package (register cross-check)", arms, points, stats)


if __name__ == "__main__":
    main()
