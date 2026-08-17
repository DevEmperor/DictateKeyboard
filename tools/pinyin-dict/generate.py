#!/usr/bin/env python3
"""
Generate the Chinese Pinyin language pack (issue #262).

Until now the only Chinese input method in the app was Zhengma, a shape-based method decomposing each
character by its strokes, used by a small minority. Pinyin — phonetic, and what almost everyone actually
types with — was missing entirely.

No new engine is needed for it. `HanShapeBasedLanguageProvider` already answers a keystroke with a prefix
lookup against a `code / text / weight` table, and `LanguagePackComponent.hanShapeBasedTable` falls back to
the locale variant, so a subtype `zh-CN-pinyin` reads a table named `pinyin` on its own. What was missing
was the table. This builds it.

Two reachable sources are combined:

  * Readings — CC-CEDICT (MDBG), CC BY-SA 4.0. ~125k entries, each with the traditional form, the
    simplified form and a per-syllable pinyin with tone numbers. Authoritative, but a *dictionary*: it
    lists headwords, not the free combinations people type all day (这是, 我要, 两个, 是因为 ...).
  * Frequency — the OPUS OpenSubtitles zh_cn list, the same source the glide dictionaries use. Unusually
    for Chinese it is word-segmented rather than character-segmented, which is what makes it usable here.
    It supplies the ranking, and its vocabulary fills the dictionary's gaps.

Gap words get their reading **composed from per-character statistics learned from CC-CEDICT itself**:
every entry whose syllable count matches its character count votes on what each character reads as in
context. A character whose top reading holds at least 70% of its votes contributes only that reading;
the 273 genuine polyphones (多音字) contribute every reading above 15%. Measured against CC-CEDICT's own
multi-character entries with leave-one-out (the word's own votes removed before composing it), this
reproduces the true reading for 98.8% of words at 1.02 codes per word — better *and* cheaper than taking
the single most common reading everywhere, which scores 98.0% at 1.35 codes.

Usage:
    python3 generate.py [--top N] [--out DIR] [--report N]

    --top N      corpus words to consider for gap filling and ranking (default 120000)
    --out DIR    output directory (default: current dir)
    --report N   print the N highest-weighted rows for eyeballing (default 50)

Writes <out>/pinyin.sqlite3 and <out>/net.devemperor.dictate.pinyin.flex — the latter is what the app
downloads; see PinyinPackManager. Licensing is recorded in ATTRIBUTION.md.
"""
import sys, os, re, gzip, json, math, sqlite3, zipfile, argparse, urllib.request
from collections import Counter, defaultdict

CEDICT = "https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz"
OPUS = "https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/freq/zh_cn.freq.gz"

EXTENSION_ID = "net.devemperor.dictate.pinyin"
TABLE = "pinyin"

# CJK ideographs: the basic block plus extension A. Everything else in either source — Latin letters,
# digits, punctuation, the corpus's stray control characters — is not something a pinyin code can produce.
HAN = re.compile(r'^[㐀-䶿一-鿿]+$')

# A character contributes only its top reading when that reading holds at least this share of its votes;
# below it the character is a genuine polyphone and every reading above POLYPHONE_FLOOR is kept. Both
# numbers were chosen by measuring, see the module docstring.
DOMINANT_SHARE = 0.70
POLYPHONE_FLOOR = 0.15
POLYPHONE_MAX = 3

# Longest word we will compose a reading for. Beyond this the ambiguity compounds and nobody types the
# whole thing in one go anyway.
MAX_COMPOSE_LEN = 4

# CC-CEDICT carries proverbs and encyclopedic phrases — 一个萝卜一个坑, 中国残疾人联合会 — that are real
# Chinese but not things anybody types on a phone. Left in, they crowd out useful candidates: the lookup
# orders by code, so every long code starting with "zhongguo" lands between 中国 and whatever the user
# wanted next. An entry longer than this that not one speaker in a billion-token subtitle corpus ever used
# is dropped; anything the corpus attests is kept however long it is.
MAX_UNATTESTED_LEN = 4


def get(url: str) -> bytes:
    sys.stderr.write(f"  GET {url}\n")
    with urllib.request.urlopen(url) as r:
        return r.read()


def normalize_syllable(syllable: str) -> str:
    """A CC-CEDICT syllable ('hao3', 'lu:4', 'r5') as it is typed: lowercase, no tone, ü written v."""
    return re.sub(r'\d', '', syllable.lower()).replace('u:', 'v')


