#!/usr/bin/env python3
"""
Generate a glide-typing dictionary (<lang>.json) with correct casing/diacritics.

Glide typing (issue #127) matches a swipe path against a word list; each word carries a frequency in
[128,255] (log-scaled) used to rank candidates. The classifier matches case-insensitively but commits the
stored form, so the dictionary must carry the correct case (German nouns capitalised, e.g. "Baum").

Two reachable sources are combined:
  * Frequency — OPUS OpenSubtitles frequency lists (https://opus.nlpl.eu, hosted on CSC object storage).
    Case-insensitive there (fully lowercased), which is fine for *ranking*.
  * Casing and spelling — the Hunspell dictionary for the language, consulted through the local
    `hunspell` binary as an oracle: a lowercase word that is only valid when capitalised (a noun) is
    capitalised; words valid lowercase (function words, verbs) stay lowercase; words rejected in both
    cases are dropped as corpus noise. Two hosts are supported, wooorm/dictionaries (--dict) and
    LibreOffice/dictionaries (--lo-dict), because neither covers every language.

    The filter is not cosmetic for languages that write variant letter forms. Arabic subtitle text is
    full of ان / الى / اخى where the correct spellings are أن / إلى / أخي; Hunspell rejects exactly
    those, which is what leaves them *outside* the dictionary and therefore correctable at runtime.

Requires the `hunspell` binary on PATH (sudo pacman -S hunspell / apt install hunspell). Without a Hunspell
dictionary for the language the result is left lowercase (a warning is printed).

Usage:
    python3 generate.py <lang> [--opus LANG] [--dict NAME | --lo-dict PATH] [--top N] [--out DIR]

    <lang>       output language code → writes <out>/<lang>.json (also the OPUS/dict default)
    --opus L     OPUS language code (default: <lang>), e.g. de, en, fr, pt
    --dict N     wooorm dictionary dir (default: <lang>), e.g. de, en-US, pt-BR
    --lo-dict P  LibreOffice dictionary path "<dir>/<basename>", e.g. ar/ar, hi_IN/hi_IN, id/id_ID
    --top N      keep the top N words by frequency (default 50000)
    --out DIR    output directory (default: current dir)

Licensing: OPUS OpenSubtitles data is freely redistributable; Hunspell dictionaries are used here only as a
build-time casing oracle (their word lists are not redistributed — only OPUS-derived frequencies with
restored case). Verify the per-language Hunspell licence before adding a language; ATTRIBUTION.md records
which licence each one was taken under.
"""
import sys, os, io, json, math, gzip, tarfile, hashlib, argparse, subprocess, tempfile, unicodedata, urllib.request

from wordfilter import drop_foreign_scripts, is_word, strip_arabic_marks

OPUS = "https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/freq"
LEIPZIG = "https://downloads.wortschatz-leipzig.de/corpora"
WOOORM = "https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries"
LIBREOFFICE = "https://raw.githubusercontent.com/LibreOffice/dictionaries/master"

# The OPUS lists are frequency-sorted, so a word this far down can never merge its way into the top N.
# Bounding the scan keeps peak memory sane on the big lists (Arabic 2.9 M words, Finnish 2.65 M).
SCAN_LIMIT_FACTOR = 10


def get(url: str) -> bytes:
    sys.stderr.write(f"  GET {url}\n")
    with urllib.request.urlopen(url) as r:
        return r.read()


