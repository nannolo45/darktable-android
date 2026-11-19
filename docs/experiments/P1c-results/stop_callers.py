# SPDX-License-Identifier: GPL-3.0-or-later
"""P1c item 4: where desktop darktable sets slider colour stops, and from which
callbacks. For every call of dt_bauhaus_slider_set_stop / _clear_stops in the
processing modules, the enclosing function and, up to three levels, the
functions calling it (by name, within the same file). A rough static reading
for the write-up; the extractor's dynamic check is the one the schema uses.

usage: python3 -I stop_callers.py <darktable source>
"""
import glob
import os
import re
import sys

FUNC = re.compile(r'^(?:static\s+)?(?:inline\s+)?[A-Za-z_][\w\s\*]*?\b([A-Za-z_]\w*)\s*\(')


def functions(lines):
    cur, out = None, []
    for l in lines:
        m = FUNC.match(l)
        if m and not l.startswith((' ', '\t', '#')) and not l.rstrip().endswith(';'):
            cur = m.group(1)
        out.append(cur)
    return out


def callers(lines, fns, name):
    pat = re.compile(r'\b' + re.escape(name) + r'\s*\(')
    return sorted({fns[i] for i, l in enumerate(lines)
                   if pat.search(l) and fns[i] and fns[i] != name and not FUNC.match(l)})


def chain(lines, fns, name, depth=0, seen=None):
    seen = seen or set()
    if depth > 3 or name in seen:
        return name
    seen.add(name)
    cs = callers(lines, fns, name)
    if not cs:
        return name
    return name + " <- {" + ", ".join(chain(lines, fns, c, depth + 1, set(seen)) for c in cs) + "}"


def main(src):
    files = sorted(glob.glob(os.path.join(src, "src/iop/*.c")) + glob.glob(os.path.join(src, "src/iop/*.cc")))
    for f in files:
        lines = open(f, encoding="utf-8", errors="replace").read().split("\n")
        fns = functions(lines)
        where = sorted({fns[i] for i, l in enumerate(lines)
                        if ("dt_bauhaus_slider_set_stop" in l or "dt_bauhaus_slider_clear_stops" in l) and fns[i]})
        for w in where:
            print(f"{os.path.relpath(f, src)}: {chain(lines, fns, w)}")


if __name__ == "__main__":
    main(sys.argv[1])