def load_cedict() -> list:
    """(traditional, simplified, [normalized syllables]) for every entry."""
    raw = gzip.decompress(get(CEDICT)).decode("utf-8")
    line_re = re.compile(r'^(\S+) (\S+) \[([^\]]*)\] /')
    entries = []
    for line in raw.splitlines():
        if line.startswith("#"):
            continue
        m = line_re.match(line)
        if not m:
            continue
        trad, simp, pinyin = m.groups()
        entries.append((trad, simp, [normalize_syllable(s) for s in pinyin.split()]))
    sys.stderr.write(f"  cedict: {len(entries)} entries\n")
    return entries


def load_corpus(top: int) -> dict:
    """word -> count from the OPUS zh_cn frequency list, restricted to Han-only tokens."""
    raw = gzip.decompress(get(OPUS)).decode("utf-8", "replace")
    counts = {}
    for line in raw.splitlines():
        parts = line.split()
        if len(parts) != 2:
            continue
        count, word = parts
        if HAN.match(word):
            counts[word] = int(count)
        if len(counts) >= top:
            break
    sys.stderr.write(f"  opus: {len(counts)} Han-only words\n")
    return counts


def learn_char_readings(entries: list) -> dict:
    """character -> Counter of readings, from entries where syllables align one-to-one with characters."""
    readings = defaultdict(Counter)
    aligned = 0
    for _trad, simp, syllables in entries:
        if len(simp) != len(syllables) or not HAN.match(simp):
            continue
        aligned += 1
        for char, syllable in zip(simp, syllables):
            readings[char][syllable] += 1
    sys.stderr.write(f"  learned readings for {len(readings)} characters from {aligned} aligned entries\n")
    return readings


def char_variants(readings: Counter) -> list:
    """The reading(s) a character contributes when composing a word — see DOMINANT_SHARE."""
    total = sum(readings.values())
    top, top_count = readings.most_common(1)[0]
    if top_count / total >= DOMINANT_SHARE:
        return [top]
    return [r for r, n in readings.most_common(POLYPHONE_MAX) if n / total >= POLYPHONE_FLOOR]


def compose(word: str, char_readings: dict) -> list:
    """Codes for a word CC-CEDICT does not list, from its characters. Empty if any character is unknown."""
    codes = [""]
    for char in word:
        counter = char_readings.get(char)
        if not counter:
            return []
        codes = [prefix + variant for prefix in codes for variant in char_variants(counter)]
    return codes


def build_rows(entries: list, corpus: dict, char_readings: dict) -> list:
    """(code, text, weight) rows for the pinyin table."""
    # Traditional forms leak into the "zh_cn" subtitle corpus in quantity (我們, 什麼, 這 ...). Their
    # counts belong to the simplified form we actually store, so they are folded in rather than dropped.
    trad_to_simp = {}
    for trad, simp, _syllables in entries:
        if trad != simp and HAN.match(trad) and HAN.match(simp):
            trad_to_simp.setdefault(trad, simp)
    folded = 0
    counts = Counter()
    for word, count in corpus.items():
        target = trad_to_simp.get(word, word)
        if target != word:
            folded += 1
        counts[target] += count
    sys.stderr.write(f"  folded {folded} traditional corpus forms into their simplified counterparts\n")

    # A single character is ranked by how often it is *read*, not by how often it stands alone: 的 is
    # everywhere, 儿 almost never on its own. Anything else would bury common characters.
    char_totals = Counter()
    for word, count in counts.items():
        for char in word:
            char_totals[char] += count

    # simplified -> {code: syllables}, so a code can later be weighed against the word's other readings
    dictionary = defaultdict(dict)
    for _trad, simp, syllables in entries:
        if not HAN.match(simp) or not syllables:
            continue
        code = "".join(syllables)
        if code.isascii() and code.isalpha():
            dictionary[simp][code] = syllables

    def reading_share(text: str, syllables: list) -> float:
        """
        How much of a word's usage this particular reading accounts for, 0..1.

        A common character keeps rare readings — 那 is also nuo2 (a surname), 的 also di4, 说 also shui4 —
        and CC-CEDICT lists them all. Without this they would each inherit the character's *full* corpus
        frequency, so typing "nuo" would answer with 那 above 诺 and 挪, which is not what anybody meant.
        The share comes from how often each reading is used across the dictionary's own entries.
        """
        if len(text) != len(syllables):
            return 1.0
        share = 1.0
        for char, syllable in zip(text, syllables):
            counter = char_readings.get(char)
            if not counter:
                continue
            total = sum(counter.values())
            share *= counter.get(syllable, 0) / total if total else 1.0
        return share

    def count_of(text: str) -> int:
        return char_totals[text] if len(text) == 1 else counts.get(text, 0)

    rows = []
    seen = set()
    dropped = 0

    def add(code: str, text: str, share: float = 1.0):
        nonlocal dropped
        if (code, text) in seen:
            return
        count = count_of(text)
        if not count and len(text) > MAX_UNATTESTED_LEN:
            dropped += 1
            return
        seen.add((code, text))
        # Zero-count entries sort after every attested one, shortest first, so a dictionary-only code
        # group still leads with its single characters instead of an arbitrary compound.
        weight = math.log10(count * share + 1) if count else -0.001 * len(text)
        rows.append((code, text, weight))

    for text, codes in dictionary.items():
        for code, syllables in codes.items():
            add(code, text, reading_share(text, syllables))
    dictionary_rows = len(rows)
    sys.stderr.write(f"  dropped {dropped} unattested entries longer than {MAX_UNATTESTED_LEN} characters\n")

    composed_words = 0
    for text in counts:
        if text in dictionary or len(text) > MAX_COMPOSE_LEN:
            continue
        codes = compose(text, char_readings)
        if not codes:
            continue
        composed_words += 1
        for code in codes:
            add(code, text)
    sys.stderr.write(
        f"  rows: {dictionary_rows} from the dictionary, {len(rows) - dictionary_rows} "
        f"composed for {composed_words} corpus words the dictionary does not list\n"
    )
    return rows