def load_leipzig_counts(pkg: str) -> dict:
    """word → count from a Leipzig Corpora Collection package's `*-words.txt` (id ⇥ word ⇥ count).

    OPUS OpenSubtitles is the better register for a phone keyboard (people type the way they speak),
    but it barely covers some languages: Tamil yields 18,926 word types against Leipzig's 100k+. Where
    that is the case, the two are merged by relative frequency — see merge_counts.
    """
    url = f"{LEIPZIG}/{pkg}.tar.gz"
    sys.stderr.write(f"  GET {url}\n")
    req = urllib.request.Request(url, headers={"User-Agent": "dictate-dict-gen"})
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
        name = next((m for m in tar.getnames() if m.endswith("-words.txt")), None)
        if name is None:
            raise SystemExit(f"error: -words.txt not found in {pkg}")
        out: dict[str, int] = {}
        for line in io.TextIOWrapper(tar.extractfile(name), encoding="utf-8", errors="replace"):
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3 or not parts[0].isdigit() or not parts[2].isdigit():
                continue
            word = strip_arabic_marks(parts[1].strip().lower())
            if not is_word(word):
                continue
            out[word] = out.get(word, 0) + int(parts[2])
        sys.stderr.write(f"  leipzig {pkg}: {len(out)} distinct words\n")
        return out
    finally:
        os.unlink(tmp.name)


def merge_counts(*sources: dict) -> dict:
    """Combine word→count maps from different corpora with equal weight.

    Raw counts are not comparable across corpora of different sizes, so each source is converted to a
    relative frequency first and the shares are added. The result is scaled back up because the caller
    takes a logarithm of it.
    """
    sources = tuple(s for s in sources if s)
    if len(sources) == 1:
        return dict(sources[0])
    combined: dict[str, float] = {}
    for src in sources:
        total = sum(src.values()) or 1
        for word, count in src.items():
            combined[word] = combined.get(word, 0.0) + count / total
    return {w: max(1, round(share * 1e9)) for w, share in combined.items()}


def repair_mojibake(word: str, recode: tuple) -> str:
    """Undo a text that was stored in one single-byte encoding and read as another.

    Some OPUS frequency lists are damaged at the source. Icelandic is the clear case: its list has
    `ađ`, `ūađ`, `viđ` where Icelandic writes `að`, `það`, `við` — the text was Latin-1 and was read
    as ISO-8859-4, turning þ→ū, ð→đ and ó→ķ. Because the damage is a single-byte substitution it is
    exactly invertible, and re-encoding restores the real words (verified against the top of the
    list: `ađ er í ekki ūađ` → `að er í ekki það`).

    This must not be applied blindly — it is configured per language, because a word that survives
    the round trip unchanged in one language may be a legitimate spelling in another.
    """
    try:
        return word.encode(recode[0]).decode(recode[1])
    except (UnicodeEncodeError, UnicodeDecodeError):
        # Not representable in the damaged encoding, so it was never damaged: leave it alone.
        return word


def load_opus_counts(opus_lang: str, top: int, recode: tuple = None) -> dict:
    """word → count from the OPUS OpenSubtitles frequency list, filtered to real words.

    Arabic marks are stripped *before* counting and the surviving forms have their counts added
    together, so مــن / مـن / من become one entry rather than three. Hunspell would not catch those:
    ayaspell happily accepts tatweel-stretched spellings.

    [recode] repairs a list that arrives mojibaked — see repair_mojibake.
    """
    raw = gzip.decompress(get(f"{OPUS}/{opus_lang}.freq.gz")).decode("utf-8", "replace")
    merged: dict[str, int] = {}
    scanned = 0
    scan_limit = max(top * SCAN_LIMIT_FACTOR, 500_000)
    for line in raw.splitlines():
        # Counts are right-aligned with leading spaces, so split on any whitespace run.
        parts = line.split(None, 1)
        if len(parts) != 2:
            continue
        cnt, word = parts
        if not cnt.isdigit():
            continue
        word = word.strip()
        if recode:
            word = repair_mojibake(word, recode)
        word = strip_arabic_marks(word.lower())
        if not is_word(word):
            continue
        merged[word] = merged.get(word, 0) + int(cnt)
        scanned += 1
        if scanned >= scan_limit:
            break
    sys.stderr.write(f"  opus {opus_lang}: {scanned} tokens scanned, {len(merged)} distinct\n")
    return merged


def rank(counts: dict, top: int) -> list:
    """Return [(word, count), ...], most frequent first, in this language's own script, capped at [top]."""
    ranked = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))
    kept = drop_foreign_scripts(ranked[: top * 2])[:top]
    sys.stderr.write(f"  frequencies: {len(counts)} distinct, keeping {len(kept)}\n")
    return kept


