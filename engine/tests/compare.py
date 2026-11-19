# SPDX-License-Identifier: GPL-3.0-or-later
"""Compare two 8-bit images (PNG or PPM) channel by channel.

usage: python3 -I compare.py A B [--crop x y w h] [--tolerance N] [--mean-tolerance M]

--crop takes the region from B (B is the larger image).
Prints max and mean absolute difference, PSNR and the share of differing
values; exits 0 if max <= tolerance (default 1) and, when given, mean <=
mean tolerance; else 1.
"""
import math
import sys

import numpy as np
from PIL import Image


def load(path):
    return np.asarray(Image.open(path).convert("RGB")).astype(np.int32)


def main(argv):
    args = argv[1:]
    tol = 1
    mean_tol = None
    crop = None
    if "--mean-tolerance" in args:
        i = args.index("--mean-tolerance")
        mean_tol = float(args[i + 1])
        del args[i:i + 2]
    if "--tolerance" in args:
        i = args.index("--tolerance")
        tol = int(args[i + 1])
        del args[i:i + 2]
    if "--crop" in args:
        i = args.index("--crop")
        crop = [int(v) for v in args[i + 1:i + 5]]
        del args[i:i + 5]
    a, b = load(args[0]), load(args[1])
    if crop:
        x, y, w, h = crop
        b = b[y:y + h, x:x + w]
    if a.shape != b.shape:
        print(f"SHAPE_MISMATCH {a.shape} {b.shape}")
        return 1
    d = np.abs(a - b)
    mx = int(d.max())
    mean = float(d.mean())
    mse = float((d.astype(np.float64) ** 2).mean())
    psnr = float("inf") if mse == 0 else 10 * math.log10(255.0 ** 2 / mse)
    diff_share = float((d > 0).mean()) * 100
    print(f"size {a.shape[1]}x{a.shape[0]} max {mx} mean {mean:.5f} "
          f"psnr {psnr:.2f} differing_values_pct {diff_share:.4f}")
    return 0 if mx <= tol and (mean_tol is None or mean <= mean_tol) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
