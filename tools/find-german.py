#!/usr/bin/env python3
"""
Find German text where the repository is meant to be English.

The project is written in English — code, comments, documentation, everything an app user can see.
A handful of places are deliberately German, and this script knows which and why. Anything German
outside that list is a finding, printed with its file and line.

    python3 tools/find-german.py           # report findings, exit 1 if any
    python3 tools/find-german.py --all     # also list what was skipped, and why

Detection is by German function words ("der", "und", "nicht", …). Two or more on a line, or one
alongside an umlaut, counts. That is deliberately blunt: it catches prose and misses the occasional
single word, which is the right trade for a check meant to be run without thinking about it.
"""

import argparse
import io
import os
import re
import subprocess
import sys

# Directories that are never scanned: build output, dependencies, version control, and `private/`,
# which is not published and is German on purpose (it is addressed to a German authority).
SKIP_DIRS = {'.git', 'build', 'node_modules', '.gradle', '.idea', '.claude', '.wrangler', 'dist',
             'private', '.venv', '__pycache__'}

TEXT_EXT = {'.kt', '.java', '.ts', '.tsx', '.js', '.mjs', '.py', '.md', '.xml', '.json', '.jsonc',
            '.sql', '.yml', '.yaml', '.gradle', '.kts', '.sh', '.txt', '.html', '.css', '.pro',
            '.cfg', '.toml', '.properties'}

# Files worth reading that carry no extension at all. `.gitignore` had six German comment lines in it
# and went unnoticed for exactly this reason.
TEXT_NAMES = {'.gitignore', '.gitattributes', '.editorconfig', 'Dockerfile', 'NOTICE', 'Makefile'}

# Allowed to be German, each with the reason. A path matches if the pattern occurs anywhere in it.
ALLOWED = [
    (r'/res/values-[a-z]{2}(-r[A-Z]{2})?/',
     'translations — being in another language is the point'),
    (r'/assets/ime/media/emoji/',
     'emoji keywords, one data file per language'),
    (r'/assets/ime/dict/',
     'word and bigram data — corpus output, not prose'),
    (r'(data_attributions\.txt|ATTRIBUTION\.md|generate_bigrams\.py)$',
     'source attributions: institution names are quoted verbatim'),
    (r'^cloud/src/admin/',
     'operator dashboard: one reader, behind Access on a single address — see cloud/README.md'),
    (r'^cloud/src/notify/',
     'alert mails: one recipient, same reason'),
    (r'^cloud/src/(routes/(rtdn|redeem|transcriptions|wallet)|rules|throttle|meter|sweep|costs)\.ts$',
     'alert texts raised from these files; their comments are English'),
    (r'^app/src/test/.*TranscriptParagraphsTest',
     'German fixtures for the German abbreviation handling — the subject of the test'),
    (r'^app/src/main/kotlin/.*/nlp/latin/LatinLanguageProvider\.kt$',
     'three comments naming German example words (ueber → über); they are about that case'),
    (r'^tools/find-german\.py$',
     'this script, which has to contain the words it looks for'),
]

WORDS = (r'\b(der|die|das|und|nicht|wird|werden|wurde|sind|eine|einen|einem|einer|dass|für|über|'
         r'auch|schon|noch|keine|kein|mit|vom|beim|wenn|dann|damit|weil|aber|oder|nur|sich|nach|'
         r'zum|zur|dem|den|des|kann|muss|soll|haben|seine|ihre|jeder|jedes|alle|etwas|nichts|ohne|'
         r'durch|gegen|unter|zwischen|deshalb|außerdem|jetzt|hier|dort|dabei|darauf|welche|welcher|'
         r'ist|wie|von|bei|sein|vor)\b')
WORD_RE = re.compile(WORDS, re.I)
UMLAUT_RE = re.compile(r'[äöüÄÖÜß]')


def git_ignored(paths):
    """Paths git ignores. The real `cloud/wrangler.jsonc` lives only on the operator's machine and is
    German like the rest of the operator's material; it is not part of what gets published."""
    if not paths:
        return set()
    try:
        result = subprocess.run(['git', 'check-ignore', '--stdin'], input='\n'.join(paths),
                                capture_output=True, text=True)
    except OSError:
        return set()
    return {line.strip() for line in result.stdout.split('\n') if line.strip()}


def allowed_for(path):
    for pattern, reason in ALLOWED:
        if re.search(pattern, path):
            return reason
    return None


def german_lines(text):
    for number, line in enumerate(text.split('\n'), 1):
        if len(line) > 300:
            continue
        hits = len(WORD_RE.findall(line))
        if hits >= 2 or (hits >= 1 and UMLAUT_RE.search(line)):
            yield number, line.strip()[:120]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--all', action='store_true', help='also list the skipped files and why')
    args = parser.parse_args()

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    os.chdir(root)

    candidates = []
    for dirpath, dirnames, filenames in os.walk('.'):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in sorted(filenames):
            path = os.path.join(dirpath, name).replace('./', '', 1)
            if os.path.splitext(path)[1].lower() in TEXT_EXT or name in TEXT_NAMES:
                candidates.append(path)

    ignored = git_ignored(candidates)
    findings, skipped = [], {}
    for path in candidates:
        if path in ignored:
            continue
        try:
            text = io.open(path, encoding='utf-8', errors='replace').read()
        except OSError:
            continue
        lines = list(german_lines(text))
        if not lines:
            continue
        reason = allowed_for(path)
        if reason:
            skipped[path] = (len(lines), reason)
        else:
            findings.append((path, lines))

    if args.all and skipped:
        print('German on purpose:\n')
        for path, (count, reason) in sorted(skipped.items()):
            print('  %-58s %4d  %s' % (path, count, reason))
        print()

    if not findings:
        print('No German outside the allowed places (%d files skipped on purpose).' % len(skipped))
        return 0

    print('%d file(s) with German where English is expected:\n' % len(findings))
    for path, lines in findings:
        print('  %s — %d line(s)' % (path, len(lines)))
        for number, line in lines[:4]:
            print('      %5d  %s' % (number, line))
        if len(lines) > 4:
            print('      … %d more' % (len(lines) - 4))
        print()
    print('Either translate it, or add the path to ALLOWED with the reason it belongs there.')
    return 1


if __name__ == '__main__':
    sys.exit(main())