def fetch_hunspell(dict_name: str, lo_dict: str) -> tuple:
    """Return (dic_bytes, aff_bytes) for the requested Hunspell dictionary.

    wooorm names every dictionary index.dic/index.aff inside a per-language dir; LibreOffice uses the
    language code as the basename and does not follow one convention for the dir (ar/ar, hi_IN/hi_IN,
    id/id_ID), so that source takes the full "<dir>/<basename>" path.
    """
    if lo_dict:
        return get(f"{LIBREOFFICE}/{lo_dict}.dic"), get(f"{LIBREOFFICE}/{lo_dict}.aff")
    return get(f"{WOOORM}/{dict_name}/index.dic"), get(f"{WOOORM}/{dict_name}/index.aff")


def uses_title_case(words: list) -> bool:
    """Whether this language's script capitalises the first letter of a word at all.

    Most caseless scripts (Arabic, Hebrew, the Indic ones) have no uppercase mappings, so title-casing
    them is a harmless no-op. Georgian is the trap: Unicode *does* give Mkhedruli letters an uppercase
    form (Mtavruli), but Georgian has no capitalisation — Mtavruli is for setting a whole word in caps,
    never for one leading letter. Title-casing it produced 22,832 entries like `Ქირავდება`, a shape that
    does not occur in written Georgian.
    """
    sample = "".join(w for w, _ in words[:500])
    letters = [c for c in sample if c.isalpha()]
    if not letters:
        return True
    # A script whose letters have a distinct uppercase form, yet whose corpus never uses it in the
    # middle of running text, does not title-case. Georgian Mkhedruli is exactly that.
    scripts = {unicodedata.name(c, "").split(" LETTER ")[0] for c in letters}
    return not any("GEORGIAN" in s for s in scripts)


def build_case_oracle(words: list, dict_name: str, lo_dict: str = "") -> dict:
    """
    Map each lowercase word to its correct case via hunspell (word→cased). Words that hunspell rejects in
    *both* cases are omitted (they are OPUS subtitle noise: typos, foreign/Swiss spellings like "gross",
    names) — the caller drops any word not in the returned map. Falls back to keeping everything lowercase
    if no Hunspell dictionary is available.
    """
    if not uses_title_case(words):
        sys.stderr.write("  (script has no title case: keeping every word lowercase)\n")
        return {w: w for w, _ in words}
    source = lo_dict or dict_name
    try:
        dic, aff = fetch_hunspell(dict_name, lo_dict)
    except Exception as e:
        sys.stderr.write(f"  WARN no Hunspell dict '{source}' ({e}); keeping all words lowercase\n")
        return {w: w for w, _ in words}
    tmp = tempfile.mkdtemp()
    open(os.path.join(tmp, "d.dic"), "wb").write(dic)
    open(os.path.join(tmp, "d.aff"), "wb").write(aff)

    def rejected(cands: list) -> set:
        """
        Return the set of cands that hunspell considers misspelled. Uses `hunspell -l` (list only the
        unknown words, no correction suggestions) — orders of magnitude faster than `-a` on tens of
        thousands of words, and alignment-proof since we test set membership rather than line order.
        """
        proc = subprocess.run(
            ["hunspell", "-l", "-d", os.path.join(tmp, "d"), "-i", "utf-8"],
            input="\n".join(cands) + "\n", capture_output=True, text=True,
        )
        return set(proc.stdout.split("\n")) - {""}

    lowers = [w for w, _ in words]
    caps = [w[:1].upper() + w[1:] for w in lowers]
    bad_low = rejected(lowers)
    bad_cap = rejected(caps)
    oracle = {}
    for w, cap in zip(lowers, caps):
        if w not in bad_low:
            oracle[w] = w              # valid lowercase → keep lowercase (function words, verbs)
        elif cap not in bad_cap:
            oracle[w] = cap            # only valid capitalised → noun/proper, capitalise
        # else: rejected in both cases → drop (OPUS noise)
    sys.stderr.write(
        f"  hunspell '{source}': {len(oracle)} of {len(lowers)} words kept "
        f"({len(lowers) - len(oracle)} rejected as noise/misspellings)\n"
    )
    return oracle


