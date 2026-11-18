#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Compare darktable renders (PFM) of two runs, test by test.

    tools/render-compare.py <reference dir> <candidate dir> [--label NAME] [--out PREFIX]

For every <test>.pfm in the reference dir: image size, max and mean absolute
difference over all channels, PSNR (peak 1.0, values clipped to [0, 1] as an
8/16-bit export would) and the share of pixels whose largest channel
difference exceeds 1/255. Writes PREFIX.json and PREFIX.md; prints the table.
Exit status 1 if a candidate render is missing or has another size.
"""
import argparse
import json
import math
import pathlib
import sys

import numpy as np


def read_pfm(path):
    with open(path, "rb") as f:
        kind = f.readline().strip()
        if kind not in (b"PF", b"Pf"):
            raise ValueError(f"{path}: not a PFM file")
        dims = f.readline().split()
        while len(dims) < 2:
            dims += f.readline().split()
        w, h = int(dims[0]), int(dims[1])
        scale = float(f.readline().strip())
        ch = 3 if kind == b"PF" else 1
        dtype = "<f4" if scale < 0 else ">f4"
        data = np.frombuffer(f.read(w * h * ch * 4), dtype=dtype)
    return data.reshape(h, w, ch).astype(np.float64)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ref")
    ap.add_argument("cand")
    ap.add_argument("--label", default="")
    ap.add_argument("--out", default=None)
    a = ap.parse_args()
    ref, cand = pathlib.Path(a.ref), pathlib.Path(a.cand)
    rows, bad = [], 0
    for r in sorted(ref.glob("*.pfm")):
        c = cand / r.name
        row = {"test": r.stem, "label": a.label}
        if not c.exists():
            row["status"] = "missing"
            bad += 1
            rows.append(row)
            continue
        A, B = read_pfm(r), read_pfm(c)
        row["size_ref"] = list(A.shape)
        row["size_cand"] = list(B.shape)
        if A.shape != B.shape:
            row["status"] = "size differs"
            bad += 1
            rows.append(row)
            continue
        d = np.abs(A - B)
        Ac, Bc = np.clip(A, 0, 1), np.clip(B, 0, 1)
        mse = float(np.mean((Ac - Bc) ** 2))
        row.update(
            status="compared",
            max_abs=float(d.max()),
            mean_abs=float(d.mean()),
            psnr_db=(math.inf if mse == 0 else 10 * math.log10(1.0 / mse)),
            pct_pixels_over_1_255=float(100.0 * np.mean(d.max(axis=2) > 1 / 255)),
            nonfinite_cand=int(np.size(B) - np.count_nonzero(np.isfinite(B))),
        )
        rows.append(row)
    if not rows:
        print("no reference renders found", file=sys.stderr)
        return 1
    lines = [
        "| test | status | size (h x w) | max abs diff | mean abs diff | PSNR dB | px > 1/255 |",
        "|---|---|---|---|---|---|---|",
    ]
    for r in rows:
        if r["status"] != "compared":
            lines.append(f"| {r['test']} | {r['status']} | | | | | |")
            continue
        psnr = "identical" if math.isinf(r["psnr_db"]) else f"{r['psnr_db']:.1f}"
        lines.append(
            f"| {r['test']} | ok | {r['size_ref'][0]} x {r['size_ref'][1]} | {r['max_abs']:.2e} "
            f"| {r['mean_abs']:.2e} | {psnr} | {r['pct_pixels_over_1_255']:.3f} % |"
        )
    md = "\n".join(lines)
    print(md)
    if a.out:
        pathlib.Path(a.out + ".md").write_text(md + "\n")
        js = [{k: (None if isinstance(v, float) and math.isinf(v) else v) for k, v in r.items()} for r in rows]
        pathlib.Path(a.out + ".json").write_text(json.dumps(js, indent=1) + "\n")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
