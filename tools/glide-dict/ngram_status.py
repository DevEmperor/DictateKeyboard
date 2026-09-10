#!/usr/bin/env python3
"""
How far along the context-table build is (issue #334).

    python3 ngram_status.py            # once
    python3 ngram_status.py --watch    # refreshing every 10 seconds

Reads the files in dist/ rather than talking to the running build, so it works from any terminal, says
something sensible when nothing is running, and cannot get the build stuck.
"""
import os, sys, time, subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
DIST = os.path.join(HERE, "dist")
sys.path.insert(0, HERE)
from generate_ngrams_all import PACKAGES, outputs  # noqa: E402

# The last line each per-language log writes for a phase, mapped to something readable.
PHASES = (("wrote", "writing"), ("shard", "counting"), ("tokenising", "tokenising"), ("GET", "downloading"))


def phase_of(lang):
    """What a language currently in flight is doing, from the tail of its own log."""
    path = os.path.join(DIST, f"{lang}.log")
    if not os.path.isfile(path):
        return "starting"
    try:
        tail = open(path, encoding="utf-8", errors="replace").read()[-4000:]
    except OSError:
        return "?"
    for line in reversed(tail.splitlines()):
        for needle, label in PHASES:
            if needle in line:
                if label == "counting" and "shard" in line:
                    return f"counting ({line.strip().split('·')[0].strip()})"
                return label
    return "starting"


def running():
    """Languages with a generator process alive right now."""
    try:
        out = subprocess.run(["ps", "-eo", "args"], capture_output=True, text=True).stdout
    except OSError:
        return []
    live = []
    for line in out.splitlines():
        if "generate_ngrams.py" in line and "--pkg" in line:
            parts = line.split()
            # The command line carries the script's full path, so match on the tail rather than on
            # the bare name.
            i = next((n for n, p in enumerate(parts) if p.endswith("generate_ngrams.py")), None)
            if i is not None and i + 1 < len(parts):
                live.append(parts[i + 1])
    return live


def report():
    done, missing = [], []
    total_bytes = 0
    for lang in sorted(PACKAGES):
        big, tri = outputs(lang)
        if os.path.isfile(big) and os.path.isfile(tri):
            done.append(lang)
            total_bytes += os.path.getsize(big) + os.path.getsize(tri)
        else:
            missing.append(lang)

    live = running()
    n, total = len(done), len(PACKAGES)
    bar = "#" * round(30 * n / total) + "." * (30 - round(30 * n / total))
    print(f"context tables  [{bar}]  {n}/{total} languages, {total_bytes / 1e6:.0f} MB")
    print()
    if live:
        for lang in sorted(live):
            print(f"  building  {lang:<4} {phase_of(lang)}")
    elif missing:
        print("  nothing running — resume with: python3 generate_ngrams_all.py")
    else:
        print("  all done")
    if missing:
        waiting = [l for l in missing if l not in live]
        if waiting:
            print(f"\n  waiting   {' '.join(waiting)}")
    failed = [l for l in missing if os.path.isfile(os.path.join(DIST, f"{l}.log")) and l not in live]
    if failed and not live:
        print(f"\n  check the logs of: {' '.join(failed)}  (dist/<lang>.log)")


def main():
    if "--watch" not in sys.argv:
        report()
        return
    try:
        while True:
            print("\033[2J\033[H", end="")
            report()
            time.sleep(10)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
