#!/usr/bin/env python3
"""Validate the community prompt library and any pending submissions (issue #105).

Checks, for `library.json` and every `submissions/*.json`:
  * the file is valid JSON and matches `schema.json`,
  * every prompt `id` is a valid slug and unique *within* the file,
  * no submission reuses an `id` that already exists in `library.json`.

Exits non-zero on the first failure so CI blocks the pull request. Run locally with:
    python3 validate.py
"""
import glob
import json
import sys

try:
    from jsonschema import Draft7Validator
except ImportError:
    sys.exit("jsonschema is required: pip install jsonschema")

ROOT = "."


def load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def ids_of(doc):
    return [p.get("id", "") for p in doc.get("prompts", [])]


def main():
    errors = []
    schema = load(f"{ROOT}/schema.json")
    validator = Draft7Validator(schema)

    def check(path, doc):
        for err in sorted(validator.iter_errors(doc), key=lambda e: e.path):
            loc = "/".join(str(p) for p in err.path)
            errors.append(f"{path}: {loc}: {err.message}")
        ids = ids_of(doc)
        seen = set()
        for pid in ids:
            if pid in seen:
                errors.append(f"{path}: duplicate id within file: '{pid}'")
            seen.add(pid)

    library = load(f"{ROOT}/library.json")
    check("library.json", library)
    library_ids = set(ids_of(library))

    for path in sorted(glob.glob(f"{ROOT}/submissions/*.json")):
        doc = load(path)
        check(path, doc)
        for pid in ids_of(doc):
            if pid in library_ids:
                errors.append(f"{path}: id '{pid}' already exists in library.json")

    if errors:
        print("Prompt library validation FAILED:\n")
        print("\n".join(f"  - {e}" for e in errors))
        sys.exit(1)
    print(f"OK: library.json ({len(library_ids)} prompts) and all submissions are valid.")


if __name__ == "__main__":
    main()
