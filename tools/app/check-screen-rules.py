#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Fail when screen code uses literal colours or sizes, or draws icons itself.

CLAUDE.md rule 5 and PLAN.md 3.6: screens take colours, text sizes, spacing and
control sizes only from ui-theme's tokens, and icons only from ui-icons'
registry. This scans the Kotlin sources of the screen modules (by default
app/darkroom and app/app, main sources) and prints every violation with file,
line and rule. Exit status 1 if there is any.

    tools/app/check-screen-rules.py [source dir ...]
    tools/app/check-screen-rules.py --self-test

Comments and string contents are ignored, except that colour strings ("#rrggbb")
count as literal colours.
"""
import os
import re
import sys

RULES = [
    ("literal size", re.compile(r"\.(dp|sp|em)\b")),
    ("literal size", re.compile(r"\b(Dp|TextUnit)\s*\(")),
    ("literal colour", re.compile(r"\bColor\s*\(")),
    ("literal colour", re.compile(r"\bColor\.(?!Companion\b)[A-Z][A-Za-z]*")),
    ("literal colour", re.compile(r"\b0x[0-9A-Fa-f]{6,8}\b")),
    ("literal colour", re.compile(r"android\.graphics\.Color\b|\bColorUtils\b")),
    ("literal colour", re.compile(r"\bR\.color\.|colorResource\s*\(")),
    ("icon drawn outside ui-icons", re.compile(r"\bImageVector\b|\bIcons\.|material\.icons|materialIcon\b")),
    ("icon drawn outside ui-icons", re.compile(r"\bpainterResource\s*\(|\bvectorResource\s*\(|\bR\.drawable\.")),
    ("icon drawn outside ui-icons", re.compile(r"\bdrawPath\s*\(|\bPath\s*\(\s*\)|\bdrawArc\s*\(|\bdrawLine\s*\(|\bdrawCircle\s*\(")),
    ("Material component (brings its own colours and sizes)", re.compile(r"\bandroidx\.compose\.material3?\b")),
]

COLOUR_STRING = re.compile(r'"#[0-9A-Fa-f]{3,8}"')


def strip(line, state):
    """Remove comments and string contents; keep colour strings visible. state: in block comment."""
    out = []
    i = 0
    in_block = state[0]
    while i < len(line):
        if in_block:
            j = line.find("*/", i)
            if j < 0:
                return "".join(out), [True]
            i = j + 2
            in_block = False
            continue
        ch = line[i]
        if line.startswith("//", i):
            break
        if line.startswith("/*", i):
            in_block = True
            i += 2
            continue
        if ch == '"':
            j = i + 1
            while j < len(line) and line[j] != '"':
                j += 2 if line[j] == "\\" else 1
            literal = line[i:j + 1]
            out.append(literal if COLOUR_STRING.fullmatch(literal) else '""')
            i = j + 1
            continue
        out.append(ch)
        i += 1
    return "".join(out), [in_block]


def check_file(path):
    problems = []
    state = [False]
    with open(path, encoding="utf-8") as f:
        for n, raw in enumerate(f, 1):
            code, state = strip(raw.rstrip("\n"), state)
            if COLOUR_STRING.search(code):
                problems.append((path, n, "literal colour", raw.strip()))
            for name, rx in RULES:
                if rx.search(code):
                    problems.append((path, n, name, raw.strip()))
                    break
    return problems


def check(dirs):
    problems = []
    files = 0
    for d in dirs:
        for root, _, names in os.walk(d):
            for name in sorted(names):
                if name.endswith(".kt"):
                    files += 1
                    problems += check_file(os.path.join(root, name))
    return files, problems


def self_test(here):
    fx = os.path.join(here, "check-screen-rules-fixtures")
    _, bad = check([os.path.join(fx, "bad")])
    _, good = check([os.path.join(fx, "good")])
    expected = 0
    with open(os.path.join(fx, "bad", "Bad.kt"), encoding="utf-8") as f:
        expected = sum(1 for line in f if "// VIOLATION" in line)
    ok = len(bad) == expected and not good
    print(f"self-test: {len(bad)} violations found in bad/ (expected {expected}), {len(good)} in good/ (expected 0)")
    for p in bad + good:
        print("  %s:%d: %s: %s" % p)
    return 0 if ok else 1


def main(argv):
    here = os.path.dirname(os.path.abspath(__file__))
    if argv[1:2] == ["--self-test"]:
        return self_test(here)
    repo = os.path.dirname(os.path.dirname(here))
    dirs = argv[1:] or [os.path.join(repo, "app", m, "src", "main") for m in ("darkroom", "app")]
    files, problems = check(dirs)
    for p in problems:
        print("%s:%d: %s: %s" % p)
    print(f"check-screen-rules: {files} files, {len(problems)} violations")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
