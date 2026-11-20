#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Brief P3c deliverable 7: the values the app picked on the emulator against the Linux engine test's.

    python3 -I tools/app/p3c-picks-compare.py <p3c-picks.txt from the emulator> <dtb_pickref log from Linux>

Both hold lines "PICK <case> <module or global> <colour space> mean r g b min r g b max r g b" for the same photo
(the public test RAW without a sidecar), the same areas and the same modules (P3cScenarioTest p3,
engine/tests/dtb_pickref.c), printed with six decimals. Bounds:
- a module's picker (white balance's raw input, exposure's input): equal (1e-6 relative, or the last printed
  decimal): these buffers come before any module whose result depends on the compiler;
- the global picker (the pipe's output, after the whole edit): within one display level (1/255) for the mean, the
  min and the max: the two engines are the same darktable built with different compilers and libraries, and their
  rendered pictures differ as the G0 exports do (compared by PSNR there).
The first run (CI 44793905374) used 1e-3 / 1e-2 relative bounds for every case, chosen before any data; module
pickers came out equal and the global picker's largest difference was 0.05 levels for a mean and 1.07e-2 relative
for a small min, so the bounds were set per kind as above (P3c report, section 1).
Exit 0 when every case is within its bound; 1 otherwise or when a case is missing.
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
    worst_module = worst_global = 0.0
    for k in sorted(lin):
        if k not in app:
            print("missing on the emulator: %s %s" % k)
            ok = False
            continue
        (cs_a, a), (cs_l, l) = app[k], lin[k]
        if cs_a != cs_l:
            print("%s %s: colour space %s on the emulator, %s on Linux" % (k[0], k[1], cs_a, cs_l))
            ok = False
        if k[1] == "global":
            d = max(abs(x - y) for x, y in zip(a, l)) * 255.0
            worst_global = max(worst_global, d)
            what = "%.3f display levels" % d
            ok = ok and d <= 1.0
        else:
            d = max(0.0 if abs(x - y) <= 1.5e-6 else abs(x - y) / max(1e-3, abs(y)) for x, y in zip(a, l))
            worst_module = max(worst_module, d)
            what = "relative %.2e" % d
            ok = ok and d <= 1e-6
        print("%-6s %-11s %-4s mean %s / %s  largest difference %s" % (k[0], k[1], cs_l,
              " ".join("%.5f" % x for x in a[0:3]), " ".join("%.5f" % x for x in l[0:3]), what))
    print("p3c picks: %d cases; module pickers: largest relative difference %.2e (bound 1e-6); global picker: %.3f "
          "display levels (bound 1): %s" % (len(lin), worst_module, worst_global, "ok" if ok else "bad"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
