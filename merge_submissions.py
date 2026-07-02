#!/usr/bin/env python3
"""Fold merged community submissions into library.json, stamping the PR author (issue #105).

Invoked by the `merge-submissions` workflow after a pull request that touched `submissions/` is
merged. For each submission file handed in on the command line:

  * validate it against `schema.json` (a bad file is reported and skipped, never folded in),
  * stamp every prompt's `author` with the merged pull request's GitHub handle — this is the
    authentic, unspoofable attribution GitHub records for whoever opened the PR,
  * append its prompts to `library.json`, skipping any `id` that already exists,
  * delete the now-consolidated submission file.

The maintainer stays in full control: this only runs *after* they merge the PR. Run locally with
`python3 merge_submissions.py <author> submissions/foo.json ...` if you ever need to fold by hand.
"""
import json
import os
import sys

try:
    from jsonschema import Draft7Validator
except ImportError:
    sys.exit("jsonschema is required: pip install jsonschema")


def load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def dump(path, obj):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(obj, fh, ensure_ascii=False, indent=2)
        fh.write("\n")


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: merge_submissions.py <author> [submission.json ...]")
    author = sys.argv[1]
    files = [f for f in sys.argv[2:] if os.path.isfile(f)]
    if not files:
        print("No submission files to process.")
        return

    validator = Draft7Validator(load("schema.json"))
    library = load("library.json")
    existing = {p["id"] for p in library.get("prompts", [])}

    added, skipped = [], []
    for path in files:
        doc = load(path)
        errors = sorted(validator.iter_errors(doc), key=lambda e: list(e.path))
        if errors:
            print(f"::error file={path}::invalid submission, skipping: {errors[0].message}")
            continue
        for entry in doc.get("prompts", []):
            pid = entry.get("id", "")
            if pid in existing:
                skipped.append(pid)
                continue
            # Authentic attribution: always the merged PR's author, regardless of what the
            # submission file claimed.
            entry["author"] = author
            library["prompts"].append(entry)
            existing.add(pid)
            added.append(pid)
        os.remove(path)

    dump("library.json", library)
    print(f"Folded {len(added)} prompt(s) by @{author}: {added}")
    if skipped:
        print(f"Skipped {len(skipped)} duplicate id(s): {skipped}")


if __name__ == "__main__":
    main()
