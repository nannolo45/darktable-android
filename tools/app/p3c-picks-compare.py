#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Brief P3c deliverable 7: the values the app picked on the emulator against the Linux engine test's.

    python3 -I tools/app/p3c-picks-compare.py <p3c-picks.txt from the emulator> <dtb_pickref log from Linux>

Both hold lines "PICK <case> <module or global> <colour space> mean r g b min r g b max r g b" for the same photo
(the public test RAW without a sidecar), the same areas and the same modules (P3cScenarioTest p3,
engine/tests/dtb_pickref.c). Prints the largest differences, relative to max(1e-3, |Linux value|): the two engines
are the same darktable built with different compilers and libraries (as in G0, where the exports are compared by
PSNR), so equal within bounds, not bit for bit. Exit 0 when every mean is within 1e-3 and every min and max within
1e-2; 1 otherwise or when a case is missing.
"""
import sys


def read(path):
    out = {}
    for line in open(path, encoding="utf-8"):
        p = line.split()
        if len(p) != 16 or p[0] != "PICK":
            continue
        v = [float(x) for x in p[5:8] + p[9:12] + p[13:16]]
        out[(p[1], p[2])] = (p[3], v)
    return out


def main():
    app, lin = read(sys.argv[1]), read(sys.argv[2])
    ok = bool(lin) and set(app) == set(lin)
    worst_mean = worst_ext = 0.0
    for k in sorted(lin):
        if k not in app:
            print("missing on the emulator: %s %s" % k)
            ok = False
            continue
        (cs_a, a), (cs_l, l) = app[k], lin[k]
        if cs_a != cs_l:
            print("%s %s: colour space %s on the emulator, %s on Linux" % (k[0], k[1], cs_a, cs_l))
            ok = False
        d = [abs(x - y) / max(1e-3, abs(y)) for x, y in zip(a, l)]
        dm, de = max(d[0:3]), max(d[3:9])
        worst_mean, worst_ext = max(worst_mean, dm), max(worst_ext, de)
        print("%-6s %-11s %-4s mean %s / %s  rel. diff mean %.2e, min/max %.2e" % (k[0], k[1], cs_l,
              " ".join("%.5f" % x for x in a[0:3]), " ".join("%.5f" % x for x in l[0:3]), dm, de))
    ok = ok and worst_mean <= 1e-3 and worst_ext <= 1e-2
    print("p3c picks: %d cases, largest relative difference: mean %.2e (bound 1e-3), min/max %.2e (bound 1e-2): %s"
          % (len(lin), worst_mean, worst_ext, "ok" if ok else "bad"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
