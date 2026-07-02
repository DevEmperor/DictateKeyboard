#!/usr/bin/env python3
"""Fold community submissions into library.json, stamping the contributor (issue #105).

Invoked by the `merge-submissions` workflow whenever the prompt-library branch advances. It
consolidates every file under `submissions/` — no matter whether it arrived through a merged pull
request (external contributors) or a direct commit (people with write access) — because attribution
is resolved per file from the commit that ADDED it, not from the push/merge event.

For each `submissions/*.json`:
  * find the commit that first added the file and look up that commit's GitHub login,
  * validate the file against `schema.json` (a bad file is reported and skipped, never folded in),
  * stamp every prompt's `author` with that login,
  * append its prompts to `library.json`, skipping any `id` that already exists,
  * delete the now-consolidated submission file.

The maintainer stays the gate: a submission only reaches the branch after they merge the PR (or make
the commit themselves). Set `AUTHOR_OVERRIDE` to bypass the git/API lookup when testing locally.

Usage: `python3 merge_submissions.py <owner/repo>`
"""
import glob
import json
import os
import subprocess
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


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True).stdout.strip()


def adding_commit(path):
    """SHA of the commit that first added `path` (diff-filter=A)."""
    return run(["git", "log", "--diff-filter=A", "--format=%H", "-1", "--", path])


def author_login(repo, path):
    """The contributor's GitHub handle for `path`. Prefers the commit's GitHub login (via the API),
    falling back to the git author name, then to 'community'. `AUTHOR_OVERRIDE` short-circuits both."""
    override = os.environ.get("AUTHOR_OVERRIDE")
    if override:
        return override
    sha = adding_commit(path)
    if sha:
        login = run(["gh", "api", f"repos/{repo}/commits/{sha}", "--jq", ".author.login"])
        if login and login != "null":
            return login
        name = run(["git", "log", "--format=%an", "-1", sha])
        if name:
            return name
    return "community"


def unique_id(base, by_id):
    """First of `base`, `base-2`, `base-3`, ... that is not already a key in `by_id`."""
    n = 2
    candidate = f"{base}-{n}"
    while candidate in by_id:
        n += 1
        candidate = f"{base}-{n}"
    return candidate


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: merge_submissions.py <owner/repo>")
    repo = sys.argv[1]
    files = sorted(glob.glob("submissions/*.json"))
    if not files:
        print("No submissions to consolidate.")
        return

    validator = Draft7Validator(load("schema.json"))
    library = load("library.json")
    by_id = {p["id"]: p for p in library.get("prompts", [])}

    added, skipped, renamed = [], [], []
    for path in files:
        doc = load(path)
        errors = sorted(validator.iter_errors(doc), key=lambda e: list(e.path))
        if errors:
            print(f"::error file={path}::invalid submission, skipping: {errors[0].message}")
            continue
        author = author_login(repo, path)
        for entry in doc.get("prompts", []):
            pid = entry.get("id", "")
            clash = by_id.get(pid)
            if clash is not None:
                # Ids are the permanent key and there is no update mechanism, so a collision is either a
                # true re-submission (identical prompt → skip) or a distinct prompt that happens to share
                # the slug (→ give it a unique -2/-3/... id so nothing is silently dropped).
                if clash.get("prompt", "").strip() == entry.get("prompt", "").strip():
                    skipped.append(pid)
                    continue
                pid = unique_id(pid, by_id)
                entry["id"] = pid
                renamed.append(pid)
            entry["author"] = author
            library["prompts"].append(entry)
            by_id[pid] = entry
            added.append(f"{pid} (@{author})")
        os.remove(path)

    dump("library.json", library)
    print(f"Folded {len(added)} prompt(s): {added}")
    if renamed:
        print(f"Re-ided {len(renamed)} colliding prompt(s): {renamed}")
    if skipped:
        print(f"Skipped {len(skipped)} identical re-submission(s): {skipped}")


if __name__ == "__main__":
    main()