def to_json(words: list, oracle: dict) -> dict:
    # Keep only words the oracle recognised (drops OPUS noise / foreign spellings).
    kept = [(oracle[w], c) for w, c in words if w in oracle]
    if not kept:
        raise SystemExit("no words survived the Hunspell filter")
    counts = [c for _, c in kept]
    lo, hi = math.log(min(counts)), math.log(max(counts))
    span = (hi - lo) or 1.0
    out = {}
    for form, c in kept:
        v = 128 + round(127 * (math.log(c) - lo) / span)
        out[form] = max(out.get(form, 0), max(128, min(255, v)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("lang")
    ap.add_argument("--opus", default=None)
    ap.add_argument("--leipzig", default="",
                    help="Leipzig Corpora package basename (e.g. tam_wikipedia_2021_1M) to merge into the "
                         "OPUS frequencies; use where OpenSubtitles barely covers the language")
    ap.add_argument("--no-opus", action="store_true",
                    help="use only --leipzig as the frequency source")
    ap.add_argument("--fix-opus-encoding", default="",
                    help="repair a mojibaked OPUS list, as 'STORED:READ' (e.g. iso8859_4:latin1 for "
                         "Icelandic, whose list has ađ/ūađ where Icelandic writes að/það)")
    ap.add_argument("--dict", default=None)
    ap.add_argument("--lo-dict", default="",
                    help="LibreOffice dictionary path '<dir>/<basename>' (e.g. ar/ar, hi_IN/hi_IN, id/id_ID); "
                         "takes precedence over --dict, for languages wooorm does not cover")
    ap.add_argument("--name", default="<DisplayName>")
    ap.add_argument("--top", type=int, default=50000)
    ap.add_argument("--out", default=".")
    ap.add_argument("--no-hunspell", action="store_true",
                    help="skip the Hunspell casing/filter (OPUS frequencies only, all lowercase) — use for "
                         "languages whose Hunspell dictionary has an incompatible licence (e.g. AGPL/CC-BY-SA) "
                         "or that have none at all")
    args = ap.parse_args()

    recode = tuple(args.fix_opus_encoding.split(":", 1)) if args.fix_opus_encoding else None
    if recode and len(recode) != 2:
        raise SystemExit("--fix-opus-encoding takes 'STORED:READ', e.g. iso8859_4:latin1")
    opus = {} if args.no_opus else load_opus_counts(args.opus or args.lang, args.top, recode)
    leipzig = load_leipzig_counts(args.leipzig) if args.leipzig else {}
    words = rank(merge_counts(opus, leipzig), args.top)
    if not words:
        raise SystemExit("no frequency data (wrong --opus code / --leipzig package?)")
    if args.no_hunspell:
        sys.stderr.write("  (Hunspell skipped: OPUS-only, lowercase)\n")
        oracle = {w: w for w, _ in words}
    else:
        oracle = build_case_oracle(words, args.dict or args.lang, args.lo_dict)
    data = to_json(words, oracle)

    os.makedirs(args.out, exist_ok=True)
    path = os.path.join(args.out, f"{args.lang}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    blob = open(path, "rb").read()
    sha = hashlib.sha256(blob).hexdigest()
    caps = sum(1 for w in data if w != w.lower())
    sys.stderr.write(
        f"  wrote {path}: {len(data)} words, {caps} capitalised, {len(blob)} bytes, sha256 {sha}\n"
        f"  sample {list(data.items())[:8]}\n"
    )
    print(f'GlideDict("{args.lang}", "{args.name}", "$REL/{args.lang}.json", {len(blob)}, "{sha}"),')


if __name__ == "__main__":
    main()
