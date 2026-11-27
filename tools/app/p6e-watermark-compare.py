#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Brief P6e: a text watermark rendered by the app (emulator) and by darktable-cli (Linux) for the same XMP.

    python3 -I tools/app/p6e-watermark-compare.py APP_ON APP_OFF CLI_ON CLI_OFF

Each side's text is the set of pixels whose largest channel difference between the export with the watermark and
the export of the same XMP with the watermark switched off is above 24 (8-bit). Prints both counts, their ratio,
the overlap of the two sets (intersection over union), the centre of each set, and the PSNR between the two exports
with the watermark. Passes (exit 0) when both sides draw more than 500 pixels, the counts are within a factor 1.5 and
the sets overlap by at least 0.5: the same text in the same place, with room for different font rasterisation
(FreeType versions, hinting) on the two systems.
"""
import math
import sys

import numpy as np
from PIL import Image


def load(p):
    return np.asarray(Image.open(p).convert("RGB")).astype(np.int32)


def main(a):
    if len(a) != 5:
        print(__doc__)
        return 2
    app_on, app_off, cli_on, cli_off = (load(p) for p in a[1:])
    if not (app_on.shape == app_off.shape == cli_on.shape == cli_off.shape):
        print(f"p6e watermark: sizes differ: app {app_on.shape} {app_off.shape}, cli {cli_on.shape} {cli_off.shape}")
        return 1
    ma = np.abs(app_on - app_off).max(axis=2) > 24
    mc = np.abs(cli_on - cli_off).max(axis=2) > 24
    na, nc = int(ma.sum()), int(mc.sum())
    union = int((ma | mc).sum())
    iou = (ma & mc).sum() / union if union else 0.0
    ratio = na / nc if nc else float("inf")

    def centre(m):
        ys, xs = np.nonzero(m)
        return (float(xs.mean()), float(ys.mean())) if len(xs) else (float("nan"), float("nan"))

    mse = ((app_on - cli_on) ** 2).mean()
    psnr = float("inf") if mse == 0 else 10 * math.log10(255 ** 2 / mse)
    ok = na > 500 and nc > 500 and 1 / 1.5 <= ratio <= 1.5 and iou >= 0.5
    ca, cc = centre(ma), centre(mc)
    print(f"p6e watermark: text pixels app {na}, darktable-cli {nc} (ratio {ratio:.3f}), overlap {iou:.3f}, "
          f"centre app ({ca[0]:.1f}, {ca[1]:.1f}) cli ({cc[0]:.1f}, {cc[1]:.1f}), psnr with watermark {psnr:.2f} dB: "
          f"{'ok' if ok else 'bad'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
