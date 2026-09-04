#!/usr/bin/env python3
"""Check the OpenRouter preset's model ids against OpenRouter's live catalog (issue #321).

A Gradle compile check never looks at a model id, so a preset pointing at a model that was retired
last month compiles perfectly and only fails in a user's hand. This asks OpenRouter — no API key
needed — and reports which of the ids in `ProviderRegistry.OPENROUTER` are gone, which no longer have
a provider serving them, and which sit in the wrong list (a chat id that only transcribes, or a
transcription id that only chats).

**Ask for `output_modalities=all`.** The bare `/api/v1/models` filters to `output_modalities=text`,
which hides every dedicated speech-to-text model. Checking against that URL is what made #321 report a
catalog that had lost all of them; every one was there the whole time.

Usage:  python3 tools/check-openrouter-models.py [--no-endpoints]
Exit code 1 if anything is wrong.
"""

import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

REGISTRY = (
    Path(__file__).resolve().parent.parent
    / "lib/dictate-core/src/main/kotlin/dev/patrickgold/florisboard/dictate/provider/ProviderRegistry.kt"
)
CATALOG_URL = "https://openrouter.ai/api/v1/models?output_modalities=all"
ENDPOINTS_URL = "https://openrouter.ai/api/v1/models/{id}/endpoints"


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def preset_block(source, name):
    """The text of `val <name> = ProviderPreset( … )`, cut at its matching parenthesis."""
    start = source.index(f"val {name} = ProviderPreset(")
    depth = 0
    for i in range(source.index("(", start), len(source)):
        if source[i] == "(":
            depth += 1
        elif source[i] == ")":
            depth -= 1
            if depth == 0:
                return source[start : i + 1]
    raise SystemExit(f"unbalanced parentheses in {name}")


def single(block, field):
    match = re.search(rf'{field}\s*=\s*"([^"]+)"', block)
    return match.group(1) if match else None


def listed(block, field):
    match = re.search(rf"{field}\s*=\s*listOf\((.*?)\)", block, re.S)
    return re.findall(r'"([^"]+)"', match.group(1)) if match else []


def main():
    check_endpoints = "--no-endpoints" not in sys.argv
    source = REGISTRY.read_text(encoding="utf-8")
    block = preset_block(source, "OPENROUTER")

    wanted = {}  # id -> "chat" | "transcription"
    for model in [single(block, "defaultChatModel")] + listed(block, "curatedChatModels"):
        if model:
            wanted[model] = "chat"
    for model in [single(block, "defaultTranscriptionModel")] + listed(block, "curatedTranscriptionModels"):
        if model:
            wanted[model] = "transcription"
    if not wanted:
        raise SystemExit("no model ids found in the OPENROUTER preset — did the field names change?")

    try:
        catalog = {m["id"]: m for m in fetch(CATALOG_URL)["data"]}
    except urllib.error.URLError as error:
        raise SystemExit(f"could not reach OpenRouter: {error}")

    stt_ids = {
        mid
        for mid, m in catalog.items()
        if "transcription" in [o.lower() for o in (m.get("architecture") or {}).get("output_modalities") or []]
    }
    print(f"catalog: {len(catalog)} models, {len(stt_ids)} of them dedicated speech-to-text")
    print(f"preset:  {len(wanted)} ids referenced\n")

    problems = []
    for model_id, kind in sorted(wanted.items(), key=lambda kv: (kv[1], kv[0])):
        entry = catalog.get(model_id)
        if entry is None:
            problems.append(f"{model_id} ({kind}) is NOT in the catalog")
            print(f"  ✗ {model_id:42s} {kind:13s} missing")
            continue

        outputs = [o.lower() for o in (entry.get("architecture") or {}).get("output_modalities") or []]
        note = ",".join(outputs) or "?"
        if kind == "transcription" and "transcription" not in outputs:
            problems.append(f"{model_id} is curated as transcription but outputs {note}")
        if kind == "chat" and "text" not in outputs:
            problems.append(f"{model_id} is curated as chat but outputs {note}")

        providers = ""
        if check_endpoints:
            try:
                endpoints = fetch(ENDPOINTS_URL.format(id=model_id))["data"].get("endpoints") or []
            except urllib.error.URLError as error:
                endpoints = []
                problems.append(f"{model_id}: could not read endpoints ({error})")
            if not endpoints:
                problems.append(f"{model_id} is listed but no provider serves it")
            providers = ", ".join(e.get("provider_name", "?") for e in endpoints)

        print(f"  ✓ {model_id:42s} {kind:13s} out={note:13s} {providers}")

    if problems:
        print("\nPROBLEMS")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print("\nall preset ids exist, sit in the right list, and have a provider.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
