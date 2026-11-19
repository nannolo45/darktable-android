# SPDX-License-Identifier: GPL-3.0-or-later
"""Summarise engine/tests/cost.sh: per image, series and before/after, the
median of each step over all rounds (ms).

usage: python3 -I cost_summary.py <output dir> cr2 raf arw
"""
import collections
import glob
import re
import statistics
import sys

STEP = re.compile(r"DTB_STEP (\S+) (\d+) (\d+)")
PERF = re.compile(r"\[dev_pixelpipe\] took ([0-9.]+) secs .* processed `([^']+)'")
CS = re.compile(r"\[dt_ioppr_transform_image_colorspace\] .* took ([0-9.]+) secs .*\[([^\]]+)\]")


def collect(out, img, mode):
    d = collections.defaultdict(lambda: collections.defaultdict(list))
    for path in sorted(glob.glob(f"{out}/{img}-{mode}-*.err")):
        key = None
        for line in open(path, errors="replace"):
            m = STEP.match(line)
            if m:
                key = (m.group(1), int(m.group(2)))
                continue
            if key and line.startswith("DTB_TIMING "):
                _, what, ms = line.split()
                d[key][what].append(float(ms))
    # darktable's per-module times are on stdout; one record per step
    for path in sorted(glob.glob(f"{out}/{img}-{mode}-*.out")):
        key, acc = None, collections.Counter()
        def flush():
            if key:
                for k, v in acc.items():
                    d[key][k].append(v)
        for line in open(path, errors="replace"):
            m = STEP.match(line)
            if m:
                flush()
                key, acc = (m.group(1), int(m.group(2))), collections.Counter()
                continue
            if key:
                p = PERF.search(line)
                if p:
                    acc["module " + p.group(2)] += float(p.group(1)) * 1e3
                c = CS.search(line)
                if c:
                    acc["colorspace for " + c.group(2)] += float(c.group(1)) * 1e3
        flush()
    return d


def main(out, imgs):
    print("Medians in ms over every step of every round (x86_64; see the log for the machine).")
    for img in imgs:
        for size_series in [("late_drag", 320), ("late_sealed", 320), ("early_drag", 320), ("unchanged", 320),
                            ("late_drag", 1024), ("unchanged", 1024)]:
            b = collect(out, img, "before").get(size_series, {})
            a = collect(out, img, "after").get(size_series, {})
            if not b and not a:
                continue
            print(f"\n### {img}: {size_series[0]}, preview {size_series[1]} px\n")
            print("| step | before | after |\n|---|---:|---:|")
            keys = sorted(set(b) | set(a), key=lambda k: (not k.startswith("render.preview_total"),
                                                           not k.startswith("render"), k))
            for k in keys:
                fb = f"{statistics.median(b[k]):.2f}" if k in b else "-"
                fa = f"{statistics.median(a[k]):.2f}" if k in a else "-"
                print(f"| {k} | {fb} | {fa} |")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
