# SPDX-License-Identifier: GPL-3.0-or-later
"""Make the two XMPs of test T6 from a valid one.

usage: python3 -I make_guard_xmps.py <src.xmp> <outdir> <operation> <engine version>

newer.xmp:   the first history entry of <operation> gets the engine's
             version of that module + 1 (the XMP's own version may be older
             than the engine's, so adding 1 to it is not enough)
unknown.xmp: the first history entry of <operation> is renamed to an
             operation no darktable has ("s3notamodule")
Prints what was changed.
"""
import re
import sys


def entry_span(text, op):
    # exiv2 writes each history entry as one <rdf:li .../> element
    for m in re.finditer(r"<rdf:li\b[^>]*?/>", text, re.S):
        if f'darktable:operation="{op}"' in m.group(0):
            return m.start(), m.end()
    raise SystemExit(f"no history entry for {op}")


def main(src, outdir, op, engine_version):
    text = open(src, encoding="utf-8").read()
    s, e = entry_span(text, op)
    entry = text[s:e]

    v = int(re.search(r'darktable:modversion="(\d+)"', entry).group(1))
    n = int(engine_version) + 1
    newer = entry.replace(f'darktable:modversion="{v}"', f'darktable:modversion="{n}"')
    open(f"{outdir}/newer.xmp", "w", encoding="utf-8").write(text[:s] + newer + text[e:])
    print(f"newer.xmp: {op} modversion {v} -> {n} (engine has {engine_version})")

    unknown = entry.replace(f'darktable:operation="{op}"', 'darktable:operation="s3notamodule"')
    open(f"{outdir}/unknown.xmp", "w", encoding="utf-8").write(text[:s] + unknown + text[e:])
    print(f"unknown.xmp: {op} -> s3notamodule")


if __name__ == "__main__":
    main(*sys.argv[1:5])
