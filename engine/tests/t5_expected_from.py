# SPDX-License-Identifier: GPL-3.0-or-later
"""Print the measured differences of a T5 run (run_tests.sh summary.txt) as
lines for t5_expected.txt: image case region max mean, with the mean rounded
up to 4 decimals. A helper for writing the list; the list itself is reviewed
by hand and carries the cause of each line.

usage: python3 -I t5_expected_from.py <summary.txt> [<summary.txt> ...]
       (several runs: the largest value of each line)
"""
import math
import re
import sys

LINE = re.compile(r"^INFO T5 (\S+) (\S+) region (\d): \d at \S+ max (\d+) mean ([0-9.]+)")


def main(paths):
    worst = {}
    for p in paths:
        for line in open(p, encoding="utf-8"):
            m = LINE.match(line)
            if not m:
                continue
            img, case, reg, mx, mean = m.group(1), m.group(2), m.group(3), int(m.group(4)), float(m.group(5))
            if mx == 0:
                continue
            k = (img, case, reg)
            a, b = worst.get(k, (0, 0.0))
            worst[k] = (max(a, mx), max(b, mean))
    for (img, case, reg), (mx, mean) in sorted(worst.items()):
        print(f"{img} {case} {reg} {mx} {math.ceil(mean * 1e4 + 1) / 1e4:.4f}")


if __name__ == "__main__":
    main(sys.argv[1:])
