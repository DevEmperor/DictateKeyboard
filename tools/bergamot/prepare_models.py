#!/usr/bin/env python3
# Copyright (C) 2026 DevEmperor (Dictate)
# Licensed under the Apache License, Version 2.0.
"""
Mirrors Mozilla's released translation models for the on-device translator (issue #424) and writes
the catalog the app downloads from.

For every language Mozilla has released in *both* directions (xx→en and en→xx — English is the pivot,
so a language missing either half cannot be translated to or from anything but English one way), this

  1. downloads the three .gz files of each direction from Mozilla's registry into build/models/,
  2. checks the model against the hash Mozilla publishes (the shortlist and vocab carry none),
  3. records the size and SHA-256 of each .gz exactly as it will be served, plus its unpacked size,
  4. writes TranslationModels.kt into lib/dictate-core, pointing at the GitHub release named below.

The .gz files are uploaded to that release unchanged; build/upload-list.txt names exactly the ones the
catalog uses (build/models/ also holds any language skipped as a duplicate):

    xargs -a tools/bergamot/build/upload-list.txt gh release upload translation-models-v1

Why a release of our own rather than Mozilla's bucket: every other download in the app (speech models,
keyboard language packs, trigram tables) is a release asset of this repository, pinned by hash in a
catalog like this one, and Mozilla's paths name a training run that a newer model replaces.

Usage:  tools/bergamot/prepare_models.py            (resumes: files already downloaded are kept)
"""

import gzip
import hashlib
import json
import sys
import urllib.request
from pathlib import Path

REGISTRY = "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/db/models.json"
RELEASE = "translation-models-v1"
# Firefox for Android ships "Release Android" where one exists (a smaller model than the desktop
# one), then "Release". Anything else — no status, Nightly, desktop-only — Mozilla has not shipped
# to phones, so neither do we.
STATUS_PREFERENCE = ["Release Android", "Release"]
# The Kotlin roles, and the registry key each comes from.
ROLES = {"model": "MODEL", "lexicalShortlist": "SHORTLIST", "vocab": "VOCAB", "srcVocab": "SOURCE_VOCAB", "trgVocab": "TARGET_VOCAB"}

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
CACHE = HERE / "build" / "models"
OUT = REPO / "lib/dictate-core/src/main/kotlin/dev/patrickgold/florisboard/dictate/translate/TranslationModels.kt"


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()


def pick(models: dict, direction: str):
    for status in STATUS_PREFERENCE:
        for entry in models.get(direction, []):
            if entry.get("releaseStatus") == status:
                return entry
    return None


def mirror(base_url: str, spec: dict) -> dict:
    """Downloads one file (unless cached) and returns what the catalog needs to know about it."""
    name = spec["path"].rsplit("/", 1)[1]
    target = CACHE / name
    if not target.exists():
        data = fetch(f"{base_url}/{spec['path']}")
        target.write_bytes(data)
    packed = target.read_bytes()
    unpacked = gzip.decompress(packed)
    expected = spec.get("uncompressedHash")
    if expected and hashlib.sha256(unpacked).hexdigest() != expected:
        target.unlink()
        sys.exit(f"hash mismatch for {name} — deleted, run again")
    return {
        "asset": name,
        "file": name.removesuffix(".gz"),
        "download": len(packed),
        "sha256": hashlib.sha256(packed).hexdigest(),
        "installed": len(unpacked),
    }


def main() -> None:
    CACHE.mkdir(parents=True, exist_ok=True)
    registry = json.loads(fetch(REGISTRY))
    models, base_url = registry["models"], registry["baseUrl"]
    codes = sorted({key.split("-", 1)[1] if key.startswith("en-") else key.split("-", 1)[0] for key in models})

    languages = []
    seen_models = {}
    for code in codes:
        if code == "en":
            continue
        entries = {d: pick(models, d) for d in (f"{code}-en", f"en-{code}")}
        if not all(entries.values()):
            print(f"skip {code}: not released in both directions", file=sys.stderr)
            continue
        directions = []
        for direction, entry in entries.items():
            files = []
            for key, spec in sorted(entry["files"].items()):
                info = mirror(base_url, spec)
                info["role"] = ROLES[key]
                files.append(info)
            directions.append({"id": direction, "architecture": entry["architecture"], "files": files})
            print(f"{direction}: {entry['architecture']} ({entry['releaseStatus']}), "
                  f"{sum(f['download'] for f in files) / 1e6:.1f} MB", file=sys.stderr)
        # Mozilla publishes some models under two codes — "no" is byte for byte the "nb" (Bokmål) model.
        # One entry is enough; TranslationCatalog.ALIASES sends the other code to it.
        models_key = tuple(f["sha256"] for d in directions for f in d["files"] if f["role"] == "MODEL")
        if models_key in seen_models:
            print(f"skip {code}: identical to {seen_models[models_key]} — keep it in TranslationCatalog.ALIASES",
                  file=sys.stderr)
            continue
        seen_models[models_key] = code
        languages.append((code, directions))

    lines = [
        "/*",
        " * Copyright (C) 2026 DevEmperor (Dictate)",
        " *",
        " * Licensed under the Apache License, Version 2.0 (the \"License\");",
        " * you may not use this file except in compliance with the License.",
        " * You may obtain a copy of the License at",
        " *",
        " *     http://www.apache.org/licenses/LICENSE-2.0",
        " */",
        "",
        "// GENERATED by tools/bergamot/prepare_models.py from Mozilla's model registry",
        f"// ({registry['generated']}). Do not edit by hand; rerun the script and upload what it mirrors.",
        "",
        "package dev.patrickgold.florisboard.dictate.translate",
        "",
        "import dev.patrickgold.florisboard.dictate.translate.TranslationModelFile.Role",
        "",
        f"internal const val TRANSLATION_MODELS_RELEASE = \"https://github.com/DevEmperor/DictateKeyboard/releases/download/{RELEASE}\"",
        "",
        "internal val TRANSLATION_LANGUAGES: List<TranslationLanguage> = listOf(",
    ]

    def direction_code(direction: dict) -> str:
        source, target = direction["id"].split("-", 1)
        out = [f"TranslationDirection(\"{source}\", \"{target}\", \"{direction['architecture']}\", listOf("]
        for f in direction["files"]:
            out.append(
                f"            TranslationModelFile(Role.{f['role']}, \"{f['file']}\", "
                f"\"$TRANSLATION_MODELS_RELEASE/{f['asset']}\", {f['download']}, {f['installed']}, \"{f['sha256']}\"),"
            )
        out.append("        ))")
        return "\n".join(out)

    for code, (to_english, from_english) in languages:
        lines.append(f"    TranslationLanguage(")
        lines.append(f"        \"{code}\",")
        lines.append(f"        {direction_code(to_english)},")
        lines.append(f"        {direction_code(from_english)},")
        lines.append("    ),")
    lines.append(")")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines) + "\n")
    assets = [CACHE / f["asset"] for _, ds in languages for d in ds for f in d["files"]]
    (HERE / "build" / "upload-list.txt").write_text("".join(f"{path}\n" for path in assets))

    total = sum(f["download"] for _, ds in languages for d in ds for f in d["files"])
    count = sum(len(d["files"]) for _, ds in languages for d in ds)
    print(f"{len(languages)} languages, {count} files, {total / 1e9:.2f} GB to upload → {OUT.relative_to(REPO)}", file=sys.stderr)


if __name__ == "__main__":
    main()
