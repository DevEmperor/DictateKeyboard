# Chinese Pinyin language pack — sources & attribution

The pack (`net.devemperor.dictate.pinyin.flex`, hosted on the `pinyin-pack-v1` GitHub release) is generated
by `generate.py` from the two sources below. It contains a single table of `code / text / weight` rows —
a pinyin spelling, the characters it produces, and a ranking number — and no source text of any kind.

## Readings
- **CC-CEDICT** (<https://www.mdbg.net/chinese/dictionary?page=cc-cedict>), published by MDBG, licensed
  **CC BY-SA 4.0**. Referenced work: CEDICT, © 1997, 1998 Paul Andrew Denisowski.
  Used for the simplified headword and its per-syllable pinyin. The generated table is a derivative of
  CC-CEDICT and is therefore itself **CC BY-SA 4.0**; the release notes say so and this file is the
  attribution.

- Readings for words CC-CEDICT does not list are **composed from CC-CEDICT itself**, not from a third
  source: every entry whose syllable count matches its character count votes on what each character reads
  as in context. A character whose top reading holds ≥70% of its votes contributes only that reading; the
  273 genuine polyphones (多音字) contribute every reading above 15%. Measured against CC-CEDICT's own
  multi-character entries with leave-one-out — the word's own votes removed before composing it — this
  reproduces the true reading for **98.8%** of words at **1.02** codes per word. Taking simply the most
  common reading of each character scores 98.0% at 1.35 codes, so the threshold is both more accurate and
  cheaper.

## Frequencies
- **OPUS — OpenSubtitles** `zh_cn` frequency list (<https://opus.nlpl.eu>). P. Lison & J. Tiedemann,
  *OpenSubtitles2016: Extracting Large Parallel Corpora from Movie and TV Subtitles* (LREC 2016). Freely
  available for use; only aggregate word counts are read.

  Chinese is written without word boundaries, so a frequency list for it may well be split by character
  rather than by word, which would be useless for ranking pinyin *words*. This one is genuinely
  word-segmented — 我们, 什么, 知道 and 他们 all appear as single entries — which is what makes it usable
  here, and why the list was inspected before anything was built on it.

  Two things it needs correcting for, both handled in `generate.py`:
  - Traditional forms leak into the "zh_cn" corpus in quantity (我們, 什麼, 這 …). Their counts belong to
    the simplified form the table stores, so they are folded in via CC-CEDICT's traditional↔simplified
    pairs rather than discarded.
  - A single character is ranked by how often it is *read* — summed over every word containing it — not by
    how often it stands alone. Ranking by standalone frequency buries characters like 儿 that are common
    inside words and rare on their own.

## What is deliberately left out
- Entries with **no corpus attestation at all and more than four characters**: proverbs and encyclopedic
  phrases (一个萝卜一个坑, 中国残疾人联合会). They are real Chinese, but nobody types them on a phone, and
  because the lookup orders by code they would sit between 中国 and whatever the user actually wanted.
  Anything the corpus attests is kept, however long.
- **Traditional Chinese.** CC-CEDICT carries it, so a second table would be cheap, but Taiwan and Hong
  Kong overwhelmingly type Zhuyin (bopomofo) rather than pinyin, which is a different input method again.