def write_database(path: str, rows: list):
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    # Same shape as the shipped han.sqlite3, because the same provider queries it. No index is created
    # here: LanguagePackExtension builds one when the pack loads, which keeps it out of the download.
    db.execute(f"CREATE TABLE {TABLE}(code VARCHAR(24), text TEXT, weight DOUBLE)")
    db.executemany(f"INSERT INTO {TABLE} VALUES (?, ?, ?)", rows)
    db.commit()
    db.execute("VACUUM")
    db.close()


def extension_manifest(version: str) -> dict:
    return {
        "$": "ime.extension.languagepack",
        "meta": {
            "id": EXTENSION_ID,
            "version": version,
            "title": "Chinese Pinyin",
            "description": (
                "拼音输入法词库 / Pinyin input for Simplified Chinese. Readings from CC-CEDICT "
                "(CC BY-SA 4.0), ranking from the OPUS OpenSubtitles frequency list."
            ),
            "maintainers": ["DevEmperor <accounts@devemperor.net>"],
            "homepage": "https://github.com/DevEmperor/DictateKeyboard",
            "license": "cc-by-sa-4.0",
        },
        "hanShapeBasedSQLite": "pinyin.sqlite3",
        "items": [
            {
                "id": "zh_CN_pinyin",
                "label": "中文 (中国) [拼音] / Chinese (China) [PINYIN]",
                "authors": ["DevEmperor"],
            }
        ],
    }


def write_flex(path: str, db_path: str, version: str):
    if os.path.exists(path):
        os.remove(path)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("extension.json", json.dumps(extension_manifest(version), ensure_ascii=False, indent=2))
        z.write(db_path, "pinyin.sqlite3")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--top", type=int, default=120000)
    ap.add_argument("--out", default=".")
    ap.add_argument("--report", type=int, default=50)
    ap.add_argument("--version", default="1.0.0")
    args = ap.parse_args()

    entries = load_cedict()
    corpus = load_corpus(args.top)
    char_readings = learn_char_readings(entries)
    rows = build_rows(entries, corpus, char_readings)

    os.makedirs(args.out, exist_ok=True)
    db_path = os.path.join(args.out, "pinyin.sqlite3")
    flex_path = os.path.join(args.out, f"{EXTENSION_ID}.flex")
    write_database(db_path, rows)
    write_flex(flex_path, db_path, args.version)

    # The report is the point at which a broken source gets caught, so print enough to actually judge:
    # a garbled corpus shows up immediately in the highest-weighted rows.
    sys.stderr.write(f"\n{len(rows)} rows, {len({t for _c, t, _w in rows})} distinct words\n")
    sys.stderr.write(f"{db_path}: {os.path.getsize(db_path) / 1e6:.1f} MB\n")
    sys.stderr.write(f"{flex_path}: {os.path.getsize(flex_path) / 1e6:.1f} MB\n\n")
    for code, text, weight in sorted(rows, key=lambda r: -r[2])[: args.report]:
        sys.stderr.write(f"  {code:<12} {text:<8} {weight:.2f}\n")


if __name__ == "__main__":
    main()
